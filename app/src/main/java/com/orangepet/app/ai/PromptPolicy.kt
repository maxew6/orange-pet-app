package com.orangepet.app.ai

/**
 * Guardrails applied to every AI-generated string before it's shown in the
 * overlay speech bubble, and timing math for rate-limiting AI calls.
 * Entirely pure (no `android.*`, no network) so it's unit-testable and so
 * it can be reused identically by both [GeminiPetBrain] and anything that
 * calls it.
 */
object PromptPolicy {

    const val MAX_GREETING_LENGTH = 120
    const val MIN_MILLIS_BETWEEN_AI_CALLS = 60_000L // at most one Gemini call per minute

    /**
     * Strips markup-like characters, collapses whitespace/newlines to single
     * spaces, trims, and hard-truncates to [maxLength]. Plain text in,
     * plain text out — this is what "ask for plain text only, strip
     * unsupported markup" means in practice for a small overlay bubble.
     */
    fun sanitize(text: String, maxLength: Int = MAX_GREETING_LENGTH): String {
        val noMarkup = text
            .replace(Regex("<[^>]*>"), "") // HTML-ish tags
            .replace(Regex("[*_`#>~\\[\\]]"), "") // common markdown symbols
        val collapsed = noMarkup.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= maxLength) {
            collapsed
        } else {
            collapsed.take(maxLength - 1).trimEnd() + "…"
        }
    }

    /** A sanitized result is unusable if there's nothing left after stripping. */
    fun isUsable(sanitized: String): Boolean = sanitized.isNotBlank()

    /**
     * True if enough time has passed since the last AI call to allow
     * another one. Pure function of three timestamps/durations — the
     * caller supplies "now" so this is trivially testable without mocking
     * a clock.
     */
    fun isCallAllowed(
        lastCallEpochMs: Long,
        nowEpochMs: Long,
        minIntervalMs: Long = MIN_MILLIS_BETWEEN_AI_CALLS
    ): Boolean = lastCallEpochMs <= 0L || (nowEpochMs - lastCallEpochMs) >= minIntervalMs
}
