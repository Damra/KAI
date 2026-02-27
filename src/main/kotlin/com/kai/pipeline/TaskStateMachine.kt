package com.kai.pipeline

import org.slf4j.LoggerFactory

/**
 * Task state machine — validates transitions between TaskStatus values.
 *
 * Valid transitions:
 * CREATED -> PLANNED
 * PLANNED -> READY (when all dependencies are DEPLOYED or task has no deps)
 * READY -> IN_PROGRESS
 * IN_PROGRESS -> PR_OPENED
 * PR_OPENED -> REVIEWING
 * REVIEWING -> APPROVED | CHANGES_REQUESTED
 * CHANGES_REQUESTED -> IN_PROGRESS
 * APPROVED -> MERGED
 * MERGED -> TESTING
 * TESTING -> TEST_PASSED | TEST_FAILED
 * TEST_PASSED -> DEPLOYED
 * TEST_FAILED -> BUG_CREATED
 * BUG_CREATED -> CREATED (new sub-task cycle)
 */
object TaskStateMachine {
    private val logger = LoggerFactory.getLogger(TaskStateMachine::class.java)

    private val validTransitions: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.CREATED to setOf(TaskStatus.PLANNED),
        TaskStatus.PLANNED to setOf(TaskStatus.READY),
        TaskStatus.READY to setOf(TaskStatus.IN_PROGRESS),
        TaskStatus.IN_PROGRESS to setOf(TaskStatus.PR_OPENED),
        TaskStatus.PR_OPENED to setOf(TaskStatus.REVIEWING),
        TaskStatus.REVIEWING to setOf(TaskStatus.APPROVED, TaskStatus.CHANGES_REQUESTED),
        TaskStatus.CHANGES_REQUESTED to setOf(TaskStatus.IN_PROGRESS),
        TaskStatus.APPROVED to setOf(TaskStatus.MERGED),
        TaskStatus.MERGED to setOf(TaskStatus.TESTING),
        TaskStatus.TESTING to setOf(TaskStatus.TEST_PASSED, TaskStatus.TEST_FAILED),
        TaskStatus.TEST_PASSED to setOf(TaskStatus.DEPLOYED),
        TaskStatus.TEST_FAILED to setOf(TaskStatus.BUG_CREATED),
        TaskStatus.BUG_CREATED to setOf(TaskStatus.CREATED)
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean {
        return validTransitions[from]?.contains(to) == true
    }

    fun validateTransition(from: TaskStatus, to: TaskStatus): TransitionValidation {
        if (canTransition(from, to)) {
            return TransitionValidation(valid = true)
        }
        val allowed = validTransitions[from] ?: emptySet()
        val message = "Invalid transition: $from -> $to. Allowed from $from: ${allowed.joinToString()}"
        logger.warn(message)
        return TransitionValidation(valid = false, errorMessage = message)
    }

    fun getAllowedTransitions(from: TaskStatus): Set<TaskStatus> {
        return validTransitions[from] ?: emptySet()
    }

    data class TransitionValidation(
        val valid: Boolean,
        val errorMessage: String = ""
    )
}
