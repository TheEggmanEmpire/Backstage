package com.eggman.backstage.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.eggman.backstage.privileged.Privileged
import kotlinx.coroutines.delay

/** Both chat surfaces and Settings observe the same persisted value, including changes during a task. */
@Composable
internal fun rememberAdbAutoApprove(store: BackstageSettingsStore): State<Boolean> {
    val value = remember(store) { mutableStateOf(store.adbAutoApprove) }
    DisposableEffect(store) {
        val unregister = store.observe { key -> if (key == "adb_auto_approve") value.value = store.adbAutoApprove }
        value.value = store.adbAutoApprove
        onDispose { unregister() }
    }
    return value
}

/** Shizuku sends permission events; Dextop's wireless connection can also change silently. */
@Composable
internal fun rememberAdbAvailable(): State<Boolean> = produceState(Privileged.available()) {
    val unregister = Privileged.addPermissionListener { value = Privileged.available() }
    try {
        while (true) {
            value = Privileged.available()
            delay(3_000)
        }
    } finally {
        unregister()
    }
}
