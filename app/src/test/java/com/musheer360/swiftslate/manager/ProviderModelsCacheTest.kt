package com.musheer360.swiftslate.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM: [ProviderModelsCache] is a process-lifetime map with no Android dependencies. */
class ProviderModelsCacheTest {

    @Test
    fun putGet_roundTripsPerProvider() {
        ProviderModelsCache.put("deepseek", ProviderModelsCache.Entry(listOf("deepseek-flash"), attempted = true))
        ProviderModelsCache.put("commandcode", ProviderModelsCache.Entry(listOf("gpt-6-sol"), attempted = false))

        assertEquals(listOf("deepseek-flash"), ProviderModelsCache.get("deepseek")?.models)
        assertEquals(true, ProviderModelsCache.get("deepseek")?.attempted)
        assertEquals(listOf("gpt-6-sol"), ProviderModelsCache.get("commandcode")?.models)
        assertEquals(false, ProviderModelsCache.get("commandcode")?.attempted)
        assertNull(ProviderModelsCache.get("openai"))

        ProviderModelsCache.invalidate("deepseek")
        ProviderModelsCache.invalidate("commandcode")
    }

    @Test
    fun invalidate_dropsOnlyThatProvider() {
        ProviderModelsCache.put("groq", ProviderModelsCache.Entry(listOf("qwen/qwen3.6-27b"), attempted = true))
        ProviderModelsCache.put("gemini", ProviderModelsCache.Entry(listOf("gemini-3.6-flash"), attempted = true))

        ProviderModelsCache.invalidate("groq")

        assertNull(ProviderModelsCache.get("groq"))
        assertEquals(listOf("gemini-3.6-flash"), ProviderModelsCache.get("gemini")?.models)

        ProviderModelsCache.invalidate("gemini")
    }
}
