package com.mingxin.feynman.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingxin.feynman.data.DoubtEntity
import com.mingxin.feynman.data.QuestionEntity
import com.mingxin.feynman.data.byId
import com.mingxin.feynman.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 疑点页：SRS 驱动的复习队列。
 * 未决疑点按到期时间排序，到期项直接给 4 档评分（忘记/吃力/想起/轻松），
 * 而不是让用户自己判断"消除了没有"——那件事应该由调度器根据你的表现来定。
 */
@Composable
fun DoubtsScreen(
    doubts: List<DoubtEntity>,
    onToggle: (DoubtEntity) -> Unit,
    onReview: (DoubtEntity, Int) -> Unit,
    questions: List<QuestionEntity>,
    onOpen: (QuestionEntity) -> Unit
) {
    val now = System.currentTimeMillis()
    val due = doubts.filter { !it.resolved && it.nextReviewAt in 1..now }.sortedBy { it.nextReviewAt }
    val scheduled = doubts.filter { !it.resolved && it.nextReviewAt > now }.sortedBy { it.nextReviewAt }
    val unscheduled = doubts.filter { !it.resolved && it.nextReviewAt == 0L }
    val closed = doubts.filter { it.resolved }

    if (doubts.isEmpty()) {
        EmptyState(
            "尚无疑点",
            "课堂评估中发现的薄弱处会自动沉淀到这里，并按记忆曲线回来找你。"
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text(
                    "待复习 ${due.size}",
                    fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = Ink
                )
                if (due.isEmpty()) {
                    Text(
                        if (scheduled.isNotEmpty()) "今天没有到期的疑点。下一次：${dateLabel(scheduled.first().nextReviewAt)}"
                        else "全部消除，境界日进。",
                        color = Jade, fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        items(due, key = { it.id }) { d ->
            ReviewCard(d, questions, onReview) { q -> onOpen(q) }
        }

        if (scheduled.isNotEmpty()) {
            item {
                Spacer(Modifier.height(10.dp))
                Text("已排程 ${scheduled.size}", color = Ink70, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            items(scheduled, key = { it.id }) { d ->
                PlainDoubtCard(d, questions, onToggle) { q -> onOpen(q) }
            }
        }

        if (unscheduled.isNotEmpty()) {
            item {
                Spacer(Modifier.height(10.dp))
                Text("未排程 ${unscheduled.size}", color = Ink70, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            items(unscheduled, key = { it.id }) { d ->
                PlainDoubtCard(d, questions, onToggle) { q -> onOpen(q) }
            }
        }

        if (closed.isNotEmpty()) {
            item {
                Spacer(Modifier.height(10.dp))
                Text("已消除 ${closed.size}", color = Ink70, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            items(closed, key = { it.id }) { d ->
                PlainDoubtCard(d, questions, onToggle) { q -> onOpen(q) }
            }
        }
    }
}

/** 到期疑点：直接给四档评分，这才是 SRS 的入口 */
@Composable
private fun ReviewCard(
    d: DoubtEntity,
    questions: List<QuestionEntity>,
    onReview: (DoubtEntity, Int) -> Unit,
    onOpen: (QuestionEntity) -> Unit
) {
    val source = questions.byId(d.questionId)
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Gold),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.RadioButtonUnchecked, null,
                    tint = Gold, modifier = Modifier.size(18.dp).padding(top = 2.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    d.text, fontSize = 14.sp, lineHeight = 21.sp, color = Ink,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${d.topic} · 上次间隔 ${if (d.intervalDays <= 0) "首次" else "${d.intervalDays} 天"}",
                fontSize = 12.sp, color = Ink70
            )
            Spacer(Modifier.height(10.dp))
            Text("现在还能讲清楚吗？", fontSize = 12.sp, color = Ink70)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradeChip("忘记了", Vermilion) { onReview(d, 1) }
                GradeChip("有点吃力", Gold) { onReview(d, 2) }
                GradeChip("讲得清", Jade) { onReview(d, 3) }
                GradeChip("很熟练", JadeDeep) { onReview(d, 4) }
            }
            if (source != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "去研习原题", color = Jade, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onOpen(source) }
                )
            }
        }
    }
}

@Composable
private fun GradeChip(label: String, tint: Color, onClick: () -> Unit) {
    Surface(
        color = tint.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

/** 非到期疑点：常规展示 + 手动消除 */
@Composable
private fun PlainDoubtCard(
    d: DoubtEntity,
    questions: List<QuestionEntity>,
    onToggle: (DoubtEntity) -> Unit,
    onOpen: (QuestionEntity) -> Unit
) {
    val source = questions.byId(d.questionId)
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (d.resolved) Ink12 else Ink12),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (d.resolved) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (d.resolved) Jade else Gold,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.text, fontSize = 14.sp, lineHeight = 21.sp,
                    color = if (d.resolved) Ink70 else Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(d.topic)
                        when {
                            d.resolved -> append(" · 已消除")
                            d.nextReviewAt > 0 -> append(" · ${dateLabel(d.nextReviewAt)}复习")
                            else -> append(" · " + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(d.createdAt)))
                        }
                    },
                    fontSize = 12.sp, color = Ink70, maxLines = 1
                )
            }
            if (source != null && !d.resolved) {
                Text(
                    "去研习", color = Jade, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.clickable { onOpen(source) }
                )
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = { onToggle(d) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (d.resolved) Icons.Default.Refresh else Icons.Default.Check,
                    contentDescription = if (d.resolved) "标记为未决" else "标记为已消除",
                    tint = if (d.resolved) Ink70 else Jade,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lightbulb, null, tint = Jade, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 13.sp, color = Ink70, lineHeight = 20.sp)
    }
}

private fun dateLabel(ts: Long): String {
    val fmt = SimpleDateFormat("MM-dd", Locale.getDefault())
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val target = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    return if (target == today.format(Date())) "今天" else fmt.format(Date(ts))
}
