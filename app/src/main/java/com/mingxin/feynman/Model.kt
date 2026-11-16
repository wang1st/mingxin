package com.mingxin.feynman

import org.json.JSONArray
import org.json.JSONObject

data class QA(val question: String, val answer: String) {
    fun toJson() = JSONObject().put("q", question).put("a", answer)
    companion object {
        fun fromJson(j: JSONObject) = QA(j.optString("q"), j.optString("a"))
    }
}

data class Lesson(
    val topic: String = "",
    val stage: String = "INTRO", // INTRO ASK REVIEW REWRITE DONE
    val firstExplanation: String = "",
    val dialogue: List<QA> = emptyList(),
    val currentQuestion: String = "",
    val evaluationJson: String = "",
    val revisedExplanation: String = "",
    val finalFeedback: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("topic", topic).put("stage", stage)
        .put("firstExplanation", firstExplanation)
        .put("dialogue", JSONArray().apply { dialogue.forEach { put(it.toJson()) } })
        .put("currentQuestion", currentQuestion)
        .put("evaluationJson", evaluationJson)
        .put("revisedExplanation", revisedExplanation)
        .put("finalFeedback", finalFeedback)
        .put("updatedAt", updatedAt)
    companion object {
        fun fromJson(j: JSONObject): Lesson {
            val items = j.optJSONArray("dialogue") ?: JSONArray()
            return Lesson(
                topic = j.optString("topic"),
                stage = j.optString("stage", "INTRO"),
                firstExplanation = j.optString("firstExplanation"),
                dialogue = (0 until items.length()).map { QA.fromJson(items.getJSONObject(it)) },
                currentQuestion = j.optString("currentQuestion"),
                evaluationJson = j.optString("evaluationJson"),
                revisedExplanation = j.optString("revisedExplanation"),
                finalFeedback = j.optString("finalFeedback"),
                updatedAt = j.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

val SUBJECTS = listOf(
    "无我与五蕴", "应无所住而生其心", "缘起与性空", "菩萨为何不住相布施",
    "阿赖耶识与末那识", "唯识无境", "真如与阿赖耶识", "佛法如何面对考试成败"
)
