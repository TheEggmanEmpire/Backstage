package com.eggman.backstage.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.eggman.backstage.data.ChatThread
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Chats as Markdown for export (pure), plus copy and share helpers for the chat UI. */
object ChatExport {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun markdown(t: ChatThread, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("# ").append(t.displayTitle).append("\n\n")
        if (t.createdMs > 0) append("_Started ").append(time(t.createdMs, zone)).append("_\n\n")
        t.entries.forEach { e ->
            val at = if (e.timeMs > 0) " · " + time(e.timeMs, zone) else ""
            when (e.kind) {
                "user" -> append("**You**").append(at).append("\n\n").append(e.text.trim()).append("\n\n")
                "answer" -> append("**You (answer)**").append(at).append("\n\n").append(e.text.trim()).append("\n\n")
                "result", "assistant" -> {
                    append("**Backstage**").append(at).append("\n\n")
                    e.thinking?.takeIf { it.isNotBlank() }?.let { th ->
                        append("<details><summary>Thinking</summary>\n\n").append(th.trim()).append("\n\n</details>\n\n")
                    }
                    append(e.text.trim()).append("\n\n")
                }
                "question" -> append("**Backstage asks**").append(at).append("\n\n").append(e.text.trim()).append("\n\n")
                "action" -> append("- ").append(if (e.step > 0) "${e.step}. " else "").append(e.text.trim().replace("\n", " ")).append('\n')
                    .also { if (e.detail?.isNotBlank() == true) append("  ```\n  ").append(e.detail.trim().replace("\n", "\n  ")).append("\n  ```\n") }
                "error" -> append("> **Error:** ").append(e.text.trim().replace("\n", "\n> ")).append("\n\n")
                else -> append("_").append(e.text.trim()).append("_\n\n")
            }
        }
    }

    fun markdownAll(threads: List<ChatThread>, zone: ZoneId = ZoneId.systemDefault()): String =
        threads.joinToString("\n---\n\n") { markdown(it, zone) }

    /** A safe file name from a chat title. */
    fun fileName(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N} _-]+"), " ").trim().replace(Regex("\\s+"), "-").take(60).ifBlank { "chat" } + ".md"

    private fun time(ms: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun copy(context: Context, text: String, label: String = "Message") {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ confirms clipboard writes itself.
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Writes [markdown] to the cache's share folder and opens the share sheet (save to Files, Drive, mail, …). */
    fun share(context: Context, name: String, markdown: String) {
        runCatching {
            val dir = File(context.cacheDir, "chat-share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, name).apply { writeText(markdown) }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.backstage-skills", file)
            val send = Intent(Intent.ACTION_SEND).setType("text/markdown")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, name.removeSuffix(".md"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Export chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(context, "Could not export: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}
