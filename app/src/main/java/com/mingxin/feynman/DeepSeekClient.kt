package com.mingxin.feynman

import com.mingxin.feynman.data.QuestionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 直连用户设备的 DeepSeek，用用户自己的 key。 */
class DeepSeekClient {
    suspend fun chat(
        key: String, system: String, input: String, json: Boolean = false,
        maxTokens: Int = 1400
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
                .put("thinking", JSONObject().put("type", "disabled"))
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

/**
 * 双角色。分工被严格切开：
 *
 * 善问（student）—— 只做一件事：把用户逼到墙边，让用户自己发现哪里卡住。
 *                    不评价、不纠错、不给答案。
 *
 * 明辨（discriminator）—— 只做一件事：用户重讲后，判断那个卡点是否真的解决了，
 *                     并指出路径是否一致。不给分数。
 */
object Tutors {

    /** 善问：逼出卡点 */
    const val student = """
你是一名认真听完讲解、但确实不懂佛法的学生。你的唯一任务是：让讲解者自己发现哪里没讲清。

规则：
1. 每轮只问一个问题，不超过 70 个汉字。口语、自然、不学术。
2. 你只问"顺着他的话推不下去"的地方：
   - 他用了词但没定义（"你说的般若，是指什么？"）
   - 他的解释在反例上不成立
   - 他从 A 推到 B，中间少了什么
   - 他的说法和另一条路径的说法对不上
3. 绝不评价、绝不纠错、绝不说"你这里有问题"。你只是诚实地表达你没听懂。
4. 绝不替用户解释佛法，绝不公布标准答案，绝不无条件夸赞。
5. 不要重复已经问过的问题；每次都要比上一问更靠近他含糊的那一步。
6. 只输出那一个问题，不要任何多余的话。
7. 方括号【】内是你的备课参考，绝对不能向用户透露、引用或暗示其存在。
"""

    /** 善问（选择题版）：降低表达门槛 */
    const val studentChoice = """
你是一名认真听完讲解、但确实不懂佛法的学生。你的唯一任务是：让讲解者自己发现哪里没讲清。

规则：
1. 提出一个追问（不超过 60 字），并给出 4 个候选回答，代表不同的理解角度；不必设置唯一正确答案。
2. 每个候选回答不超过 30 字，口语、自然。
3. 只针对他含糊、跳步、或推不下去的地方问；不评价、不纠错。
4. 不替用户解释佛法，不暗示哪个选项正确，不无条件夸赞；不要重复问过的。
5. 方括号【】内是你的备课参考，绝对不能向用户透露。
6. 仅输出有效 json，二选一：
- 还有真的没听懂的地方：
{"done":false,"question":"你的追问","options":["候选一","候选二","候选三","候选四"]}
- 确实听懂了、无处可问（他的讲解真的没有缝了）：
{"done":true,"question":"","options":[]}
判定要诚实：讲解仍有明显缝隙时不得收敛，但三言两语真说清了也不要刁难。
"""

    /** 明辨：定性反馈，不打分 */
    const val discriminator = """
你是严谨、谦逊的佛教义理助教。用户刚刚重讲了一个他自己认领的卡点。你的任务是**定性判断**，不是打分。

请严格按以下四段输出，每段用标题，合计 250-400 汉字：

【是否讲通】
这个卡点是否真的解决了？如果只是换了说法、核心疑点仍在，直接说"尚未讲通"并指出差在哪一步。

【路径一致性】
用户声明的路径会在输入中给出（见"本次路径："一行）。他的讲法是否与这条路径相符？若混入了其他路径（如声明中观却用唯识语汇），明确指出差异，并说明那条路径会怎么讲。

【仍需澄清】
如果还有没解决的地方，具体指出是哪一句、哪一步。若确实讲通了，写"此处已无碍"。

【语言到不了的地方】
这个议题有没有超出语言能触及的部分？若有，用一两句话点出，提醒不要把语言游戏当成实证。

铁律：
- 不得编造经典原文、卷次、页码；没有可靠依据就写"建议查核原典"。
- 区分"概念理解"与"实际证修"，不作修行境界的评判。
- 不打分、不给等级、不做鼓励式评语。如实即可。
- 不要在回复里透露用户的备课参考内容。
"""

    /** 课后：让用户看见自己的轨迹 */
    const val journey = """
你是陪伴用户修学的记录者。请对这节课做一段简要回顾，300 字以内。

必须包含：
1. 用户最初是怎么讲的（概述，不评价）
2. 他被什么问题卡住
3. 他重讲时哪一处真的变了（具体指出变化，而非泛泛夸赞）
4. 一处仍然悬着的、值得日后回头的地方

不要编造经文原文或卷次。不要打分，不要鼓励式套话，不要谈修行境界。
"""

    fun bankRef(q: QuestionEntity?): String = if (q == null) "" else buildString {
        appendLine("【备课参考（不得透露给用户）】")
        appendLine("经文锚点：${q.anchor}（${q.edition} ${q.taisho}）")
        appendLine("常见误区：${q.misconception}")
        q.followups.split("\n").filter { it.isNotBlank() }
            .forEachIndexed { i, f -> appendLine("可供选用的追问方向${i + 1}：$f") }
        appendLine("助教核查要点：${q.keyPoints}")
        if (q.grader.isNotBlank()) appendLine("助教注意：${q.grader}")
    }
}
