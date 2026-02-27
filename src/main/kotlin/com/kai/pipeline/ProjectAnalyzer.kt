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
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Analyze a project and create Epic/Feature/Task breakdown.
     * Returns the analysis result and persists everything to DB.
     */
    suspend fun analyze(project: Project): AnalysisResult {
        logger.info("Analyzing project: ${project.name}")

        val prompt = buildAnalysisPrompt(project)
        val response = llmClient.chat(
            system = """Sen deneyimli bir yazilim mimarsiniz. Proje tanimlarini analiz edip
                |Epic -> Feature -> Task kirilimi yapiyorsun. Sadece gecerli JSON dondur.
                |Her task icin dogru category (DESIGN, BACKEND, FRONTEND, DEVOPS, TESTING, DOCUMENTATION),
                |priority (CRITICAL, HIGH, MEDIUM, LOW) ve complexity (1-5) belirle.
                |Tasklar arasi bagimliliklari dependsOnTitles ile belirt.""".trimMargin(),
            user = prompt
        )

        val result = parseAnalysisResponse(response)
        persistAnalysis(project.id, result)

        logger.info("Analysis complete: ${result.epics.size} epics, " +
            "${result.epics.sumOf { it.features.size }} features, " +
            "${result.epics.sumOf { e -> e.features.sumOf { it.tasks.size } }} tasks")

        return result
    }

    private fun buildAnalysisPrompt(project: Project): String = """
        Proje Adi: ${project.name}
        Proje Aciklamasi: ${project.description}
        ${if (project.repoUrl.isNotBlank()) "Repo: ${project.repoUrl}" else ""}

        Bu projeyi analiz et ve asagidaki JSON formatinda Epic/Feature/Task kirilimi yap:

        {
          "epics": [
            {
              "title": "Epic basligi",
              "description": "Epic aciklamasi",
              "features": [
                {
                  "title": "Feature basligi",
                  "description": "Feature aciklamasi",
                  "acceptanceCriteria": "Kabul kriterleri",
                  "tasks": [
                    {
                      "title": "Task basligi",
                      "description": "Detayli task aciklamasi",
                      "category": "BACKEND",
                      "priority": "HIGH",
                      "estimatedComplexity": 3,
                      "dependsOnTitles": ["Baska bir task basligi"]
                    }
                  ]
                }
              ]
            }
          ]
        }

        Kurallar:
        - Her epic en az 1 feature icermeli
        - Her feature en az 1 task icermeli
        - Category degerleri: DESIGN, BACKEND, FRONTEND, DEVOPS, TESTING, DOCUMENTATION
        - Priority degerleri: CRITICAL, HIGH, MEDIUM, LOW
        - estimatedComplexity: 1 (basit) - 5 (cok karmasik)
        - dependsOnTitles: Bu task'in bagli oldugu diger task'larin title'lari (bos olabilir)
        - Sadece JSON dondur, baska aciklama yazma
    """.trimIndent()

    private fun parseAnalysisResponse(response: String): AnalysisResult {
        return try {
            val jsonStr = extractJson(response)
            json.decodeFromString<AnalysisResult>(jsonStr)
        } catch (e: Exception) {
            logger.error("Failed to parse analysis response, using fallback. Response: ${response.take(500)}", e)
            // Fallback: minimal structure
            AnalysisResult(
                epics = listOf(
                    AnalysisEpic(
                        title = "Core Implementation",
                        description = "Main project implementation",
                        features = listOf(
                            AnalysisFeature(
                                title = "Initial Setup",
                                description = "Project setup and core functionality",
                                tasks = listOf(
                                    AnalysisTask(
                                        title = "Project scaffolding",
                                        description = "Set up project structure and dependencies",
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

    /** Extract JSON from LLM response (may be wrapped in markdown code block) */
    private fun extractJson(text: String): String {
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n([\\s\\S]*?)```")
        val match = codeBlockRegex.find(text)
        if (match != null) return match.groupValues[1].trim()

        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1)
        }
        return text
    }
}
