package com.example.receiptorc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.receiptorc.data.ReceiptItem
import com.example.receiptorc.data.SheetData
import com.example.receiptorc.util.ValidationUtils
import com.example.receiptorc.viewmodel.DataBrowserViewModel

/**
 * データ閲覧画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataBrowserScreen(
    viewModel: DataBrowserViewModel,
    eraYear: Int,
    onBack: () -> Unit,
    onNavigateToOcrCapture: () -> Unit,
    onEditItem: (Int, Int, Int) -> Unit  // (year, month, sheetNumber)
) {
    val selectedYear by viewModel.selectedYear.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val monthlyData by viewModel.monthlyData.collectAsState()
    val receiptItems by viewModel.receiptItems.collectAsState()
    val sheetDataList by viewModel.sheetDataList.collectAsState()

    // 表示用データの作成
    val displayRows = remember(receiptItems, sheetDataList) {
        createDisplayRows(receiptItems, sheetDataList)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("データ閲覧") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 月選択ヘッダー
            MonthSelector(
                year = selectedYear,
                month = selectedMonth,
                eraYear = eraYear,
                onPreviousMonth = { viewModel.previousMonth() },
                onNextMonth = { viewModel.nextMonth() }
            )

            Divider()

            // サマリー
            SummarySection(
                eraYear = eraYear,
                month = selectedMonth,
                totalSheets = monthlyData?.totalSheets ?: 0,
                totalAmount = monthlyData?.monthlyTotal ?: 0
            )

            Divider()

            // データグリッド
            if (displayRows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "データがありません",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                DataGrid(
                    rows = displayRows,
                    onRowClick = { sheetNumber ->
                        sheetNumber?.let {
                            onEditItem(selectedYear, selectedMonth, it)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Divider()

            // ボトムボタン
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateToOcrCapture,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("OCR撮影")
                }
            }
        }
    }
}

/**
 * 月選択ヘッダー
 */
@Composable
private fun MonthSelector(
    year: Int,
    month: Int,
    eraYear: Int,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(Icons.Default.KeyboardArrowLeft, "前の月")
        }

        Text(
            text = "令和${eraYear}年 ${month}月",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        IconButton(onClick = onNextMonth) {
            Icon(Icons.Default.KeyboardArrowRight, "次の月")
        }
    }
}

/**
 * サマリーセクション
 */
@Composable
private fun SummarySection(
    eraYear: Int,
    month: Int,
    totalSheets: Int,
    totalAmount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "令和${eraYear}年 ${month}月発行",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "合計${totalSheets}枚 ${ValidationUtils.formatAmount(totalAmount)}",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * データグリッド
 */
@Composable
private fun DataGrid(
    rows: List<DisplayRow>,
    onRowClick: (Int?) -> Unit,  // sheetNumber
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // ヘッダー
        GridHeader()

        Divider()

        // データ行
        LazyColumn {
            items(rows) { row ->
                GridRow(
                    row = row,
                    onClick = {
                        if (!row.isSubtotalRow) {
                            onRowClick(row.sheetNumber)
                        }
                    }
                )
                Divider()
            }
        }
    }
}

/**
 * グリッドヘッダー
 */
@Composable
private fun GridHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        HeaderCell("日付", Modifier.weight(1f))
        HeaderCell("商品名", Modifier.weight(2f))
        HeaderCell("金額", Modifier.weight(1f))
        HeaderCell("分類", Modifier.weight(1.5f))
        HeaderCell("枚", Modifier.weight(0.7f))
        HeaderCell("小計", Modifier.weight(1f))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
}

/**
 * グリッドの行
 */
@Composable
private fun GridRow(
    row: DisplayRow,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (row.isSubtotalRow)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    MaterialTheme.colorScheme.surface
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DataCell(row.date, Modifier.weight(1f))
        DataCell(row.productName, Modifier.weight(2f))
        DataCell(
            if (row.amount >= 0) "%,d".format(row.amount)
            else "-%,d".format(-row.amount),
            Modifier.weight(1f),
            align = TextAlign.End
        )
        DataCell(row.category, Modifier.weight(1.5f))
        DataCell(
            if (row.sheetNumber > 0) row.sheetNumber.toString() else "",
            Modifier.weight(0.7f),
            align = TextAlign.Center
        )
        DataCell(
            row.subtotal?.let { "%,d".format(it) } ?: "",
            Modifier.weight(1f),
            align = TextAlign.End,
            fontWeight = if (row.isSubtotalRow) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun DataCell(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 13.sp,
        textAlign = align,
        fontWeight = fontWeight
    )
}

/**
 * 表示用データ行
 */
data class DisplayRow(
    val date: String,
    val productName: String,
    val amount: Int,
    val category: String,
    val sheetNumber: Int,
    val subtotal: Int?,
    val isSubtotalRow: Boolean,
    val itemId: Long?
)

/**
 * 表示用データの作成
 */
private fun createDisplayRows(
    items: List<ReceiptItem>,
    sheetDataList: List<SheetData>
): List<DisplayRow> {
    val rows = mutableListOf<DisplayRow>()
    val groupedBySheet = items.groupBy { it.sheetNumber }
    val sheetDataMap = sheetDataList.associateBy { it.sheetNumber }

    groupedBySheet.keys.sorted().forEach { sheetNumber ->
        val sheetItems = groupedBySheet[sheetNumber] ?: emptyList()
        val sheetData = sheetDataMap[sheetNumber]

        sheetItems.sortedBy { it.itemNumber }.forEach { item ->
            rows.add(
                DisplayRow(
                    date = ValidationUtils.formatDate(item.receiptMonth, item.receiptDay),
                    productName = item.productName,
                    amount = item.amount,
                    category = item.category,
                    sheetNumber = item.sheetNumber,
                    subtotal = null,
                    isSubtotalRow = false,
                    itemId = item.id
                )
            )
        }

        // 小計行を追加（各伝票の最後）
        if (sheetItems.isNotEmpty() && sheetData != null) {
            rows.add(
                DisplayRow(
                    date = "",
                    productName = "小計",
                    amount = 0,
                    category = "",
                    sheetNumber = 0,
                    subtotal = sheetData.totalFromInput,
                    isSubtotalRow = true,
                    itemId = null
                )
            )
        }
    }

    return rows
}
