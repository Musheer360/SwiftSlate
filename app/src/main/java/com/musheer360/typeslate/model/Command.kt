package com.musheer360.typeslate.model

data class Command(
    val trigger: String,
    val prompt: String,
    val isBuiltIn: Boolean = false
)
