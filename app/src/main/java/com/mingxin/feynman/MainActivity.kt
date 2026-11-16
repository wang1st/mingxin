package com.mingxin.feynman

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ------------------------------------------------------------------ */
/*  One palette, four named colors; every tint is derived from them.   */
/* ------------------------------------------------------------------ */
private val Paper = Color(0xFFF6F3EA)
private val Ink = Color(0xFF20362E)
private val Jade = Color(0xFF3B7965)
private val Gold = Color(0xFF9E793E)
private val Vermilion = Color(0xFFB4432F)

private val Ink70 = Ink.copy(alpha = 0.70f)
private val Ink70 = Ink.copy(alpha = 0.70f)
private val GoldText = Color(0xFF84652F)
private val Ink12 = Ink.copy(alpha = 0.12f)
private val Ink8 = Ink.copy(alpha = 0.08f)
private val Jade10 = Jade.copy(alpha = 0.10f)
private val Gold12 = Gold.copy(alpha = 0.12f)
private val Vermilion12 = Vermilion.copy(alpha = 0.12f)

private val Serif = FontFamily.Serif

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuestionBank.load(this)
        hasApiKey = secrets.hasKey()
        current = runCatching { Lesson.fromJson(JSONObject(prefs.getString("active", "{}") ?: "{}")) }.getOrDefault(Lesson())
        history = runCatching {
            val arr = JSONArray(prefs.getString("completed", "[]") ?: "[]")
            (0 until arr.length()).map { Lesson.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) tts?.language = Locale.SIMPLIFIED_CHINESE
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Jade, onPrimary = Color.White, background = Paper,
                surface = Color.White, onSurface = Ink, secondary = Gold,
                error = Vermilion
            )) {
                MainUI(
                    page = page, lesson = current, history = history, busy = busy,
                    error = error, hasKey = hasApiKey,
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
                    onOpenHistory = { selected -> current = selected; page = "lesson" }
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
                        val question = client.chat(
                            key = key, system = Tutors.student,
                            input = Tutors.transcript(next) + "\n" + bankRef() +
                                "\n这是首次讲解，请提出第1个关键追问。",
                            maxTokens = 220
                        )
                        current = next.copy(stage = "ASK", currentQuestion = question)
                    }
                    "ASK" -> {
                        val addition = QA(current.currentQuestion, input)
                        val next = current.copy(dialogue = current.dialogue + addition, updatedAt = System.currentTimeMillis())
                        if (next.dialogue.size < 3) {
                            val question = client.chat(
                                key = key, system = Tutors.student,
                                input = Tutors.transcript(next) + "\n" + bankRef() +
                                    "\n请提出第${next.dialogue.size + 1}个追问，不要重复前面的问题。",
                                maxTokens = 240
                            )
                            current = next.copy(currentQuestion = question)
                        } else {
                            val judgement = client.chat(
                                key = key, system = Tutors.evaluator,
                                input = Tutors.transcript(next) + "\n" + bankRef() +
                                    "\n现在请按指定 json 结构评价本次讲解。",
                                json = true, maxTokens = 1600
                            )
                            JSONObject(judgement) // only move to next stage for valid JSON
                            current = next.copy(stage = "REVIEW", currentQuestion = "", evaluationJson = judgement)
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

    private fun speak(text: String) {
        if (text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lesson_question")
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); scope.cancel()
        super.onDestroy()
    }
}

/* ------------------------------------------------------------------ */
/*  Scaffold, navigation and home                                     */
/* ------------------------------------------------------------------ */

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
    onStartQuestion: (BankQuestion) -> Unit,
    onCustomTopic: (String) -> Unit,
    onResume: () -> Unit,
    onSend: (String) -> Unit,
    onRevise: () -> Unit,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onSpeech: (String) -> Unit,
    onOpenHistory: (Lesson) -> Unit
) {
    var selectedGroup by remember { mutableStateOf<TopicGroup?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var speechText by remember { mutableStateOf("") }
    var customDraft by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as Activity
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!recognized.isNullOrBlank()) speechText = recognized
        }
    }
    val startVoice: () -> Unit = {
        try {
            speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "用自己的话讲一讲...")
            })
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(activity, "手机上未安装可用的语音识别服务", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    if (showCustom) {
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("自定义课题", fontFamily = Serif) },
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
            TopAppBar(
                title = {
                    Text(
                        if (page == "lesson") "费曼课堂" else "明心",
                        fontFamily = Serif, fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (page == "lesson" || page == "topic") IconButton(onClick = { onNavigate("home") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
            )
        },
        bottomBar = {
            if (page != "lesson") NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                listOf(
                    Triple("home", "研习", Icons.Default.Home),
                    Triple("history", "笔记", Icons.Default.MenuBook),
                    Triple("settings", "设置", Icons.Default.Settings)
                ).forEach { item ->
                    NavigationBarItem(
                        selected = page == item.first, onClick = { onNavigate(item.first) },
                        icon = { Icon(item.third, contentDescription = item.second) },
                        label = { Text(item.second, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Jade, selectedTextColor = Jade, indicatorColor = Jade10)
                    )
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
                    active = lesson, hasKey = hasKey,
                    onResume = onResume, onSettings = { onNavigate("settings") },
                    onPickGroup = { selectedGroup = it; onNavigate("topic") },
                    onRandom = { QuestionBank.randomQuestion().let(onStartQuestion) },
                    onCustom = { showCustom = true }
                )
                "topic" -> if (selectedGroup != null) {
                    TopicScreen(selectedGroup!!, onStartQuestion)
                } else {
                    LaunchedEffect(Unit) { onNavigate("home") }
                    Spacer(Modifier.fillMaxSize())
                }
                "lesson" -> LessonScreen(
                    lesson, busy, error, onSend, onRevise, startVoice,
                    speechText, { speechText = "" }, onSpeech
                )
                "history" -> HistoryScreen(history, onOpenHistory)
                "settings" -> SettingsScreen(hasKey, keyDraft, { keyDraft = it },
                    { onSaveKey(keyDraft); keyDraft = "" }, onDeleteKey)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    active: Lesson, hasKey: Boolean,
    onResume: () -> Unit, onSettings: () -> Unit,
    onPickGroup: (TopicGroup) -> Unit,
    onRandom: () -> Unit, onCustom: () -> Unit
) {
    var sutra by remember { mutableStateOf(QuestionBank.sutras.firstOrNull().orEmpty()) }
    val topics = remember(sutra) { QuestionBank.topicsOf(sutra) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("AI 费曼研习", color = GoldText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text("你来讲佛法", fontFamily = Serif, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("AI 做一个爱提问的学生", fontFamily = Serif, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Jade)
            Spacer(Modifier.height(10.dp))
            Text("以讲促学，以问明理。讲得明白，才是真懂。", color = Ink70, fontSize = 14.sp)
        }

        if (!hasKey) {
            item {
                Surface(color = Vermilion12, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("尚未配置 API Key", color = Vermilion, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("去设置", color = Vermilion, maxLines = 1) }
                    }
                }
            }
        }

        if (active.topic.isNotBlank() && active.stage != "DONE") {
            item {
                Button(
                    onClick = onResume, modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Jade)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("继续", fontSize = 16.sp, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        Text("·", color = Color.White.copy(alpha = 0.6f))
                        Spacer(Modifier.width(10.dp))
                        Text(active.topic, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("选题研习", fontFamily = Serif, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestionBank.sutras.forEach { name ->
                    val selected = name == sutra
                    Surface(
                        color = if (selected) Jade else Color.Transparent,
                        shape = RoundedCornerShape(20.dp),
                        border = if (selected) null else BorderStroke(1.dp, Ink12),
                        modifier = Modifier.clickable { sutra = name }
                    ) {
                        Text(
                            name,
                            color = if (selected) Color.White else Ink,
                            fontSize = 14.sp, maxLines = 1,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        items(topics, key = { "${it.sutra}-${it.topic}" }) { group ->
            Surface(
                color = Color.White, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Ink12), modifier = Modifier.fillMaxWidth().clickable { onPickGroup(group) }
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        group.topic, modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${group.questions.size} 题", color = Ink70, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Ink70, modifier = Modifier.size(16.dp))
                }
            }
        }

        item {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onRandom, modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Jade)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = Jade, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("随机一题", color = Jade, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onCustom, modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Ink12)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("自定义课题", color = Ink, maxLines = 1)
                }
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
                fontFamily = Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink
            )
            Text("选一道题，用自己的话先讲给 AI 学生听", color = Ink70, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(4.dp))
        }
        items(group.questions, key = { it.id }) { q ->
            Surface(
                color = Color.White, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Ink12), modifier = Modifier.fillMaxWidth().clickable { onStart(q) }
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

private val STAGE_STEPS = listOf("初讲", "追问", "明辨", "再讲")
private fun stageIndex(stage: String): Int = when (stage) {
    "INTRO" -> 0; "ASK" -> 1; "REVIEW" -> 2; "REWRITE" -> 3; else -> 4
}

@Composable
private fun StageStepper(stage: String) {
    val current = stageIndex(stage)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        STAGE_STEPS.forEachIndexed { i, label ->
            val done = i < current || current >= 4
            val active = i == current && current < 4
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> Jade
                                active -> Color.Transparent
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            width = if (active) 1.5.dp else 1.dp,
                            color = when {
                                done -> Jade
                                active -> Jade
                                else -> Ink12
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    } else {
                        Text(
                            "${i + 1}",
                            fontSize = 11.sp,
                            color = if (active) Jade else Ink70
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    label, fontSize = 12.sp, maxLines = 1,
                    color = when {
                        done || active -> Jade
                        else -> Ink70
                    },
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            if (i < STAGE_STEPS.lastIndex) {
                Box(
                    Modifier
                        .weight(0.5f)
                        .height(1.dp)
                        .background(if (i < current) Jade else Ink12)
                )
            }
        }
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
                fontFamily = Serif,
                fontSize = if (compact) 18.sp else 21.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = if (compact) 26.sp else 30.sp,
                color = Ink
            )
        } else {
            Text(
                lesson.topic,
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
private fun StudentCard(text: String, onSpeak: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(14.dp))
            .background(Jade10)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoStories, contentDescription = null, tint = Jade, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text("善问 · AI 学生", color = Jade, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = onSpeak, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.VolumeUp, contentDescription = "朗读", tint = Jade, modifier = Modifier.size(18.dp))
            }
        }
        Text(text, color = Ink, lineHeight = 24.sp, fontSize = 16.sp)
    }
}

@Composable
private fun TutorCard(text: String) {
    Column(
        Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(14.dp))
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
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink8)
                .padding(14.dp)
        ) {
            Text("我的讲解", color = Ink70, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Ink, lineHeight = 23.sp, fontSize = 15.sp)
        }
    }
}

@Composable
private fun LessonScreen(
    lesson: Lesson, busy: Boolean, error: String,
    onSend: (String) -> Unit, onRevise: () -> Unit,
    voice: () -> Unit, speechText: String, consumeSpeech: (String) -> Unit,
    readAloud: (String) -> Unit
) {
    var input by remember(lesson.stage, lesson.currentQuestion, lesson.dialogue.size) { mutableStateOf("") }
    LaunchedEffect(speechText) {
        if (speechText.isNotBlank()) {
            input = (input + " " + speechText).trim()
            consumeSpeech("")
        }
    }
    val editable = lesson.stage in listOf("INTRO", "ASK", "REWRITE")
    val sendLabel = when (lesson.stage) {
        "INTRO" -> "请提问"; "ASK" -> "作答"; else -> "提交"
    }
    val inputHint = when (lesson.stage) {
        "INTRO" -> "我认为这个问题的意思是……"
        "ASK" -> "我的回答是……"
        else -> "这一次，我这样讲……"
    }

    Column(Modifier.fillMaxSize()) {
        StageStepper(lesson.stage)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { QuestionHero(lesson) }
            if (lesson.firstExplanation.isNotBlank()) item { UserBubble(lesson.firstExplanation) }
            itemsIndexed(lesson.dialogue, key = { i, _ -> "qa$i" }) { _, qa ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudentCard(qa.question) { readAloud(qa.question) }
                    UserBubble(qa.answer)
                }
            }
            when (lesson.stage) {
                "ASK" -> item(key = "current") {
                    StudentCard(lesson.currentQuestion) { readAloud(lesson.currentQuestion) }
                }
                "REVIEW" -> {
                    item(key = "tutor") { TutorCard("你的初讲与三轮回答我都看完了。下面是义理、逻辑、表达与应用的审核，评分只反映本轮表现。") }
                    item(key = "eval") { EvaluationCard(lesson.evaluationJson) }
                    item(key = "revise") {
                        Button(
                            onClick = onRevise, modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("重新讲解", fontSize = 15.sp, maxLines = 1) }
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
                }
            }
        }
        if (editable) {
            if (error.isNotBlank()) {
                Surface(color = Vermilion12, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
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
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    minLines = 3, maxLines = 6,
                    enabled = !busy,
                    placeholder = { Text(inputHint, color = Ink70, fontSize = 14.sp) }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = voice, enabled = !busy,
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Ink12)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = Jade, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("语音", color = Ink, fontSize = 14.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { onSend(input) },
                        enabled = input.isNotBlank() && !busy,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(sendLabel, fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
            }
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
    Surface(color = Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Ink12)) {
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
/*  History and settings                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun HistoryScreen(history: List<Lesson>, open: (Lesson) -> Unit) {
    if (history.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.MenuBook, contentDescription = null, tint = Jade, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("尚无研习笔记", fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("完成一次费曼课堂后，这里会保存你的回答与评估。", fontSize = 13.sp, color = Ink70)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(history, key = { "${it.questionId}-${it.updatedAt}" }) { item ->
                Surface(
                    color = Color.White, shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Ink12), modifier = Modifier.fillMaxWidth().clickable { open(item) }
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.topic, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp,
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

@Composable
private fun SettingsScreen(
    hasKey: Boolean, draft: String, changeDraft: (String) -> Unit, save: () -> Unit, delete: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("DeepSeek API", fontFamily = Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
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
                shape = RoundedCornerShape(10.dp)
            ) { Text("保存", maxLines = 1) }
        }
        if (hasKey) {
            item {
                OutlinedButton(
                    onClick = delete, modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Vermilion)
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
