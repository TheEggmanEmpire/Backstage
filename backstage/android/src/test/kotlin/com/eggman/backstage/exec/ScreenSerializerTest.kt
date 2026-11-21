package com.eggman.backstage.exec

import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.testutil.Screens
import com.eggman.backstage.testutil.Screens.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSerializerTest {
    private val s = ScreenSerializer(ownPackages = setOf("com.eggman.backstage"))

    @Test fun keepsActionableAndTextNodesWithIndicesAndCenters() {
        val cap = Screens.app(
            node(text = "Inbox", b = Box(0, 0, 1080, 100)),
            node(cls = "android.widget.FrameLayout", b = Box(0, 100, 1080, 200)), // empty container: dropped
            node(text = "Compose", cls = "android.widget.Button", b = Box(800, 2000, 1000, 2100), clickable = true, id = "com.example:id/fab"),
            node(cls = "android.widget.EditText", b = Box(0, 300, 1080, 400), editable = true, hint = "Search mail", focused = true),
        )
        val st = s.serialize(cap, "phone")
        assertEquals(3, st.elements.size)
        assertEquals("Inbox", st.elements[0].label)
        val fab = st.elements[1]
        assertEquals(1, fab.index)
        assertEquals("fab", fab.resourceId)
        assertEquals(900, fab.centerX); assertEquals(2050, fab.centerY)
        assertTrue(fab.traits.contains("click"))
        assertTrue(st.elements[2].editable)
        assertTrue(st.text.contains("[1] Button \"Compose\" id=fab (900,2050) click"))
        assertTrue(st.text.contains("hint=\"Search mail\""))
        assertTrue(st.text.contains("edit,focused"))
        assertEquals("com.example", st.foregroundPackage)
    }

    @Test fun clickableContainerTakesLabelFromChildrenWithoutDuplicates() {
        val row = node(cls = "android.widget.LinearLayout", b = Box(0, 500, 1080, 650), clickable = true, children = listOf(
            node(text = "Anna", b = Box(150, 510, 600, 560)),
            node(text = "See you at 8", b = Box(150, 570, 900, 630)),
            node(desc = "Delete", cls = "android.widget.ImageButton", b = Box(950, 540, 1050, 620), clickable = true),
        ))
        val st = s.serialize(Screens.app(row), "phone")
        assertEquals(2, st.elements.size)
        assertEquals("Anna · See you at 8", st.elements[0].label)
        assertEquals("Delete", st.elements[1].label)
    }

    @Test fun invisibleOffscreenAndOwnOverlayAreSkipped() {
        val cap = Screens.app(
            node(text = "hidden", b = Box(0, 0, 100, 100), visible = false),
            node(text = "below", b = Box(0, 3000, 100, 3100)),
            node(text = "partly", b = Box(1000, 2300, 1200, 2500), clickable = true),
        ).let { c ->
            c.copy(windows = c.windows + UiWindow(9, WindowKind.APPLICATION, "bubble", "com.eggman.backstage", layer = 50,
                bounds = Box(0, 0, 200, 200), root = node(text = "Backstage bubble", b = Box(0, 0, 200, 200), clickable = true)))
        }
        val st = s.serialize(cap, "phone")
        assertEquals(listOf("partly"), st.elements.map { it.label })
        // clipped to the display
        assertEquals(Box(1000, 2300, 1080, 2400), st.elements[0].bounds)
        assertFalse(st.text.contains("Backstage bubble"))
    }

    @Test fun elementsUnderHigherWindowsAreCoveredAndKeyboardDetected() {
        val base = Screens.app(
            node(text = "Top", cls = "android.widget.Button", b = Box(0, 100, 1080, 200), clickable = true),
            node(text = "Under keyboard", cls = "android.widget.Button", b = Box(0, 2000, 1080, 2100), clickable = true),
        )
        val withIme = base.copy(windows = base.windows + UiWindow(2, WindowKind.INPUT_METHOD, "Gboard", "com.google.android.inputmethod.latin",
            layer = 10, bounds = Box(0, 1500, 1080, 2400), root = node(text = "q", b = Box(0, 1500, 100, 1600), clickable = true)))
        val st = s.serialize(withIme, "phone")
        assertTrue(st.keyboardVisible)
        assertEquals(listOf("Top"), st.elements.map { it.label })
        assertEquals(1, st.coveredElements)
        assertTrue(st.text.contains("keyboard is open"))
        assertTrue(st.text.contains("hidden behind other windows"))
    }

    @Test fun dialogWindowListedFirstAndCoversApp() {
        val base = Screens.buttons("Behind")
        val dialog = UiWindow(3, WindowKind.SYSTEM, "Permission", "com.google.android.permissioncontroller", layer = 20,
            bounds = Box(100, 0, 980, 2400), root = node(text = "Allow", cls = "android.widget.Button", b = Box(200, 1000, 880, 1100), clickable = true))
        val st = s.serialize(base.copy(windows = base.windows + dialog), "phone")
        assertEquals("Allow", st.elements[0].label)
        assertEquals(1, st.elements.size)
    }

    @Test fun truncationIsReported() {
        val many = (0 until 50).map { node(text = "Row $it", cls = "android.widget.Button", b = Box(0, it * 40, 1080, it * 40 + 40), clickable = true) }
        val small = ScreenSerializer(maxElements = 10)
        val st = small.serialize(Screens.app(*many.toTypedArray()), "phone")
        assertEquals(10, st.elements.size)
        assertEquals(40, st.omittedElements)
        assertTrue(st.text.contains("40 more elements"))
        val tiny = ScreenSerializer(maxChars = 400).serialize(Screens.app(*many.toTypedArray()), "phone")
        assertTrue(tiny.text.length < 600)
        assertTrue(tiny.text.contains("output truncated"))
    }

    @Test fun fingerprintStableUnderTinyMovesButChangesWithContent() {
        val a = s.serialize(Screens.buttons("Alpha", "Beta"), "phone")
        val b = s.serialize(Screens.buttons("Alpha", "Beta"), "phone")
        val c = s.serialize(Screens.buttons("Alpha", "Gamma"), "phone")
        assertEquals(a.fingerprint, b.fingerprint)
        assertNotEquals(a.fingerprint, c.fingerprint)
    }

    @Test fun emptyScreenHintsAtScreenshotAndJsonExport() {
        val st = s.serialize(ScreenCapture(0, 1080, 2400, emptyList()), "desktop")
        assertTrue(st.text.contains("no accessible elements"))
        val j = ScreenSerializer.toJson(s.serialize(Screens.buttons("Go"), "phone"))
        assertEquals("phone", j.getString("target"))
        val el = j.getJSONArray("elements").getJSONObject(0)
        assertEquals("Go", el.getString("label"))
        assertEquals(4, el.getJSONArray("bounds").length())
        assertNull(st.element(5))
    }

    @Test fun passwordFieldIsMasked() {
        val st = s.serialize(Screens.app(UiNode(className = "android.widget.EditText", text = "hunter2", password = true, editable = true,
            bounds = Box(0, 0, 500, 100))), "phone")
        assertEquals("••••", st.elements[0].label)
        assertFalse(st.text.contains("hunter2"))
    }
}

class TargetResolverTest {
    private fun ctx(
        requested: Target = Target.AUTO, prompt: String = "open gmail", source: Source = Source.CHAT, host: Boolean = true,
        active: Boolean = false, priv: Boolean = true, autoStart: Boolean = true, def: Target = Target.AUTO, conv: TargetKind? = null,
    ) = TargetContext(requested, def, source, prompt, host, active, priv, autoStart, conv)

    @Test fun explicitTargetsWin() {
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(requested = Target.PHONE, active = true)).kind)
        val d = TargetResolver.resolve(ctx(requested = Target.DESKTOP))
        assertEquals(TargetKind.DESKTOP, d.kind)
        assertTrue(d.needsDesktopStart)
        assertNull(TargetResolver.resolve(ctx(requested = Target.DESKTOP, host = false)).kind)
        assertNull(TargetResolver.resolve(ctx(requested = Target.DESKTOP, priv = false)).kind)
    }

    @Test fun autoPrefersDesktopWhenAvailable() {
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(active = true)).kind)
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(active = false, priv = true)).kind)
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(active = false, priv = false)).kind)
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(host = false)).kind)
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(active = false, autoStart = false)).kind)
    }

    @Test fun phoneReferencesAndOnScreenSurfaces() {
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(active = true, prompt = "Tap the like button on my screen")).kind)
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(active = true, source = Source.ASSIST, prompt = "summarize this")).kind)
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(active = true, source = Source.ASSIST, prompt = "order pizza")).kind)
    }

    @Test fun defaultsAndConversationStickiness() {
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(active = true, def = Target.PHONE)).kind)
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(active = true, conv = TargetKind.PHONE)).kind)
        // desktop requested in words but not startable: falls back to the default target
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(active = false, priv = false, def = Target.PHONE, prompt = "do it in the background on the desktop")).kind)
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(active = true, def = Target.PHONE, prompt = "do it in the background")).kind)
    }

    @Test fun desktopHintsAreWholePhrasesNotSubstrings() {
        // These are phone requests even though they contain "dex", "background" or "desktop" as text.
        for (p in listOf("find the index page", "change the background colour", "open the desktop site of reddit", "set a desktop wallpaper")) {
            assertFalse(p, TargetResolver.mentionsDesktop(p))
            assertEquals(p, TargetKind.PHONE, TargetResolver.resolve(ctx(active = true, def = Target.PHONE, prompt = p)).kind)
        }
        for (p in listOf("do it in the background", "open YouTube on DeX", "run it on the desktop", "Mach das im Hintergrund")) {
            assertTrue(p, TargetResolver.mentionsDesktop(p))
        }
    }
}

class AppCatalogAndKeysTest {
    private val apps = listOf(AppEntry("Google Maps", "com.google.android.apps.maps"), AppEntry("Gmail", "com.google.android.gm"),
        AppEntry("WhatsApp", "com.whatsapp"), AppEntry("Maps Lite", "com.example.mapslite"))

    @Test fun matchRanking() {
        assertEquals("com.google.android.gm", AppCatalog.match(apps, "gmail").first().packageName)
        assertEquals("com.whatsapp", AppCatalog.match(apps, "com.whatsapp").first().packageName)
        assertEquals("Maps Lite", AppCatalog.match(apps, "maps").first().label) // label prefix beats word prefix
        assertEquals("Google Maps", AppCatalog.match(apps, "google maps").first().label)
        assertEquals("WhatsApp", AppCatalog.match(apps, "whatsap").first().label)
        assertEquals("WhatsApp", AppCatalog.match(apps, "whatsaap").firstOrNull()?.label) // fuzzy
        assertTrue(AppCatalog.match(apps, "telegram").isEmpty())
        assertEquals(1, AppCatalog.editDistance("kitten", "kittn"))
    }

    @Test fun keysAndChords() {
        assertEquals(66, KeyNames.code("Enter"))
        assertEquals(66, KeyNames.code("KEYCODE_ENTER"))
        assertEquals(4, KeyNames.chord("back")!!.first)
        val (code, meta) = KeyNames.chord("ctrl+shift+t")!!
        assertEquals(29 + ('t' - 'a'), code)
        assertTrue(meta and 0x1000 != 0 && meta and 0x1 != 0)
        assertEquals(131, KeyNames.chord("f1")!!.first)
        assertNull(KeyNames.chord("hyper+x"))
        assertNull(KeyNames.chord("nosuchkey"))
    }

    @Test fun screenImageScaling() {
        val img = ScreenImage("x", "image/jpeg", 540, 1200, 1080, 2400)
        assertEquals(2.0, img.scaleX, 1e-9)
        assertEquals(1080f to 2000f, img.toDisplay(540.0, 1000.0))
    }
}
