package com.eggman.backstage.exec

import com.eggman.backstage.agent.PromptContext
import com.eggman.backstage.agent.SystemPrompt
import com.eggman.backstage.tools.TestToolContext
import com.eggman.backstage.tools.ToolRegistry
import com.eggman.backstage.testutil.FakeServices
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale

/** generic requests use the user's default apps, never a hard-coded browser. */
class DefaultAppsTest {
    private val sbrowser = "com.sec.android.app.sbrowser"
    private val chrome = "com.android.chrome"
    private val firefox = "org.mozilla.firefox"

    /** A phone: [defaults] = what Android resolves per probe action+data, [handlers] per probe, [system] packages. */
    private class FakePlatform(
        val defaults: Map<String, String> = emptyMap(),
        val handlers: Map<String, List<String>> = emptyMap(),
        val system: Set<String> = emptySet(),
        val holders: Map<AppRole, String> = emptyMap(),
        override val manufacturer: String = "samsung",
        override val sdkInt: Int = 34,
    ) : DefaultAppsPlatform {
        private fun key(p: IntentProbe) = p.action + "|" + (p.data ?: "") + "|" + p.categories.joinToString()
        override fun roleHolder(role: AppRole) = holders[role]
        override fun resolvedDefault(probe: IntentProbe) = defaults[key(probe)]
        override fun handlers(probe: IntentProbe) = handlers[key(probe)].orEmpty()
        override fun label(packageName: String) = LABELS[packageName]
        override fun isSystem(packageName: String) = packageName in system

        companion object {
            val LABELS = mapOf(
                "com.sec.android.app.sbrowser" to "Samsung Internet", "com.android.chrome" to "Chrome",
                "org.mozilla.firefox" to "Firefox", "com.google.android.apps.maps" to "Maps",
                "com.samsung.android.dialer" to "Phone", "com.sec.android.app.popupcalculator" to "Calculator",
                "com.google.android.calculator" to "Calculator (Google)",
            )
            fun browserKey() = "android.intent.action.VIEW|https://example.com/|android.intent.category.BROWSABLE"
            fun calculatorKey() = "android.intent.action.MAIN||android.intent.category.APP_CALCULATOR"
        }
    }

    @Test fun theUsersDefaultBrowserIsUsedEvenWhenChromeIsInstalled() {
        val p = FakePlatform(
            defaults = mapOf(FakePlatform.browserKey() to firefox),
            handlers = mapOf(FakePlatform.browserKey() to listOf(chrome, sbrowser, firefox)),
            system = setOf(chrome, sbrowser),
        )
        val app = DefaultAppsResolver.resolveRole(p, AppRole.BROWSER)!!
        assertEquals(firefox, app.packageName)
        assertEquals(DefaultHow.DEFAULT, app.how)
    }

    @Test fun noDefaultPicksTheMakersSystemAppAndSaysSo() {
        // Android would show a chooser: resolveActivity answers with the system's resolver.
        val p = FakePlatform(
            defaults = mapOf(FakePlatform.browserKey() to "android"),
            handlers = mapOf(FakePlatform.browserKey() to listOf(chrome, firefox, sbrowser)),
            system = setOf(chrome, sbrowser),
        )
        val app = DefaultAppsResolver.resolveRole(p, AppRole.BROWSER)!!
        assertEquals(sbrowser, app.packageName)
        assertEquals(DefaultHow.CHOSEN, app.how)
        assertTrue(app.others.containsAll(listOf("Chrome", "Firefox")))
        assertTrue(app.line().contains("no default is set"))
        // On another maker's phone a preinstalled app still beats a downloaded one.
        val other = FakePlatform(defaults = mapOf(FakePlatform.browserKey() to "android"),
            handlers = mapOf(FakePlatform.browserKey() to listOf(firefox, chrome)), system = setOf(chrome), manufacturer = "Google")
        assertEquals(chrome, DefaultAppsResolver.resolveRole(other, AppRole.BROWSER)!!.packageName)
    }

    @Test fun missingOnlyAndRoleHolders() {
        val p = FakePlatform(
            handlers = mapOf(FakePlatform.calculatorKey() to listOf("com.sec.android.app.popupcalculator")),
            holders = mapOf(AppRole.DIALER to "com.samsung.android.dialer"),
        )
        assertNull(DefaultAppsResolver.resolveRole(p, AppRole.MAPS))
        val calc = DefaultAppsResolver.resolveRole(p, AppRole.CALCULATOR)!!
        assertEquals(DefaultHow.ONLY, calc.how)
        assertEquals(DefaultHow.DEFAULT, DefaultAppsResolver.resolveRole(p, AppRole.DIALER)!!.how)
        // Our own app and the system resolver are never anybody's default.
        val own = FakePlatform(defaults = mapOf(FakePlatform.browserKey() to "com.eggman.backstage"),
            handlers = mapOf(FakePlatform.browserKey() to listOf("com.eggman.backstage", "android")))
        assertNull(DefaultAppsResolver.resolveRole(own, AppRole.BROWSER, setOf("com.eggman.backstage")))
        val all = DefaultAppsResolver.resolve(p)
        assertEquals(setOf(AppRole.CALCULATOR, AppRole.DIALER), all.map { it.role }.toSet())
    }

    @Test fun genericWordsMapToRolesAppNamesDoNot() {
        assertEquals(AppRole.BROWSER, AppRole.forQuery("browser"))
        assertEquals(AppRole.BROWSER, AppRole.forQuery("the Web Browser"))
        assertEquals(AppRole.MAPS, AppRole.forQuery("maps"))
        assertEquals(AppRole.CAMERA, AppRole.forQuery("Camera"))
        assertEquals(AppRole.CALCULATOR, AppRole.forQuery("calculator"))
        assertEquals(AppRole.EMAIL, AppRole.forQuery("e-mail"))
        assertNull(AppRole.forQuery("Chrome"))
        assertNull(AppRole.forQuery("Samsung Internet"))
        assertNull(AppRole.forQuery("Google Maps"))
        assertEquals(AppRole.BROWSER, AppRole.forScheme("https"))
        assertEquals(AppRole.EMAIL, AppRole.forScheme("mailto"))
    }

    @Test fun linksArePinnedOnlyWhenAndroidWouldAsk() {
        val defaults = listOf(DefaultApp(AppRole.BROWSER, sbrowser, "Samsung Internet", DefaultHow.CHOSEN))
        val p = FakePlatform(system = setOf(sbrowser))
        // A default (or an app that verified the link) handles it: Android decides.
        assertNull(DefaultAppsResolver.pinForLink("https", chrome, listOf(chrome, sbrowser), defaults, p, emptySet()))
        // Chooser: the default app's place is taken by the picked browser.
        assertEquals(sbrowser, DefaultAppsResolver.pinForLink("https", "android", listOf(chrome, sbrowser), defaults, p, emptySet()))
        // Unknown role: the best handler.
        assertEquals(sbrowser, DefaultAppsResolver.pinForLink("foo", null, listOf(firefox, sbrowser), emptyList(), p, emptySet()))
        assertNull(DefaultAppsResolver.pinForLink("https", null, emptyList(), defaults, p, emptySet()))
    }

    private fun prompt(defaultApps: String) = SystemPrompt.build(
        PromptContext(TargetKind.PHONE, null, false, false, true, true, true, 0L, ZoneId.of("UTC"), Locale.ENGLISH, defaultApps = defaultApps),
    )

    @Test fun thePromptCarriesTheDefaultAppsList() {
        val lines = DefaultAppsResolver.promptLines(listOf(
            DefaultApp(AppRole.MAPS, "com.google.android.apps.maps", "Maps", DefaultHow.DEFAULT),
            DefaultApp(AppRole.BROWSER, sbrowser, "Samsung Internet", DefaultHow.DEFAULT),
        ))
        val p = prompt(lines)
        assertTrue(p.contains("## Default apps"))
        assertTrue(p.contains("- browser (web browser): Samsung Internet ($sbrowser)"))
        assertTrue(p.indexOf("- browser") < p.indexOf("- maps"))
        assertTrue(p.contains("{\"app\":\"browser\"}"))
        // The rule stands without a list too.
        assertTrue(prompt("").contains("## Default apps"))
    }

    @Test fun launchAppMapsCategoriesToTheDefaultAndHonoursNamedApps() {
        val services = FakeServices(apps = listOf(com.eggman.backstage.exec.AppEntry("Chrome", chrome),
            com.eggman.backstage.exec.AppEntry("Samsung Internet", sbrowser), com.eggman.backstage.exec.AppEntry("Browser Lite", "com.example.browser")))
        services.defaults = listOf(DefaultApp(AppRole.BROWSER, sbrowser, "Samsung Internet", DefaultHow.DEFAULT))
        val reg = ToolRegistry.default()
        fun run(args: String): Pair<TestToolContext, com.eggman.backstage.tools.ToolResult> {
            val ctx = TestToolContext(services = services)
            return ctx to runBlocking { reg.execute(ctx, "launch_app", JSONObject(args)) }
        }
        val (a, ra) = run("""{"app":"browser"}""")
        assertEquals("launch $sbrowser", a.phone.actions.single())
        assertTrue(ra.text.contains("the user's web browser"))
        val (b, _) = run("""{"app":"Chrome"}""")
        assertEquals("launch $chrome", b.phone.actions.single())
        // No default known: the category word falls back to name matching, never to a fixed package.
        services.defaults = emptyList()
        val (c, _) = run("""{"app":"camera"}""")
        assertTrue(c.phone.actions.isEmpty())
        val list = runBlocking { reg.execute(TestToolContext(services = services.also {
            it.defaults = listOf(DefaultApp(AppRole.BROWSER, sbrowser, "Samsung Internet", DefaultHow.DEFAULT))
        }), "default_apps", JSONObject()) }
        assertTrue(list.text.contains("Samsung Internet"))
    }

    @Test fun noHardCodedBrowserInToolsPromptOrSources() {
        val bias = Regex("""com\.android\.chrome|\\?"Chrome\\?"|google\.com/search""", RegexOption.IGNORE_CASE)
        ToolRegistry.default().tools.forEach { t ->
            assertFalse("${t.name} description", bias.containsMatchIn(t.description))
            assertFalse("${t.name} schema", bias.containsMatchIn(t.schema.toString()))
        }
        assertFalse(bias.containsMatchIn(prompt("")))
        val main = listOf(File("src/main/kotlin"), File("backstage/android/src/main/kotlin"), File("android/src/main/kotlin")).firstOrNull { it.isDirectory }
        // the browser_search presets are engines the user picks in Settings; the default is the browser's own.
        assertEquals("", com.eggman.backstage.tools.BrowserSearch.PRESETS.first().template)
        assertEquals("", com.eggman.backstage.tools.ModuleConfig().browserSearchUrl)
        if (main != null) main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "SearchEngines.kt" }.forEach { f ->
            assertFalse(f.path, bias.containsMatchIn(f.readText()))
        }
    }
}
