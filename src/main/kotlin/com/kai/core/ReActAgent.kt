package com.kai.core

import com.kai.llm.LLMClient
import com.kai.memory.MemoryLayer
import com.kai.models.StreamEvent
import com.kai.tools.Tool
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

/**
 * ReAct Agent — Düşün → Araç Kullan → Gözlemle → Tekrarla.
 *
 * Her ajan bu çekirdeği kullanır. Role-specific davranış,
 * system prompt ve tool seti ile belirlenir.
 */
class ReActAgent(
    val role: AgentRole,
    private val llmClient: LLMClient,
    private val tools: Map<String, Tool>,
    private val memory: MemoryLayer,
    private val config: AgentConfig = AgentConfig(),
    private val systemPromptBuilder: SystemPromptBuilder = DefaultSystemPromptBuilder()
) {
    private val logger = LoggerFactory.getLogger("ReActAgent[${role.displayName}]")

    data class AgentConfig(
        val maxIterations: Int = 10,
        val maxRetries: Int = 3,
        val toolTimeoutMs: Long = 30_000,
        val confidenceThreshold: Double = 0.7,
        val tokenBudget: Int = 50_000
    )

    /**
     * Ana çalıştırma döngüsü.
     * suspend fun olması sayesinde her adım non-blocking.
     */
    suspend fun execute(task: AgentTask): AgentStep.Answer {
        return execute(task, null)
    }

    /**
     * Streaming destekli ana çalıştırma döngüsü.
     * Her adımda onStep callback'i çağrılır.
     */
    suspend fun execute(
        task: AgentTask,
        onStep: (suspend (StreamEvent) -> Unit)?
    ): AgentStep.Answer {
        val trajectory = mutableListOf<AgentStep>()
        var iteration = 0
        var retryCount = 0

        logger.info("[${role.displayName}] Starting task: ${task.description.take(80)}")

        // 1) Hafızadan ilgili bağlamı çek
        val episodicContext = memory.recallSimilar(task.description, limit = 5)
        val semanticContext = memory.queryGraph(task.entities())

        // 2) Sistem promptunu oluştur (role-specific)
        val systemPrompt = systemPromptBuilder.build(
            role = role,
            episodicContext = episodicContext,
            semanticContext = semanticContext,
            taskConstraints = task.constraints,
            additionalContext = task.context
        )

        // Tool definition'ları hazırla
        val toolDefinitions = tools.values.map { it.toToolDefinition() }

        while (iteration < config.maxIterations) {
            iteration++

            logger.debug("[${role.displayName}] Iteration $iteration/${config.maxIterations}")

            // 3) LLM'e mevcut trajectory'yi gönder → sonraki adımı al
            val nextStep = try {
                llmClient.reason(
                    system = systemPrompt,
                    trajectory = trajectory,
                    availableTools = toolDefinitions
                )
            } catch (e: Exception) {
                logger.error("[${role.displayName}] LLM call failed", e)
                AgentStep.Error(
                    message = "LLM cagri hatasi: ${e.message}",
                    recoverable = retryCount < config.maxRetries,
                    suggestedAction = "Tekrar dene"
                )
            }

            trajectory.add(nextStep)

            when (nextStep) {
                is AgentStep.Think -> {
                    logger.debug("[${role.displayName}] Think: ${nextStep.thought.take(100)}")
                    onStep?.invoke(StreamEvent.Thinking(nextStep.thought))

                    // Yeterince güvenli mi?
                    if (nextStep.confidence >= config.confidenceThreshold) {
                        logger.info("[${role.displayName}] High confidence (${nextStep.confidence}), proceeding")
                    }
                    continue
                }

                is AgentStep.Act -> {
                    logger.info("[${role.displayName}] Act: ${nextStep.toolName}(${nextStep.reasoning.take(50)})")
                    onStep?.invoke(StreamEvent.ToolCall(nextStep.toolName, nextStep.toolInput.toString()))

                    // Aracı çalıştır
                    val tool = tools[nextStep.toolName]
                    if (tool == null) {
                        val error = AgentStep.Observe(
                            toolName = nextStep.toolName,
                            result = ToolResult.Failure("Bilinmeyen tool: ${nextStep.toolName}", retryable = true),
                            durationMs = 0
                        )
                        trajectory.add(error)
                        onStep?.invoke(StreamEvent.ToolResultEvent(nextStep.toolName, "Bilinmeyen tool", false))
                        continue
                    }

                    var result: ToolResult
                    val duration = measureTimeMillis {
                        result = withTimeoutOrNull(config.toolTimeoutMs) {
                            try {
                                tool.execute(nextStep.toolInput)
                            } catch (e: Exception) {
                                logger.error("[${role.displayName}] Tool '${nextStep.toolName}' threw exception", e)
                                ToolResult.Failure("Tool exception: ${e.message}", retryable = true)
                            }
                        } ?: ToolResult.Failure(
                            "Tool timeout (${config.toolTimeoutMs}ms): ${nextStep.toolName}",
                            retryable = true
                        )
                    }

                    val observe = AgentStep.Observe(
                        toolName = nextStep.toolName,
                        result = result,
                        durationMs = duration
                    )
                    trajectory.add(observe)

                    onStep?.invoke(
                        StreamEvent.ToolResultEvent(
                            nextStep.toolName,
                            when (result) {
                                is ToolResult.Success -> result.output.take(500)
                                is ToolResult.Failure -> "HATA: ${result.error}"
                            },
                            result.isSuccess
                        )
                    )
                }

                is AgentStep.Answer -> {
                    logger.info("[${role.displayName}] Answer ready (${nextStep.artifacts.size} artifacts)")

                    // Metadata ekle
                    val enrichedAnswer = nextStep.copy(
                        metadata = AnswerMetadata(
                            totalSteps = trajectory.size,
                            toolsUsed = trajectory.filterIsInstance<AgentStep.Act>()
                                .map { it.toolName }.distinct(),
                            totalDurationMs = trajectory.filterIsInstance<AgentStep.Observe>()
                                .sumOf { it.durationMs },
                            agentsInvolved = listOf(role)
                        )
                    )

                    // Episodic hafızaya kaydet
                    try {
                        memory.storeEpisode(task, trajectory, enrichedAnswer)
                    } catch (e: Exception) {
                        logger.warn("[${role.displayName}] Failed to store episode", e)
                    }

                    onStep?.invoke(StreamEvent.Done(enrichedAnswer.content, enrichedAnswer.metadata))
                    return enrichedAnswer
                }

                is AgentStep.Delegate -> {
                    logger.info("[${role.displayName}] Delegating to ${nextStep.targetAgent}")
                    onStep?.invoke(
                        StreamEvent.Delegation(
                            from = role.displayName,
                            to = nextStep.targetAgent.displayName,
                            reason = nextStep.context.reason
                        )
                    )
                    throw DelegationException(nextStep)
                }

                is AgentStep.Error -> {
                    logger.warn("[${role.displayName}] Error: ${nextStep.message} (recoverable: ${nextStep.recoverable})")
                    onStep?.invoke(StreamEvent.Error(nextStep.message, nextStep.recoverable))

                    if (!nextStep.recoverable) {
                        throw AgentException(nextStep.message)
                    }

                    retryCount++
                    if (retryCount >= config.maxRetries) {
                        throw AgentException("Max retries ($config.maxRetries) exceeded: ${nextStep.message}")
                    }
                    continue
                }

                is AgentStep.Observe -> {
                    // LLM doğrudan Observe üretmez, Act sonrası biz ekleriz
                    continue
                }
            }
        }

        throw MaxIterationsExceededException(config.maxIterations)
    }
}
