package com.musheer360.swiftslate.service

import androidx.annotation.StringRes
import com.musheer360.swiftslate.R
import java.util.Locale

/**
 * Maps a raw API/network error string to a localized string resource ID.
 * Pure Kotlin function with zero Android Context dependency for pure JVM unit testing.
 */
object ErrorMessages {
    @StringRes
    fun map(raw: String): Int {
        if (raw.isBlank()) return R.string.error_bad_request
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            lower.contains("permission_denied") || lower.contains("permission denied") ->
                R.string.error_no_model_access
            lower.contains("invalid api key") || lower.contains("api key not valid") || lower.contains("api_key_invalid") ||
                lower.contains("invalid_api_key") || lower.contains("incorrect api key") ->
                R.string.error_invalid_key
            lower.contains("rate limit") || lower.contains("resource_exhausted") || lower.contains("quota") ->
                R.string.error_rate_limited
            // Must come AFTER the rate-limit branch is skipped for these: Groq's 413 body reads
            // "Request too large ... on tokens per minute (TPM): Limit 8000, Requested 8192".
            lower.contains("request too large") || lower.contains("tokens per minute") ||
                lower.contains("context_length_exceeded") || lower.contains("too many tokens") ->
                R.string.error_input_too_long
            lower.contains("model not found") || lower.contains("model_not_found") || lower.contains("not found for api version") ||
                lower.contains("does not exist or you do not have access") || lower.contains("decommissioned") ->
                R.string.error_model_not_found
            lower.contains("safety") || lower.contains("content_filter") || lower.contains("content filter") || lower.contains("recitation") ||
                lower.contains("blocked by safety") || lower.contains("finish_reason: safety") ||
                lower.contains("json_validate_failed") || lower.contains("failed to validate json") ||
                lower.contains("response_format") || lower.contains("failed to generate json") || lower.contains("failed_generation") ->
                R.string.error_safety_blocked
            lower.contains("empty response") || lower.contains("no content found") || lower.contains("no choices found") || lower.contains("no candidates found") ->
                R.string.error_empty_response
            lower.contains("timeout") || lower.contains("timed out") ->
                R.string.error_timeout_connection
            lower.contains("unable to resolve host") || lower.contains("no address associated") ||
                lower.contains("network is unreachable") || lower.contains("no route to host") ||
                lower.contains("software caused connection abort") || lower.contains("connection reset") ||
                lower.contains("broken pipe") ->
                R.string.error_no_internet
            lower.contains("connection refused") || lower.contains("connect failed") ||
                lower.contains("http_5") || lower.contains("server error") || lower.contains("unexpected error (http 5") ->
                R.string.error_endpoint_unreachable
            lower.contains("bad request") || lower.contains("http_400") || lower.contains("http_422") ->
                R.string.error_bad_request
            // Safe user-facing fallback (never leak raw JSON API bodies or internal strings)
            else -> R.string.error_bad_request
        }
    }
}