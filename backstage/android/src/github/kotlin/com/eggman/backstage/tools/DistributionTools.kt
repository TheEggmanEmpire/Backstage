package com.eggman.backstage.tools

internal object DistributionTools {
    val privileged: Array<Tool>
        get() = arrayOf(AdbCommandTool(), OmpSubagentTool(), OmpSubagentStatusTool(), OmpSubagentCancelTool())

    @Suppress("UNUSED_PARAMETER")
    fun available(tool: Tool, availability: ToolRegistry.Availability): Boolean? = null
}
