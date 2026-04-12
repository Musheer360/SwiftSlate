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
        private val HTTP_CODE_REGEX = Regex("^HTTP_(\\d+):")
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
        useStructuredOutput: Boolean = false
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        var soFailed = false

        var result = doGenerate(prompt, text, apiKey, model, temperature, useStructuredOutput)

        // Retry once for transient network errors
        if (result.isFailure && result.exceptionOrNull().isTransientNetwork()) {
            kotlinx.coroutines.delay(1000)
            result = doGenerate(prompt, text, apiKey, model, temperature, useStructuredOutput)
        }

        val finalResult = if (useStructuredOutput && result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: ""
            val code = HTTP_CODE_REGEX.find(msg)?.groupValues?.get(1)?.toIntOrNull()
            if (code == 400 || code == 422) {
                val retry = doGenerate(prompt, text, apiKey, model, temperature, false)
                if (retry.isSuccess) soFailed = true
                stripHttpPrefix(retry.map { it.first })
            } else {
                stripHttpPrefix(result.map { it.first })
            }
        } else {
            if (result.isSuccess && result.getOrNull()?.second == true) soFailed = true
            stripHttpPrefix(result.map { it.first })
        }

        finalResult.map { GenerateResult(it, soFailed) }
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
        withStructured: Boolean
    ): Result<Pair<String, Boolean>> {
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
                                put("text", "---BEGIN TEXT---\n$text\n---END TEXT---")
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", temperature)
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
                            val (extracted, _) = ApiClientUtils.tryExtractStructuredText(resultText)
                            if (extracted != null) return Result.success(Pair(extracted, false))
                        }

                        resultText = ApiClientUtils.stripMarkdownFences(resultText)
                        if (finishReason == "MAX_TOKENS") {
                            resultText += "\n\n[Note: Response may be truncated]"
                        }
                        Result.success(Pair(resultText, withStructured))
                    } else {
                        Result.failure(Exception("No content found in response"))
                    }
                } else {
                    Result.failure(Exception("No candidates found in response"))
                }
            } else if (responseCode == 429) {
                val retryAfter = connection.getHeaderField("Retry-After")
                val seconds = retryAfter?.toIntOrNull()
                val msg = if (seconds != null) "Rate limit exceeded, retry after ${seconds}s" else "Rate limit exceeded"
                Result.failure(ApiException(ApiError.RateLimit(msg, seconds), msg))
            } else if (responseCode == 400 || responseCode == 422) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Bad request"
                Result.failure(Exception("HTTP_${responseCode}: $detail"))
            } else if (responseCode == 401 || responseCode == 403) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Invalid API key"
                Result.failure(ApiException(ApiError.InvalidKey(detail), detail))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val detail = ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "Unexpected error")
                val apiError = if (responseCode in 500..599) ApiError.ServerError(detail) else ApiError.Other(detail)
                Result.failure(ApiException(apiError, detail))
            }
        } catch (e: Exception) {
            val apiError = when (e) {
                is ApiException -> e.apiError
                is SocketTimeoutException, is UnknownHostException, is ConnectException -> ApiError.Network(e.message ?: "Network error")
                is org.json.JSONException -> ApiError.Other("Invalid response from server")
                else -> ApiError.Other(e.message ?: "Unknown error")
            }
            if (e is ApiException) Result.failure(e) else Result.failure(ApiException(apiError, e.message ?: "Unknown error"))
        } finally {
            connection?.disconnect()
        }
    }
}
