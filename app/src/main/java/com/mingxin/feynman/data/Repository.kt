package com.mingxin.feynman.data

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * 数据的唯一入口。
 *
 * 与 v0.8 的根本区别：不再有「AI 判定你有问题 → 存疑点」，
 * 只有「你自己认领卡点 → 存理解卡」。
 */
class Repository(private val ctx: Context) {

    private val db = MingXinDb.get(ctx)
    private val questionDao = db.questionDao()
    private val sessionDao = db.sessionDao()
    private val insightDao = db.insightDao()
    private val reviewLogDao = db.reviewLogDao()
    private val metaDao = db.metaDao()

    val sessions = sessionDao
    val insights = insightDao

    suspend fun initialize() {
        seedQuestions()
    }

    private suspend fun seedQuestions() {
        val raw = ctx.assets.open("bank.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(raw).getJSONArray("questions")
        val items = ArrayList<QuestionEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val fu = j.optJSONArray("followups")
            items += QuestionEntity(
                id = j.optString("id"),
                sutra = j.optString("sutra"),
                sutraCode = j.optString("sutraCode"),
                topic = j.optString("topic"),
                difficulty = j.optInt("difficulty", 3),
                question = j.optString("question"),
                anchor = j.optString("anchor"),
                keyPoints = j.optString("keyPoints"),
                misconception = j.optString("misconception"),
                followups = if (fu == null) "" else (0 until fu.length()).joinToString("\n") { fu.optString(it) },
                grader = j.optString("grader"),
                taisho = j.optString("taisho"),
                edition = j.optString("edition")
            )
        }
        questionDao.upsertAll(items)
    }

    // ---------------- 题库 ----------------

    suspend fun allQuestions(): List<QuestionEntity> = questionDao.all()
    suspend fun questionById(id: String): QuestionEntity? = if (id.isBlank()) null else questionDao.byId(id)
    suspend fun practicedIds(): Set<String> = sessionDao.practicedQuestionIds().toSet()

    // ---------------- 课 ----------------

    suspend fun activeSession(): SessionEntity? = sessionDao.active()
    suspend fun saveSession(s: SessionEntity) = sessionDao.upsert(s)
    suspend fun deleteSession(id: String) = sessionDao.delete(id)
    suspend fun allSessions(): List<SessionEntity> = sessionDao.all()

    // ---------------- 理解卡 ----------------

    suspend fun allInsights(): List<InsightEntity> = insightDao.all()
    suspend fun dueCount(now: Long = System.currentTimeMillis()): Int = insightDao.dueCount(now)
    suspend fun dueInsights(now: Long = System.currentTimeMillis()): List<InsightEntity> =
        insightDao.due(now)

    /**
     * 认领一个卡点。卡点文本由用户提供，不是 AI 生成的。
     * 首次排程 1 天后，让用户带着"这个问题"过一夜再回来重讲。
     */
    suspend fun claimInsight(
        sessionId: String,
        questionId: String,
        topic: String,
        path: String,
        stuckAt: String,
        triggerQuestion: String,
        contextSnapshot: String,
        unspeakable: String = ""
    ): InsightEntity {
        val now = System.currentTimeMillis()
        val item = InsightEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            questionId = questionId,
            topic = topic,
            path = path,
            stuckAt = stuckAt.trim(),
            triggerQuestion = triggerQuestion.trim(),
            contextSnapshot = contextSnapshot,
            resolution = "",
            status = "OPEN",
            unspeakable = unspeakable.trim(),
            createdAt = now,
            resolvedAt = 0,
            nextReviewAt = now + 24L * 60 * 60 * 1000,
            intervalDays = 1,
            ease = 2.5f,
            streak = 0
        )
        insightDao.upsert(item)
        return item
    }

    /** 重讲之后更新这张卡：填上你是怎么讲通的 */
    suspend fun resolveInsight(insight: InsightEntity, resolution: String) {
        val now = System.currentTimeMillis()
        insightDao.upsert(
            insight.copy(
                resolution = resolution.trim(),
                status = "RESOLVED",
                resolvedAt = now
            )
        )
    }

    /** 复习打分：1=还是卡 2=说清了一些 3=讲通了 4=很熟练 */
    suspend fun reviewInsight(insight: InsightEntity, grade: Int, retell: String) {
        val r = Scheduler.schedule(insight.nextReviewAt, insight.intervalDays, insight.ease, insight.streak, grade)
        insightDao.upsert(
            insight.copy(
                intervalDays = r.intervalDays,
                ease = r.ease,
                streak = r.streak,
                nextReviewAt = r.dueAt,
                // 复习时若"还是卡"，保持 OPEN；讲通则归档
                status = if (grade >= 3) "RESOLVED" else "OPEN",
                resolvedAt = if (grade >= 3 && insight.resolvedAt == 0L) System.currentTimeMillis() else insight.resolvedAt,
                resolution = if (retell.isNotBlank()) retell.trim() else insight.resolution
            )
        )
        reviewLogDao.insert(
            ReviewLogEntity(
                id = UUID.randomUUID().toString(),
                insightId = insight.id,
                grade = grade,
                reviewedAt = System.currentTimeMillis(),
                prevIntervalDays = insight.intervalDays,
                newIntervalDays = r.intervalDays,
                retell = retell
            )
        )
    }

    suspend fun reviewCount(): Int = reviewLogDao.count()

    suspend fun parkInsight(insight: InsightEntity) = insightDao.upsert(insight.copy(status = "PARKED"))

    suspend fun reopenInsight(insight: InsightEntity) = insightDao.upsert(insight.copy(status = "OPEN"))

    suspend fun deleteInsight(id: String) = insightDao.delete(id)

    /** 兜底：未决但没排程的卡自动入队 */
    suspend fun scheduleUnscheduled() {
        val now = System.currentTimeMillis()
        insightDao.all()
            .filter { it.status == "OPEN" && it.nextReviewAt == 0L }
            .forEach { insightDao.upsert(it.copy(nextReviewAt = now, intervalDays = 1, ease = 2.5f)) }
    }

    /**
     * 一节课的完整原话，作为理解卡的语境快照。
     * 复习时用它对照"我当时是怎么讲的"。
     */
    suspend fun transcriptFor(sessionId: String): String {
        val s = sessionDao.byId(sessionId) ?: return ""
        return buildString {
            if (s.prompt.isNotBlank()) appendLine("问题：${s.prompt}")
            appendLine("我的初讲：${s.firstExplanation}")
            if (s.revisedExplanation.isNotBlank()) appendLine("我的重讲：${s.revisedExplanation}")
        }.trim()
    }

    companion object {
        const val KEY_MIGRATED = "legacy_migrated"
    }
}
