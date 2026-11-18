package com.mingxin.feynman.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mingxin.feynman.DeepSeekClient
import com.mingxin.feynman.SecretStore
import com.mingxin.feynman.Tutors
import com.mingxin.feynman.data.AttemptEntity
import com.mingxin.feynman.data.DoubtEntity
import com.mingxin.feynman.data.QuestionEntity
import com.mingxin.feynman.data.Repository
import com.mingxin.feynman.data.ReviewLogEntity
import com.mingxin.feynman.data.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class QaItem(val question: String, val answer: String, val options: List<String> = emptyList())

data class LessonUi(
    val attemptId: String = "",
    val topic: String = "",
    val prompt: String = "",
    val anchor: String = "",
    val edition: String = "",
    val questionId: String = "",
    val stage: String = "INTRO",
    val firstExplanation: String = "",
    val dialogue: List<QaItem> = emptyList(),
    val currentQuestion: String = "",
    val currentOptions: List<String> = emptyList(),
    val evaluationJson: String = "",
    val revisedExplanation: String = "",
    val finalFeedback: String = "",
    val startedAt: Long = 0,
    val updatedAt: Long = 0
) {
    val isActive: Boolean get() = attemptId.isNotBlank() && stage != "DONE"
}

class LessonViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)
    private val secrets = SecretStore(app)
    private val client = DeepSeekClient()

    private val _lesson = MutableStateFlow(LessonUi())
    val lesson: StateFlow<LessonUi> = _lesson.asStateFlow()

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

    /** 今日该复习的疑点数，首页展示 */
    private val _dueCount = MutableStateFlow(0)
    val dueCount: StateFlow<Int> = _dueCount.asStateFlow()

    val attempts: StateFlow<List<AttemptEntity>> =
        repo.attempts.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val doubts: StateFlow<List<DoubtEntity>> =
        repo.doubts.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repo.initialize()
            _questions.value = repo.allQuestions()
            _hasKey.value = secrets.hasKey()
            // 恢复未完成的尝试
            repo.activeAttempt()?.let { _lesson.value = it.toUi() }
            _dueCount.value = repo.dueCount()
            _ready.value = true
        }
    }

    // ---------------- 密钥 ----------------

    fun saveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            _error.value = "请填写 API Key"
            return
        }
        runCatching { secrets.save(trimmed) }
            .onSuccess { _hasKey.value = true; _error.value = "" }
            .onFailure { _error.value = "保存失败：${it.localizedMessage}" }
    }

    fun deleteKey() {
        secrets.delete()
        _hasKey.value = false
    }

    fun clearError() { _error.value = "" }

    // ---------------- 开始研习 ----------------

    /** 从题库题开始 */
    fun startQuestion(q: QuestionEntity) = startNew(
        questionId = q.id, topic = "${q.sutra} · ${q.topic}", prompt = q.question,
        anchor = q.anchor, edition = q.edition
    )

    /** 自定义课题 */
    fun startCustom(topic: String) = startNew(
        questionId = "", topic = topic.trim(), prompt = "", anchor = "", edition = ""
    )

    /** 恢复历史尝试（笔记页点开） */
    fun openAttempt(a: AttemptEntity) {
        _lesson.value = a.toUi()
    }

    private fun startNew(questionId: String, topic: String, prompt: String, anchor: String, edition: String) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        _lesson.value = LessonUi(
            attemptId = id, topic = topic, prompt = prompt, anchor = anchor,
            edition = edition, questionId = questionId, stage = "INTRO",
            startedAt = now, updatedAt = now
        )
        _error.value = ""
        persist()
    }

    /** 放弃当前未完成尝试 */
    fun abandonCurrent() {
        val l = _lesson.value
        if (l.attemptId.isBlank()) return
        viewModelScope.launch {
            repo.deleteAttempt(l.attemptId)
            _lesson.value = LessonUi()
        }
    }

    // ---------------- 对话主流程 ----------------

    fun send(input: String) {
        val l = _lesson.value
        if (l.attemptId.isBlank() || _busy.value || input.isBlank()) return
        val key = secrets.read()
        if (key.isNullOrBlank()) { _error.value = "请先在设置中配置 API Key"; return }

        _error.value = ""
        _busy.value = true
        viewModelScope.launch {
            try {
                when (l.stage) {
                    "INTRO" -> {
                        // 首次讲解 → 保存 → 请 AI 学生追问
                        val next = l.copy(firstExplanation = input, updatedAt = System.currentTimeMillis())
                        _lesson.value = next
                        persist()
                        askStudent(next, key, weakPoint = "")
                    }
                    "ASK" -> {
                        // 回答学生问题 → 进评估
                        val qa = QaItem(l.currentQuestion, input, l.currentOptions)
                        val next = l.copy(
                            dialogue = l.dialogue + qa,
                            currentQuestion = "", currentOptions = emptyList(),
                            updatedAt = System.currentTimeMillis()
                        )
                        _lesson.value = next
                        persist()
                        evaluate(next, key)
                    }
                    "REWRITE" -> {
                        // 第二次讲解 → 总结反馈
                        val next = l.copy(revisedExplanation = input, updatedAt = System.currentTimeMillis())
                        _lesson.value = next
                        persist()
                        finalizeReview(next, key)
                    }
                    else -> Unit
                }
            } catch (t: Exception) {
                _error.value = t.localizedMessage ?: "请求失败，请重试"
            } finally {
                _busy.value = false
            }
        }
    }

    /** 评估薄弱处 → 打回学生再追问一轮 */
    fun loopBack(weakPoint: String) {
        val key = secrets.read() ?: run { _error.value = "请先配置 API Key"; return }
        _error.value = ""
        _busy.value = true
        viewModelScope.launch {
            try {
                val next = _lesson.value.copy(stage = "ASK", updatedAt = System.currentTimeMillis())
                _lesson.value = next
                askStudent(next, key, weakPoint)
            } catch (t: Exception) {
                _error.value = t.localizedMessage ?: "请求失败，请重试"
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun askStudent(l: LessonUi, key: String, weakPoint: String) {
        val ref = bankRef(l.questionId)
        val instruction = if (weakPoint.isBlank())
            "请以学生身份，针对用户的讲解提出一个追问（选择题形式）。"
        else
            "助教指出用户讲解中最薄弱的一处：$weakPoint\n请只针对这一处，以学生身份提出一个追问（选择题形式）。"

        val raw = client.chat(
            key = key, system = Tutors.studentChoice,
            input = transcript(l) + "\n" + ref + "\n" + instruction,
            json = true, maxTokens = 480
        )
        val j = JSONObject(raw)
        if (j.optBoolean("done", false)) {
            // 学生听懂了 → 直接进评估
            evaluate(l, key)
            return
        }
        val q = j.optString("question").trim()
        require(q.isNotBlank()) { "AI 没有返回有效问题，请重试。" }
        val arr = j.optJSONArray("options")
        val opts = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it).trim() }
        val next = l.copy(
            stage = "ASK", currentQuestion = q, currentOptions = opts,
            updatedAt = System.currentTimeMillis()
        )
        _lesson.value = next
        persist()
    }

    private suspend fun evaluate(l: LessonUi, key: String) {
        val raw = client.chat(
            key = key, system = Tutors.evaluator,
            input = transcript(l) + "\n" + bankRef(l.questionId) + "\n现在请按指定 json 结构评价本次讲解。",
            json = true, maxTokens = 1600
        )
        JSONObject(raw) // 校验 JSON 有效
        val next = l.copy(
            stage = "REVIEW", currentQuestion = "", currentOptions = emptyList(),
            evaluationJson = raw, updatedAt = System.currentTimeMillis()
        )
        _lesson.value = next
        persist()
        // 沉淀疑点
        runCatching { JSONObject(raw).optString("issues").trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { repo.addDoubtIfNew(it, l.questionId, l.topic) }
        refreshDue()
    }

    private suspend fun finalizeReview(l: LessonUi, key: String) {
        val raw = client.chat(
            key = key, system = Tutors.review,
            input = transcript(l) + "\n用户第二次讲解：${l.revisedExplanation}",
            json = false, maxTokens = 900
        )
        val now = System.currentTimeMillis()
        val next = l.copy(
            stage = "DONE", finalFeedback = raw,
            updatedAt = now
        )
        _lesson.value = next
        persist(finished = true)
    }

    /** 进入第二次讲解阶段 */
    fun startRewrite() {
        val l = _lesson.value
        if (l.stage != "REVIEW") return
        val next = l.copy(stage = "REWRITE", updatedAt = System.currentTimeMillis())
        _lesson.value = next
        persist()
    }

    // ---------------- 疑点 ----------------

    fun toggleDoubt(d: DoubtEntity) {
        viewModelScope.launch {
            repo.toggleDoubt(d)
            refreshDue()
        }
    }

    /** 疑点复习打分：1=忘记 2=吃力 3=想起 4=轻松 */
    fun reviewDoubt(d: DoubtEntity, grade: Int) {
        viewModelScope.launch {
            val r = Scheduler.schedule(d, grade)
            repo.doubts.upsert(
                d.copy(
                    intervalDays = r.intervalDays, ease = r.ease, streak = r.streak,
                    nextReviewAt = r.dueAt, resolved = false
                )
            )
            repo.logReview(
                ReviewLogEntity(
                    id = UUID.randomUUID().toString(), doubtId = d.id, grade = grade,
                    reviewedAt = System.currentTimeMillis(),
                    prevIntervalDays = d.intervalDays, newIntervalDays = r.intervalDays
                )
            )
            refreshDue()
        }
    }

    /** 疑点卡片"去研习"：从疑点回到原题 */
    fun openDoubtSource(q: QuestionEntity) = startQuestion(q)

    fun refreshDue() {
        viewModelScope.launch { _dueCount.value = repo.dueCount() }
    }

    /** 导出用：Repository 是纯数据入口，不暴露可变状态 */
    fun repositoryForExport(): Repository = repo

    // ---------------- 持久化 ----------------

    private fun persist(finished: Boolean = false) {
        val l = _lesson.value
        if (l.attemptId.isBlank()) return
        viewModelScope.launch {
            repo.saveAttempt(
                AttemptEntity(
                    id = l.attemptId, questionId = l.questionId, topic = l.topic,
                    prompt = l.prompt, anchor = l.anchor, edition = l.edition,
                    stage = l.stage, firstExplanation = l.firstExplanation,
                    dialogueJson = dialogueToJson(l.dialogue),
                    evaluationJson = l.evaluationJson,
                    revisedExplanation = l.revisedExplanation,
                    finalFeedback = l.finalFeedback,
                    startedAt = l.startedAt,
                    finishedAt = if (finished || l.stage == "DONE") System.currentTimeMillis() else 0,
                    updatedAt = l.updatedAt
                )
            )
        }
    }

    // ---------------- 工具 ----------------

    private fun bankRef(questionId: String): String {
        if (questionId.isBlank()) return ""
        val q = _questions.value.firstOrNull { it.id == questionId } ?: return ""
        return Tutors.bankRef(q)
    }

    private fun transcript(l: LessonUi): String = buildString {
        appendLine("主题：${l.topic}")
        if (l.prompt.isNotBlank()) appendLine("研习问题：${l.prompt}")
        appendLine("用户首次讲解：${l.firstExplanation}")
        l.dialogue.forEachIndexed { i, qa ->
            appendLine("第${i + 1}问 AI学生：${qa.question}")
            if (qa.options.isNotEmpty()) appendLine("候选回答：${qa.options.joinToString(" / ")}")
            appendLine("用户回答：${qa.answer}")
        }
    }

    private fun dialogueToJson(items: List<QaItem>): String {
        val arr = JSONArray()
        items.forEach { qa ->
            arr.put(
                JSONObject().put("q", qa.question).put("a", qa.answer)
                    .put("opts", JSONArray().apply { qa.options.forEach { put(it) } })
            )
        }
        return arr.toString()
    }

    private fun AttemptEntity.toUi(): LessonUi {
        val items = runCatching { JSONArray(dialogueJson) }.getOrDefault(JSONArray())
        val dialogue = (0 until items.length()).map { i ->
            val j = items.optJSONObject(i) ?: JSONObject()
            val opts = j.optJSONArray("opts")
            QaItem(
                question = j.optString("q"), answer = j.optString("a"),
                options = if (opts == null) emptyList() else (0 until opts.length()).map { opts.optString(it) }
            )
        }
        return LessonUi(
            attemptId = id, topic = topic, prompt = prompt, anchor = anchor, edition = edition,
            questionId = questionId, stage = stage, firstExplanation = firstExplanation,
            dialogue = dialogue, evaluationJson = evaluationJson,
            revisedExplanation = revisedExplanation, finalFeedback = finalFeedback,
            startedAt = startedAt, updatedAt = updatedAt
        )
    }
}
