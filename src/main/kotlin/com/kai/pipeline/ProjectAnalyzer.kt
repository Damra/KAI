package com.kai.pipeline

import com.kai.llm.LLMClient
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Analyzes a project description using LLM and decomposes it into
 * Epic -> Feature -> Task hierarchy. Results are persisted via TaskStore.
 */
class ProjectAnalyzer(
    private val llmClient: LLMClient,
    private val taskStore: TaskStore
) {
    private val logger = LoggerFactory.getLogger(ProjectAnalyzer::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; allowTrailingComma = true }

    companion object {
        private const val MAX_RETRIES = 2
    }

    /**
     * Analyze a project and create Epic/Feature/Task breakdown.
     * Returns the analysis result and persists everything to DB.
     */
    suspend fun analyze(project: Project): AnalysisResult {
        logger.info("Analyzing project: ${project.name}")

        var lastError: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            val systemPrompt = if (attempt == 0) SYSTEM_PROMPT else SYSTEM_PROMPT_STRICT
            val userPrompt = buildAnalysisPrompt(project)

            try {
                val response = llmClient.chat(system = systemPrompt, user = userPrompt)
                logger.info("LLM response received (attempt $attempt, ${response.length} chars)")

                val jsonStr = extractJson(response)
                val result = json.decodeFromString<AnalysisResult>(jsonStr)

                // Validate result has actual content
                val totalTasks = result.epics.sumOf { e -> e.features.sumOf { it.tasks.size } }
                if (totalTasks == 0) {
                    logger.warn("Parsed result has 0 tasks, retrying...")
                    continue
                }

                persistAnalysis(project.id, result)

                logger.info("Analysis complete: ${result.epics.size} epics, " +
                    "${result.epics.sumOf { it.features.size }} features, $totalTasks tasks")

                return result
            } catch (e: Exception) {
                lastError = e
                logger.warn("Analysis attempt $attempt failed: ${e.message}")
            }
        }

        // All retries failed — use fallback
        logger.error("All analysis attempts failed, using fallback", lastError)
        val fallback = buildFallbackResult(project)
        persistAnalysis(project.id, fallback)
        return fallback
    }

    private val SYSTEM_PROMPT = """You are a software architect. Analyze the project and return ONLY valid JSON.
No explanations, no markdown, no text before or after the JSON.
Response must start with { and end with }."""

    private val SYSTEM_PROMPT_STRICT = """IMPORTANT: Return ONLY a JSON object. No text, no explanation, no markdown code blocks.
Your entire response must be a single valid JSON object starting with { and ending with }.
Do not write anything else."""

    private fun buildAnalysisPrompt(project: Project): String = """
Analyze this project and create an Epic/Feature/Task breakdown.

Project: ${project.name}
Description: ${project.description}
${if (project.repoUrl.isNotBlank()) "Repo: ${project.repoUrl}" else ""}

Return this exact JSON structure (with real data filled in):
{"epics":[{"title":"Epic title","description":"Epic desc","features":[{"title":"Feature title","description":"Feature desc","acceptanceCriteria":"criteria text","tasks":[{"title":"Task title","description":"Detailed task description","category":"BACKEND","priority":"HIGH","estimatedComplexity":3,"dependsOnTitles":[]}]}]}]}

Rules:
- Create multiple epics, each with multiple features and tasks
- category: DESIGN, BACKEND, FRONTEND, DEVOPS, TESTING, or DOCUMENTATION
- priority: CRITICAL, HIGH, MEDIUM, or LOW
- estimatedComplexity: 1 (simple) to 5 (very complex)
- acceptanceCriteria must be a string (not an array)
- dependsOnTitles: list of other task titles this depends on (can be empty)
- Return ONLY valid JSON, nothing else
""".trimIndent()

    private fun buildFallbackResult(project: Project): AnalysisResult {
        return AnalysisResult(
            epics = listOf(
                AnalysisEpic(
                    title = "Core Implementation",
                    description = "Main project implementation for ${project.name}",
                    features = listOf(
                        AnalysisFeature(
                            title = "Initial Setup",
                            description = "Project setup and core functionality",
                            tasks = listOf(
                                AnalysisTask(
                                    title = "Project scaffolding",
                                    description = "Set up project structure and dependencies for ${project.name}",
                                    category = "DEVOPS",
                                    priority = "HIGH",
                                    estimatedComplexity = 2
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    /** Persist the analysis result into database via TaskStore */
    private suspend fun persistAnalysis(projectId: Long, result: AnalysisResult) {
        // Map of task title -> task ID for resolving dependencies
        val taskTitleToId = mutableMapOf<String, Long>()
        // Deferred dependency resolutions
        val deferredDeps = mutableListOf<Pair<Long, List<String>>>()

        for ((epicIndex, epicData) in result.epics.withIndex()) {
            val epic = taskStore.createEpic(
                projectId = projectId,
                title = epicData.title,
                description = epicData.description,
                order = epicIndex
            )

            for (featureData in epicData.features) {
                val feature = taskStore.createFeature(
                    epicId = epic.id,
                    title = featureData.title,
                    description = featureData.description,
                    acceptanceCriteria = featureData.acceptanceCriteria
                )

                for (taskData in featureData.tasks) {
                    val category = try { TaskCategory.valueOf(taskData.category) } catch (_: Exception) { TaskCategory.BACKEND }
                    val priority = try { TaskPriority.valueOf(taskData.priority) } catch (_: Exception) { TaskPriority.MEDIUM }

                    val task = taskStore.createTask(
                        featureId = feature.id,
                        title = taskData.title,
                        description = taskData.description,
                        category = category,
                        priority = priority,
                        estimatedComplexity = taskData.estimatedComplexity.coerceIn(1, 5)
                    )

                    taskTitleToId[taskData.title] = task.id
                    if (taskData.dependsOnTitles.isNotEmpty()) {
                        deferredDeps.add(task.id to taskData.dependsOnTitles)
                    }
                }
            }
        }

        // Resolve dependencies by title (best-effort matching)
        for ((taskId, depTitles) in deferredDeps) {
            for (depTitle in depTitles) {
                val depId = taskTitleToId[depTitle]
                if (depId != null) {
                    taskStore.addDependency(taskId, depId)
                } else {
                    logger.warn("Could not resolve dependency '$depTitle' for task $taskId")
                }
            }
        }
    }

    /** Extract JSON from LLM response (may be wrapped in markdown code block or have surrounding text) */
    private fun extractJson(text: String): String {
        // Try markdown code block first
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")
        val match = codeBlockRegex.find(text)
        if (match != null) {
            val inner = match.groupValues[1].trim()
            if (inner.startsWith("{")) return inner
        }

        // Find the outermost { } pair
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1)
        }

        return text.trim()
    }
}
