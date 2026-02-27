package com.kai.pipeline

import kotlinx.serialization.Serializable
import java.time.Instant

// ── Enums ──

enum class TaskStatus {
    CREATED, PLANNED, READY, IN_PROGRESS, PR_OPENED,
    REVIEWING, CHANGES_REQUESTED, APPROVED, MERGED,
    TESTING, TEST_PASSED, TEST_FAILED, BUG_CREATED, DEPLOYED
}

@Serializable
enum class TaskCategory { DESIGN, BACKEND, FRONTEND, DEVOPS, TESTING, DOCUMENTATION }

@Serializable
enum class TaskPriority { CRITICAL, HIGH, MEDIUM, LOW }

enum class ProjectStatus { ACTIVE, PAUSED, COMPLETED, ARCHIVED }

// ── Data Classes ──

data class Project(
    val id: Long = 0,
    val name: String,
    val description: String,
    val repoUrl: String = "",
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

data class Epic(
    val id: Long = 0,
    val projectId: Long,
    val title: String,
    val description: String,
    val order: Int = 0
)

data class Feature(
    val id: Long = 0,
    val epicId: Long,
    val title: String,
    val description: String,
    val acceptanceCriteria: String = ""
)

data class DevTask(
    val id: Long = 0,
    val featureId: Long,
    val title: String,
    val description: String,
    val category: TaskCategory = TaskCategory.BACKEND,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val status: TaskStatus = TaskStatus.CREATED,
    val assignedAgent: String = "",
    val branchName: String = "",
    val prUrl: String = "",
    val dependsOn: List<Long> = emptyList(),
    val estimatedComplexity: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

data class TaskTransition(
    val id: Long = 0,
    val taskId: Long,
    val fromStatus: TaskStatus,
    val toStatus: TaskStatus,
    val reason: String = "",
    val triggeredBy: String = "",
    val createdAt: Instant = Instant.now()
)

// ── API DTOs (Serializable for Ktor JSON) ──

@Serializable
data class ProjectRequest(
    val name: String,
    val description: String,
    val repoUrl: String = ""
)

@Serializable
data class ProjectResponse(
    val id: Long,
    val name: String,
    val description: String,
    val repoUrl: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class EpicResponse(
    val id: Long,
    val projectId: Long,
    val title: String,
    val description: String,
    val order: Int
)

@Serializable
data class FeatureResponse(
    val id: Long,
    val epicId: Long,
    val title: String,
    val description: String,
    val acceptanceCriteria: String
)

@Serializable
data class DevTaskResponse(
    val id: Long,
    val featureId: Long,
    val title: String,
    val description: String,
    val category: String,
    val priority: String,
    val status: String,
    val assignedAgent: String,
    val branchName: String,
    val prUrl: String,
    val dependsOn: List<Long>,
    val estimatedComplexity: Int,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class TaskDetailResponse(
    val task: DevTaskResponse,
    val history: List<TaskTransitionResponse>
)

@Serializable
data class PipelineExecuteResponse(
    val executed: Int,
    val tasks: List<DevTaskResponse>
)

@Serializable
data class TaskTransitionRequest(
    val status: String,
    val reason: String = ""
)

@Serializable
data class TaskTransitionResponse(
    val id: Long,
    val taskId: Long,
    val fromStatus: String,
    val toStatus: String,
    val reason: String,
    val triggeredBy: String,
    val createdAt: String
)

@Serializable
data class ProjectStatusResponse(
    val projectId: Long,
    val projectName: String,
    val totalTasks: Int,
    val tasksByStatus: Map<String, Int>,
    val epics: List<EpicResponse>,
    val features: List<FeatureResponse>,
    val tasks: List<DevTaskResponse>
)

@Serializable
data class AnalysisResult(
    val epics: List<AnalysisEpic>
)

@Serializable
data class AnalysisEpic(
    val title: String,
    val description: String,
    val features: List<AnalysisFeature>
)

@Serializable
data class AnalysisFeature(
    val title: String,
    val description: String,
    val acceptanceCriteria: String = "",
    val tasks: List<AnalysisTask>
)

@Serializable
data class AnalysisTask(
    val title: String,
    val description: String,
    val category: String = "BACKEND",
    val priority: String = "MEDIUM",
    val estimatedComplexity: Int = 1,
    val dependsOnTitles: List<String> = emptyList()
)

// ── Extension functions for converting domain -> response ──

fun Project.toResponse() = ProjectResponse(
    id = id, name = name, description = description, repoUrl = repoUrl,
    status = status.name, createdAt = createdAt.toString(), updatedAt = updatedAt.toString()
)

fun Epic.toResponse() = EpicResponse(
    id = id, projectId = projectId, title = title, description = description, order = order
)

fun Feature.toResponse() = FeatureResponse(
    id = id, epicId = epicId, title = title, description = description, acceptanceCriteria = acceptanceCriteria
)

fun DevTask.toResponse() = DevTaskResponse(
    id = id, featureId = featureId, title = title, description = description,
    category = category.name, priority = priority.name, status = status.name,
    assignedAgent = assignedAgent, branchName = branchName, prUrl = prUrl,
    dependsOn = dependsOn, estimatedComplexity = estimatedComplexity,
    createdAt = createdAt.toString(), updatedAt = updatedAt.toString()
)

fun TaskTransition.toResponse() = TaskTransitionResponse(
    id = id, taskId = taskId, fromStatus = fromStatus.name, toStatus = toStatus.name,
    reason = reason, triggeredBy = triggeredBy, createdAt = createdAt.toString()
)
