package com.musheer360.swiftslate.manager

import com.musheer360.swiftslate.model.ProviderType

/**
 * Process-lifetime cache of the model lists fetched from the Gemini and Groq
 * /models endpoints for the dynamic Settings dropdowns (issue #148).
 *
 * Session-only by design — never written to prefs — so a stale provider catalog
 * can never outlive this app process, matching how the Custom provider keeps its
 * fetched list in composition state. [Entry.attempted] records whether a real
 * fetch already ran this session (success or failure), so entering Settings
 * again never auto-refires it; only lack of an API key leaves [attempted] false,
 * letting the automatic fetch run once a key is added.
 */
object ProviderModelsCache {
    data class Entry(val models: List<String>, val attempted: Boolean)

    @Volatile private var gemini: Entry? = null
    @Volatile private var groq: Entry? = null

    fun get(type: String): Entry? = when (type) {
        ProviderType.GEMINI -> gemini
        ProviderType.GROQ -> groq
        else -> null
    }

    fun put(type: String, entry: Entry) {
        when (type) {
            ProviderType.GEMINI -> gemini = entry
            ProviderType.GROQ -> groq = entry
        }
    }
}
