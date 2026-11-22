package com.eggman.backstage.tools

internal object DistributionTools {
    val privileged: Array<Tool>
        get() = (listOf(ForceStopAppTool()) + CompanionPrivilegedTool.all()).toTypedArray()

    fun available(tool: Tool, availability: ToolRegistry.Availability): Boolean? = when (tool) {
        is CompanionPrivilegedTool -> availability.companionCapabilities and tool.capability == tool.capability
        is ForceStopAppTool -> if (availability.localPrivilege) null else false
        else -> null
    }
}
