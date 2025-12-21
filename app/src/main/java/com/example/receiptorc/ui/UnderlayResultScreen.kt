package com.example.receiptorc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.receiptorc.viewmodel.CameraViewModel

/**
 * 下に敷くタイプの結果表示画面
 */
@Composable
fun UnderlayResultScreen(
    state: CameraViewModel.CameraUiState.SuccessUnderlay,
    viewModel: CameraViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ヘッダー
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "OCR結果（下に敷くタイプ）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "認識行数: ${state.rows.size}行",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (state.subtotals.isNotEmpty()) {
                        Text(
                            text = "小計: ${state.subtotals.size}件",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // 小計データ
        if (state.subtotals.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "小計・合計",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        state.subtotals.forEach { subtotal ->
                            Text(
                                text = "¥${subtotal.value}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }

        // 明細行
        items(state.rows.size) { index ->
            val row = state.rows[index]
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "行${index + 1}:",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(50.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            // 日付
                            if (row.date != null) {
                                Text(
                                    text = "日付: ${row.date}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            // 商品名
                            if (row.itemName != null) {
                                Text(
                                    text = "商品: ${row.itemName}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            // 金額
                            if (row.amount != null) {
                                Text(
                                    text = "金額: ¥${row.amount}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            // 分類計
                            if (row.categorySum != null) {
                                Text(
                                    text = "分類計: ¥${row.categorySum}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // ボタン
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.resetToPreview() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("新しい伝票を撮影")
            }
        }
    }
}
