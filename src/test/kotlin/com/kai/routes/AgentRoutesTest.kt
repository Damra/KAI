package com.kai.routes

import com.kai.core.*
import com.kai.llm.LLMClient
import com.kai.memory.*
import com.kai.models.*
import com.kai.tools.ToolDefinition
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class AgentRoutesTest {

    private class TestLLM : LLMClient {
        override suspend fun reason(
            system: String,
            trajectory: List<AgentStep>,
            availableTools: List<ToolDefinition>
        ) = AgentStep.Answer(
            "Test cevabi",
            listOf(CodeArtifact("Test.kt", "kotlin", "fun test() = true"))
        )

        override suspend fun chat(system: String, user: String): String {
            return """
                {
                  "steps": [{
                    "id": "s1",
                    "description": "Test adimi",
                    "assignedAgent": "CODE_WRITER",
                    "dependsOn": [],
                    "requiresVerification": false,
                    "constraints": []
                  }]
                }
            """.trimIndent()
        }
    }

    private class TestMemory : MemoryLayer {
        override suspend fun recallSimilar(query: String, limit: Int) = emptyList<Episode>()
        override suspend fun queryGraph(entities: List<String>) = GraphContext(emptyList(), entities)
        override suspend fun storeEpisode(task: AgentTask, trajectory: List<AgentStep>, outcome: AgentStep.Answer) {}
        override suspend fun updateGraph(facts: List<Fact>) {}
        override suspend fun getSessionContext(sessionId: String) = ""
        override suspend fun updateSessionContext(sessionId: String, context: String) {}
    }

    private fun createTestController(): MetaController {
        val llm = TestLLM()
        val memory = TestMemory()
        val agents = AgentRole.entries.associateWith { role ->
            ReActAgent(role, llm, emptyMap(), memory)
        }
        val gate = VerificationGate(llm)
        return MetaController(agents, llm, memory, gate)
    }

    @Test
    fun `health endpoint returns ok`() = testApplication {
        val controller = createTestController()

        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            routing { agentRoutes(controller) }
        }

        val response = client.get("/api/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "ok")
        assertContains(body, "KAI")
    }

    @Test
    fun `agent endpoint processes request`() = testApplication {
        val controller = createTestController()

        application {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
            }
            routing { agentRoutes(controller) }
        }

        val response = client.post("/api/v1/agent") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": "Bir fonksiyon yaz"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.isNotBlank())
    }

    @Test
    fun `agent endpoint rejects empty message`() = testApplication {
        val controller = createTestController()

        application {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
            }
            routing { agentRoutes(controller) }
        }

        val response = client.post("/api/v1/agent") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": ""}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `agent endpoint rejects invalid JSON`() = testApplication {
        val controller = createTestController()

        application {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
            }
            routing { agentRoutes(controller) }
        }

        val response = client.post("/api/v1/agent") {
            contentType(ContentType.Application.Json)
            setBody("bu json degil")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `health endpoint returns version`() = testApplication {
        val controller = createTestController()

        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            routing { agentRoutes(controller) }
        }

        val response = client.get("/api/v1/health")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals("0.1.0", json["version"]?.jsonPrimitive?.content)
    }
}
