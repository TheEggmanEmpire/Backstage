package com.eggman.backstage.a11y

import org.junit.Assert.*
import org.junit.Test

class TextVerificationAndSecretFieldTest {
    @Test fun lostMeaningfulPunctuationIsNotVerified() {
        listOf("100.50" to "10050", "-50" to "50", "1,5" to "15", "a@b.com" to "abcom",
            "AB12" to "AB/12", "abc123" to "abc:123", "1500" to "1,50", "50" to "-50").forEach { (expected, actual) ->
            assertNotEquals("$expected -> $actual", TextVerification.VERIFIED, TextInputPolicy.verify(expected, actual))
        }
    }
    @Test fun numberFormattingPreservesDigitsSignsAndExistingDecimals() {
        listOf("5551234567" to "(555) 123-4567", "+15551234567" to "+1 (555) 123-4567",
            "4111111111111111" to "4111 1111 1111 1111", "1000000" to "1,000,000",
            "1234567.89" to "1,234,567.89", "1234567,89" to "1.234.567,89",
            "1000" to "1\u2009000", "1000" to "1\u00a0000", "word pair" to "word\u00a0pair").forEach { (expected, actual) ->
            assertEquals("$expected -> $actual", TextVerification.VERIFIED, TextInputPolicy.verify(expected, actual))
        }
    }
    @Test fun postalPinCodeDoesNotBecomeACredential() {
        listOf("PIN code", "Enter PIN code", "Postal PIN", "Address PIN", "ZIP PIN").forEach {
            assertFalse(it, TextInputPolicy.isSecretField(editable = true, label = it))
        }
        assertFalse(TextInputPolicy.isSecretField(editable = true, resourceId = "app:id/pin_code"))
        assertFalse(TextInputPolicy.isSecretField(editable = true, label = "PIN", hint = "Postal address"))
        listOf("PIN", "Enter PIN").forEach { assertTrue(it, TextInputPolicy.isSecretField(editable = true, label = it)) }
        assertTrue(TextInputPolicy.isSecretField(editable = true, label = "PIN code", hint = "Security credential"))
        assertTrue(TextInputPolicy.isSecretField(password = true, editable = true, label = "PIN code"))
        assertTrue(TextInputPolicy.isSecretField(editable = true, label = "Verification code", resourceId = "pin_code"))
    }
}
