package com.musheer360.swiftslate.service

import android.content.Context
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.api.ApiClientUtils
import java.util.Locale

/**
 * Maps a raw API/network error string to a concise, localized, user-facing message.
 * Extracted from AssistantService so the error-text policy lives in one testable place.
 * Falls back to the raw text when nothing matches.
 */
object ErrorMessages {
    fun map(context: Context, raw: String): String {
        // A blank provider message previously produced an empty toast: extractApiErrorMessage
        // accepts whitespace, and stripHttpPrefix can consume the rest.
        if (raw.isBlank()) return context.getString(R.string.error_bad_request)
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            lower.contains("permission_denied") || lower.contains("permission denied") ->
                context.getString(R.string.error_no_model_access)
            lower.contains("invalid api key") || lower.contains("api key not valid") || lower.contains("api_key_invalid") ||
                lower.contains("invalid_api_key") || lower.contains("incorrect api key") ->
                context.getString(R.string.error_invalid_key)
            lower.contains("rate limit") || lower.contains("resource_exhausted") || lower.contains("quota") ->
                context.getString(R.string.error_rate_limited)
            // Must come AFTER the rate-limit branch is skipped for these: Groq's 413 body reads
            // "Request too large ... on tokens per minute (TPM): Limit 8000, Requested 8192".
            // Reporting that as "rate limited, try again shortly" told the user to wait, which
            // never helps when the text itself exceeds the budget.
            lower.contains("request too large") || lower.contains("tokens per minute") ||
                lower.contains("context_length_exceeded") || lower.contains("too many tokens") ->
                context.getString(R.string.error_input_too_long)
            lower.contains("model not found") || lower.contains("model_not_found") || lower.contains("not found for api version") ||
                lower.contains("does not exist or you do not have access") || lower.contains("decommissioned") ->
                context.getString(R.string.error_model_not_found)
            lower.contains("safety") || lower.contains("content_filter") || lower.contains("content filter") || lower.contains("recitation") ||
                lower.contains("blocked by safety") || lower.contains("finish_reason: safety") ->
                context.getString(R.string.error_safety_blocked)
            lower.contains("empty response") || lower.contains("no content found") || lower.contains("no choices found") || lower.contains("no candidates found") ->
                context.getString(R.string.error_empty_response)
            lower.contains("timeout") || lower.contains("timed out") ->
                context.getString(R.string.error_timeout_connection)
            lower.contains("unable to resolve host") || lower.contains("no address associated") ||
                lower.contains("network is unreachable") || lower.contains("no route to host") ||
                lower.contains("software caused connection abort") || lower.contains("connection reset") ||
                lower.contains("broken pipe") ->
                context.getString(R.string.error_no_internet)
            lower.contains("connection refused") || lower.contains("connect failed") ->
                context.getString(R.string.error_endpoint_unreachable)
            lower.contains("bad request") ->
                context.getString(R.string.error_bad_request)
            // Last resort: the provider's own text. Kept because it is often the only
            // actionable detail (e.g. "model has been decommissioned"), but secrets are
            // stripped first — some OpenAI-compatible endpoints echo the key back
            // ("Incorrect API key provided: sk-ab...XYZ") and this string is shown verbatim.
            else -> ApiClientUtils.redactSecrets(raw)
        }
    }
}