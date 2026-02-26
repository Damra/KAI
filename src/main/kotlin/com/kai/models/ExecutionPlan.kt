package com.kai.models

import com.kai.core.AgentRole
import kotlinx.serialization.Serializable

@Serializable
data class ExecutionPlan(
    val steps: List<PlanStep>
) {
    /** Bağımlılıkları çözümleyerek paralel çalıştırılabilecek grupları döndürür */
    fun executionWaves(): List<List<PlanStep>> {
        val completed = mutableSetOf<String>()
        val remaining = steps.toMutableList()
        val waves = mutableListOf<List<PlanStep>>()

        while (remaining.isNotEmpty()) {
            val wave = remaining.filter { step ->
                step.dependsOn.all { dep -> dep in completed }
            }
            if (wave.isEmpty()) {
                throw IllegalStateException(
                    "Circular dependency detected. Remaining: ${remaining.map { it.id }}"
                )
            }
            waves.add(wave)
            completed.addAll(wave.map { it.id })
            remaining.removeAll(wave.toSet())
        }

        return waves
    }
}

@Serializable
data class PlanStep(
    val id: String,
    val description: String,
    val assignedAgent: AgentRole,
    val dependsOn: List<String> = emptyList(),
    val requiresVerification: Boolean = true,
    val constraints: List<String> = emptyList()
)
