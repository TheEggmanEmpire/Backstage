package com.eggman.backstage.agent

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads [DeviceFacts] and the volatile "[device]" items on Android. Every read is guarded; nothing personal is read. */
class AndroidDeviceFacts(private val context: Context) {
    private val app = context.applicationContext ?: context

    private fun phoneDisplay(): Display? = runCatching { app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()

    private fun hasHinge(): Boolean = Build.VERSION.SDK_INT >= 30 &&
        runCatching { app.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null }.getOrDefault(false)

    /** One hinge reading (≤ 150 ms), or null. Off the main thread only. */
    private fun hingeAngle(): Float? {
        if (Build.VERSION.SDK_INT < 30 || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
        val sm = app.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return null
        val latch = CountDownLatch(1)
        var value: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { value = e.values.firstOrNull(); latch.countDown() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        return runCatching {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, android.os.Handler(android.os.Looper.getMainLooper()))
            latch.await(150, TimeUnit.MILLISECONDS)
            value
        }.also { runCatching { sm.unregisterListener(listener) } }.getOrNull()
    }

    private fun size(): Pair<Int, Int>? = runCatching {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") phoneDisplay()?.getRealMetrics(m)
        if (m.widthPixels > 0) m.widthPixels to m.heightPixels else null
    }.getOrNull()

    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** ro.build.version.oneui = 70000 -> "7.0" (60101 -> "6.1.1"). */
    fun oneUi(): String? = systemProperty("ro.build.version.oneui")?.toIntOrNull()?.let { v ->
        val major = v / 10_000
        val minor = (v / 100) % 100
        val patch = v % 100
        if (patch > 0) "$major.$minor.$patch" else "$major.$minor"
    }

    fun facts(): DeviceFacts {
        val (w, h) = size() ?: (0 to 0)
        val config = app.resources.configuration
        val dpi = runCatching { app.resources.displayMetrics.densityDpi }.getOrDefault(0)
        val insets = runCatching {
            val wm = app.createDisplayContext(phoneDisplay()!!).getSystemService(WindowManager::class.java)
            wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
            )
        }.getOrNull()
        val nav = runCatching { android.provider.Settings.Secure.getInt(app.contentResolver, "navigation_mode") }.getOrNull()?.let {
            when (it) { 0 -> "3-button"; 1 -> "2-button"; 2 -> "gestures"; else -> null }
        }
        val languages = runCatching { (0 until config.locales.size()).map { config.locales[it].toLanguageTag() } }.getOrDefault(emptyList())
        val keyboards = runCatching {
            val imm = app.getSystemService(InputMethodManager::class.java)
            val current = android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            val ime = imm.enabledInputMethodList.firstOrNull { it.id == current }
            ime?.let { imm.getEnabledInputMethodSubtypeList(it, true) }.orEmpty()
                .mapNotNull { s -> (if (Build.VERSION.SDK_INT >= 24) s.languageTag else null)?.takeIf { it.isNotBlank() } }.distinct()
        }.getOrDefault(emptyList())
        val kind = FoldStates.kindOf(Build.MODEL.orEmpty(), hasHinge())
        val stable = DisplayMetrics.DENSITY_DEVICE_STABLE
        return DeviceFacts(
            maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() },
            model = Build.MODEL.orEmpty(),
            android = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            oneUi = oneUi(),
            widthPx = w, heightPx = h, densityDpi = dpi,
            orientation = if (w > h) "landscape" else "portrait",
            insetTop = insets?.top ?: 0, insetBottom = insets?.bottom ?: 0, insetLeft = insets?.left ?: 0, insetRight = insets?.right ?: 0,
            navigation = nav,
            darkMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
            fontScale = config.fontScale,
            displayScale = if (stable > 0 && dpi > 0) dpi.toFloat() / stable else 1f,
            languages = languages,
            keyboardLanguages = keyboards,
            foldKind = kind,
            fold = FoldStates.from(kind, w, h, hingeAngle()),
        )
    }

    /** The volatile items of the "[device]" line ([DeviceStateDiff]). */
    fun volatile(): Map<String, String> = buildMap {
        // Keyboard: the input-method window on either screen (the agent's own accessibility view of the windows).
        runCatching {
            val service = com.eggman.backstage.a11y.AgentAccessibility.instance?.service
            val ime = service?.windowsOnAllDisplays?.let { all ->
                (0 until all.size()).flatMap { i -> all.valueAt(i).map { w -> all.keyAt(i) to w } }
                    .firstOrNull { (_, w) -> w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            if (service != null) put("keyboard", ime?.let { (display, _) -> if (display == Display.DEFAULT_DISPLAY) "shown on the phone" else "shown on the desktop" } ?: "hidden")
        }
        runCatching {
            val locked = app.getSystemService(KeyguardManager::class.java).isDeviceLocked
            val on = app.getSystemService(PowerManager::class.java).isInteractive
            put("phone", (if (locked) "locked" else "unlocked") + if (on) "" else ", screen off")
        }
        runCatching {
            val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (level >= 0 && scale > 0) put("battery", DeviceStateDiff.battery(level * 100 / scale,
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL))
        }
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val kind = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "connected"
            }
            val metered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            put("network", kind + if (metered) ", metered" else "")
        }
        runCatching {
            val f = app.getSystemService(NotificationManager::class.java).currentInterruptionFilter
            put("do not disturb", if (f == NotificationManager.INTERRUPTION_FILTER_ALL || f == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) "off" else "on")
        }
        runCatching {
            val (w, h) = size() ?: return@runCatching
            val fold = FoldStates.from(FoldStates.kindOf(Build.MODEL.orEmpty(), hasHinge()), w, h)
            put("phone screen", (if (w > h) "landscape" else "portrait") + (fold?.let { ", ${it.text}" } ?: ""))
        }
    }
}
