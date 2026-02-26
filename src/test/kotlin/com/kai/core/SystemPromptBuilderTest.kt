package com.kai.core

import com.kai.memory.Episode
import com.kai.memory.Fact
import com.kai.memory.GraphContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SystemPromptBuilderTest {
    private val builder = DefaultSystemPromptBuilder()

    @Test
    fun `planner prompt contains role description`() {
        val prompt = builder.build(
            role = AgentRole.PLANNER,
            episodicContext = emptyList(),
            semanticContext = GraphContext(emptyList(), emptyList()),
            taskConstraints = emptyList(),
            additionalContext = ""
        )

        assertContains(prompt, "Planner")
        assertContains(prompt, "gorev")
    }

    @Test
    fun `code writer prompt contains Kotlin guidelines`() {
        val prompt = builder.build(
            role = AgentRole.CODE_WRITER,
            episodicContext = emptyList(),
            semanticContext = GraphContext(emptyList(), emptyList()),
            taskConstraints = emptyList(),
            additionalContext = ""
        )

        assertContains(prompt, "Kotlin")
        assertContains(prompt, "SOLID")
    }

    @Test
    fun `reviewer prompt contains scoring criteria`() {
        val prompt = builder.build(
            role = AgentRole.REVIEWER,
            episodicContext = emptyList(),
            semanticContext = GraphContext(emptyList(), emptyList()),
            taskConstraints = emptyList(),
            additionalContext = ""
        )

        assertContains(prompt, "Dogruluk")
        assertContains(prompt, "Performans")
    }

    @Test
    fun `prompt includes episodic memory context`() {
        val episodes = listOf(
            Episode(
                taskDescription = "HttpClient yazdik",
                trajectorySummary = "CIO engine kullandik",
                outcomeScore = 0.9,
                artifacts = emptyList()
            )
        )

        val prompt = builder.build(
            role = AgentRole.CODE_WRITER,
            episodicContext = episodes,
            semanticContext = GraphContext(emptyList(), emptyList()),
            taskConstraints = emptyList(),
            additionalContext = ""
        )

        assertContains(prompt, "HttpClient")
        assertContains(prompt, "0.9")
    }

    @Test
    fun `prompt includes semantic graph context`() {
        val graphContext = GraphContext(
            facts = listOf(
                Fact("User", "PREFERS", "Kotlin"),
                Fact("Project", "USES", "KTOR")
            ),
            entities = listOf("User", "Project")
        )

        val prompt = builder.build(
            role = AgentRole.CODE_WRITER,
            episodicContext = emptyList(),
            semanticContext = graphContext,
            taskConstraints = emptyList(),
            additionalContext = ""
        )

        assertContains(prompt, "PREFERS")
        assertContains(prompt, "KTOR")
    }

    @Test
    fun `prompt includes task constraints`() {
        val prompt = builder.build(
            role = AgentRole.CODE_WRITER,
            episodicContext = emptyList(),
            semanticContext = GraphContext(emptyList(), emptyList()),
            taskConstraints = listOf("Coroutine kullan", "Minimum SDK 26"),
            additionalContext = ""
        )

        assertContains(prompt, "Coroutine kullan")
        assertContains(prompt, "Minimum SDK 26")
    }

    @Test
    fun `prompt includes additional context`() {
        val prompt = builder.build(
            role = AgentRole.FIXER,
            episodicContext = emptyList(),
            semanticContext = GraphContext(emptyList(), emptyList()),
            taskConstraints = emptyList(),
            additionalContext = "Onceki asamada NullPointerException alindi"
        )

        assertContains(prompt, "NullPointerException")
    }

    @Test
    fun `all roles produce non-empty prompts`() {
        AgentRole.entries.forEach { role ->
            val prompt = builder.build(
                role = role,
                episodicContext = emptyList(),
                semanticContext = GraphContext(emptyList(), emptyList()),
                taskConstraints = emptyList(),
                additionalContext = ""
            )
            assertTrue(prompt.isNotBlank(), "Prompt for $role should not be blank")
        }
    }

    @Test
    fun `general rules always included`() {
        val prompt = builder.build(
            role = AgentRole.TESTER,
            episodicContext = emptyList(),
            semanticContext = GraphContext(emptyList(), emptyList()),
            taskConstraints = emptyList(),
            additionalContext = ""
        )

        assertContains(prompt, "Kotlin idiomatic")
        assertContains(prompt, "Coroutine")
    }
}
