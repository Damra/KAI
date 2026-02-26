package com.kai.core

import com.kai.models.ExecutionPlan
import com.kai.models.PlanStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExecutionPlanTest {

    @Test
    fun `single step produces single wave`() {
        val plan = ExecutionPlan(
            steps = listOf(
                PlanStep("s1", "Kod yaz", AgentRole.CODE_WRITER)
            )
        )

        val waves = plan.executionWaves()
        assertEquals(1, waves.size)
        assertEquals("s1", waves[0][0].id)
    }

    @Test
    fun `independent steps run in single wave`() {
        val plan = ExecutionPlan(
            steps = listOf(
                PlanStep("s1", "Arastir", AgentRole.RESEARCHER),
                PlanStep("s2", "Diger arastirma", AgentRole.RESEARCHER),
                PlanStep("s3", "Ucuncu is", AgentRole.CODE_WRITER)
            )
        )

        val waves = plan.executionWaves()
        assertEquals(1, waves.size)
        assertEquals(3, waves[0].size)
    }

    @Test
    fun `dependent steps create sequential waves`() {
        val plan = ExecutionPlan(
            steps = listOf(
                PlanStep("s1", "Arastir", AgentRole.RESEARCHER),
                PlanStep("s2", "Kod yaz", AgentRole.CODE_WRITER, dependsOn = listOf("s1")),
                PlanStep("s3", "Test yaz", AgentRole.TESTER, dependsOn = listOf("s2")),
                PlanStep("s4", "Review", AgentRole.REVIEWER, dependsOn = listOf("s2"))
            )
        )

        val waves = plan.executionWaves()
        assertEquals(3, waves.size)

        // Wave 1: s1 (bagimsiz)
        assertEquals(listOf("s1"), waves[0].map { it.id })

        // Wave 2: s2 (s1'e bagimli)
        assertEquals(listOf("s2"), waves[1].map { it.id })

        // Wave 3: s3, s4 (ikisi de s2'ye bagimli, paralel calisabilir)
        assertEquals(setOf("s3", "s4"), waves[2].map { it.id }.toSet())
    }

    @Test
    fun `diamond dependency pattern`() {
        // s1 -> s2, s3 -> s4
        val plan = ExecutionPlan(
            steps = listOf(
                PlanStep("s1", "Baslangic", AgentRole.PLANNER),
                PlanStep("s2", "Sol kol", AgentRole.CODE_WRITER, dependsOn = listOf("s1")),
                PlanStep("s3", "Sag kol", AgentRole.RESEARCHER, dependsOn = listOf("s1")),
                PlanStep("s4", "Birlestir", AgentRole.REVIEWER, dependsOn = listOf("s2", "s3"))
            )
        )

        val waves = plan.executionWaves()
        assertEquals(3, waves.size)

        assertEquals(listOf("s1"), waves[0].map { it.id })
        assertEquals(setOf("s2", "s3"), waves[1].map { it.id }.toSet())
        assertEquals(listOf("s4"), waves[2].map { it.id })
    }

    @Test
    fun `circular dependency throws exception`() {
        val plan = ExecutionPlan(
            steps = listOf(
                PlanStep("s1", "A", AgentRole.CODE_WRITER, dependsOn = listOf("s2")),
                PlanStep("s2", "B", AgentRole.CODE_WRITER, dependsOn = listOf("s1"))
            )
        )

        assertFailsWith<IllegalStateException> {
            plan.executionWaves()
        }
    }

    @Test
    fun `empty plan produces empty waves`() {
        val plan = ExecutionPlan(steps = emptyList())
        val waves = plan.executionWaves()
        assertTrue(waves.isEmpty())
    }

    @Test
    fun `plan step serialization`() {
        val step = PlanStep(
            id = "step_1",
            description = "Kotlin client yaz",
            assignedAgent = AgentRole.CODE_WRITER,
            dependsOn = listOf("step_0"),
            requiresVerification = true,
            constraints = listOf("Kotlin", "KTOR")
        )

        assertEquals("step_1", step.id)
        assertEquals(AgentRole.CODE_WRITER, step.assignedAgent)
        assertTrue(step.requiresVerification)
        assertEquals(2, step.constraints.size)
    }

    private fun assertTrue(condition: Boolean) {
        kotlin.test.assertTrue(condition)
    }
}
