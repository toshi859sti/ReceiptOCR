package com.example.receiptorc.ui

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
import com.example.receiptorc.data.AppPreferences
import com.example.receiptorc.data.DepositMeisai
import com.example.receiptorc.data.ReceiptDatabase
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
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var importResultMessage by remember { mutableStateOf<String?>(null) }

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
                    // 重複チェック：取引日+通番で既存データをフィルタ
                    var newCount = 0
                    var skipCount = 0
                    for (meisai in parsedList) {
                        val existing = database.depositMeisaiDao().findByDateAndNumber(
                            meisai.transactionDate,
                            meisai.transactionNumber
                        )
                        if (existing == null) {
                            database.depositMeisaiDao().insert(meisai)
                            newCount++
                        } else {
                            skipCount++
                        }
                    }
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

            // 件数表示
            Text(
                text = "件数: ${meisaiList.size}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ヘッダー行
            PassbookGridHeader()

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
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(meisaiList) { meisai ->
                        PassbookGridRow(meisai, hideAmount)
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
private fun PassbookGridHeader() {
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
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "通番",
            modifier = Modifier.weight(0.8f),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "摘要",
            modifier = Modifier.weight(2f),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "金額",
            modifier = Modifier.weight(1.2f),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * グリッド行
 */
@Composable
private fun PassbookGridRow(meisai: DepositMeisai, hideAmount: Boolean = false) {
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
            text = meisai.transactionDate, // YYYY-MM-DD形式で表示
            modifier = Modifier.weight(1.4f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = meisai.transactionNumber.takeLast(3), // 下3桁
            modifier = Modifier.weight(0.8f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = meisai.tekiyou,
            modifier = Modifier.weight(2f),
            fontSize = 12.sp,
            textAlign = TextAlign.Start,
            maxLines = 2
        )
        Text(
            text = amountText,
            modifier = Modifier.weight(1.2f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = amountColor
        )
    }
}
