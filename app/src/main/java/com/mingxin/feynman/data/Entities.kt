package com.mingxin.feynman.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 题库题目。种子来自 assets，可增量更新。
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
    val followups: String,
    val grader: String,
    val taisho: String,
    val edition: String,
    val bankVersion: Int = 1
)

/**
 * 一节课。费曼闭环的容器：立题 → 初讲 → 被追问 → 认领卡点 → 重讲。
 * 全程留痕，原话永不删改。
 */
@Entity(
    tableName = "session",
    indices = [Index("questionId"), Index("startedAt"), Index("finishedAt")]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val questionId: String,
    val topic: String,
    val prompt: String,
    val anchor: String,
    val edition: String,
    /** 你这次站在哪条路径上讲：中观/唯识/如来藏/不限 */
    val path: String,
    /** INTRO / ASK / CLAIM / REVISIT / REWRITE / DONE */
    val stage: String,
    /** 初讲原话，逐字保存 */
    val firstExplanation: String,
    /** 问答序列 JSON：[{q, a, options}] */
    val dialogueJson: String,
    /** 重讲原话 */
    val revisedExplanation: String,
    /** 明辨的定性反馈（不再打分）*/
    val feedback: String,
    val startedAt: Long,
    val finishedAt: Long,
    val updatedAt: Long
)

/**
 * 理解卡 —— 学习体系的核心资产。
 *
 * 与旧的"疑点"根本区别：卡点由**你自己认领**，不是你被 AI 判定有问题。
 * 记录的是你自己的困境与突破，复习时亮出来的是你自己的原话。
 */
@Entity(
    tableName = "insight",
    indices = [Index("sessionId"), Index("nextReviewAt"), Index("status")]
)
data class InsightEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val questionId: String,
    val topic: String,
    /** 选定的路径：中观/唯识/如来藏/不限 */
    val path: String,
    /** 你怎么卡的 —— 你自己的话 */
    val stuckAt: String,
    /** 触发这个卡点的 AI 追问原话（可空）*/
    val triggerQuestion: String,
    /** 当时你怎么讲的 —— 原话快照，用于日后对照 */
    val contextSnapshot: String,
    /** 重讲之后，这个卡点解决了没有 */
    val resolution: String,
    /** 状态：OPEN=待重讲  RESOLVED=已想通  PARKED=搁置 */
    val status: String,
    /** 语言到不了的地方（诚实记录天花板，可空）*/
    val unspeakable: String,
    val createdAt: Long,
    val resolvedAt: Long,
    /** SRS */
    val nextReviewAt: Long = 0,
    val intervalDays: Int = 0,
    val ease: Float = 2.5f,
    val streak: Int = 0
)

/** 复习日志：每次重讲结果，用于调参加历史回溯 */
@Entity(tableName = "review_log", indices = [Index("insightId"), Index("reviewedAt")])
data class ReviewLogEntity(
    @PrimaryKey val id: String,
    val insightId: String,
    /** 1=还是卡 2=说清了一些 3=讲通了 4=很熟练 */
    val grade: Int,
    val reviewedAt: Long,
    val prevIntervalDays: Int,
    val newIntervalDays: Int,
    /** 这次复习时你的重讲原话 */
    val retell: String
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String
)
