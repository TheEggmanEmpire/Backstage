package com.eggman.backstage.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * the Backstage icon where the Backstage icon is meant (no generic robot glyph), and light glyphs on the dark
 * discs and sheets ("icons are black on grey, should be white on grey like the send icon").
 */
class IconResourcesTest {
    private fun dir(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.isDirectory } ?: error("none of ${candidates.toList()} found from ${File(".").absolutePath}")

    private val main get() = dir("src/main", "backstage/android/src/main", "android/src/main")
    private val overlayRes get() = dir("../overlay/res", "backstage/overlay/res", "overlay/res")
    private val patches get() = dir("../../patches", "patches", "../patches")

    @Test fun theBubbleShowsTheBackstageAppIconAndIsCalledBackstage() {
        assertEquals(com.eggman.backstage.R.drawable.bs_ic_launcher_round, BubbleModel.ICON)
        assertEquals("Backstage", BubbleModel.LABEL)
        // The round rendition is the launcher icon's own artwork: its background colour and every foreground path.
        val round = File(main, "res/drawable/bs_ic_launcher_round.xml").readText()
        val fg = File(main, "res/drawable/bs_ic_launcher_fg.xml").readText()
        val paths = Regex("pathData=\"([^\"]+)\"").findAll(fg).map { it.groupValues[1] }.toList()
        assertTrue(paths.isNotEmpty())
        paths.forEach { assertTrue("foreground path missing from the round icon", round.contains(it)) }
        val bg = Regex("fillColor=\"(#[0-9A-F]{8})\"").find(File(main, "res/drawable/bs_ic_launcher_bg.xml").readText())!!.groupValues[1]
        assertTrue(round.contains("android:fillColor=\"$bg\""))
    }

    @Test fun noRobotGlyphWhereTheBackstageIconIsMeant() {
        val sources = File(main, "kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 50)
        val robots = sources.filter { it.readText().contains("SmartToy") }
        assertTrue("robot glyph in ${robots.map { it.name }}", robots.isEmpty())
        // The home-screen widgets show the app icon; every drawable reference in Kotlin points at a Backstage icon or the GitHub badge.
        assertTrue(File(main, "res/layout/bs_widget_button.xml").readText().contains("@drawable/bs_ic_launcher_round"))
        val refs = sources.flatMap { f -> Regex("R\\.drawable\\.(\\w+)").findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals(setOf("bs_ic_agent", "bs_ic_launcher_round", "bs_nav_penguin", "bs_nav_penguin_filled", "get_it_on_github"), refs)
    }

    /**
     * Glyphs drawn on dark discs or as one-colour system icons (24-unit vectors: the popup's button row from patch 0001,
     * the notification/tile mark, the Quick Settings overlay) are white, never black or a dark colour.
   */
    @Test fun glyphVectorsAreLight() {
        val files = buildMap {
            File(main, "res/drawable").listFiles()!!.filter { it.extension == "xml" }.forEach { put(it.name, it.readText()) }
            File(overlayRes, "drawable").listFiles()!!.filter { it.extension == "xml" }.forEach { put("overlay/" + it.name, it.readText()) }
            // New drawables of patch 0001 (the popup's button row), from the patch text.
            val patch = File(patches, "0001-mirrorservice-presentation.patch").readText()
            Regex("\\+\\+\\+ b/(\\S+/res/drawable/backstage_ic_\\w+\\.xml)\\n@@[^\\n]*\\n((?:\\+[^\\n]*\\n)+)").findAll(patch).forEach { m ->
                put(m.groupValues[1], m.groupValues[2].lines().joinToString("\n") { it.removePrefix("+") })
            }
        }
        val glyphs = files.filter { (_, xml) -> xml.contains("<vector") && xml.contains("android:viewportWidth=\"24\"") }
        assertTrue("popup row glyphs not found: ${files.keys}", glyphs.keys.map { it.substringAfterLast('/') }.filter { it.startsWith("backstage_ic_") }.toSet().size == 5)
        glyphs.forEach { (name, xml) ->
            val colours = Regex("android:(?:fillColor|strokeColor)=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
            assertTrue("$name has no colour", colours.isNotEmpty())
            colours.forEach { c -> assertTrue("$name uses $c (glyphs must be white or a theme attribute)", c.uppercase() == "#FFFFFFFF" || c.startsWith("?")) }
        }
    }
}
