package com.eggman.backstage.agent

import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.exec.TargetDecision
import com.eggman.backstage.tools.ToolRegistry
import com.eggman.backstage.ui.settings.ProviderOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayBoundaryTest {
    @Test fun missingCompanionLeavesProviderAndNonScreenToolsAvailable() {
        val status = "Install Backstage Agent to use screen tools."
        val inputs = ReadinessInputs(
            providerProblem = null, accessibilityEnabled = false, privilegedAvailable = false,
            hostPresent = false, desktopActive = false, directDisplay = true, screenOn = false,
            unlocked = false, sdkInt = 36, accessibilityHost = false, companionStatus = status,
        )
        val readiness = ReadinessCheck.compute(inputs)
        assertTrue(readiness.providerConfigured)
        assertFalse(readiness.accessibilityEnabled)
        assertTrue(status in readiness.problems)
        assertNull(ReadinessCheck.unattendedProblem(TargetDecision(null, false, "No screen"), inputs))

        val tools = ToolRegistry.default().forAgent(ToolRegistry.Availability(desktop = true, screen = false))
            .map { it.name }
        assertTrue("web_search" in tools)
        assertTrue("launch_app" in tools)
        assertTrue("desktop_launch_app" in tools)
        assertFalse("get_screen" in tools)
        assertFalse("screenshot" in tools)
    }

    @Test fun skillScriptsRequireABoundCompanionWithScriptAndShellSupport() {
        val registry = ToolRegistry.default()
        val availability = ToolRegistry.Availability(desktop = false, skills = true)
        val combined = CompanionProtocol.CAP_SHELL or CompanionProtocol.CAP_SKILL_SCRIPTS

        assertFalse(registry.forAgent(availability.copy(companionCapabilities = CompanionProtocol.CAP_SHELL))
            .any { it.name == "run_skill_script" })
        assertFalse(registry.forAgent(availability.copy(skills = false, companionCapabilities = combined))
            .any { it.name == "run_skill_script" })
        assertTrue(registry.forAgent(availability.copy(companionCapabilities = combined))
            .any { it.name == "run_skill_script" })
    }

    @Test fun onlyCompanionCapabilitiesOfferShellAndPackages() {
        val tools = ToolRegistry.default().forAgent(
            ToolRegistry.Availability(desktop = true, phone = true, skills = true, adb = true, omp = true)
        ).map { it.name }
        assertFalse(tools.any { it == "adb_command" || it.startsWith("omp_subagent") || it == "run_skill_script" })
        assertTrue(ProviderOptions.all.any { it.id == "CUSTOM" })
        assertTrue(ProviderOptions.all.any { it.id == "CHATGPT" })

        val companion = ToolRegistry.Availability(desktop = false, screen = false, companionCapabilities = 6)
        val registry = ToolRegistry.default()
        val agentTools = registry.forAgent(companion).map { it.name }
        assertTrue(agentTools.containsAll(listOf("adb_command", "install_apk", "uninstall_app")))
        assertFalse(registry.forMcp(companion).any { it.name in setOf("adb_command", "install_apk", "uninstall_app") })
    }
}
