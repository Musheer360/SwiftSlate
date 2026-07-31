package com.musheer360.swiftslate.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class GeminiClient {

    companion object {
        private val HTTP_PREFIX_REGEX = Regex("^HTTP_\\d+:\\s*")
    }

    suspend fun validateKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream?.use { stream ->
                    val buf = ByteArray(1024)
                    while (stream.read(buf) != -1) { /* drain */ }
                }
                Result.success("Valid")
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)

                when (responseCode) {
                    429 -> Result.failure(Exception("Rate limited. Please try again later."))
                    400, 403 -> {
                        val detail = if (apiMessage.isNotEmpty()) apiMessage else "Invalid API key"
                        Result.failure(Exception(detail))
                    }
                    else -> {
                        val detail = if (apiMessage.isNotEmpty()) apiMessage else "Unexpected error"
                        Result.failure(Exception("Error $responseCode: $detail"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun generate(
        prompt: String,
        text: String,
        apiKey: String,
        model: String,
        temperature: Double,
        useStructuredOutput: Boolean = false,
        thinkingLevel: String? = null
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        var result = doGenerate(prompt, text, apiKey, model, temperature, useStructuredOutput, thinkingLevel)

        // Retry once for transient network/server errors (with 1.5s backoff)
        if (result.isFailure && result.exceptionOrNull().isTransientNetwork()) {
            kotlinx.coroutines.delay(1500)
            result = doGenerate(prompt, text, apiKey, model, temperature, useStructuredOutput, thinkingLevel)
        }

        val cleaned = stripHttpPrefix(result.map { it.text })
        val meta = result.getOrNull()
        cleaned.map { GenerateResult(it, meta?.structuredOutputFailed == true, meta?.truncated == true) }
    }

    private fun stripHttpPrefix(result: Result<String>): Result<String> {
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: ""
            val cleaned = msg.replaceFirst(HTTP_PREFIX_REGEX, "")
            if (cleaned != msg) return Result.failure(Exception(cleaned))
        }
        return result
    }

    private fun doGenerate(
        prompt: String,
        text: String,
        apiKey: String,
        model: String,
        temperature: Double,
        withStructured: Boolean,
        thinkingLevel: String? = null
    ): Result<GenerateResult> {
        var connection: HttpURLConnection? = null
        return try {
            val safeModel = model.replace(Regex("[^a-zA-Z0-9._-]"), "")
            connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val jsonBody = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", ApiClientUtils.SYSTEM_PROMPT_PREFIX + prompt)
                        })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", ApiClientUtils.wrapUserText(text))
                            })
                        })
                    })
                })
                put("safetySettings", JSONArray().apply {
                    for (cat in arrayOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT", "HARM_CATEGORY_CIVIC_INTEGRITY")) {
                        put(JSONObject().apply {
                            put("category", cat)
                            put("threshold", "BLOCK_NONE")
                        })
                    }
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", temperature)
                    // Spec-driven thinking control (mirrors Groq reasoning params).
                    // "minimal" keeps latency low; null => send no thinkingConfig.
                    if (thinkingLevel != null) {
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingLevel", thinkingLevel)
                        })
                    }
                    if (withStructured) {
                        put("responseMimeType", "application/json")
                        put("responseSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("text", JSONObject().apply {
                                    put("type", "string")
                                })
                            })
                            put("required", JSONArray().apply { put("text") })
                        })
                    }
                })
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)

                val jsonResponse = JSONObject(response)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)

                    val finishReason = candidate.optString("finishReason", "")
                    if (finishReason in setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "SPII", "BLOCKLIST")) {
                        return Result.failure(Exception("Response blocked by safety filters"))
                    }

                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        var resultText = parts.getJSONObject(0).optString("text", "")
                        if (resultText.isBlank()) {
                            return Result.failure(Exception("Model returned empty response"))
                        }

                        if (withStructured) {
                            val (extracted, parseFailed) = ApiClientUtils.tryExtractStructuredText(resultText)
                            if (extracted != null) return Result.success(GenerateResult(extracted))
                            // Same guard as the OpenAI-compatible client: never paste a raw
                            // JSON payload into the user's field when the structured response
                            // is unusable (parsed with no "text", or malformed/truncated JSON).
                            if (!parseFailed || resultText.trimStart().startsWith("{")) {
                                return Result.failure(Exception(
                                    "Model returned empty response (${ApiClientUtils.STRUCTURED_UNUSABLE_MARKER})"))
                            }
                        }

                        resultText = ApiClientUtils.stripMarkdownFences(resultText)
                        Result.success(GenerateResult(
                            resultText,
                            structuredOutputFailed = withStructured,
                            truncated = finishReason == "MAX_TOKENS"))
                    } else {
                        Result.failure(Exception("No content found in response"))
                    }
                } else {
                    // No candidates at all: when Gemini blocks the *prompt* rather than the
                    // response, the payload carries promptFeedback.blockReason and omits
                    // candidates entirely. Reporting this as an empty response told the user
                    // to "try again", which can never succeed.
                    val blockReason = jsonResponse.optJSONObject("promptFeedback")
                        ?.optString("blockReason", "") ?: ""
                    if (blockReason.isNotEmpty()) {
                        Result.failure(Exception("Response blocked by safety filters ($blockReason)"))
                    } else {
                        Result.failure(Exception("No candidates found in response"))
                    }
                }
            } else if (responseCode == 413) {
                // Request too large for this key's per-minute token budget. Groq enforces
                // TPM per organization, so another key (different org) may still have
                // headroom. Classified as a rate limit so the caller cools this key down
                // briefly and rotates, instead of hard-failing the whole command.
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Request too large"
                Result.failure(ApiException(ApiError.RequestTooLarge(detail), detail))
            } else if (responseCode == 429) {
                val retryAfter = connection.getHeaderField("Retry-After")
                val seconds = retryAfter?.toIntOrNull()
                val msg = if (seconds != null) "Rate limit exceeded, retry after ${seconds}s" else "Rate limit exceeded"
                Result.failure(ApiException(ApiError.RateLimit(msg, seconds), msg))
            } else if (responseCode == 400 || responseCode == 422) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                // Gemini reports invalid API keys as HTTP 400 (reason: API_KEY_INVALID).
                // Classify as InvalidKey so the caller marks the key and rotates to the next one.
                if (errorBody.contains("API_KEY_INVALID") ||
                    apiMessage.contains("API key not valid", ignoreCase = true)) {
                    val detail = if (apiMessage.isNotEmpty()) apiMessage else "Invalid API key"
                    Result.failure(ApiException(ApiError.InvalidKey(detail), detail))
                } else {
                    val detail = if (apiMessage.isNotEmpty()) apiMessage else "Bad request"
                    Result.failure(Exception("HTTP_${responseCode}: $detail"))
                }
            } else if (responseCode == 401 || responseCode == 403) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Invalid API key"
                Result.failure(ApiException(ApiError.InvalidKey(detail), detail))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                var detail = ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "Unexpected error (HTTP $responseCode)")
                // Mirror the OpenAI-compatible client: normalize an unknown/inaccessible model
                // onto the existing translated "model not found" string instead of raw English.
                val providerCode = ApiClientUtils.extractApiErrorCode(errorBody)
                if (responseCode == 404 || providerCode == "model_not_found") {
                    detail = "Model not found. $detail"
                }
                val apiError = if (responseCode in 500..599) ApiError.ServerError(detail) else ApiError.Other(detail)
                Result.failure(ApiException(apiError, detail))
            }
        } catch (e: Exception) {
            val apiError = when (e) {
                is ApiException -> e.apiError
                is SocketTimeoutException, is UnknownHostException, is ConnectException, is java.net.SocketException -> ApiError.Network(e.message ?: "Network error")
                is org.json.JSONException -> ApiError.Other("Invalid response from server")
                else -> ApiError.Other(e.message ?: "Unknown error")
            }
            if (e is ApiException) Result.failure(e) else Result.failure(ApiException(apiError, e.message ?: "Unknown error"))
        } finally {
            connection?.disconnect()
        }
    }
}
