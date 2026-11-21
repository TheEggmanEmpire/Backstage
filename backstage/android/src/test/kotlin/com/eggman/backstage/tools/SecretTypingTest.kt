package com.eggman.backstage.tools

import com.eggman.backstage.exec.Box
import com.eggman.backstage.testutil.Screens
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SecretTypingTest {
    private fun input(password: Boolean = false, hint: String = "") = Screens.app(
        Screens.node(cls = "android.widget.EditText", b = Box(0, 0, 1000, 100), editable = true,
            hint = hint, focused = true).copy(password = password))

    @Test fun focusedPasswordRequiresSecretBeforeAnyAction() = runBlocking {
        val ctx = TestToolContext()
        ctx.phone.capture = input(password = true)
        val args = JSONObject().put("text", "sample-password")
        val r = TypeTextTool().run(ctx, Args(args))
        assertTrue(r.isError)
        assertTrue(r.text.contains("secret=true"))
        assertTrue(ctx.phone.actions.isEmpty())
        assertEquals(15, args.getJSONObject("text").getInt("secret_len"))
        assertFalse(r.text.contains("sample-password"))
    }
    @Test fun indexedOtpRequiresSecret() = runBlocking {
        val ctx = TestToolContext()
        ctx.phone.capture = input(hint = "Verification code")
        ctx.phone.screen()
        val r = TypeTextTool().run(ctx, Args(JSONObject().put("text", "123456").put("index", 0)))
        assertTrue(r.isError)
        assertTrue(ctx.phone.actions.isEmpty())
    }
    @Test fun explicitSecretIsTypedButAbsentFromResultAndRetainedArguments() = runBlocking {
        val ctx = TestToolContext()
        ctx.phone.capture = input(password = true)
        val args = JSONObject().put("text", "sample-password").put("secret", true)
        val r = TypeTextTool().run(ctx, Args(args))
        assertFalse(r.isError)
        assertEquals("text null sample-password", ctx.phone.actions.single())
        assertFalse(r.text.contains("sample-password"))
        assertNull(r.image)
        assertEquals(15, args.getJSONObject("text").getInt("secret_len"))
    }
    @Test fun clearingPasswordDoesNotNeedSecretFlag() = runBlocking {
        val ctx = TestToolContext()
        ctx.phone.capture = input(password = true)
        val r = TypeTextTool().run(ctx, Args(JSONObject().put("text", "")))
        assertFalse(r.isError)
        assertEquals("text null ", ctx.phone.actions.single())
    }
    @Test fun retainedSequenceArgumentsAreCopiedAndRedacted() {
        val args = JSONObject("""{"steps":[{"tool":"type_text","args":{"secret":true,"text":"sixsix","reason":"sixsix"}}]}""")
        val safe = TypeTextTool.redactedArguments("act_sequence", args)
        val nested = safe.getJSONArray("steps").getJSONObject(0).getJSONObject("args")
        assertEquals(6, nested.getJSONObject("text").getInt("secret_len"))
        assertFalse(nested.has("reason"))
        assertFalse(safe.toString().contains("sixsix"))
        assertTrue(args.toString().contains("sixsix"))
    }
    @Test fun providerHistorySerializersNeverRepeatSecretArguments() {
        val args = JSONObject().put("secret", true).put("text", "private-token")
        val message = com.eggman.backstage.llm.Message(com.eggman.backstage.llm.Role.ASSISTANT,
            listOf(com.eggman.backstage.llm.Part.ToolCall("s1", "type_text", args)))
        val request = com.eggman.backstage.llm.LlmRequest(system = "", messages = listOf(message))
        val encoded = listOf(
            com.eggman.backstage.llm.OpenAiCompatClient.encodeRequest("test", request, com.eggman.backstage.llm.OpenAiCompatClient.Dialect()),
            com.eggman.backstage.llm.OpenAiResponsesClient.encodeRequest("test", request, vision = false),
            com.eggman.backstage.llm.AnthropicClient.encodeRequest("test", request),
        )
        encoded.forEach { assertFalse(it.toString().contains("private-token")); assertTrue(it.toString().contains("secret_len")) }
        assertEquals("private-token", args.getString("text"))
    }

}
