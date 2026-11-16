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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF203D35)
private val Jade = Color(0xFF3B7965)
private val Paper = Color(0xFFF8F5EE)
private val Gold = Color(0xFF9E793E)

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
                surface = Color.White, onSurface = Ink, secondary = Gold
            )) {
                MainUI(
                    page = page, lesson = current, history = history, busy = busy,
                    error = error, hasKey = hasApiKey,
                    onNavigate = { page = it; error = "" },
                    onChooseTopic = { topic ->
                        current = Lesson(topic = topic)
                        persistActive()
                        page = "lesson"
                    },
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

    private fun persistActive() {
        prefs.edit().putString("active", current.toJson().toString()).apply()
    }

    private fun persistHistory() {
        val arr = JSONArray().apply { history.take(30).forEach { put(it.toJson()) } }
        prefs.edit().putString("completed", arr.toString()).apply()
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
                        val question = client.chat(
                            key = key, system = Tutors.student,
                            input = Tutors.transcript(next) + "\n这是首次讲解，请提出第1个关键追问。",
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
                                input = Tutors.transcript(next) + "\n请提出第${next.dialogue.size + 1}个追问，不要重复前面的问题。",
                                maxTokens = 240
                            )
                            current = next.copy(currentQuestion = question)
                        } else {
                            val judgement = client.chat(
                                key = key, system = Tutors.evaluator,
                                input = Tutors.transcript(next) + "\n现在请按指定 json 结构评价本次讲解。",
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
                        history = listOf(current) + history.filterNot { it.topic == current.topic && it.updatedAt == current.updatedAt }
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
    onChooseTopic: (String) -> Unit,
    onResume: () -> Unit,
    onSend: (String) -> Unit,
    onRevise: () -> Unit,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onSpeech: (String) -> Unit,
    onOpenHistory: (Lesson) -> Unit
) {
    var customTopic by remember { mutableStateOf("") }
    var keyDraft by remember { mutableStateOf("") }
    var speechText by remember { mutableStateOf("") }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (page == "lesson") "费曼课堂" else "明心 · 佛法研习", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (page == "lesson") IconButton(onClick = { onNavigate("home") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
            )
        },
        bottomBar = {
            if (page != "lesson") NavigationBar(containerColor = Color.White) {
                listOf(Triple("home", "研习", Icons.Default.Home),
                       Triple("history", "笔记", Icons.Default.MenuBook),
                       Triple("settings", "设置", Icons.Default.Settings)).forEach { item ->
                    NavigationBarItem(selected = page == item.first, onClick = { onNavigate(item.first) },
                        icon = { Icon(item.third, contentDescription = item.second) },
                        label = { Text(item.second) })
                }
            }
        },
        containerColor = Paper
    ) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            if (error.isNotBlank()) {
                Surface(color = Color(0xFFFFECE8), modifier = Modifier.fillMaxWidth()) {
                    Text("提示：$error", color = Color(0xFF9E3C25), modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }
            when (page) {
                "home" -> HomeScreen(lesson, hasKey, customTopic, { customTopic = it }, onChooseTopic, onResume, onNavigate)
                "lesson" -> LessonScreen(lesson, busy, onSend, onRevise, startVoice, speechText, { speechText = it }, onSpeech, onNavigate)
                "history" -> HistoryScreen(history, onOpenHistory)
                "settings" -> SettingsScreen(hasKey, keyDraft, { keyDraft = it },
                    { onSaveKey(keyDraft); keyDraft = "" }, onDeleteKey)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    active: Lesson, hasKey: Boolean, customTopic: String, changeTopic: (String) -> Unit,
    start: (String) -> Unit, resume: () -> Unit, navigate: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("以讲促学，以问明理", fontSize = 29.sp, color = Ink, fontWeight = FontWeight.Bold)
        Text("你来讲佛法，AI 做一个爱提问的学生。", color = Color.Gray)
        if (!hasKey) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E7))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("首次使用", fontWeight = FontWeight.Bold)
                    Text("请先在设置中填入自己的 DeepSeek API Key。调用费用由你的 DeepSeek 账户承担。", fontSize = 13.sp)
                    OutlinedButton(onClick = { navigate("settings") }) { Text("去配置 API") }
                }
            }
        }
        if (active.topic.isNotBlank() && active.stage != "DONE") {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE5EFE9))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("继续上次课堂", color = Jade, fontWeight = FontWeight.Bold)
                    Text(active.topic, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Button(onClick = resume) { Text("继续学习") }
                }
            }
        }
        Text("选择一个佛法概念", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        SUBJECTS.forEachIndexed { idx, topic ->
            Surface(
                color = Color.White, shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, Color(0xFFE5E6DF)), modifier = Modifier.fillMaxWidth().clickable { start(topic) }
            ) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d".format(idx + 1), color = Gold, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text(topic, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Jade)
                }
            }
        }
        OutlinedTextField(value = customTopic, onValueChange = changeTopic, label = { Text("或者输入自定义主题") },
            modifier = Modifier.fillMaxWidth(), maxLines = 2)
        Button(onClick = { start(customTopic.trim()) }, enabled = customTopic.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("开始自定义研习")
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LessonScreen(
    lesson: Lesson, busy: Boolean, onSend: (String) -> Unit, onRevise: () -> Unit,
    voice: () -> Unit, speechText: String, consumeSpeech: (String) -> Unit,
    readAloud: (String) -> Unit, navigate: (String) -> Unit
) {
    var input by remember(lesson.topic, lesson.stage, lesson.currentQuestion) { mutableStateOf("") }
    LaunchedEffect(speechText) {
        if (speechText.isNotBlank()) {
            input = (input + " " + speechText).trim()
            consumeSpeech("")
        }
    }
    val isEditable = lesson.stage in listOf("INTRO", "ASK", "REWRITE")
    val title = when (lesson.stage) {
        "INTRO" -> "第一步 · 你先讲"
        "ASK" -> "第二步 · 学生追问 ${lesson.dialogue.size + 1}/3"
        "REVIEW" -> "第三步 · 助教明辨"
        "REWRITE" -> "第四步 · 再讲一次"
        else -> "研习已完成"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(lesson.topic, fontSize = 24.sp, color = Ink, fontWeight = FontWeight.Bold)
        Text(title, fontSize = 15.sp, color = Jade, fontWeight = FontWeight.SemiBold)
        when (lesson.stage) {
            "INTRO" -> {
                RoleCard("善问 · AI 学生", "老师，请您像讲给一个12岁的孩子听那样，用自己的话解释『${lesson.topic}』。", true, readAloud)
                Text("不要查资料，也不必追求完美。第一次表达越真实，越容易发现盲点。", color = Color.Gray, fontSize = 13.sp)
            }
            "ASK" -> {
                if (lesson.dialogue.isNotEmpty()) {
                    Text("你此前已经回答了 ${lesson.dialogue.size} 个问题。", color = Color.Gray, fontSize = 13.sp)
                }
                RoleCard("善问 · AI 学生", lesson.currentQuestion, true, readAloud)
            }
            "REVIEW" -> {
                RoleCard("明辨 · AI 助教", "我根据你的第一次讲解和三轮追问做了分析。评分只用于跟踪本轮学习表现。")
                EvaluationCard(lesson.evaluationJson)
                Button(onClick = onRevise, modifier = Modifier.fillMaxWidth()) { Text("我来重新讲解") }
            }
            "REWRITE" -> {
                RoleCard("明辨 · AI 助教", "现在请不用照抄分析，用你自己的话重新讲明白『${lesson.topic}』。")
                EvaluationCard(lesson.evaluationJson)
            }
            "DONE" -> {
                RoleCard("明辨 · AI 助教", lesson.finalFeedback, false, readAloud)
                Text("修订后的讲解", fontWeight = FontWeight.Bold)
                Text(lesson.revisedExplanation)
                OutlinedButton(onClick = { navigate("home") }, modifier = Modifier.fillMaxWidth()) { Text("返回研习首页") }
            }
        }
        if (isEditable) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 155.dp), minLines = 5, maxLines = 12,
                enabled = !busy,
                placeholder = { Text(if (lesson.stage == "INTRO") "我认为这个概念的意思是……" else "老师，我的理解是……") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = voice, enabled = !busy) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(5.dp)); Text("语音输入")
                }
                Button(onClick = { onSend(input) }, enabled = input.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(when (lesson.stage) { "INTRO" -> "讲完了，请提问"; "ASK" -> "回答完成"; else -> "提交第二次讲解" })
                }
            }
        }
        if (lesson.dialogue.isNotEmpty() && lesson.stage != "ASK") {
            HorizontalDivider()
            Text("本轮课堂问答记录", fontWeight = FontWeight.SemiBold)
            lesson.dialogue.forEachIndexed { i, qa ->
                Text("第${i + 1}问：${qa.question}", color = Jade, fontWeight = FontWeight.SemiBold)
                Text(qa.answer, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RoleCard(label: String, message: String, allowRead: Boolean = false, readAloud: (String) -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF0EA))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoStories, contentDescription = null, tint = Jade, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, color = Jade, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (allowRead) IconButton(onClick = { readAloud(message) }) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "朗读", tint = Jade)
                }
            }
            Text(message, color = Ink, lineHeight = 25.sp)
        }
    }
}

@Composable
private fun EvaluationCard(jsonText: String) {
    val j = runCatching { JSONObject(jsonText) }.getOrNull()
    if (j == null) { Text("评价未能解析：$jsonText"); return }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("义理准确" to "accuracy", "逻辑自洽" to "logic", "表达清晰" to "clarity", "生活应用" to "application")
                    .forEach { (label, key) ->
                        val score = j.optInt(key).coerceIn(0, 5)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, modifier = Modifier.width(86.dp), fontSize = 13.sp)
                            LinearProgressIndicator(progress = { score / 5f }, modifier = Modifier.weight(1f).height(8.dp), color = Jade)
                            Spacer(Modifier.width(8.dp))
                            Text("$score/5", fontSize = 12.sp)
                        }
                    }
            }
        }
        listOf("已经理解的" to "strengths", "需要澄清的" to "issues", "建议核查经论" to "verify", "再讲一次的提示" to "suggestion")
            .forEach { (heading, key) ->
                Text(heading, color = Jade, fontWeight = FontWeight.Bold)
                Text(j.optString(key).ifBlank { "暂无" }, lineHeight = 23.sp)
            }
        Text("注意：本评价由 AI 生成，不能替代查阅经典、可靠注疏或老师指导。", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun HistoryScreen(history: List<Lesson>, open: (Lesson) -> Unit) {
    if (history.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.MenuBook, contentDescription = null, tint = Jade, modifier = Modifier.size(50.dp))
            Spacer(Modifier.height(12.dp))
            Text("尚无研习笔记", fontWeight = FontWeight.Bold)
            Text("完成一次费曼课堂后，这里会保存你的回答与评估。", fontSize = 13.sp, color = Color.Gray)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(history) { item ->
                ElevatedCard(onClick = { open(item) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.topic, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt)),
                            fontSize = 12.sp, color = Color.Gray)
                        Text(item.revisedExplanation.take(86), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DeepSeek API", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (hasKey) "状态：已保存加密密钥" else "状态：尚未配置", color = if (hasKey) Jade else Gold)
        OutlinedTextField(value = draft, onValueChange = changeDraft, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("API Key") },
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(
                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "显示/隐藏密钥") } }
        )
        Button(onClick = save, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("加密保存密钥") }
        if (hasKey) OutlinedButton(onClick = delete, modifier = Modifier.fillMaxWidth()) { Text("删除本机密钥") }
        HorizontalDivider()
        Text("接口地址", fontWeight = FontWeight.SemiBold)
        Text("https://api.deepseek.com/chat/completions", fontSize = 13.sp)
        Text("模型：deepseek-flash", color = Jade)
        Text("隐私与费用", fontWeight = FontWeight.SemiBold)
        Text("密钥仅存储在本机并由 Android Keystore 加密；学习内容将在请求时发送给 DeepSeek 处理。本 App 没有自建中转服务器，不会替你支付 API 费用。", lineHeight = 23.sp)
        Text("建议为 API 账户设定充值和费用上限。不要把包含 API Key 的截图或录屏发给他人。", color = Color.Gray, fontSize = 13.sp)
        Text("语音输入依赖手机安装的系统识别服务，其隐私政策可能与 DeepSeek 不同。", color = Color.Gray, fontSize = 13.sp)
    }
}
