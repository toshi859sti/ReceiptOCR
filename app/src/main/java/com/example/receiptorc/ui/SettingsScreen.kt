package com.example.receiptorc.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.receiptorc.data.AppPreferences
import com.example.receiptorc.data.CameraResolution
import com.example.receiptorc.data.ReceiptDatabase
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ReceiptDatabase.getDatabase(context) }

    var eraYear by remember { mutableIntStateOf(appPreferences.eraYear) }
    var currentIssueMonth by remember { mutableIntStateOf(appPreferences.currentIssueMonth) }
    var selectedResolution by remember { mutableStateOf(appPreferences.cameraResolution) }
    var cameraPreview by remember { mutableStateOf(appPreferences.cameraPreview) }
    var cameraFlash by remember { mutableStateOf(appPreferences.cameraFlash) }
    var showCameraInfo by remember { mutableStateOf(false) }
    var showResolutionDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    // エクスポート用ファイル作成ランチャー
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = exportData(context, db, it)
                exportMessage = result
            }
        }
    }

    // インポート用ファイル選択ランチャー
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = importData(context, db, it)
                importMessage = result
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "戻る"
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 年号設定
            SettingSection(title = "📅 年号設定")

            SettingItem(
                title = "令和何年",
                subtitle = "現在: 令和${eraYear}年"
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (eraYear > 1) {
                                eraYear--
                                appPreferences.eraYear = eraYear
                            }
                        }
                    ) {
                        Text("-", fontSize = 24.sp)
                    }

                    Text(
                        text = "${eraYear}年",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(60.dp)
                    )

                    IconButton(
                        onClick = {
                            eraYear++
                            appPreferences.eraYear = eraYear
                        }
                    ) {
                        Text("+", fontSize = 24.sp)
                    }
                }
            }

            SettingItem(
                title = "現在の月",
                subtitle = "OCR撮影時に使用: ${currentIssueMonth}月"
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (currentIssueMonth > 1) {
                                currentIssueMonth--
                                appPreferences.currentIssueMonth = currentIssueMonth
                            }
                        }
                    ) {
                        Text("-", fontSize = 24.sp)
                    }

                    Text(
                        text = "${currentIssueMonth}月",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(60.dp)
                    )

                    IconButton(
                        onClick = {
                            if (currentIssueMonth < 12) {
                                currentIssueMonth++
                                appPreferences.currentIssueMonth = currentIssueMonth
                            }
                        }
                    ) {
                        Text("+", fontSize = 24.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // カメラ設定
            SettingSection(title = "📷 カメラ設定")

            SettingItem(
                title = "解像度",
                subtitle = CameraResolution.fromValue(selectedResolution).displayName
            ) {
                TextButton(onClick = { showResolutionDialog = true }) {
                    Text("変更")
                }
            }

            SettingItem(
                title = "プレビュー表示",
                subtitle = if (cameraPreview) "ON" else "OFF"
            ) {
                Switch(
                    checked = cameraPreview,
                    onCheckedChange = {
                        cameraPreview = it
                        appPreferences.cameraPreview = it
                    }
                )
            }

            SettingItem(
                title = "フラッシュ",
                subtitle = if (cameraFlash) "ON" else "OFF"
            ) {
                Switch(
                    checked = cameraFlash,
                    onCheckedChange = {
                        cameraFlash = it
                        appPreferences.cameraFlash = it
                    }
                )
            }

            SettingItem(
                title = "カメラ情報",
                subtitle = "デバイス情報を表示"
            ) {
                TextButton(onClick = { showCameraInfo = true }) {
                    Text("表示")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // データ管理
            SettingSection(title = "💾 データ管理")

            SettingItem(
                title = "データをエクスポート",
                subtitle = exportMessage ?: "データをJSONファイルに保存"
            ) {
                TextButton(onClick = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    exportLauncher.launch("receipt_backup_$timestamp.json")
                }) {
                    Text("エクスポート")
                }
            }

            SettingItem(
                title = "データをインポート",
                subtitle = importMessage ?: "JSONファイルからデータを読み込み"
            ) {
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/json"))
                }) {
                    Text("インポート")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // アプリ情報
            SettingSection(title = "ℹ️ アプリ情報")

            SettingItem(
                title = "バージョン",
                subtitle = "1.0.0"
            ) {}

            SettingItem(
                title = "ビルド情報",
                subtitle = "2025-12-14"
            ) {}
        }
    }

    // 解像度選択ダイアログ
    if (showResolutionDialog) {
        ResolutionDialog(
            currentResolution = selectedResolution,
            onDismiss = { showResolutionDialog = false },
            onSelect = { resolution ->
                selectedResolution = resolution
                appPreferences.cameraResolution = resolution
                showResolutionDialog = false
            }
        )
    }

    // カメラ情報ダイアログ
    if (showCameraInfo) {
        CameraInfoDialog(
            context = context,
            onDismiss = { showCameraInfo = false }
        )
    }
}

/**
 * 設定セクションタイトル
 */
@Composable
private fun SettingSection(title: String) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    Divider()
}

/**
 * 設定項目
 */
@Composable
private fun SettingItem(
    title: String,
    subtitle: String,
    action: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action()
    }
}

/**
 * 解像度選択ダイアログ
 */
@Composable
private fun ResolutionDialog(
    currentResolution: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解像度を選択") },
        text = {
            Column {
                CameraResolution.values().forEach { resolution ->
                    TextButton(
                        onClick = { onSelect(resolution.value) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(resolution.displayName)
                            if (resolution.value == currentResolution) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

/**
 * カメラ情報ダイアログ
 */
@Composable
private fun CameraInfoDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("カメラ情報") },
        text = {
            Column {
                InfoRow("デバイス", Build.MODEL)
                InfoRow("メーカー", Build.MANUFACTURER)
                InfoRow("Android", Build.VERSION.RELEASE)
                InfoRow("SDK", Build.VERSION.SDK_INT.toString())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

/**
 * 情報行
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "$label:", fontWeight = FontWeight.Medium)
        Text(text = value)
    }
}

/**
 * データエクスポート用のデータクラス
 */
data class ExportData(
    val exportDate: String,
    val receiptItems: List<com.example.receiptorc.data.ReceiptItem>,
    val sheetData: List<com.example.receiptorc.data.SheetData>,
    val monthlyData: List<com.example.receiptorc.data.MonthlyData>
)

/**
 * データをエクスポート
 */
private suspend fun exportData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        // データベースから全データを取得
        val receiptItems = db.receiptDao().getAllReceiptItems()
        val sheetData = db.receiptDao().getAllSheetData()
        val monthlyData = db.receiptDao().getAllMonthlyData()

        // エクスポート用データを作成
        val exportData = ExportData(
            exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            receiptItems = receiptItems,
            sheetData = sheetData,
            monthlyData = monthlyData
        )

        // JSONに変換
        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(exportData)

        // ファイルに書き込み
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(json.toByteArray())
        }

        "エクスポート成功: ${receiptItems.size}件"
    } catch (e: Exception) {
        "エクスポート失敗: ${e.message}"
    }
}

/**
 * データをインポート
 */
private suspend fun importData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        // ファイルから読み込み
        val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes().toString(Charsets.UTF_8)
        } ?: return@withContext "ファイル読み込み失敗"

        // JSONをパース
        val gson = Gson()
        val importData = gson.fromJson(json, ExportData::class.java)

        // データベースに保存（既存データは削除しない、重複は上書き）
        db.receiptDao().insertReceiptItems(importData.receiptItems)
        importData.sheetData.forEach { db.receiptDao().insertSheetData(it) }
        importData.monthlyData.forEach { db.receiptDao().insertMonthlyData(it) }

        "インポート成功: ${importData.receiptItems.size}件"
    } catch (e: Exception) {
        "インポート失敗: ${e.message}"
    }
}
