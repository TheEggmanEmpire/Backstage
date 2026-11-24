package com.eggman.backstage.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.eggman.backstage.a11y.CompanionConnection
import com.eggman.backstage.a11y.companion.CompanionProtocol

@Composable
internal fun availableCompanionSettings(): Set<CompanionSetting> {
    val capabilities by CompanionConnection.capabilities.collectAsState()
    return remember(capabilities) {
        buildSet {
            fun addWhenSupported(setting: CompanionSetting, required: Int) {
                if (capabilities and required == required) add(setting)
            }

            addWhenSupported(CompanionSetting.SHELL, CompanionProtocol.CAP_SHELL)
            addWhenSupported(CompanionSetting.SKILL_SCRIPTS, CompanionProtocol.CAP_SHELL or CompanionProtocol.CAP_SKILL_SCRIPTS)
            addWhenSupported(CompanionSetting.ACCESSIBILITY, CompanionProtocol.CAP_ACCESSIBILITY_SETTINGS)
            addWhenSupported(CompanionSetting.OMP, CompanionProtocol.CAP_OMP)
        }
    }
}
