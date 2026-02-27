package com.kai.pipeline

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed ORM table definitions for the pipeline system.
 */

object Projects : LongIdTable("pipeline_projects") {
    val name = varchar("name", 255)
    val description = text("description")
    val repoUrl = varchar("repo_url", 512).default("")
    val status = varchar("status", 50).default(ProjectStatus.ACTIVE.name)
    val createdAt = timestamp("created_at").clientDefault { java.time.Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { java.time.Instant.now() }
}

object Epics : LongIdTable("pipeline_epics") {
    val projectId = reference("project_id", Projects, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 500)
    val description = text("description")
    val order = integer("epic_order").default(0)
}

object Features : LongIdTable("pipeline_features") {
    val epicId = reference("epic_id", Epics, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 500)
    val description = text("description")
    val acceptanceCriteria = text("acceptance_criteria").default("")
}

object DevTasks : LongIdTable("pipeline_dev_tasks") {
    val featureId = reference("feature_id", Features, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 500)
    val description = text("description")
    val category = varchar("category", 50).default(TaskCategory.BACKEND.name)
    val priority = varchar("priority", 50).default(TaskPriority.MEDIUM.name)
    val status = varchar("status", 50).default(TaskStatus.CREATED.name)
    val assignedAgent = varchar("assigned_agent", 100).default("")
    val branchName = varchar("branch_name", 255).default("")
    val prUrl = varchar("pr_url", 512).default("")
    val estimatedComplexity = integer("estimated_complexity").default(1)
    val createdAt = timestamp("created_at").clientDefault { java.time.Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { java.time.Instant.now() }
}

object TaskDependencies : Table("pipeline_task_dependencies") {
    val taskId = reference("task_id", DevTasks, onDelete = ReferenceOption.CASCADE)
    val dependsOnTaskId = reference("depends_on_task_id", DevTasks, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(taskId, dependsOnTaskId)
}

object TaskTransitions : LongIdTable("pipeline_task_transitions") {
    val taskId = reference("task_id", DevTasks, onDelete = ReferenceOption.CASCADE)
    val fromStatus = varchar("from_status", 50)
    val toStatus = varchar("to_status", 50)
    val reason = text("reason").default("")
    val triggeredBy = varchar("triggered_by", 100).default("")
    val createdAt = timestamp("created_at").clientDefault { java.time.Instant.now() }
}

object TaskOutputs : LongIdTable("pipeline_task_outputs") {
    val taskId = reference("task_id", DevTasks, onDelete = ReferenceOption.CASCADE)
    val outputType = varchar("output_type", 50)  // CODE_GENERATION, REVIEW, TEST_RESULT
    val content = text("content")
    val agent = varchar("agent", 100).default("")  // CODE_WRITER, REVIEWER
    val createdAt = timestamp("created_at").clientDefault { java.time.Instant.now() }
}
