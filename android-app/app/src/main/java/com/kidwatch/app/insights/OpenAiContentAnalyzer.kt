package com.kidwatch.app.insights

import com.kidwatch.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenAiContentAnalyzer {

    data class AnalysisResult(
        val channel: String,
        val label: String,
        val reason: String
    )

    suspend fun assessChannelsForYoungKids(channels: List<String>): List<AnalysisResult> =
        withContext(Dispatchers.IO) {
            val normalizedChannels = channels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (normalizedChannels.isEmpty()) return@withContext emptyList()

            val apiKey = BuildConfig.OPENAI_API_KEY
            if (apiKey.isBlank()) {
                return@withContext normalizedChannels.map {
                    AnalysisResult(it, "unknown", "OPENAI_API_KEY missing")
                }
            }

            val responseBody = runCatching {
                requestChatCompletion(apiKey, normalizedChannels)
            }.getOrElse {
                return@withContext normalizedChannels.map { channel ->
                    AnalysisResult(channel, "unknown", it.message ?: "Analysis request failed")
                }
            }

            parseStructuredResponse(normalizedChannels, responseBody)
        }

    private fun requestChatCompletion(apiKey: String, channels: List<String>): String {
        val endpoint = URL("https://api.openai.com/v1/chat/completions")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val prompt = buildPrompt(channels)
        val payload = JSONObject()
            .put("model", "gpt-4o-mini")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "You are a child safety content classifier."))
                    .put(JSONObject().put("role", "user").put("content", prompt))
            )
            .put("temperature", 0.2)

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(payload.toString())
        }

        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = BufferedReader(stream.reader()).readText()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("OpenAI API error ${connection.responseCode}: $body")
        }
        return body
    }

    private fun buildPrompt(channels: List<String>): String {
        return """
            Assess whether the following YouTube channels are safe for children aged 2-6.
            Allowed labels: safe, overstimulating, addictive, unknown.
            Return strict JSON in this schema:
            {
              "assessments": [
                {"channel":"<name>","label":"safe|overstimulating|addictive|unknown","reason":"<short reason>"}
              ]
            }
            Channels:
            ${channels.joinToString("\n") { "- $it" }}
        """.trimIndent()
    }

    private fun parseStructuredResponse(channels: List<String>, rawBody: String): List<AnalysisResult> {
        val content = JSONObject(rawBody)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()

        val parsed = runCatching { JSONObject(content) }.getOrNull()
        val assessments = parsed?.optJSONArray("assessments")

        if (assessments == null) {
            return channels.map { AnalysisResult(it, "unknown", "Could not parse model output") }
        }

        val byChannel = mutableMapOf<String, AnalysisResult>()
        for (index in 0 until assessments.length()) {
            val item = assessments.optJSONObject(index) ?: continue
            val channel = item.optString("channel").trim()
            if (channel.isBlank()) continue
            val label = item.optString("label").trim().lowercase().ifBlank { "unknown" }
            val reason = item.optString("reason").trim()
            byChannel[channel] = AnalysisResult(channel, label, reason.ifBlank { "No rationale returned" })
        }

        return channels.map { channel ->
            byChannel[channel] ?: AnalysisResult(channel, "unknown", "No assessment returned for channel")
        }
    }
}
