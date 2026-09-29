package com.example.greenframeocr.ui

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.example.greenframeocr.data.*
import com.example.greenframeocr.util.AoiroChoboTransactionsBuilder
import com.example.greenframeocr.util.CsvUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * 出力確認画面
 * @param department "購買" or "預金"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputConfirmScreen(
    department: String,
    database: ReceiptDatabase,
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    when (department) {
        "購買" -> PurchaseOutputConfirmContent(
            database = database,
            onBack = onBack,
            scope = scope,
            context = context,
            appPreferences = appPreferences,
            accountingSoftware = appPreferences.accountingSoftware
        )
        "預金" -> DepositOutputConfirmContent(
            database = database,
            onBack = onBack,
            scope = scope,
            context = context,
            hideAmount = appPreferences.depositHideAmount,
            appPreferences = appPreferences,
            accountingSoftware = appPreferences.accountingSoftware
        )
    }
}

/**
 * 購買部門の出力データ
 */
data class PurchaseOutputItem(
    val id: Long,
    val date: String,           // 日付 (YYYY/MM/DD)
    val tekiyou: String,        // 摘要（弥生=勘定科目名 / あおいろ=科目 ／ 摘要）
    val memo: String,           // メモ（商品名）
    val amount: Int,            // 購入金額
    val yayoiSubAccountName: String = "",
    val defaultTaxCategory: String = "対象外",
    val exportedAt: String? = null,  // 直近のCSV出力日時。未出力ならnull
    /** あおいろ帳簿モードだけ。transactions.json を組み立てる元（行と紐付いた商品） */
    val aoiroRow: AoiroChoboTransactionsBuilder.PurchaseRow? = null,
    var isSelected: Boolean = true
)

/**
 * 預金部門の出力データ
 */
data class DepositOutputItem(
    val id: Int,
    val date: String,           // 日付
    val tekiyou: String,        // 摘要（弥生=勘定科目名 / あおいろ=科目 ／ 摘要）
    val memo: String,           // メモ（通帳摘要原文）
    val deposit: Int?,          // 入金（正の金額）
    val withdrawal: Int?,       // 出金（負の金額の絶対値）
    val passbookId: Int = Passbook.DEFAULT_ID,
    // 預金側（普通預金）の弥生の補助科目。通帳ごとに違う（空欄なら補助科目なし）
    val bankYayoiSubAccountName: String = "",
    val yayoiSubAccountName: String = "",
    val defaultTaxCategory: String = "対象外",
    val exportedAt: String? = null,  // 直近のCSV出力日時。未出力ならnull
    /** あおいろ帳簿モードだけ。transactions.json を組み立てる元（明細・通帳・ルール） */
    val aoiroRow: AoiroChoboTransactionsBuilder.DepositRow? = null,
    var isSelected: Boolean = true
)

/**
 * 購買部門の出力確認画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseOutputConfirmContent(
    database: ReceiptDatabase,
    onBack: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    appPreferences: AppPreferences,
    accountingSoftware: AccountingSoftware = AccountingSoftware.YAYOI
) {
    var listFontSize by remember { mutableStateOf(appPreferences.listFontSize) }
    var allItems by remember { mutableStateOf<List<PurchaseOutputItem>>(emptyList()) }
    var outputItems by remember { mutableStateOf<List<PurchaseOutputItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var unexportedOnly by remember { mutableStateOf(false) }
    var showUnmatchedBlockDialog by remember { mutableStateOf(false) }

    // 期間選択用のState
    var startDate by remember { mutableStateOf<Calendar?>(null) }
    var endDate by remember { mutableStateOf<Calendar?>(null) }

    // CSV出力用のファイル選択ランチャー
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val selected = outputItems.filter { item -> item.isSelected }
                val success = exportPurchaseYayoiCsvToUri(context, it, selected)
                if (success) {
                    val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                    val exportedIds = selected.map { item -> item.id }.toSet()
                    database.receiptDao().markExported(selected.map { item -> item.id }, timestamp)
                    val currentSelection = outputItems.associateBy { item -> item.id }
                    allItems = allItems.map { item ->
                        when {
                            item.id in exportedIds -> item.copy(exportedAt = timestamp, isSelected = false)
                            else -> currentSelection[item.id]?.let { item.copy(isSelected = it.isSelected) } ?: item
                        }
                    }
                }
            }
        }
    }

    // あおいろ帳簿（transactions.json）
    val isAoiro = accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP
    var aoiroResult by remember { mutableStateOf<AoiroChoboTransactionsBuilder.Result?>(null) }
    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val selected = outputItems.filter { item -> item.isSelected }
                val result = exportPurchaseAoiroJsonToUri(context, it, database, selected) ?: return@launch
                // 出せなかった行（金額 0・不正な日付）は出力済みにしない
                val exportedIds = result.file.entries.map { entry -> entry.meta.sourceRowId }.toSet()
                val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                database.receiptDao().markExported(exportedIds.toList(), timestamp)
                val currentSelection = outputItems.associateBy { item -> item.id }
                allItems = allItems.map { item ->
                    when {
                        item.id in exportedIds -> item.copy(exportedAt = timestamp, isSelected = false)
                        else -> currentSelection[item.id]?.let { item.copy(isSelected = it.isSelected) } ?: item
                    }
                }
                aoiroResult = result
            }
        }
    }

    // データ読み込み
    LaunchedEffect(Unit) {
        isLoading = true
        val loaded = loadPurchaseOutputItems(database, accountingSoftware)
        // 弥生は厳密なCSVが必要なため科目（摘要）未設定は誤出力防止でデフォルトチェックOFF。
        // 出力済みの明細も二重出力防止でデフォルトチェックOFFにする
        allItems = loaded.map { item ->
            val shouldDefaultOff = item.exportedAt != null ||
                (accountingSoftware == AccountingSoftware.YAYOI && item.tekiyou.isBlank())
            if (shouldDefaultOff) item.copy(isSelected = false) else item
        }
        outputItems = allItems
        isLoading = false
    }

    // フィルタリング（年・期間）
    LaunchedEffect(startDate, endDate, allItems, selectedYear, unexportedOnly) {
        var filtered = filterPurchaseItemsByDateRange(allItems, startDate, endDate)
        if (selectedYear != null) {
            filtered = filtered.filter { it.date.take(4).toIntOrNull() == selectedYear }
        }
        if (unexportedOnly) {
            filtered = filtered.filter { it.exportedAt == null }
        }
        outputItems = filtered
    }

    val selectedCount = outputItems.count { it.isSelected }
    val availablePurchaseYears = remember(allItems) {
        allItems.mapNotNull { it.date.take(4).toIntOrNull() }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("出力確認 - 購買") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                actions = {
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
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // 出力形式バッジ
                OutputFormatBadge(accountingSoftware)

                // 年選択UI
                YearSelector(
                    availableYears = availablePurchaseYears,
                    selectedYear = selectedYear,
                    onYearSelect = { selectedYear = it }
                )

                // 期間選択UI
                DateRangeSelector(
                    context = context,
                    startDate = startDate,
                    endDate = endDate,
                    onStartDateChange = { startDate = it },
                    onEndDateChange = { endDate = it }
                )

                // 全選択/全解除ボタン
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            outputItems = outputItems.map { it.copy(isSelected = true) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("全選択")
                    }
                    OutlinedButton(
                        onClick = {
                            outputItems = outputItems.map { it.copy(isSelected = false) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("全解除")
                    }
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

                // ヘッダー行
                PurchaseGridHeader(accountingSoftware)

                Divider()

                // グリッド
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(outputItems, key = { it.id }) { item ->
                        PurchaseGridRow(
                            item = item,
                            fontSize = listFontSize,
                            accountingSoftware = accountingSoftware,
                            onToggleSelect = {
                                outputItems = outputItems.map {
                                    if (it.id == item.id) it.copy(isSelected = !it.isSelected)
                                    else it
                                }
                            }
                        )
                        Divider()
                    }
                }

                // 下部：出力数とCSV出力ボタン
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
                                    outputItems.any { it.isSelected && it.tekiyou.isBlank() }
                                val dateFormat = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                val timestamp = dateFormat.format(Date())
                                if (hasUnmatched) {
                                    showUnmatchedBlockDialog = true
                                } else if (isAoiro) {
                                    // 科目・摘要が未確定の行も止めない。PC が「要確認」で受ける（契約 §9）
                                    jsonLauncher.launch("ja_shiwake_$timestamp.json")
                                } else {
                                    val fileName = "購買_$timestamp.csv"
                                    csvLauncher.launch(fileName)
                                }
                            },
                            enabled = selectedCount > 0
                        ) {
                            Text(if (isAoiro) "JSON出力" else "CSV出力")
                        }
                    }
                }
            }
        }

        if (showUnmatchedBlockDialog) {
            val unmatchedNames = outputItems
                .filter { it.isSelected && it.tekiyou.isBlank() }
                .map { it.memo }
            UnmatchedAccountBlockDialog(
                itemNames = unmatchedNames,
                onDismiss = { showUnmatchedBlockDialog = false }
            )
        }

        aoiroResult?.let { result ->
            AoiroExportResultDialog(result = result, onDismiss = { aoiroResult = null })
        }
    }
}

/**
 * transactions.json を書き出した結果。PC で「要確認」になる行の数と、出せなかった行を知らせる
 */
@Composable
internal fun AoiroExportResultDialog(
    result: AoiroChoboTransactionsBuilder.Result,
    onDismiss: () -> Unit
) {
    val entries = result.file.entries
    val byStatus = entries.groupingBy { it.matchStatus }.eachCount()
    val matched = byStatus[AoiroChoboTransactionsBuilder.MatchStatus.MATCHED] ?: 0
    val noAccount = byStatus[AoiroChoboTransactionsBuilder.MatchStatus.UNMATCHED_ACCOUNT] ?: 0
    val noMemo = byStatus[AoiroChoboTransactionsBuilder.MatchStatus.UNMATCHED_MEMO] ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("JSONを出力しました") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${entries.size} 件を出力しました（${result.file.vocabulary.fiscalYear} 年度の科目・摘要で作成）")
                Text("・科目・摘要とも確定: $matched 件")
                if (noMemo > 0) Text("・摘要なし: $noMemo 件（PC の「要確認」で摘要を選びます）")
                if (noAccount > 0) Text("・科目なし: $noAccount 件（PC の「要確認」で科目を選びます）")
                if (result.skipped.isNotEmpty()) {
                    Text(
                        "出力できなかった行 ${result.skipped.size} 件（出力済みにはしていません）",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    result.skipped.take(10).forEach { skipped ->
                        Text("・${skipped.label}：${skipped.reason.label}", fontSize = 13.sp)
                    }
                    if (result.skipped.size > 10) Text("ほか ${result.skipped.size - 10} 件", fontSize = 13.sp)
                }
                result.warnings.forEach { warning ->
                    Text(warning, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

/**
 * 購買グリッドヘッダー
 */
@Composable
private fun PurchaseGridHeader(accountingSoftware: AccountingSoftware = AccountingSoftware.YAYOI) {
    val tekiyouLabel = when (accountingSoftware) {
        AccountingSoftware.YAYOI -> "科目/メモ"
        AccountingSoftware.BLUE_RETURN_PREP -> "科目 ／ 摘要/メモ"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 出力チェック列
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("出力", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        // 日付列
        Text(
            text = "日付",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.2f)
        )
        // 科目/メモ or 摘要/メモ列
        Text(
            text = tekiyouLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(2f)
        )
        // 金額列
        Text(
            text = "金額",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
    }
}

/**
 * 購買グリッド行
 */
@Composable
private fun PurchaseGridRow(
    item: PurchaseOutputItem,
    fontSize: Float = 14f,
    accountingSoftware: AccountingSoftware = AccountingSoftware.YAYOI,
    onToggleSelect: () -> Unit
) {
    // 弥生は科目未設定のまま出力できないため警告扱い
    val isUnmatchedWarning = accountingSoftware == AccountingSoftware.YAYOI && item.tekiyou.isBlank()
    // 摘要未設定の場合は薄い赤の背景色、出力済みの場合は薄いグレー
    val backgroundColor = when {
        item.tekiyou.isBlank() -> Color(0xFFFFEBEE) // 薄い赤
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
        // 出力チェック列
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Checkbox(
                checked = item.isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.size(24.dp)
            )
        }
        // 日付列
        Column(modifier = Modifier.weight(1.2f)) {
            Text(
                text = item.date,
                fontSize = fontSize.sp,
                textAlign = TextAlign.Center,
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
        // 摘要/メモ列（2行表示）
        Column(
            modifier = Modifier.weight(2f)
        ) {
            if (item.tekiyou.isEmpty() && isUnmatchedWarning) {
                Surface(color = Color(0xFFC62828), shape = MaterialTheme.shapes.extraSmall) {
                    Text(
                        text = "⚠未設定",
                        fontSize = (fontSize - 2f).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else {
                Text(
                    text = item.tekiyou.ifEmpty { "（未設定）" },
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (item.tekiyou.isEmpty()) Color.Gray else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = item.memo,
                fontSize = (fontSize - 1f).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 金額列
        Text(
            text = "%,d".format(item.amount),
            fontSize = fontSize.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
    }
}

/**
 * 預金部門の出力確認画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepositOutputConfirmContent(
    database: ReceiptDatabase,
    onBack: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    hideAmount: Boolean = false,
    appPreferences: AppPreferences,
    accountingSoftware: AccountingSoftware = AccountingSoftware.YAYOI
) {
    var listFontSize by remember { mutableStateOf(appPreferences.listFontSize) }
    var allItems by remember { mutableStateOf<List<DepositOutputItem>>(emptyList()) }
    var outputItems by remember { mutableStateOf<List<DepositOutputItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var unexportedOnly by remember { mutableStateOf(false) }
    var showUnmatchedBlockDialog by remember { mutableStateOf(false) }
    // 通帳の絞り込み（null＝すべて）。弥生 CSV は補助科目で口座を分けるので、すべてまとめて出してもよい
    var passbooks by remember { mutableStateOf<List<Passbook>>(emptyList()) }
    var selectedPassbookId by remember { mutableStateOf<Int?>(null) }

    // 期間選択用のState
    var startDate by remember { mutableStateOf<Calendar?>(null) }
    var endDate by remember { mutableStateOf<Calendar?>(null) }

    // CSV出力用のファイル選択ランチャー
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val selected = outputItems.filter { item -> item.isSelected }
                val success = exportDepositYayoiCsvToUri(context, it, selected)
                if (success) {
                    val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                    val exportedIds = selected.map { item -> item.id }.toSet()
                    database.depositMeisaiDao().markExported(selected.map { item -> item.id }, timestamp)
                    val currentSelection = outputItems.associateBy { item -> item.id }
                    allItems = allItems.map { item ->
                        when {
                            item.id in exportedIds -> item.copy(exportedAt = timestamp, isSelected = false)
                            else -> currentSelection[item.id]?.let { item.copy(isSelected = it.isSelected) } ?: item
                        }
                    }
                }
            }
        }
    }

    // あおいろ帳簿（transactions.json）
    val isAoiro = accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP
    var aoiroResult by remember { mutableStateOf<AoiroChoboTransactionsBuilder.Result?>(null) }
    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val selected = outputItems.filter { item -> item.isSelected }
                val result = exportDepositAoiroJsonToUri(context, it, database, selected) ?: return@launch
                // 出せなかった行（口座が未設定の通帳など）は出力済みにしない
                val exportedIds = result.file.entries.map { entry -> entry.meta.sourceRowId.toInt() }.toSet()
                val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                database.depositMeisaiDao().markExported(exportedIds.toList(), timestamp)
                val currentSelection = outputItems.associateBy { item -> item.id }
                allItems = allItems.map { item ->
                    when {
                        item.id in exportedIds -> item.copy(exportedAt = timestamp, isSelected = false)
                        else -> currentSelection[item.id]?.let { item.copy(isSelected = it.isSelected) } ?: item
                    }
                }
                aoiroResult = result
            }
        }
    }

    // データ読み込み
    LaunchedEffect(Unit) {
        isLoading = true
        passbooks = withContext(Dispatchers.IO) { database.passbookDao().ensureDefault() }
        val loaded = loadDepositOutputItems(database, accountingSoftware)
        // 弥生は厳密なCSVが必要なため科目（摘要）未設定は誤出力防止でデフォルトチェックOFF。
        // 出力済みの明細も二重出力防止でデフォルトチェックOFFにする
        allItems = loaded.map { item ->
            val shouldDefaultOff = item.exportedAt != null ||
                (accountingSoftware == AccountingSoftware.YAYOI && item.tekiyou.isBlank())
            if (shouldDefaultOff) item.copy(isSelected = false) else item
        }
        outputItems = allItems
        isLoading = false
    }

    // フィルタリング（年・期間）
    LaunchedEffect(startDate, endDate, allItems, selectedYear, unexportedOnly, selectedPassbookId) {
        var filtered = filterDepositItemsByDateRange(allItems, startDate, endDate)
        selectedPassbookId?.let { id -> filtered = filtered.filter { it.passbookId == id } }
        if (selectedYear != null) {
            filtered = filtered.filter { it.date.take(4).toIntOrNull() == selectedYear }
        }
        if (unexportedOnly) {
            filtered = filtered.filter { it.exportedAt == null }
        }
        outputItems = filtered
    }

    val selectedCount = outputItems.count { it.isSelected }
    val availableDepositYears = remember(allItems) {
        allItems.mapNotNull { it.date.take(4).toIntOrNull() }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("出力確認 - 預金") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                actions = {
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
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // 出力形式バッジ
                OutputFormatBadge(accountingSoftware)

                // 年選択UI
                YearSelector(
                    availableYears = availableDepositYears,
                    selectedYear = selectedYear,
                    onYearSelect = { selectedYear = it }
                )

                // 通帳の絞り込み（2冊以上のときだけ）
                if (passbooks.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedPassbookId == null,
                            onClick = { selectedPassbookId = null },
                            label = { Text("すべての通帳") }
                        )
                        passbooks.forEach { passbook ->
                            FilterChip(
                                selected = selectedPassbookId == passbook.id,
                                onClick = { selectedPassbookId = passbook.id },
                                label = { Text(passbook.name) }
                            )
                        }
                    }
                    // 補助科目が空の通帳が2冊以上あると、弥生では同じ「普通預金」に混ざる
                    if (accountingSoftware == AccountingSoftware.YAYOI &&
                        passbooks.count { it.yayoiSubAccountName.isBlank() } >= 2
                    ) {
                        Text(
                            "弥生の補助科目が未設定の通帳が複数あります。弥生では同じ「普通預金」に入ります（通帳データ画面の「通帳の管理」で設定）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // あおいろは口座（bankSlotNo）が決まらない通帳の明細を出せない（契約 §6）
                if (isAoiro) {
                    val noAccount = passbooks.filter { it.aoiroAccountKey == null }
                    if (noAccount.isNotEmpty()) {
                        Text(
                            "あおいろの口座が未設定の通帳（${noAccount.joinToString("・") { it.name }}）の明細は出力されません" +
                                "（通帳データ画面の「通帳の管理」で設定）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // 期間選択UI
                DateRangeSelector(
                    context = context,
                    startDate = startDate,
                    endDate = endDate,
                    onStartDateChange = { startDate = it },
                    onEndDateChange = { endDate = it }
                )

                // 全選択/全解除ボタン
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            outputItems = outputItems.map { it.copy(isSelected = true) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("全選択")
                    }
                    OutlinedButton(
                        onClick = {
                            outputItems = outputItems.map { it.copy(isSelected = false) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("全解除")
                    }
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

                // ヘッダー行
                DepositGridHeader(accountingSoftware)

                Divider()

                // グリッド
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(outputItems, key = { it.id }) { item ->
                        DepositGridRow(
                            item = item,
                            hideAmount = hideAmount,
                            fontSize = listFontSize,
                            accountingSoftware = accountingSoftware,
                            onToggleSelect = {
                                outputItems = outputItems.map {
                                    if (it.id == item.id) it.copy(isSelected = !it.isSelected)
                                    else it
                                }
                            }
                        )
                        Divider()
                    }
                }

                // 下部：出力数とCSV出力ボタン
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
                                    outputItems.any { it.isSelected && it.tekiyou.isBlank() }
                                val dateFormat = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                val timestamp = dateFormat.format(Date())
                                if (hasUnmatched) {
                                    showUnmatchedBlockDialog = true
                                } else if (isAoiro) {
                                    // 相手科目・摘要が未確定の行も止めない。PC が「要確認」で受ける（契約 §9）
                                    jsonLauncher.launch("ja_shiwake_$timestamp.json")
                                } else {
                                    val fileName = "預金_$timestamp.csv"
                                    csvLauncher.launch(fileName)
                                }
                            },
                            enabled = selectedCount > 0
                        ) {
                            Text(if (isAoiro) "JSON出力" else "CSV出力")
                        }
                    }
                }
            }
        }

        if (showUnmatchedBlockDialog) {
            val unmatchedNames = outputItems
                .filter { it.isSelected && it.tekiyou.isBlank() }
                .map { it.memo }
            UnmatchedAccountBlockDialog(
                itemNames = unmatchedNames,
                onDismiss = { showUnmatchedBlockDialog = false }
            )
        }

        aoiroResult?.let { result ->
            AoiroExportResultDialog(result = result, onDismiss = { aoiroResult = null })
        }
    }
}

/**
 * 預金グリッドヘッダー
 */
@Composable
private fun DepositGridHeader(accountingSoftware: AccountingSoftware = AccountingSoftware.YAYOI) {
    val tekiyouLabel = when (accountingSoftware) {
        AccountingSoftware.YAYOI -> "科目/メモ"
        AccountingSoftware.BLUE_RETURN_PREP -> "科目 ／ 摘要/メモ"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 出力チェック列
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("出力", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        // 日付列
        Text(
            text = "日付",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.2f)
        )
        // 科目/メモ or 摘要/メモ列
        Text(
            text = tekiyouLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(2f)
        )
        // 金額列
        Text(
            text = "金額",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
    }
}

/**
 * 預金グリッド行
 */
@Composable
private fun DepositGridRow(
    item: DepositOutputItem,
    hideAmount: Boolean = false,
    fontSize: Float = 14f,
    accountingSoftware: AccountingSoftware = AccountingSoftware.YAYOI,
    onToggleSelect: () -> Unit
) {
    // 弥生は科目未設定のまま出力できないため警告扱い
    val isUnmatchedWarning = accountingSoftware == AccountingSoftware.YAYOI && item.tekiyou.isBlank()
    // 摘要未設定の場合は薄い赤の背景色、出力済みの場合は薄いグレー
    val backgroundColor = when {
        item.tekiyou.isBlank() -> Color(0xFFFFEBEE) // 薄い赤
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
        // 出力チェック列
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Checkbox(
                checked = item.isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.size(24.dp)
            )
        }
        // 日付列
        Column(modifier = Modifier.weight(1.2f)) {
            Text(
                text = item.date,
                fontSize = fontSize.sp,
                textAlign = TextAlign.Center,
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
        // 摘要/メモ列（2行表示）
        Column(
            modifier = Modifier.weight(2f)
        ) {
            if (item.tekiyou.isEmpty() && isUnmatchedWarning) {
                Surface(color = Color(0xFFC62828), shape = MaterialTheme.shapes.extraSmall) {
                    Text(
                        text = "⚠未設定",
                        fontSize = (fontSize - 2f).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else {
                Text(
                    text = item.tekiyou.ifEmpty { "（未設定）" },
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (item.tekiyou.isEmpty()) Color.Gray else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = item.memo,
                fontSize = (fontSize - 1f).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 金額列（出金はマイナス表示）
        val amount = when {
            item.deposit != null -> item.deposit
            item.withdrawal != null -> -item.withdrawal
            else -> 0
        }
        val amountColor = if (amount >= 0) Color(0xFF4CAF50) else Color(0xFFE53935)
        val amountText = if (hideAmount) {
            if (amount >= 0) "***" else "-***"
        } else {
            "%,d".format(amount)
        }
        Text(
            text = amountText,
            fontSize = fontSize.sp,
            color = amountColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
    }
}

/**
 * 購買出力データを読み込む
 */
private suspend fun loadPurchaseOutputItems(
    database: ReceiptDatabase,
    accountingSoftware: AccountingSoftware = AccountingSoftware.YAYOI
): List<PurchaseOutputItem> {
    return withContext(Dispatchers.IO) {
        val receiptItems = database.receiptDao().getAllReceiptItems()
        val productMasterDao = database.productMasterDao()
        val ocrVariantDao = database.ocrVariantDao()
        val allYayoiAccounts = if (accountingSoftware == AccountingSoftware.YAYOI)
            database.yayoiAccountDao().getAll().associateBy { it.id } else emptyMap()
        val isAoiro = accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP
        val aoiroAccountNames = if (isAoiro)
            database.aoiroChoboVocabDao().getAllAccounts().associate { it.accountKey to it.name } else emptyMap()
        val aoiroMemoNames = if (isAoiro)
            database.aoiroChoboVocabDao().getAllMemoTemplates().associate { it.memoKey to it.name } else emptyMap()

        val sortedItems = receiptItems.sortedWith(
            compareBy<ReceiptItem> { it.receiptYear * 10000 + it.receiptMonth * 100 + it.receiptDay }
                .thenBy { it.sheetNumber }
                .thenBy { it.itemNumber }
        )

        sortedItems
            .filter { !it.productName.contains("小計") && !it.productName.contains("合計") }
            .map { item ->
                // productMasterId（FK）を最優先で使う。表記ゆれ（全角/半角スペース等）による
                // 完全一致ミスで勘定科目/摘要が空欄になるのを避けるため。未紐づけの過去データ
                // のみ、従来の文字列完全一致にフォールバックする。
                val productMaster = item.productMasterId?.let { productMasterDao.getById(it) }
                    ?: productMasterDao.getByName(item.productName)
                    ?: ocrVariantDao.getByText(item.productName)
                        ?.let { variant -> productMasterDao.getById(variant.productId) }

                val westernYear = 2018 + item.receiptYear
                val date = "%04d/%02d/%02d".format(westernYear, item.receiptMonth, item.receiptDay)

                if (accountingSoftware == AccountingSoftware.YAYOI) {
                    val account = productMaster?.yayoiAccountId?.let { allYayoiAccounts[it] }
                    val parentAccount = account?.parentId?.let { allYayoiAccounts[it] }
                    val mainName = parentAccount?.accountName ?: account?.accountName ?: ""
                    val subName = if (parentAccount != null) account?.accountName ?: "" else ""
                    PurchaseOutputItem(
                        id = item.id,
                        date = date,
                        tekiyou = mainName,
                        memo = item.productName,
                        amount = item.amount,
                        yayoiSubAccountName = subName,
                        defaultTaxCategory = account?.defaultTaxCategory ?: "対象外",
                        exportedAt = item.exportedAt
                    )
                } else {
                    // 表示は今の vocabulary の名前。当年度に無いキーは出さない（JSON でも送らない）
                    val accountName = productMaster?.accountKey?.let { aoiroAccountNames[it] }
                    val memoName = productMaster?.memoKey?.let { aoiroMemoNames[it] }
                    PurchaseOutputItem(
                        id = item.id,
                        date = date,
                        tekiyou = listOfNotNull(accountName, memoName).joinToString(" ／ "),
                        memo = item.productName,
                        amount = item.amount,
                        exportedAt = item.exportedAt,
                        aoiroRow = AoiroChoboTransactionsBuilder.PurchaseRow(item, productMaster)
                    )
                }
            }
    }
}

/**
 * 預金出力データを読み込む
 */
private suspend fun loadDepositOutputItems(
    database: ReceiptDatabase,
    accountingSoftware: AccountingSoftware = AccountingSoftware.YAYOI
): List<DepositOutputItem> {
    return withContext(Dispatchers.IO) {
        val depositMeisaiList = database.depositMeisaiDao().getAll()
        val passbooksById = database.passbookDao().getAll().associateBy { it.id }
        val matchingRules = database.tekiyouMatchingRuleDao().getAllWithTekiyou()
        val allYayoiAccounts = if (accountingSoftware == AccountingSoftware.YAYOI)
            database.yayoiAccountDao().getAll().associateBy { it.id } else emptyMap()
        // あおいろはルールの accountKey / memoKey を使う（結合ビューには無いのでエンティティを読む）
        val isAoiro = accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP
        val aoiroRulesByPattern = if (isAoiro)
            database.tekiyouMatchingRuleDao().getAll().associateBy { it.pattern } else emptyMap()
        val aoiroAccountNames = if (isAoiro)
            database.aoiroChoboVocabDao().getAllAccounts().associate { it.accountKey to it.name } else emptyMap()
        val aoiroMemoNames = if (isAoiro)
            database.aoiroChoboVocabDao().getAllMemoTemplates().associate { it.memoKey to it.name } else emptyMap()

        val ruleMap = mutableMapOf<String, MatchingRuleWithTekiyou>()
        for (rule in matchingRules) {
            ruleMap[rule.pattern] = rule
        }

        val sortedItems = depositMeisaiList.sortedWith(
            compareBy<DepositMeisai> { it.transactionDate }
                .thenBy { it.transactionNumber }
        )

        sortedItems.map { meisai ->
            val normalized = normalizeTekiyou(meisai.tekiyou)
            val isDeposit = meisai.amount >= 0
            val patternKey = normalized + "_" + if (isDeposit) "D" else "W"
            val rule = ruleMap[patternKey]

            if (accountingSoftware == AccountingSoftware.YAYOI) {
                // 個別オーバーライドを最優先、なければルール一致の科目
                val effectiveAccountId = meisai.overrideYayoiAccountId ?: rule?.yayoiAccountId
                val account = effectiveAccountId?.let { allYayoiAccounts[it] }
                val parentAccount = account?.parentId?.let { allYayoiAccounts[it] }
                val mainName = parentAccount?.accountName ?: account?.accountName ?: ""
                val subName = if (parentAccount != null) account?.accountName ?: "" else ""
                DepositOutputItem(
                    id = meisai.id,
                    date = meisai.transactionDate,
                    tekiyou = mainName,
                    memo = meisai.tekiyou,
                    deposit = if (meisai.amount >= 0) meisai.amount else null,
                    withdrawal = if (meisai.amount < 0) -meisai.amount else null,
                    yayoiSubAccountName = subName,
                    defaultTaxCategory = account?.defaultTaxCategory ?: "対象外",
                    exportedAt = meisai.exportedAt,
                    passbookId = meisai.passbookId,
                    bankYayoiSubAccountName = passbooksById[meisai.passbookId]?.yayoiSubAccountName.orEmpty()
                )
            } else {
                val aoiroRule = aoiroRulesByPattern[patternKey]
                // 表示は今の vocabulary の名前。個別指定があれば科目・摘要ともそちら（ビルダーと同じ優先順）
                val overridden = meisai.overrideAccountKey != null
                val accountKey = if (overridden) meisai.overrideAccountKey else aoiroRule?.accountKey
                val memoKey = if (overridden) meisai.overrideMemoKey else aoiroRule?.memoKey
                DepositOutputItem(
                    id = meisai.id,
                    date = meisai.transactionDate,
                    tekiyou = listOfNotNull(
                        accountKey?.let { aoiroAccountNames[it] },
                        memoKey?.let { aoiroMemoNames[it] }
                    ).joinToString(" ／ "),
                    memo = meisai.tekiyou,
                    deposit = if (meisai.amount >= 0) meisai.amount else null,
                    withdrawal = if (meisai.amount < 0) -meisai.amount else null,
                    exportedAt = meisai.exportedAt,
                    passbookId = meisai.passbookId,
                    aoiroRow = AoiroChoboTransactionsBuilder.DepositRow(
                        meisai, passbooksById[meisai.passbookId], aoiroRule
                    )
                )
            }
        }
    }
}

/**
 * 摘要を正規化（末尾の数字やスペースを除去）
 */
private fun normalizeTekiyou(tekiyou: String): String {
    return tekiyou
        .replace(Regex("\\s+\\d{2}-\\d{2}$"), "")
        .replace(Regex("\\s+\\d{4}$"), "")
        .replace(Regex("\\s+$"), "")
        .trim()
}

/**
 * あおいろ帳簿向け transactions.json を書き出す（docs/integration/transaction-import.md）。
 * 成功したらビルダーの結果（出した行・出せなかった行・警告）を返す。失敗・vocabulary 未取込なら null。
 */
private suspend fun exportPurchaseAoiroJsonToUri(
    context: Context,
    uri: Uri,
    database: ReceiptDatabase,
    items: List<PurchaseOutputItem>
): AoiroChoboTransactionsBuilder.Result? = withContext(Dispatchers.IO) {
    try {
        val vocabDao = database.aoiroChoboVocabDao()
        val meta = vocabDao.getMeta() ?: error("あおいろ帳簿の科目・摘要がまだ取り込まれていません")
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
        val result = AoiroChoboTransactionsBuilder.buildPurchase(
            rows = items.mapNotNull { it.aoiroRow },
            accounts = vocabDao.getAllAccounts(),
            memos = vocabDao.getAllMemoTemplates(),
            vocabMeta = meta,
            appVersion = appVersion
        )
        // UTF-8・BOM なし（契約 §1）
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(result.json)
            writer.write("\n")
        } ?: error("ファイルを開けませんでした")
        result
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "JSON出力エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
        null
    }
}

/** 預金の transactions.json を書き出す。購買の [exportPurchaseAoiroJsonToUri] と同じ流れ */
private suspend fun exportDepositAoiroJsonToUri(
    context: Context,
    uri: Uri,
    database: ReceiptDatabase,
    items: List<DepositOutputItem>
): AoiroChoboTransactionsBuilder.Result? = withContext(Dispatchers.IO) {
    try {
        val vocabDao = database.aoiroChoboVocabDao()
        val meta = vocabDao.getMeta() ?: error("あおいろ帳簿の科目・摘要がまだ取り込まれていません")
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
        val result = AoiroChoboTransactionsBuilder.buildDeposit(
            rows = items.mapNotNull { it.aoiroRow },
            accounts = vocabDao.getAllAccounts(),
            memos = vocabDao.getAllMemoTemplates(),
            vocabMeta = meta,
            appVersion = appVersion
        )
        // UTF-8・BOM なし（契約 §1）
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(result.json)
            writer.write("\n")
        } ?: error("ファイルを開けませんでした")
        result
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "JSON出力エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
        null
    }
}


private fun toYayoiDate(dateStr: String): String = CsvUtils.toYayoiDate(dateStr)

private fun qf(s: String) = CsvUtils.quoteField(s)

private fun buildPurchaseYayoiRow(item: PurchaseOutputItem): String {
    val cols = Array(25) { "" }
    cols[0] = toYayoiDate(item.date)           // 伝票日付
    cols[1] = ""                                // 伝票番号
    cols[2] = item.memo.take(40)               // 伝票摘要（商品名）
    cols[3] = ""                                // 借方部門
    cols[4] = item.tekiyou                     // 借方科目
    cols[5] = item.yayoiSubAccountName         // 借方補助科目
    cols[6] = item.defaultTaxCategory          // 借方税区分
    cols[7] = item.amount.toString()           // 借方金額
    cols[8] = ""                                // 借方消費税額
    cols[9] = ""                                // 貸方部門
    cols[10] = "買掛金"                         // 貸方科目
    cols[11] = ""                               // 貸方補助科目
    cols[12] = "対象外"                         // 貸方税区分
    cols[13] = item.amount.toString()          // 貸方金額
    cols[14] = ""                               // 貸方消費税額
    // cols[15..24] = ""
    return cols.joinToString(",") { qf(it) }
}

private fun buildDepositYayoiRow(item: DepositOutputItem): String {
    val cols = Array(25) { "" }
    cols[0] = toYayoiDate(item.date)
    cols[1] = ""
    cols[2] = item.memo.take(40)
    val isDeposit = item.deposit != null
    val amount = (item.deposit ?: item.withdrawal ?: 0).toString()
    if (isDeposit) {
        // 入金: 借方=普通預金（補助科目＝通帳）、貸方=売上/雑収入など
        cols[3] = ""
        cols[4] = "普通預金"
        cols[5] = item.bankYayoiSubAccountName
        cols[6] = "対象外"
        cols[7] = amount
        cols[8] = ""
        cols[9] = ""
        cols[10] = item.tekiyou
        cols[11] = item.yayoiSubAccountName
        cols[12] = item.defaultTaxCategory
        cols[13] = amount
        cols[14] = ""
    } else {
        // 出金: 借方=費用科目、貸方=普通預金（補助科目＝通帳）
        cols[3] = ""
        cols[4] = item.tekiyou
        cols[5] = item.yayoiSubAccountName
        cols[6] = item.defaultTaxCategory
        cols[7] = amount
        cols[8] = ""
        cols[9] = ""
        cols[10] = "普通預金"
        cols[11] = item.bankYayoiSubAccountName
        cols[12] = "対象外"
        cols[13] = amount
        cols[14] = ""
    }
    return cols.joinToString(",") { qf(it) }
}

private suspend fun exportPurchaseYayoiCsvToUri(
    context: Context,
    uri: Uri,
    items: List<PurchaseOutputItem>
): Boolean = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            val writer = os.bufferedWriter(CsvUtils.yayoiCharset())
            for (item in items) {
                writer.write(buildPurchaseYayoiRow(item))
                writer.write("\r\n")
            }
            writer.flush()
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "仕訳CSVを出力しました（弥生形式）", Toast.LENGTH_LONG).show()
        }
        true
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "CSV出力エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
        false
    }
}

private suspend fun exportDepositYayoiCsvToUri(
    context: Context,
    uri: Uri,
    items: List<DepositOutputItem>
): Boolean = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            val writer = os.bufferedWriter(CsvUtils.yayoiCharset())
            for (item in items) {
                writer.write(buildDepositYayoiRow(item))
                writer.write("\r\n")
            }
            writer.flush()
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "仕訳CSVを出力しました（弥生形式）", Toast.LENGTH_LONG).show()
        }
        true
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "CSV出力エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
        false
    }
}

/**
 * 弥生モードで科目（摘要）未設定の明細がチェックされたまま出力しようとしたときのブロックダイアログ。
 * 購買・預金で共用（レシート領収書側は別途GeneralReceiptOutputScreen.ktに同等の実装あり）
 */
@Composable
private fun UnmatchedAccountBlockDialog(
    itemNames: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("科目未設定の明細があります") },
        text = {
            Column {
                Text("以下の明細に勘定科目（摘要）が設定されていないため、弥生CSVを出力できません。")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = itemNames.take(10).joinToString("\n") { "・$it" } +
                        if (itemNames.size > 10) "\n他${itemNames.size - 10}件" else "",
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("科目を設定するか、チェックを外してください。", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

/**
 * 出力形式バッジ（弥生=青、あおいろ=青緑）
 */
@Composable
private fun OutputFormatBadge(accountingSoftware: AccountingSoftware) {
    val (bgColor, badgeLabel, formatNote) = when (accountingSoftware) {
        AccountingSoftware.YAYOI -> Triple(Color(0xFF1565C0), "弥生の青色申告", "仕訳CSV（Shift-JIS・25列）")
        AccountingSoftware.BLUE_RETURN_PREP -> Triple(Color(0xFF00695C), "あおいろ帳簿", "transactions.json（UTF-8）")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(color = bgColor, shape = MaterialTheme.shapes.small) {
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
}

/**
 * 年選択チップUI（データがある年のみ表示。データが1年分以下なら非表示）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YearSelector(
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

/**
 * 期間選択UI
 */
@Composable
private fun DateRangeSelector(
    context: Context,
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

        // 開始日
        OutlinedButton(
            onClick = {
                val cal = startDate ?: Calendar.getInstance()
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        val newDate = Calendar.getInstance().apply {
                            set(year, month, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onStartDateChange(newDate)
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = startDate?.let { dateFormat.format(it.time) } ?: "開始日",
                fontSize = 12.sp
            )
        }

        Text("~", fontSize = 14.sp)

        // 終了日
        OutlinedButton(
            onClick = {
                val cal = endDate ?: Calendar.getInstance()
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        val newDate = Calendar.getInstance().apply {
                            set(year, month, day, 23, 59, 59)
                            set(Calendar.MILLISECOND, 999)
                        }
                        onEndDateChange(newDate)
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = endDate?.let { dateFormat.format(it.time) } ?: "終了日",
                fontSize = 12.sp
            )
        }

        // クリアボタン
        TextButton(
            onClick = {
                onStartDateChange(null)
                onEndDateChange(null)
            },
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("解除", fontSize = 12.sp)
        }
    }
}

/**
 * 購買データを期間でフィルタリング
 */
private fun filterPurchaseItemsByDateRange(
    items: List<PurchaseOutputItem>,
    startDate: Calendar?,
    endDate: Calendar?
): List<PurchaseOutputItem> {
    if (startDate == null && endDate == null) return items

    val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

    return items.filter { item ->
        try {
            val itemDate = dateFormat.parse(item.date) ?: return@filter true
            val afterStart = startDate?.let { itemDate >= it.time } ?: true
            val beforeEnd = endDate?.let { itemDate <= it.time } ?: true
            afterStart && beforeEnd
        } catch (e: Exception) {
            true
        }
    }
}

/**
 * 預金データを期間でフィルタリング
 */
private fun filterDepositItemsByDateRange(
    items: List<DepositOutputItem>,
    startDate: Calendar?,
    endDate: Calendar?
): List<DepositOutputItem> {
    if (startDate == null && endDate == null) return items

    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    return items.filter { item ->
        try {
            val itemDate = dateFormat.parse(item.date) ?: return@filter true
            val afterStart = startDate?.let { itemDate >= it.time } ?: true
            val beforeEnd = endDate?.let { itemDate <= it.time } ?: true
            afterStart && beforeEnd
        } catch (e: Exception) {
            true
        }
    }
}
