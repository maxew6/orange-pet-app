package com.orangepet.app.ai

import com.orangepet.app.behavior.GreetingPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gemini-backed [PetBrain].
 *
 * **Implementation note — deliberately not the official SDK.** This talks
 * to the Gemini REST endpoint directly via `HttpURLConnection` + `org.json`
 * (both built into the Android SDK) instead of the
 * `com.google.ai.client.generativeai` / Firebase AI client library. This
 * project was built in a sandbox with no access to Google's Maven
 * repository, so a new SDK dependency's exact artifact coordinates and API
 * surface could not be verified against a real build — and the person
 * building this explicitly prioritized a GitHub Actions build with zero
 * dependency-resolution surprises. A REST call needs no new Gradle
 * dependency at all, which removes that entire risk category. If you'd
 * rather use the official SDK, it's a contained swap: everything else in
 * the app depends only on the [PetBrain] interface.
 *
 * Every failure mode (missing/invalid key, offline, timeout, malformed
 * response, rate limit) falls back to [fallback] rather than throwing —
 * per spec, a greeting failing to generate must never crash or block the
 * overlay.
 */
class GeminiPetBrain(
    private val apiKey: String,
    private val modelName: String = "gemini-2.5-flash",
    private val fallback: PetBrain = OfflinePetBrain()
) : PetBrain {

    @Volatile
    private var lastCallEpochMs: Long = 0L

    override suspend fun createGreeting(userName: String, period: GreetingPeriod): String {
        if (apiKey.isBlank()) return fallback.createGreeting(userName, period)

        val now = System.currentTimeMillis()
        if (!PromptPolicy.isCallAllowed(lastCallEpochMs, now)) {
            // Rate-limited: never call Gemini more than once per interval.
            return fallback.createGreeting(userName, period)
        }

        return try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val raw = callGeminiRest(userName, period)
                    lastCallEpochMs = System.currentTimeMillis()
                    val sanitized = PromptPolicy.sanitize(raw)
                    if (PromptPolicy.isUsable(sanitized)) sanitized else fallback.createGreeting(userName, period)
                }
            }
        } catch (t: Throwable) {
            // Network failure, timeout, HTTP error, malformed JSON, etc. —
            // all treated the same way: fall back, never propagate.
            fallback.createGreeting(userName, period)
        }
    }

    private fun callGeminiRest(userName: String, period: GreetingPeriod): String {
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = URL("$BASE_URL/$modelName:generateContent?key=$encodedKey")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            val requestBody = buildRequestBody(userName, period)
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Gemini request failed: HTTP $responseCode")
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            return parseGreetingText(responseText)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * No screen contents, notifications, passwords, or other private data
     * ever go into this prompt — only the user's display name (which the
     * user typed into Settings themselves) and which part of the day it
     * is.
     */
    private fun buildRequestBody(userName: String, period: GreetingPeriod): String {
        val name = userName.ifBlank { "friend" }
        val situation = when (period) {
            GreetingPeriod.MORNING -> "It is morning."
            GreetingPeriod.LUNCH -> "It is lunchtime."
            GreetingPeriod.NIGHT -> "It is bedtime."
        }
        val prompt = "You are a tiny, cheerful desktop pet speaking directly to your owner, " +
            "$name. $situation Write exactly one short, warm sentence, under 15 words. " +
            "Plain text only: no markdown, no emoji, no quotation marks."

        val body = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    put("temperature", 0.7)
                }
            )
        }
        return body.toString()
    }

    private fun parseGreetingText(responseJson: String): String {
        val json = JSONObject(responseJson)
        val candidates = json.optJSONArray("candidates")
            ?: throw IOException("Gemini response had no candidates")
        val firstCandidate = candidates.optJSONObject(0)
            ?: throw IOException("Gemini response had an empty candidate")
        val content = firstCandidate.optJSONObject("content")
            ?: throw IOException("Gemini candidate had no content")
        val parts = content.optJSONArray("parts")
            ?: throw IOException("Gemini content had no parts")
        val text = parts.optJSONObject(0)?.optString("text")
        if (text.isNullOrBlank()) throw IOException("Gemini response had no text")
        return text
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val REQUEST_TIMEOUT_MS = 8_000L
        private const val MAX_OUTPUT_TOKENS = 60
    }
}
