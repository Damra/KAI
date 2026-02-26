package com.kai.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentRequest(
    val message: String,
    val sessionId: String? = null,
    val constraints: List<String> = emptyList(),
    val streaming: Boolean = false
)
