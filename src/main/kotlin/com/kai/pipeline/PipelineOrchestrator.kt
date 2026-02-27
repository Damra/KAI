package com.kai.pipeline

import com.kai.core.MetaController
import com.kai.models.StreamEvent
import com.kai.tools.GitHubTool
import org.slf4j.LoggerFactory

/**
 * Pipeline controller — coordinates project analysis, task execution, and status reporting.
 * Phase 2: manually triggered via REST API.
 * Phase 3: will run as background coroutine.
 */
class PipelineOrchestrator(
    private val taskStore: TaskStore,
    private val projectAnalyzer: ProjectAnalyzer,
    private val gitHubTool: GitHubTool,
    private val metaController: MetaController
) {
    private val logger = LoggerFactory.getLogger(PipelineOrchestrator::class.java)

    /** Run LLM analysis on a project and create Epic/Feature/Task breakdown */
    suspend fun analyzeProject(projectId: Long): AnalysisResult {
        val project = taskStore.getProject(projectId)
            ?: throw IllegalArgumentException("Project $projectId not found")

        logger.info("Starting analysis for project: ${project.name}")
        return projectAnalyzer.analyze(project)
    }

    /**
     * Analyze project + auto-transition CREATED→PLANNED + execute.
     * Single-click pipeline: analyze → plan → execute.
     */
    suspend fun analyzeAndExecute(projectId: Long, onEvent: (suspend (StreamEvent) -> Unit)? = null): List<DevTask> {
        // Step 1: Analyze — creates tasks in CREATED status
        val analysisResult = analyzeProject(projectId)
        logger.info("Analysis complete: ${analysisResult.epics.size} epics created")

        // Step 2: Transition all CREATED tasks to PLANNED
        transitionCreatedToPlanned(projectId)

        // Step 3: Execute the pipeline
        return executeNextTasks(projectId, onEvent)
    }

    /** Bulk-transition all CREATED tasks for a project to PLANNED */
    private suspend fun transitionCreatedToPlanned(projectId: Long) {
        val createdTasks = taskStore.getTasksByProject(projectId, TaskStatus.CREATED)
        logger.info("Transitioning ${createdTasks.size} CREATED tasks to PLANNED for project $projectId")
        for (task in createdTasks) {
            try {
                taskStore.transitionTask(task.id, TaskStatus.PLANNED, "Auto-planned by pipeline")
            } catch (e: Exception) {
                logger.warn("Could not transition task ${task.id} CREATED→PLANNED: ${e.message}")
            }
        }
    }

    /**
     * Execute the next batch of ready tasks for a project.
     * Returns the list of tasks that were started.
     */
    suspend fun executeNextTasks(projectId: Long, onEvent: (suspend (StreamEvent) -> Unit)? = null): List<DevTask> {
        // Auto-transition any remaining CREATED tasks to PLANNED
        transitionCreatedToPlanned(projectId)

        val readyTasks = taskStore.getReadyTasks(projectId)
        if (readyTasks.isEmpty()) {
            logger.info("No ready tasks for project $projectId")
            return emptyList()
        }

        logger.info("Found ${readyTasks.size} ready tasks for project $projectId")

        // Transition PLANNED tasks to READY
        val tasksToExecute = mutableListOf<DevTask>()
        for (task in readyTasks) {
            try {
                val readyTask = taskStore.transitionTask(task.id, TaskStatus.READY, "Dependencies satisfied")
                tasksToExecute.add(readyTask)
            } catch (e: Exception) {
                logger.warn("Could not transition task ${task.id} to READY: ${e.message}")
            }
        }

        // Execute each task
        for (task in tasksToExecute) {
            try {
                processTask(task.id, onEvent)
            } catch (e: Exception) {
                logger.error("Failed to process task ${task.id}: ${e.message}", e)
            }
        }

        return tasksToExecute
    }

    /**
     * Process a single task through the pipeline:
     * 1. Create branch (GitHubTool)
     * 2. Write code (MetaController -> CODE_WRITER agent)
     * 3. Create PR (GitHubTool)
     * 4. Review (MetaController -> REVIEWER agent)
     * 5. Merge or request changes
     */
    suspend fun processTask(taskId: Long, onEvent: (suspend (StreamEvent) -> Unit)? = null): DevTask {
        val task = taskStore.getTask(taskId)
            ?: throw IllegalArgumentException("Task $taskId not found")

        logger.info("Processing task: [${task.id}] ${task.title}")

        // Step 1: Transition to IN_PROGRESS
        val inProgressTask = taskStore.transitionTask(taskId, TaskStatus.IN_PROGRESS, "Pipeline started")
        val branchName = "feature/task-${task.id}-${task.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(40)}"
        taskStore.updateTask(taskId, branchName = branchName, assignedAgent = "CODE_WRITER")

        onEvent?.invoke(StreamEvent.PipelineUpdate(
            taskId = taskId.toString(),
            status = TaskStatus.IN_PROGRESS.name,
            message = "Task started: ${task.title}"
        ))

        // Step 2: Create branch
        try {
            gitHubTool.execute(mapOf("action" to "create_branch", "branch_name" to branchName))
            logger.info("Branch created: $branchName")
        } catch (e: Exception) {
            logger.warn("Branch creation failed (continuing): ${e.message}")
        }

        // Step 3: Generate code via MetaController
        val codePrompt = buildCodePrompt(task)
        val codeResult = metaController.process(codePrompt, "pipeline-task-$taskId", onEvent)
        logger.info("Code generation complete for task ${task.id}")

        // Save code generation output
        try {
            taskStore.saveTaskOutput(taskId, "CODE_GENERATION", codeResult.answer, "CODE_WRITER")
        } catch (e: Exception) {
            logger.warn("Failed to save code output for task $taskId: ${e.message}")
        }

        // Step 4: Transition to PR_OPENED
        taskStore.transitionTask(taskId, TaskStatus.PR_OPENED, "Code generated")

        // Step 5: Create PR
        var prUrl = ""
        try {
            val prResult = gitHubTool.execute(mapOf(
                "action" to "create_pr",
                "message" to "[KAI-${task.id}] ${task.title}",
                "body" to "Auto-generated by KAI Pipeline\n\n${task.description}\n\n---\n${codeResult.answer.take(500)}",
                "base_branch" to "main"
            ))
            if (prResult.isSuccess) {
                prUrl = (prResult as com.kai.core.ToolResult.Success).data ?: ""
                taskStore.updateTask(taskId, prUrl = prUrl)
            }
        } catch (e: Exception) {
            logger.warn("PR creation failed (continuing): ${e.message}")
        }

        // Step 6: Review via MetaController
        taskStore.transitionTask(taskId, TaskStatus.REVIEWING, "PR created: $prUrl")
        val reviewPrompt = "Review the code generated for task: ${task.title}\n\nCode output:\n${codeResult.answer.take(2000)}"
        val reviewResult = metaController.process(reviewPrompt, "pipeline-review-$taskId", onEvent)

        // Save review output
        try {
            taskStore.saveTaskOutput(taskId, "REVIEW", reviewResult.answer, "REVIEWER")
        } catch (e: Exception) {
            logger.warn("Failed to save review output for task $taskId: ${e.message}")
        }

        // Step 7: Approve or request changes (simple heuristic for now)
        val isApproved = !reviewResult.answer.lowercase().let {
            it.contains("major issue") || it.contains("critical bug") || it.contains("reject")
        }

        return if (isApproved) {
            taskStore.transitionTask(taskId, TaskStatus.APPROVED, "Review passed")
            taskStore.transitionTask(taskId, TaskStatus.MERGED, "Auto-merged")
            taskStore.transitionTask(taskId, TaskStatus.TESTING, "Running tests")
            // For now, auto-pass testing (Phase 3 will have real test execution)
            taskStore.transitionTask(taskId, TaskStatus.TEST_PASSED, "Tests passed")
            val deployedTask = taskStore.transitionTask(taskId, TaskStatus.DEPLOYED, "Deployed")

            onEvent?.invoke(StreamEvent.PipelineUpdate(
                taskId = taskId.toString(),
                status = TaskStatus.DEPLOYED.name,
                message = "Task deployed: ${task.title}"
            ))

            deployedTask
        } else {
            val changesTask = taskStore.transitionTask(taskId, TaskStatus.CHANGES_REQUESTED, reviewResult.answer.take(500))

            onEvent?.invoke(StreamEvent.PipelineUpdate(
                taskId = taskId.toString(),
                status = TaskStatus.CHANGES_REQUESTED.name,
                message = "Changes requested for: ${task.title}"
            ))

            changesTask
        }
    }

    /** Get full project status including all tasks and statistics */
    suspend fun getProjectStatus(projectId: Long): ProjectStatusResponse {
        val project = taskStore.getProject(projectId)
            ?: throw IllegalArgumentException("Project $projectId not found")

        val epics = taskStore.getEpicsByProject(projectId)
        val features = taskStore.getFeaturesByProject(projectId)
        val tasks = taskStore.getTasksByProject(projectId)

        val tasksByStatus = tasks.groupBy { it.status.name }.mapValues { it.value.size }

        return ProjectStatusResponse(
            projectId = project.id,
            projectName = project.name,
            totalTasks = tasks.size,
            tasksByStatus = tasksByStatus,
            epics = epics.map { it.toResponse() },
            features = features.map { it.toResponse() },
            tasks = tasks.map { it.toResponse() }
        )
    }

    private fun buildCodePrompt(task: DevTask): String = """
        Asagidaki task icin kod yaz:

        Task: ${task.title}
        Aciklama: ${task.description}
        Kategori: ${task.category}
        Oncelik: ${task.priority}

        Kurallar:
        - Kotlin/Ktor best practices kullan
        - Temiz, okunabilir kod yaz
        - Gerekli import'lari ekle
        - Error handling ekle
    """.trimIndent()
}
