package com.musheer360.swiftslate.model

/**
 * Catalog of the Gemini models SwiftSlate offers and how each is tuned. Mirrors
 * [GroqModels]: each model is defined once in [SPECS] together with its thinking
 * level, and the dropdown list, default, validation, and per-model thinking
 * level all derive from that single table.
 *
 * Thinking control on Gemini 3.x is via generationConfig.thinkingConfig.thinkingLevel
 * (a string enum), kept per model here (spec-driven) rather than hardcoded in the
 * client — exactly like Groq's per-model reasoning params:
 *  - "minimal" keeps latency low for short, inline text transformations. Without
 *    it, gemini-3.6-flash defaults to "medium" thinking (~4-5s per request).
 */
object GeminiModels {

    /** One entry per offered model: its id, display label + the thinking level to request. */
    private data class Spec(val id: String, val label: String, val thinkingLevel: String)

    // Curated set, ordered cost-efficient -> higher quality.
    //
    // CONTRACT: thinkingLevel is sent verbatim as generationConfig.thinkingConfig and
    // must be a valid enum value for that model — an invalid value returns HTTP 400.
    // The client degrades gracefully (retries once without it) so a bad entry won't
    // hard-break the app, but new/edited entries should still be verified against the
    // live API before shipping.
    private val SPECS: List<Spec> = listOf(
        Spec("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "minimal"), // fastest/cheapest GA flash-lite
        Spec("gemini-3.6-flash", "Gemini 3.6 Flash", "minimal")            // higher quality; minimal thinking to stay fast
    )

    /** Default model = first spec entry, so it can never point outside the catalog. */
    val DEFAULT: String = SPECS.first().id

    /** Model IDs, in display order (used for validation and by the provider config). */
    val ALL: List<String> = SPECS.map { it.id }

    /** (id, label) pairs for the Settings dropdown — shows a friendly name, stores the id. */
    val OPTIONS: List<Pair<String, String>> = SPECS.map { it.id to it.label }

    /** Friendly display label for [model]; falls back to the id if unknown. */
    fun label(model: String): String = SPECS.firstOrNull { it.id == model }?.label ?: model

    /**
     * Coerce a stored/selected model to a currently-supported one. This migrates
     * users off retired ids (e.g. gemini-2.5-flash-lite, which is no longer
     * available to new users) to [DEFAULT].
     */
    fun sanitize(value: String?): String = if (value in ALL) value!! else DEFAULT

    /**
     * Thinking level to request for [model] via generationConfig.thinkingConfig,
     * or null if the model isn't in the catalog (then send no thinkingConfig).
     */
    fun thinkingLevel(model: String): String? = SPECS.firstOrNull { it.id == model }?.thinkingLevel
}
