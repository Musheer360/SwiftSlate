package com.musheer360.swiftslate.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OpenAICompatibleClient {

    companion object {
        private val HTTP_CODE_REGEX = Regex("^HTTP_(\\d+):")
        private val HTTP_PREFIX_REGEX = Regex("^HTTP_\\d+:\\s*")
    }

    @Volatile
    var structuredOutputFailed = false

    suspend fun validateKey(apiKey: String, endpoint: String): Result<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val baseUrl = endpoint.trimEnd('/')
            connection = URL("$baseUrl/models")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
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
                    401, 403 -> {
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
        endpoint: String,
        useStructuredOutput: Boolean = false,
        useJsonObjectMode: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        structuredOutputFailed = false

        val result = doGenerate(prompt, text, apiKey, model, temperature, endpoint, useStructuredOutput, useJsonObjectMode)

        if (useStructuredOutput && result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: ""
            val code = HTTP_CODE_REGEX.find(msg)?.groupValues?.get(1)?.toIntOrNull()
            if (code == 400 || code == 422) {
                val retry = doGenerate(prompt, text, apiKey, model, temperature, endpoint, false, false)
                if (retry.isSuccess) {
                    structuredOutputFailed = true
                }
                return@withContext stripHttpPrefix(retry)
            }
        }

        stripHttpPrefix(result)
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
        endpoint: String,
        withStructured: Boolean,
        withJsonObject: Boolean = false
    ): Result<String> {
        var connection: HttpURLConnection? = null
        return try {
            val baseUrl = endpoint.trimEnd('/')
            connection = URL("$baseUrl/chat/completions")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val systemContent = if (withJsonObject) {
                ApiClientUtils.SYSTEM_PROMPT_PREFIX + prompt + " Respond with JSON: {\"text\": \"your result\"}"
            } else {
                ApiClientUtils.SYSTEM_PROMPT_PREFIX + prompt
            }

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemContent)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "---BEGIN TEXT---\n$text\n---END TEXT---")
                    })
                })
                put("temperature", temperature)
                if (withStructured) {
                    put("response_format", JSONObject().apply {
                        put("type", "json_schema")
                        put("json_schema", JSONObject().apply {
                            put("name", "text_output")
                            put("schema", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("text", JSONObject().apply {
                                        put("type", "string")
                                    })
                                })
                                put("required", JSONArray().apply { put("text") })
                            })
                        })
                    })
                } else if (withJsonObject) {
                    put("response_format", JSONObject().apply {
                        put("type", "json_object")
                    })
                }
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)

                val jsonResponse = JSONObject(response)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.getJSONObject(0)

                    val finishReason = choice.optString("finish_reason", "")
                    if (finishReason == "content_filter") {
                        return Result.failure(Exception("Response blocked by content filter"))
                    }

                    val message = choice.optJSONObject("message")
                    var resultText = message?.optString("content", "") ?: ""
                    if (resultText.isBlank()) {
                        return Result.failure(Exception("Model returned empty response"))
                    }

                    if (withStructured || withJsonObject) {
                        val (extracted, _) = ApiClientUtils.tryExtractStructuredText(resultText)
                        if (extracted != null) return Result.success(extracted)
                        if (withStructured) structuredOutputFailed = true
                    }

                    resultText = ApiClientUtils.stripMarkdownFences(resultText)
                    if (finishReason == "length") {
                        resultText += "\n\n[Note: Response may be truncated]"
                    }
                    Result.success(resultText)
                } else {
                    Result.failure(Exception("No choices found in response"))
                }
            } else if (responseCode == 429) {
                val retryAfter = connection.getHeaderField("Retry-After")
                val seconds = retryAfter?.toLongOrNull()
                val msg = if (seconds != null) "Rate limit exceeded, retry after ${seconds}s" else "Rate limit exceeded"
                Result.failure(Exception(msg))
            } else if (responseCode == 400 || responseCode == 422) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Bad request"
                Result.failure(Exception("HTTP_${responseCode}: $detail"))
            } else if (responseCode == 401 || responseCode == 403) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Invalid API key"
                Result.failure(Exception(detail))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val detail = ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "Unexpected error")
                Result.failure(Exception(detail))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
