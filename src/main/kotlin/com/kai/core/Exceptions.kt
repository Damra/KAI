package com.kai.core

open class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class DelegationException(val delegation: AgentStep.Delegate) : RuntimeException(
    "Agent delegating to ${delegation.targetAgent}: ${delegation.context.reason}"
)

class ToolExecutionException(
    val toolName: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException("Tool '$toolName' failed: $message", cause)

class MaxIterationsExceededException(
    val iterations: Int
) : AgentException("Max iterations ($iterations) exceeded")

class VerificationFailedException(
    val score: Double,
    val issues: List<String>
) : AgentException("Verification failed (score: $score): ${issues.joinToString(", ")}")
