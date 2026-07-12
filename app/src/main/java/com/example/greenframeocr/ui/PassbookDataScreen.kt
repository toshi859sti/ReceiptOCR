package com.example.greenframeocr.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.DepositMeisai
import com.example.greenframeocr.data.ReceiptDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 通帳データ画面
 * 取引日・取引通番・摘要・金額のグリッド表示、CSV取込機能付き
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassbookDataScreen(
    database: ReceiptDatabase,
    appPreferences: AppPreferences,
    onBack: () -> Unit,
    initialUri: Uri? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var meisaiList by remember { mutableStateOf<List<DepositMeisai>>(emptyList()) }
    val hideAmount = appPreferences.depositHideAmount
    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var importResultMessage by remember { mutableStateOf<String?>(null) }

    val currentCalendarYear = remember {
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
    }
    val availableYears = remember(meisaiList) {
        meisaiList.map { it.transactionDate.take(4) }
            .filter { it.matches(Regex("\\d{4}")) }
            .distinct()
            .sortedDescending()
    }
    var selectedYear by remember(availableYears) {
        mutableStateOf(
            when {
                availableYears.contains(currentCalendarYear) -> currentCalendarYear
                availableYears.isNotEmpty() -> availableYears.first()
                else -> null
            }
        )
    }
    val displayedMeisai = remember(meisaiList, selectedYear) {
        if (selectedYear == null) meisaiList
        else meisaiList.filter { it.transactionDate.startsWith(selectedYear!!) }
    }
    var yearDropdownExpanded by remember { mutableStateOf(false) }

    // データ読み込み
    fun loadData() {
        scope.launch {
            isLoading = true
            meisaiList = database.depositMeisaiDao().getAll()
            isLoading = false
        }
    }

    // CSV取込処理（重複チェック付きマージ）
    fun importCsv(uri: Uri) {
        scope.launch {
            try {
                val parsedList = withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                    val lines = reader.readLines()
                    reader.close()

                    // ヘッダー行をスキップして解析
                    lines.drop(1).mapNotNull { line ->
                        parseCsvLine(line)
                    }
                }

                if (parsedList.isNotEmpty()) {
                    val results = database.depositMeisaiDao().insertAllIgnoreDuplicates(parsedList)
                    val newCount = results.count { it != -1L }
                    val skipCount = results.count { it == -1L }
                    importResultMessage = if (skipCount > 0) {
                        "${newCount}件追加（${skipCount}件は既存のためスキップ）"
                    } else {
                        "${newCount}件のデータを取り込みました"
                    }
                    loadData()
                } else {
                    importResultMessage = "取り込み可能なデータがありませんでした"
                }
            } catch (e: Exception) {
                importResultMessage = "取込エラー: ${e.message}"
            }
        }
    }

    // ファイル選択ランチャー
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importCsv(it) }
    }

    // 初期URI（共有から起動した場合）
    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            importCsv(initialUri)
        }
    }

    // 初期データ読み込み
    LaunchedEffect(Unit) {
        loadData()
    }

    // 全削除確認ダイアログ
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("確認") },
            text = { Text("すべての通帳データを削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            database.depositMeisaiDao().deleteAll()
                            loadData()
                        }
                        showDeleteConfirmDialog = false
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // 取込結果メッセージ
    importResultMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3000)
            importResultMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通帳データ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "戻る"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch("text/*") }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "CSV取込"
                        )
                    }
                    IconButton(onClick = { showDeleteConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "全削除"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
            // 取込結果メッセージ
            importResultMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center
                )
            }

            // 年フィルター + 件数表示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = yearDropdownExpanded,
                    onExpandedChange = { yearDropdownExpanded = it },
                    modifier = Modifier.width(130.dp)
                ) {
                    OutlinedTextField(
                        value = selectedYear ?: "全て",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("年", fontSize = 11.sp) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearDropdownExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true,
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = yearDropdownExpanded,
                        onDismissRequest = { yearDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全て") },
                            onClick = { selectedYear = null; yearDropdownExpanded = false }
                        )
                        availableYears.forEach { year ->
                            DropdownMenuItem(
                                text = { Text(year) },
                                onClick = { selectedYear = year; yearDropdownExpanded = false }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "件数: ${displayedMeisai.size}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ヘッダー行
            PassbookGridHeader(fontSize = listFontSize)

            Divider(thickness = 2.dp)

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (meisaiList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "データがありません",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "右上の＋ボタンでCSVを取り込んでください",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (displayedMeisai.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${selectedYear}年のデータがありません",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayedMeisai) { meisai ->
                        PassbookGridRow(meisai, hideAmount, listFontSize)
                        Divider()
                    }
                }
            }
        }
    }
}

/**
 * CSV行をパース
 */
private fun parseCsvLine(line: String): DepositMeisai? {
    val parts = line.split(",")
    if (parts.size < 4) return null

    val date = parts[0].trim()
    val number = parts[1].trim()
    val tekiyou = parts[2].trim()
    val amount = parts[3].trim().toIntOrNull() ?: return null
    val memo = if (parts.size > 4) parts[4].trim() else ""

    // 日付形式チェック（簡易）
    if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return null

    return DepositMeisai(
        transactionDate = date,
        transactionNumber = number,
        tekiyou = tekiyou,
        amount = amount,
        memo = memo
    )
}

/**
 * グリッドヘッダー
 */
@Composable
private fun PassbookGridHeader(fontSize: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "取引日",
            modifier = Modifier.weight(1.4f),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "通番",
            modifier = Modifier.weight(0.8f),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "摘要",
            modifier = Modifier.weight(2f),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "金額",
            modifier = Modifier.weight(1.2f),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * グリッド行
 */
@Composable
private fun PassbookGridRow(meisai: DepositMeisai, hideAmount: Boolean = false, fontSize: Float = AppPreferences.DEFAULT_LIST_FONT_SIZE) {
    val amountColor = if (meisai.amount >= 0) {
        Color(0xFF1B5E20) // 緑（入金）
    } else {
        Color(0xFFB71C1C) // 赤（出金）
    }

    val amountText = if (hideAmount) {
        if (meisai.amount >= 0) "***" else "-***"
    } else {
        String.format("%,d", meisai.amount)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = meisai.transactionDate,
            modifier = Modifier.weight(1.4f),
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = meisai.transactionNumber.takeLast(3),
            modifier = Modifier.weight(0.8f),
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = meisai.tekiyou,
            modifier = Modifier.weight(2f),
            fontSize = fontSize.sp,
            textAlign = TextAlign.Start,
            maxLines = 2
        )
        Text(
            text = amountText,
            modifier = Modifier.weight(1.2f),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = amountColor
        )
    }
}
