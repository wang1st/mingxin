package com.mingxin.feynman.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
interface AttemptDao {
    /** 全部尝试，按开始时间倒序；学习资产，永不截断 */
    @Query("SELECT * FROM attempt ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<AttemptEntity>>

    @Query("SELECT * FROM attempt ORDER BY startedAt DESC")
    suspend fun all(): List<AttemptEntity>

    @Query("SELECT * FROM attempt WHERE id = :id")
    suspend fun byId(id: String): AttemptEntity?

    /** 当前未完成的尝试（stage 不在 DONE/ABANDONED），用于 App 重启后恢复 */
    @Query("SELECT * FROM attempt WHERE finishedAt = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun active(): AttemptEntity?

    @Query("SELECT questionId FROM attempt WHERE finishedAt > 0 AND questionId != ''")
    suspend fun practicedQuestionIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: AttemptEntity)

    @Query("DELETE FROM attempt WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DoubtDao {
    @Query("SELECT * FROM doubt ORDER BY resolved ASC, createdAt DESC")
    fun observeAll(): Flow<List<DoubtEntity>>

    @Query("SELECT * FROM doubt ORDER BY resolved ASC, createdAt DESC")
    suspend fun all(): List<DoubtEntity>

    @Query("SELECT * FROM doubt WHERE id = :id")
    suspend fun byId(id: String): DoubtEntity?

    /** 今日到期（含逾期）的未决疑点 */
    @Query("SELECT * FROM doubt WHERE resolved = 0 AND nextReviewAt > 0 AND nextReviewAt <= :now ORDER BY nextReviewAt ASC")
    suspend fun due(now: Long): List<DoubtEntity>

    @Query("SELECT COUNT(*) FROM doubt WHERE resolved = 0")
    fun observeOpenCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM doubt WHERE resolved = 0 AND nextReviewAt > 0 AND nextReviewAt <= :now")
    suspend fun dueCount(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: DoubtEntity)

    @Query("DELETE FROM doubt WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReviewLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ReviewLogEntity)

    @Query("SELECT * FROM review_log ORDER BY reviewedAt DESC")
    suspend fun all(): List<ReviewLogEntity>
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: MetaEntity)
}
