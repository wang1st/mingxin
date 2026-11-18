package com.mingxin.feynman.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.mingxin.feynman.data.InsightEntity
import com.mingxin.feynman.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 理解卡页 —— 复习对象是你自己的卡点，不是 AI 的评语。
 *
 * 复习时亮出你当时的原话，让你对照"现在讲得不一样了吗"。
 * 这就是间隔复习真正该做的事：复习你自己的认知轨迹。
 */
@Composable
fun InsightsScreen(
    insights: List<InsightEntity>,
    onReview: (InsightEntity, Int, String) -> Unit,
    onPark: (InsightEntity) -> Unit,
    onReopen: (InsightEntity) -> Unit,
    onDelete: (InsightEntity) -> Unit
) {
    val now = System.currentTimeMillis()
    val due = insights.filter { it.status == "OPEN" && it.nextReviewAt in 1..now }.sortedBy { it.nextReviewAt }
    val scheduled = insights.filter { it.status == "OPEN" && it.nextReviewAt > now }.sortedBy { it.nextReviewAt }
    val unscheduled = insights.filter { it.status == "OPEN" && it.nextReviewAt == 0L }
    val parked = insights.filter { it.status == "PARKED" }
    val resolved = insights.filter { it.status == "RESOLVED" }

    if (insights.isEmpty()) {
        EmptyInsights()
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("待重讲 ${due.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    when {
                        due.isNotEmpty() -> "这些问题是你自己认领的。现在讲得通了吗？"
                        scheduled.isNotEmpty() -> "今天没有到期的卡点。下一次：${dateLabel(scheduled.first().nextReviewAt)}"
                        else -> "还没有卡点。上完一节课后，你会在这里认领自己的卡点。"
                    },
                    fontSize = 13.sp, lineHeight = 20.sp, color = Ink70
                )
            }
        }

        items(due, key = { it.id }) { item ->
            DueInsightCard(item, onReview, onPark)
        }

        if (scheduled.isNotEmpty()) {
            item { SectionLabel("已排程 ${scheduled.size}") }
            items(scheduled, key = { it.id }) { item ->
                CompactInsightCard(item, onReopen, onDelete) { dateLabel(item.nextReviewAt) + "重讲" }
            }
        }
        if (unscheduled.isNotEmpty()) {
            item { SectionLabel("未排程 ${unscheduled.size}") }
            items(unscheduled, key = { it.id }) { item ->
                CompactInsightCard(item, onReopen, onDelete) { "待排程" }
            }
        }
        if (parked.isNotEmpty()) {
            item { SectionLabel("搁置 ${parked.size}") }
            items(parked, key = { it.id }) { item ->
                CompactInsightCard(item, onReopen, onDelete) { "已搁置" }
            }
        }
        if (resolved.isNotEmpty()) {
            item { SectionLabel("已想通 ${resolved.size}") }
            items(resolved, key = { it.id }) { item ->
                CompactInsightCard(item, onReopen, onDelete) { "已想通" }
            }
        }
    }
}

/** 到期卡：直接给四档评价 + 重讲输入 */
@Composable
private fun DueInsightCard(
    item: InsightEntity,
    onReview: (InsightEntity, Int, String) -> Unit,
    onPark: (InsightEntity) -> Unit
) {
    var retell by remember(item.id) { mutableStateOf("") }
    var expanding by remember(item.id) { mutableStateOf(false) }

    Surface(
        color = Color.White,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
        border = BorderStroke(1.5.dp, Gold),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Gold12, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        item.path, fontSize = 11.sp, color = GoldText, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (item.intervalDays <= 0) "首次" else "间隔 ${item.intervalDays} 天",
                    fontSize = 11.sp, color = Ink70
                )
            }

            Text(item.stuckAt, fontSize = 15.sp, lineHeight = 23.sp, color = Ink, fontWeight = FontWeight.Medium)

            if (item.triggerQuestion.isNotBlank()) {
                Text(
                    "当时被问：${item.triggerQuestion}",
                    fontSize = 12.sp, lineHeight = 18.sp, color = Ink70, maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(item.topic, fontSize = 11.sp, color = Ink70)

            HorizontalDivider(color = Ink12, thickness = 0.5.dp)

            Text("现在能讲通了吗？", fontSize = 12.sp, color = Ink70)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                GradeChip("还是卡", Vermilion) { onReview(item, 1, retell) }
                GradeChip("说清些", Gold) { onReview(item, 2, retell) }
                GradeChip("讲通了", Jade) { onReview(item, 3, retell) }
                GradeChip("很熟", JadeDeep) { onReview(item, 4, retell) }
            }

            if (!expanding) {
                Text(
                    "＋ 重讲一遍（可选）", color = Jade, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { expanding = true }
                )
            } else {
                OutlinedTextField(
                    value = retell, onValueChange = { retell = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    minLines = 3, maxLines = 5,
                    placeholder = { Text("这次我这样讲……", fontSize = 13.sp, color = Ink70) }
                )
                Text(
                    "写完再点上面的评价，会一起记下来。",
                    fontSize = 11.sp, color = Ink70
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "先搁置", color = Ink70, fontSize = 12.sp,
                    modifier = Modifier.clickable { onPark(item) }
                )
            }
        }
    }
}

@Composable
private fun CompactInsightCard(
    item: InsightEntity,
    onReopen: (InsightEntity) -> Unit,
    onDelete: (InsightEntity) -> Unit,
    trailing: () -> String
) {
    val isDone = item.status == "RESOLVED"
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, Ink12),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                item.stuckAt, fontSize = 14.sp, lineHeight = 21.sp,
                color = if (isDone) Ink70 else Ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${item.path} · ${item.topic} · ${trailing()}",
                    fontSize = 11.sp, color = Ink70, maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                if (isDone) {
                    Text("重新打开", color = Jade, fontSize = 11.sp,
                        modifier = Modifier.clickable { onReopen(item) })
                    Spacer(Modifier.width(10.dp))
                }
                Icon(
                    Icons.Default.Close, "删除", tint = Ink70,
                    modifier = Modifier.size(15.dp).clickable { onDelete(item) }
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
            label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Ink70, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun EmptyInsights() {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lightbulb, null, tint = Jade, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text("还没有理解卡", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "上完一节课，你会自己认领「哪里卡住了」，\n那个问题就会变成一张理解卡，按时回来找你重讲。",
            fontSize = 13.sp, color = Ink70, lineHeight = 21.sp
        )
    }
}

private fun dateLabel(ts: Long): String {
    val target = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    return if (target == today) "今天" else SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
}
