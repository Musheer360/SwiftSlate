package com.musheer360.swiftslate.model

/**
 * OpenCode Go models (https://opencode.ai/inference/go/openai/v1, OpenAI-compatible).
 *
 * OpenCode Go is OpenCode's low-cost subscription plan for popular open coding
 * models. The live catalog is fetched from the endpoint's own /models listing
 * (see [com.musheer360.swiftslate.manager.ProviderModelsCache]); this table only
 * supplies the first-run default and the retirement migration, so any id the API
 * accepts passes through untouched, exactly like [GroqModels].
 *
 * No reasoning params are sent: the legal values are per-model and absent from
 * the models list, and an unsupported param fails the whole request.
 */
object OpenCodeGoModels {

    /** Fast GLM flash lane — the cheap/fast tier this app's latency budget needs. */
    val DEFAULT: String = "glm-5.3-flash"

    /** Model ids, in display order (used for validation and by the provider config). */
    val ALL: List<String> = listOf(DEFAULT)

    /** Ids the Go plan has retired; migrate to [DEFAULT] instead of failing forever. */
    private val RETIRED_IDS: Set<String> = emptySet()

    /** Normalize a stored/selected model value; unknown ids pass through trimmed. */
    fun sanitize(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed in RETIRED_IDS) DEFAULT else trimmed
    }
}
