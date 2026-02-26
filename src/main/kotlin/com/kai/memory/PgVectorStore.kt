package com.kai.memory

import com.kai.models.CodeArtifact
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * PostgreSQL + pgvector ile episodic memory.
 * Vektör benzerliği araması yaparak geçmiş deneyimleri bulur.
 */
class PgVectorStore(private val database: Database) {
    private val logger = LoggerFactory.getLogger(PgVectorStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Veritabanı tablosunu ve pgvector extension'ını oluştur */
    suspend fun initialize() {
        newSuspendedTransaction(db = database) {
            exec("CREATE EXTENSION IF NOT EXISTS vector")
            exec(
                """
                CREATE TABLE IF NOT EXISTS episodes (
                    id BIGSERIAL PRIMARY KEY,
                    task_description TEXT NOT NULL,
                    trajectory_summary TEXT NOT NULL,
                    outcome_score DOUBLE PRECISION NOT NULL,
                    artifacts JSONB DEFAULT '[]',
                    embedding vector(1536),
                    created_at TIMESTAMPTZ DEFAULT NOW()
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE INDEX IF NOT EXISTS idx_episodes_embedding
                ON episodes USING ivfflat (embedding vector_cosine_ops)
                WITH (lists = 100)
                """.trimIndent()
            )
        }
        logger.info("PgVectorStore initialized")
    }

    /** Vektör benzerliği ile en yakın episodları getir */
    suspend fun querySimilar(embedding: List<Float>, limit: Int): List<Episode> {
        return newSuspendedTransaction(db = database) {
            val vectorStr = embedding.joinToString(",", "[", "]")
            exec(
                """
                SELECT task_description, trajectory_summary, outcome_score, artifacts, created_at
                FROM episodes
                ORDER BY embedding <=> '$vectorStr'::vector
                LIMIT $limit
                """.trimIndent()
            ) { rs ->
                val episodes = mutableListOf<Episode>()
                while (rs.next()) {
                    episodes.add(
                        Episode(
                            taskDescription = rs.getString("task_description"),
                            trajectorySummary = rs.getString("trajectory_summary"),
                            outcomeScore = rs.getDouble("outcome_score"),
                            artifacts = try {
                                json.decodeFromString<List<CodeArtifact>>(
                                    rs.getString("artifacts") ?: "[]"
                                )
                            } catch (_: Exception) {
                                emptyList()
                            },
                            createdAt = rs.getTimestamp("created_at").time
                        )
                    )
                }
                episodes
            } ?: emptyList()
        }
    }

    /** Yeni episode kaydet */
    suspend fun insertEpisode(
        taskDescription: String,
        trajectorySummary: String,
        outcomeScore: Double,
        artifacts: String,
        embedding: List<Float>
    ) {
        newSuspendedTransaction(db = database) {
            val vectorStr = embedding.joinToString(",", "[", "]")
            exec(
                """
                INSERT INTO episodes (task_description, trajectory_summary, outcome_score, artifacts, embedding)
                VALUES ('${taskDescription.escapeSql()}', '${trajectorySummary.escapeSql()}',
                        $outcomeScore, '$artifacts'::jsonb, '$vectorStr'::vector)
                """.trimIndent()
            )
        }
    }

    private fun String.escapeSql(): String = replace("'", "''")
}
