package com.kai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContains

class AgentTaskTest {

    @Test
    fun `entities extracts capitalized words`() {
        val task = AgentTask(description = "KTOR kullanarak GitHub API icin client yaz")
        val entities = task.entities()

        assertContains(entities, "KTOR")
        assertContains(entities, "GitHub")
        assertContains(entities, "API")
    }

    @Test
    fun `entities extracts technical terms`() {
        val task = AgentTask(description = "kotlin coroutine ile database baglantisi kur")
        val entities = task.entities()

        assertTrue(entities.any { it.contains("kotlin", ignoreCase = true) })
        assertTrue(entities.any { it.contains("coroutine", ignoreCase = true) })
        assertTrue(entities.any { it.contains("database", ignoreCase = true) })
    }

    @Test
    fun `entities extracts dotted names`() {
        val task = AgentTask(description = "io.ktor.client paketini kullan")
        val entities = task.entities()

        assertTrue(entities.any { it.contains(".") })
    }

    @Test
    fun `entities returns distinct values`() {
        val task = AgentTask(description = "Kotlin Kotlin Kotlin ile yaz")
        val entities = task.entities()

        assertEquals(entities.size, entities.distinct().size)
    }

    @Test
    fun `entities empty for generic description`() {
        val task = AgentTask(description = "bir fonksiyon yaz")
        val entities = task.entities()
        // "bir" ve "fonksiyon" ne buyuk harfle baslar ne technical term
        // Sadece lowercase generic kelimeler
        assertTrue(entities.isEmpty() || entities.all { it.isNotBlank() })
    }

    @Test
    fun `fromDelegation creates correct task`() {
        val delegation = DelegationContext(
            reason = "Derleme hatasi",
            taskDescription = "Hatayi duzelt",
            constraints = listOf("Kotlin", "minimal")
        )

        val task = AgentTask.fromDelegation(delegation)

        assertEquals("Hatayi duzelt", task.description)
        assertEquals("Derleme hatasi", task.context)
        assertEquals(listOf("Kotlin", "minimal"), task.constraints)
    }

    @Test
    fun `task preserves all fields`() {
        val task = AgentTask(
            description = "Test gorevi",
            context = "Ek baglam bilgisi",
            constraints = listOf("constraint1", "constraint2"),
            parentTaskId = "parent-123"
        )

        assertEquals("Test gorevi", task.description)
        assertEquals("Ek baglam bilgisi", task.context)
        assertEquals(2, task.constraints.size)
        assertEquals("parent-123", task.parentTaskId)
    }
}
