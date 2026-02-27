package com.kai.core

import kotlinx.serialization.Serializable

@Serializable
data class AgentTask(
    val description: String,
    val context: String = "",
    val constraints: List<String> = emptyList(),
    val parentTaskId: String? = null
) {
    fun entities(): List<String> {
        // Basit entity extraction: büyük harfle başlayan kelimeleri ve
        // teknik terimleri çıkar
        val words = description.split("\\s+".toRegex())
        return words.filter { word ->
            word.isNotEmpty() && (
                word.first().isUpperCase() ||
                word.contains('.') ||
                TECHNICAL_TERMS.any { term -> word.contains(term, ignoreCase = true) }
            )
        }.distinct()
    }

    companion object {
        private val TECHNICAL_TERMS = listOf(
            "kotlin", "ktor", "coroutine", "flow", "channel",
            "sealed", "data class", "suspend", "gradle",
            "rest", "websocket", "api", "json", "database",
            "postgresql", "neo4j", "docker", "test", "junit"
        )

        fun fromDelegation(delegation: DelegationContext): AgentTask {
            return AgentTask(
                description = delegation.taskDescription,
                context = delegation.reason,
                constraints = delegation.constraints
            )
        }
    }
}
