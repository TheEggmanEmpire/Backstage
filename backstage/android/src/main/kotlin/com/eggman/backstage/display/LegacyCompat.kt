package com.eggman.backstage.display

import android.content.Context
import android.os.Build
import com.eggman.backstage.exec.AppCatalog
import com.eggman.backstage.exec.AppEntry

/** Deprecated UI adapters for the app catalog and manufacturer detection. */

@Deprecated("Use com.eggman.backstage.exec.AppCatalog")
object AppLauncher {
    fun installedApps(context: Context): List<AppEntry> = AppCatalog.installedApps(context)
    fun resolve(context: Context, query: String): AppEntry? = AppCatalog.resolve(context, query)
}

@Deprecated("Desktop settings are owned by Dextop now")
object DesktopSettings {
    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}
