package com.musheer360.swiftslate.model

object ProviderType {
    const val GEMINI = "gemini"
    const val GROQ = "groq"
    const val CUSTOM = "custom"

    private val VALID = setOf(GEMINI, GROQ, CUSTOM)
    fun sanitize(value: String?): String = if (value in VALID) value!! else GEMINI
}

// CI verification: confirms the preview APK job produces a side-by-side installable
// artifact and that pull requests never reach the release path. Branch is throwaway.
