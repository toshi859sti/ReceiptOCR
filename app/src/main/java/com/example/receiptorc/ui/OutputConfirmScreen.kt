package com.example.receiptorc.ui

import android.app.DatePickerDialog
import android.content.Context
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
import com.example.receiptorc.data.*
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
            context = context
        )
        "預金" -> DepositOutputConfirmContent(
            database = database,
            onBack = onBack,
            scope = scope,
            context = context,
            hideAmount = appPreferences.depositHideAmount
        )
    }
}

/**
 * 購買部門の出力データ
 */
data class PurchaseOutputItem(
    val id: Long,
    val date: String,           // 日付 (YYYY/MM/DD)
    val tekiyou: String,        // 摘要（買掛摘要名）
    val memo: String,           // メモ（商品名）
    val amount: Int,            // 購入金額
    var isSelected: Boolean = true
)

/**
 * 預金部門の出力データ
 */
data class DepositOutputItem(
    val id: Int,
    val date: String,           // 日付
    val tekiyou: String,        // 摘要（預金摘要名）
    val memo: String,           // メモ（通帳摘要原文）
    val deposit: Int?,          // 入金（正の金額）
    val withdrawal: Int?,       // 出金（負の金額の絶対値）
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
    context: Context
) {
    var allItems by remember { mutableStateOf<List<PurchaseOutputItem>>(emptyList()) }
    var outputItems by remember { mutableStateOf<List<PurchaseOutputItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 期間選択用のState
    var startDate by remember { mutableStateOf<Calendar?>(null) }
    var endDate by remember { mutableStateOf<Calendar?>(null) }

    // CSV出力用のファイル選択ランチャー
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                exportPurchaseCsvToUri(context, it, outputItems.filter { item -> item.isSelected })
            }
        }
    }

    // データ読み込み
    LaunchedEffect(Unit) {
        isLoading = true
        allItems = loadPurchaseOutputItems(database)
        outputItems = allItems
        isLoading = false
    }

    // 期間フィルタリング
    LaunchedEffect(startDate, endDate, allItems) {
        outputItems = filterPurchaseItemsByDateRange(allItems, startDate, endDate)
    }

    val selectedCount = outputItems.count { it.isSelected }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("出力確認 - 購買") },
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
                ) {
                    CircularProgressIndicator()
                }
            } else {
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

                // ヘッダー行
                PurchaseGridHeader()

                Divider()

                // グリッド
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(outputItems, key = { it.id }) { item ->
                        PurchaseGridRow(
                            item = item,
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
                                val dateFormat = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                val timestamp = dateFormat.format(Date())
                                val fileName = "購買_$timestamp.csv"
                                csvLauncher.launch(fileName)
                            },
                            enabled = selectedCount > 0
                        ) {
                            Text("CSV出力")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 購買グリッドヘッダー
 */
@Composable
private fun PurchaseGridHeader() {
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
        // 摘要/メモ列
        Text(
            text = "摘要/メモ",
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
    onToggleSelect: () -> Unit
) {
    // 摘要未設定の場合は薄い赤の背景色
    val backgroundColor = if (item.tekiyou.isBlank()) {
        Color(0xFFFFEBEE) // 薄い赤
    } else {
        Color.Transparent
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
        Text(
            text = item.date,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.2f)
        )
        // 摘要/メモ列（2行表示）
        Column(
            modifier = Modifier.weight(2f)
        ) {
            Text(
                text = item.tekiyou.ifEmpty { "（未設定）" },
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (item.tekiyou.isEmpty()) Color.Gray else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.memo,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 金額列
        Text(
            text = "%,d".format(item.amount),
            fontSize = 11.sp,
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
    hideAmount: Boolean = false
) {
    var allItems by remember { mutableStateOf<List<DepositOutputItem>>(emptyList()) }
    var outputItems by remember { mutableStateOf<List<DepositOutputItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 期間選択用のState
    var startDate by remember { mutableStateOf<Calendar?>(null) }
    var endDate by remember { mutableStateOf<Calendar?>(null) }

    // CSV出力用のファイル選択ランチャー
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                exportDepositCsvToUri(context, it, outputItems.filter { item -> item.isSelected })
            }
        }
    }

    // データ読み込み
    LaunchedEffect(Unit) {
        isLoading = true
        allItems = loadDepositOutputItems(database)
        outputItems = allItems
        isLoading = false
    }

    // 期間フィルタリング
    LaunchedEffect(startDate, endDate, allItems) {
        outputItems = filterDepositItemsByDateRange(allItems, startDate, endDate)
    }

    val selectedCount = outputItems.count { it.isSelected }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("出力確認 - 預金") },
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
                ) {
                    CircularProgressIndicator()
                }
            } else {
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

                // ヘッダー行
                DepositGridHeader()

                Divider()

                // グリッド
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(outputItems, key = { it.id }) { item ->
                        DepositGridRow(
                            item = item,
                            hideAmount = hideAmount,
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
                                val dateFormat = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                val timestamp = dateFormat.format(Date())
                                val fileName = "預金_$timestamp.csv"
                                csvLauncher.launch(fileName)
                            },
                            enabled = selectedCount > 0
                        ) {
                            Text("CSV出力")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 預金グリッドヘッダー
 */
@Composable
private fun DepositGridHeader() {
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
        // 摘要/メモ列
        Text(
            text = "摘要/メモ",
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
    onToggleSelect: () -> Unit
) {
    // 摘要未設定の場合は薄い赤の背景色
    val backgroundColor = if (item.tekiyou.isBlank()) {
        Color(0xFFFFEBEE) // 薄い赤
    } else {
        Color.Transparent
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
        Text(
            text = item.date,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.2f)
        )
        // 摘要/メモ列（2行表示）
        Column(
            modifier = Modifier.weight(2f)
        ) {
            Text(
                text = item.tekiyou.ifEmpty { "（未設定）" },
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (item.tekiyou.isEmpty()) Color.Gray else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.memo,
                fontSize = 10.sp,
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
            fontSize = 11.sp,
            color = amountColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
    }
}

/**
 * 購買出力データを読み込む
 */
private suspend fun loadPurchaseOutputItems(database: ReceiptDatabase): List<PurchaseOutputItem> {
    return withContext(Dispatchers.IO) {
        val receiptItems = database.receiptDao().getAllReceiptItems()
        val productMasterDao = database.productMasterDao()
        val rakurakuTekiyouDao = database.rakurakuTekiyouDao()

        // 取引日昇順、伝票番号昇順、行番号昇順でソート
        val sortedItems = receiptItems.sortedWith(
            compareBy<ReceiptItem> { it.receiptYear * 10000 + it.receiptMonth * 100 + it.receiptDay }
                .thenBy { it.sheetNumber }
                .thenBy { it.itemNumber }
        )

        // 小計・合計行を除外してマッピング
        sortedItems
            .filter { !it.productName.contains("小計") && !it.productName.contains("合計") }
            .map { item ->
                // 商品名からProductMasterを検索
                val productMaster = productMasterDao.getByName(item.productName)
                // ProductMasterのkaikakeTekiyouIdからRakurakuTekiyouを取得
                val tekiyouName = productMaster?.kaikakeTekiyouId?.let { tekiyouId ->
                    rakurakuTekiyouDao.getById(tekiyouId)?.tekiyouName
                } ?: ""

                // 令和年を西暦に変換（令和7年 = 2025年）
                val westernYear = 2018 + item.receiptYear

                PurchaseOutputItem(
                    id = item.id,
                    date = "%04d/%02d/%02d".format(westernYear, item.receiptMonth, item.receiptDay),
                    tekiyou = tekiyouName,
                    memo = item.productName,
                    amount = item.amount
                )
            }
    }
}

/**
 * 預金出力データを読み込む
 */
private suspend fun loadDepositOutputItems(database: ReceiptDatabase): List<DepositOutputItem> {
    return withContext(Dispatchers.IO) {
        val depositMeisaiList = database.depositMeisaiDao().getAll()
        val matchingRules = database.tekiyouMatchingRuleDao().getAllWithTekiyou()

        // 正規化パターン → マッチングルールのマップを作成
        val ruleMap = mutableMapOf<String, MatchingRuleWithTekiyou>()
        for (rule in matchingRules) {
            ruleMap[rule.pattern] = rule
        }

        // 取引日昇順、取引通番昇順でソート
        val sortedItems = depositMeisaiList.sortedWith(
            compareBy<DepositMeisai> { it.transactionDate }
                .thenBy { it.transactionNumber }
        )

        sortedItems.map { meisai ->
            // 摘要を正規化してマッチングルールを検索
            val normalized = normalizeTekiyou(meisai.tekiyou)
            val isDeposit = meisai.amount >= 0
            val patternKey = normalized + "_" + if (isDeposit) "D" else "W"
            val rule = ruleMap[patternKey]

            // 預金摘要名を取得
            val tekiyouName = rule?.rakurakuTekiyouName ?: ""

            DepositOutputItem(
                id = meisai.id,
                date = meisai.transactionDate,
                tekiyou = tekiyouName,
                memo = meisai.tekiyou,
                deposit = if (meisai.amount >= 0) meisai.amount else null,
                withdrawal = if (meisai.amount < 0) -meisai.amount else null
            )
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
 * 購買CSVを出力（URI経由）
 */
private suspend fun exportPurchaseCsvToUri(context: Context, uri: Uri, items: List<PurchaseOutputItem>) {
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                // ヘッダー行
                writer.write("ID,日付,摘要,メモ,金額")
                writer.newLine()

                // データ行
                for (item in items) {
                    val line = listOf(
                        item.id.toString(),
                        item.date,
                        escapeCsvField(item.tekiyou),
                        escapeCsvField(item.memo),
                        item.amount.toString()
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

/**
 * 預金CSVを出力（URI経由）
 */
private suspend fun exportDepositCsvToUri(context: Context, uri: Uri, items: List<DepositOutputItem>) {
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                // ヘッダー行
                writer.write("ID,日付,摘要,メモ,金額")
                writer.newLine()

                // データ行（入金はプラス、出金はマイナス）
                for (item in items) {
                    val amount = when {
                        item.deposit != null -> item.deposit
                        item.withdrawal != null -> -item.withdrawal
                        else -> 0
                    }
                    val line = listOf(
                        item.id.toString(),
                        item.date,
                        escapeCsvField(item.tekiyou),
                        escapeCsvField(item.memo),
                        amount.toString()
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

/**
 * CSVフィールドをエスケープ
 */
private fun escapeCsvField(field: String): String {
    return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
        "\"${field.replace("\"", "\"\"")}\""
    } else {
        field
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
