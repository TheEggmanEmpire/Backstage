package com.eggman.backstage.a11y

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InputReliabilityTest {
    @Test fun noReactionReturnsAtFirstChangeDeadline() = runTest {
        val d = UiSettleDetector { testScheduler.currentTime }
        val r = d.awaitOutcome(quietMs = 400, maxMs = 3000, expectChangeMs = 200)
        assertFalse(r.changed)
        assertEquals(200L, r.waitedMs)
    }
    @Test fun burstWaitsForQuietAfterLastChange() = runTest {
        val d = UiSettleDetector { testScheduler.currentTime }
        val events = launch { delay(50); d.onEvent(true); delay(100); d.onEvent(false) }
        val r = d.awaitOutcome(quietMs = 300, maxMs = 3000, expectChangeMs = 200)
        events.join()
        assertTrue(r.changed)
        assertTrue(r.settled)
        assertEquals(450L, r.waitedMs)
    }
    @Test fun tickerIsIgnoredAfterContinuousContentBudget() = runTest {
        val d = UiSettleDetector { testScheduler.currentTime }
        val events = launch { while (true) { delay(50); d.onEvent(false, "clock") } }
        val r = d.awaitOutcome(quietMs = 300, maxMs = 5000, expectChangeMs = 200)
        events.cancel()
        assertTrue(r.changed)
        assertTrue(r.settled)
        assertTrue(r.waitedMs in 1500..2000)
    }
    @Test fun windowChangesAlwaysKeepWaiting() = runTest {
        val d = UiSettleDetector { testScheduler.currentTime }
        val events = launch { while (true) { delay(50); d.onEvent(true) } }
        val r = d.awaitOutcome(quietMs = 300, maxMs = 2200, expectChangeMs = 200)
        events.cancel()
        assertFalse(r.settled)
        assertEquals(2200L, r.waitedMs)
    }
    @Test fun secretFieldsRequireEditableCodeWordsAndPasswordAlwaysWins() {
        assertTrue(TextInputPolicy.isSecretField(password = true))
        listOf("otp", "one-time code", "verification code", "2fa", "PIN", "TAN-Nummer", "Bestätigungscode", "app:id/otp_input", "認証コード", "验证码", "인증번호").forEach {
            assertTrue(it, TextInputPolicy.isSecretField(editable = true, label = it))
        }
        listOf("pincodebook", "Code editor", "Shipping address").forEach {
            assertFalse(it, TextInputPolicy.isSecretField(editable = false, label = it))
        }
        assertFalse(TextInputPolicy.isSecretField(editable = true, label = "pinterest"))
    }
    @Test fun verificationHasCauseSpecificHintsWithoutText() {
        assertEquals(TextVerification.VERIFIED, TextInputPolicy.verify("abc", "abc"))
        assertEquals(TextVerification.LENGTH_LIMIT, TextInputPolicy.verify("abcde", "abc"))
        assertEquals(TextVerification.FILTERED, TextInputPolicy.verify("a1b2", "12"))
        assertEquals(TextVerification.NOTHING_TAKEN, TextInputPolicy.verify("abc", ""))
        assertEquals(TextVerification.FIELD_GONE, TextInputPolicy.verify("abc", null))
        assertEquals(TextVerification.VERIFIED, TextInputPolicy.verify("secret", "••••••", password = true))
        assertFalse(TextInputPolicy.verify("secret", "sec").message.contains("sec"))
    }
    @Test fun formattingDoesNotTurnSuccessfulTypingIntoRetries() {
        listOf("5551234567" to "(555) 123-4567", "1234567.89" to "1,234,567.89",
            "1234567890123456" to "1234 5678 9012 3456", "1234" to "1\u00a0234").forEach { (expected, actual) ->
            assertEquals(actual, TextVerification.VERIFIED, TextInputPolicy.verify(expected, actual))
        }
        assertEquals(TextVerification.MISMATCH, TextInputPolicy.verify("1234", "1235"))
        assertNotEquals(TextVerification.VERIFIED, TextInputPolicy.verify("AB12", "AB/12"))
        assertNotEquals(TextVerification.VERIFIED, TextInputPolicy.verify("abc123", "abc:123"))
        assertEquals(TextVerification.MISMATCH, TextInputPolicy.verify("ab", "AB"))
        assertEquals(TextVerification.MISMATCH, TextInputPolicy.verify("!?", "@#"))
    }
    @Test fun ordinaryCodeFieldsDoNotRequireSecrets() {
        listOf("Postal code", "ZIP code", "Promo code", "Coupon code", "Country code", "code", "tan", "Suntan").forEach {
            assertFalse(it, TextInputPolicy.isSecretField(editable = true, label = it))
        }
        assertFalse(TextInputPolicy.isSecretField(editable = true, resourceId = "app:id/postal_code"))
        listOf("Verification code", "One-time code", "PIN", "SMS code", "Login code", "TAN-Nummer").forEach {
            assertTrue(it, TextInputPolicy.isSecretField(editable = true, label = it))
        }
        assertTrue(TextInputPolicy.isSecretField(editable = true, resourceId = "app:id/otp_input"))
    }

}
