package com.mingxin.feynman.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingxin.feynman.ui.QaItem
import com.mingxin.feynman.ui.theme.*

/**
 * 认领卡点 —— 整个体系的枢纽。
 *
 * 设计意图：AI 只是把你逼到墙边，墙在哪必须由你说。
 * 这里列出刚才所有的追问，你选出哪一句真的让你卡住了，
 * 然后用自己的话写下"我卡在哪"。
 *
 * 这是把定位权从 AI 收回给人的地方。
 */
@Composable
fun ClaimScreen(
    dialogue: List<QaItem>,
    claimed: List<String>,
    onClaim: (stuckAt: String, trigger: String) -> Unit,
    onFinish: () -> Unit
) {
    val cc = MingTheme.colors
    var selectedTrigger by remember { mutableStateOf("") }
    var stuckText by remember { mutableStateOf("") }
    var viewingAll by remember { mutableStateOf(true) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("哪里卡住了？", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = cc.ink)
                Text(
                    "下面这些问题，哪个让你答不下去、或者答得心虚？\n" +
                        "选一个，再用自己的话写下你卡在哪。这个问题会成为你的「理解卡」，日后回来重讲。",
                    fontSize = 13.sp, lineHeight = 21.sp, color = cc.ink70
                )
            }
        }

        if (!viewingAll) {
            item {
                TextButton(onClick = { viewingAll = true; selectedTrigger = "" }) {
                    Text("← 重新选择", color = cc.jade, fontSize = 13.sp)
                }
            }
        }

        item {
            Surface(
                color = cc.jade10, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "你也可以说：我其实讲通了，没有卡住。\n那就直接点最下面的「完成认领」。",
                    fontSize = 12.sp, lineHeight = 19.sp, color = cc.jadeDeep,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        itemsIndexed(dialogue) { i, qa ->
            val num = i + 1
            val ok = selectedTrigger == qa.question
            Surface(
                color = if (ok) cc.jade10 else Color.White,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = if (ok) 4.dp else 1.dp,
                border = BorderStroke(1.5.dp, if (ok) cc.jade else cc.ink12),
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedTrigger = if (ok) "" else qa.question
                    stuckText = ""
                }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("追问 $num", fontSize = 11.sp, color = cc.goldText, fontWeight = FontWeight.Bold)
                    Text(qa.question, fontSize = 15.sp, lineHeight = 22.sp, color = cc.ink, fontWeight = FontWeight.Medium)
                    if (qa.answer.isNotBlank()) {
                        Text(
                            "你当时答：${qa.answer}",
                            fontSize = 13.sp, lineHeight = 20.sp, color = cc.ink70, maxLines = 3
                        )
                    }
                }
            }
        }

        if (selectedTrigger.isNotBlank()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("用你自己的话，写下卡在哪", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cc.ink)
                    Text(
                        "不要复述那个问题。要写下你真正不懂的是什么。\n例：「我不懂'无分别智'和'不分别'到底差在哪里」",
                        fontSize = 12.sp, lineHeight = 18.sp, color = cc.ink70
                    )
                    OutlinedTextField(
                        value = stuckText, onValueChange = { stuckText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        minLines = 3, maxLines = 6,
                        placeholder = { Text("我卡在……", color = cc.ink70, fontSize = 14.sp) }
                    )
                    Button(
                        onClick = {
                            if (stuckText.isNotBlank()) {
                                onClaim(stuckText.trim(), selectedTrigger)
                                stuckText = ""
                                selectedTrigger = ""
                            }
                        },
                        enabled = stuckText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = BtnShape
                    ) { Text("确认这个卡点", fontSize = 15.sp) }
                }
            }
        }

        if (claimed.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已认领 ${claimed.size} 个卡点", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cc.jade)
                    claimed.forEach { c ->
                        Surface(
                            color = Color.White, shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, cc.jade.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Text("●", color = cc.jade, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(c, fontSize = 13.sp, lineHeight = 20.sp, color = cc.ink)
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (claimed.isNotEmpty()) {
                    Button(
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = BtnShape,
                        colors = ButtonDefaults.buttonColors(containerColor = cc.jade)
                    ) {
                        Text("带着这些问题去查，回来重讲（${claimed.size}）", fontSize = 14.sp, maxLines = 1)
                    }
                }
                TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (claimed.isEmpty()) "我讲通了，没有卡住 →" else "先不认领更多，直接重讲",
                        color = cc.ink70, fontSize = 13.sp
                    )
                }
            }
        }
    }
}
