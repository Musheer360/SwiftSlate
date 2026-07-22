package com.musheer360.swiftslate.provider

import com.musheer360.swiftslate.model.GeminiModels
import com.musheer360.swiftslate.model.GroqModels
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.model.ProviderType
import org.junit.Assert.*
import org.junit.Test

/** Pure unit tests for provider routing/config. No Android deps. */
class ProviderConfigTest {

    @Test
    fun forType_routes_each_provider() {
        assertSame(GeminiConfig, Providers.forType(ProviderType.GEMINI))
        assertSame(GroqConfig, Providers.forType(ProviderType.GROQ))
        assertSame(CustomConfig, Providers.forType(ProviderType.CUSTOM))
    }

    @Test
    fun forType_defaults_to_gemini_for_null_or_unknown() {
        assertSame(GeminiConfig, Providers.forType(null))
        assertSame(GeminiConfig, Providers.forType("nonsense"))
    }

    @Test
    fun transports_are_correct() {
        assertEquals(Transport.GEMINI_NATIVE, GeminiConfig.transport)
        assertEquals(Transport.OPENAI_COMPAT, GroqConfig.transport)
        assertEquals(Transport.OPENAI_COMPAT, CustomConfig.transport)
    }

    @Test
    fun model_pref_keys_and_defaults() {
        assertEquals(PrefKeys.GEMINI_MODEL, GeminiConfig.modelPrefKey)
        assertEquals(PrefKeys.GROQ_MODEL, GroqConfig.modelPrefKey)
        assertEquals(PrefKeys.CUSTOM_MODEL, CustomConfig.modelPrefKey)
        assertEquals(GeminiModels.DEFAULT, GeminiConfig.defaultModel)
        assertEquals(GroqModels.DEFAULT, GroqConfig.defaultModel)
        assertEquals("", CustomConfig.defaultModel)
    }

    @Test
    fun endpoint_resolution() {
        assertEquals(GroqConfig.ENDPOINT, GroqConfig.resolveEndpoint("ignored"))
        assertEquals("", GeminiConfig.resolveEndpoint("ignored"))
        assertEquals("https://my.endpoint/v1", CustomConfig.resolveEndpoint("https://my.endpoint/v1"))
    }

    @Test
    fun jsonObjectMode_only_groq_and_only_when_enabled() {
        assertTrue(GroqConfig.useJsonObjectMode(true))
        assertFalse(GroqConfig.useJsonObjectMode(false))
        assertFalse(GeminiConfig.useJsonObjectMode(true))
        assertFalse(CustomConfig.useJsonObjectMode(true))
    }

    @Test
    fun isConfigured_only_custom_requires_both() {
        assertTrue(GeminiConfig.isConfigured("", ""))
        assertTrue(GroqConfig.isConfigured("m", ""))
        assertTrue(CustomConfig.isConfigured("m", "https://x"))
        assertFalse(CustomConfig.isConfigured("", "https://x"))
        assertFalse(CustomConfig.isConfigured("m", ""))
        assertFalse(CustomConfig.isConfigured("m", "   "))
    }

    @Test
    fun custom_model_is_trimmed_and_null_safe() {
        assertEquals("gpt-4o", CustomConfig.sanitizeModel("  gpt-4o  "))
        assertEquals("", CustomConfig.sanitizeModel(null))
    }

    @Test
    fun gemini_config_coerces_model_and_exposes_thinking_level() {
        assertEquals(GeminiModels.DEFAULT, GeminiConfig.sanitizeModel("gemini-2.5-flash-lite"))
        assertEquals("minimal", GeminiConfig.thinkingLevel(GeminiModels.DEFAULT))
    }

    @Test
    fun groq_config_delegates_reasoning_params() {
        assertEquals(
            mapOf("reasoning_effort" to "low", "include_reasoning" to false),
            GroqConfig.reasoningParams("openai/gpt-oss-120b")
        )
        assertTrue(GroqConfig.reasoningParams("llama-3.1-8b-instant").isEmpty())
        // Non-Gemini providers expose no thinking level.
        assertNull(GroqConfig.thinkingLevel("openai/gpt-oss-120b"))
        assertNull(CustomConfig.thinkingLevel("anything"))
        // Non-Groq providers add no reasoning params.
        assertTrue(GeminiConfig.reasoningParams("x").isEmpty())
        assertTrue(CustomConfig.reasoningParams("x").isEmpty())
    }
}
