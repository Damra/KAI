package com.kai.core

import kotlinx.serialization.Serializable

@Serializable
enum class AgentRole {
    PLANNER,
    CODE_WRITER,
    REVIEWER,
    FIXER,
    TESTER,
    RESEARCHER;

    val displayName: String
        get() = when (this) {
            PLANNER -> "Planner"
            CODE_WRITER -> "Code Writer"
            REVIEWER -> "Reviewer"
            FIXER -> "Fixer"
            TESTER -> "Tester"
            RESEARCHER -> "Researcher"
        }

    val systemPromptKey: String
        get() = name.lowercase()
}
