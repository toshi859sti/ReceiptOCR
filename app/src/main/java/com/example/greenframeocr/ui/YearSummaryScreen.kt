package com.example.greenframeocr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.ReceiptDatabase
import java.text.NumberFormat
import java.util.*

private data class YearGroup(
    val year: Int,
    val yearTotal: Int,
    val sheetCount: Int,
    val months: List<MonthEntry>
)

private data class MonthEntry(
    val month: Int,
    val sheetCount: Int,
    val monthTotal: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearSummaryScreen(
    database: ReceiptDatabase,
    appPreferences: AppPreferences,
    onNavigateToMonth: (year: Int, month: Int) -> Unit,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var yearGroups by remember { mutableStateOf<List<YearGroup>>(emptyList()) }
    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }

    LaunchedEffect(Unit) {
        val allSheets = database.receiptDao().getAllSheetData()
        yearGroups = allSheets
            .groupBy { it.issueYear }
            .entries
            .sortedByDescending { it.key }
            .map { (year, sheets) ->
                val months = sheets
                    .groupBy { it.issueMonth }
                    .entries
                    .sortedBy { it.key }
                    .map { (month, monthSheets) ->
                        MonthEntry(
                            month = month,
                            sheetCount = monthSheets.size,
                            monthTotal = monthSheets.sumOf {
                                (it.subtotalGeneral ?: 0) + (it.subtotalGas ?: 0) + (it.subtotalAgri ?: 0)
                            }
                        )
                    }
                YearGroup(
                    year = year,
                    yearTotal = months.sumOf { it.monthTotal },
                    sheetCount = sheets.size,
                    months = months
                )
            }
        isLoading = false
    }

    val expandedYears = remember { mutableStateMapOf<Int, Boolean>() }
    val numberFormat = NumberFormat.getNumberInstance(Locale.JAPAN)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("購買データ一覧") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                FontSizeControl(
                    fontSize = listFontSize,
                    onDecrease = {
                        listFontSize = (listFontSize - 1f).coerceAtLeast(10f)
                        appPreferences.listFontSize = listFontSize
                    },
                    onIncrease = {
                        listFontSize = (listFontSize + 1f).coerceAtMost(20f)
                        appPreferences.listFontSize = listFontSize
                    }
                )
            }
            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                yearGroups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "データがありません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(yearGroups, key = { it.year }) { group ->
                    val isExpanded = expandedYears[group.year] ?: false
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedYears[group.year] = !isExpanded }
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "令和${group.year}年",
                                        fontSize = (listFontSize + 6f).sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "${group.sheetCount}枚・${group.months.size}ヶ月分",
                                        fontSize = (listFontSize - 1f).coerceAtLeast(10f).sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "¥${numberFormat.format(group.yearTotal)}",
                                        fontSize = (listFontSize + 4f).sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isExpanded) "▲" else "▼",
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 16.dp, bottom = 12.dp
                                    )
                                ) {
                                    Divider(
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)
                                    )
                                    group.months.forEach { entry ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onNavigateToMonth(group.year, entry.month) }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${entry.month}月（${entry.sheetCount}枚）",
                                                fontSize = (listFontSize + 2f).sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                text = "¥${numberFormat.format(entry.monthTotal)}",
                                                fontSize = (listFontSize + 2f).sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
