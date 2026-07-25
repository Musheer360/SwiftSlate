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

class OpenAICompatibleClient {

    companion object {
        private val HTTP_CODE_REGEX = Regex("^HTTP_(\\d+):")
        private val HTTP_PREFIX_REGEX = Regex("^HTTP_\\d+:\\s*")
    }

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
        useJsonObjectMode: Boolean = false,
        extraParams: Map<String, Any> = emptyMap()
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        var soFailed = false
        // Whether a tuning param we sent was *specifically* named as rejected by the provider
        // and dropped to succeed. Only set when the provider's message names one of our
        // params — see the ladder below.
        var tuningDegraded = false
        var rejectedParam: String? = null
        // Optional provider tuning (e.g. Groq reasoning params). Threaded as a var so
        // that if the provider rejects it, we can drop it and keep degraded retries consistent.
        var effectiveExtras = extraParams
        var jsonMode = useJsonObjectMode
        // Guards against re-sending a byte-identical request: without them the ladder's
        // steps 2 and 3 (and step 1 vs the structured fallback) issued the same payload
        // twice, burning quota against an 8,000 TPM ceiling for no chance of a new outcome.
        var triedWithoutTuning = false
        var triedWithoutStructured = false

        var result = doGenerate(prompt, text, apiKey, model, temperature, endpoint, useStructuredOutput, jsonMode, effectiveExtras)

        // Retry once for transient network errors (with backoff)
        if (result.isFailure && result.exceptionOrNull().isTransientNetwork()) {
            kotlinx.coroutines.delay(2000)
            result = doGenerate(prompt, text, apiKey, model, temperature, endpoint, useStructuredOutput, jsonMode, effectiveExtras)
        }

        // Degradation ladder. Order matters: attribute the failure to the thing that actually
        // caused it, so the user is told the truth and the right feature gets disabled.
        //
        // 1. JSON-mode failure. Groq answers HTTP 400 json_validate_failed when the model
        //    cannot finish a valid JSON object, and a 200 with an unusable payload is treated
        //    the same way. Both are fixed by dropping JSON mode. This case used to fall into
        //    step 3 below, where a successful retry was misreported as a rejected model
        //    setting — the single biggest source of that bogus toast.
        if (result.isFailure && jsonMode && ApiClientUtils.isJsonModeFailure(errorTextOf(result))) {
            triedWithoutStructured = true
            val retry = doGenerate(prompt, text, apiKey, model, temperature, endpoint, false, false, effectiveExtras)
            if (retry.isSuccess) {
                result = retry
                jsonMode = false
                soFailed = true // disables JSON mode for 24h via the caller
            }
            else result = preferActionableFailure(result, retry)
        }

        // 2. Genuine tuning rejection: the provider named one of the params we sent, so our
        //    model catalog is stale. This is the only path that may claim so to the user.
        if (result.isFailure && effectiveExtras.isNotEmpty() && isBadRequest(result) &&
            ApiClientUtils.namesTuningParam(errorTextOf(result), effectiveExtras.keys)) {
            val named = ApiClientUtils.namedTuningParam(errorTextOf(result), effectiveExtras.keys)
            triedWithoutTuning = true
            val degraded = doGenerate(prompt, text, apiKey, model, temperature, endpoint, useStructuredOutput, jsonMode, emptyMap())
            if (degraded.isSuccess) {
                result = degraded
                effectiveExtras = emptyMap()
                tuningDegraded = true
                rejectedParam = named
            }
            else result = preferActionableFailure(result, degraded)
        }

        // 3. Unattributed 4xx with tuning params sent. Still worth one blind retry without
        //    them (working beats failing), but deliberately does NOT set tuningDegraded: we
        //    do not know that the tuning was at fault, so we must not say it was.
        //    Skipped when step 2 already sent this exact payload and it failed.
        if (result.isFailure && effectiveExtras.isNotEmpty() && isBadRequest(result) && !triedWithoutTuning) {
            triedWithoutTuning = true
            val degraded = doGenerate(prompt, text, apiKey, model, temperature, endpoint, useStructuredOutput, jsonMode, emptyMap())
            if (degraded.isSuccess) {
                result = degraded
                effectiveExtras = emptyMap()
            }
            else result = preferActionableFailure(result, degraded)
        }

        val finalResult = if (useStructuredOutput && result.isFailure) {
            // Only if step 1 has not already sent the identical no-structured/no-JSON request.
            if (isBadRequest(result) && !triedWithoutStructured) {
                val retry = doGenerate(prompt, text, apiKey, model, temperature, endpoint, false, false, effectiveExtras)
                if (retry.isSuccess) soFailed = true
                stripHttpPrefix(retry.map { it.first })
            } else {
                stripHttpPrefix(result.map { it.first })
            }
        } else {
            if (result.isSuccess && result.getOrNull()?.second == true) soFailed = true
            stripHttpPrefix(result.map { it.first })
        }

        finalResult.map { GenerateResult(it, soFailed, tuningDegraded, rejectedParam) }
    }

    /**
     * Keeps [retry]'s failure when it carries an [ApiException] the caller can act on
     * (rate limit, invalid key, server error). Ladder steps previously kept only successes,
     * so a retry that surfaced a 401 or 429 had that error silently discarded and the command
     * was reported with the original error and never rotated to another key.
     */
    private fun preferActionableFailure(original: Result<Pair<String, Boolean>>, retry: Result<Pair<String, Boolean>>): Result<Pair<String, Boolean>> =
        if (retry.isFailure && retry.exceptionOrNull() is ApiException) retry else original

    /** Failure message of [result], for attributing *why* a request was rejected. */
    private fun errorTextOf(result: Result<*>): String = result.exceptionOrNull()?.message ?: ""

    /** True if a failed result carries an HTTP 400/422 (bad-request) marker. */
    private fun isBadRequest(result: Result<*>): Boolean {
        if (result.isSuccess) return false
        val code = HTTP_CODE_REGEX.find(result.exceptionOrNull()?.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
        return code == 400 || code == 422
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
        withJsonObject: Boolean = false,
        extraParams: Map<String, Any> = emptyMap()
    ): Result<Pair<String, Boolean>> {
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
                val safeModel = model.replace(Regex("[^a-zA-Z0-9._\\-/: ]"), "")
                put("model", safeModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemContent)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", ApiClientUtils.wrapUserText(text))
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
                // Extra provider-specific params (e.g. Groq reasoning controls),
                // resolved by the caller from the active provider config. Empty
                // for providers/models that take none, so nothing is sent.
                extraParams.forEach { (k, v) -> put(k, v) }
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
                        val (extracted, parseFailed) = ApiClientUtils.tryExtractStructuredText(resultText)
                        if (extracted != null) return Result.success(Pair(extracted, false))
                        // Do not fall through with a raw JSON payload — that pasted literal
                        // JSON such as {"text": ""} (parsed, no usable field) or a truncated
                        // object (unparseable but clearly JSON) into the user's text field.
                        if (!parseFailed || resultText.trimStart().startsWith("{")) {
                            return Result.failure(Exception(
                                "Model returned empty response (${ApiClientUtils.STRUCTURED_UNUSABLE_MARKER})"))
                        }
                        // Genuinely not JSON: the model ignored response_format. Use the text,
                        // but flag it so the caller disables JSON mode for 24h. Previously only
                        // the withStructured branch did this, so json_object mode never got
                        // disabled and kept corrupting output on every request.
                        resultText = ApiClientUtils.stripMarkdownFences(resultText)
                        if (finishReason == "length") resultText += "\n\n[Note: Response may be truncated]"
                        return Result.success(Pair(resultText, true))
                    }

                    resultText = ApiClientUtils.stripMarkdownFences(resultText)
                    if (finishReason == "length") {
                        resultText += "\n\n[Note: Response may be truncated]"
                    }
                    Result.success(Pair(resultText, false))
                } else {
                    Result.failure(Exception("No choices found in response"))
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
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Bad request"
                Result.failure(Exception("HTTP_${responseCode}: $detail"))
            } else if (responseCode == 401 || responseCode == 403) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Invalid API key"
                Result.failure(ApiException(ApiError.InvalidKey(detail), detail))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                var detail = ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "Unexpected error (HTTP $responseCode)")
                // Groq reports an unknown or inaccessible model as HTTP 404 with the reason only
                // in error.code ("model_not_found"); its message ("The model `x` does not exist or
                // you do not have access to it.") matches none of the localized patterns, so the
                // user was shown raw English. Normalize onto the existing translated string.
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

