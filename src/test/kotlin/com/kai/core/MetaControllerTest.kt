package com.kai.core

import com.kai.llm.LLMClient
import com.kai.memory.Episode
import com.kai.memory.Fact
import com.kai.memory.GraphContext
import com.kai.memory.MemoryLayer
import com.kai.models.CodeArtifact
import com.kai.models.StepStatus
import com.kai.tools.ToolDefinition
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MetaControllerTest {

    // ── Mock LLM that returns a plan, then answers for each agent ──
    private class PlanningLLM(
        private val planJson: String,
        private val agentAnswer: String = "Agent cevabi"
    ) : LLMClient {
        var chatCallCount = 0
        var reasonCallCount = 0

        override suspend fun reason(
            system: String,
            trajectory: List<AgentStep>,
            availableTools: List<ToolDefinition>
        ): AgentStep {
            reasonCallCount++
            return AgentStep.Answer(
                agentAnswer,
                listOf(CodeArtifact("Output.kt", "kotlin", "fun output() = 42"))
            )
        }

        override suspend fun chat(system: String, user: String): String {
            chatCallCount++
            return planJson
        }
    }

    private class NoOpMemory : MemoryLayer {
        override suspend fun recallSimilar(query: String, limit: Int) = emptyList<Episode>()
        override suspend fun queryGraph(entities: List<String>) = GraphContext(emptyList(), entities)
        override suspend fun storeEpisode(task: AgentTask, trajectory: List<AgentStep>, outcome: AgentStep.Answer) {}
        override suspend fun updateGraph(facts: List<Fact>) {}
        override suspend fun getSessionContext(sessionId: String) = ""
        override suspend fun updateSessionContext(sessionId: String, context: String) {}
    }

    private fun createAgent(role: AgentRole, llm: LLMClient, memory: MemoryLayer): ReActAgent {
        return ReActAgent(role, llm, emptyMap(), memory)
    }

    // ── Basit pass-through verification gate ──
    private class PassingVerificationGate : VerificationGate(
        llmClient = object : LLMClient {
            override suspend fun reason(system: String, trajectory: List<AgentStep>, availableTools: List<ToolDefinition>) =
                AgentStep.Answer("ok")
            override suspend fun chat(system: String, user: String) =
                """{"score": 0.9, "issues": [], "suggestions": []}"""
        }
    )

    @Test
    fun `single step plan produces response`() = runTest {
        val planJson = """
            {
              "steps": [{
                "id": "s1",
                "description": "Kod yaz",
                "assignedAgent": "CODE_WRITER",
                "dependsOn": [],
                "requiresVerification": false,
                "constraints": ["Kotlin"]
              }]
            }
        """.trimIndent()

        val llm = PlanningLLM(planJson)
        val memory = NoOpMemory()

        val agents = mapOf(
            AgentRole.CODE_WRITER to createAgent(AgentRole.CODE_WRITER, llm, memory),
            AgentRole.PLANNER to createAgent(AgentRole.PLANNER, llm, memory),
            AgentRole.REVIEWER to createAgent(AgentRole.REVIEWER, llm, memory),
            AgentRole.FIXER to createAgent(AgentRole.FIXER, llm, memory),
            AgentRole.TESTER to createAgent(AgentRole.TESTER, llm, memory),
            AgentRole.RESEARCHER to createAgent(AgentRole.RESEARCHER, llm, memory)
        )

        val controller = MetaController(agents, llm, memory, PassingVerificationGate())

        val response = controller.process("Bir fonksiyon yaz", "session-1")

        assertTrue(response.answer.isNotBlank())
        assertEquals(1, response.planSteps.size)
        assertEquals(StepStatus.COMPLETED, response.planSteps[0].status)
    }

    @Test
    fun `multi-step plan executes in order`() = runTest {
        val planJson = """
            {
              "steps": [
                {
                  "id": "s1",
                  "description": "Arastir",
                  "assignedAgent": "RESEARCHER",
                  "dependsOn": [],
                  "requiresVerification": false,
                  "constraints": []
                },
                {
                  "id": "s2",
                  "description": "Kod yaz",
                  "assignedAgent": "CODE_WRITER",
                  "dependsOn": ["s1"],
                  "requiresVerification": false,
                  "constraints": ["Kotlin"]
                }
              ]
            }
        """.trimIndent()

        val llm = PlanningLLM(planJson)
        val memory = NoOpMemory()

        val agents = AgentRole.entries.associateWith { createAgent(it, llm, memory) }
        val controller = MetaController(agents, llm, memory, PassingVerificationGate())

        val response = controller.process("Client yaz", "session-2")

        assertEquals(2, response.planSteps.size)
        assertTrue(response.planSteps.all { it.status == StepStatus.COMPLETED })
    }

    @Test
    fun `fallback plan when LLM returns invalid JSON`() = runTest {
        val llm = PlanningLLM("bu gecerli JSON degil!!!")
        val memory = NoOpMemory()
        val agents = AgentRole.entries.associateWith { createAgent(it, llm, memory) }
        val controller = MetaController(agents, llm, memory, PassingVerificationGate())

        // Fallback plan: tek adimlik CODE_WRITER plani
        val response = controller.process("Bir seyler yap", "session-3")

        assertTrue(response.answer.isNotBlank())
        assertTrue(response.planSteps.isNotEmpty())
    }

    @Test
    fun `response contains artifacts from all steps`() = runTest {
        val planJson = """
            {
              "steps": [
                {
                  "id": "s1",
                  "description": "Kod yaz",
                  "assignedAgent": "CODE_WRITER",
                  "dependsOn": [],
                  "requiresVerification": false,
                  "constraints": []
                },
                {
                  "id": "s2",
                  "description": "Test yaz",
                  "assignedAgent": "TESTER",
                  "dependsOn": [],
                  "requiresVerification": false,
                  "constraints": []
                }
              ]
            }
        """.trimIndent()

        val llm = PlanningLLM(planJson)
        val memory = NoOpMemory()
        val agents = AgentRole.entries.associateWith { createAgent(it, llm, memory) }
        val controller = MetaController(agents, llm, memory, PassingVerificationGate())

        val response = controller.process("Kod ve test yaz", "session-4")

        // Her step 1 artifact uretir, toplam 2
        assertEquals(2, response.artifacts.size)
    }

    @Test
    fun `streaming callback receives plan updates`() = runTest {
        val planJson = """
            {
              "steps": [{
                "id": "s1",
                "description": "Yap",
                "assignedAgent": "CODE_WRITER",
                "dependsOn": [],
                "requiresVerification": false,
                "constraints": []
              }]
            }
        """.trimIndent()

        val events = mutableListOf<com.kai.models.StreamEvent>()
        val llm = PlanningLLM(planJson)
        val memory = NoOpMemory()
        val agents = AgentRole.entries.associateWith { createAgent(it, llm, memory) }
        val controller = MetaController(agents, llm, memory, PassingVerificationGate())

        controller.process("Stream test", "session-5") { event ->
            events.add(event)
        }

        // Plan update event'leri olmali (PENDING, RUNNING, COMPLETED)
        assertTrue(events.any { it is com.kai.models.StreamEvent.PlanUpdate })
    }

    @Test
    fun `response metadata aggregates all agents`() = runTest {
        val planJson = """
            {
              "steps": [{
                "id": "s1",
                "description": "Yap",
                "assignedAgent": "CODE_WRITER",
                "dependsOn": [],
                "requiresVerification": false,
                "constraints": []
              }]
            }
        """.trimIndent()

        val llm = PlanningLLM(planJson)
        val memory = NoOpMemory()
        val agents = AgentRole.entries.associateWith { createAgent(it, llm, memory) }
        val controller = MetaController(agents, llm, memory, PassingVerificationGate())

        val response = controller.process("Meta test", "session-6")

        assertNotNull(response.metadata)
        assertTrue(response.metadata!!.agentsInvolved.isNotEmpty())
    }
}
