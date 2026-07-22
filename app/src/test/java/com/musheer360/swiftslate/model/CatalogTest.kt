package com.musheer360.swiftslate.model

import org.junit.Assert.*
import org.junit.Test

/** Pure unit tests for the Groq/Gemini model catalogs. No Android deps. */
class CatalogTest {

    // ---------- GroqModels ----------

    @Test
    fun groq_all_nonEmpty_and_default_in_catalog() {
        assertTrue(GroqModels.ALL.isNotEmpty())
        assertTrue("DEFAULT must be a real catalog entry", GroqModels.DEFAULT in GroqModels.ALL)
    }

    @Test
    fun groq_sanitize_coerces_unknown_and_null_to_default() {
        assertEquals(GroqModels.DEFAULT, GroqModels.sanitize(null))
        assertEquals(GroqModels.DEFAULT, GroqModels.sanitize(""))
        assertEquals(GroqModels.DEFAULT, GroqModels.sanitize("llama-3.3-70b-versatile")) // retired
        assertEquals(GroqModels.DEFAULT, GroqModels.sanitize("meta-llama/llama-4-scout-17b-16e-instruct")) // removed
    }

    @Test
    fun groq_sanitize_keeps_valid_models() {
        for (id in GroqModels.ALL) assertEquals(id, GroqModels.sanitize(id))
    }

    @Test
    fun groq_reasoningParams_per_family() {
        assertEquals(
            mapOf("reasoning_effort" to "low", "include_reasoning" to false),
            GroqModels.reasoningParams("openai/gpt-oss-120b")
        )
        assertEquals(mapOf("reasoning_effort" to "none"), GroqModels.reasoningParams("qwen/qwen3.6-27b"))
        // Unknown / removed models must send nothing (else the API 400s).
        assertEquals(emptyMap<String, Any>(), GroqModels.reasoningParams("openai/gpt-oss-20b"))
        assertEquals(emptyMap<String, Any>(), GroqModels.reasoningParams("llama-3.1-8b-instant"))
        assertEquals(emptyMap<String, Any>(), GroqModels.reasoningParams("something-else"))
    }

    @Test
    fun groq_labels_present_and_fallback() {
        for (id in GroqModels.ALL) assertTrue(GroqModels.label(id).isNotBlank())
        assertEquals("unknown-model", GroqModels.label("unknown-model"))
    }

    @Test
    fun groq_options_match_all_ids_in_order() {
        assertEquals(GroqModels.ALL, GroqModels.OPTIONS.map { it.first })
        assertTrue(GroqModels.OPTIONS.all { it.second.isNotBlank() })
    }

    // ---------- GeminiModels ----------

    @Test
    fun gemini_all_nonEmpty_and_default_in_catalog() {
        assertTrue(GeminiModels.ALL.isNotEmpty())
        assertTrue(GeminiModels.DEFAULT in GeminiModels.ALL)
    }

    @Test
    fun gemini_sanitize_coerces_unknown_to_default() {
        assertEquals(GeminiModels.DEFAULT, GeminiModels.sanitize(null))
        assertEquals(GeminiModels.DEFAULT, GeminiModels.sanitize("gemini-2.5-flash-lite")) // retired (issue #113)
        for (id in GeminiModels.ALL) assertEquals(id, GeminiModels.sanitize(id))
    }

    @Test
    fun gemini_thinkingLevel_minimal_for_catalog_null_for_unknown() {
        for (id in GeminiModels.ALL) assertEquals("minimal", GeminiModels.thinkingLevel(id))
        assertNull(GeminiModels.thinkingLevel("gemini-9-ultra"))
    }

    @Test
    fun gemini_options_match_all_ids() {
        assertEquals(GeminiModels.ALL, GeminiModels.OPTIONS.map { it.first })
        assertTrue(GeminiModels.OPTIONS.all { it.second.isNotBlank() })
    }
}
