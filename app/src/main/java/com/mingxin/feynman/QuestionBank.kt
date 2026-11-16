package com.mingxin.feynman

import android.content.Context
import org.json.JSONObject

data class BankQuestion(
    val id: String,
    val sutra: String,
    val sutraCode: String,
    val topic: String,
    val difficulty: Int,
    val question: String,
    val anchor: String,
    val keyPoints: String,
    val misconception: String,
    val followups: List<String>,
    val grader: String,
    val taisho: String,
    val edition: String
)

data class TopicGroup(val sutra: String, val topic: String, val questions: List<BankQuestion>)

/** Offline question bank shipped as a raw asset; no user data is ever written back to it. */
object QuestionBank {
    val questions = mutableListOf<BankQuestion>()
    val sutras = mutableListOf<String>()

    fun load(context: Context) {
        if (questions.isNotEmpty()) return
        val raw = context.assets.open("bank.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(raw).getJSONArray("questions")
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val fu = j.optJSONArray("followups")
            questions += BankQuestion(
                id = j.optString("id"),
                sutra = j.optString("sutra"),
                sutraCode = j.optString("sutraCode"),
                topic = j.optString("topic"),
                difficulty = j.optInt("difficulty", 3),
                question = j.optString("question"),
                anchor = j.optString("anchor"),
                keyPoints = j.optString("keyPoints"),
                misconception = j.optString("misconception"),
                followups = if (fu == null) emptyList()
                    else (0 until fu.length()).map { fu.optString(it) },
                grader = j.optString("grader"),
                taisho = j.optString("taisho"),
                edition = j.optString("edition")
            )
        }
        sutras += questions.map { it.sutra }.distinct()
    }

    fun topicsOf(sutra: String): List<TopicGroup> =
        questions.filter { it.sutra == sutra }
            .groupBy { it.topic }
            .map { (t, qs) -> TopicGroup(sutra, t, qs.sortedBy { it.id }) }

    fun byId(id: String): BankQuestion? = questions.firstOrNull { it.id == id }

    fun randomQuestion(): BankQuestion = questions.random()
}
