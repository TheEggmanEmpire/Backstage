package com.eggman.backstage.exec

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.eggman.backstage.privileged.Privileged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/**
 * Shell-identity input injection into one display via IInputManager.injectInputEvent (Dextop's
 * InputDispatcher pattern: stamp the displayId, WAIT_FOR_RESULT, deviceId 0, interpolated MOVE
 * timestamps). Used for the phone display when privileged access exists (keys accessibility cannot
 * send) — the desktop uses the DesktopHost instead.
 */
class InputInjector(
    private val displayId: Int,
    /** Looks up the privileged IInputManager proxy; null while Shizuku / wireless debugging is not connected. */
    private val resolve: () -> Any? = { Privileged.serviceInterfaceOrNull("input", "android.hardware.input.IInputManager") },
) {
    private class Binding(val manager: Any, val inject: Method)

    /**
     * Resolved on demand and dropped when a call fails, so privileged access that appears later (Shizuku
     * started after the first task) or comes back after a restart (dead binder) is picked up without
     * restarting the app.
   */
    @Volatile private var binding: Binding? = null
    private val setDisplayId by lazy { runCatching { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }.getOrNull() }

    private fun binding(): Binding? = binding ?: runCatching {
        val m = resolve() ?: return null
        Binding(m, m.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType))
    }.getOrNull()?.also { binding = it }

    val available: Boolean get() = binding() != null

    private suspend fun send(event: InputEvent): Boolean = withContext(Dispatchers.IO) {
        val b = binding() ?: return@withContext false
        try {
            setDisplayId?.invoke(event, displayId)
            b.inject.invoke(b.manager, event, WAIT_FOR_RESULT) as? Boolean ?: false
        } catch (t: Throwable) {
            binding = null // dead proxy or revoked access: look it up again next time
            false
        }
    }

    private suspend fun motion(action: Int, down: Long, at: Long, x: Float, y: Float): Boolean {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f })
        val event = MotionEvent.obtain(down, at, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { send(event) } finally { event.recycle() }
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val down = SystemClock.uptimeMillis()
        val a = motion(MotionEvent.ACTION_DOWN, down, down, x, y)
        delay(40)
        return motion(MotionEvent.ACTION_UP, down, down + 40, x, y) && a
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val down = SystemClock.uptimeMillis()
        var ok = motion(MotionEvent.ACTION_DOWN, down, down, x1, y1)
        val steps = (durationMs / 16).toInt().coerceIn(4, 90)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            ok = motion(MotionEvent.ACTION_MOVE, down, down + (durationMs * t).toLong(), x1 + (x2 - x1) * t, y1 + (y2 - y1) * t) && ok
            delay(durationMs / steps)
        }
        return motion(MotionEvent.ACTION_UP, down, down + durationMs, x2, y2) && ok
    }

    suspend fun key(keyCode: Int, metaState: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        if (!send(down)) return false
        // The UP must follow a delivered DOWN even when the caller is cancelled meanwhile (no stuck key).
        return withContext(NonCancellable) { send(up) }
    }

    /** ASCII-ish text through the virtual keyboard map. False when a char has no key mapping. */
    suspend fun typeViaKeys(text: String): Boolean {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return false
        for (e in events) if (!send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0))) return false
        return true
    }

    private companion object { const val WAIT_FOR_RESULT = 1 }
}
