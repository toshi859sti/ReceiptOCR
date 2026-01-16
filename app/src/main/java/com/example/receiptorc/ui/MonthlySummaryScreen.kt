package com.example.receiptorc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.receiptorc.data.ReceiptDatabase
import com.example.receiptorc.data.SheetData
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlySummaryScreen(
    year: Int,
    month: Int,
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var sheetDataList by remember { mutableStateOf<List<SheetData>>(emptyList()) }
    var totalSheets by remember { mutableIntStateOf(0) }

    // データ読み込み
    LaunchedEffect(year, month) {
        scope.launch {
            sheetDataList = database.receiptDao().getAllSheetData()
                .filter { it.issueYear == year && it.issueMonth == month }
                .sortedBy { it.sheetNumber }
            totalSheets = sheetDataList.size
        }
    }

    // カテゴリ別合計を計算
    val generalTotal = sheetDataList.sumOf { it.subtotalGeneral ?: 0 }
    val gasTotal = sheetDataList.sumOf { it.subtotalGas ?: 0 }
    val agriTotal = sheetDataList.sumOf { it.subtotalAgri ?: 0 }
    val monthlyTotal = generalTotal + gasTotal + agriTotal

    val numberFormat = NumberFormat.getNumberInstance(Locale.JAPAN)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("月次サマリー - 令和${year}年${month}月") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 月次合計カード
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "月次合計",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "総枚数: ${totalSheets}枚",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)
                        )
                        SummaryRow("一般購買", generalTotal, numberFormat)
                        SummaryRow("給油所", gasTotal, numberFormat)
                        SummaryRow("農業機械", agriTotal, numberFormat)
                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            thickness = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "月合計",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "¥${numberFormat.format(monthlyTotal)}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // 伝票別詳細
            item {
                Text(
                    text = "伝票別詳細",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(sheetDataList) { sheet ->
                SheetSummaryCard(sheet, numberFormat)
            }

            // 空データの場合
            if (sheetDataList.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "この月のデータはありません",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    amount: Int,
    numberFormat: NumberFormat
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = "¥${numberFormat.format(amount)}",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

@Composable
private fun SheetSummaryCard(
    sheet: SheetData,
    numberFormat: NumberFormat
) {
    val sheetTotal = (sheet.subtotalGeneral ?: 0) + (sheet.subtotalGas ?: 0) + (sheet.subtotalAgri ?: 0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "伝票 ${sheet.sheetNumber}枚目",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "¥${numberFormat.format(sheetTotal)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // カテゴリ別内訳
            val categories = listOf(
                Triple("一般購買", sheet.subtotalGeneral, Color(0xFF4CAF50)),
                Triple("給油所", sheet.subtotalGas, Color(0xFF2196F3)),
                Triple("農業機械", sheet.subtotalAgri, Color(0xFFFF9800))
            )

            categories.forEach { (label, amount, color) ->
                if (amount != null && amount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(color, shape = MaterialTheme.shapes.small)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Text(
                            text = "¥${numberFormat.format(amount)}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            // 入力合計との比較
            if (sheet.totalFromInput != null) {
                val diff = sheetTotal - sheet.totalFromInput
                if (diff != 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠ 入力合計との差額: ¥${numberFormat.format(diff)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}
