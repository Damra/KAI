package com.kai.models

import com.kai.core.AgentRole
import com.kai.core.AnswerMetadata
import kotlinx.serialization.Serializable

@Serializable
data class AgentResponse(
    val answer: String,
    val artifacts: List<CodeArtifact> = emptyList(),
    val planSteps: List<PlanStepResult> = emptyList(),
    val metadata: AnswerMetadata? = null
)

@Serializable
data class PlanStepResult(
    val stepId: String,
    val agent: AgentRole,
    val description: String,
    val status: StepStatus,
    val output: String? = null
)

@Serializable
enum class StepStatus {
    PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
}
