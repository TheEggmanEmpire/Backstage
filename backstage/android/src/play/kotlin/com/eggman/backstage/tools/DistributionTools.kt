package com.eggman.backstage.tools

internal object DistributionTools {
    val privileged: Array<Tool>
        get() = (listOf(ForceStopAppTool(), CompanionSkillScriptTool()) + CompanionPrivilegedTool.all() + companionOmpTools()).toTypedArray()

    fun available(tool: Tool, availability: ToolRegistry.Availability): Boolean? = when (tool) {
        is CompanionPrivilegedTool -> availability.companionCapabilities and tool.capability == tool.capability
        is CompanionSkillScriptTool -> availability.skills &&
            availability.companionCapabilities and CompanionSkillScriptTool.capability == CompanionSkillScriptTool.capability
        is ForceStopAppTool -> if (availability.localPrivilege) null else false
        else -> isCompanionOmpToolAvailable(tool, availability)
    }
}
