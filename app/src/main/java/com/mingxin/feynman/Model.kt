package com.mingxin.feynman

import org.json.JSONArray
import org.json.JSONObject

data class QA(
    val question: String,
    val answer: String,
    val options: List<String> = emptyList()
) {
    fun toJson() = JSONObject().put("q", question).put("a", answer).apply {
        if (options.isNotEmpty()) put("opts", JSONArray().apply { options.forEach { put(it) } })
    }
    companion object {
        fun fromJson(j: JSONObject): QA {
            val arr = j.optJSONArray("opts")
            return QA(
                j.optString("q"), j.optString("a"),
                if (arr == null) emptyList()
                else (0 until arr.length()).map { arr.optString(it) }
            )
        }
    }
}

data class Lesson(
    val topic: String = "",
    val stage: String = "INTRO", // INTRO ASK REVIEW REWRITE DONE
    val firstExplanation: String = "",
    val dialogue: List<QA> = emptyList(),
    val currentQuestion: String = "",
    val currentOptions: List<String> = emptyList(),
    val evaluationJson: String = "",
    val revisedExplanation: String = "",
    val finalFeedback: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    // question-bank link (empty for custom topics)
    val questionId: String = "",
    val prompt: String = "",
    val anchor: String = "",
    val edition: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("topic", topic).put("stage", stage)
        .put("firstExplanation", firstExplanation)
        .put("dialogue", JSONArray().apply { dialogue.forEach { put(it.toJson()) } })
        .put("currentQuestion", currentQuestion)
        .put("currentOptions", JSONArray().apply { currentOptions.forEach { put(it) } })
        .put("evaluationJson", evaluationJson)
        .put("revisedExplanation", revisedExplanation)
        .put("finalFeedback", finalFeedback)
        .put("updatedAt", updatedAt)
        .put("questionId", questionId)
        .put("prompt", prompt)
        .put("anchor", anchor)
        .put("edition", edition)

    companion object {
        fun fromJson(j: JSONObject): Lesson {
            val items = j.optJSONArray("dialogue") ?: JSONArray()
            val opts = j.optJSONArray("currentOptions")
            return Lesson(
                topic = j.optString("topic"),
                stage = j.optString("stage", "INTRO"),
                firstExplanation = j.optString("firstExplanation"),
                dialogue = (0 until items.length()).map { QA.fromJson(items.getJSONObject(it)) },
                currentQuestion = j.optString("currentQuestion"),
                currentOptions = if (opts == null) emptyList()
                    else (0 until opts.length()).map { opts.optString(it) },
                evaluationJson = j.optString("evaluationJson"),
                revisedExplanation = j.optString("revisedExplanation"),
                finalFeedback = j.optString("finalFeedback"),
                updatedAt = j.optLong("updatedAt", System.currentTimeMillis()),
                questionId = j.optString("questionId"),
                prompt = j.optString("prompt"),
                anchor = j.optString("anchor"),
                edition = j.optString("edition")
            )
        }
    }
}
