package com.eggman.backstage.host

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Contract between the Backstage agent library (:backstage) and the Dextop app module.
 *
 * The implementation lives in backstage/dextop-glue (compiled INTO Dextop's:app module so it can
 * use Dextop's `internal` PrivilegedAccess / InputDispatcher / AppCatalog / TaskBridge and the
 * MirrorService companion API). The library never references Dextop classes directly.
 *
 * Threading: every method may be called from any thread unless noted; implementations must not
 * block the main thread for injection (IInputManager WAIT_FOR_RESULT) — suspend functions run
 * their blocking work on an IO dispatcher.
 */
interface DesktopHost {

    /**
     * How the Dextop desktop is presented on the phone while it keeps running. IN_APP: the live player inside the app's
     * Home screen shows it (parked on the background sink while no player is on screen); no window over the phone.
   */
    enum class Presentation { FULL, MINI, HIDDEN, IN_APP, NONE }

    data class Geometry(val displayId: Int, val width: Int, val height: Int, val densityDpi: Int)

    data class ShellResult(val exitCode: Int, val output: String, val error: String) {
        val ok: Boolean get() = exitCode == 0
    }

    interface Listener {
        /** Session started/stopped, display recreated, or presentation changed. */
        fun onDesktopChanged(geometry: Geometry?, presentation: Presentation)
    }

    // ---- privilege ---------------------------------------------------------------------------
    /** Dextop's embedded wireless-ADB runtime or Shizuku is connected (shell identity available). */
    fun privilegedAvailable(): Boolean
    /** Run a command as shell (uid 2000) through Dextop's PrivilegedAccess. */
    suspend fun shell(vararg argv: String): ShellResult
    /** Bounded shell call. Older hosts must decline rather than silently drop the deadline. */
    suspend fun shell(vararg argv: String, timeoutMs: Long, maxOutputBytes: Int = 128 * 1024): ShellResult =
        throw UnsupportedOperationException("This desktop host does not support bounded shell commands")
    /** Hidden system-service AIDL proxy whose transactions run as shell, e.g. ("input", "android.hardware.input.IInputManager"). */
    fun systemService(name: String, aidlInterface: String): Any?

    // ---- desktop session ---------------------------------------------------------------------
    fun sessionActive(): Boolean
    fun geometry(): Geometry?
    /** True when the session uses the direct owned VirtualDisplay (required for MINI/HIDDEN + accessibility). */
    fun directDisplay(): Boolean
    /**
     * Start a desktop session with the user's saved Dextop profile (same path as Dextop's START_DISPLAY
     * shortcut) and switch to [presentation] once the display exists. Returns the geometry or null on timeout.
   */
    suspend fun startDesktop(presentation: Presentation, timeoutMs: Long = 20_000): Geometry?
    fun stopDesktop()
    fun presentation(): Presentation
    /** FULL <-> MINI <-> HIDDEN without recreating the display (apps keep running). */
    fun setPresentation(presentation: Presentation): Boolean
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    // ---- perception --------------------------------------------------------------------------
    /** Latest desktop frame (background sink in HIDDEN, PixelCopy in FULL/MINI); null if unavailable. */
    suspend fun captureFrame(): Bitmap?

    // ---- input into the desktop display (coordinates in desktop display pixels) ---------------
    suspend fun tap(x: Float, y: Float): Boolean
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 650): Boolean
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean
    suspend fun key(keyCode: Int, metaState: Int = 0): Boolean
    /**
     * Text through key events on the desktop display (the agent tries accessibility SET_TEXT first). False for text the
     * key map cannot produce: implementations never use the phone's clipboard (see [DesktopTyping]).
   */
    suspend fun typeText(text: String): Boolean
    /** Mouse right-click / secondary button at a point (desktop context menus). */
    suspend fun rightClick(x: Float, y: Float): Boolean
    /** Mouse wheel scroll at a point; positive = content moves up (scroll down). */
    suspend fun scrollWheel(x: Float, y: Float, vertical: Float, horizontal: Float = 0f): Boolean

    // ---- apps / windows on the desktop -------------------------------------------------------
    fun launchPackage(packageName: String, bounds: Rect? = null): Boolean
    fun launchIntent(intent: Intent, bounds: Rect? = null): Boolean
    /** Named Dextop slots: left, right, top_left,... (WorkspaceLayoutEngine). */
    fun launchPackageAt(packageName: String, slot: String): Boolean
    /** "back" | "home" | "recents" on the desktop (Dextop's performCardexAction). */
    fun globalAction(action: String): Boolean
}

/** Process-wide registry; the Dextop glue installs its implementation at process start. */
object DesktopHosts {
    @Volatile
    var current: DesktopHost? = null
}
