package com.musheer360.swiftslate.service

import android.content.Context
import com.musheer360.swiftslate.R
import java.util.Locale

/**
 * Maps a raw API/network error string to a concise, localized, user-facing message.
 * Extracted from AssistantService so the error-text policy lives in one testable place.
 * Falls back to the raw text when nothing matches.
 */
object ErrorMessages {
    fun map(context: Context, raw: String): String {
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            lower.contains("permission_denied") || lower.contains("permission denied") ->
                context.getString(R.string.error_no_model_access)
            lower.contains("invalid api key") || lower.contains("api key not valid") || lower.contains("api_key_invalid") ->
                context.getString(R.string.error_invalid_key)
            lower.contains("rate limit") || lower.contains("resource_exhausted") || lower.contains("quota") ->
                context.getString(R.string.error_rate_limited)
            lower.contains("model not found") || lower.contains("model_not_found") || lower.contains("not found for api version") ->
                context.getString(R.string.error_model_not_found)
            lower.contains("safety") || lower.contains("content_filter") || lower.contains("content filter") || lower.contains("recitation") ||
                lower.contains("blocked by safety") || lower.contains("finish_reason: safety") ||
                lower.contains("failed_generation") ->
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
            else -> raw
        }
    }
}
