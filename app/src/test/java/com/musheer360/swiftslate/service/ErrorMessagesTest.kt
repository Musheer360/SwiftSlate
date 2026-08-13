package com.musheer360.swiftslate.service

import com.musheer360.swiftslate.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ErrorMessages] is the only thing standing between a raw provider error body and the user,
 * and it is pure Kotlin, so it is cheap to pin down. Ordering between the branches is the part
 * that actually breaks: several patterns match the same message.
 */
class ErrorMessagesTest {

    @Test
    fun blankAndUnrecognized_fallBackToBadRequest() {
        assertEquals(R.string.error_bad_request, ErrorMessages.map(""))
        assertEquals(R.string.error_bad_request, ErrorMessages.map("   "))
        assertEquals(R.string.error_bad_request, ErrorMessages.map("something nobody has seen before"))
    }

    @Test
    fun invalidKeyVariants() {
        assertEquals(R.string.error_invalid_key, ErrorMessages.map("API key not valid. Please pass a valid API key."))
        assertEquals(R.string.error_invalid_key, ErrorMessages.map("API_KEY_INVALID"))
        assertEquals(R.string.error_invalid_key, ErrorMessages.map("Incorrect API key provided"))
        assertEquals(R.string.error_invalid_key, ErrorMessages.map("invalid_api_key"))
    }

    @Test
    fun providerSignInRequiredVariants() {
        // Composed by OpenAICompatibleClient when a signin_url accompanies the 401.
        assertEquals(R.string.error_provider_auth_required, ErrorMessages.map("signin_required: unauthorized"))
        assertEquals(R.string.error_provider_auth_required, ErrorMessages.map("you are not currently signed in"))
        assertEquals(R.string.error_provider_auth_required, ErrorMessages.map("unauthorized [signin_url: https://ollama.com/signin/x]"))
    }

    /**
     * Neutrality guard: a bare "unauthorized" from a non-Ollama provider must NOT be
     * re-labelled as a server sign-in problem — it is usually a key/permission error
     * and must keep falling through to the generic message.
     */
    @Test
    fun bareUnauthorized_doesNotMapToSignInMessage() {
        assertEquals(R.string.error_bad_request, ErrorMessages.map("Unauthorized"))
        assertEquals(R.string.error_bad_request, ErrorMessages.map("401 Unauthorized"))
    }

    @Test
    fun permissionDeniedBeatsInvalidKey() {
        // A 403 is usually the model not being available to the project, not a bad key.
        assertEquals(R.string.error_no_model_access, ErrorMessages.map("PERMISSION_DENIED: invalid api key"))
    }

    @Test
    fun rateLimitVariants() {
        assertEquals(R.string.error_rate_limited, ErrorMessages.map("Rate limit exceeded, retry after 30s"))
        assertEquals(R.string.error_rate_limited, ErrorMessages.map("RESOURCE_EXHAUSTED"))
        assertEquals(R.string.error_rate_limited, ErrorMessages.map("Quota exceeded for this project"))
    }

    /** Groq's 413 body says "Request too large ... on tokens per minute (TPM)". */
    @Test
    fun requestTooLarge_isReportedAsInputTooLong_notRateLimited() {
        assertEquals(
            R.string.error_input_too_long,
            ErrorMessages.map("Request too large for model on tokens per minute (TPM): Limit 8000, Requested 8192")
        )
        assertEquals(R.string.error_input_too_long, ErrorMessages.map("context_length_exceeded"))
    }

    @Test
    fun modelNotFoundVariants() {
        assertEquals(R.string.error_model_not_found, ErrorMessages.map("model_not_found"))
        assertEquals(
            R.string.error_model_not_found,
            ErrorMessages.map("The model `x` does not exist or you do not have access to it.")
        )
        assertEquals(R.string.error_model_not_found, ErrorMessages.map("This model has been decommissioned"))
    }

    @Test
    fun safetyVariants() {
        assertEquals(R.string.error_safety_blocked, ErrorMessages.map("Response blocked by safety filters (SAFETY)"))
        assertEquals(R.string.error_safety_blocked, ErrorMessages.map("content_filter"))
        assertEquals(R.string.error_safety_blocked, ErrorMessages.map("RECITATION"))
    }

    /**
     * Deliberate: a JSON-mode rejection means the model broke out of the requested JSON envelope
     * to refuse in prose, so the provider rejected the response as invalid JSON. It is a refusal
     * signal, not a formatting bug, and must stay mapped to the safety message. Pinned here
     * because "route this to a formatting error instead" looks like an obvious cleanup and is wrong.
     */
    @Test
    fun jsonModeFailures_areReportedAsSafetyRefusals() {
        assertEquals(R.string.error_safety_blocked, ErrorMessages.map("json_validate_failed"))
        assertEquals(R.string.error_safety_blocked, ErrorMessages.map("Failed to generate JSON. Please adjust your prompt."))
        assertEquals(R.string.error_safety_blocked, ErrorMessages.map("failed_generation"))
        assertEquals(R.string.error_safety_blocked, ErrorMessages.map("HTTP_400: Bad request [json_validate_failed]"))
    }

    @Test
    fun emptyResponseVariants() {
        assertEquals(R.string.error_empty_response, ErrorMessages.map("Model returned empty response"))
        assertEquals(R.string.error_empty_response, ErrorMessages.map("No candidates found in response"))
        assertEquals(R.string.error_empty_response, ErrorMessages.map("No choices found in response"))
    }

    @Test
    fun networkVariants() {
        assertEquals(R.string.error_timeout_connection, ErrorMessages.map("Read timed out"))
        assertEquals(R.string.error_no_internet, ErrorMessages.map("Unable to resolve host \"api.groq.com\""))
        assertEquals(R.string.error_no_internet, ErrorMessages.map("Connection reset by peer"))
        assertEquals(R.string.error_endpoint_unreachable, ErrorMessages.map("Connection refused"))
        assertEquals(R.string.error_endpoint_unreachable, ErrorMessages.map("HTTP_503: Service Unavailable"))
    }

    @Test
    fun badRequestVariants() {
        assertEquals(R.string.error_bad_request, ErrorMessages.map("HTTP_400: Bad request"))
        assertEquals(R.string.error_bad_request, ErrorMessages.map("HTTP_422: Unprocessable"))
    }

    /** Nothing may return a message that is not a real resource id. */
    @Test
    fun everyMappingResolvesToANonZeroResourceId() {
        val samples = listOf(
            "", "permission_denied", "invalid api key", "rate limit", "request too large",
            "model not found", "safety", "empty response", "timeout", "connection refused",
            "unable to resolve host", "bad request", "totally unknown", "signin_required: unauthorized",
            "you are not currently signed in", "Unauthorized"
        )
        for (s in samples) {
            assert(ErrorMessages.map(s) != 0) { "map(\"$s\") returned 0" }
        }
    }
}
