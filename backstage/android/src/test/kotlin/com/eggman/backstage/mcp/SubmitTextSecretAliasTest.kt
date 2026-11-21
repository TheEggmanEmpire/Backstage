package com.eggman.backstage.mcp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class SubmitTextSecretAliasTest {
    @Test fun submitTextPassesTheSecretFlagAndAdvertisesIt() {
        val alias = McpAliases.all.single { it.name == "submit_text" }
        val (tool, args) = alias.map(JSONObject().put("text", "123456").put("secret", true))
        assertEquals("type_text", tool)
        assertTrue(args.getBoolean("secret"))
        assertTrue(args.getBoolean("submit"))
        assertTrue(alias.schema.getJSONObject("properties").has("secret"))
    }
}
