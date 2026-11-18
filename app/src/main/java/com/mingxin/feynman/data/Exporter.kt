package com.mingxin.feynman.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据导出。数据是资产，必须自包含、可读、可带走。
 */
object Exporter {

    private fun dateTime(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    private fun date(ts: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

    suspend fun toJson(repo: Repository): String {
        val questions = JSONArray()
        repo.allQuestions().forEach { q ->
            questions.put(JSONObject()
                .put("id", q.id).put("sutra", q.sutra).put("topic", q.topic)
                .put("difficulty", q.difficulty).put("question", q.question)
                .put("anchor", q.anchor).put("taisho", q.taisho).put("edition", q.edition))
        }

        val sessions = JSONArray()
        repo.allSessions().forEach { s ->
            sessions.put(JSONObject()
                .put("id", s.id).put("questionId", s.questionId).put("topic", s.topic)
                .put("prompt", s.prompt).put("path", s.path).put("stage", s.stage)
                .put("firstExplanation", s.firstExplanation)
                .put("dialogue", runCatching { JSONArray(s.dialogueJson) }.getOrDefault(JSONArray()))
                .put("revisedExplanation", s.revisedExplanation)
                .put("feedback", s.feedback)
                .put("startedAt", s.startedAt).put("finishedAt", s.finishedAt))
        }

        val insights = JSONArray()
        repo.allInsights().forEach { i ->
            insights.put(JSONObject()
                .put("id", i.id).put("sessionId", i.sessionId).put("questionId", i.questionId)
                .put("topic", i.topic).put("path", i.path)
                .put("stuckAt", i.stuckAt).put("triggerQuestion", i.triggerQuestion)
                .put("contextSnapshot", i.contextSnapshot).put("resolution", i.resolution)
                .put("status", i.status).put("unspeakable", i.unspeakable)
                .put("createdAt", i.createdAt).put("resolvedAt", i.resolvedAt)
                .put("nextReviewAt", i.nextReviewAt).put("intervalDays", i.intervalDays)
                .put("ease", i.ease.toDouble()).put("streak", i.streak))
        }

        return JSONObject()
            .put("schema", 2)
            .put("app", "mingxin")
            .put("exportedAt", System.currentTimeMillis())
            .put("questions", questions)
            .put("sessions", sessions)
            .put("insights", insights)
            .toString(2)
    }

    /**
     * 可读 Markdown。核心是让人看见自己的轨迹：
     * 初讲 → 卡在哪 → 查了什么 → 重讲成什么样。
     */
    suspend fun toMarkdown(repo: Repository): String {
        val sessions = repo.allSessions().filter { it.finishedAt > 0 }
        val insights = repo.allInsights()
        val byId = insights.groupBy { it.sessionId }
        val sb = StringBuilder()

        sb.appendLine("# 明心研习记录")
        sb.appendLine()
        sb.appendLine("- 导出时间：${dateTime(System.currentTimeMillis())}")
        sb.appendLine("- 完成研习：${sessions.size} 节课")
        sb.appendLine("- 理解卡：${insights.size} 张（已想通 ${insights.count { it.status == "RESOLVED" }}）")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        sessions.groupBy { it.topic }.forEach { (topic, list) ->
            sb.appendLine("## $topic")
            sb.appendLine()
            list.sortedBy { it.finishedAt }.forEach { s ->
                sb.appendLine("### ${date(s.finishedAt)} · ${s.prompt.ifBlank { s.topic }}")
                sb.appendLine()
                sb.appendLine("_路径：${s.path}_")
                sb.appendLine()
                if (s.firstExplanation.isNotBlank()) {
                    sb.appendLine("**我的初讲**")
                    sb.appendLine()
                    sb.appendLine("> " + s.firstExplanation.replace("\n", "\n> "))
                    sb.appendLine()
                }
                val cards = byId[s.id].orEmpty()
                if (cards.isNotEmpty()) {
                    sb.appendLine("**我卡在哪里**")
                    sb.appendLine()
                    cards.forEach { c ->
                        sb.appendLine("- ${c.stuckAt}")
                        if (c.triggerQuestion.isNotBlank()) {
                            sb.appendLine("  - 起因：${c.triggerQuestion}")
                        }
                    }
                    sb.appendLine()
                }
                if (s.revisedExplanation.isNotBlank()) {
                    sb.appendLine("**我的重讲**")
                    sb.appendLine()
                    sb.appendLine("> " + s.revisedExplanation.replace("\n", "\n> "))
                    sb.appendLine()
                }
                if (s.feedback.isNotBlank()) {
                    sb.appendLine("**明辨的判断**")
                    sb.appendLine()
                    sb.appendLine(s.feedback)
                    sb.appendLine()
                }
                sb.appendLine("---")
                sb.appendLine()
            }
        }

        if (insights.isNotEmpty()) {
            sb.appendLine("## 理解卡清单")
            sb.appendLine()
            insights.forEach { i ->
                val mark = if (i.status == "RESOLVED") "x" else " "
                sb.appendLine("- [$mark] ${i.stuckAt}  ")
                sb.appendLine("  - ${i.path} · ${i.topic} · " +
                    when (i.status) {
                        "RESOLVED" -> "已想通"
                        "PARKED" -> "已搁置"
                        else -> "待重讲（间隔 ${i.intervalDays} 天）"
                    })
                if (i.resolution.isNotBlank()) {
                    sb.appendLine("  - 后来我这样讲：${i.resolution}")
                }
            }
        }
        return sb.toString()
    }

    fun writeTemp(ctx: Context, name: String, content: String): File {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        return File(dir, name).apply { writeText(content, Charsets.UTF_8) }
    }

    fun stampName(prefix: String, ext: String): String =
        "${prefix}-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())}.$ext"
}
