package com.eggman.backstage.llm

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageRegressionTest {
    private fun usage() = AnthropicClient.decodeResponse(JSONObject("""{
        "content": [], "stop_reason": "end_turn", "usage": {
            "input_tokens": 100, "cache_read_input_tokens": 90000,
            "cache_creation_input_tokens": 10000, "output_tokens": 200
        }}""")).usage

    @Test fun cacheHeavyPromptCountsEveryInputCategoryForCompression() {
        val u = usage()
        assertEquals(100100L, u.inputTokens)
        assertEquals(90000L, u.cacheReadTokens)
        assertEquals(10000L, u.cacheWriteTokens)
        assertEquals(100300L, u.totalTokens)
        assertTrue("Cache-heavy context must reach compression thresholds", u.inputTokens >= 80000L)
    }

    @Test fun priceBillsUncachedCacheReadAndCacheWriteExactlyOnce() {
        val prices = PriceTable(mapOf("model" to Price(3.0, 15.0, 0.3)))
        val expected = (100 * 3.0 + 90000 * 0.3 + 10000 * 3.0 + 200 * 15.0) / 1000000
        assertEquals(expected, prices.cost("model", usage())!!, 1e-12)
        val meter = UsageMeter(prices)
        repeat(2) { meter.record("model", usage()) }
        assertEquals(200200L, meter.usage.inputTokens)
        assertEquals(expected * 2, meter.costUsd!!, 1e-12)
    }

    @Test fun missingCacheFieldsAndMissingUsageRemainZero() {
        val u = AnthropicClient.decodeResponse(JSONObject("""{"usage":{"input_tokens":7,"output_tokens":2}}""")).usage
        assertEquals(7L, u.inputTokens)
        assertEquals(0L, u.cacheReadTokens)
        assertEquals(0L, u.cacheWriteTokens)
        assertEquals(Usage(), AnthropicClient.decodeResponse(JSONObject()).usage)
    }
}
