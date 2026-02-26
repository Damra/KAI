package com.kai.core

import com.kai.models.CodeArtifact
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AgentStepTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `Think step stores thought and confidence`() {
        val step = AgentStep.Think("Bu gorevi analiz ediyorum", confidence = 0.85)
        assertEquals("Bu gorevi analiz ediyorum", step.thought)
        assertEquals(0.85, step.confidence)
    }

    @Test
    fun `Think step default confidence is zero`() {
        val step = AgentStep.Think("dusunce")
        assertEquals(0.0, step.confidence)
    }

    @Test
    fun `Act step stores tool info and reasoning`() {
        val step = AgentStep.Act(
            toolName = "kotlin_compile",
            toolInput = mapOf("code" to "fun main() {}", "filename" to "Main.kt"),
            reasoning = "Kodu derlemem gerekiyor"
        )
        assertEquals("kotlin_compile", step.toolName)
        assertEquals("fun main() {}", step.toolInput["code"])
        assertEquals("Kodu derlemem gerekiyor", step.reasoning)
    }

    @Test
    fun `Observe step with Success result`() {
        val result = ToolResult.Success("Derleme basarili", data = "bytecode")
        val step = AgentStep.Observe("kotlin_compile", result, durationMs = 1500)

        assertEquals("kotlin_compile", step.toolName)
        assertTrue(step.result.isSuccess)
        assertEquals(1500, step.durationMs)
        assertIs<ToolResult.Success>(step.result)
        assertEquals("Derleme basarili", (step.result as ToolResult.Success).output)
    }

    @Test
    fun `Observe step with Failure result`() {
        val result = ToolResult.Failure("Syntax error at line 5", retryable = true)
        val step = AgentStep.Observe("kotlin_compile", result, durationMs = 200)

        assertFalse(step.result.isSuccess)
        assertIs<ToolResult.Failure>(step.result)
        assertTrue((step.result as ToolResult.Failure).retryable)
    }

    @Test
    fun `Answer step with artifacts`() {
        val artifacts = listOf(
            CodeArtifact("Main.kt", "kotlin", "fun main() = println(\"hello\")", version = 1),
            CodeArtifact("MainTest.kt", "kotlin", "@Test fun test() {}", version = 1)
        )
        val step = AgentStep.Answer(
            content = "Kod yazildi",
            artifacts = artifacts,
            metadata = AnswerMetadata(
                totalSteps = 5,
                toolsUsed = listOf("kotlin_compile", "file_system"),
                totalDurationMs = 3000,
                agentsInvolved = listOf(AgentRole.CODE_WRITER)
            )
        )

        assertEquals(2, step.artifacts.size)
        assertEquals("Main.kt", step.artifacts[0].filename)
        assertEquals(5, step.metadata?.totalSteps)
    }

    @Test
    fun `Answer step with empty artifacts`() {
        val step = AgentStep.Answer(content = "Basit cevap")
        assertTrue(step.artifacts.isEmpty())
        assertNull(step.metadata)
    }

    @Test
    fun `Error step recoverable flag`() {
        val recoverable = AgentStep.Error("Timeout", recoverable = true, suggestedAction = "Tekrar dene")
        val fatal = AgentStep.Error("API key gecersiz", recoverable = false)

        assertTrue(recoverable.recoverable)
        assertEquals("Tekrar dene", recoverable.suggestedAction)
        assertFalse(fatal.recoverable)
        assertNull(fatal.suggestedAction)
    }

    @Test
    fun `Delegate step targets correct agent`() {
        val step = AgentStep.Delegate(
            targetAgent = AgentRole.FIXER,
            context = DelegationContext(
                reason = "Derleme hatasi var",
                taskDescription = "Hatayi duzelt",
                constraints = listOf("Kotlin", "minimal degisiklik")
            )
        )

        assertEquals(AgentRole.FIXER, step.targetAgent)
        assertEquals("Derleme hatasi var", step.context.reason)
        assertEquals(2, step.context.constraints.size)
    }

    @Test
    fun `AgentStep sealed class exhaustive when`() {
        val steps: List<AgentStep> = listOf(
            AgentStep.Think("test"),
            AgentStep.Act("tool", emptyMap(), "reason"),
            AgentStep.Observe("tool", ToolResult.Success("ok"), 0),
            AgentStep.Answer("done"),
            AgentStep.Error("err", true),
            AgentStep.Delegate(AgentRole.FIXER, DelegationContext("r", "t"))
        )

        // Hepsi farklı tür olmalı
        val types = steps.map { it::class }.toSet()
        assertEquals(6, types.size)
    }

    @Test
    fun `ToolResult Success serialization roundtrip`() {
        val original = ToolResult.Success("output data", "extra")
        val serialized = json.encodeToString<ToolResult>(original)
        val deserialized = json.decodeFromString<ToolResult>(serialized)

        assertIs<ToolResult.Success>(deserialized)
        assertEquals("output data", deserialized.output)
    }

    @Test
    fun `ToolResult Failure serialization roundtrip`() {
        val original = ToolResult.Failure("broken", retryable = false)
        val serialized = json.encodeToString<ToolResult>(original)
        val deserialized = json.decodeFromString<ToolResult>(serialized)

        assertIs<ToolResult.Failure>(deserialized)
        assertEquals("broken", deserialized.error)
        assertFalse(deserialized.retryable)
    }
}
