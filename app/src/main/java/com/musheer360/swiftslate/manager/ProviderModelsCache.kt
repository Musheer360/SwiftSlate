package com.musheer360.swiftslate.manager

/**
 * Process-lifetime cache of the model lists fetched from every provider's /models endpoint
 * for the dynamic Settings dropdowns (issue #148).
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

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun get(type: String): Entry? = entries[type]

    fun put(type: String, entry: Entry) {
        entries[type] = entry
    }

    /** Forget a provider's list so the next Settings visit refetches it (e.g. after a key add). */
    fun invalidate(type: String) {
        entries.remove(type)
    }
}
