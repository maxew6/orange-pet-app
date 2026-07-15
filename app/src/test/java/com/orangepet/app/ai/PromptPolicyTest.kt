package com.orangepet.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPolicyTest {

    @Test
    fun sanitize_stripsMarkdownSymbols() {
        assertEquals("Hello there friend", PromptPolicy.sanitize("**Hello** _there_ #friend"))
    }

    @Test
    fun sanitize_collapsesWhitespaceAndNewlines() {
        assertEquals("Hello there", PromptPolicy.sanitize("Hello\n\n  there"))
    }

    @Test
    fun sanitize_truncatesToMaxLength() {
        val result = PromptPolicy.sanitize("a".repeat(500))
        assertTrue(result.length <= PromptPolicy.MAX_GREETING_LENGTH)
    }

    @Test
    fun sanitize_stripsHtmlTags() {
        assertEquals("bold text", PromptPolicy.sanitize("<b>bold</b> text"))
    }

    @Test
    fun isUsable_blankAfterSanitize_isNotUsable() {
        assertFalse(PromptPolicy.isUsable(PromptPolicy.sanitize("****___")))
    }

    @Test
    fun isUsable_normalText_isUsable() {
        assertTrue(PromptPolicy.isUsable(PromptPolicy.sanitize("Good morning!")))
    }

    @Test
    fun isCallAllowed_firstEverCall_isAllowed() {
        assertTrue(PromptPolicy.isCallAllowed(lastCallEpochMs = 0L, nowEpochMs = 100_000L))
    }

    @Test
    fun isCallAllowed_tooSoon_isBlocked() {
        assertFalse(
            PromptPolicy.isCallAllowed(lastCallEpochMs = 100_000L, nowEpochMs = 100_500L, minIntervalMs = 60_000L)
        )
    }

    @Test
    fun isCallAllowed_afterInterval_isAllowed() {
        assertTrue(
            PromptPolicy.isCallAllowed(lastCallEpochMs = 100_000L, nowEpochMs = 200_000L, minIntervalMs = 60_000L)
        )
    }
}
