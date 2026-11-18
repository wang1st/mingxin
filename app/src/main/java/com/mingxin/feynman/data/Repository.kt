package com.mingxin.feynman.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 数据的唯一入口。所有读写经由这里，UI/ViewModel 不直接碰 DB 或 prefs。
 * 旧版 SharedPreferences（mingxin_lesson）在这里做一次性迁移。
 */
class Repository(private val ctx: Context) {

    private val db = MingXinDb.get(ctx)
    private val questionDao = db.questionDao()
    private val attemptDao = db.attemptDao()
    private val doubtDao = db.doubtDao()
    private val reviewLogDao = db.reviewLogDao()
    private val metaDao = db.metaDao()

    val attempts = attemptDao
    val doubts = doubtDao

    // ---------------- 初始化 / 迁移 ----------------

    suspend fun initialize() {
        seedQuestions()
        if (metaDao.get(KEY_MIGRATED) == null) migrateLegacy()
        scheduleUnscheduledDoubts()
    }

    /**
     * 兜底自愈：未决但没排程的疑点（旧版本迁入 / 极端情况）立即进入复习队列。
     * 幂等，每次启动跑一遍很便宜。
     */
    private suspend fun scheduleUnscheduledDoubts() {
        val now = System.currentTimeMillis()
        doubtDao.all()
            .filter { !it.resolved && it.nextReviewAt == 0L }
            .forEach { d ->
                doubtDao.upsert(d.copy(nextReviewAt = now, intervalDays = 1, ease = 2.5f))
            }
    }

    /** 从 assets 导入题库；已有则按 id REPLACE 做增量更新（经文修订随版本下发） */
    private suspend fun seedQuestions() {
        val raw = ctx.assets.open("bank.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(raw).getJSONArray("questions")
        val items = ArrayList<QuestionEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val fu = j.optJSONArray("followups")
            val followups = if (fu == null) "" else
                (0 until fu.length()).joinToString("\n") { fu.optString(it) }
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
                followups = followups,
                grader = j.optString("grader"),
                taisho = j.optString("taisho"),
                edition = j.optString("edition")
            )
        }
        // 题库内容以 assets 为准，每次启动 REPLACE 保证经文修订能随版本下发
        questionDao.upsertAll(items)
        metaDao.put(MetaEntity(KEY_BANK_COUNT, items.size.toString()))
    }

    /**
     * 一次性迁移旧 SharedPreferences。
     * 旧版：active(单条 Lesson) / completed(最多30条) / doubts。
     * 迁移后写 KEY_MIGRATED，永不重复。
     */
    private suspend fun migrateLegacy() {
        val prefs = ctx.getSharedPreferences("mingxin_lesson", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        runCatching {
            val arr = JSONArray(prefs.getString("completed", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                attemptDao.upsert(
                    AttemptEntity(
                        id = UUID.randomUUID().toString(),
                        questionId = j.optString("questionId"),
                        topic = j.optString("topic"),
                        prompt = j.optString("prompt"),
                        anchor = j.optString("anchor"),
                        edition = j.optString("edition"),
                        stage = "DONE",
                        firstExplanation = j.optString("firstExplanation"),
                        dialogueJson = j.optString("dialogue", "[]"),
                        evaluationJson = j.optString("evaluationJson"),
                        revisedExplanation = j.optString("revisedExplanation"),
                        finalFeedback = j.optString("finalFeedback"),
                        startedAt = j.optLong("updatedAt", now),
                        finishedAt = j.optLong("updatedAt", now),
                        updatedAt = j.optLong("updatedAt", now)
                    )
                )
            }
        }

        runCatching {
            val raw = prefs.getString("active", null)
            if (!raw.isNullOrBlank()) {
                val j = JSONObject(raw)
                if (j.optString("topic").isNotBlank()) {
                    val stage = j.optString("stage", "INTRO")
                    if (stage != "DONE") {
                        attemptDao.upsert(
                            AttemptEntity(
                                id = UUID.randomUUID().toString(),
                                questionId = j.optString("questionId"),
                                topic = j.optString("topic"),
                                prompt = j.optString("prompt"),
                                anchor = j.optString("anchor"),
                                edition = j.optString("edition"),
                                stage = stage,
                                firstExplanation = j.optString("firstExplanation"),
                                dialogueJson = j.optString("dialogue", "[]"),
                                evaluationJson = j.optString("evaluationJson"),
                                revisedExplanation = j.optString("revisedExplanation"),
                                finalFeedback = j.optString("finalFeedback"),
                                startedAt = j.optLong("updatedAt", now),
                                finishedAt = 0,
                                updatedAt = j.optLong("updatedAt", now)
                            )
                        )
                    }
                }
            }
        }

        runCatching {
            val arr = JSONArray(prefs.getString("doubts", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val created = j.optLong("createdAt", now)
                val resolved = j.optBoolean("resolved", false)
                doubtDao.upsert(
                    DoubtEntity(
                        id = UUID.randomUUID().toString(),
                        text = j.optString("text"),
                        questionId = j.optString("questionId"),
                        topic = j.optString("topic"),
                        createdAt = created,
                        resolved = resolved,
                        resolvedAt = j.optLong("resolvedAt", 0),
                        // 未决的旧疑点立即进入复习队列（逾期视为该复习了）
                        nextReviewAt = if (resolved) 0 else now,
                        intervalDays = if (resolved) 0 else 1,
                        ease = 2.5f,
                        streak = 0
                    )
                )
            }
        }

        prefs.edit().putBoolean("legacy_migrated", true).apply()
        metaDao.put(MetaEntity(KEY_MIGRATED, "1"))
    }

    // ---------------- 题库 ----------------

    suspend fun allQuestions(): List<QuestionEntity> = questionDao.all()
    suspend fun questionById(id: String): QuestionEntity? =
        if (id.isBlank()) null else questionDao.byId(id)

    /** 已练过的题库题 id 集合（来自全部完成记录，不是截断的 30 条） */
    suspend fun practicedIds(): Set<String> = attemptDao.practicedQuestionIds().toSet()

    // ---------------- 尝试 ----------------

    suspend fun activeAttempt(): AttemptEntity? = attemptDao.active()

    suspend fun saveAttempt(a: AttemptEntity) = attemptDao.upsert(a)
    suspend fun deleteAttempt(id: String) = attemptDao.delete(id)
    suspend fun allAttempts(): List<AttemptEntity> = attemptDao.all()

    // ---------------- 疑点 ----------------

    suspend fun allDoubts(): List<DoubtEntity> = doubtDao.all()
    suspend fun dueCount(now: Long = System.currentTimeMillis()): Int = doubtDao.dueCount(now)
    suspend fun dueDoubts(now: Long = System.currentTimeMillis()): List<DoubtEntity> =
        doubtDao.due(now)

    suspend fun toggleDoubt(d: DoubtEntity) = doubtDao.upsert(
        d.copy(resolved = !d.resolved, resolvedAt = if (!d.resolved) System.currentTimeMillis() else 0)
    )

    /** 沉淀疑点；同题同文去重（用 id 而非时间戳判定） */
    suspend fun addDoubtIfNew(text: String, questionId: String, topic: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val exists = doubtDao.all().any { it.questionId == questionId && it.text == trimmed }
        if (exists) return
        val now = System.currentTimeMillis()
        doubtDao.upsert(
            DoubtEntity(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                questionId = questionId,
                topic = topic,
                createdAt = now,
                resolved = false,
                resolvedAt = 0,
                // 首次排程：1 天后回来复习，然后由 SRS 按表现拉长
                nextReviewAt = now + 24L * 60 * 60 * 1000,
                intervalDays = 1,
                ease = 2.5f,
                streak = 0
            )
        )
    }

    // ---------------- 复习 ----------------

    suspend fun logReview(log: ReviewLogEntity) = reviewLogDao.insert(log)

    companion object {
        const val KEY_MIGRATED = "legacy_migrated"
        const val KEY_BANK_COUNT = "bank_count"
    }
}
