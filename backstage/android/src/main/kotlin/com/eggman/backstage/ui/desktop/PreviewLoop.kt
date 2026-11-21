package com.eggman.backstage.ui.desktop

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The Desktop tab's live preview: captures a frame every [intervalMs], but only while [lifecycle] is at least
 * STARTED. The process outlives the activity (the accessibility service keeps it), so a loop that is not tied
 * to the lifecycle would keep copying desktop-size bitmaps (or spawning `screencap` and mirror displays) twice a
 * second after the user pressed Home. Suspends until the caller is cancelled (the composable leaves).
 */
internal suspend fun <T : Any> runPreviewLoop(
    lifecycle: Lifecycle,
    intervalMs: Long,
    capture: suspend () -> T?,
    onFrame: (T) -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (currentCoroutineContext().isActive) {
            capture()?.let(onFrame)
            delay(intervalMs)
        }
    }
}
