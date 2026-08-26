package com.musheer360.swiftslate.api

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs under Robolectric because [GeminiClient.parseModelsJson] uses org.json,
 * which is an unimplemented stub in the plain JVM unit-test classpath.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiModelsListParserTest {

    @Test
    fun keeps_generateContent_models_and_strips_models_prefix() {
        val json = """
            {"models":[
              {"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
              {"name":"models/imagen-4","supportedGenerationMethods":["predict"]}
            ]}
        """.trimIndent()
        assertEquals(listOf("gemini-3.6-flash"), GeminiClient.parseModelsJson(json))
    }

    @Test
    fun honors_supportedActions_field_variant() {
        val json = """
            {"models":[
              {"name":"models/gemini-next","supportedActions":["generateContent"]},
              {"name":"models/aqa-model","supportedActions":["questionAnswering"]}
            ]}
        """.trimIndent()
        assertEquals(listOf("gemini-next"), GeminiClient.parseModelsJson(json))
    }

    @Test
    fun fails_open_when_neither_capability_array_present() {
        val json = """{"models":[{"name":"models/mystery-model"},{"name":"models/gemini-x"}]}"""
        assertEquals(listOf("mystery-model", "gemini-x"), GeminiClient.parseModelsJson(json))
    }

    @Test
    fun dedupes_in_first_seen_order_and_skips_blank_names() {
        val json = """
            {"models":[
              {"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},
              {"name":"","supportedGenerationMethods":["generateContent"]},
              {"name":"models/nested/models/gemini-b","supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()
        // Only ONE leading "models/" is stripped — real ids never nest the prefix,
        // and repeated stripping could corrupt an id that legitimately contains it.
        assertEquals(listOf("gemini-a", "nested/models/gemini-b"), GeminiClient.parseModelsJson(json))
    }

    @Test
    fun non_json_or_empty_bodies_yield_empty_list() {
        assertEquals(emptyList<String>(), GeminiClient.parseModelsJson(""))
        assertEquals(emptyList<String>(), GeminiClient.parseModelsJson("   "))
        assertEquals(emptyList<String>(), GeminiClient.parseModelsJson("<html>error</html>"))
        assertEquals(emptyList<String>(), GeminiClient.parseModelsJson("""{"models":[]}"""))
        assertEquals(emptyList<String>(), GeminiClient.parseModelsJson("""{"other":1}"""))
    }
}
