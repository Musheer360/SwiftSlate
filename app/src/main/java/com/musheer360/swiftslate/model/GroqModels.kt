package com.musheer360.swiftslate.model

/**
 * Single source of truth for the Groq models SwiftSlate offers and how each one
 * is driven with respect to reasoning ("thinking").
 *
 * Each offered model is defined once in [SPECS] together with the reasoning
 * parameters it needs. The dropdown list, the default, validation, and the
 * per-model reasoning params all derive from that single table, so adding or
 * removing a model is a one-line change that forces you to state its reasoning
 * behavior right there.
 *
 * Reasoning support on Groq is per-model and strictly validated by the API:
 * sending an unsupported reasoning parameter (or an unsupported value) returns
 * HTTP 400. There are three categories:
 *
 *  1. Non-reasoning models (e.g. llama-3.1-8b-instant): must NOT receive any
 *     reasoning parameters. They never emit reasoning tokens.
 *  2. GPT-OSS models: reasoning cannot be fully disabled. Lowest is
 *     reasoning_effort="low" ("none" returns 400). include_reasoning=false hides
 *     the reasoning from the response (it is still generated internally, so this
 *     only trims what we receive/parse).
 *  3. Qwen 3.x models: reasoning can be fully turned off with
 *     reasoning_effort="none" (zero reasoning tokens). "low"/"medium"/"high"
 *     return 400 for Qwen.
 */
object GroqModels {

    /** One entry per offered model: its ID, display label, + the reasoning params to send (empty = none). */
    private data class Spec(val id: String, val label: String, val reasoning: Map<String, Any>)

    // Ordered fast/cheap -> higher quality. Curated set (not every model Groq hosts).
    // Only models that reliably TRANSFORM (never answer/respond to) the user's text are
    // kept. llama-3.1-8b-instant and openai/gpt-oss-20b were removed after testing: they
    // consistently fulfilled requests embedded in the text (essays, tips, recipes) instead
    // of transforming them. Retired IDs (llama-3.3-70b-versatile, llama-4-scout) also gone.
    //
    // CONTRACT: the reasoning map is sent verbatim as top-level request params and
    // must be valid for that specific model (see the category notes above) — an
    // invalid key/value returns HTTP 400. The client degrades gracefully (retries
    // once without these params) so a bad entry won't hard-break the app, but new or
    // edited entries should still be verified against the live API before shipping.
    private val SPECS: List<Spec> = listOf(
        // GPT-OSS: cannot disable reasoning; "medium" balances quality and latency (~1.2s).
        Spec("openai/gpt-oss-120b", "GPT-OSS 120B", mapOf("reasoning_effort" to "medium", "include_reasoning" to false)),
        // Qwen 3.x: fully disable reasoning (never "default" — that blows up latency/quota).
        Spec("qwen/qwen3.6-27b", "Qwen 3.6 27B", mapOf("reasoning_effort" to "none"))
    )

    /** Default model = first spec entry, so it can never point outside the catalog. */
    val DEFAULT: String = SPECS.first().id

    /** Model IDs, in display order (used for validation and by the provider config). */
    val ALL: List<String> = SPECS.map { it.id }

    /** (id, label) pairs for the Settings dropdown — shows a friendly name, stores the id. */
    val OPTIONS: List<Pair<String, String>> = SPECS.map { it.id to it.label }

    /** Friendly display label for [model]; falls back to the id if unknown. */
    fun label(model: String): String = SPECS.firstOrNull { it.id == model }?.label ?: model

    /** Coerce a stored/selected model to a currently-supported one. */
    fun sanitize(value: String?): String = if (value in ALL) value!! else DEFAULT

    /**
     * Reasoning parameters to merge into a Groq chat-completions request body for
     * [model]. Returns an empty map for non-reasoning models and unknown models
     * (send nothing, else the API returns HTTP 400).
     */
    fun reasoningParams(model: String): Map<String, Any> =
        SPECS.firstOrNull { it.id == model }?.reasoning ?: emptyMap()
}
