package com.kai.models

import kotlinx.serialization.Serializable

@Serializable
data class CodeArtifact(
    val filename: String,
    val language: String,
    val content: String,
    val version: Int = 1
)
