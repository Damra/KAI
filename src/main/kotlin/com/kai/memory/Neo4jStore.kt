package com.kai.memory

import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.slf4j.LoggerFactory

/**
 * Neo4j ile semantic memory.
 * Entity-Relation grafiği: "User PREFERS Kotlin", "Project USES KTOR" gibi.
 */
class Neo4jStore(private val driver: Driver) {
    private val logger = LoggerFactory.getLogger(Neo4jStore::class.java)

    /** Schema constraint'leri oluştur */
    suspend fun initialize() {
        driver.session().use { session ->
            session.run(
                "CREATE CONSTRAINT IF NOT EXISTS FOR (e:Entity) REQUIRE e.name IS UNIQUE"
            )
        }
        logger.info("Neo4jStore initialized")
    }

    /** Verilen entity'lere bağlı tüm ilişkileri getir */
    suspend fun queryRelated(entities: List<String>): GraphContext {
        if (entities.isEmpty()) return GraphContext(emptyList(), entities)

        return driver.session().use { session ->
            val result = session.run(
                """
                MATCH (e:Entity)-[r]->(related:Entity)
                WHERE e.name IN ${'$'}entities
                RETURN e.name AS subject, type(r) AS relation, related.name AS object
                LIMIT 50
                """.trimIndent(),
                Values.parameters("entities", entities)
            )

            val facts = result.list().map { record ->
                Fact(
                    subject = record["subject"].asString(),
                    relation = record["relation"].asString(),
                    objectEntity = record["object"].asString()
                )
            }

            GraphContext(facts, entities)
        }
    }

    /** Fact'leri grafa ekle (MERGE ile upsert) */
    suspend fun mergeFacts(facts: List<Fact>) {
        driver.session().use { session ->
            for (fact in facts) {
                session.run(
                    """
                    MERGE (a:Entity {name: ${'$'}subject})
                    MERGE (b:Entity {name: ${'$'}object})
                    MERGE (a)-[r:${sanitizeRelation(fact.relation)}]->(b)
                    SET r.updatedAt = datetime()
                    """.trimIndent(),
                    Values.parameters(
                        "subject", fact.subject,
                        "object", fact.objectEntity
                    )
                )
            }
        }
    }

    /** Relation adlarını Neo4j-safe yap */
    private fun sanitizeRelation(relation: String): String {
        return relation
            .uppercase()
            .replace(Regex("[^A-Z0-9_]"), "_")
            .take(50)
    }

    fun close() {
        driver.close()
    }
}
