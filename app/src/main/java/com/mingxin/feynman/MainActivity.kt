package com.mingxin.feynman

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.mingxin.feynman.data.Exporter
import com.mingxin.feynman.data.InsightEntity
import com.mingxin.feynman.data.QuestionEntity
import com.mingxin.feynman.data.SessionEntity
import com.mingxin.feynman.data.TopicGroup
import com.mingxin.feynman.data.byId
import com.mingxin.feynman.data.sutrasOf
import com.mingxin.feynman.data.topicsOf
import com.mingxin.feynman.ui.LessonViewModel
import com.mingxin.feynman.ui.QaItem
import com.mingxin.feynman.ui.SessionUi
import com.mingxin.feynman.ui.screens.ClaimScreen
import com.mingxin.feynman.ui.screens.InsightsScreen
import com.mingxin.feynman.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 可选的学习路径。佛学没有唯一解释，讲之前先声明你站在哪条路上。 */
val PATH_OPTIONS = listOf("不限", "中观", "唯识", "如来藏", "戒律", "净土")

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var speaking by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                val vm: LessonViewModel = viewModel()
                val session by vm.session.collectAsStateWithLifecycle()
                val busy by vm.busy.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()
                val hasKey by vm.hasKey.collectAsStateWithLifecycle()
                val ready by vm.ready.collectAsStateWithLifecycle()
                val questions by vm.questions.collectAsStateWithLifecycle()
                val insights by vm.insights.collectAsStateWithLifecycle()
                val sessions by vm.sessions.collectAsStateWithLifecycle()
                val dueCount by vm.dueCount.collectAsStateWithLifecycle()
                val sessionInsights by vm.sessionInsights.collectAsStateWithLifecycle()

                val navController = androidx.navigation.compose.rememberNavController()
                navControllerHook = { route -> navController.navigate(route) { launchSingleTop = true } }
                val backStackEntry by navController.currentBackStackEntryFlow.collectAsState(initial = null)
                val page = backStackEntry?.destination?.route ?: "home"

                if (!ready) {
                    LoadingScreen()
                } else {
                    MainUI(
                        page = page,
                        navController = navController,
                        session = session,
                        sessionInsights = sessionInsights,
                        sessions = sessions,
                        insights = insights,
                        questions = questions,
                        dueCount = dueCount,
                        busy = busy,
                        error = error,
                        hasKey = hasKey,
                        speaking = speaking,
                        onStartQuestion = { q, path -> vm.startQuestion(q, path); go("lesson") },
                        onCustomTopic = { t, path -> vm.startCustom(t, path); go("lesson") },
                        onOpenSession = { s -> vm.openSession(s); go("lesson") },
                        onSubmitFirst = vm::submitFirstExplanation,
                        onAnswer = vm::answerQuestion,
                        onGoClaim = { vm.goToClaim(); go("claim") },
                        onClaim = vm::claimInsight,
                        onFinishClaim = { vm.finishClaiming(); go("lesson") },
                        onSubmitRevision = vm::submitRevision,
                        onReviewInsight = vm::reviewInsight,
                        onParkInsight = vm::parkInsight,
                        onReopenInsight = vm::reopenInsight,
                        onDeleteInsight = vm::deleteInsight,
                        onSaveKey = vm::saveKey,
                        onDeleteKey = vm::deleteKey,
                        onSpeech = { phrase -> speak(phrase) },
                        onExport = { kind ->
                            lifecycleScope.launch {
                                val (name, content, mime) = when (kind) {
                                    "json" -> Triple(
                                        Exporter.stampName("mingxin-data", "json"),
                                        Exporter.toJson(vm.repositoryForExport()), "application/json"
                                    )
                                    else -> Triple(
                                        Exporter.stampName("mingxin-notes", "md"),
                                        Exporter.toMarkdown(vm.repositoryForExport()), "text/markdown"
                                    )
                                }
                                shareFile(name, content, mime)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun shareFile(name: String, content: String, mime: String) {
        runCatching {
            val file = Exporter.writeTemp(this, name, content)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "明心导出 $name")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出"))
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        if (speaking == text) { tts?.stop(); speaking = ""; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lesson_question")
        speaking = text
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}

/** Composable 内部无法直接持有 Activity 方法，用这个钩子暴露导航 */
private var navControllerHook: ((String) -> Unit)? = null
private fun go(route: String) { navControllerHook?.invoke(route) }

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Jade)
    }
}


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
    navController: androidx.navigation.NavHostController,
    session: SessionUi,
    sessionInsights: List<InsightEntity>,
    sessions: List<SessionEntity>,
    insights: List<InsightEntity>,
    questions: List<QuestionEntity>,
    dueCount: Int,
    busy: Boolean,
    error: String,
    hasKey: Boolean,
    speaking: String,
    onStartQuestion: (QuestionEntity, String) -> Unit,
    onCustomTopic: (String, String) -> Unit,
    onOpenSession: (SessionEntity) -> Unit,
    onSubmitFirst: (String) -> Unit,
    onAnswer: (String) -> Unit,
    onGoClaim: () -> Unit,
    onClaim: (String, String) -> Unit,
    onFinishClaim: () -> Unit,
    onSubmitRevision: (String) -> Unit,
    onReviewInsight: (InsightEntity, Int, String) -> Unit,
    onParkInsight: (InsightEntity) -> Unit,
    onReopenInsight: (InsightEntity) -> Unit,
    onDeleteInsight: (InsightEntity) -> Unit,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onSpeech: (String) -> Unit,
    onExport: (String) -> Unit
) {
    var keyDraft by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<TopicGroup?>(null) }
    var selectedPath by rememberSaveable { mutableStateOf("不限") }
    var pendingQuestion by remember { mutableStateOf<QuestionEntity?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    var customDraft by remember { mutableStateOf("") }

    if (showCustom) {
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("自定义课题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customDraft, onValueChange = { customDraft = it },
                        modifier = Modifier.fillMaxWidth(), maxLines = 2,
                        placeholder = { Text("例如：因果与自由意志") }
                    )
                    Text("站在哪条路径上讲？", fontSize = 12.sp, color = Ink70)
                    PathChips(selectedPath) { selectedPath = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (customDraft.isNotBlank()) {
                            onCustomTopic(customDraft.trim(), selectedPath)
                            customDraft = ""; showCustom = false
                        }
                    },
                    enabled = customDraft.isNotBlank()
                ) { Text("开始", color = Jade) }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("取消", color = Ink70) } }
        )
    }

    if (pendingQuestion != null) {
        AlertDialog(
            onDismissRequest = { pendingQuestion = null },
            title = { Text("开始新课？") },
            text = { Text("有一节课还没讲完（${session.topic}），开始新课会放弃它。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingQuestion?.let { onStartQuestion(it, selectedPath) }
                    pendingQuestion = null
                }) { Text("放弃并开始", color = Vermilion) }
            },
            dismissButton = { TextButton(onClick = { pendingQuestion = null }) { Text("取消", color = Ink70) } }
        )
    }

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            if (page != "lesson" && page != "claim") {
                Box {
                    Surface(color = Color.White, shadowElevation = 10.dp) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NavEntry("home", "研习", Icons.Default.Home, page, Modifier.weight(1f)) {
                                navController.navigate("home") { popUpTo("home") { inclusive = true } }
                            }
                            NavEntry("insights", "理解卡", Icons.Default.Lightbulb, page, Modifier.weight(1f)) {
                                navController.navigate("insights") { popUpTo("home") }
                            }
                            Spacer(Modifier.width(64.dp))
                            NavEntry("history", "笔记", Icons.Default.MenuBook, page, Modifier.weight(1f)) {
                                navController.navigate("history") { popUpTo("home") }
                            }
                            NavEntry("settings", "设置", Icons.Default.Settings, page, Modifier.weight(1f)) {
                                navController.navigate("settings") { popUpTo("home") }
                            }
                        }
                    }
                }
            }
        }
    ) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            if (error.isNotBlank() && page != "lesson" && page != "claim") {
                Surface(color = Vermilion12, modifier = Modifier.fillMaxWidth()) {
                    Text(error, color = Vermilion, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
            NavHost(navController = navController, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                composable("home") {
                    HomeScreen(
                        session = session, sessions = sessions, insights = insights,
                        questions = questions, dueCount = dueCount, selectedPath = selectedPath,
                        onPathChange = { selectedPath = it },
                        onStart = { q ->
                            if (session.isActive) pendingQuestion = q else onStartQuestion(q, selectedPath)
                        },
                        onResume = { navController.navigate("lesson") { launchSingleTop = true } },
                        onBrowse = { navController.navigate("browse") },
                        onInsights = { navController.navigate("insights") { popUpTo("home") } },
                        onCustom = { showCustom = true }
                    )
                }
                composable("browse") {
                    BrowseScreen(
                        questions = questions,
                        onPickGroup = { g -> selectedGroup = g; navController.navigate("topic") },
                        onCustom = { showCustom = true }
                    )
                }
                composable("topic") {
                    val g = selectedGroup
                    if (g != null) {
                        TopicScreen(g) { q ->
                            if (session.isActive) pendingQuestion = q else onStartQuestion(q, selectedPath)
                        }
                    } else {
                        LaunchedEffect(Unit) { navController.navigate("home") { popUpTo("home") { inclusive = true } } }
                        Spacer(Modifier.fillMaxSize())
                    }
                }
                composable("insights") {
                    InsightsScreen(insights, onReviewInsight, onParkInsight, onReopenInsight, onDeleteInsight)
                }
                composable("history") {
                    HistoryScreen(sessions, { s -> onOpenSession(s) }, { onExport("markdown") })
                }
                composable("settings") {
                    SettingsScreen(hasKey, keyDraft, { keyDraft = it }, { onSaveKey(keyDraft); keyDraft = "" }, onDeleteKey)
                }
                composable("claim") {
                    ClaimScreen(
                        dialogue = session.dialogue,
                        claimed = sessionInsights.map { it.stuckAt },
                        onClaim = onClaim,
                        onFinish = onFinishClaim
                    )
                }
                composable("lesson") {
                    LessonScreen(
                        session = session, sessionInsights = sessionInsights, busy = busy,
                        error = error, speaking = speaking,
                        onSubmitFirst = onSubmitFirst, onAnswer = onAnswer,
                        onGoClaim = onGoClaim, onSubmitRevision = onSubmitRevision,
                        onSpeech = onSpeech, questions = questions, onSubmitSuccess = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun PathChips(current: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PATH_OPTIONS.forEach { p ->
            val sel = p == current
            Surface(
                color = if (sel) Jade else Color.White,
                shape = RoundedCornerShape(14.dp),
                border = if (sel) null else BorderStroke(1.dp, Ink12),
                modifier = Modifier.clickable { onChange(p) }
            ) {
                Text(
                    p, fontSize = 12.sp, maxLines = 1,
                    color = if (sel) Color.White else Ink70,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (h < 5) "夜深了" else if (h < 12) "早上好" else if (h < 18) "下午好" else "晚上好"
}

/** 首页：今天该重讲什么 + 开始新的一课 */
@Composable
private fun HomeScreen(
    session: SessionUi,
    sessions: List<SessionEntity>,
    insights: List<InsightEntity>,
    questions: List<QuestionEntity>,
    dueCount: Int,
    selectedPath: String,
    onPathChange: (String) -> Unit,
    onStart: (QuestionEntity) -> Unit,
    onResume: () -> Unit,
    onBrowse: () -> Unit,
    onInsights: () -> Unit,
    onCustom: () -> Unit
) {
    val practiced = remember(sessions.size) {
        sessions.filter { it.finishedAt > 0 }.map { it.questionId }.filter { it.isNotBlank() }.toSet()
    }
    val remaining = questions.filter { it.id !in practiced }
    val next = remaining.minByOrNull { it.difficulty } ?: questions.randomOrNull()
    val resolvedCount = insights.count { it.status == "RESOLVED" }
    val openCount = insights.count { it.status == "OPEN" }
    val inFlight = session.isActive

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Jade10),
                    contentAlignment = Alignment.Center
                ) { Text("明", color = JadeDeep, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${greeting()}，欢迎回来", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("今日也来讲一课", fontSize = 13.sp, color = Ink70)
                }
            }
        }

        // 今日该做的事：待重讲的卡
        item {
            Surface(
                color = Color.White, shape = CardShape, shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(16.dp).clickable { if (dueCount > 0) onInsights() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (dueCount > 0) "今天有 $dueCount 个卡点该重讲了" else "今天没有到期的卡点",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = if (dueCount > 0) GoldText else Ink
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "已想通 $resolvedCount · 悬而未决 $openCount",
                            fontSize = 12.sp, color = Ink70
                        )
                    }
                    if (dueCount > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Gold, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // 未完成的课
        if (inFlight) {
            item {
                Surface(
                    color = Jade, shape = CardShape, shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth().clickable { onResume() }
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("继续上次的研习", color = Color(0xFFBFD8CC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(session.topic, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(
                            when (session.stage) {
                                "INTRO" -> "还没开始初讲"
                                "ASK" -> "善问正在追问你"
                                "CLAIM" -> "去认领你的卡点"
                                "REVISIT" -> "回去查过了，来重讲"
                                else -> "继续"
                            },
                            color = Color(0xFFBFD8CC), fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 新的一课
        if (next != null && !inFlight) {
            item {
                Surface(
                    color = Jade, shape = CardShape, shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("下一题 · ${next.sutra}", color = Color(0xFFBFD8CC), fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            DifficultyDots(next.difficulty, Color.White)
                        }
                        Text(next.question, color = Color.White, fontSize = 18.sp,
                            lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                        Text("拟站在：$selectedPath", color = Color(0xFFBFD8CC), fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PATH_OPTIONS.take(4).forEach { p ->
                                val sel = p == selectedPath
                                Surface(
                                    color = if (sel) Color.White else Color.White.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.clickable { onPathChange(p) }
                                ) {
                                    Text(
                                        p, fontSize = 11.sp, maxLines = 1,
                                        color = if (sel) JadeDeep else Color.White,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { onStart(next) },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = BtnShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = JadeDeep)
                        ) { Text("开始研习", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        item {
            Text("按经论选", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickChip("自由选题", Icons.Default.MenuBook, Modifier.weight(1f)) { onBrowse() }
                QuickChip("理解卡", Icons.Default.Lightbulb, Modifier.weight(1f)) {
                    if (dueCount > 0) onInsights() else onInsights()
                }
                QuickChip("自定义", Icons.Default.Edit, Modifier.weight(1f)) { onCustom() }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
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
            Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = Jade, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 13.sp, color = Ink, maxLines = 1)
        }
    }
}

@Composable
private fun BrowseScreen(
    questions: List<QuestionEntity>,
    onPickGroup: (TopicGroup) -> Unit,
    onCustom: () -> Unit
) {
    var sutra by remember { mutableStateOf(questions.sutrasOf().firstOrNull().orEmpty()) }
    val topics = remember(sutra, questions.size) { questions.topicsOf(sutra) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自由选题", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
        }
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            questions.sutrasOf().forEach { name ->
                val sel = name == sutra
                Surface(
                    color = if (sel) Jade else Color.White,
                    shape = RoundedCornerShape(18.dp),
                    border = if (sel) null else BorderStroke(1.dp, Ink12),
                    modifier = Modifier.clickable { sutra = name }
                ) {
                    Text(name, fontSize = 13.sp, maxLines = 1,
                        color = if (sel) Color.White else Ink70,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(topics) { group ->
                Surface(
                    color = Color.White, shape = CardShape, shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().clickable { onPickGroup(group) }
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.topic, fontSize = 16.sp, color = Ink, modifier = Modifier.weight(1f))
                        Text("${group.questions.size} 题", fontSize = 12.sp, color = Ink70)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Ink70, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicScreen(group: TopicGroup, onStart: (QuestionEntity) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(group.topic, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(group.sutra, fontSize = 12.sp, color = Ink70)
            Spacer(Modifier.height(4.dp))
        }
        items(group.questions) { q ->
            Surface(
                color = Color.White, shape = CardShape, shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().clickable { onStart(q) }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q.id, color = GoldText, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        DifficultyDots(q.difficulty)
                    }
                    Text(q.question, fontSize = 15.sp, lineHeight = 22.sp, color = Ink)
                }
            }
        }
    }
}

@Composable
private fun DifficultyDots(level: Int, tint: Color = Jade) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            Box(
                Modifier.size(5.dp).clip(CircleShape)
                    .background(if (i <= level) tint else Ink12)
            )
        }
    }
}


/* ------------------------------------------------------------------ */
/*  课堂：初讲 → 被追问 → 认领卡点 → 重讲                              */
/* ------------------------------------------------------------------ */

@Composable
private fun StageBar(stage: String) {
    val steps = listOf("初讲" to "INTRO", "被追问" to "ASK", "认领卡点" to "CLAIM", "重讲" to "REVISIT")
    val current = when (stage) {
        "INTRO" -> 0
        "ASK" -> 1
        "CLAIM" -> 2
        "REVISIT", "DONE" -> 3
        else -> 0
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, (label, _) ->
            val done = i < current
            val now = i == current
            Text(
                label,
                fontSize = 12.sp,
                color = if (now) Ink else if (done) Jade else Ink70.copy(alpha = 0.45f),
                fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            if (i < steps.lastIndex) {
                Box(
                    Modifier
                        .padding(horizontal = 7.dp)
                        .weight(1f)
                        .height(1.dp)
                        .background(if (i < current) Jade else Ink12)
                )
            }
        }
    }
}

@Composable
private fun LessonScreen(
    session: SessionUi,
    sessionInsights: List<InsightEntity>,
    busy: Boolean,
    error: String,
    speaking: String,
    onSubmitFirst: (String) -> Unit,
    onAnswer: (String) -> Unit,
    onGoClaim: () -> Unit,
    onSubmitRevision: (String) -> Unit,
    onSpeech: (String) -> Unit,
    questions: List<QuestionEntity>,
    onSubmitSuccess: () -> Unit
) {
    var input by rememberSaveable(session.stage, session.dialogue.size) { mutableStateOf("") }
    var selectedOption by rememberSaveable(session.dialogue.size) { mutableStateOf(-1) }
    val listState = rememberLazyListState()

    val choiceMode = session.stage == "ASK" && session.currentOptions.isNotEmpty()
    val editable = session.stage in listOf("INTRO", "ASK", "REVISIT")

    LaunchedEffect(session.dialogue.size, session.stage, session.feedback) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部：阶段
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            StageBar(session.stage)
            Spacer(Modifier.height(10.dp))
            Text(
                session.prompt.ifBlank { session.topic },
                fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, color = Ink
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.height(2.dp))
                Text(session.topic, fontSize = 11.sp, color = GoldText)
                Text(" · 路径：${session.path}", fontSize = 11.sp, color = Ink70)
            }
        }
        HorizontalDivider(color = Ink12, thickness = 0.5.dp)

        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (session.firstExplanation.isNotBlank()) {
                item { UserBubble(session.firstExplanation, "我的初讲") }
            }
            session.dialogue.forEachIndexed { _, qa ->
                item(key = "q-${qa.question.hashCode()}-${qa.answer.hashCode()}") {
                    StudentCard(qa.question, speaking == qa.question) { onSpeech(qa.question) }
                }
                item(key = "a-${qa.answer.hashCode()}-${qa.question.hashCode()}") {
                    UserBubble(qa.answer, "我的回答")
                }
            }
            if (session.currentQuestion.isNotBlank() && !busy) {
                item {
                    StudentCard(session.currentQuestion, speaking == session.currentQuestion) {
                        onSpeech(session.currentQuestion)
                    }
                }
            }
            if (busy) item { ThinkingCard() }

            if (session.stage == "CLAIM") {
                item {
                    Surface(
                        color = Gold12, shape = CardShape, modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("现在轮到你了", fontWeight = FontWeight.Bold, color = GoldText, fontSize = 15.sp)
                            Text(
                                "善问问了这些，你不必都答得上。\n下一步是：自己指出——哪一句真的让你卡住了。",
                                fontSize = 13.sp, lineHeight = 21.sp, color = Ink
                            )
                            Button(
                                onClick = onGoClaim,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = BtnShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Gold)
                            ) { Text("去认领卡点", fontSize = 14.sp) }
                        }
                    }
                }
            }

            if (session.stage == "REVISIT" && sessionInsights.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("你认领的卡点", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GoldText)
                        sessionInsights.forEach { c ->
                            Surface(
                                color = Color.White, shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Gold.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(c.stuckAt, fontSize = 14.sp, lineHeight = 21.sp, color = Ink)
                                    if (c.triggerQuestion.isNotBlank()) {
                                        Text("起因：${c.triggerQuestion}", fontSize = 11.sp, color = Ink70)
                                    }
                                }
                            }
                        }
                        Text(
                            "查过之后，针对这些问题重讲一遍。",
                            fontSize = 12.sp, color = Ink70, lineHeight = 18.sp
                        )
                    }
                }
            }

            if (session.stage == "REVISIT" && sessionInsights.isEmpty()) {
                item {
                    Surface(color = Jade10, shape = CardShape, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "你没有认领任何卡点——说明这次讲得比较透。\n也可以再讲一遍，看看能讲出什么新东西。",
                            fontSize = 13.sp, lineHeight = 21.sp, color = JadeDeep,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            if (session.revisedExplanation.isNotBlank()) {
                item { UserBubble(session.revisedExplanation, "我的重讲") }
            }

            if (session.feedback.isNotBlank()) {
                item { TutorCard(session.feedback) }
            }

            if (error.isNotBlank()) {
                item {
                    Surface(color = Vermilion12, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(error, color = Vermilion, fontSize = 13.sp, lineHeight = 20.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
        }

        // 底部输入区
        if (editable && session.stage != "CLAIM") {
            Surface(color = AppSurface, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    if (choiceMode) {
                        ChoicePanel(
                            options = session.currentOptions,
                            selected = selectedOption,
                            onSelect = { selectedOption = it },
                            input = input,
                            onInputChange = { input = it },
                            busy = busy,
                            sendLabel = "作答",
                            onSend = { text -> onAnswer(text); input = ""; selectedOption = -1 }
                        )
                    } else {
                        FreeTextPanel(
                            input = input,
                            onInputChange = { input = it },
                            busy = busy,
                            sendLabel = when (session.stage) {
                                "INTRO" -> "讲完了，让善问问"
                                "REVISIT" -> "重讲完了，让明辨看"
                                else -> "回答"
                            },
                            inputHint = when (session.stage) {
                                "INTRO" -> "我理解这个问题的意思是……"
                                "REVISIT" -> "针对刚才卡住的地方，这次我这样讲……"
                                else -> "我的回答是……"
                            },
                            onSend = { text ->
                                when (session.stage) {
                                    "INTRO" -> onSubmitFirst(text)
                                    "REVISIT" -> onSubmitRevision(text)
                                    else -> onAnswer(text)
                                }
                                input = ""
                            },
                            onFocused = {}
                        )
                    }
                    if (session.stage == "ASK" && session.currentQuestion.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "答不上来也没关系——点这里可以直接进入「认领卡点」",
                            fontSize = 11.sp, color = Jade, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().clickable { onGoClaim() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleAvatar(char: String, bg: Color, textColor: Color = Color.White) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(char, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                Text("善问 · AI 学生", color = Color(0xFFD9E8E0), fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (active) Icons.Default.Stop else Icons.Default.VolumeUp,
                        contentDescription = if (active) "停止朗读" else "朗读",
                        tint = if (active) Color(0xFFFFD9CC) else Color.White,
                        modifier = Modifier.size(17.dp)
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
            .fillMaxWidth(0.94f)
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
        Text(text, color = Ink, lineHeight = 24.sp, fontSize = 15.sp)
    }
}

@Composable
private fun UserBubble(text: String, label: String = "我的讲解") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Spacer(Modifier.weight(0.12f))
        Column(
            Modifier
                .weight(0.80f)
                .clip(CardShape)
                .background(Color.White)
                .border(0.5.dp, Ink12, CardShape)
                .padding(14.dp)
        ) {
            Text(label, color = Ink70, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Ink, lineHeight = 23.sp, fontSize = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        RoleAvatar("我", Ink)
    }
}

@Composable
private fun ThinkingCard() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dotAlphas = (0..2).map { i ->
        transition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, delayMillis = i * 200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot$i"
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoleAvatar("善", Color.White, JadeDeep)
        Spacer(Modifier.width(8.dp))
        Surface(color = Jade10, shape = CardShape) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("善问在想", color = JadeDeep, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                dotAlphas.forEach { a ->
                    Box(
                        Modifier.padding(horizontal = 1.dp).size(5.dp)
                            .clip(CircleShape).background(Jade.copy(alpha = a.value))
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    index: Int, text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit
) {
    Surface(
        color = if (selected) Jade10 else Color.White,
        shape = RoundedCornerShape(12.dp),
        border = if (selected) BorderStroke(1.5.dp, Jade) else BorderStroke(1.dp, Ink12),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .border(1.5.dp, if (selected) Color.White else Ink12, CircleShape)
                    .background(if (selected) Jade else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                else if (index >= 0) Text("${index + 1}", color = Ink70, fontSize = 11.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 14.sp, lineHeight = 21.sp, color = Ink, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChoicePanel(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    sendLabel: String,
    onSend: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val escape = selected == options.size
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEachIndexed { i, opt ->
            OptionRow(i, opt, selected == i, !busy) { onSelect(i) }
        }
        OptionRow(-1, "以上都不是", escape, !busy) { onSelect(options.size) }
        if (escape) {
            OutlinedTextField(
                value = input, onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                minLines = 2, maxLines = 4, enabled = !busy,
                placeholder = { Text("用你自己的话说……", color = Ink70, fontSize = 13.sp) }
            )
        }
        Button(
            onClick = {
                when {
                    escape -> onSend(input)
                    selected in options.indices -> onSend(options[selected])
                }
            },
            enabled = !busy && (if (escape) input.isNotBlank() else selected >= 0),
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = BtnShape
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(sendLabel, fontSize = 14.sp, maxLines = 1)
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .onFocusChanged { if (it.isFocused) onFocused() },
            minLines = 2, maxLines = 6, enabled = !busy,
            placeholder = { Text(inputHint, color = Ink70, fontSize = 14.sp) }
        )
        Button(
            onClick = { if (input.isNotBlank() && !busy) onSend(input) },
            enabled = input.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = BtnShape
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(sendLabel, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  笔记：每一节课的轨迹                                               */
/* ------------------------------------------------------------------ */

@Composable
private fun HistoryScreen(
    sessions: List<SessionEntity>,
    open: (SessionEntity) -> Unit,
    onExport: () -> Unit
) {
    val done = sessions.filter { it.finishedAt > 0 }
    if (done.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.MenuBook, null, tint = Jade, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("尚无研习笔记", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text("完成一节课后，你的初讲、卡点与重讲都会留在这里。",
                fontSize = 13.sp, color = Ink70, lineHeight = 21.sp)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("研习笔记", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            Text("导出", color = Jade, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onExport() })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(done, key = { it.id }) { s ->
                Surface(
                    color = Color.White, shape = CardShape, shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().clickable { open(s) }
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.finishedAt)),
                                fontSize = 11.sp, color = Ink70
                            )
                            Spacer(Modifier.weight(1f))
                            Surface(color = Jade10, shape = RoundedCornerShape(6.dp)) {
                                Text(s.path, fontSize = 10.sp, color = JadeDeep,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text(
                            s.prompt.ifBlank { s.topic }, fontSize = 15.sp, lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Text(s.topic, fontSize = 11.sp, color = GoldText)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  设置                                                               */
/* ------------------------------------------------------------------ */

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
            Text("学习方式", color = GoldText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "明心只做一件事：让你把理解讲出来，然后自己发现哪里没讲清。\n\n" +
                    "· 善问只负责把你逼到说不动的地方，不纠正、不给答案。\n" +
                    "· 卡点由你自己认领，不是 AI 判定的。\n" +
                    "· 理解卡会按记忆曲线回来找你重讲。\n" +
                    "· 明辨只做定性判断，不打分。",
                lineHeight = 21.sp, fontSize = 13.sp, color = Ink70
            )
        }
        item {
            Text("接口与模型", color = GoldText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("模型：deepseek-flash", color = Jade, fontSize = 13.sp)
        }
        item {
            Text("隐私与费用", color = GoldText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "密钥仅存储在本机并由 Android Keystore 加密；学习内容在请求时发送给 DeepSeek 处理。" +
                    "本 App 没有中转服务器，也不代付 API 费用。建议为账户设置费用上限。\n" +
                    "所有学习记录只存在本机，可随时在笔记页导出。",
                lineHeight = 21.sp, fontSize = 13.sp, color = Ink70
            )
        }
    }
}
