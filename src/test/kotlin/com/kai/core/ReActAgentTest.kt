package com.kai.core

import com.kai.llm.LLMClient
import com.kai.memory.Episode
import com.kai.memory.GraphContext
import com.kai.memory.MemoryLayer
import com.kai.memory.Fact
import com.kai.models.CodeArtifact
import com.kai.tools.Tool
import com.kai.tools.ToolDefinition
import com.kai.tools.ToolParameter
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReActAgentTest {

    // ── Mock LLM: sirasiyla adimlar dondurur ──
    private class SequentialLLM(private val steps: List<AgentStep>) : LLMClient {
        var callCount = 0
            private set

        override suspend fun reason(
            system: String,
            trajectory: List<AgentStep>,
            availableTools: List<ToolDefinition>
        ): AgentStep {
            return steps.getOrElse(callCount++) {
                AgentStep.Answer("Fallback cevap")
            }
        }

        override suspend fun chat(system: String, user: String): String = "mock response"
    }

    // ── Mock Tool ──
    private class MockTool(
        override val name: String = "mock_tool",
        private val result: ToolResult = ToolResult.Success("mock output")
    ) : Tool {
        var executionCount = 0
            private set
        var lastInput: Map<String, String>? = null
            private set

        override val description = "Mock tool for testing"
        override val parameters = listOf(ToolParameter("input", "string", "Test input"))

        override suspend fun execute(input: Map<String, String>): ToolResult {
            executionCount++
            lastInput = input
            return result
        }
    }

    // ── Mock Memory ──
    private class MockMemory : MemoryLayer {
        val storedEpisodes = mutableListOf<Triple<AgentTask, List<AgentStep>, AgentStep.Answer>>()

        override suspend fun recallSimilar(query: String, limit: Int) = emptyList<Episode>()
        override suspend fun queryGraph(entities: List<String>) = GraphContext(emptyList(), entities)
        override suspend fun storeEpisode(task: AgentTask, trajectory: List<AgentStep>, outcome: AgentStep.Answer) {
            storedEpisodes.add(Triple(task, trajectory, outcome))
        }
        override suspend fun updateGraph(facts: List<Fact>) {}
        override suspend fun getSessionContext(sessionId: String) = ""
        override suspend fun updateSessionContext(sessionId: String, context: String) {}
    }

    @Test
    fun `agent returns answer on first step`() = runTest {
        val llm = SequentialLLM(listOf(
            AgentStep.Answer("Gorev tamamlandi", listOf(
                CodeArtifact("Main.kt", "kotlin", "fun main() {}")
            ))
        ))
        val memory = MockMemory()
        val agent = ReActAgent(AgentRole.CODE_WRITER, llm, emptyMap(), memory)

        val result = agent.execute(AgentTask("Basit bir fonksiyon yaz"))

        assertEquals("Gorev tamamlandi", result.content)
        assertEquals(1, result.artifacts.size)
        assertEquals(1, llm.callCount)
        assertEquals(1, memory.storedEpisodes.size)
    }

    @Test
    fun `agent executes think then answer`() = runTest {
        val llm = SequentialLLM(listOf(
            AgentStep.Think("Once dusunmem lazim", confidence = 0.6),
            AgentStep.Answer("Dusundukten sonra cevap")
        ))
        val agent = ReActAgent(AgentRole.PLANNER, llm, emptyMap(), MockMemory())

        val result = agent.execute(AgentTask("Plan yap"))

        assertEquals("Dusundukten sonra cevap", result.content)
        assertEquals(2, llm.callCount)
    }

    @Test
    fun `agent calls tool and observes result`() = runTest {
        val mockTool = MockTool(result = ToolResult.Success("dosya yazildi"))
        val llm = SequentialLLM(listOf(
            AgentStep.Act("mock_tool", mapOf("input" to "test data"), "Dosya yazmam lazim"),
            AgentStep.Answer("Tool kullanarak tamamladim")
        ))
        val agent = ReActAgent(
            AgentRole.CODE_WRITER, llm,
            mapOf("mock_tool" to mockTool),
            MockMemory()
        )

        val result = agent.execute(AgentTask("Dosya yaz"))

        assertEquals("Tool kullanarak tamamladim", result.content)
        assertEquals(1, mockTool.executionCount)
        assertEquals("test data", mockTool.lastInput?.get("input"))
    }

    @Test
    fun `agent handles unknown tool gracefully`() = runTest {
        val llm = SequentialLLM(listOf(
            AgentStep.Act("nonexistent_tool", emptyMap(), "Bu tool yok"),
            AgentStep.Answer("Tool bulunamadi ama devam ettim")
        ))
        val agent = ReActAgent(AgentRole.CODE_WRITER, llm, emptyMap(), MockMemory())

        val result = agent.execute(AgentTask("Tool dene"))

        assertEquals("Tool bulunamadi ama devam ettim", result.content)
    }

    @Test
    fun `agent handles tool failure and continues`() = runTest {
        val failingTool = MockTool(result = ToolResult.Failure("disk full", retryable = true))
        val llm = SequentialLLM(listOf(
            AgentStep.Act("mock_tool", emptyMap(), "Dosya yazayim"),
            AgentStep.Answer("Tool basarisiz ama cevap verdim")
        ))
        val agent = ReActAgent(
            AgentRole.CODE_WRITER, llm,
            mapOf("mock_tool" to failingTool),
            MockMemory()
        )

        val result = agent.execute(AgentTask("Dosya yaz"))

        assertEquals("Tool basarisiz ama cevap verdim", result.content)
        assertEquals(1, failingTool.executionCount)
    }

    @Test
    fun `agent throws on non-recoverable error`() = runTest {
        val llm = SequentialLLM(listOf(
            AgentStep.Error("Fatal hata", recoverable = false)
        ))
        val agent = ReActAgent(AgentRole.CODE_WRITER, llm, emptyMap(), MockMemory())

        assertFailsWith<AgentException> {
            agent.execute(AgentTask("Hata olacak"))
        }
    }

    @Test
    fun `agent retries recoverable errors up to max`() = runTest {
        val llm = SequentialLLM(listOf(
            AgentStep.Error("Gecici hata 1", recoverable = true),
            AgentStep.Error("Gecici hata 2", recoverable = true),
            AgentStep.Error("Gecici hata 3", recoverable = true),
            // maxRetries=3 olunca 3. denemede patlayacak
        ))
        val agent = ReActAgent(
            AgentRole.CODE_WRITER, llm, emptyMap(), MockMemory(),
            config = ReActAgent.AgentConfig(maxRetries = 3)
        )

        assertFailsWith<AgentException> {
            agent.execute(AgentTask("Hep hata"))
        }
    }

    @Test
    fun `agent throws DelegationException on delegate step`() = runTest {
        val llm = SequentialLLM(listOf(
            AgentStep.Delegate(
                AgentRole.FIXER,
                DelegationContext("Hata var", "Duzelt")
            )
        ))
        val agent = ReActAgent(AgentRole.REVIEWER, llm, emptyMap(), MockMemory())

        val exception = assertFailsWith<DelegationException> {
            agent.execute(AgentTask("Review yap"))
        }

        assertEquals(AgentRole.FIXER, exception.delegation.targetAgent)
    }

    @Test
    fun `agent throws MaxIterationsExceededException`() = runTest {
        // Sadece Think dondurup donguyu bitirmiyor
        val infiniteThink = object : LLMClient {
            override suspend fun reason(
                system: String,
                trajectory: List<AgentStep>,
                availableTools: List<ToolDefinition>
            ) = AgentStep.Think("Hala dusunuyorum...", confidence = 0.1)

            override suspend fun chat(system: String, user: String) = ""
        }

        val agent = ReActAgent(
            AgentRole.PLANNER, infiniteThink, emptyMap(), MockMemory(),
            config = ReActAgent.AgentConfig(maxIterations = 3)
        )

        assertFailsWith<MaxIterationsExceededException> {
            agent.execute(AgentTask("Sonsuz dongu"))
        }
    }

    @Test
    fun `answer includes metadata with tool info`() = runTest {
        val mockTool = MockTool()
        val llm = SequentialLLM(listOf(
            AgentStep.Act("mock_tool", emptyMap(), "Kullan"),
            AgentStep.Answer("Tamam")
        ))
        val agent = ReActAgent(
            AgentRole.CODE_WRITER, llm,
            mapOf("mock_tool" to mockTool),
            MockMemory()
        )

        val result = agent.execute(AgentTask("Yap"))

        assertNotNull(result.metadata)
        assertContains(result.metadata!!.toolsUsed, "mock_tool")
        assertEquals(listOf(AgentRole.CODE_WRITER), result.metadata!!.agentsInvolved)
    }

    @Test
    fun `streaming callback receives events`() = runTest {
        val events = mutableListOf<com.kai.models.StreamEvent>()
        val llm = SequentialLLM(listOf(
            AgentStep.Think("Dusunuyorum"),
            AgentStep.Answer("Bitti")
        ))
        val agent = ReActAgent(AgentRole.CODE_WRITER, llm, emptyMap(), MockMemory())

        agent.execute(AgentTask("Stream test")) { event ->
            events.add(event)
        }

        assertTrue(events.any { it is com.kai.models.StreamEvent.Thinking })
        assertTrue(events.any { it is com.kai.models.StreamEvent.Done })
    }

    @Test
    fun `agent stores episode in memory after completion`() = runTest {
        val memory = MockMemory()
        val llm = SequentialLLM(listOf(
            AgentStep.Answer("Hafizaya kaydet beni")
        ))
        val agent = ReActAgent(AgentRole.CODE_WRITER, llm, emptyMap(), memory)

        agent.execute(AgentTask("Hafiza testi"))

        assertEquals(1, memory.storedEpisodes.size)
        assertEquals("Hafiza testi", memory.storedEpisodes[0].first.description)
    }
}
