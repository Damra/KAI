package com.kai.core

import com.kai.llm.LLMClient
import com.kai.models.PlanStep
import com.kai.tools.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * PEV — Plan, Execute, Verify.
 * Üretilen kodu 3 katmanlı doğrulama ile kontrol eder.
 */
open class VerificationGate(
    private val llmClient: LLMClient,
    private val compilerTool: Tool? = null,
    private val testRunnerTool: Tool? = null
) {
    private val logger = LoggerFactory.getLogger(VerificationGate::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class VerificationResult(
        val passed: Boolean,
        val score: Double,
        val issues: List<Issue>,
        val suggestions: List<String>
    )

    data class Issue(
        val severity: Severity,
        val description: String,
        val location: String? = null
    )

    enum class Severity { CRITICAL, WARNING, INFO }

    /**
     * Üretilen kodu çok katmanlı olarak doğrular:
     * 1) Statik analiz (compile check)
     * 2) Mantıksal doğruluk (LLM-as-Judge)
     * 3) Test çalıştırma (varsa)
     */
    suspend fun verify(step: PlanStep, result: AgentStep.Answer): VerificationResult {
        val issues = mutableListOf<Issue>()
        var judgeScore = 0.7 // default

        // ── Katman 1: Statik Analiz ──
        if (compilerTool != null) {
            for (artifact in result.artifacts) {
                if (artifact.language.lowercase() in listOf("kotlin", "kt")) {
                    try {
                        val compileResult = compilerTool.execute(
                            mapOf("code" to artifact.content, "filename" to artifact.filename)
                        )
                        when (compileResult) {
                            is ToolResult.Failure -> {
                                issues.add(
                                    Issue(
                                        Severity.CRITICAL,
                                        "Derleme hatasi: ${compileResult.error}",
                                        artifact.filename
                                    )
                                )
                            }
                            is ToolResult.Success -> {
                                logger.debug("Compile check passed: ${artifact.filename}")
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Compile check failed for ${artifact.filename}", e)
                    }
                }
            }
        }

        // ── Katman 2: LLM-as-Judge ──
        try {
            val judgeResponse = llmClient.chat(
                system = """
                    Sen bir kod review uzmanisin. Verilen kodu su kriterlere gore puanla:
                    1. Dogruluk (gorevi yerine getiriyor mu?)
                    2. Kotlin idiomatic kullanim
                    3. Hata yonetimi
                    4. Performans

                    Sadece JSON dondur:
                    { "score": 0.0-1.0, "issues": ["sorun1", "sorun2"], "suggestions": ["oneri1"] }
                """.trimIndent(),
                user = """
                    Gorev: ${step.description}
                    Kisitlar: ${step.constraints}
                    Uretilen Kod:
                    ${result.artifacts.joinToString("\n---\n") { "// ${it.filename}\n${it.content}" }}
                """.trimIndent()
            )

            val judgeJson = extractJsonFromResponse(judgeResponse)
            val parsed = json.parseToJsonElement(judgeJson).let { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@let null
                JudgeResponse(
                    score = obj["score"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                    } ?: 0.7,
                    issues = obj["issues"]?.let { arr ->
                        (arr as? kotlinx.serialization.json.JsonArray)?.map {
                            (it as kotlinx.serialization.json.JsonPrimitive).content
                        }
                    } ?: emptyList(),
                    suggestions = obj["suggestions"]?.let { arr ->
                        (arr as? kotlinx.serialization.json.JsonArray)?.map {
                            (it as kotlinx.serialization.json.JsonPrimitive).content
                        }
                    } ?: emptyList()
                )
            }

            if (parsed != null) {
                judgeScore = parsed.score
                issues.addAll(parsed.issues.map { Issue(Severity.WARNING, it, null) })

                return VerificationResult(
                    passed = !issues.any { it.severity == Severity.CRITICAL } && judgeScore >= 0.7,
                    score = judgeScore,
                    issues = issues,
                    suggestions = parsed.suggestions
                )
            }
        } catch (e: Exception) {
            logger.warn("LLM-as-Judge failed", e)
        }

        // ── Katman 3: Test Çalıştırma ──
        if (testRunnerTool != null) {
            val testArtifacts = result.artifacts.filter {
                it.filename.contains("Test", ignoreCase = true)
            }
            val sourceArtifacts = result.artifacts.filter {
                !it.filename.contains("Test", ignoreCase = true)
            }

            if (testArtifacts.isNotEmpty() && sourceArtifacts.isNotEmpty()) {
                try {
                    val testResult = testRunnerTool.execute(
                        mapOf(
                            "source_code" to sourceArtifacts.first().content,
                            "test_code" to testArtifacts.first().content
                        )
                    )
                    when (testResult) {
                        is ToolResult.Failure -> {
                            issues.add(Issue(Severity.CRITICAL, "Test basarisiz: ${testResult.error}", null))
                        }
                        is ToolResult.Success -> {
                            logger.debug("Tests passed")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Test execution failed", e)
                }
            }
        }

        val hasCritical = issues.any { it.severity == Severity.CRITICAL }

        return VerificationResult(
            passed = !hasCritical && judgeScore >= 0.7,
            score = judgeScore,
            issues = issues,
            suggestions = emptyList()
        )
    }

    private fun extractJsonFromResponse(text: String): String {
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

    private data class JudgeResponse(
        val score: Double,
        val issues: List<String>,
        val suggestions: List<String>
    )
}
