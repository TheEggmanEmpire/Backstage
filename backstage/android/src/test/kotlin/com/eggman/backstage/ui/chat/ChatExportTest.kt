package com.eggman.backstage.ui.chat

import com.eggman.backstage.data.ChatThread
import com.eggman.backstage.data.ThreadEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ChatExportTest {
    private val t = ChatThread("id", title = "Weather in Berlin", createdMs = 0, entries = listOf(
        ThreadEntry("user", "How is the weather?", 60_000),
        ThreadEntry("action", "web_search Berlin weather", 61_000, step = 1, detail = "3 results"),
        ThreadEntry("result", "Sunny, 21 °C.", 62_000, thinking = "Check the forecast"),
        ThreadEntry("error", "Timed out once", 63_000),
    ))

    @Test fun markdownKeepsSpeakersStepsAndThinking() {
        val md = ChatExport.markdown(t, ZoneOffset.UTC)
        assertTrue(md.startsWith("# Weather in Berlin\n"))
        assertTrue(md.contains("**You** · 1970-01-01 00:01\n\nHow is the weather?"))
        assertTrue(md.contains("- 1. web_search Berlin weather\n  ```\n  3 results\n  ```"))
        assertTrue(md.contains("<details><summary>Thinking</summary>\n\nCheck the forecast"))
        assertTrue(md.contains("**Backstage** · 1970-01-01 00:01\n\n<details>"))
        assertTrue(md.contains("Sunny, 21 °C."))
        assertTrue(md.contains("> **Error:** Timed out once"))
    }

    @Test fun allChatsAreSeparated() {
        assertEquals(2, ChatExport.markdownAll(listOf(t, t), ZoneOffset.UTC).split("\n---\n").size)
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("Wetter-in-Berlin-heute.md", ChatExport.fileName("Wetter in Berlin: heute?"))
        assertEquals("chat.md", ChatExport.fileName("///"))
    }
}
