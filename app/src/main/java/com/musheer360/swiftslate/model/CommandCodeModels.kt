package com.musheer360.swiftslate.model

/**
 * Command Code Provider API models
 * (https://api.commandcode.ai/provider/v1, OpenAI-compatible).
 *
 * The live catalog is fetched from the endpoint's own /models listing (see
 * [com.musheer360.swiftslate.manager.ProviderModelsCache]), so this table only
 * supplies the first-run default and the retirement migration. Any id the API
 * accepts passes through untouched, exactly like [GroqModels].
 *
 * No reasoning params are sent: unlike Groq, the models list exposes a
 * per-model supported-endpoints field but nothing about legal reasoning
 * values, and an unsupported param fails the whole request. [ApiClientUtils.stripReasoningBlock]
 * still removes any inline chain of thought for reasoning models that emit it.
 */
object CommandCodeModels {

    /** Fast, cheap, chat-completions-compatible default (the model the API docs demo). */
    val DEFAULT: String = "deepseek/deepseek-v4-flash"

    /** Model ids, in display order (used for validation and by the provider config). */
    val ALL: List<String> = listOf(DEFAULT)

    /**
     * Ids the Provider API no longer serves; migrate to [DEFAULT] rather than
     * send a request that can only fail.
     */
    private val RETIRED_IDS: Set<String> = emptySet()

    /** Normalize a stored/selected model value; unknown ids pass through trimmed. */
    fun sanitize(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed in RETIRED_IDS) DEFAULT else trimmed
    }
}
