package com.kai.core

import com.kai.memory.Episode
import com.kai.memory.GraphContext

/**
 * Her ajan rolü için system prompt oluşturan interface.
 */
interface SystemPromptBuilder {
    fun build(
        role: AgentRole,
        episodicContext: List<Episode>,
        semanticContext: GraphContext,
        taskConstraints: List<String>,
        additionalContext: String
    ): String
}

class DefaultSystemPromptBuilder : SystemPromptBuilder {

    override fun build(
        role: AgentRole,
        episodicContext: List<Episode>,
        semanticContext: GraphContext,
        taskConstraints: List<String>,
        additionalContext: String
    ): String {
        val base = rolePrompt(role)
        val memory = buildMemorySection(episodicContext, semanticContext)
        val constraints = if (taskConstraints.isNotEmpty()) {
            "\n## Kisitlar\n${taskConstraints.joinToString("\n") { "- $it" }}"
        } else ""
        val context = if (additionalContext.isNotBlank()) {
            "\n## Ek Baglam\n$additionalContext"
        } else ""

        return """
            |$base
            |$memory
            |$constraints
            |$context
            |
            |## Genel Kurallar
            |- Her zaman Kotlin idiomatic kodla. `when` yerine `if-else` zinciri KULLANMA.
            |- Coroutine kullan, blocking I/O YAPMA.
            |- Hata durumlarini sealed class veya Result ile yonet.
            |- Kodu acikla, ama gereksiz yorum yazma.
            |- Turkce dusun, kod isimleri Ingilizce olsun.
        """.trimMargin()
    }

    private fun rolePrompt(role: AgentRole): String = when (role) {
        AgentRole.PLANNER -> """
            |## Rol: Planner Agent
            |Sen bir yazilim mimar ve gorev plancisin.
            |Kullanicinin isteğini analiz et ve alt gorevlere bol.
            |Her alt gorevi hangi ajanin yapacagini belirle.
            |Gorevler arasi bagimliliklari tanimla.
            |JSON formatta ExecutionPlan dondur.
        """.trimMargin()

        AgentRole.CODE_WRITER -> """
            |## Rol: Code Writer Agent
            |Sen uzman bir Kotlin/KTOR gelistiricisin.
            |Temiz, idiomatic, test edilebilir Kotlin kodu yaz.
            |Her zaman:
            |- Data class ve sealed class kullan
            |- Null safety'ye dikkat et
            |- Coroutine-friendly yaz (suspend fun)
            |- SOLID prensiplerine uy
        """.trimMargin()

        AgentRole.REVIEWER -> """
            |## Rol: Reviewer Agent
            |Sen bir kod review uzmanisin.
            |Verilen kodu su kriterlere gore incele:
            |1. Dogruluk — gorevi yerine getiriyor mu?
            |2. Kotlin best practices
            |3. Hata yonetimi
            |4. Performans ve verimlilik
            |5. Guvenlik
            |Score (0.0-1.0) ve issues listesi dondur.
        """.trimMargin()

        AgentRole.FIXER -> """
            |## Rol: Fixer Agent
            |Sen bir bug-fix uzmanisin.
            |Verilen hatalari/uyarilari duzelt.
            |Minimum degisiklikle maximum etki sagla.
            |Orijinal kodun stiline ve pattern'ine sadik kal.
        """.trimMargin()

        AgentRole.TESTER -> """
            |## Rol: Tester Agent
            |Sen bir test muhendisisin.
            |JUnit 5 + Kotlin Test ile unit testler yaz.
            |MockK ile mock'lama yap.
            |Edge case'leri ve hata senaryolarini kapsa.
            |Her test icin acik bir isimlendirme kullan:
            |`should do X when Y`
        """.trimMargin()

        AgentRole.RESEARCHER -> """
            |## Rol: Researcher Agent
            |Sen bir teknik arastirmacisin.
            |Kotlin, KTOR, Android ve JVM ekosisteminde uzmansin.
            |Dokumantasyon ve API referanslarini ara.
            |Bulgulari ozet olarak sun, kod ornekleri ekle.
        """.trimMargin()
    }

    private fun buildMemorySection(
        episodicContext: List<Episode>,
        semanticContext: GraphContext
    ): String {
        val parts = mutableListOf<String>()

        if (episodicContext.isNotEmpty()) {
            parts.add("## Gecmis Deneyimler (Episodic Memory)")
            episodicContext.forEach { ep ->
                parts.add("- [Skor: ${ep.outcomeScore}] ${ep.taskDescription.take(100)}")
                parts.add("  Ozet: ${ep.trajectorySummary.take(150)}")
            }
        }

        if (semanticContext.facts.isNotEmpty()) {
            parts.add("\n## Bilgi Grafi (Semantic Memory)")
            parts.add(semanticContext.toPromptString())
        }

        return if (parts.isNotEmpty()) parts.joinToString("\n") else ""
    }
}
