package com.kidwatch.app.insights

import com.kidwatch.app.BuildConfig
import com.kidwatch.app.data.local.entity.ContentAnalysisEntity
import com.kidwatch.app.data.local.entity.VideoEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenAiSessionSuitabilityAnalyzer {

    data class SessionInput(
        val appName: String,
        val packageName: String,
        val durationMs: Long,
        val attentionLevel: String,
        val screenshotCount: Int,
        val faceObservationCount: Int,
        val identityLabel: String?,
        val targetAges: List<Int>,
        val assignedPersonName: String?,
        val assignedPersonRole: String?,
        val videos: List<VideoEventEntity>,
        val analyses: List<ContentAnalysisEntity>
    )

    data class SuitabilityResult(
        val kidFriendlyScore: Int,
        val attentionLevel: String,
        val headline: String,
        val explanation: String,
        val recommendedAction: String
    )

    suspend fun assessSession(input: SessionInput): SuitabilityResult? =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.OPENAI_API_KEY
            if (apiKey.isBlank()) return@withContext null
            val responseBody = runCatching {
                requestChatCompletion(apiKey, input)
            }.getOrNull() ?: return@withContext null
            parseStructuredResponse(responseBody)
        }

    private fun requestChatCompletion(apiKey: String, input: SessionInput): String {
        val endpoint = URL("https://api.openai.com/v1/chat/completions")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("kidFriendlyScore", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 10))
                    .put(
                        "attentionLevel",
                        JSONObject().put("type", "string").put("enum", JSONArray().put("normal").put("watch").put("review_now"))
                    )
                    .put("headline", JSONObject().put("type", "string"))
                    .put("explanation", JSONObject().put("type", "string"))
                    .put("recommendedAction", JSONObject().put("type", "string"))
            )
            .put(
                "required",
                JSONArray()
                    .put("kidFriendlyScore")
                    .put("attentionLevel")
                    .put("headline")
                    .put("explanation")
                    .put("recommendedAction")
            )
            .put("additionalProperties", false)

        val payload = JSONObject()
            .put("model", MODEL_NAME)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().put(
                            "role",
                            "system"
                        ).put(
                            "content",
                            "You are an age-suitability classifier for children's mobile content sessions. Score how suitable a session is for the described child age context from 1 to 10, where 10 is strongly kid-friendly and age-appropriate."
                        )
                    )
                    .put(JSONObject().put("role", "user").put("content", buildPrompt(input)))
            )
            .put(
                "response_format",
                JSONObject()
                    .put("type", "json_schema")
                    .put(
                        "json_schema",
                        JSONObject()
                            .put("name", "kidwatch_session_suitability")
                            .put("strict", true)
                            .put("schema", schema)
                    )
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

    private fun buildPrompt(input: SessionInput): String {
        val ageContext = when {
            input.assignedPersonRole == "child" && !input.assignedPersonName.isNullOrBlank() && input.targetAges.size == 1 ->
                "Known child in this session: ${input.assignedPersonName}, age ${input.targetAges.first()}."
            input.targetAges.isNotEmpty() ->
                "Known child ages using this phone: ${input.targetAges.joinToString(", ")}."
            else -> "No child ages are set yet. Assume the app is used by children aged 2 to 8."
        }
        val videos = if (input.videos.isEmpty()) {
            "- No captured titles"
        } else {
            input.videos.take(8).joinToString("\n") { "- ${it.title} | ${it.channel}" }
        }
        val analyses = if (input.analyses.isEmpty()) {
            "- No prior channel analysis"
        } else {
            input.analyses.take(8).joinToString("\n") { "- ${it.channel}: ${it.label} (${it.reason})" }
        }
        return """
            Judge how suitable this mobile session was for the child age context.

            $ageContext
            App: ${input.appName} (${input.packageName})
            Duration minutes: ${input.durationMs / 60000}
            Existing attention signal: ${input.attentionLevel}
            Screenshots captured: ${input.screenshotCount}
            Face observations: ${input.faceObservationCount}
            Resolved viewer label: ${input.identityLabel ?: "unknown"}

            Captured titles:
            $videos

            Prior channel analysis:
            $analyses

            Return:
            - kidFriendlyScore from 1 to 10
            - attentionLevel as normal, watch, or review_now
            - headline as a short parent-readable summary
            - explanation as why the score fits the age context
            - recommendedAction as the next parenting step
        """.trimIndent()
    }

    private fun parseStructuredResponse(rawBody: String): SuitabilityResult? {
        val content = JSONObject(rawBody)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        val parsed = runCatching { JSONObject(extractJsonObject(content)) }.getOrNull() ?: return null
        val score = parsed.optInt("kidFriendlyScore", -1).takeIf { it in 1..10 } ?: return null
        val level = parsed.optString("attentionLevel").trim().lowercase()
            .takeIf { it == "normal" || it == "watch" || it == "review_now" } ?: return null
        val headline = parsed.optString("headline").trim().ifBlank { return null }
        val explanation = parsed.optString("explanation").trim().ifBlank { return null }
        val recommendedAction = parsed.optString("recommendedAction").trim().ifBlank { return null }
        return SuitabilityResult(
            kidFriendlyScore = score,
            attentionLevel = level,
            headline = headline,
            explanation = explanation,
            recommendedAction = recommendedAction
        )
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        if (trimmed.startsWith("```")) {
            return trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }
        return trimmed
    }

    companion object {
        const val MODEL_NAME = "gpt-4o-mini"
        const val ANALYSIS_MODEL = "openai:gpt-4o-mini:session-age-v1"

        fun isConfigured(): Boolean = BuildConfig.OPENAI_API_KEY.isNotBlank()
    }
}
