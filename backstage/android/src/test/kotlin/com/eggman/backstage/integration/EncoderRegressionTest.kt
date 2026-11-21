package com.eggman.backstage.integration

import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.OpenAiCompatClient
import com.eggman.backstage.llm.Role
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**  an empty assistant turn (length cut-off) must not reach OpenAI-compatible APIs. */
class EncoderRegressionTest {
    @Test fun emptyAssistantTurnIsNotEncoded() {
        val out = JSONArray()
        val dialect = OpenAiCompatClient.Dialect()
        OpenAiCompatClient.encodeMessage(Message(Role.ASSISTANT, emptyList()), out, dialect)
        OpenAiCompatClient.encodeMessage(Message.user("Continue."), out, dialect)
        assertEquals(1, out.length())
        assertEquals("user", out.getJSONObject(0).getString("role"))
    }
}
