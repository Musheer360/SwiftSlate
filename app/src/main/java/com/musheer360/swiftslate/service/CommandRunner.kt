package com.musheer360.swiftslate.service

import android.content.Context
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.api.ApiClientUtils
import com.musheer360.swiftslate.api.ApiError
import com.musheer360.swiftslate.api.ApiException
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.provider.Providers
import com.musheer360.swiftslate.provider.Transport
import java.util.Locale

sealed interface CommandOutcome {
    data class Success(val text: String) : CommandOutcome
    /** The model refused in-band. Its answer, not a fault — re-running the same prompt won't help. */
    data object Refusal : CommandOutcome
    /** Nothing was sent and nothing will be until the user changes something. */
    data class Unavailable(val message: String) : CommandOutcome
    /** A request was attempted and failed. Retrying may work. */
    data class Failure(val message: String) : CommandOutcome
}

private const val DEFAULT_TEMPERATURE = 0.5f
private const val STRUCTURED_OUTPUT_RETRY_MS = 86_400_000L // re-try structured output after 24h

/**
 * Everything a trigger command does between "user asked" and "text came back": provider
 * resolution, key rotation, rate-limit benching and error mapping. Both entry points call this
 * — the accessibility service for a typed `?trigger`, the text-selection sheet for a tapped
 * one — so a fix to the request policy lands in both at once.
 *
 * Knows nothing about how the result is delivered: no nodes, no toasts, no UI state. Suspends
 * on the caller's dispatcher and reads disk (prefs, Keystore), so call it off the main thread.
 *
 * @param onFirstAttempt run just before the first request actually goes out — after it is known
 *   that a usable key exists. The service starts its inline spinner here.
 */
suspend fun runTextCommand(
    context: Context,
    keyManager: KeyManager,
    geminiClient: GeminiClient,
    openAIClient: OpenAICompatibleClient,
    prompt: String,
    text: String,
    onFirstAttempt: () -> Unit = {}
): CommandOutcome {
    // keys_keystore_error rather than a "reinstall" message: the usual cause is the Keystore key
    // being invalidated by a lock-screen change, where re-adding the keys is enough.
    if (!keyManager.keystoreAvailable) {
        return CommandOutcome.Unavailable(context.getString(R.string.keys_keystore_error))
    }

    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val provider = Providers.forType(prefs.getString(PrefKeys.PROVIDER_TYPE, null))
    val model = provider.sanitizeModel(prefs.getString(provider.modelPrefKey, provider.defaultModel))
    val endpoint = provider.resolveEndpoint(prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "")
    if (!provider.isConfigured(model, endpoint)) {
        return CommandOutcome.Unavailable(context.getString(R.string.toast_custom_not_configured))
    }
    val temperature = prefs.getFloat(PrefKeys.TEMPERATURE, DEFAULT_TEMPERATURE).toDouble()
    val useStructuredOutput = System.currentTimeMillis() -
        prefs.getLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, 0L) > STRUCTURED_OUTPUT_RETRY_MS

    var lastErrorMsg: String? = null
    var lastErrorWasRateLimit = false
    var lastErrorWasPermission = false
    var lastFailedKey: String? = null
    var started = false
    val tried = mutableSetOf<String>()

    // One attempt per configured key; getNextKey skips the ones already tried, plus any that are
    // benched for rate limiting or known-invalid.
    val maxAttempts = keyManager.getKeys().size.coerceAtLeast(1)
    while (tried.size < maxAttempts) {
        val key = keyManager.getNextKey(tried) ?: break
        tried.add(key)
        if (!started) {
            started = true
            onFirstAttempt()
        }

        val result = when (provider.transport) {
            Transport.OPENAI_COMPAT -> openAIClient.generate(
                prompt, text, key, model, temperature, endpoint,
                useJsonObjectMode = provider.useJsonObjectMode(useStructuredOutput),
                extraParams = provider.reasoningParams(model))
            Transport.GEMINI_NATIVE -> geminiClient.generate(
                prompt, text, key, model, temperature, useStructuredOutput,
                thinkingLevel = provider.thinkingLevel(model))
        }

        result.onSuccess { generated ->
            if (ApiClientUtils.isModelRefusal(generated.text)) return CommandOutcome.Refusal
            if (generated.structuredOutputFailed) {
                prefs.edit()
                    .putLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, System.currentTimeMillis())
                    .apply()
            }
            // Keep the truncation warning localized and shared by both entry points rather than
            // leaving callers to duplicate it (or clients to inject an English-only string).
            val outputText = if (generated.truncated) {
                generated.text + "\n\n" + context.getString(R.string.note_response_truncated)
            } else {
                generated.text
            }
            return CommandOutcome.Success(outputText)
        }

        val error = result.exceptionOrNull()
        val msg = error?.message ?: ""
        lastErrorMsg = msg
        when (val apiError = (error as? ApiException)?.apiError) {
            is ApiError.RateLimit -> {
                lastErrorWasRateLimit = true
                keyManager.reportRateLimit(key, apiError.retryAfterSeconds?.toLong() ?: 60)
            }
            is ApiError.InvalidKey -> {
                lastErrorWasRateLimit = false
                // Server-side sign-in failures (Ollama Cloud) are not the key's fault:
                // don't bench it, don't record it as a failed key, and don't let an
                // earlier iteration's permission verdict override the sign-in message.
                if (msg.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER)) {
                    lastFailedKey = null
                    lastErrorWasPermission = false
                } else {
                    lastFailedKey = key
                    // Distinguish "this key is bad" from "this key may not use this model" (both
                    // arrive as 401/403) so the final message names the right fix.
                    val m = msg.lowercase(Locale.ROOT)
                    lastErrorWasPermission = m.contains("permission") ||
                        m.contains("does not have access") || m.contains("not been used in project")
                    // Never bench the last remaining key: with no fallback to rotate to, the
                    // 15-minute invalid mark just turned every later trigger into "all keys
                    // invalid" with no recovery path until a process restart.
                    if (keyManager.getKeys().size > 1) {
                        keyManager.markInvalid(key)
                    }
                }
            }
            // 5xx — try the next key.
            is ApiError.ServerError -> lastErrorWasRateLimit = false
            else -> {
                // Rotating keys cannot help: RequestTooLarge is a per-account token budget, the
                // rest are non-retryable. Clear the flag so a 400 arriving after an earlier 429
                // is not reported as a rate limit with a bogus countdown.
                lastErrorWasRateLimit = false
                break
            }
        }
    }

    val waitMs = keyManager.getShortestWaitTimeMs()
    val failedKey = lastFailedKey
    val raw = lastErrorMsg
    return CommandOutcome.Failure(
        when {
            // Prefer the message carrying the actual wait time, but only when the last error
            // really was a rate limit — otherwise an unrelated failure would be masked by some
            // other key that merely happens to be cooling down.
            waitMs != null && (raw == null || lastErrorWasRateLimit) ->
                context.getString(R.string.toast_key_rate_limited, ((waitMs + 999) / 1000).coerceAtLeast(1))
            // Must precede the generic branch: raw is never null once a request was attempted. A
            // 403 is usually the selected model not being available to the project rather than
            // bad keys, so don't send the user off to check keys that are fine.
            lastErrorWasPermission -> context.getString(R.string.error_no_model_access)
            raw != null -> {
                val mapped = ErrorMessages.map(raw)
                if (mapped == R.string.error_invalid_key && failedKey != null && keyManager.getKeys().size > 1) {
                    context.getString(R.string.error_invalid_key_with_hint, "••••" + failedKey.takeLast(4))
                } else {
                    context.getString(mapped)
                }
            }
            keyManager.getKeys().isEmpty() -> context.getString(R.string.toast_no_keys)
            else -> context.getString(R.string.toast_all_keys_invalid)
        }
    )
}
