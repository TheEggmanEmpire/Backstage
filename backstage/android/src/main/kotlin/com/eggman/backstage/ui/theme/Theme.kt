package com.eggman.backstage.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(primary = Color(0xFF5B3CC4), secondary = Color(0xFF00897B), tertiary = Color(0xFFC2185B))
private val Dark = darkColorScheme(primary = Color(0xFFC3B2FF), secondary = Color(0xFF6ED3C5), tertiary = Color(0xFFFF9EBF))

/**
 * Material 3 theme; Material You colours when the platform offers them.
 *
 * Sets the default content colour ([LocalContentColor] = onSurface) so icons
 * and text outside a Scaffold/Surface (the chat sheet over other apps, the bubble)
 * use the theme's light-on-dark or dark-on-light colour, like the Send icon.
 */
@Composable
fun BackstageTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}
