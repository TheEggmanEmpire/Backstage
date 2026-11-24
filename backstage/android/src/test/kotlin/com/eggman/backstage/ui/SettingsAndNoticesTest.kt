package com.eggman.backstage.ui

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.data.InMemoryMemory
import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.NonInteractiveConfirm
import com.eggman.backstage.data.RemoteMcpServer
import com.eggman.backstage.data.Settings
import com.eggman.backstage.exec.AppEntry
import com.eggman.backstage.privileged.ShizukuState
import com.eggman.backstage.tools.HttpResult
import com.eggman.backstage.ui.licenses.Licenses
import com.eggman.backstage.ui.onboarding.AccessibilityDisclosure
import com.eggman.backstage.ui.settings.CoreSettingsStore
import com.eggman.backstage.ui.settings.ModuleSettingsLogic
import com.eggman.backstage.ui.settings.UNATTENDED_ORDER
import com.eggman.backstage.ui.settings.unattendedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Consent, module settings, memories, remote MCP and legal notices. */
class SettingsAndNoticesTest {

    // ---- accessibility consent ----------------------------------------------------------
    @Test fun nothingAutoEnablesAccessibilityWithoutConsent() {
        val s = Settings(MemoryStore())
        assertEquals(!PLAY_DISTRIBUTION, s.autoEnableAccessibility)
        assertFalse("consent is not presumed", s.accessibilityConsent)
        assertFalse(s.mayAutoEnableAccessibility)
        s.accessibilityConsent = true
        assertEquals(!PLAY_DISTRIBUTION, s.mayAutoEnableAccessibility)
        s.autoEnableAccessibility = false
        assertFalse(s.mayAutoEnableAccessibility)
        // Withdrawing consent stops it even with auto-enable on.
        s.autoEnableAccessibility = true
        CoreSettingsStore(s).accessibilityConsent = false
        assertFalse(s.mayAutoEnableAccessibility)
    }

    @Test fun shizukuSetupRunsOnlyAfterConsentAndOtherwisePromptsOnce() {
        val ready = ShizukuState.READY
        assertFalse(AccessibilityDisclosure.autoSetupAllowed(ready, setupDone = false, consent = false))
        assertTrue(AccessibilityDisclosure.autoSetupAllowed(ready, setupDone = false, consent = true))
        assertFalse(AccessibilityDisclosure.autoSetupAllowed(ready, setupDone = true, consent = true))
        assertFalse(AccessibilityDisclosure.autoSetupAllowed(ShizukuState.PERMISSION_NEEDED, setupDone = false, consent = true))
        assertTrue(AccessibilityDisclosure.promptAutomatically(ready, setupDone = false, consent = false, declinedHere = false))
        assertFalse(AccessibilityDisclosure.promptAutomatically(ready, setupDone = false, consent = false, declinedHere = true))
        assertFalse(AccessibilityDisclosure.promptAutomatically(ready, setupDone = false, consent = true, declinedHere = false))
    }

    @Test fun disclosureSaysWhatIsReadWhatIsDoneAndWhereItGoes() {
        val text = AccessibilityDisclosure.POINTS.joinToString(" ")
        // One consent for the app's one accessibility service, which runs the desktop and serves the agent.
        listOf("One service, two jobs", "runs the desktop", "What is read", "What is done", "Where it goes", "AI provider you choose", "withdraw").forEach {
            assertTrue(it, text.contains(it))
        }
    }

    // ---- settings reachable from the UI -------------------------------------------------
    @Test fun storeExposesEveryModuleSettingAndValidates() {
        val s = Settings(MemoryStore())
        val st = CoreSettingsStore(s)
        st.weatherApiKey = " k1 "
        st.weatherUnits = "imperial"
        st.weatherDefaultLocation = "Berlin, DE"
        assertEquals("k1", s.moduleConfig().weatherApiKey)
        assertEquals("imperial", s.moduleConfig().weatherUnits)
        st.weatherUnits = "kelvin"
        assertEquals("metric", st.weatherUnits)
        st.searchProvider = "Brave"
        st.searchApiKey = "bk"
        assertEquals("brave", s.moduleConfig().searchProvider)
        assertEquals("bk", s.moduleConfig().searchApiKey)
        st.searchProvider = "bing"
        assertEquals("duckduckgo", st.searchProvider)
        st.searxngUrl = "https://sx.example.org"
        assertEquals("https://sx.example.org", s.moduleConfig().searxngUrl)
        st.searxngUrl = ""
        assertEquals("", s.moduleConfig().searxngUrl)
        st.memoryEnabled = false
        assertFalse(s.moduleConfig().memoryEnabled)
        st.screenshotQuality = 5
        st.screenshotScale = 500
        assertEquals(30, s.screenshotQuality)
        assertEquals(100, s.screenshotScale)
        st.nonInteractiveConfirm = NonInteractiveConfirm.DENY
        assertEquals(NonInteractiveConfirm.DENY, s.nonInteractiveConfirm)
        st.appRules = mapOf("com.spotify.music" to "Never start a trial", "com.empty" to " ")
        assertEquals(mapOf("com.spotify.music" to "Never start a trial"), s.appRules)
        st.calendarDefaultReminder = 15
        st.calendarMaxEvents = 30
        st.calendarEnabledIds = setOf(1L, 2L)
        assertEquals(15, s.moduleConfig().calendarDefaultReminder)
        assertEquals(30, s.moduleConfig().calendarMaxEvents)
        assertEquals(setOf(1L, 2L), s.moduleConfig().calendarEnabledIds)
        st.remoteMcpServers = listOf(RemoteMcpServer("a", "Home", "https://mcp.example.org/mcp", "tok"))
        assertEquals("tok", s.remoteMcpServers.single().token)
        assertEquals(3, UNATTENDED_ORDER.map(::unattendedLabel).toSet().size)
    }

    @Test fun searchAndWeatherHelpers() {
        assertTrue(ModuleSettingsLogic.searchNeedsKey("tavily"))
        assertFalse(ModuleSettingsLogic.searchNeedsKey("duckduckgo"))
        assertEquals(1, ModuleSettingsLogic.searchProblems("serper", "", "").size)
        assertEquals(1, ModuleSettingsLogic.searchProblems("searxng", "", "not a url").size)
        assertTrue(ModuleSettingsLogic.searchProblems("searxng", "", "https://sx.example.org").isEmpty())
        val url = ModuleSettingsLogic.weatherTestUrl("a&b", "Köln, DE", "imperial")
        assertTrue(url, url.startsWith("https://api.openweathermap.org/data/2.5/weather?q=K%C3%B6ln%2C+DE&appid=a%26b&units=imperial"))
        val ok = HttpResult(200, """{"name":"Berlin","sys":{"country":"DE"},"main":{"temp":12.4,"humidity":50},"weather":[{"main":"Clouds","description":"overcast clouds"}]}""")
        assertEquals("Works: Berlin, DE, 12°C, overcast clouds", ModuleSettingsLogic.weatherTestResult(ok, "metric"))
        assertTrue(ModuleSettingsLogic.weatherTestResult(HttpResult(401, ""), "metric").contains("rejected the key"))
        assertTrue(ModuleSettingsLogic.weatherTestResult(null, "metric").contains("starting"))
    }

    @Test fun remoteServerHelpers() {
        assertNotNull(ModuleSettingsLogic.urlProblem("ftp://x"))
        assertNotNull(ModuleSettingsLogic.urlProblem("https://"))
        assertNull(ModuleSettingsLogic.urlProblem("https://mcp.example.org/mcp"))
        assertNotNull(ModuleSettingsLogic.insecureWarning("http://mcp.example.org/mcp"))
        assertEquals(
            if (PLAY_DISTRIBUTION) "Unencrypted connection: keys and content travel in plain text." else null,
            ModuleSettingsLogic.insecureWarning("http://192.168.1.5:8000/mcp"),
        )
        assertNull(ModuleSettingsLogic.insecureWarning("https://mcp.example.org"))
        val added = ModuleSettingsLogic.upsertServer(emptyList(), RemoteMcpServer("", " ", " https://mcp.example.org/mcp ", " t "))
        val s = added.single()
        assertTrue(s.id.isNotBlank())
        assertEquals("mcp.example.org", s.name)
        assertEquals("https://mcp.example.org/mcp", s.url)
        assertEquals("t", s.token)
        val edited = ModuleSettingsLogic.upsertServer(added, s.copy(name = "Home"))
        assertEquals(listOf("Home"), edited.map { it.name })
    }

    @Test fun appAndMemoryFilters() {
        val apps = listOf(AppEntry("Spotify", "com.spotify.music"), AppEntry("Chrome", "com.android.chrome"), AppEntry("Spotify", "com.spotify.music"))
        assertEquals(listOf("Chrome", "Spotify"), ModuleSettingsLogic.filterApps(apps, "").map { it.label })
        assertEquals(listOf("Chrome"), ModuleSettingsLogic.filterApps(apps, "android").map { it.label })

        val mem = InMemoryMemory()
        mem.save("My gym is FitX Kreuzberg", "agent")
        mem.save("Anna's birthday is 3 May", "agent")
        assertEquals(1, ModuleSettingsLogic.filterMemories(mem.all(), "gym").size)
        assertEquals(2, ModuleSettingsLogic.filterMemories(mem.all(), " ").size)
        val export = ModuleSettingsLogic.exportMemories(mem, "1.0.0", 1L)
        val other = InMemoryMemory()
        other.save("Anna's birthday is 3 May", "agent")
        assertEquals("duplicates are skipped", 1, ModuleSettingsLogic.importMemories(other, export))
        assertEquals(2, other.all().size)
        assertEquals(null, ModuleSettingsLogic.parseReminder("", 10))
        assertEquals(30, ModuleSettingsLogic.parseReminder("30", 10))
    }

    // ---- legal notices: legal notices ---------------------------------------------------------
    @Test fun legalNoticesAreCompleteAndAccurate() {
        assertTrue(Licenses.COPYRIGHT.contains("Copyright © 2026 TheEggmanEmpire"))
        assertTrue(Licenses.COPYRIGHT.contains("NarYuki"))
        assertTrue(Licenses.sourceLine("1.0.0").contains("github.com/TheEggmanEmpire/Backstage"))
        assertTrue(Licenses.sourceLine("1.0.0").contains("1.0.0"))
        assertFalse(Licenses.desktop.note.contains("unmodified"))
        assertTrue(Licenses.desktop.note.contains("modified by the Backstage project"))
        assertTrue(Licenses.desktop.note.contains("NOTICE.md"))
        val note = Licenses.SERVICES_NOTE
        assertFalse(note.contains("only remote service"))
        listOf("Brave", "Serper", "Tavily", "Kagi", "custom search URL", "OpenWeatherMap", "alphacephei.com", "api.github.com", "MCP").forEach {
            assertTrue(it, note.contains(it))
        }
    }
}
