package com.musheer360.swiftslate.model

/**
 * DeepSeek models (https://api.deepseek.com, OpenAI-compatible).
 *
 * DeepSeek publishes two live ids: `deepseek-flash` (default, currently served by
 * V4.1-Flash-1017) and `deepseek-v4-pro`. The legacy `deepseek-v4-flash` /
 * `deepseek-v4-flash-vision-exp` names are still accepted and billed as Flash, so they
 * are not retired. The Settings dropdown lists whatever `/models` returns; this table
 * only supplies the first-run default.
 *
 * No reasoning params are sent: thinking is opt-in on DeepSeek (`thinking: {type:
 * "enabled"}`), so leaving it out keeps text transformations fast. If a model returns
 * reasoning inline, [com.musheer360.swiftslate.api.ApiClientUtils.stripReasoningBlock]
 * removes it before it reaches the user.
 */
object DeepSeekModels {

    val DEFAULT: String = "deepseek-flash"

    /** Model ids, in display order (used for validation and by the provider config). */
    val ALL: List<String> = listOf(DEFAULT, "deepseek-v4-pro")

    /** Ids DeepSeek has retired outright; none currently. */
    private val RETIRED_IDS: Set<String> = emptySet()

    /** Normalize a stored/selected model value; unknown ids pass through trimmed. */
    fun sanitize(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed in RETIRED_IDS) DEFAULT else trimmed
    }
}
