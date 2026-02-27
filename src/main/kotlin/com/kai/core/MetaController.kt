package com.kai.core

import com.kai.llm.LLMClient
import com.kai.memory.MemoryLayer
import com.kai.models.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Multi-Agent Orchestrator.
 * Gelen görevi planlar, ajanlara dağıtır, doğrular ve sonuçları birleştirir.
 */
class MetaController(
    private val agents: Map<AgentRole, ReActAgent>,
    private val llmClient: LLMClient,
    private val memory: MemoryLayer,
    private val verificationGate: VerificationGate
) {
    private val logger = LoggerFactory.getLogger(MetaController::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Kullanıcı isteğini alır → Plan oluşturur →
     * Ajanları sırayla/paralel çalıştırır → Sonuç döner
     */
    suspend fun process(userRequest: String, sessionId: String): AgentResponse {
        return process(userRequest, sessionId, null)
    }

    /**
     * Streaming destekli işleme.
     * Her adımda onStep callback'i çağrılır.
     */
    suspend fun process(
        userRequest: String,
        sessionId: String,
        onStep: (suspend (StreamEvent) -> Unit)?
    ): AgentResponse {
        logger.info("Processing request: ${userRequest.take(100)}")

        // ═══ DOĞRUDAN LLM YANITI (multi-agent pipeline geçici olarak devre dışı) ═══
        onStep?.invoke(StreamEvent.Thinking("Düşünüyorum..."))
        val answer = try {
            llmClient.chat(
                system = """Sen KAI, cok yetenekli bir AI kod asistanisin.
                    |Kullanicinin dilinde yanit ver (genellikle Turkce).
                    |Kod isteklerinde Kotlin/Ktor oncelikli yaz.
                    |Kod bloklari icin markdown ``` kullan.
                    |Kisa, net ve yardimci ol.""".trimMargin(),
                user = userRequest
            )
        } catch (e: Exception) {
            logger.error("LLM chat failed", e)
            "Bir hata olustu: ${e.message}"
        }
        val finalAnswer = answer.ifEmpty { "Merhaba! Size nasil yardimci olabilirim?" }
        logger.info("LLM response received (${finalAnswer.length} chars)")
        onStep?.invoke(StreamEvent.Done(finalAnswer, null))
        return AgentResponse(
            answer = finalAnswer,
            artifacts = emptyList(),
            planSteps = emptyList(),
            metadata = AnswerMetadata(totalSteps = 1, toolsUsed = emptyList(), totalDurationMs = 0, agentsInvolved = emptyList())
        )

        /* ═══ MULTI-AGENT PIPELINE (şimdilik devre dışı) ═══
        // ═══ ADIM 1: PLANLAMA ═══
        val plan = planTask(userRequest)
        logger.info("Plan created with ${plan.steps.size} steps")

        plan.steps.forEach { step ->
            onStep?.invoke(StreamEvent.PlanUpdate(step.id, StepStatus.PENDING, step.description))
        }

        // ═══ ADIM 2: YÜRÜTME (paralel wave'ler halinde) ═══
        val results = mutableMapOf<String, AgentStep.Answer>()
        val stepResults = mutableListOf<PlanStepResult>()

        val waves = plan.executionWaves()
        logger.info("Execution waves: ${waves.size}")

        for ((waveIndex, wave) in waves.withIndex()) {
            logger.info("Wave ${waveIndex + 1}: ${wave.map { it.id }}")

            // Aynı wave'deki adımları paralel çalıştır
            coroutineScope {
                val deferreds = wave.map { step ->
                    async {
                        executeStep(step, results, onStep)
                    }
                }

                for ((index, deferred) in deferreds.withIndex()) {
                    val step = wave[index]
                    try {
                        val result = deferred.await()
                        results[step.id] = result

                        // ═══ ADIM 3: DOĞRULAMA (PEV) ═══
                        if (step.requiresVerification && result.artifacts.isNotEmpty()) {
                            onStep?.invoke(StreamEvent.PlanUpdate(step.id, StepStatus.RUNNING, "Dogrulanıyor..."))

                            val verification = verificationGate.verify(step, result)
                            if (!verification.passed) {
                                logger.warn("Verification failed for ${step.id}: ${verification.issues}")
                                val fixed = handleVerificationFailure(step, result, verification, onStep)
                                results[step.id] = fixed
                            }
                        }

                        stepResults.add(
                            PlanStepResult(
                                stepId = step.id,
                                agent = step.assignedAgent,
                                description = step.description,
                                status = StepStatus.COMPLETED,
                                output = result.content.take(500)
                            )
                        )
                        onStep?.invoke(StreamEvent.PlanUpdate(step.id, StepStatus.COMPLETED, step.description))

                    } catch (e: Exception) {
                        logger.error("Step ${step.id} failed", e)
                        stepResults.add(
                            PlanStepResult(
                                stepId = step.id,
                                agent = step.assignedAgent,
                                description = step.description,
                                status = StepStatus.FAILED,
                                output = "Hata: ${e.message}"
                            )
                        )
                        onStep?.invoke(StreamEvent.PlanUpdate(step.id, StepStatus.FAILED, "Hata: ${e.message}"))
                    }
                }
            }
        }

        // ═══ ADIM 4: SONUÇ BİRLEŞTİRME ═══
        return synthesizeResults(plan, results, stepResults)
        MULTI-AGENT PIPELINE SONU */
    }

    /** Tek bir plan adımını çalıştır */
    private suspend fun executeStep(
        step: PlanStep,
        previousResults: Map<String, AgentStep.Answer>,
        onStep: (suspend (StreamEvent) -> Unit)?
    ): AgentStep.Answer {
        val agent = agents[step.assignedAgent]
            ?: throw AgentException("${step.assignedAgent} icin ajan bulunamadi")

        onStep?.invoke(StreamEvent.PlanUpdate(step.id, StepStatus.RUNNING, step.description))

        val context = buildStepContext(previousResults, step.dependsOn)

        val task = AgentTask(
            description = step.description,
            context = context,
            constraints = step.constraints
        )

        return try {
            agent.execute(task, onStep)
        } catch (e: DelegationException) {
            // Ajan başka bir ajana devretmek istiyor
            logger.info("Delegation from ${step.assignedAgent} to ${e.delegation.targetAgent}")
            val targetAgent = agents[e.delegation.targetAgent]
                ?: throw AgentException("Delegation target bulunamadi: ${e.delegation.targetAgent}")

            val delegatedTask = AgentTask.fromDelegation(e.delegation.context)
            targetAgent.execute(delegatedTask, onStep)
        }
    }

    /** LLM kullanarak görevi alt adımlara böler */
    private suspend fun planTask(request: String): ExecutionPlan {
        val planPrompt = """
            Kullanici Istegi: $request

            Mevcut Ajanlar: ${agents.keys.joinToString()}

            JSON formatinda bir yurutme plani olustur. Sadece JSON dondur, baska bir sey yazma.
            {
              "steps": [
                {
                  "id": "step_1",
                  "description": "Yapilacak is aciklamasi",
                  "assignedAgent": "CODE_WRITER",
                  "dependsOn": [],
                  "requiresVerification": true,
                  "constraints": ["Kotlin", "coroutines"]
                }
              ]
            }

            Kurallar:
            - En az 1, en fazla 6 adim.
            - Bagimliliklari dogru tanimla (dependsOn).
            - RESEARCHER ilk adimlarda bilgi toplar.
            - CODE_WRITER kod yazar.
            - REVIEWER kodu inceler.
            - FIXER hatalari duzeltir.
            - TESTER test yazar.
        """.trimIndent()

        val planResponse = llmClient.chat(
            system = "Sen bir gorev planlama uzmanisin. Sadece gecerli JSON dondur.",
            user = planPrompt
        )

        return try {
            // JSON'u çıkar (bazen LLM markdown code block içinde döndürür)
            val jsonStr = extractJson(planResponse)
            json.decodeFromString<ExecutionPlan>(jsonStr)
        } catch (e: Exception) {
            logger.error("Plan parse failed, using fallback. Response: ${planResponse.take(300)}", e)
            // Fallback: basit tek adımlık plan
            ExecutionPlan(
                steps = listOf(
                    PlanStep(
                        id = "step_1",
                        description = request,
                        assignedAgent = AgentRole.CODE_WRITER,
                        requiresVerification = true,
                        constraints = listOf("Kotlin")
                    )
                )
            )
        }
    }

    /** Verification başarısız olduğunda Fixer'a gönder */
    private suspend fun handleVerificationFailure(
        step: PlanStep,
        originalResult: AgentStep.Answer,
        verification: VerificationGate.VerificationResult,
        onStep: (suspend (StreamEvent) -> Unit)?
    ): AgentStep.Answer {
        val fixer = agents[AgentRole.FIXER] ?: return originalResult

        val fixTask = AgentTask(
            description = """
                Asagidaki kodu duzelt. Sorunlar:
                ${verification.issues.joinToString("\n") { "- [${it.severity}] ${it.description}" }}

                Oneriler:
                ${verification.suggestions.joinToString("\n") { "- $it" }}

                Orijinal kod:
                ${originalResult.artifacts.joinToString("\n---\n") { it.content }}
            """.trimIndent(),
            constraints = step.constraints
        )

        return try {
            fixer.execute(fixTask, onStep)
        } catch (e: Exception) {
            logger.warn("Fixer failed, returning original result", e)
            originalResult
        }
    }

    /** Önceki adımların sonuçlarından bağlam oluştur */
    private fun buildStepContext(
        results: Map<String, AgentStep.Answer>,
        dependsOn: List<String>
    ): String {
        if (dependsOn.isEmpty()) return ""

        return dependsOn.mapNotNull { depId ->
            results[depId]?.let { answer ->
                "[$depId sonucu]: ${answer.content.take(1000)}\n" +
                    answer.artifacts.joinToString("\n") { "Dosya: ${it.filename}\n${it.content}" }
            }
        }.joinToString("\n\n")
    }

    /** Tüm adım sonuçlarını tek bir AgentResponse'a birleştir */
    private suspend fun synthesizeResults(
        plan: ExecutionPlan,
        results: Map<String, AgentStep.Answer>,
        stepResults: List<PlanStepResult>
    ): AgentResponse {
        val allArtifacts = results.values.flatMap { it.artifacts }

        // Son adımın cevabını ana cevap olarak kullan, yoksa birleştir
        val lastAnswer = results.values.lastOrNull()?.content ?: "Islem tamamlandi."

        val totalDuration = results.values
            .mapNotNull { it.metadata?.totalDurationMs }
            .sum()

        val allTools = results.values
            .flatMap { it.metadata?.toolsUsed ?: emptyList() }
            .distinct()

        val allAgents = results.values
            .flatMap { it.metadata?.agentsInvolved ?: emptyList() }
            .distinct()

        return AgentResponse(
            answer = lastAnswer,
            artifacts = allArtifacts,
            planSteps = stepResults,
            metadata = AnswerMetadata(
                totalSteps = stepResults.size,
                toolsUsed = allTools,
                totalDurationMs = totalDuration,
                agentsInvolved = allAgents
            )
        )
    }

    /** Basit mesaj mı kontrol et (selamlama, kısa soru, kod içermeyen) */
    private fun isSimpleMessage(message: String): Boolean {
        val lower = message.trim().lowercase()
        val wordCount = lower.split("\\s+".toRegex()).size
        // Kısa mesajlar (5 kelimeden az) ve kod/teknik terim içermeyenler
        if (wordCount <= 5 && !lower.contains("```") && !lower.contains("kod") &&
            !lower.contains("code") && !lower.contains("yaz") && !lower.contains("write") &&
            !lower.contains("create") && !lower.contains("oluştur") && !lower.contains("implement") &&
            !lower.contains("fix") && !lower.contains("düzelt") && !lower.contains("debug") &&
            !lower.contains("refactor")
        ) return true
        return false
    }

    /** JSON'u markdown code block'undan çıkar */
    private fun extractJson(text: String): String {
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n([\\s\\S]*?)```")
        val match = codeBlockRegex.find(text)
        if (match != null) return match.groupValues[1].trim()

        // Code block yoksa doğrudan JSON olabilir
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1)
        }

        return text
    }
}
