package com.kai.llm

import com.kai.core.AgentStep
import com.kai.core.ToolResult
import com.kai.tools.ToolDefinition
import com.kai.tools.InputSchema
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AnthropicClientParsingTest {

    private fun createMockClient(responseBody: String, statusCode: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = responseBody,
                        status = statusCode,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    // ── Text Response Parsing ──

    @Test
    fun `parses text answer response`() = runTest {
        val responseJson = """
            {
              "id": "msg_123",
              "type": "message",
              "role": "assistant",
              "content": [
                {
                  "type": "text",
                  "text": "Istek tamamlandi. Iste Kotlin kodu:\n```kotlin\nfun hello() = println(\"Merhaba\")\n```"
                }
              ],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 100, "output_tokens": 50}
            }
        """.trimIndent()

        val client = AnthropicClient(createMockClient(responseJson), "test-key")
        val step = client.reason("system", emptyList(), emptyList())

        assertIs<AgentStep.Answer>(step)
        assertContains(step.content, "Kotlin")
        assertTrue(step.artifacts.isNotEmpty(), "Code blocks should be extracted as artifacts")
    }

    // ── Tool Use Response Parsing ──

    @Test
    fun `parses tool use response`() = runTest {
        val responseJson = """
            {
              "id": "msg_456",
              "type": "message",
              "role": "assistant",
              "content": [
                {
                  "type": "text",
                  "text": "Dosyayi okumam gerekiyor."
                },
                {
                  "type": "tool_use",
                  "id": "toolu_123",
                  "name": "file_system",
                  "input": {"action": "read", "path": "src/Main.kt"}
                }
              ],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 80, "output_tokens": 30}
            }
        """.trimIndent()

        val client = AnthropicClient(createMockClient(responseJson), "test-key")
        val tools = listOf(
            ToolDefinition("file_system", "File ops", InputSchema(properties = emptyMap()))
        )
        val step = client.reason("system", emptyList(), tools)

        assertIs<AgentStep.Act>(step)
        assertEquals("file_system", step.toolName)
        assertEquals("read", step.toolInput["action"])
        assertEquals("src/Main.kt", step.toolInput["path"])
        assertContains(step.reasoning, "okumam")
    }

    // ── Short Text → Think ──

    @Test
    fun `short text response parsed as Think`() = runTest {
        val responseJson = """
            {
              "id": "msg_789",
              "type": "message",
              "role": "assistant",
              "content": [
                {
                  "type": "text",
                  "text": "Hmm, bunu dusunmem lazim."
                }
              ],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 50, "output_tokens": 10}
            }
        """.trimIndent()

        val client = AnthropicClient(createMockClient(responseJson), "test-key")
        val step = client.reason("system", emptyList(), emptyList())

        // Kisa text, artifact yok → Think olarak parse edilmeli
        assertIs<AgentStep.Think>(step)
        assertContains(step.thought, "dusunmem")
    }

    // ── Error Handling ──

    @Test
    fun `API error returns Error step`() = runTest {
        val errorJson = """{"error": {"type": "invalid_request_error", "message": "Invalid API key"}}"""

        val client = AnthropicClient(
            createMockClient(errorJson, HttpStatusCode.Unauthorized),
            "bad-key"
        )
        val step = client.reason("system", emptyList(), emptyList())

        assertIs<AgentStep.Error>(step)
        assertFalse(step.recoverable)
    }

    @Test
    fun `rate limit returns recoverable Error step`() = runTest {
        val errorJson = """{"error": {"type": "rate_limit_error", "message": "Too many requests"}}"""

        val client = AnthropicClient(
            createMockClient(errorJson, HttpStatusCode.TooManyRequests),
            "test-key"
        )
        val step = client.reason("system", emptyList(), emptyList())

        assertIs<AgentStep.Error>(step)
        assertTrue(step.recoverable)
    }

    @Test
    fun `server error returns recoverable Error step`() = runTest {
        val errorJson = """{"error": {"type": "server_error", "message": "Internal error"}}"""

        val client = AnthropicClient(
            createMockClient(errorJson, HttpStatusCode.InternalServerError),
            "test-key"
        )
        val step = client.reason("system", emptyList(), emptyList())

        assertIs<AgentStep.Error>(step)
        assertTrue(step.recoverable)
    }

    // ── Chat Method ──

    @Test
    fun `chat returns text content`() = runTest {
        val responseJson = """
            {
              "content": [{"type": "text", "text": "Merhaba!"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()

        val client = AnthropicClient(createMockClient(responseJson), "test-key")
        val result = client.chat("system prompt", "selam")

        assertEquals("Merhaba!", result)
    }

    // ── Code Artifact Extraction ──

    @Test
    fun `extracts multiple code artifacts from response`() = runTest {
        val responseJson = """
            {
              "content": [{
                "type": "text",
                "text": "Iste iki dosya:\n```kotlin\nclass A\n```\n\nVe test:\n```kotlin\n@Test fun test() {}\n```"
              }],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 50, "output_tokens": 30}
            }
        """.trimIndent()

        val client = AnthropicClient(createMockClient(responseJson), "test-key")
        val step = client.reason("system", emptyList(), emptyList())

        assertIs<AgentStep.Answer>(step)
        assertEquals(2, step.artifacts.size)
        assertEquals("kotlin", step.artifacts[0].language)
    }

    @Test
    fun `extracts java code artifact`() = runTest {
        val responseJson = """
            {
              "content": [{
                "type": "text",
                "text": "Java kodu:\n```java\npublic class Main { public static void main(String[] args) {} }\n```\nBitti."
              }],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 50, "output_tokens": 30}
            }
        """.trimIndent()

        val client = AnthropicClient(createMockClient(responseJson), "test-key")
        val step = client.reason("system", emptyList(), emptyList())

        assertIs<AgentStep.Answer>(step)
        assertEquals(1, step.artifacts.size)
        assertEquals("java", step.artifacts[0].language)
        assertTrue(step.artifacts[0].filename.endsWith(".java"))
    }

    // ── Embedding Client ──

    @Test
    fun `mock embedding client produces deterministic vectors`() = runTest {
        val client = MockEmbeddingClient(dimensions = 128)

        val vec1 = client.embed("test query")
        val vec2 = client.embed("test query")
        val vec3 = client.embed("different query")

        assertEquals(128, vec1.size)
        assertEquals(vec1, vec2, "Same input should produce same output")
        assertNotEquals(vec1, vec3, "Different input should produce different output")
    }

    @Test
    fun `mock embedding batch matches individual calls`() = runTest {
        val client = MockEmbeddingClient()
        val texts = listOf("hello", "world")

        val batch = client.embedBatch(texts)
        val individual = texts.map { runBlocking { client.embed(it) } }

        assertEquals(batch.size, individual.size)
        assertEquals(batch[0], individual[0])
        assertEquals(batch[1], individual[1])
    }
}

// runBlocking helper for non-suspend test context
private fun <T> runBlocking(block: suspend () -> T): T {
    return kotlinx.coroutines.runBlocking { block() }
}
