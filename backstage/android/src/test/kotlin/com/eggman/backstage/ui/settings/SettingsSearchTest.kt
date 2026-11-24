package com.eggman.backstage.ui.settings

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun synonymsStemmingAndTyposFindTheExpectedSettings() {
        mapOf(
            "token" to "API key", "approve" to "Confirm before irreversible actions",
            "export" to "Backup", "dex" to "Desktop popup", "pictures" to "Screenshots",
            "plann" to "Plan Mode", "modde" to "Plan Mode", "confrm" to "When nobody can confirm", "PLANS" to "Plan Mode", "planning" to "Plan Mode",
            "credentials" to "API key", "microphone" to "Wake word",
            "plugins" to "Skills", "linux" to "App settings",
        ).forEach { (query, title) ->
            assertTrue("$query should find $title", SettingsSearch.search(query).any { it.title == title })
        }
    }

    @Test fun everyWordMustMatchAndTitleHitsRankFirst() {
        assertEquals("API key", SettingsSearch.search("api key").first().title)
        assertEquals("Plan Mode", SettingsSearch.search("plan").first().title)
        assertTrue(SettingsSearch.search("plan banana").isEmpty())
        assertTrue(SettingsSearch.search("  \t").isNotEmpty())
        assertTrue(SettingsSearch.search("zzzzzzzz").isEmpty())
    }

    @Test fun catalogueHasUniqueIdsAndCoversEveryGroup() {
        val entries = SettingsSearch.entries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(SettingsGroup.entries.filterNot { PLAY_DISTRIBUTION && it == SettingsGroup.OMP }.toSet(), entries.map { it.group }.toSet())
        assertTrue(entries.all { it.title.isNotBlank() && it.description.isNotBlank() && it.keywords.isNotEmpty() })
    }

    @Test fun companionSettingsRequireTheirOwnCapabilities() {
        if (!PLAY_DISTRIBUTION) return
        val titles = mapOf(
            "ADB commands" to CompanionSetting.SHELL,
            "Run skill scripts" to CompanionSetting.SKILL_SCRIPTS,
            "Detector packages" to CompanionSetting.ACCESSIBILITY,
            "Enable OMP harness" to CompanionSetting.OMP,
        )
        titles.forEach { (title, capability) ->
            assertFalse(SettingsSearch.search(title).any { it.title == title })
            assertTrue(SettingsSearch.search(title, companionSettings = setOf(capability)).any { it.title == title })
        }
    }

    @Test fun everyLiteralSettingControlInTheUiHasSearchMetadata() {
        // Scan real controls so adding a UI field without indexing it fails this test.
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { it.resolve("android/src/main/kotlin/com/eggman/backstage") }
            .firstOrNull { it.isDirectory }
            ?: java.io.File("src/main/kotlin/com/eggman/backstage")
        val channel = if (PLAY_DISTRIBUTION) "play" else "github"
        val variant = root.resolve("../../../../../$channel/kotlin/com/eggman/backstage").normalize()
        val shared = listOf("SettingsScreen.kt", "ModulesSettings.kt")
        val githubOnly = listOf("OmpSettings.kt", "SkillScriptsSettings.kt")
        val sources = (shared + if (PLAY_DISTRIBUTION) emptyList() else githubOnly)
            .map { root.resolve("ui/settings/$it") } + variant.resolve("ui/settings/AccessibilitySection.kt")
        assertTrue("Settings sources must be available", sources.all { it.isFile })
        val controls = Regex("""(?:SettingToggle|ToggleRow|NumberSetting)\(\s*"([^"$]+)"""")
        val labels = Regex("""label = \{ Text\("([^"$]+)"\)""")
        val titles = sources.flatMap { file ->
            val source = file.readText()
            (controls.findAll(source) + labels.findAll(source)).map { it.groupValues[1] }.toList()
        }.filterNot { it == "Search settings" }
        assertTrue(titles.size >= 40)
        val missing = titles.filter { title -> SettingsSearch.search("", companionSettings = CompanionSetting.entries.toSet()).none {
            title in it.title || title in it.description
        } }
        assertEquals("Every setting must be searchable", emptyList<String>(), missing)
    }

    @Test fun shortQueriesDoNotGetFuzzyMatchesAndRankingIsStable() {
        assertTrue(SettingsSearch.search("adq").isEmpty())
        assertTrue(SettingsSearch.search("????").isEmpty())
        assertTrue(SettingsSearch.search("日本語").isEmpty())
        assertEquals(SettingsSearch.search("permission"), SettingsSearch.search("PERMISSION"))
        assertTrue(SettingsSearch.search("approving").any { it.title == "Confirm before irreversible actions" })
        assertTrue(SettingsSearch.search("tokens secret").any { it.title == "API key" })
        assertEquals(SettingsSearch.entries, SettingsSearch.search(""))
    }

    @Test fun allProviderAndBrowserChoicesAreDiscoverable() {
        com.eggman.backstage.data.Provider.entries.forEach { provider ->
            assertTrue(provider.label, SettingsSearch.search(provider.label).any { it.group == SettingsGroup.MODEL })
        }
        listOf("Kagi", "DuckDuckGo", "Google", "Bing", "Startpage").forEach { engine ->
            assertTrue(engine, SettingsSearch.search(engine).any { it.title == "Browser searches" })
        }
        listOf("English", "Deutsch", "Français", "Español", "Italiano").forEach { language ->
            assertTrue(language, SettingsSearch.search(language).any { it.title == "Speech model" })
        }
    }

    @Test fun dynamicallyRegisteredMcpToolsAreSearchableWithoutAndroidDependencies() {
        val extra = SettingsSearch.mcpTools(listOf("desktop_right_click", "tap_screen", "desktop_right_click"))
        assertEquals(2, extra.size)
        assertEquals("desktop_right_click", SettingsSearch.search("right click", extra).first().title)
        assertEquals("tap_screen", SettingsSearch.search("tap screen", extra).first().title)
        assertTrue(extra.all { it.group == SettingsGroup.CONNECTIONS })
    }
}
