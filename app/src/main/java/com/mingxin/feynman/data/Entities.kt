package com.mingxin.feynman.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 题库题目。从 assets 种子导入，可被后续版本增量补丁更新。
 * 主键用题库自带的稳定 id（如 H-003），不是自增，保证跨设备一致。
 */
@Entity(tableName = "question")
data class QuestionEntity(
    @PrimaryKey val id: String,
    val sutra: String,
    val sutraCode: String,
    val topic: String,
    val difficulty: Int,
    val question: String,
    val anchor: String,
    val keyPoints: String,
    val misconception: String,
    val followups: String,      // \n 分隔，Room 不存 List
    val grader: String,
    val taisho: String,
    val edition: String,
    /** 题库版本号，用于增量更新对比 */
    val bankVersion: Int = 1
)

/**
 * 一次研习尝试 = 学习系统的核心资产。
 * 永不截断、永不过期。UUID 主键保证未来可导出/合并。
 */
@Entity(
    tableName = "attempt",
    indices = [Index("questionId"), Index("startedAt"), Index("finishedAt")]
)
data class AttemptEntity(
    @PrimaryKey val id: String,
    /** 题库题 id；自定义课题为空串 */
    val questionId: String,
    val topic: String,
    val prompt: String,
    val anchor: String,
    val edition: String,
    /** INTRO / ASK / REVIEW / REWRITE / DONE / ABANDONED */
    val stage: String,
    val firstExplanation: String,
    /** 对话序列化 JSON 数组 */
    val dialogueJson: String,
    val evaluationJson: String,
    val revisedExplanation: String,
    val finalFeedback: String,
    val startedAt: Long,
    val finishedAt: Long,        // 0 = 未完成
    val updatedAt: Long
)

/**
 * 疑点：从评估中沉淀、可被消除。带 SRS 调度字段，S1 才会真正用上，
 * 但表结构现在就定，避免以后迁移。
 */
@Entity(
    tableName = "doubt",
    indices = [Index("questionId"), Index("nextReviewAt")]
)
data class DoubtEntity(
    @PrimaryKey val id: String,
    val text: String,
    val questionId: String,
    val topic: String,
    val createdAt: Long,
    val resolved: Boolean,
    val resolvedAt: Long,
    /** SRS：下次到期时间戳，0 = 未排程 */
    val nextReviewAt: Long = 0,
    /** SRS：当前间隔天数 */
    val intervalDays: Int = 0,
    /** SRS：难度系数（FSRS 简化） */
    val ease: Float = 2.5f,
    /** SRS：连续答对次数 */
    val streak: Int = 0
)

/**
 * 复习日志：每次 SRS 复习结果，用于调参与历史回溯。
 */
@Entity(tableName = "review_log", indices = [Index("doubtId"), Index("reviewedAt")])
data class ReviewLogEntity(
    @PrimaryKey val id: String,
    val doubtId: String,
    /** 1=忘记 2=吃力 3=想起 4=轻松 */
    val grade: Int,
    val reviewedAt: Long,
    val prevIntervalDays: Int,
    val newIntervalDays: Int
)

/**
 * 元数据表：题库存量版本、上次导入时间等。
 */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String
)
