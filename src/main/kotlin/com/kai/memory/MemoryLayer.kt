package com.kai.memory

import com.kai.core.AgentStep
import com.kai.core.AgentTask

/**
 * Dual Memory System interface.
 * Episodic (vektör benzerliği) + Semantic (graf) hafıza.
 */
interface MemoryLayer {
    /** Vektör benzerliği ile geçmiş episodları bul */
    suspend fun recallSimilar(query: String, limit: Int = 5): List<Episode>

    /** Graf üzerinde entity/relation sorgusu */
    suspend fun queryGraph(entities: List<String>): GraphContext

    /** Yeni bir çalışma episodunu kaydet */
    suspend fun storeEpisode(task: AgentTask, trajectory: List<AgentStep>, outcome: AgentStep.Answer)

    /** Semantik grafa bilgi ekle */
    suspend fun updateGraph(facts: List<Fact>)

    /** Session bazlı kısa süreli hafıza */
    suspend fun getSessionContext(sessionId: String): String
    suspend fun updateSessionContext(sessionId: String, context: String)
}

data class Episode(
    val taskDescription: String,
    val trajectorySummary: String,
    val outcomeScore: Double,
    val artifacts: List<com.kai.models.CodeArtifact>,
    val createdAt: Long = System.currentTimeMillis()
)

data class Fact(
    val subject: String,
    val relation: String,
    val objectEntity: String
)

data class GraphContext(
    val facts: List<Fact>,
    val entities: List<String>
) {
    fun toPromptString(): String {
        if (facts.isEmpty()) return ""
        return facts.joinToString("\n") { "${it.subject} --[${it.relation}]--> ${it.objectEntity}" }
    }
}
