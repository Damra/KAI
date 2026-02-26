package com.kai.core

import com.kai.llm.LLMClient
import com.kai.models.CodeArtifact
import com.kai.models.PlanStep
import com.kai.tools.Tool
import com.kai.tools.ToolDefinition
import com.kai.tools.ToolParameter
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VerificationGateTest {

    private class JudgeLLM(private val response: String) : LLMClient {
        override suspend fun reason(
            system: String,
            trajectory: List<AgentStep>,
            availableTools: List<ToolDefinition>
        ) = AgentStep.Answer("ok")

        override suspend fun chat(system: String, user: String) = response
    }

    private class MockCompiler(private val result: ToolResult) : Tool {
        override val name = "kotlin_compile"
        override val description = "Mock compiler"
        override val parameters = listOf(ToolParameter("code", "string", "code"))
        override suspend fun execute(input: Map<String, String>) = result
    }

    private val goodStep = PlanStep(
        id = "s1",
        description = "REST client yaz",
        assignedAgent = AgentRole.CODE_WRITER,
        constraints = listOf("Kotlin", "KTOR")
    )

    private val goodAnswer = AgentStep.Answer(
        content = "Client yazildi",
        artifacts = listOf(
            CodeArtifact("GitHubClient.kt", "kotlin", """
                class GitHubClient(private val httpClient: HttpClient) {
                    suspend fun getRepos(): List<Repo> {
                        return httpClient.get("/repos").body()
                    }
                }
            """.trimIndent())
        )
    )

    @Test
    fun `high score passes verification`() = runTest {
        val llm = JudgeLLM("""{"score": 0.92, "issues": [], "suggestions": ["Rate limiter ekle"]}""")
        val gate = VerificationGate(llm)

        val result = gate.verify(goodStep, goodAnswer)

        assertTrue(result.passed)
        assertTrue(result.score >= 0.7)
        assertTrue(result.suggestions.contains("Rate limiter ekle"))
    }

    @Test
    fun `low score fails verification`() = runTest {
        val llm = JudgeLLM("""{"score": 0.3, "issues": ["Hata yonetimi yok", "Null safety eksik"], "suggestions": []}""")
        val gate = VerificationGate(llm)

        val result = gate.verify(goodStep, goodAnswer)

        assertFalse(result.passed)
        assertTrue(result.score < 0.7)
        assertTrue(result.issues.isNotEmpty())
    }

    @Test
    fun `compile failure adds critical issue`() = runTest {
        val llm = JudgeLLM("""{"score": 0.8, "issues": [], "suggestions": []}""")
        val compiler = MockCompiler(ToolResult.Failure("Unresolved reference: HttpClient", retryable = true))
        val gate = VerificationGate(llm, compilerTool = compiler)

        val result = gate.verify(goodStep, goodAnswer)

        assertFalse(result.passed)
        assertTrue(result.issues.any { it.severity == VerificationGate.Severity.CRITICAL })
        assertTrue(result.issues.any { it.description.contains("Derleme") })
    }

    @Test
    fun `compile success with high judge score passes`() = runTest {
        val llm = JudgeLLM("""{"score": 0.85, "issues": [], "suggestions": []}""")
        val compiler = MockCompiler(ToolResult.Success("Derleme basarili"))
        val gate = VerificationGate(llm, compilerTool = compiler)

        val result = gate.verify(goodStep, goodAnswer)

        assertTrue(result.passed)
        assertEquals(0.85, result.score)
    }

    @Test
    fun `non-kotlin artifacts skip compile check`() = runTest {
        val answer = AgentStep.Answer(
            content = "JSON yazildi",
            artifacts = listOf(CodeArtifact("config.json", "json", """{"key": "value"}"""))
        )

        val llm = JudgeLLM("""{"score": 0.9, "issues": [], "suggestions": []}""")
        val compiler = MockCompiler(ToolResult.Failure("Should not be called", retryable = false))
        val gate = VerificationGate(llm, compilerTool = compiler)

        val result = gate.verify(goodStep, answer)

        // JSON artifact icin compiler cagrilmamali
        assertTrue(result.passed)
    }

    @Test
    fun `empty artifacts still get judge review`() = runTest {
        val answer = AgentStep.Answer(content = "Sadece aciklama", artifacts = emptyList())
        val llm = JudgeLLM("""{"score": 0.75, "issues": [], "suggestions": []}""")
        val gate = VerificationGate(llm)

        val result = gate.verify(goodStep, answer)

        assertTrue(result.passed)
    }

    @Test
    fun `judge JSON wrapped in code block is parsed`() = runTest {
        val llm = JudgeLLM("""
            Kod analizi:
            ```json
            {"score": 0.88, "issues": ["Minor: naming convention"], "suggestions": ["camelCase kullan"]}
            ```
        """.trimIndent())
        val gate = VerificationGate(llm)

        val result = gate.verify(goodStep, goodAnswer)

        assertTrue(result.passed)
        assertEquals(0.88, result.score)
    }

    @Test
    fun `judge returns invalid JSON gracefully`() = runTest {
        val llm = JudgeLLM("Bu JSON degil, sadece yorum.")
        val gate = VerificationGate(llm)

        // Hata olmamali, default score ile devam etmeli
        val result = gate.verify(goodStep, goodAnswer)
        // Passes with default score since no critical issues
        assertNotNull(result)
    }

    @Test
    fun `multiple artifacts all get compile checked`() = runTest {
        val answer = AgentStep.Answer(
            content = "Iki dosya yazildi",
            artifacts = listOf(
                CodeArtifact("A.kt", "kotlin", "class A"),
                CodeArtifact("B.kt", "kotlin", "class B")
            )
        )

        var compileCount = 0
        val compiler = object : Tool {
            override val name = "kotlin_compile"
            override val description = "counter"
            override val parameters = emptyList<ToolParameter>()
            override suspend fun execute(input: Map<String, String>): ToolResult {
                compileCount++
                return ToolResult.Success("ok")
            }
        }

        val llm = JudgeLLM("""{"score": 0.9, "issues": [], "suggestions": []}""")
        val gate = VerificationGate(llm, compilerTool = compiler)

        gate.verify(goodStep, answer)

        assertEquals(2, compileCount)
    }
}
