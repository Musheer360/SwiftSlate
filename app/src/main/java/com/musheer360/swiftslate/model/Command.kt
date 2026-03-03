package com.musheer360.swiftslate.model

data class Command(
    val trigger: String,
    val prompt: String,
    val isBuiltIn: Boolean = false,
    val isGeneration: Boolean = false,
    val model: String? = null
)
