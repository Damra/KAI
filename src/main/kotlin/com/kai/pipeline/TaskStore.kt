package com.kai.pipeline

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Pipeline task persistence layer.
 * Handles CRUD operations for Projects, Epics, Features, DevTasks.
 * Uses TaskStateMachine for transition validation.
 */
class TaskStore(private val database: Database) {
    private val logger = LoggerFactory.getLogger(TaskStore::class.java)

    /** Create pipeline tables if they don't exist */
    suspend fun initialize() {
        newSuspendedTransaction(db = database) {
            SchemaUtils.create(Projects, Epics, Features, DevTasks, TaskDependencies, TaskTransitions, TaskOutputs)
        }
        logger.info("Pipeline tables initialized")
    }

    // ── Projects ──

    suspend fun createProject(name: String, description: String, repoUrl: String = ""): Project {
        return newSuspendedTransaction(db = database) {
            val now = Instant.now()
            val id = Projects.insertAndGetId {
                it[Projects.name] = name
                it[Projects.description] = description
                it[Projects.repoUrl] = repoUrl
                it[Projects.status] = ProjectStatus.ACTIVE.name
                it[Projects.createdAt] = now
                it[Projects.updatedAt] = now
            }
            Project(id = id.value, name = name, description = description, repoUrl = repoUrl, createdAt = now, updatedAt = now)
        }
    }

    suspend fun getProject(id: Long): Project? {
        return newSuspendedTransaction(db = database) {
            Projects.selectAll().where { Projects.id eq id }.singleOrNull()?.toProject()
        }
    }

    suspend fun listProjects(): List<Project> {
        return newSuspendedTransaction(db = database) {
            Projects.selectAll().orderBy(Projects.createdAt, SortOrder.DESC).map { it.toProject() }
        }
    }

    // ── Epics ──

    suspend fun createEpic(projectId: Long, title: String, description: String, order: Int = 0): Epic {
        return newSuspendedTransaction(db = database) {
            val id = Epics.insertAndGetId {
                it[Epics.projectId] = projectId
                it[Epics.title] = title
                it[Epics.description] = description
                it[Epics.order] = order
            }
            Epic(id = id.value, projectId = projectId, title = title, description = description, order = order)
        }
    }

    suspend fun getEpicsByProject(projectId: Long): List<Epic> {
        return newSuspendedTransaction(db = database) {
            Epics.selectAll().where { Epics.projectId eq projectId }
                .orderBy(Epics.order, SortOrder.ASC)
                .map { it.toEpic() }
        }
    }

    // ── Features ──

    suspend fun createFeature(epicId: Long, title: String, description: String, acceptanceCriteria: String = ""): Feature {
        return newSuspendedTransaction(db = database) {
            val id = Features.insertAndGetId {
                it[Features.epicId] = epicId
                it[Features.title] = title
                it[Features.description] = description
                it[Features.acceptanceCriteria] = acceptanceCriteria
            }
            Feature(id = id.value, epicId = epicId, title = title, description = description, acceptanceCriteria = acceptanceCriteria)
        }
    }

    suspend fun getFeaturesByEpic(epicId: Long): List<Feature> {
        return newSuspendedTransaction(db = database) {
            Features.selectAll().where { Features.epicId eq epicId }.map { it.toFeature() }
        }
    }

    suspend fun getFeaturesByProject(projectId: Long): List<Feature> {
        return newSuspendedTransaction(db = database) {
            val epicIds = Epics.selectAll().where { Epics.projectId eq projectId }.map { it[Epics.id].value }
            if (epicIds.isEmpty()) return@newSuspendedTransaction emptyList()
            Features.selectAll().where { Features.epicId inList epicIds }.map { it.toFeature() }
        }
    }

    // ── DevTasks ──

    suspend fun createTask(
        featureId: Long,
        title: String,
        description: String,
        category: TaskCategory = TaskCategory.BACKEND,
        priority: TaskPriority = TaskPriority.MEDIUM,
        estimatedComplexity: Int = 1,
        dependsOn: List<Long> = emptyList()
    ): DevTask {
        return newSuspendedTransaction(db = database) {
            val now = Instant.now()
            val id = DevTasks.insertAndGetId {
                it[DevTasks.featureId] = featureId
                it[DevTasks.title] = title
                it[DevTasks.description] = description
                it[DevTasks.category] = category.name
                it[DevTasks.priority] = priority.name
                it[DevTasks.status] = TaskStatus.CREATED.name
                it[DevTasks.estimatedComplexity] = estimatedComplexity
                it[DevTasks.createdAt] = now
                it[DevTasks.updatedAt] = now
            }

            // Insert dependencies
            for (depId in dependsOn) {
                TaskDependencies.insert {
                    it[TaskDependencies.taskId] = id.value
                    it[TaskDependencies.dependsOnTaskId] = depId
                }
            }

            DevTask(
                id = id.value, featureId = featureId, title = title, description = description,
                category = category, priority = priority, estimatedComplexity = estimatedComplexity,
                dependsOn = dependsOn, createdAt = now, updatedAt = now
            )
        }
    }

    suspend fun getTask(id: Long): DevTask? {
        return newSuspendedTransaction(db = database) {
            DevTasks.selectAll().where { DevTasks.id eq id }.singleOrNull()?.toDevTask()
        }
    }

    suspend fun getTasksByProject(projectId: Long, statusFilter: TaskStatus? = null): List<DevTask> {
        return newSuspendedTransaction(db = database) {
            val epicIds = Epics.selectAll().where { Epics.projectId eq projectId }.map { it[Epics.id].value }
            if (epicIds.isEmpty()) return@newSuspendedTransaction emptyList()
            val featureIds = Features.selectAll().where { Features.epicId inList epicIds }.map { it[Features.id].value }
            if (featureIds.isEmpty()) return@newSuspendedTransaction emptyList()

            val query = DevTasks.selectAll().where { DevTasks.featureId inList featureIds }
            val rows = if (statusFilter != null) {
                query.andWhere { DevTasks.status eq statusFilter.name }
            } else {
                query
            }
            rows.orderBy(DevTasks.createdAt, SortOrder.ASC).map { it.toDevTask() }
        }
    }

    suspend fun getTasksByStatus(status: TaskStatus): List<DevTask> {
        return newSuspendedTransaction(db = database) {
            DevTasks.selectAll().where { DevTasks.status eq status.name }
                .orderBy(DevTasks.createdAt, SortOrder.ASC)
                .map { it.toDevTask() }
        }
    }

    /** Get tasks whose dependencies are all DEPLOYED (or tasks with no dependencies) */
    suspend fun getReadyTasks(projectId: Long): List<DevTask> {
        val allTasks = getTasksByProject(projectId, TaskStatus.PLANNED)
        return allTasks.filter { task ->
            if (task.dependsOn.isEmpty()) {
                true
            } else {
                val depStatuses = newSuspendedTransaction(db = database) {
                    DevTasks.selectAll().where { DevTasks.id inList task.dependsOn }
                        .map { TaskStatus.valueOf(it[DevTasks.status]) }
                }
                depStatuses.all { it == TaskStatus.DEPLOYED }
            }
        }
    }

    /** Transition a task to a new status with state machine validation */
    suspend fun transitionTask(taskId: Long, newStatus: TaskStatus, reason: String = "", triggeredBy: String = "system"): DevTask {
        return newSuspendedTransaction(db = database) {
            val row = DevTasks.selectAll().where { DevTasks.id eq taskId }.singleOrNull()
                ?: throw IllegalArgumentException("Task $taskId not found")

            val currentStatus = TaskStatus.valueOf(row[DevTasks.status])
            val validation = TaskStateMachine.validateTransition(currentStatus, newStatus)
            if (!validation.valid) {
                throw IllegalStateException(validation.errorMessage)
            }

            val now = Instant.now()
            DevTasks.update({ DevTasks.id eq taskId }) {
                it[DevTasks.status] = newStatus.name
                it[DevTasks.updatedAt] = now
            }

            // Record transition
            TaskTransitions.insert {
                it[TaskTransitions.taskId] = taskId
                it[TaskTransitions.fromStatus] = currentStatus.name
                it[TaskTransitions.toStatus] = newStatus.name
                it[TaskTransitions.reason] = reason
                it[TaskTransitions.triggeredBy] = triggeredBy
                it[TaskTransitions.createdAt] = now
            }

            row.toDevTask().copy(status = newStatus, updatedAt = now)
        }
    }

    /** Add a dependency between tasks */
    suspend fun addDependency(taskId: Long, dependsOnTaskId: Long) {
        newSuspendedTransaction(db = database) {
            TaskDependencies.insert {
                it[TaskDependencies.taskId] = taskId
                it[TaskDependencies.dependsOnTaskId] = dependsOnTaskId
            }
        }
    }

    /** Update task fields (branch, PR URL, assigned agent) */
    suspend fun updateTask(taskId: Long, branchName: String? = null, prUrl: String? = null, assignedAgent: String? = null): DevTask {
        return newSuspendedTransaction(db = database) {
            val now = Instant.now()
            DevTasks.update({ DevTasks.id eq taskId }) {
                if (branchName != null) it[DevTasks.branchName] = branchName
                if (prUrl != null) it[DevTasks.prUrl] = prUrl
                if (assignedAgent != null) it[DevTasks.assignedAgent] = assignedAgent
                it[DevTasks.updatedAt] = now
            }
            DevTasks.selectAll().where { DevTasks.id eq taskId }.single().toDevTask()
        }
    }

    suspend fun getTaskHistory(taskId: Long): List<TaskTransition> {
        return newSuspendedTransaction(db = database) {
            TaskTransitions.selectAll().where { TaskTransitions.taskId eq taskId }
                .orderBy(TaskTransitions.createdAt, SortOrder.ASC)
                .map { it.toTaskTransition() }
        }
    }

    // ── Task Outputs ──

    suspend fun saveTaskOutput(taskId: Long, outputType: String, content: String, agent: String = ""): TaskOutput {
        return newSuspendedTransaction(db = database) {
            val now = Instant.now()
            val id = TaskOutputs.insertAndGetId {
                it[TaskOutputs.taskId] = taskId
                it[TaskOutputs.outputType] = outputType
                it[TaskOutputs.content] = content
                it[TaskOutputs.agent] = agent
                it[TaskOutputs.createdAt] = now
            }
            TaskOutput(id = id.value, taskId = taskId, outputType = outputType, content = content, agent = agent, createdAt = now)
        }
    }

    suspend fun getTaskOutputs(taskId: Long): List<TaskOutput> {
        return newSuspendedTransaction(db = database) {
            TaskOutputs.selectAll().where { TaskOutputs.taskId eq taskId }
                .orderBy(TaskOutputs.createdAt, SortOrder.ASC)
                .map { it.toTaskOutput() }
        }
    }

    // ── Row mapping helpers ──

    private fun ResultRow.toProject() = Project(
        id = this[Projects.id].value,
        name = this[Projects.name],
        description = this[Projects.description],
        repoUrl = this[Projects.repoUrl],
        status = ProjectStatus.valueOf(this[Projects.status]),
        createdAt = this[Projects.createdAt],
        updatedAt = this[Projects.updatedAt]
    )

    private fun ResultRow.toEpic() = Epic(
        id = this[Epics.id].value,
        projectId = this[Epics.projectId].value,
        title = this[Epics.title],
        description = this[Epics.description],
        order = this[Epics.order]
    )

    private fun ResultRow.toFeature() = Feature(
        id = this[Features.id].value,
        epicId = this[Features.epicId].value,
        title = this[Features.title],
        description = this[Features.description],
        acceptanceCriteria = this[Features.acceptanceCriteria]
    )

    private fun ResultRow.toDevTask(): DevTask {
        val taskId = this[DevTasks.id].value
        val deps = TaskDependencies.selectAll().where { TaskDependencies.taskId eq taskId }
            .map { it[TaskDependencies.dependsOnTaskId].value }

        return DevTask(
            id = taskId,
            featureId = this[DevTasks.featureId].value,
            title = this[DevTasks.title],
            description = this[DevTasks.description],
            category = TaskCategory.valueOf(this[DevTasks.category]),
            priority = TaskPriority.valueOf(this[DevTasks.priority]),
            status = TaskStatus.valueOf(this[DevTasks.status]),
            assignedAgent = this[DevTasks.assignedAgent],
            branchName = this[DevTasks.branchName],
            prUrl = this[DevTasks.prUrl],
            estimatedComplexity = this[DevTasks.estimatedComplexity],
            dependsOn = deps,
            createdAt = this[DevTasks.createdAt],
            updatedAt = this[DevTasks.updatedAt]
        )
    }

    private fun ResultRow.toTaskTransition() = TaskTransition(
        id = this[TaskTransitions.id].value,
        taskId = this[TaskTransitions.taskId].value,
        fromStatus = TaskStatus.valueOf(this[TaskTransitions.fromStatus]),
        toStatus = TaskStatus.valueOf(this[TaskTransitions.toStatus]),
        reason = this[TaskTransitions.reason],
        triggeredBy = this[TaskTransitions.triggeredBy],
        createdAt = this[TaskTransitions.createdAt]
    )

    private fun ResultRow.toTaskOutput() = TaskOutput(
        id = this[TaskOutputs.id].value,
        taskId = this[TaskOutputs.taskId].value,
        outputType = this[TaskOutputs.outputType],
        content = this[TaskOutputs.content],
        agent = this[TaskOutputs.agent],
        createdAt = this[TaskOutputs.createdAt]
    )
}
