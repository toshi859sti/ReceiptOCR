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
import com.example.greenframeocr.util.AoiroChoboTransactionsBuilder
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
    var showUnmatchedBlockDialog by remember { mutableStateOf(false) }
    var unexportedOnly by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val selected = outputItems.filter { item -> item.isSelected }
                val success = exportYayoiCsvToUri(context, it, selected)
                if (success) {
                    val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                    val exportedIds = selected.map { item -> item.itemId }.toSet()
                    viewModel.markItemsExported(selected.map { item -> item.itemId }, timestamp)
                    // allItemsの更新で年/期間フィルタが再計算されるため、現在の画面上のチェック状態
                    // （outputItems）を先に反映してから、出力済み分だけexportedAt・チェックOFFを上書きする
                    val currentSelection = outputItems.associateBy { item -> item.itemId }
                    allItems = allItems.map { item ->
                        when {
                            item.itemId in exportedIds -> item.copy(exportedAt = timestamp, isSelected = false)
                            else -> currentSelection[item.itemId]?.let { item.copy(isSelected = it.isSelected) } ?: item
                        }
                    }
                }
            }
        }
    }

    // あおいろ帳簿（transactions.json）
    val isAoiro = accountingSoftware == AccountingSoftware.AOIRO
    var aoiroLabels by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var aoiroResult by remember { mutableStateOf<AoiroChoboTransactionsBuilder.Result?>(null) }
    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val selected = outputItems.filter { item -> item.isSelected }
                val result = try {
                    val appVersion = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: ""
                    val built = viewModel.buildAoiroReceiptJson(selected.map { item -> item.itemId }, appVersion)
                    // UTF-8・BOM なし（契約 §1）
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(it)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                            writer.write(built.json)
                            writer.write("\n")
                        } ?: error("ファイルを開けませんでした")
                    }
                    built
                } catch (e: Exception) {
                    Toast.makeText(context, "JSON出力エラー: ${e.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                // 出せなかった品目（金額 0・不正な日付）は出力済みにしない
                val exportedIds = result.file.entries.map { entry -> entry.meta.sourceRowId }.toSet()
                val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                viewModel.markItemsExported(exportedIds.toList(), timestamp)
                val currentSelection = outputItems.associateBy { item -> item.itemId }
                allItems = allItems.map { item ->
                    when {
                        item.itemId in exportedIds -> item.copy(exportedAt = timestamp, isSelected = false)
                        else -> currentSelection[item.itemId]?.let { item.copy(isSelected = it.isSelected) } ?: item
                    }
                }
                aoiroResult = result
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        if (isAoiro) aoiroLabels = viewModel.loadAoiroReceiptLabels()
        val loaded = viewModel.loadOutputItems()
        // 弥生は厳密なCSVが必要なため、科目未設定の品目は誤出力防止でデフォルトチェックOFFにする。
        // 出力済み品目も二重出力防止でデフォルトチェックOFFにする（ソフト共通）
        allItems = loaded.map { item ->
            val shouldDefaultOff = item.exportedAt != null ||
                (accountingSoftware == AccountingSoftware.YAYOI && item.accountName.isBlank())
            if (shouldDefaultOff) item.copy(isSelected = false) else item
        }
        outputItems = allItems
        // 作業年（設定画面のeraYear）のデータがあれば作業年をデフォルト選択、なければ全年のまま
        val years = allItems.mapNotNull { it.date.take(4).toIntOrNull() }.distinct()
        val workingYear = appPreferences.workingCalendarYear
        if (years.contains(workingYear)) {
            selectedYear = workingYear
        }
        isLoading = false
    }

    LaunchedEffect(startDate, endDate, allItems, selectedYear, unexportedOnly) {
        var filtered = filterGeneralReceiptByDateRange(allItems, startDate, endDate)
        if (selectedYear != null) {
            filtered = filtered.filter { it.date.take(4).toIntOrNull() == selectedYear }
        }
        if (unexportedOnly) {
            filtered = filtered.filter { it.exportedAt == null }
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
                        AccountingSoftware.AOIRO ->
                            Triple(Color(0xFF00695C), "あおいろ帳簿", "transactions.json（UTF-8）")
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = unexportedOnly,
                        onClick = { unexportedOnly = !unexportedOnly },
                        label = { Text("未出力のみ表示") }
                    )
                }

                GeneralReceiptGridHeader(if (isAoiro) "科目 ／ 摘要" else "勘定科目")
                Divider()

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(outputItems, key = { it.itemId }) { item ->
                        GeneralReceiptGridRow(
                            // あおいろはグループのあおいろ科目・摘要を出す（弥生の科目の列を差し替える）
                            item = if (isAoiro) item.copy(
                                accountName = aoiroLabels[item.itemId].orEmpty(),
                                debitSubAccountName = ""
                            ) else item,
                            accountingSoftware = accountingSoftware,
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
                                val hasUnmatched = accountingSoftware == AccountingSoftware.YAYOI &&
                                    outputItems.any { it.isSelected && it.accountName.isBlank() }
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                    .format(Date())
                                if (hasUnmatched) {
                                    showUnmatchedBlockDialog = true
                                } else if (isAoiro) {
                                    // 科目・摘要が未確定の品目も止めない。PC が「要確認」で受ける（契約 §9）
                                    jsonLauncher.launch("ja_shiwake_$timestamp.json")
                                } else {
                                    csvLauncher.launch("レシート_$timestamp.csv")
                                }
                            },
                            enabled = selectedCount > 0
                        ) { Text(if (isAoiro) "JSON出力" else "CSV出力") }
                    }
                }
            }
        }

        if (showUnmatchedBlockDialog) {
            val unmatchedNames = outputItems
                .filter { it.isSelected && it.accountName.isBlank() }
                .map { it.itemName }
            AlertDialog(
                onDismissRequest = { showUnmatchedBlockDialog = false },
                title = { Text("科目未設定の品目があります") },
                text = {
                    Column {
                        Text("以下の品目に勘定科目が設定されていないため、弥生CSVを出力できません。")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = unmatchedNames.take(10).joinToString("\n") { "・$it" } +
                                if (unmatchedNames.size > 10) "\n他${unmatchedNames.size - 10}件" else "",
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "「商品名・但し書きリスト」で科目を設定するか、チェックを外してください。",
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showUnmatchedBlockDialog = false }) { Text("OK") }
                }
            )
        }

        aoiroResult?.let { result ->
            AoiroExportResultDialog(result = result, onDismiss = { aoiroResult = null })
        }
    }
}

@Composable
private fun GeneralReceiptGridHeader(accountLabel: String = "勘定科目") {
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
            text = accountLabel,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.5f)
        )
    }
}

@Composable
private fun GeneralReceiptGridRow(
    item: GeneralReceiptOutputItem,
    accountingSoftware: AccountingSoftware,
    onToggleSelect: () -> Unit
) {
    // 弥生は科目未設定のまま出力できないため警告表示
    val isUnmatchedWarning = accountingSoftware == AccountingSoftware.YAYOI && item.accountName.isBlank()
    val backgroundColor = when {
        isUnmatchedWarning -> Color(0xFFFFEBEE)
        item.exportedAt != null -> Color(0xFFF5F5F5)
        else -> Color.Transparent
    }

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
        Column(modifier = Modifier.weight(1.2f)) {
            Text(
                text = item.date,
                fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (item.exportedAt != null) {
                Text(
                    text = "出力済 ${item.exportedAt.take(10)}",
                    fontSize = 8.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
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
        Box(modifier = Modifier.weight(1.5f), contentAlignment = Alignment.Center) {
            if (item.accountName.isEmpty() && isUnmatchedWarning) {
                Surface(color = Color(0xFFC62828), shape = MaterialTheme.shapes.extraSmall) {
                    Text(
                        text = "⚠未設定",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else {
                Text(
                    text = accountDisplay.ifEmpty { "（未設定）" },
                    fontSize = 10.sp,
                    color = if (item.accountName.isEmpty()) Color.Gray else Color.Unspecified,
                    textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
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
        item.counterAccountName,      // 貸方勘定科目
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
): Boolean = withContext(Dispatchers.IO) {
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
        true
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "CSV出力エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
        false
    }
}
