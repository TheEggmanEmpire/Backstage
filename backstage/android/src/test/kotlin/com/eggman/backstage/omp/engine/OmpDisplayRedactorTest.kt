package com.eggman.backstage.omp.engine

import org.junit.Assert.*
import org.junit.Test

class OmpDisplayRedactorTest {
    @Test fun remoteMcpTokensAreMaskedEvenWithoutCredentialSyntaxAndWhenDisabled() {
        val settings = com.eggman.backstage.data.Settings(com.eggman.backstage.data.MemoryStore())
        val tokens = listOf("opaqueRemoteOne", "opaqueRemoteTwo")
        settings.remoteMcpServers = listOf(
            com.eggman.backstage.data.RemoteMcpServer("one", "One", "https://one.test", tokens[0]),
            com.eggman.backstage.data.RemoteMcpServer("two", "Two", "https://two.test", tokens[1], enabled = false),
            com.eggman.backstage.data.RemoteMcpServer("blank", "Blank", "https://blank.test", "  "),
        )
        val secrets = settings.existingOmpDisplaySecrets()
        assertTrue(secrets.containsAll(tokens))
        assertFalse(secrets.any(String::isBlank))
        val redactor = OmpDisplayRedactor(secrets)
        for (token in tokens) assertEquals("HTTP 500: rejected •••", redactor.redact("HTTP 500: rejected $token"))
    }
    @Test fun recognizedSecretFormsAndExactConfiguredSecretsAreRemoved() {
        val cases = listOf(
            "curl -H 'Authorization: Bearer AUTH_SENTINEL'" to "AUTH_SENTINEL",
            "Proxy-Authorization: Basic PROXY_SENTINEL" to "PROXY_SENTINEL",
            "Bearer BEARER_SENTINEL" to "BEARER_SENTINEL",
            "token=TOKEN_SENTINEL" to "TOKEN_SENTINEL",
            "api_key='API_SENTINEL'" to "API_SENTINEL",
            "apikey=ALIAS_SENTINEL" to "ALIAS_SENTINEL",
            "password=PASSWORD_SENTINEL" to "PASSWORD_SENTINEL",
            "secret=SECRET_SENTINEL" to "SECRET_SENTINEL",
            "{\"api_key\":\"JSON_SENTINEL\"}" to "JSON_SENTINEL",
            "{\"key\":\"GENERIC_KEY_SENTINEL\"}" to "GENERIC_KEY_SENTINEL",
            "{\"accessToken\":\"ACCESS_TOKEN_SENTINEL\"}" to "ACCESS_TOKEN_SENTINEL",
            "curl -u user:BASIC_SENTINEL https://example.test" to "BASIC_SENTINEL",
            "https://user:URL_SENTINEL@example.test/file" to "URL_SENTINEL",
            "sk-ant-SENTINEL0123456789abcdef" to "SENTINEL0123456789abcdef",
            "ghp_GITHUB_SENTINEL0123456789" to "GITHUB_SENTINEL0123456789",
            "gho_OAUTH_SENTINEL0123456789" to "OAUTH_SENTINEL0123456789",
            "github_pat_PAT_SENTINEL0123456789" to "PAT_SENTINEL0123456789",
            "AIzaGOOGLE_SENTINEL0123456789" to "GOOGLE_SENTINEL0123456789",
            "xoxb-SLACK-SENTINEL-0123456789" to "SLACK-SENTINEL-0123456789",
            "xoxp-PERSON-SENTINEL-0123456789" to "PERSON-SENTINEL-0123456789",
            "token is ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-",
            "secret 0123456789abcdef0123456789abcdef" to "0123456789abcdef0123456789abcdef",
            "echo EXACT_PROVIDER_SENTINEL" to "EXACT_PROVIDER_SENTINEL",
            "echo EXACT_BROKER_SENTINEL" to "EXACT_BROKER_SENTINEL",
        )
        val redactor = OmpDisplayRedactor(listOf("EXACT_PROVIDER_SENTINEL", "EXACT_BROKER_SENTINEL"))
        cases.forEach { (input, sentinel) ->
            val safe = redactor.redact(input)
            assertFalse("A synthetic sentinel survived redaction", safe.contains(sentinel))
            assertTrue(safe.contains("•••"))
        }
        assertEquals("read: src/Main.kt", redactor.redact("read: src/Main.kt"))
    }

    @Test fun redactionHappensBeforeLabelTruncationAndUnknownArgumentsAreNotDisplayed() {
        val command = "curl -H 'Authorization: Bearer " + "SENTINEL".repeat(100) + "'"
        val summary = OmpEventMapper.toolSummary("bash", org.json.JSONObject().put("command", command))
        assertFalse(summary.contains("SENTINEL"))
        assertTrue(summary.contains("•••"))
        assertEquals("custom", OmpEventMapper.toolSummary("custom", org.json.JSONObject().put("value", "ARBITRARY_SENTINEL")))
    }
}
