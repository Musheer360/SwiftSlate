package com.musheer360.swiftslate.model

object ProviderType {
    const val GEMINI = "gemini"
    const val GROQ = "groq"
    const val COMMAND_CODE = "commandcode"
    const val OPENCODE_GO = "opencodego"
    const val DEEPSEEK = "deepseek"
    const val CUSTOM = "custom"

    private val VALID = setOf(GEMINI, GROQ, COMMAND_CODE, OPENCODE_GO, DEEPSEEK, CUSTOM)
    fun sanitize(value: String?): String = if (value in VALID) value!! else GEMINI
}
