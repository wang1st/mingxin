package com.mingxin.feynman.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 完整数据导出。愿景要求"数据是资产、可迁移"，
 * 所以导出必须自包含、可读、可再导入。
 */
object Exporter {

    private fun dateTime(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    private fun date(ts: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

    /** 全量 JSON：题目快照 + 全部尝试 + 全部疑点 */
    suspend fun toJson(repo: Repository): String {
        val questions = JSONArray()
        repo.allQuestions().forEach { q ->
            questions.put(
                JSONObject()
                    .put("id", q.id).put("sutra", q.sutra).put("sutraCode", q.sutraCode)
                    .put("topic", q.topic).put("difficulty", q.difficulty)
                    .put("question", q.question).put("anchor", q.anchor)
                    .put("keyPoints", q.keyPoints).put("misconception", q.misconception)
                    .put("taisho", q.taisho).put("edition", q.edition)
            )
        }

        val attempts = JSONArray()
        repo.allAttempts().forEach { a ->
            attempts.put(
                JSONObject()
                    .put("id", a.id).put("questionId", a.questionId).put("topic", a.topic)
                    .put("prompt", a.prompt).put("anchor", a.anchor).put("edition", a.edition)
                    .put("stage", a.stage)
                    .put("firstExplanation", a.firstExplanation)
                    .put("dialogue", runCatching { JSONArray(a.dialogueJson) }.getOrDefault(JSONArray()))
                    .put("evaluation", runCatching { JSONObject(a.evaluationJson) }.getOrDefault(JSONObject()))
                    .put("revisedExplanation", a.revisedExplanation)
                    .put("finalFeedback", a.finalFeedback)
                    .put("startedAt", a.startedAt).put("finishedAt", a.finishedAt)
                    .put("updatedAt", a.updatedAt)
            )
        }

        val doubts = JSONArray()
        repo.allDoubts().forEach { d ->
            doubts.put(
                JSONObject()
                    .put("id", d.id).put("text", d.text).put("questionId", d.questionId)
                    .put("topic", d.topic).put("createdAt", d.createdAt)
                    .put("resolved", d.resolved).put("resolvedAt", d.resolvedAt)
                    .put("nextReviewAt", d.nextReviewAt).put("intervalDays", d.intervalDays)
                    .put("ease", d.ease.toDouble()).put("streak", d.streak)
            )
        }

        return JSONObject()
            .put("schema", 1)
            .put("app", "mingxin")
            .put("exportedAt", System.currentTimeMillis())
            .put("questions", questions)
            .put("attempts", attempts)
            .put("doubts", doubts)
            .toString(2)
    }

    /** 可读 Markdown：方便用户自己收藏、打印、迁移到笔记软件 */
    suspend fun toMarkdown(repo: Repository): String {
        val attempts = repo.allAttempts().filter { it.finishedAt > 0 }
        val doubts = repo.allDoubts()
        val sb = StringBuilder()

        sb.appendLine("# 明心研习记录")
        sb.appendLine()
        sb.appendLine("- 导出时间：${dateTime(System.currentTimeMillis())}")
        sb.appendLine("- 完成研习：${attempts.size} 次")
        sb.appendLine("- 疑点：${doubts.size} 条（未决 ${doubts.count { !it.resolved }}）")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        attempts.groupBy { it.topic }.forEach { (topic, list) ->
            sb.appendLine("## $topic")
            sb.appendLine()
            list.sortedBy { it.finishedAt }.forEach { a ->
                sb.appendLine("### ${date(a.finishedAt)} · ${a.prompt.ifBlank { a.topic }}")
                sb.appendLine()
                if (a.firstExplanation.isNotBlank()) {
                    sb.appendLine("**我的首次讲解**")
                    sb.appendLine()
                    sb.appendLine(a.firstExplanation)
                    sb.appendLine()
                }
                val ev = runCatching { JSONObject(a.evaluationJson) }.getOrNull()
                if (ev != null && a.evaluationJson.isNotBlank()) {
                    sb.appendLine(
                        "**评估**：准确 ${ev.optInt("accuracy")} · 逻辑 ${ev.optInt("logic")} · " +
                            "清晰 ${ev.optInt("clarity")} · 应用 ${ev.optInt("application")}"
                    )
                    sb.appendLine()
                    val issues = ev.optString("issues").trim()
                    if (issues.isNotBlank()) {
                        sb.appendLine("> 仍存在的关键疑点：$issues")
                        sb.appendLine()
                    }
                }
                if (a.revisedExplanation.isNotBlank()) {
                    sb.appendLine("**第二次讲解**")
                    sb.appendLine()
                    sb.appendLine(a.revisedExplanation)
                    sb.appendLine()
                }
                if (a.finalFeedback.isNotBlank()) {
                    sb.appendLine("**助教总结**")
                    sb.appendLine()
                    sb.appendLine(a.finalFeedback)
                    sb.appendLine()
                }
                sb.appendLine("---")
                sb.appendLine()
            }
        }

        if (doubts.isNotEmpty()) {
            sb.appendLine("## 疑点清单")
            sb.appendLine()
            doubts.forEach { d ->
                sb.appendLine("- [${if (d.resolved) "x" else " "}] ${d.text}")
                sb.appendLine("  - ${d.topic}")
            }
        }
        return sb.toString()
    }

    /** 写入缓存目录，供 FileProvider 分享 */
    fun writeTemp(ctx: Context, name: String, content: String): File {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        return File(dir, name).apply { writeText(content, Charsets.UTF_8) }
    }

    fun stampName(prefix: String, ext: String): String =
        "${prefix}-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())}.$ext"
}
