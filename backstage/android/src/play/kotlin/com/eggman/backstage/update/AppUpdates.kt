package com.eggman.backstage.update

import android.content.Context
import androidx.compose.runtime.Composable

/** Play has no GitHub checker, downloader, installer, alarms, updater components or update notifications. */
object AppUpdates {
    const val EXTRA_OPEN = "com.eggman.backstage.extra.UPDATES"
    fun enabled(context: Context): Boolean = false
    fun start(context: Context) = Unit
    fun checkStatus(context: Context, manual: Boolean): Map<String, Any?> = mapOf("enabled" to false)
    fun open(context: Context) = Unit
}

@Composable internal fun UpdatesSection() = Unit
