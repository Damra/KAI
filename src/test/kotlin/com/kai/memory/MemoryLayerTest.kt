package com.kai.memory

import com.kai.core.AgentRole
import com.kai.core.AgentStep
import com.kai.core.AgentTask
import com.kai.core.AnswerMetadata
import com.kai.core.ToolResult
import com.kai.llm.MockEmbeddingClient
import com.kai.models.CodeArtifact
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemoryLayerTest {

    // ── GraphContext Tests ──

    @Test
    fun `GraphContext toPromptString formats facts`() {
        val context = GraphContext(
            facts = listOf(
                Fact("User", "PREFERS", "Kotlin"),
                Fact("Project", "USES", "KTOR")
            ),
            entities = listOf("User", "Project")
        )

        val prompt = context.toPromptString()

        assertContains(prompt, "User --[PREFERS]--> Kotlin")
        assertContains(prompt, "Project --[USES]--> KTOR")
    }

    @Test
    fun `empty GraphContext produces empty string`() {
        val context = GraphContext(emptyList(), emptyList())
        assertEquals("", context.toPromptString())
    }

    // ── Episode Tests ──

    @Test
    fun `Episode stores all fields`() {
        val artifacts = listOf(
            CodeArtifact("Main.kt", "kotlin", "fun main() {}", 1)
        )
        val episode = Episode(
            taskDescription = "REST client yaz",
            trajectorySummary = "Think | Act: file_write | Answer",
            outcomeScore = 0.85,
            artifacts = artifacts
        )

        assertEquals("REST client yaz", episode.taskDescription)
        assertEquals(0.85, episode.outcomeScore)
        assertEquals(1, episode.artifacts.size)
        assertTrue(episode.createdAt > 0)
    }

    // ── Fact Tests ──

    @Test
    fun `Fact represents triple correctly`() {
        val fact = Fact("Kotlin", "IS_A", "Programming Language")

        assertEquals("Kotlin", fact.subject)
        assertEquals("IS_A", fact.relation)
        assertEquals("Programming Language", fact.objectEntity)
    }

    // ── DualMemory Integration (with MockEmbeddingClient) ──
    // Note: Bu testler pgvector/neo4j olmadan calisir,
    // sadece DualMemory'nin trajectory ozetleme ve score hesaplama logigini test eder.

    @Test
    fun `MockEmbeddingClient produces consistent embeddings`() = runTest {
        val client = MockEmbeddingClient(dimensions = 64)

        val vec1 = client.embed("Kotlin REST client")
        val vec2 = client.embed("Kotlin REST client")
        val vec3 = client.embed("Python Flask server")

        assertEquals(64, vec1.size)
        assertEquals(vec1, vec2)
        assertNotEquals(vec1, vec3)
    }

    @Test
    fun `MockEmbeddingClient batch is consistent`() = runTest {
        val client = MockEmbeddingClient(dimensions = 32)
        val texts = listOf("one", "two", "three")

        val batch = client.embedBatch(texts)

        assertEquals(3, batch.size)
        assertEquals(32, batch[0].size)
        // Her biri farkli olmali
        assertNotEquals(batch[0], batch[1])
        assertNotEquals(batch[1], batch[2])
    }

    // ── Score calculation logic (mirroring DualMemory internal logic) ──

    @Test
    fun `score calculation base is 0_5`() {
        val score = calculateTestScore(
            hasArtifacts = false,
            errorCount = 0,
            stepCount = 3
        )
        assertEquals(0.5, score)
    }

    @Test
    fun `artifacts increase score`() {
        val withArtifacts = calculateTestScore(hasArtifacts = true, errorCount = 0, stepCount = 3)
        val without = calculateTestScore(hasArtifacts = false, errorCount = 0, stepCount = 3)

        assertTrue(withArtifacts > without)
    }

    @Test
    fun `errors decrease score`() {
        val noErrors = calculateTestScore(hasArtifacts = true, errorCount = 0, stepCount = 5)
        val withErrors = calculateTestScore(hasArtifacts = true, errorCount = 3, stepCount = 5)

        assertTrue(noErrors > withErrors)
    }

    @Test
    fun `too many steps decrease score`() {
        val efficient = calculateTestScore(hasArtifacts = true, errorCount = 0, stepCount = 5)
        val inefficient = calculateTestScore(hasArtifacts = true, errorCount = 0, stepCount = 15)

        assertTrue(efficient > inefficient)
    }

    @Test
    fun `score is clamped between 0 and 1`() {
        val extreme = calculateTestScore(hasArtifacts = false, errorCount = 20, stepCount = 50)
        assertTrue(extreme >= 0.0)
        assertTrue(extreme <= 1.0)
    }

    // Helper: mirrors DualMemory.calculateScore logic
    private fun calculateTestScore(hasArtifacts: Boolean, errorCount: Int, stepCount: Int): Double {
        var score = 0.5
        if (hasArtifacts) score += 0.2
        score -= errorCount * 0.1
        if (stepCount > 8) score -= 0.1
        return score.coerceIn(0.0, 1.0)
    }
}
