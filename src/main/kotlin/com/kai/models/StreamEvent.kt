package com.kai.models

import com.kai.core.AnswerMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class StreamEvent {
    @Serializable
    @SerialName("thinking")
    data class Thinking(val thought: String) : StreamEvent()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(val tool: String, val input: String) : StreamEvent()

    @Serializable
    @SerialName("tool_result")
    data class ToolResultEvent(val tool: String, val output: String, val success: Boolean) : StreamEvent()

    @Serializable
    @SerialName("code_generated")
    data class CodeGenerated(val artifact: CodeArtifact) : StreamEvent()

    @Serializable
    @SerialName("plan_update")
    data class PlanUpdate(val stepId: String, val status: StepStatus, val description: String) : StreamEvent()

    @Serializable
    @SerialName("delegation")
    data class Delegation(val from: String, val to: String, val reason: String) : StreamEvent()

    @Serializable
    @SerialName("done")
    data class Done(val answer: String, val metadata: AnswerMetadata?) : StreamEvent()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val recoverable: Boolean) : StreamEvent()

    @Serializable
    @SerialName("pipeline_update")
    data class PipelineUpdate(val taskId: String, val status: String, val message: String) : StreamEvent()

    @Serializable
    @SerialName("task_created")
    data class TaskCreated(val taskId: String, val title: String, val category: String) : StreamEvent()
}
