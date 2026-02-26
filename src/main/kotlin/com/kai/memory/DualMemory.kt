package com.kai.memory

import com.kai.core.AgentStep
import com.kai.core.AgentTask
import com.kai.llm.EmbeddingClient
import com.kai.models.CodeArtifact
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

/**
 * Dual Memory: pgvector (episodic) + Neo4j (semantic).
 *
 * Episodic: "Geçen sefer HttpClient yazarken CIO engine kullanmıştık → 0.85 skor"
 * Semantic: "Kullanıcı MVVM tercih ediyor, Kotlin coroutines kullanıyor"
 */
class DualMemory(
    private val vectorStore: PgVectorStore,
    private val graphStore: Neo4jStore,
    private val embeddingClient: EmbeddingClient
) : MemoryLayer {
    private val logger = LoggerFactory.getLogger(DualMemory::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ── Episodic Memory (pgvector) ──

    override suspend fun recallSimilar(query: String, limit: Int): List<Episode> {
        return try {
            val embedding = embeddingClient.embed(query)
            vectorStore.querySimilar(embedding, limit)
        } catch (e: Exception) {
            logger.warn("Episodic recall failed, returning empty: ${e.message}")
            emptyList()
        }
    }

    override suspend fun storeEpisode(
        task: AgentTask,
        trajectory: List<AgentStep>,
        outcome: AgentStep.Answer
    ) {
        try {
            val summary = summarizeTrajectory(trajectory)
            val embedding = embeddingClient.embed(task.description)
            val score = calculateScore(outcome, trajectory)

            vectorStore.insertEpisode(
                taskDescription = task.description,
                trajectorySummary = summary,
                outcomeScore = score,
                artifacts = json.encodeToString(outcome.artifacts),
                embedding = embedding
            )
            logger.info("Episode stored: ${task.description.take(50)}... (score: $score)")
        } catch (e: Exception) {
            logger.error("Failed to store episode", e)
        }
    }

    // ── Semantic Memory (Neo4j) ──

    override suspend fun queryGraph(entities: List<String>): GraphContext {
        return try {
            graphStore.queryRelated(entities)
        } catch (e: Exception) {
            logger.warn("Graph query failed, returning empty: ${e.message}")
            GraphContext(emptyList(), entities)
        }
    }

    override suspend fun updateGraph(facts: List<Fact>) {
        try {
            graphStore.mergeFacts(facts)
            logger.info("Graph updated with ${facts.size} facts")
        } catch (e: Exception) {
            logger.error("Failed to update graph", e)
        }
    }

    // ── Session Memory (in-memory, short-lived) ──

    private val sessionStore = mutableMapOf<String, String>()

    override suspend fun getSessionContext(sessionId: String): String {
        return sessionStore.getOrDefault(sessionId, "")
    }

    override suspend fun updateSessionContext(sessionId: String, context: String) {
        sessionStore[sessionId] = context
    }

    // ── Helpers ──

    private fun summarizeTrajectory(trajectory: List<AgentStep>): String {
        return trajectory.mapNotNull { step ->
            when (step) {
                is AgentStep.Think -> "Think: ${step.thought.take(100)}"
                is AgentStep.Act -> "Act: ${step.toolName}(${step.reasoning.take(50)})"
                is AgentStep.Observe -> "Observe: ${step.toolName} -> ${
                    when (step.result) {
                        is com.kai.core.ToolResult.Success -> "success"
                        is com.kai.core.ToolResult.Failure -> "failure"
                    }
                }"
                is AgentStep.Answer -> "Answer: ${step.content.take(100)}"
                is AgentStep.Error -> "Error: ${step.message.take(100)}"
                is AgentStep.Delegate -> "Delegate: -> ${step.targetAgent}"
            }
        }.joinToString(" | ")
    }

    private fun calculateScore(outcome: AgentStep.Answer, trajectory: List<AgentStep>): Double {
        var score = 0.5 // base

        // Artifact varsa puan artır
        if (outcome.artifacts.isNotEmpty()) score += 0.2

        // Hata yoksa puan artır
        val errorCount = trajectory.count { it is AgentStep.Error }
        score -= errorCount * 0.1

        // Çok fazla adım varsa puan düşür (verimlilik)
        if (trajectory.size > 8) score -= 0.1

        return score.coerceIn(0.0, 1.0)
    }
}
