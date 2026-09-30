package com.example.greenframeocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.util.GeminiReceiptClient

// AI 科目提案の確認ダイアログ。JA 購買の商品・通帳の摘要パターン・レシートの品目グループで共用する

/**
 * AI 提案 1 件。[productId] は提案先の行の識別子（商品 id・摘要パターン id・品目グループの並び順）、
 * [key] は保存に使う科目の識別子（弥生は科目 id、あおいろは accountKey）、
 * [label] は「→」の右に出す科目の表示
 */
internal data class AiSuggestionRow<K>(
    val productId: Long,
    val productName: String,
    val key: K,
    val label: String,
    val reason: String
)

/**
 * AI提案確認ダイアログ（弥生・あおいろ共通）
 * onSave: Map<productId, key> — 承認した提案のみ保存
 */
@Composable
internal fun <K> AiMatchingDialog(
    rows: List<AiSuggestionRow<K>>,
    usageStats: GeminiReceiptClient.AiUsageStats?,
    title: String = "AI 科目提案",
    emptyMessage: String = "未マッチング品目に対する提案が見つかりませんでした。\n勘定科目リストを見直してください。",
    onDismiss: () -> Unit,
    onSave: (Map<Long, K>) -> Unit
) {
    // 提案ごとに「承認するか」のチェック状態を管理
    data class SuggestionState(
        val row: AiSuggestionRow<K>,
        val accepted: Boolean = true
    ) {
        val productName get() = row.productName
        val reason get() = row.reason
    }

    val states = remember(rows) {
        rows.map { androidx.compose.runtime.mutableStateOf(SuggestionState(it)) }
    }

    val acceptedCount = states.count { it.value.accepted }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (states.isEmpty()) {
                    Text("提案なし", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${states.size}件の提案（${acceptedCount}件承認中）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { states.forEach { it.value = it.value.copy(accepted = true) } },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("全承認", fontSize = 12.sp) }
                            TextButton(
                                onClick = { states.forEach { it.value = it.value.copy(accepted = false) } },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("全解除", fontSize = 12.sp) }
                        }
                    }
                }
            }
        },
        text = {
            if (states.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        emptyMessage,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(states.size) { idx ->
                        val state by states[idx]
                        Surface(
                            color = if (state.accepted)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { states[idx].value = state.copy(accepted = !state.accepted) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Checkbox(
                                    checked = state.accepted,
                                    onCheckedChange = { states[idx].value = state.copy(accepted = it) },
                                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        state.productName,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            "→",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            state.row.label,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (state.reason.isNotEmpty()) {
                                        Text(
                                            state.reason,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (usageStats != null) {
                    Text(
                        text = usageStats.toDisplayString(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Row {
                    TextButton(onClick = onDismiss) { Text("キャンセル") }
                    TextButton(
                        onClick = {
                            val accepted = states
                                .filter { it.value.accepted }
                                .associate { it.value.row.productId to it.value.row.key }
                            onSave(accepted)
                        },
                        enabled = acceptedCount > 0
                    ) { Text("${acceptedCount}件を保存") }
                }
            }
        },
        dismissButton = null
    )
}
