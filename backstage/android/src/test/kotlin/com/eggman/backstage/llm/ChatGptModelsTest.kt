package com.eggman.backstage.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGptModelsTest {
    @Test fun listsVisibleModelsInBackendOrderWithDisplayNames() {
        val body = """{"models":[
            {"slug":"gpt-6.2-sol","display_name":"GPT-6.2-Sol","visibility":"list"},
            {"slug":"codex-auto-review","display_name":"Review","visibility":"hide"},
            {"slug":"gpt-6.1-terra","display_name":"","visibility":"list"}]}"""
        val models = ChatGptModels.parse(body)!!
        assertEquals(listOf("gpt-6.2-sol", "gpt-6.1-terra"), models.map { it.id })
        assertEquals(listOf("GPT-6.2-Sol", "gpt-6.1-terra"), models.map { it.name })
    }

    @Test fun emptyOrMissingListFallsBack() {
        assertNull(ChatGptModels.parse("""{"models":[]}"""))
        assertNull(ChatGptModels.parse("""{"other":1}"""))
    }
}
