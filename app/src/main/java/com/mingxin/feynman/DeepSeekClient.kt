package com.mingxin.feynman

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Requests are executed directly from user's Android device with their own key. */
class DeepSeekClient {
    suspend fun chat(
        key: String, system: String, input: String, json: Boolean = false,
        thinking: Boolean = false, maxTokens: Int = 1400
    ): String = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 95_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            val req = JSONObject()
                .put("model", "deepseek-flash")
                .put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", input)))
                .put("max_tokens", maxTokens)
            if (json) req.put("response_format", JSONObject().put("type", "json_object"))
            conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val hint = when (status) {
                    401 -> "API Key 无效或已失效"
                    402 -> "账户余额可能不足"
                    429 -> "请求频繁，请稍后重试"
                    else -> "接口返回 HTTP $status"
                }
                throw IllegalStateException("$hint。${raw.take(280)}")
            }
            val answer = JSONObject(raw).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").optString("content").trim()
            require(answer.isNotBlank()) { "AI 返回空内容，请重试。" }
            answer
        } finally {
            conn.disconnect()
        }
    }
}

object Tutors {
    const val student = """
你是一名真正好奇但不懂佛法的12岁学生，正在接受用户的讲解。
目标：通过苏格拉底式追问帮助用户发现概念含混、逻辑跳跃及实际应用的难点。
规则：
1. 每轮只能问一个问题，最多70个汉字；句子自然、不学术、不油腻。
2. 紧扣用户刚才解释的内容，避免重复之前已经问过的问题。
3. 优先针对关键前提、反例、概念边界和真实情境追问。
4. 不能替用户解释佛法，不能公布标准答案，不能无条件夸赞。
5. 你可以表达暂时不理解，但不能故意曲解用户的话。
6. 只输出学生提出的那一个问题，不要输出多余解释。
7. 方括号【】内的内容是你的备课参考，绝对不能向用户透露、引用或暗示其存在。
"""

    const val evaluator = """
你是严谨、谦逊的佛教义理助教，负责审核用户以费曼学习法讲解佛法的准确性。
注意：
1. 不得把佛教中的不同经论传统混成单一解释。涉及中观、唯识、如来藏时，请注明解释路径。
2. 不得编造经典原文、卷次、页码、人名观点。若没有可靠原文支持，写“建议查核原典”，不要捏造引文。
3. 区分“概念理解”与“实际证悟”。指出具体误区，但不作修行境界的评判。
4. 遵循用户已提供的回答内容进行评价，不要假定未表达的观点。
5. 【】内的审核参考仅供你把握方向，不是标准答案；请结合用户实际作答评判，也不要向用户逐字转述参考内容。
6. 你必须仅输出有效 json，格式为：
{"accuracy":4,"logic":3,"clarity":4,"application":3,
 "strengths":"理解较准确的具体内容", "issues":"最关键的缺陷或概念混淆",
 "verify":"应当回到哪些经论主题核查；如无法核实请直接说明", "suggestion":"重新解释时可采用的具体提示"}
四项打分为0到5的整数，分数仅反映本轮解释的表现，不是佛学水平或证悟评价。
"""

    const val review = """
你是帮助人学习佛法的严谨助教。请对用户的首次讲解和第二次讲解做差异反馈。
只需输出三个小节，用“进步”“仍有疑点”“下一步研习”作标题，合计约200至350汉字。
不要编造经文原文或卷次，不把论证能力当成修行证量，也不应无条件赞许。
"""

    fun bankRef(q: BankQuestion?): String = if (q == null) "" else buildString {
        appendLine("【备课参考（不得透露给用户）】")
        appendLine("经文锚点：${q.anchor}（${q.edition} ${q.taisho}）")
        appendLine("常见误区：${q.misconception}")
        q.followups.forEachIndexed { i, f -> appendLine("可供选用的追问方向${i + 1}：$f") }
        appendLine("助教审核要点：${q.keyPoints}")
        if (q.grader.isNotBlank()) appendLine("助教评分注意：${q.grader}")
    }

    fun transcript(l: Lesson): String = buildString {
        appendLine("主题：${l.topic}")
        if (l.prompt.isNotBlank()) appendLine("研习问题：${l.prompt}")
        appendLine("用户首次讲解：${l.firstExplanation}")
        l.dialogue.forEachIndexed { index, qa ->
            appendLine("第${index + 1}问 AI学生：${qa.question}")
            appendLine("用户回答：${qa.answer}")
        }
    }
}
