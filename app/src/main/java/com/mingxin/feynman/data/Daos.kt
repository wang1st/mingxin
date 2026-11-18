package com.mingxin.feynman.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    @Query("SELECT * FROM question ORDER BY sutra, topic, id")
    suspend fun all(): List<QuestionEntity>

    @Query("SELECT * FROM question WHERE id = :id")
    suspend fun byId(id: String): QuestionEntity?

    @Query("SELECT COUNT(*) FROM question")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<QuestionEntity>)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session ORDER BY startedAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    /** 当前未完成的课 */
    @Query("SELECT * FROM session WHERE finishedAt = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun active(): SessionEntity?

    @Query("SELECT questionId FROM session WHERE finishedAt > 0 AND questionId != ''")
    suspend fun practicedQuestionIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SessionEntity)

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface InsightDao {
    @Query("SELECT * FROM insight ORDER BY status ASC, createdAt DESC")
    fun observeAll(): Flow<List<InsightEntity>>

    @Query("SELECT * FROM insight ORDER BY status ASC, createdAt DESC")
    suspend fun all(): List<InsightEntity>

    @Query("SELECT * FROM insight WHERE id = :id")
    suspend fun byId(id: String): InsightEntity?

    /** 到期（含逾期）的、仍待解决的 */
    @Query("SELECT * FROM insight WHERE status = 'OPEN' AND nextReviewAt > 0 AND nextReviewAt <= :now ORDER BY nextReviewAt ASC")
    suspend fun due(now: Long): List<InsightEntity>

    @Query("SELECT COUNT(*) FROM insight WHERE status = 'OPEN' AND nextReviewAt > 0 AND nextReviewAt <= :now")
    suspend fun dueCount(now: Long): Int

    @Query("SELECT COUNT(*) FROM insight WHERE status = 'RESOLVED'")
    fun observeResolvedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(i: InsightEntity)

    @Query("DELETE FROM insight WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReviewLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ReviewLogEntity)

    @Query("SELECT * FROM review_log ORDER BY reviewedAt DESC")
    suspend fun all(): List<ReviewLogEntity>

    @Query("SELECT COUNT(*) FROM review_log")
    suspend fun count(): Int
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: MetaEntity)
}
