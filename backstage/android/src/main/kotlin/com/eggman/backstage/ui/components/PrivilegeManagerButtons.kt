package com.eggman.backstage.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eggman.backstage.privileged.PrivilegeProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** One privilege manager the desktop can use: download page, installed packages and its icon in its repository. */
data class PrivilegeManager(val label: String, val download: String, val packages: List<String>, val iconUrl: String) {
    val cacheName: String get() = "privilege-icon-" + label.lowercase().replace(' ', '-') + ".img"

    companion object {
        val STELLAR = PrivilegeManager("Stellar", PrivilegeProviders.STELLAR_DOWNLOAD, listOf(PrivilegeProviders.STELLAR_PACKAGE),
            "https://raw.githubusercontent.com/roro2239/Stellar/HEAD/manager/src/main/res/mipmap-xxxhdpi/ic_launcher.webp")
        val SHIZUKU_PLUS = PrivilegeManager("Shizuku Plus", PrivilegeProviders.SHIZUKU_PLUS_DOWNLOAD, PrivilegeProviders.shizukuPackages,
            "https://raw.githubusercontent.com/thejaustin/ShizukuPlus/HEAD/manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
        val ALL = listOf(STELLAR, SHIZUKU_PLUS)
    }
}

/** "Get Stellar" and "Get Shizuku Plus" side by side, each with the app's icon. */
@Composable
fun PrivilegeManagerButtons(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrivilegeManager.ALL.forEach { manager ->
            var icon by remember(manager) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(manager) { icon = withContext(Dispatchers.IO) { PrivilegeManagerIcons.load(context, manager) } }
            OutlinedButton(onClick = { PrivilegeManagerIcons.open(context, manager.download) }, modifier = Modifier.weight(1f)) {
                icon?.let {
                    Image(it, contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(8.dp))
                }
                Text("Get ${manager.label}")
            }
        }
    }
}

internal object PrivilegeManagerIcons {
    private val client by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }

    /** The installed app's own icon, else the icon from its repository (fetched once and cached). */
    fun load(context: Context, manager: PrivilegeManager): ImageBitmap? = installed(context, manager) ?: cached(context, manager)

    private fun installed(context: Context, manager: PrivilegeManager): ImageBitmap? = manager.packages.firstNotNullOfOrNull { pkg ->
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val size = 96
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, size, size); drawable.draw(Canvas(it))
            }.asImageBitmap()
        }.getOrNull()
    }

    private fun cached(context: Context, manager: PrivilegeManager): ImageBitmap? {
        val file = File(context.cacheDir, manager.cacheName)
        if (!file.isFile || file.length() == 0L) runCatching {
            client.newCall(Request.Builder().url(manager.iconUrl).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes()?.let { bytes ->
                    if (bytes.size in 1..512_000) file.writeBytes(bytes)
                }
            }
        }
        return runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
    }

    fun open(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
