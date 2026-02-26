package com.kai.core

import com.kai.models.CodeArtifact
import kotlinx.serialization.Serializable

/**
 * Bir ajan döngüsündeki olası adımlar.
 * Sealed class sayesinde `when` bloğu exhaustive olur —
 * yeni bir adım eklediğinde compiler seni uyarır.
 */
@Serializable
sealed class AgentStep {

    /** LLM düşünüyor, reasoning üretiyor */
    @Serializable
    data class Think(
        val thought: String,
        val confidence: Double = 0.0
    ) : AgentStep()

    /** Bir araç çağrılacak */
    @Serializable
    data class Act(
        val toolName: String,
        val toolInput: Map<String, String>,
        val reasoning: String
    ) : AgentStep()

    /** Araç sonucu geri döndü */
    @Serializable
    data class Observe(
        val toolName: String,
        val result: ToolResult,
        val durationMs: Long
    ) : AgentStep()

    /** Sonuç hazır, döngü bitti */
    @Serializable
    data class Answer(
        val content: String,
        val artifacts: List<CodeArtifact> = emptyList(),
        val metadata: AnswerMetadata? = null
    ) : AgentStep()

    /** Hata oluştu, recovery gerekiyor */
    @Serializable
    data class Error(
        val message: String,
        val recoverable: Boolean,
        val suggestedAction: String? = null
    ) : AgentStep()

    /** Başka bir ajana devret */
    @Serializable
    data class Delegate(
        val targetAgent: AgentRole,
        val context: DelegationContext
    ) : AgentStep()
}

@Serializable
sealed class ToolResult {
    @Serializable
    data class Success(val output: String, val data: String? = null) : ToolResult()

    @Serializable
    data class Failure(val error: String, val retryable: Boolean) : ToolResult()

    val isSuccess: Boolean get() = this is Success
}

@Serializable
data class AnswerMetadata(
    val totalSteps: Int,
    val toolsUsed: List<String>,
    val totalDurationMs: Long,
    val agentsInvolved: List<AgentRole>
)

@Serializable
data class DelegationContext(
    val reason: String,
    val taskDescription: String,
    val parentTrajectory: List<AgentStep> = emptyList(),
    val constraints: List<String> = emptyList()
)
