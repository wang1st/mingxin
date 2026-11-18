package com.mingxin.feynman.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mingxin.feynman.DeepSeekClient
import com.mingxin.feynman.SecretStore
import com.mingxin.feynman.Tutors
import com.mingxin.feynman.data.InsightEntity
import com.mingxin.feynman.data.QuestionEntity
import com.mingxin.feynman.data.Repository
import com.mingxin.feynman.data.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一轮问答 */
data class QaItem(val question: String, val answer: String, val options: List<String> = emptyList())

/**
 * 一节课的 UI 状态。
 *
 * 阶段流转：INTRO（初讲）→ ASK（被追问）→ CLAIM（认领卡点）→ REVISIT（查看/重讲）→ DONE
 */
data class SessionUi(
    val sessionId: String = "",
    val topic: String = "",
    val prompt: String = "",
    val anchor: String = "",
    val edition: String = "",
    val questionId: String = "",
    /** 本次站在哪条路径上讲 */
    val path: String = "不限",
    val stage: String = "INTRO",
    val firstExplanation: String = "",
    val dialogue: List<QaItem> = emptyList(),
    val currentQuestion: String = "",
    val currentOptions: List<String> = emptyList(),
    val revisedExplanation: String = "",
    val feedback: String = "",
    val startedAt: Long = 0,
    val updatedAt: Long = 0
) {
    val isActive: Boolean get() = sessionId.isNotBlank() && stage != "DONE"
}

class LessonViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)
    private val secrets = SecretStore(app)
    private val client = DeepSeekClient()

    private val _session = MutableStateFlow(SessionUi())
    val session: StateFlow<SessionUi> = _session.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _questions = MutableStateFlow<List<QuestionEntity>>(emptyList())
    val questions: StateFlow<List<QuestionEntity>> = _questions.asStateFlow()

    private val _dueCount = MutableStateFlow(0)
    val dueCount: StateFlow<Int> = _dueCount.asStateFlow()

    /** 本节已认领的理解卡 */
    private val _sessionInsights = MutableStateFlow<List<InsightEntity>>(emptyList())
    val sessionInsights: StateFlow<List<InsightEntity>> = _sessionInsights.asStateFlow()

    val sessions: StateFlow<List<SessionEntity>> =
        repo.sessions.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val insights: StateFlow<List<InsightEntity>> =
        repo.insights.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            // 初始化失败必须暴露出来，否则界面会永远白屏
            runCatching {
                repo.initialize()
                repo.scheduleUnscheduled()
                _questions.value = repo.allQuestions()
                _hasKey.value = secrets.hasKey()
                repo.activeSession()?.let { _session.value = it.toUi() }
                refreshDue()
            }.onFailure {
                _error.value = "初始化失败：${it.message ?: it.javaClass.simpleName}"
            }
            _ready.value = true
        }
    }

    // ---------------- 密钥 ----------------

    fun saveKey(key: String) {
        val t = key.trim()
        if (t.isBlank()) { _error.value = "请填写 API Key"; return }
        runCatching { secrets.save(t) }
            .onSuccess { _hasKey.value = true; _error.value = "" }
            .onFailure { _error.value = "保存失败：${it.localizedMessage}" }
    }

    fun deleteKey() { secrets.delete(); _hasKey.value = false }
    fun clearError() { _error.value = "" }
    fun repositoryForExport(): Repository = repo

    // ---------------- 开课 ----------------

    fun startQuestion(q: QuestionEntity, path: String) = startNew(
        questionId = q.id, topic = "${q.sutra} · ${q.topic}", prompt = q.question,
        anchor = q.anchor, edition = q.edition, path = path
    )

    fun startCustom(topic: String, path: String) = startNew(
        questionId = "", topic = topic.trim(), prompt = "", anchor = "", edition = "", path = path
    )

    fun openSession(s: SessionEntity) {
        _session.value = s.toUi()
    }

    private fun startNew(questionId: String, topic: String, prompt: String, anchor: String, edition: String, path: String) {
        val now = System.currentTimeMillis()
        _session.value = SessionUi(
            sessionId = UUID.randomUUID().toString(), topic = topic, prompt = prompt,
            anchor = anchor, edition = edition, questionId = questionId,
            path = path, stage = "INTRO", startedAt = now, updatedAt = now
        )
        _error.value = ""
        persist()
    }

    fun abandonCurrent() {
        val s = _session.value
        if (s.sessionId.isBlank()) return
        viewModelScope.launch {
            repo.deleteSession(s.sessionId)
            _session.value = SessionUi()
        }
    }

    // ---------------- 主流程 ----------------

    /** ① 初讲提交 → 请善问追问 */
    fun submitFirstExplanation(text: String) {
        val s = _session.value
        if (s.sessionId.isBlank() || _busy.value || text.isBlank()) return
        val key = secrets.read() ?: run { _error.value = "请先在设置中配置 API Key"; return }
        _error.value = ""
        _busy.value = true
        viewModelScope.launch {
            try {
                val next = s.copy(firstExplanation = text.trim(), updatedAt = System.currentTimeMillis())
                _session.value = next
                persist()
                askStudent(next, key)
            } catch (t: Exception) {
                _error.value = t.localizedMessage ?: "请求失败，请重试"
            } finally { _busy.value = false }
        }
    }

    /** ② 回答追问 → 继续被追问，或进入"认领卡点" */
    fun answerQuestion(text: String) {
        val s = _session.value
        if (s.sessionId.isBlank() || _busy.value || text.isBlank()) return
        val key = secrets.read() ?: run { _error.value = "请先在设置中配置 API Key"; return }
        _error.value = ""
        _busy.value = true
        viewModelScope.launch {
            try {
                val qa = QaItem(s.currentQuestion, text.trim(), s.currentOptions)
                val next = s.copy(
                    dialogue = s.dialogue + qa,
                    currentQuestion = "", currentOptions = emptyList(),
                    updatedAt = System.currentTimeMillis()
                )
                _session.value = next
                persist()
                // 已经问过 2 轮就主动把主动权交给用户去认领卡点
                if (next.dialogue.size >= 3) {
                    goToClaim()
                } else {
                    askStudent(next, key)
                }
            } catch (t: Exception) {
                _error.value = t.localizedMessage ?: "请求失败，请重试"
            } finally { _busy.value = false }
        }
    }

    /** 随时可以提前进入认领阶段（用户觉得够了） */
    fun goToClaim() {
        val next = _session.value.copy(stage = "CLAIM", currentQuestion = "", currentOptions = emptyList(),
            updatedAt = System.currentTimeMillis())
        _session.value = next
        persist()
        loadSessionInsights(next.sessionId)
    }

    /**
     * ③ 认领卡点。这是整个体系的核心：由用户选定"哪里卡住了"。
     * 可以一次认领多个，也可以一个都不认领（说明真的讲清了）。
     */
    fun claimInsight(stuckAt: String, triggerQuestion: String) {
        val s = _session.value
        if (s.sessionId.isBlank() || stuckAt.isBlank()) return
        viewModelScope.launch {
            val snapshot = s.firstExplanation
            repo.claimInsight(
                sessionId = s.sessionId,
                questionId = s.questionId,
                topic = s.topic,
                path = s.path,
                stuckAt = stuckAt,
                triggerQuestion = triggerQuestion,
                contextSnapshot = snapshot
            )
            loadSessionInsights(s.sessionId)
            refreshDue()
        }
    }

    /** 认领完毕 → 进入重讲 */
    fun finishClaiming() {
        val next = _session.value.copy(stage = "REVISIT", updatedAt = System.currentTimeMillis())
        _session.value = next
        persist()
    }

    /** ④ 重讲：针对自己认领的卡点重新讲一遍 */
    fun submitRevision(text: String) {
        val s = _session.value
        if (s.sessionId.isBlank() || _busy.value || text.isBlank()) return
        val key = secrets.read() ?: run { _error.value = "请先在设置中配置 API Key"; return }
        _error.value = ""
        _busy.value = true
        viewModelScope.launch {
            try {
                val next = s.copy(revisedExplanation = text.trim(), updatedAt = System.currentTimeMillis())
                _session.value = next
                persist()

                // 明辨：定性判断卡点是否真解决（不打分）
                val raw = client.chat(
                    key = key, system = Tutors.discriminator,
                    input = buildString {
                        appendLine("本次路径：${next.path}")
                        appendLine("研习问题：${next.prompt.ifBlank { next.topic }}")
                        appendLine("用户初讲：${next.firstExplanation}")
                        val claims = _sessionInsights.value
                        if (claims.isNotEmpty()) {
                            appendLine("用户认领的卡点：")
                            claims.forEachIndexed { i, c ->
                                appendLine("  ${i + 1}. ${c.stuckAt}")
                            }
                        }
                        appendLine("用户重讲：${next.revisedExplanation}")
                    },
                    json = false, maxTokens = 1200
                )
                val done = next.copy(stage = "DONE", feedback = raw, updatedAt = System.currentTimeMillis())
                _session.value = done
                persist(finished = true)
                loadSessionInsights(done.sessionId)
                refreshDue()
            } catch (t: Exception) {
                _error.value = t.localizedMessage ?: "请求失败，请重试"
            } finally { _busy.value = false }
        }
    }

    private suspend fun askStudent(s: SessionUi, key: String) {
        val ref = bankRef(s.questionId)
        val raw = client.chat(
            key = key, system = Tutors.studentChoice,
            input = buildString {
                appendLine("本次路径：${s.path}")
                appendLine("主题：${s.topic}")
                if (s.prompt.isNotBlank()) appendLine("研习问题：${s.prompt}")
                appendLine("用户初讲：${s.firstExplanation}")
                s.dialogue.forEachIndexed { i, qa ->
                    appendLine("第${i + 1}问：${qa.question}")
                    if (qa.options.isNotEmpty()) appendLine("候选：${qa.options.joinToString(" / ")}")
                    appendLine("用户答：${qa.answer}")
                }
                appendLine(ref)
                appendLine("请以学生身份提出一个追问（选择题形式）。")
            },
            json = true, maxTokens = 480
        )
        val j = JSONObject(raw)
        if (j.optBoolean("done", false)) {
            goToClaim()
            return
        }
        val q = j.optString("question").trim()
        require(q.isNotBlank()) { "AI 没有返回有效问题，请重试。" }
        val arr = j.optJSONArray("options")
        val opts = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it).trim() }
        val next = s.copy(
            stage = "ASK", currentQuestion = q, currentOptions = opts,
            updatedAt = System.currentTimeMillis()
        )
        _session.value = next
        persist()
    }

    // ---------------- 理解卡 ----------------

    fun loadSessionInsights(sessionId: String) {
        viewModelScope.launch {
            _sessionInsights.value = repo.allInsights().filter { it.sessionId == sessionId }
        }
    }

    fun reviewInsight(insight: InsightEntity, grade: Int, retell: String) {
        viewModelScope.launch {
            repo.reviewInsight(insight, grade, retell)
            refreshDue()
        }
    }

    fun parkInsight(insight: InsightEntity) {
        viewModelScope.launch { repo.parkInsight(insight); refreshDue() }
    }

    fun reopenInsight(insight: InsightEntity) {
        viewModelScope.launch { repo.reopenInsight(insight); refreshDue() }
    }

    fun deleteInsight(insight: InsightEntity) {
        viewModelScope.launch { repo.deleteInsight(insight.id); refreshDue() }
    }

    fun refreshDue() {
        viewModelScope.launch { _dueCount.value = repo.dueCount() }
    }

    // ---------------- 持久化 ----------------

    private fun persist(finished: Boolean = false) {
        val s = _session.value
        if (s.sessionId.isBlank()) return
        viewModelScope.launch {
            repo.saveSession(
                SessionEntity(
                    id = s.sessionId, questionId = s.questionId, topic = s.topic,
                    prompt = s.prompt, anchor = s.anchor, edition = s.edition, path = s.path,
                    stage = s.stage, firstExplanation = s.firstExplanation,
                    dialogueJson = dialogueToJson(s.dialogue),
                    revisedExplanation = s.revisedExplanation, feedback = s.feedback,
                    startedAt = s.startedAt,
                    finishedAt = if (finished || s.stage == "DONE") System.currentTimeMillis() else 0,
                    updatedAt = s.updatedAt
                )
            )
        }
    }

    private fun bankRef(questionId: String): String {
        if (questionId.isBlank()) return ""
        return Tutors.bankRef(_questions.value.firstOrNull { it.id == questionId })
    }

    private fun dialogueToJson(items: List<QaItem>): String {
        val arr = JSONArray()
        items.forEach { qa ->
            arr.put(JSONObject().put("q", qa.question).put("a", qa.answer)
                .put("opts", JSONArray().apply { qa.options.forEach { put(it) } }))
        }
        return arr.toString()
    }

    private fun SessionEntity.toUi(): SessionUi {
        val items = runCatching { JSONArray(dialogueJson) }.getOrDefault(JSONArray())
        val dialogue = (0 until items.length()).map { i ->
            val j = items.optJSONObject(i) ?: JSONObject()
            val opts = j.optJSONArray("opts")
            QaItem(
                j.optString("q"), j.optString("a"),
                if (opts == null) emptyList() else (0 until opts.length()).map { opts.optString(it) }
            )
        }
        return SessionUi(
            sessionId = id, topic = topic, prompt = prompt, anchor = anchor, edition = edition,
            questionId = questionId, path = path, stage = stage,
            firstExplanation = firstExplanation, dialogue = dialogue,
            revisedExplanation = revisedExplanation, feedback = feedback,
            startedAt = startedAt, updatedAt = updatedAt
        )
    }
}
