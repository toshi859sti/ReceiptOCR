package com.example.greenframeocr.ui

import android.app.DatePickerDialog
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AccountingSoftware
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.util.CsvUtils
import com.example.greenframeocr.viewmodel.GeneralReceiptOutputItem
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralReceiptOutputScreen(
    viewModel: GeneralReceiptViewModel,
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountingSoftware = appPreferences.accountingSoftware

    var allItems by remember { mutableStateOf<List<GeneralReceiptOutputItem>>(emptyList()) }
    var outputItems by remember { mutableStateOf<List<GeneralReceiptOutputItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var startDate by remember { mutableStateOf<Calendar?>(null) }
    var endDate by remember { mutableStateOf<Calendar?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val selected = outputItems.filter { item -> item.isSelected }
                if (accountingSoftware == AccountingSoftware.YAYOI) {
                    exportYayoiCsvToUri(context, it, selected)
                } else {
                    exportRakurakuCsvToUri(context, it, selected)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        allItems = viewModel.loadOutputItems()
        outputItems = allItems
        isLoading = false
    }

    LaunchedEffect(startDate, endDate, allItems, selectedYear) {
        var filtered = filterGeneralReceiptByDateRange(allItems, startDate, endDate)
        if (selectedYear != null) {
            filtered = filtered.filter { it.date.take(4).toIntOrNull() == selectedYear }
        }
        outputItems = filtered
    }

    val selectedCount = outputItems.count { it.isSelected }
    val availableYears = remember(allItems) {
        allItems.mapNotNull { it.date.take(4).toIntOrNull() }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("出力確認 - レシート・領収書") },
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
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                // 出力形式バッジ
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (badgeColor, badgeLabel, formatNote) = when (accountingSoftware) {
                        AccountingSoftware.YAYOI ->
                            Triple(
                                Color(0xFF1565C0),
                                "弥生の青色申告",
                                "仕訳CSV（Shift-JIS・25列）"
                            )
                        else ->
                            Triple(
                                Color(0xFF2E7D32),
                                "らくらく青色申告",
                                "シンプルCSV（UTF-8）"
                            )
                    }
                    Surface(
                        color = badgeColor,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = badgeLabel,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = formatNote,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                GeneralReceiptYearSelector(
                    availableYears = availableYears,
                    selectedYear = selectedYear,
                    onYearSelect = { selectedYear = it }
                )

                GeneralReceiptDateRangeSelector(
                    context = context,
                    startDate = startDate,
                    endDate = endDate,
                    onStartDateChange = { startDate = it },
                    onEndDateChange = { endDate = it }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { outputItems = outputItems.map { it.copy(isSelected = true) } },
                        modifier = Modifier.weight(1f)
                    ) { Text("全選択") }
                    OutlinedButton(
                        onClick = { outputItems = outputItems.map { it.copy(isSelected = false) } },
                        modifier = Modifier.weight(1f)
                    ) { Text("全解除") }
                }

                GeneralReceiptGridHeader()
                Divider()

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(outputItems, key = { it.itemId }) { item ->
                        GeneralReceiptGridRow(
                            item = item,
                            onToggleSelect = {
                                outputItems = outputItems.map {
                                    if (it.itemId == item.itemId) it.copy(isSelected = !it.isSelected)
                                    else it
                                }
                            }
                        )
                        Divider()
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "出力数: $selectedCount 件",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                    .format(Date())
                                csvLauncher.launch("レシート_$timestamp.csv")
                            },
                            enabled = selectedCount > 0
                        ) { Text("CSV出力") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralReceiptGridHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Text("出力", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "日付",
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.2f)
        )
        Text(
            text = "商品名",
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(2f)
        )
        Text(
            text = "金額",
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        Text(
            text = "勘定科目",
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.5f)
        )
    }
}

@Composable
private fun GeneralReceiptGridRow(
    item: GeneralReceiptOutputItem,
    onToggleSelect: () -> Unit
) {
    val backgroundColor = if (item.accountName.isBlank()) Color(0xFFFFEBEE) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onToggleSelect)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = item.isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = item.date,
            fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.2f)
        )
        Text(
            text = item.itemName,
            fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f)
        )
        Text(
            text = "%,d".format(item.price),
            fontSize = 11.sp, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        // 補助科目がある場合は「親科目/補助科目」で表示
        val accountDisplay = if (item.debitSubAccountName.isNotBlank())
            "${item.accountName}/${item.debitSubAccountName}"
        else
            item.accountName
        Text(
            text = accountDisplay.ifEmpty { "（未設定）" },
            fontSize = 10.sp,
            color = if (item.accountName.isEmpty()) Color.Gray else Color.Unspecified,
            textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralReceiptYearSelector(
    availableYears: List<Int>,
    selectedYear: Int?,
    onYearSelect: (Int?) -> Unit
) {
    if (availableYears.size <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("年:", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        FilterChip(
            selected = selectedYear == null,
            onClick = { onYearSelect(null) },
            label = { Text("全年") }
        )
        availableYears.forEach { year ->
            FilterChip(
                selected = selectedYear == year,
                onClick = { onYearSelect(if (selectedYear == year) null else year) },
                label = { Text("${year}年") }
            )
        }
    }
}

@Composable
private fun GeneralReceiptDateRangeSelector(
    context: android.content.Context,
    startDate: Calendar?,
    endDate: Calendar?,
    onStartDateChange: (Calendar?) -> Unit,
    onEndDateChange: (Calendar?) -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("期間:", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        OutlinedButton(
            onClick = {
                val cal = startDate ?: Calendar.getInstance()
                DatePickerDialog(context, { _, y, m, d ->
                    onStartDateChange(Calendar.getInstance().apply {
                        set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                    })
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(startDate?.let { dateFormat.format(it.time) } ?: "開始日", fontSize = 12.sp)
        }
        Text("~", fontSize = 14.sp)
        OutlinedButton(
            onClick = {
                val cal = endDate ?: Calendar.getInstance()
                DatePickerDialog(context, { _, y, m, d ->
                    onEndDateChange(Calendar.getInstance().apply {
                        set(y, m, d, 23, 59, 59); set(Calendar.MILLISECOND, 999)
                    })
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(endDate?.let { dateFormat.format(it.time) } ?: "終了日", fontSize = 12.sp)
        }
        TextButton(
            onClick = { onStartDateChange(null); onEndDateChange(null) },
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) { Text("解除", fontSize = 12.sp) }
    }
}

private fun filterGeneralReceiptByDateRange(
    items: List<GeneralReceiptOutputItem>,
    startDate: Calendar?,
    endDate: Calendar?
): List<GeneralReceiptOutputItem> {
    if (startDate == null && endDate == null) return items
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return items.filter { item ->
        try {
            val d = dateFormat.parse(item.date) ?: return@filter true
            val afterStart = startDate?.let { d >= it.time } ?: true
            val beforeEnd = endDate?.let { d <= it.time } ?: true
            afterStart && beforeEnd
        } catch (e: Exception) { true }
    }
}

// ─── 弥生出力（25列・Shift-JIS・CRLF）─────────────────────────────────────

private fun toYayoiDate(dateStr: String): String = CsvUtils.toYayoiDate(dateStr)

private fun buildYayoiRow(item: GeneralReceiptOutputItem): String {
    val memo = item.itemName.take(40)
    val fields = listOf(
        "2000",
        "",                           // 伝票No
        "",                           // 決算
        toYayoiDate(item.date),       // 取引日付（和暦）
        item.accountName,             // 借方勘定科目
        item.debitSubAccountName,     // 借方補助科目
        "",                           // 借方部門
        item.defaultTaxCategory,      // 借方税区分
        item.price.toString(),        // 借方金額
        "0",                          // 借方税金額
        "現金",                       // 貸方勘定科目
        "",                           // 貸方補助科目
        "",                           // 貸方部門
        "対象外",                     // 貸方税区分
        item.price.toString(),        // 貸方金額
        "0",                          // 貸方税金額
        memo,                         // 摘要
        "",                           // 番号
        "",                           // 期日
        "0",                          // タイプ
        "",                           // 生成元
        "",                           // 仕訳メモ
        "0",                          // 付箋1
        "0",                          // 付箋2
        "no"                          // 調整
    )
    return fields.joinToString(",") { f -> CsvUtils.quoteField(f) }
}

private suspend fun exportYayoiCsvToUri(
    context: android.content.Context,
    uri: Uri,
    items: List<GeneralReceiptOutputItem>
) {
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                val writer = stream.bufferedWriter(CsvUtils.yayoiCharset())
                for (item in items) {
                    writer.write(buildYayoiRow(item))
                    writer.write("\r\n")
                }
                writer.flush()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "弥生インポート用CSVを出力しました", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "CSV出力エラー: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// ─── らくらく出力（シンプル・UTF-8）──────────────────────────────────────

private suspend fun exportRakurakuCsvToUri(
    context: android.content.Context,
    uri: Uri,
    items: List<GeneralReceiptOutputItem>
) {
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write("日付,商品名,金額,勘定科目,科目コード")
                writer.newLine()
                for (item in items) {
                    val date = item.date.replace("-", "/")
                    val line = listOf(
                        date,
                        escapeCsvField(item.itemName),
                        item.price.toString(),
                        escapeCsvField(item.accountName),
                        escapeCsvField(item.accountCode)
                    ).joinToString(",")
                    writer.write(line)
                    writer.newLine()
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "CSVを出力しました", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "CSV出力エラー: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun escapeCsvField(field: String): String = CsvUtils.escapeCsvField(field)
