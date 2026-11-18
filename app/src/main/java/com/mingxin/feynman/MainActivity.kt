package com.mingxin.feynman

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ------------------------------------------------------------------ */
/*  One palette, four named colors; every tint is derived from them.   */
/* ------------------------------------------------------------------ */
private val Paper = Color(0xFFFAF8F3)
private val Ink = Color(0xFF20362E)
private val Jade = Color(0xFF3B7965)
private val JadeDeep = Color(0xFF2F5D4D)
private val JadeBright = Color(0xFF4A8B74)
private val Gold = Color(0xFF9E793E)
private val Vermilion = Color(0xFFB4432F)
private val JadeGrad = listOf(JadeBright, JadeDeep)
private val CardRadius = 20.dp
private val CardShape = RoundedCornerShape(CardRadius)
private val BtnShape = RoundedCornerShape(50)

private val Ink70 = Ink.copy(alpha = 0.70f)
private val GoldText = Color(0xFF84652F)
private val Ink12 = Ink.copy(alpha = 0.12f)
private val Ink8 = Ink.copy(alpha = 0.08f)
private val Jade10 = Jade.copy(alpha = 0.10f)
private val Gold12 = Gold.copy(alpha = 0.12f)
private val Vermilion12 = Vermilion.copy(alpha = 0.12f)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("mingxin_lesson", MODE_PRIVATE) }
    private val secrets by lazy { SecretStore(this) }
    private val client = DeepSeekClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null

    private var page by mutableStateOf("home")
    private var current by mutableStateOf(Lesson())
    private var history by mutableStateOf(listOf<Lesson>())
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf("")
    private var hasApiKey by mutableStateOf(false)
    private var speaking by mutableStateOf("")
    private var doubts by mutableStateOf(listOf<Doubt>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuestionBank.load(this)
        hasApiKey = secrets.hasKey()
        current = runCatching { Lesson.fromJson(JSONObject(prefs.getString("active", "{}") ?: "{}")) }.getOrDefault(Lesson())
        history = runCatching {
            val arr = JSONArray(prefs.getString("completed", "[]") ?: "[]")
            (0 until arr.length()).map { Lesson.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        doubts = runCatching {
            val arr = JSONArray(prefs.getString("doubts", "[]") ?: "[]")
            (0 until arr.length()).map { Doubt.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { speaking = "" }
                    @Deprecated("deprecated in API 21")
                    override fun onError(id: String?) { speaking = "" }
                })
            }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Jade, onPrimary = Color.White, background = Paper,
                surface = Color.White, onSurface = Ink, secondary = Gold,
                error = Vermilion
            )) {
                MainUI(
                    page = page, lesson = current, history = history, busy = busy,
                    error = error, hasKey = hasApiKey, speaking = speaking,
                    onNavigate = { page = it; error = "" },
                    onStartQuestion = ::startQuestion,
                    onCustomTopic = ::startCustom,
                    onResume = { page = "lesson" },
                    onSend = ::send,
                    onRevise = {
                        current = current.copy(stage = "REWRITE", updatedAt = System.currentTimeMillis())
                        persistActive()
                    },
                    onSaveKey = { raw ->
                        if (raw.trim().isBlank()) error = "请输入 API Key"
                        else {
                            runCatching { secrets.save(raw.trim()) }
                                .onSuccess { hasApiKey = true; error = "" }
                                .onFailure { error = "保存失败：${it.localizedMessage}" }
                        }
                    },
                    onDeleteKey = { secrets.delete(); hasApiKey = false },
                    onSpeech = { phrase -> speak(phrase) },
                    onOpenHistory = { selected -> current = selected; page = "lesson" },
                    doubts = doubts,
                    onExportNotes = { exportNotes() },
                    onToggleDoubt = ::toggleDoubt,
                    onLoop = ::loopBack,
                    nextUp = nextUp(current.questionId),
                    onPractice = { startQuestion(nextUp(null) ?: QuestionBank.randomQuestion()) }
                )
            }
        }
    }

    private fun startQuestion(q: BankQuestion) {
        current = Lesson(
            topic = "${q.sutra} · ${q.topic}",
            questionId = q.id, prompt = q.question,
            anchor = q.anchor, edition = q.edition
        )
        persistActive()
        page = "lesson"
    }

    private fun startCustom(topic: String) {
        current = Lesson(topic = topic)
        persistActive()
        page = "lesson"
    }

    private fun persistActive() {
        prefs.edit().putString("active", current.toJson().toString()).apply()
    }

    private fun persistHistory() {
        val arr = JSONArray().apply { history.take(30).forEach { put(it.toJson()) } }
        prefs.edit().putString("completed", arr.toString()).apply()
    }

    private fun bankRef(): String = Tutors.bankRef(QuestionBank.byId(current.questionId))

    /** 派题：未练优先、难度递进；exclude 用于课堂内"下一题"排除当前题。 */
    private fun nextUp(excludeId: String?): BankQuestion? {
        val practiced = history.map { it.questionId }.filter { it.isNotBlank() }.toSet()
        return QuestionBank.questions
            .filter { it.id !in practiced && it.id != excludeId }
            .minByOrNull { it.difficulty }
    }

    private data class Choice(val done: Boolean, val question: String, val options: List<String>)

    private fun parseChoice(raw: String): Choice {
        val j = JSONObject(raw)
        if (j.optBoolean("done", false)) return Choice(true, "", emptyList())
        val q = j.optString("question").trim()
        require(q.isNotBlank()) { "AI 没有返回有效问题，请重试。" }
        val arr = j.optJSONArray("options")
        val opts = if (arr == null) emptyList()
            else (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }
        return Choice(false, q, opts)
    }

    private fun persistDoubts() {
        val arr = JSONArray().apply { doubts.take(200).forEach { put(it.toJson()) } }
        prefs.edit().putString("doubts", arr.toString()).apply()
    }

    /** 原理4：评估中指出的"需要澄清的"自动沉淀为疑点（去重）。 */
    private fun saveDoubtFrom(evaluationJson: String, lesson: Lesson) {
        val issues = runCatching { JSONObject(evaluationJson).optString("issues").trim() }.getOrDefault("")
        if (issues.isBlank()) return
        if (doubts.any { it.questionId == lesson.questionId && it.text == issues }) return
        doubts = listOf(Doubt(issues, lesson.questionId, lesson.topic)) + doubts
        persistDoubts()
    }

    fun toggleDoubt(target: Doubt) {
        doubts = doubts.map {
            if (it.createdAt == target.createdAt && it.text == target.text)
                it.copy(resolved = !it.resolved, resolvedAt = if (!it.resolved) System.currentTimeMillis() else 0)
            else it
        }
        persistDoubts()
    }

    /** 原理3+4：评估完成即沉淀疑点。 */
    private suspend fun runEvaluator(key: String, next: Lesson): Lesson {
        val judgement = client.chat(
            key = key, system = Tutors.evaluator,
            input = Tutors.transcript(next) + "\n" + bankRef() +
                "\n现在请按指定 json 结构评价本次讲解。",
            json = true, maxTokens = 1600
        )
        JSONObject(judgement) // only move to next stage for valid JSON
        saveDoubtFrom(judgement, next)
        return next.copy(stage = "REVIEW", currentQuestion = "", currentOptions = emptyList(), evaluationJson = judgement)
    }

    /** 原理：课堂是回路——明辨的薄弱处把用户打回学生面前。 */
    private fun loopBack(weakPoint: String) {
        val key = secrets.read()
        if (key.isNullOrBlank()) { error = "请先配置 API Key"; return }
        error = ""
        busy = true
        scope.launch {
            try {
                val c = parseChoice(client.chat(
                    key = key, system = Tutors.studentChoice,
                    input = Tutors.transcript(current) + "\n" + bankRef() +
                        "\n助教指出用户讲解中最薄弱的一处：$weakPoint\n" +
                        "请只针对这一处，以学生身份提出一个追问（选择题形式）。",
                    json = true, maxTokens = 480
                ))
                current = current.copy(
                    stage = "ASK", currentQuestion = c.question, currentOptions = c.options,
                    updatedAt = System.currentTimeMillis()
                )
                persistActive()
            } catch (t: Exception) {
                error = t.localizedMessage ?: "请求失败，请重试"
            } finally { busy = false }
        }
    }

    private fun send(input: String) {
        if (busy || input.isBlank()) return
        val key = secrets.read()
        if (key.isNullOrBlank()) {
            error = "先到设置页面填入自己的 DeepSeek API Key"
            return
        }
        error = ""
        busy = true
        scope.launch {
            try {
                when (current.stage) {
                    "INTRO" -> {
                        val next = current.copy(firstExplanation = input, updatedAt = System.currentTimeMillis())
                        val c = parseChoice(client.chat(
                            key = key, system = Tutors.studentChoice,
                            input = Tutors.transcript(next) + "\n" + bankRef() +
                                "\n这是首次讲解，请提出第1个关键追问（选择题形式）。",
                            json = true, maxTokens = 480
                        ))
                        current = if (c.done) runEvaluator(key, next)
                                  else next.copy(stage = "ASK", currentQuestion = c.question, currentOptions = c.options)
                    }
                    "ASK" -> {
                        val addition = QA(current.currentQuestion, input, current.currentOptions)
                        val next = current.copy(dialogue = current.dialogue + addition, updatedAt = System.currentTimeMillis())
                        if (next.dialogue.size >= 4) {
                            current = runEvaluator(key, next)
                        } else {
                            val c = parseChoice(client.chat(
                                key = key, system = Tutors.studentChoice,
                                input = Tutors.transcript(next) + "\n" + bankRef() +
                                    "\n请判断用户的回答是否解除了你的疑惑：已解除则输出 done=true；" +
                                    "未解除则提出下一个追问（选择题形式），不要重复前面的问题。",
                                json = true, maxTokens = 480
                            ))
                            current = if (c.done) runEvaluator(key, next)
                                      else next.copy(currentQuestion = c.question, currentOptions = c.options)
                        }
                    }
                    "REWRITE" -> {
                        val response = client.chat(
                            key = key, system = Tutors.review,
                            input = Tutors.transcript(current) +
                                "\n助教首次评价：${current.evaluationJson}" +
                                "\n用户第二次讲解：$input",
                            maxTokens = 850
                        )
                        current = current.copy(
                            stage = "DONE", revisedExplanation = input,
                            finalFeedback = response, updatedAt = System.currentTimeMillis()
                        )
                        history = listOf(current) + history.filterNot { it.questionId == current.questionId && it.questionId.isNotBlank() }
                        persistHistory()
                    }
                }
                persistActive()
            } catch (t: Exception) {
                error = t.localizedMessage ?: "请求失败，请检查网络及账户余额"
            } finally { busy = false }
        }
    }

    /** 导出全部研习记录为 Markdown，供用户送其他大模型验证。 */
    private fun exportNotes() {
        val md = buildNotesMarkdown()
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        val file = File(dir, "mingxin-notes-$stamp.md")
        runCatching { file.writeText(md) }.onFailure {
            error = "导出失败：${it.localizedMessage}"; return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "明心研习笔记 $stamp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "导出笔记")) }
            .onFailure { error = "没有可用的分享应用" }
    }

    private fun buildNotesMarkdown(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        appendLine("# 明心研习笔记")
        appendLine("导出时间：${fmt.format(Date())}；共 ${history.size} 节")
        appendLine()
        val open = doubts.filter { !it.resolved }
        val closed = doubts.filter { it.resolved }
        appendLine("## 疑点（未决 ${open.size} / 已消 ${closed.size}）")
        appendLine()
        (open + closed).forEach { d ->
            appendLine("- [${if (d.resolved) "已消" else "未决"}] ${d.text}（${d.topic}）")
        }
        appendLine()
        appendLine("## 研习记录")
        history.forEachIndexed { i, l ->
            appendLine()
            appendLine("### ${i + 1}. ${l.topic}")
            appendLine("- 时间：${fmt.format(Date(l.updatedAt))}")
            if (l.prompt.isNotBlank()) {
                appendLine("- 研习问题：${l.prompt}")
                if (l.edition.isNotBlank()) appendLine("- 依据底本：${l.edition}；经文锚点：${l.anchor}")
            }
            appendLine("- 我的初讲：${l.firstExplanation}")
            l.dialogue.forEachIndexed { j, qa ->
                appendLine("- 追问${j + 1}（善问）：${qa.question}")
                if (qa.options.isNotEmpty()) {
                    appendLine("  - 候选：${qa.options.joinToString("；")}")
                    appendLine("  - 我的选择：${qa.answer}")
                } else {
                    appendLine("  - 我的回答：${qa.answer}")
                }
            }
            runCatching { JSONObject(l.evaluationJson) }.getOrNull()?.let { j ->
                appendLine(
                    "- 评分（0-5）：义理 ${j.optInt("accuracy")} / 逻辑 ${j.optInt("logic")} / " +
                        "清晰 ${j.optInt("clarity")} / 应用 ${j.optInt("application")}"
                )
                appendLine("- 已经理解的：${j.optString("strengths")}")
                appendLine("- 需要澄清的：${j.optString("issues")}")
                appendLine("- 建议核查经论：${j.optString("verify")}")
                appendLine("- 再讲一次的提示：${j.optString("suggestion")}")
            }
            if (l.revisedExplanation.isNotBlank()) appendLine("- 我的重讲：${l.revisedExplanation}")
            if (l.finalFeedback.isNotBlank()) appendLine("- 助教总结：${l.finalFeedback}")
        }
        appendLine()
        appendLine("> 注：以上问答与评估由 AI（deepseek-flash）生成，义理未经专家校勘，引用请回查原典。")
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        if (speaking == text) { tts?.stop(); speaking = ""; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lesson_question")
        speaking = text
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); scope.cancel()
        super.onDestroy()
    }
}

/* ------------------------------------------------------------------ */
/*  Scaffold, navigation and home                                     */
/* ------------------------------------------------------------------ */

@Composable
private fun NavEntry(
    key: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    page: String, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val selected = page == key
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Jade else Ink70, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = if (selected) Jade else Ink70, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainUI(
    page: String,
    lesson: Lesson,
    history: List<Lesson>,
    busy: Boolean,
    error: String,
    hasKey: Boolean,
    onNavigate: (String) -> Unit,
    speaking: String,
    onStartQuestion: (BankQuestion) -> Unit,
    onCustomTopic: (String) -> Unit,
    onResume: () -> Unit,
    onSend: (String) -> Unit,
    onRevise: () -> Unit,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onSpeech: (String) -> Unit,
    onOpenHistory: (Lesson) -> Unit,
    doubts: List<Doubt>,
    onToggleDoubt: (Doubt) -> Unit,
    onLoop: (String) -> Unit,
    nextUp: BankQuestion?,
    onExportNotes: () -> Unit,
    onPractice: () -> Unit
) {
    var selectedGroup by remember { mutableStateOf<TopicGroup?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var customDraft by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }

    if (showCustom) {
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("自定义课题") },
            text = {
                OutlinedTextField(
                    value = customDraft, onValueChange = { customDraft = it },
                    modifier = Modifier.fillMaxWidth(), maxLines = 2,
                    placeholder = { Text("例如：因果与自由意志") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = customDraft.isNotBlank(),
                    onClick = { showCustom = false; onCustomTopic(customDraft.trim()); customDraft = "" }
                ) { Text("开始", color = Jade) }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("取消", color = Ink70) } }
        )
    }

    Scaffold(
        topBar = {
            if (page == "lesson" || page == "topic" || page == "browse") TopAppBar(
                title = {
                    Text(
                        if (page == "lesson") "费曼课堂" else "明心",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onNavigate("home") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
            )
        },
        bottomBar = {
            if (page != "lesson") Box {
                Surface(color = Color.White, shadowElevation = 10.dp, tonalElevation = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavEntry("home", "研习", Icons.Default.Home, page, Modifier.weight(1f)) { onNavigate("home") }
                        NavEntry("doubts", "疑点", Icons.Default.Lightbulb, page, Modifier.weight(1f)) { onNavigate("doubts") }
                        Spacer(Modifier.width(64.dp))
                        NavEntry("history", "笔记", Icons.Default.MenuBook, page, Modifier.weight(1f)) { onNavigate("history") }
                        NavEntry("settings", "设置", Icons.Default.Settings, page, Modifier.weight(1f)) { onNavigate("settings") }
                    }
                }
                FloatingActionButton(
                    onClick = onPractice,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp).size(56.dp),
                    shape = CircleShape,
                    containerColor = Jade, contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(6.dp)
                ) {
                    Icon(Icons.Default.AutoStories, contentDescription = "开始研习", modifier = Modifier.size(24.dp))
                }
            }
        },
        containerColor = Paper
    ) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            if (error.isNotBlank() && page != "lesson") {
                Surface(color = Vermilion12, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        error, color = Vermilion, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            when (page) {
                "home" -> HomeScreen(
                    active = lesson, hasKey = hasKey, history = history, doubts = doubts,
                    onResume = onResume, onSettings = { onNavigate("settings") },
                    onBrowse = { onNavigate("browse") },
                    onStart = onStartQuestion,
                    onDoubts = { onNavigate("doubts") },
                    onCustom = { showCustom = true }
                )
                "browse" -> BrowseScreen(
                    onPickGroup = { selectedGroup = it; onNavigate("topic") },
                    onCustom = { showCustom = true }
                )
                "topic" -> if (selectedGroup != null) {
                    TopicScreen(selectedGroup!!, onStartQuestion)
                } else {
                    LaunchedEffect(Unit) { onNavigate("home") }
                    Spacer(Modifier.fillMaxSize())
                }
                "lesson" -> LessonScreen(
                    lesson, busy, error, speaking, onSend, onRevise, onLoop, onSpeech,
                    nextUp = nextUp, onNext = onStartQuestion
                )
                "doubts" -> DoubtsScreen(doubts, onToggleDoubt) { q -> onStartQuestion(q) }
                "history" -> HistoryScreen(history, onOpenHistory, onExportNotes)
                "settings" -> SettingsScreen(hasKey, keyDraft, { keyDraft = it },
                    { onSaveKey(keyDraft); keyDraft = "" }, onDeleteKey)
            }
        }
    }
}

@Composable
private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (h < 5) "夜深了" else if (h < 12) "早上好" else if (h < 18) "下午好" else "晚上好"
}

/** 连续研习天数：以今天（或昨天）结尾往回数。 */
private fun streakOf(history: List<Lesson>): Int {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val days = history.map { fmt.format(Date(it.updatedAt)) }.toSet()
    val cal = Calendar.getInstance()
    if (fmt.format(cal.time) !in days) cal.add(Calendar.DAY_OF_YEAR, -1)
    var n = 0
    while (fmt.format(cal.time) in days) { n++; cal.add(Calendar.DAY_OF_YEAR, -1) }
    return n
}

@Composable
private fun HomeScreen(
    active: Lesson, hasKey: Boolean, history: List<Lesson>, doubts: List<Doubt>,
    onResume: () -> Unit, onSettings: () -> Unit, onBrowse: () -> Unit,
    onStart: (BankQuestion) -> Unit, onDoubts: () -> Unit, onCustom: () -> Unit
) {
    val practiced = remember(history.size) {
        history.map { it.questionId }.filter { it.isNotBlank() }.toSet()
    }
    var candidateId by rememberSaveable { mutableStateOf("") }
    val candidate = candidateId.takeIf { it.isNotBlank() }
        ?.let { QuestionBank.byId(it) }?.takeIf { it.id !in practiced }
    val remaining = QuestionBank.questions.filter { it.id !in practiced }
    val next = candidate ?: remaining.minByOrNull { it.difficulty } ?: QuestionBank.randomQuestion()
    val streak = remember(history.size) { streakOf(history) }
    val openDoubts = doubts.count { !it.resolved }
    val inFlight = active.topic.isNotBlank() && active.stage != "DONE"
    var pendingStart by remember { mutableStateOf<BankQuestion?>(null) }
    var confirmAbandon by remember { mutableStateOf(false) }
    if (confirmAbandon) {
        val target = pendingStart
        AlertDialog(
            onDismissRequest = { confirmAbandon = false; pendingStart = null },
            title = { Text("开始新题？") },
            text = { Text("有一节未完成的研习（${active.topic}），开始新题将放弃它。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAbandon = false
                    target?.let(onStart)
                    pendingStart = null
                }) { Text("放弃并开始", color = Vermilion) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAbandon = false; pendingStart = null }) {
                    Text("继续原课", color = Jade)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoleAvatar("明", Jade)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${greeting()}，欢迎回来", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = Ink, maxLines = 1)
                    Text("今日也来讲一课", fontSize = 13.sp, color = Ink70, maxLines = 1)
                }
                if (!hasKey) {
                    TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("配置密钥", color = Vermilion, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }

        item {
            Surface(color = Color.White, shape = CardShape, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, contentDescription = null,
                        tint = Vermilion, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("连续研习 $streak 天", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            color = Ink, maxLines = 1)
                        Text("已练 ${practiced.size} 题", fontSize = 12.sp, color = Ink70, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Icon(Icons.Default.Lightbulb, contentDescription = null,
                            tint = if (openDoubts > 0) Gold else Jade, modifier = Modifier.size(22.dp))
                        Text("疑点 $openDoubts", fontSize = 12.sp,
                            color = if (openDoubts > 0) GoldText else Jade, maxLines = 1)
                    }
                }
            }
        }

        item {
            Surface(
                shape = CardShape, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.background(Brush.linearGradient(JadeGrad)).padding(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (inFlight && active.questionId == next.id) "继续研习 · ${next.sutra}"
                                else "下一题 · ${next.sutra}",
                                color = Color(0xFFD9E8E0), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp, modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            DifficultyDots(next.difficulty, Color.White)
                        }
                        Text(
                            next.question, fontSize = 18.sp, lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold, color = Color.White
                        )
                        if (next.anchor.isNotBlank()) {
                            Text("锚点 · ${next.anchor}", color = Color(0xFFBFD8CC), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (remaining.isNotEmpty()) "依难度递进 · 余 ${remaining.size} 题" else "已全练 · 随机复习",
                                color = Color(0xFFBFD8CC), fontSize = 12.sp,
                                modifier = Modifier.weight(1f), maxLines = 1
                            )
                            Surface(
                                color = Color.White, shape = BtnShape,
                                modifier = Modifier.clickable {
                                    when {
                                        !inFlight -> onStart(next)
                                        active.questionId.isNotBlank() && active.questionId == next.id -> onResume()
                                        else -> { pendingStart = next; confirmAbandon = true }
                                    }
                                }
                            ) {
                                Text(
                                    if (inFlight && active.questionId == next.id) "继续研习" else "开始研习",
                                    color = JadeDeep, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("快捷研习", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickChip("自由选题", Icons.Default.MenuBook, Modifier.weight(1f)) { onBrowse() }
                QuickChip("疑点复习", Icons.Default.Lightbulb, Modifier.weight(1f)) { onDoubts() }
                QuickChip("自定义课题", Icons.Default.Edit, Modifier.weight(1f)) { onCustom() }
            }
        }
    }
}

@Composable
private fun QuickChip(
    label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Surface(
        color = Color.White, shape = CardShape, shadowElevation = 2.dp,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(vertical = 14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = Jade, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 12.sp, color = Ink, maxLines = 1)
        }
    }
}

@Composable
private fun BrowseScreen(
    onPickGroup: (TopicGroup) -> Unit, onCustom: () -> Unit
) {
    var sutra by remember { mutableStateOf(QuestionBank.sutras.firstOrNull().orEmpty()) }
    val topics = remember(sutra) { QuestionBank.topicsOf(sutra) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("自由选题", fontSize = 20.sp, lineHeight = 26.sp,
                fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestionBank.sutras.forEach { name ->
                    val selected = name == sutra
                    Surface(
                        color = if (selected) Jade else Color.Transparent,
                        shape = RoundedCornerShape(20.dp),
                        border = if (selected) null else BorderStroke(1.dp, Ink12),
                        modifier = Modifier.clickable { sutra = name }
                    ) {
                        Text(name, color = if (selected) Color.White else Ink, fontSize = 14.sp,
                            maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        items(topics, key = { "${it.sutra}-${it.topic}" }) { group ->
            Surface(
                color = Color.White, shape = CardShape, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable { onPickGroup(group) }
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(group.topic, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium,
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(10.dp))
                    Text("${group.questions.size} 题", color = Ink70, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                        tint = Ink70, modifier = Modifier.size(16.dp))
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onCustom, modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = BtnShape, border = BorderStroke(1.dp, Ink12)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("自定义课题", color = Ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TopicScreen(group: TopicGroup, onStart: (BankQuestion) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "${group.sutra} · ${group.topic}",
                fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Ink
            )
            Text("选一道题，用自己的话先讲给 AI 学生听", color = Ink70, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(4.dp))
        }
        items(group.questions, key = { it.id }) { q ->
            Surface(
                color = Color.White, shape = CardShape, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable { onStart(q) }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q.id, color = GoldText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        DifficultyDots(q.difficulty)
                    }
                    Text(q.question, fontSize = 16.sp, lineHeight = 24.sp, color = Ink)
                    if (q.anchor.isNotBlank()) {
                        Text("锚点 · ${q.anchor}", color = Ink70, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun DifficultyDots(level: Int, tint: Color = Jade) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (1..5).forEach { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i <= level) tint else Ink12)
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Lesson: one question hero, alternating voices, pinned input        */
/* ------------------------------------------------------------------ */

private fun stageIndex(stage: String): Int = when (stage) {
    "INTRO" -> 0; "ASK" -> 1; "REVIEW" -> 2; "REWRITE" -> 3; else -> 4
}

@Composable
private fun StageLabel(stage: String, answered: Int) {
    val label = when (stage) {
        "INTRO" -> "第一步 · 初讲"
        "ASK" -> "第二步 · 追问（已答 $answered 问）"
        "REVIEW" -> "第三步 · 明辨"
        "REWRITE" -> "第四步 · 再讲一次"
        else -> "研习完成"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = GoldText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, modifier = Modifier.weight(1f))
        DifficultyDots(stageIndex(stage).coerceIn(0, 4) + 1)
    }
    HorizontalDivider(color = Ink12, thickness = 0.5.dp)
}

@Composable
private fun QuestionHero(lesson: Lesson) {
    val compact = stageIndex(lesson.stage) > 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                lesson.topic,
                color = GoldText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (lesson.prompt.isNotBlank()) {
            Text(
                lesson.prompt,
                fontSize = if (compact) 18.sp else 21.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = if (compact) 26.sp else 30.sp,
                color = Ink
            )
        } else {
            Text(
                fontFamily = Serif,
                fontSize = if (compact) 18.sp else 21.sp,
                fontWeight = FontWeight.Bold, color = Ink
            )
        }
        if (!compact) {
            Text(
                if (lesson.stage == "INTRO") "不要查资料，也不必追求完美。第一次表达越真实，越容易发现盲点。"
                else "紧扣这个问题来讲。",
                color = Ink70, fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun RoleAvatar(char: String, bg: Color, textColor: Color = Color.White) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(char, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StudentCard(text: String, active: Boolean = false, onSpeak: () -> Unit) {
    Row {
        RoleAvatar("善", Color.White, JadeDeep)
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(CardShape)
                .background(Brush.linearGradient(JadeGrad))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoStories, contentDescription = null,
                    tint = Color(0xFFD9E8E0), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("善问 · AI 学生", color = Color(0xFFD9E8E0), fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = onSpeak, modifier = Modifier.size(44.dp)) {
                    Icon(
                        if (active) Icons.Default.Stop else Icons.Default.VolumeUp,
                        contentDescription = if (active) "停止朗读" else "朗读",
                        tint = if (active) Color(0xFFFFD9CC) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(text, color = Color.White, lineHeight = 24.sp, fontSize = 16.sp)
        }
    }
}

@Composable
private fun TutorCard(text: String) {
    Column(
        Modifier
            .fillMaxWidth(0.92f)
            .clip(CardShape)
            .background(Gold12)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MenuBook, contentDescription = null, tint = Gold, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text("明辨 · AI 助教", color = GoldText, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Text(text, color = Ink, lineHeight = 24.sp, fontSize = 16.sp)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Spacer(Modifier.weight(0.16f))
        Column(
            Modifier
                .weight(0.76f)
                .clip(CardShape)
                .background(Color.White)
                .border(0.5.dp, Ink12, CardShape)
                .padding(14.dp)
        ) {
            Text("我的讲解", color = Ink70, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Ink, lineHeight = 23.sp, fontSize = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        RoleAvatar("我", Ink)
    }
}

@Composable
private fun LessonScreen(
    lesson: Lesson, busy: Boolean, error: String, speaking: String,
    onSend: (String) -> Unit, onRevise: () -> Unit, onLoop: (String) -> Unit,
    readAloud: (String) -> Unit,
    nextUp: BankQuestion?, onNext: (BankQuestion) -> Unit
) {
    var input by rememberSaveable(lesson.stage, lesson.currentQuestion, lesson.dialogue.size) { mutableStateOf("") }
    var selectedOption by rememberSaveable(lesson.currentQuestion, lesson.dialogue.size) { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    LaunchedEffect(lesson.stage, lesson.currentQuestion, lesson.dialogue.size, lesson.evaluationJson, lesson.finalFeedback) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollToBottom: () -> Unit = {
        scope.launch {
            kotlinx.coroutines.delay(300)
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
    }
    // 键盘可见期间持续把信息流钉在底部：最后一条永远贴输入框上沿
    LaunchedEffect(Unit) {
        var wasVisible = false
        androidx.compose.runtime.snapshotFlow {
            androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0
        }.collect { visible ->
            if (visible && !wasVisible) {
                kotlinx.coroutines.delay(150)
                while (androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0) {
                    val c = listState.layoutInfo.totalItemsCount
                    if (c > 0) listState.scrollToItem(c - 1)
                    kotlinx.coroutines.delay(100)
                }
            }
            wasVisible = visible
        }
    }
    val editable = lesson.stage in listOf("INTRO", "ASK", "REWRITE")
    val choiceMode = lesson.stage == "ASK" && lesson.currentOptions.isNotEmpty()
    val sendLabel = when (lesson.stage) {
        "INTRO" -> "请提问"; "ASK" -> "作答"; else -> "提交"
    }
    val inputHint = when (lesson.stage) {
        "INTRO" -> "我认为这个问题的意思是……"
        "ASK" -> "我的回答是……"
        else -> "这一次，我这样讲……"
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        StageLabel(lesson.stage, lesson.dialogue.size)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { QuestionHero(lesson) }
            if (lesson.firstExplanation.isNotBlank()) item { UserBubble(lesson.firstExplanation) }
            itemsIndexed(lesson.dialogue, key = { i, _ -> "qa$i" }) { _, qa ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudentCard(qa.question, speaking == qa.question) { readAloud(qa.question) }
                    UserBubble(qa.answer)
                }
            }
            when (lesson.stage) {
                "ASK" -> item(key = "current") {
                    StudentCard(lesson.currentQuestion, speaking == lesson.currentQuestion) { readAloud(lesson.currentQuestion) }
                }
                "REVIEW" -> {
                    item(key = "tutor") { TutorCard("你的初讲与三轮回答我都看完了。下面是义理、逻辑、表达与应用的审核，评分只反映本轮表现。") }
                    item(key = "eval") { EvaluationCard(lesson.evaluationJson) }
                    item(key = "revise") {
                        Button(
                            onClick = onRevise, modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = BtnShape
                        ) { Text("重新讲解", fontSize = 15.sp, maxLines = 1) }
                    }
                    item(key = "loop") {
                        val weak = runCatching {
                            JSONObject(lesson.evaluationJson).optString("issues").trim()
                        }.getOrDefault("")
                        if (weak.isNotBlank()) {
                            OutlinedButton(
                                onClick = { onLoop(weak) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = BtnShape, border = BorderStroke(1.dp, Jade)
                            ) { Text("回到学生，再讲这一处", color = Jade, fontSize = 15.sp, maxLines = 1) }
                        }
                    }
                }
                "REWRITE" -> item(key = "tutor2") {
                    TutorCard("现在不看分析，用你自己的话把这个问题重新讲明白。")
                }
                "DONE" -> {
                    item(key = "feedback") { TutorCard(lesson.finalFeedback) }
                    item(key = "revised") { UserBubble(lesson.revisedExplanation) }
                    item(key = "note") {
                        Text(
                            "研习记录已存入笔记。理解能力不等于证悟境界，AI 评价不能替代查阅原典与师长指导。",
                            color = Ink70, fontSize = 12.sp, lineHeight = 18.sp
                        )
                    }
                    item(key = "nextUp") {
                        val nq = nextUp
                        if (nq != null) {
                            Surface(color = Color.White, shape = CardShape, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("下一题 · ${nq.sutra}", color = GoldText, fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(nq.question, fontSize = 16.sp, lineHeight = 23.sp,
                                        fontWeight = FontWeight.Bold, color = Ink, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    Button(
                                        onClick = { onNext(nq) },
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        shape = BtnShape
                                    ) { Text("开始研习", fontSize = 15.sp, maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (editable) {
            if (error.isNotBlank()) {
                Surface(color = Vermilion12, shape = BtnShape, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        error, color = Vermilion, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(color = Ink12, thickness = 0.5.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = choiceMode,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
                ) {
                    ChoicePanel(
                        lesson = lesson,
                        selectedOption = selectedOption,
                        onSelect = { selectedOption = it },
                        input = input,
                        onInputChange = { input = it },
                        busy = busy,
                        onSend = onSend
                    )
                }
                AnimatedVisibility(
                    visible = !choiceMode,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150))
                ) {
                    FreeTextPanel(
                        input = input,
                        onInputChange = { input = it },
                        busy = busy,
                        sendLabel = sendLabel,
                        inputHint = inputHint,
                        onSend = onSend,
                        onFocused = scrollToBottom
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoicePanel(
    lesson: Lesson,
    selectedOption: Int,
    onSelect: (Int) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    onSend: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选一个最接近你想法的回答", color = Ink70, fontSize = 13.sp, maxLines = 1)
        lesson.currentOptions.forEachIndexed { i, opt ->
            OptionRow(
                index = i, text = opt,
                selected = selectedOption == i, enabled = !busy,
                onClick = { onSelect(i) }
            )
        }
        OptionRow(
            index = -1, text = "以上都不是，我有不同想法",
            selected = selectedOption == lesson.currentOptions.size, enabled = !busy,
            onClick = { onSelect(lesson.currentOptions.size) }
        )
        val escape = selectedOption == lesson.currentOptions.size
        if (escape) {
            OutlinedTextField(
                value = input, onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                minLines = 2, maxLines = 5,
                enabled = !busy,
                placeholder = { Text("用你自己的话说……", color = Ink70, fontSize = 14.sp) }
            )
        }
        Button(
            onClick = {
                if (escape) onSend(input)
                else if (selectedOption in lesson.currentOptions.indices) onSend(lesson.currentOptions[selectedOption])
            },
            enabled = !busy && (if (escape) input.isNotBlank() else selectedOption >= 0),
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = BtnShape
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("作答", fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FreeTextPanel(
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    sendLabel: String,
    inputHint: String,
    onSend: (String) -> Unit,
    onFocused: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input, onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .onFocusChanged { if (it.isFocused) onFocused() },
            minLines = 2, maxLines = 6,
            enabled = !busy,
            placeholder = { Text(inputHint, color = Ink70, fontSize = 14.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !busy) onSend(input) })
        )
        Button(
            onClick = { onSend(input) },
            enabled = input.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = BtnShape
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(sendLabel, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ThinkingCard() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dotAlphas = (0..2).map { i ->
        transition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse),
            label = "dot$i"
        )
    }
    Row {
        RoleAvatar("善", Jade)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .clip(CardShape)
                .background(Brush.linearGradient(JadeGrad))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dotAlphas.forEach { alpha ->
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = alpha.value))
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    index: Int, text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit
) {
    Surface(
        color = if (selected) Jade else Color.White,
        shape = CardShape,
        shadowElevation = if (selected) 4.dp else 2.dp,
        border = if (selected) null else BorderStroke(1.dp, Ink12),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .border(1.5.dp, if (selected) Color.White else Ink12, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when {
                    selected -> Icon(Icons.Default.Check, contentDescription = null, tint = Jade, modifier = Modifier.size(13.dp))
                    index < 0 -> Icon(Icons.Default.Edit, contentDescription = null,
                        tint = if (selected) Color.White else Ink70, modifier = Modifier.size(12.dp))
                    else -> Text("${index + 1}", fontSize = 12.sp, color = if (selected) Color.White else Ink70)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 15.sp, lineHeight = 22.sp,
                color = if (selected) Color.White else Ink, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EvaluationCard(jsonText: String) {
    val j = runCatching { JSONObject(jsonText) }.getOrNull()
    if (j == null) {
        Text("评价未能解析", color = Vermilion, fontSize = 13.sp)
        return
    }
    Surface(color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 3.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("义理准确" to "accuracy", "逻辑自洽" to "logic", "表达清晰" to "clarity", "生活应用" to "application")
                .forEach { (label, key) ->
                    val score = j.optInt(key).coerceIn(0, 5)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.width(72.dp), fontSize = 13.sp, color = Ink70, maxLines = 1)
                        DifficultyDots(score)
                        Spacer(Modifier.weight(1f))
                        Text("$score / 5", fontSize = 12.sp, color = Ink70, maxLines = 1)
                    }
                }
            HorizontalDivider(color = Ink12, thickness = 0.5.dp)
            listOf("已经理解的" to "strengths", "需要澄清的" to "issues", "建议核查经论" to "verify", "再讲一次的提示" to "suggestion")
                .forEach { (heading, key) ->
                    Column {
                        Text(heading, color = GoldText, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Text(j.optString(key).ifBlank { "暂无" }, lineHeight = 22.sp, fontSize = 14.sp, color = Ink)
                    }
                }
            Text("本评价由 AI 生成，仅供参考。", fontSize = 11.sp, color = Ink70)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Doubts: the core asset — open/resolved learning state (原理4)     */
/* ------------------------------------------------------------------ */

@Composable
private fun DoubtsScreen(doubts: List<Doubt>, onToggle: (Doubt) -> Unit, onOpen: (BankQuestion) -> Unit) {
    val open = doubts.filter { !it.resolved }
    val closed = doubts.filter { it.resolved }
    if (doubts.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = Jade, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("尚无疑点", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("课堂评估中发现的薄弱处会自动沉淀到这里。", fontSize = 13.sp, color = Ink70)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "未决疑点 ${open.size}",
                    fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = Ink
                )
                if (open.isEmpty()) {
                    Text("全部消除，境界日进。", color = Jade, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
            }
            items(open, key = { "o${it.createdAt}-${it.text.hashCode()}" }) { d ->
                DoubtCard(d, onToggle, onOpen)
            }
            if (closed.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("已消除 ${closed.size}", color = Ink70, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                items(closed, key = { "c${it.createdAt}-${it.text.hashCode()}" }) { d ->
                    DoubtCard(d, onToggle, onOpen)
                }
            }
        }
    }
}

@Composable
private fun DoubtCard(d: Doubt, onToggle: (Doubt) -> Unit, onOpen: (BankQuestion) -> Unit) {
    Surface(
        color = Color.White, shape = BtnShape,
        border = BorderStroke(1.dp, if (d.resolved) Ink12 else Gold.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable { onToggle(d) }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (d.resolved) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (d.resolved) "标记为未决" else "标记为已消除",
                tint = if (d.resolved) Jade else Gold,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.text, fontSize = 14.sp, lineHeight = 21.sp, color = if (d.resolved) Ink70 else Ink,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${d.topic} · " + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(d.createdAt)),
                    fontSize = 12.sp, color = Ink70, maxLines = 1
                )
            }
            val source = QuestionBank.byId(d.questionId)
            if (source != null) {
                IconButton(onClick = { onOpen(source) }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "去研习此题",
                        tint = Jade, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  History and settings                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun HistoryScreen(history: List<Lesson>, open: (Lesson) -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("研习笔记", fontSize = 20.sp, lineHeight = 26.sp,
                fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) {
                OutlinedButton(
                    onClick = onExport, modifier = Modifier.height(38.dp),
                    shape = BtnShape, border = BorderStroke(1.dp, Jade),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Jade, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("导出", color = Jade, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    if (history.isEmpty()) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.MenuBook, contentDescription = null, tint = Jade, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("尚无研习笔记", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("完成一次费曼课堂后，这里会保存你的回答与评估。", fontSize = 13.sp, color = Ink70)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(history, key = { "${it.questionId}-${it.updatedAt}" }) { item ->
                Surface(
                    color = Color.White, shape = CardShape, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable { open(item) }
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.topic, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.prompt.isNotBlank()) {
                            Text(item.prompt, fontSize = 13.sp, color = Ink70,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt)),
                                fontSize = 12.sp, color = Ink70, modifier = Modifier.weight(1f), maxLines = 1
                            )
                            Text("已完成", color = Jade, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SettingsScreen(
    hasKey: Boolean, draft: String, changeDraft: (String) -> Unit, save: () -> Unit, delete: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除密钥") },
            text = { Text("将删除本机加密保存的 API Key，删除后需重新配置才能研习。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; delete() }) { Text("删除", color = Vermilion) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Ink70) }
            }
        )
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("DeepSeek API", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                if (hasKey) "状态：已保存加密密钥" else "状态：尚未配置",
                color = if (hasKey) Jade else Vermilion, fontSize = 13.sp, maxLines = 1
            )
        }
        item {
            OutlinedTextField(
                value = draft, onValueChange = changeDraft, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("API Key") },
                visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "显示或隐藏"
                        )
                    }
                }
            )
        }
        item {
            Button(
                onClick = save, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = BtnShape
            ) { Text("保存", maxLines = 1) }
        }
        if (hasKey) {
            item {
                OutlinedButton(
                    onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = BtnShape, border = BorderStroke(1.dp, Vermilion)
                ) { Text("删除密钥", color = Vermilion, maxLines = 1) }
            }
        }
        item {
            HorizontalDivider(color = Ink12, thickness = 0.5.dp)
            Spacer(Modifier.height(4.dp))
            Text("接口与模型", color = GoldText, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Text("https://api.deepseek.com/chat/completions", fontSize = 13.sp, color = Ink70)
            Text("模型：deepseek-flash", color = Jade, fontSize = 13.sp, maxLines = 1)
        }
        item {
            Text("隐私与费用", color = GoldText, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Text(
                "密钥仅存储在本机并由 Android Keystore 加密；学习内容在请求时发送给 DeepSeek 处理。本 App 没有中转服务器，也不代付 API 费用。建议为账户设置费用上限。",
                lineHeight = 21.sp, fontSize = 13.sp, color = Ink70
            )
            Text("语音输入依赖系统识别服务，其隐私政策与 DeepSeek 无关。", fontSize = 12.sp, color = Ink70)
        }
    }
}
