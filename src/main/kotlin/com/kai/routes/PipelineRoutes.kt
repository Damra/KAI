package com.kai.routes

import com.kai.pipeline.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PipelineRoutes")

fun Route.pipelineRoutes(
    taskStore: TaskStore,
    orchestrator: PipelineOrchestrator
) {
    route("/api/v1/pipeline") {

        // ── Projects ──

        /** POST /api/v1/pipeline/projects — Create a new project */
        post("/projects") {
            val request = try {
                call.receive<ProjectRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.message}"))
                return@post
            }

            if (request.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "'name' cannot be empty"))
                return@post
            }

            try {
                val project = taskStore.createProject(request.name, request.description, request.repoUrl)
                call.respond(HttpStatusCode.Created, project.toResponse())
            } catch (e: Exception) {
                logger.error("Failed to create project", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create project: ${e.message}"))
            }
        }

        /** GET /api/v1/pipeline/projects — List all projects */
        get("/projects") {
            try {
                val projects = taskStore.listProjects()
                call.respond(projects.map { it.toResponse() })
            } catch (e: Exception) {
                logger.error("Failed to list projects", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list projects: ${e.message}"))
            }
        }

        /** GET /api/v1/pipeline/projects/:id — Project detail with tasks */
        get("/projects/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))

            try {
                val status = orchestrator.getProjectStatus(id)
                call.respond(status)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
            } catch (e: Exception) {
                logger.error("Failed to get project", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get project: ${e.message}"))
            }
        }

        /** POST /api/v1/pipeline/projects/:id/analyze-and-execute — Analyze + auto-plan + execute */
        post("/projects/{id}/analyze-and-execute") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))

            try {
                val executedTasks = orchestrator.analyzeAndExecute(id)
                call.respond(PipelineExecuteResponse(
                    executed = executedTasks.size,
                    tasks = executedTasks.map { it.toResponse() }
                ))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
            } catch (e: Exception) {
                logger.error("Analyze-and-execute failed", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Analyze-and-execute failed: ${e.message}"))
            }
        }

        /** POST /api/v1/pipeline/projects/:id/analyze — Start task decomposition */
        post("/projects/{id}/analyze") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))

            try {
                val result = orchestrator.analyzeProject(id)
                call.respond(result)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
            } catch (e: Exception) {
                logger.error("Failed to analyze project", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Analysis failed: ${e.message}"))
            }
        }

        // ── Pipeline Execution ──

        /** POST /api/v1/pipeline/execute/:projectId — Execute next batch of tasks */
        post("/execute/{projectId}") {
            val projectId = call.parameters["projectId"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))

            try {
                val executedTasks = orchestrator.executeNextTasks(projectId)
                call.respond(PipelineExecuteResponse(
                    executed = executedTasks.size,
                    tasks = executedTasks.map { it.toResponse() }
                ))
            } catch (e: Exception) {
                logger.error("Pipeline execution failed", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Execution failed: ${e.message}"))
            }
        }

        // ── Tasks ──

        /** GET /api/v1/pipeline/tasks — List all tasks (optional status filter) */
        get("/tasks") {
            try {
                val statusParam = call.request.queryParameters["status"]
                val tasks = if (statusParam != null) {
                    val status = try {
                        TaskStatus.valueOf(statusParam.uppercase())
                    } catch (_: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid status: $statusParam"))
                    }
                    taskStore.getTasksByStatus(status)
                } else {
                    // Return all tasks across all projects
                    val projects = taskStore.listProjects()
                    projects.flatMap { taskStore.getTasksByProject(it.id) }
                }
                call.respond(tasks.map { it.toResponse() })
            } catch (e: Exception) {
                logger.error("Failed to list tasks", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list tasks: ${e.message}"))
            }
        }

        /** GET /api/v1/pipeline/tasks/:id — Task detail with history */
        get("/tasks/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))

            try {
                val task = taskStore.getTask(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
                val history = taskStore.getTaskHistory(id)
                val outputs = taskStore.getTaskOutputs(id)
                call.respond(TaskDetailResponse(
                    task = task.toResponse(),
                    history = history.map { it.toResponse() },
                    outputs = outputs.map { it.toResponse() }
                ))
            } catch (e: Exception) {
                logger.error("Failed to get task", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get task: ${e.message}"))
            }
        }

        /** POST /api/v1/pipeline/tasks/:id/transition — Manual state change */
        post("/tasks/{id}/transition") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))

            val request = try {
                call.receive<TaskTransitionRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.message}"))
                return@post
            }

            try {
                val newStatus = TaskStatus.valueOf(request.status.uppercase())
                val task = taskStore.transitionTask(id, newStatus, request.reason, triggeredBy = "manual")
                call.respond(task.toResponse())
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
            } catch (e: Exception) {
                logger.error("Failed to transition task", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Transition failed: ${e.message}"))
            }
        }

        // ── Status ──

        /** GET /api/v1/pipeline/status/:projectId — Pipeline dashboard data */
        get("/status/{projectId}") {
            val projectId = call.parameters["projectId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))

            try {
                val status = orchestrator.getProjectStatus(projectId)
                call.respond(status)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
            } catch (e: Exception) {
                logger.error("Failed to get status", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get status: ${e.message}"))
            }
        }
    }
}
