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
    onBack: () -> Unit,
    onNavigateToOcrLearningStatus: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ReceiptDatabase.getDatabase(context) }

    var eraYear by remember { mutableIntStateOf(appPreferences.eraYear) }
    var cameraFlash by remember { mutableStateOf(appPreferences.cameraFlash) }
    var depositHideAmount by remember { mutableStateOf(appPreferences.depositHideAmount) }
    var showCameraInfo by remember { mutableStateOf(false) }

    // メッセージ状態
    var allExportMessage by remember { mutableStateOf<String?>(null) }
    var allImportMessage by remember { mutableStateOf<String?>(null) }
    var purchaseExportMessage by remember { mutableStateOf<String?>(null) }
    var purchaseImportMessage by remember { mutableStateOf<String?>(null) }
    var depositExportMessage by remember { mutableStateOf<String?>(null) }
    var depositImportMessage by remember { mutableStateOf<String?>(null) }
    var masterExportMessage by remember { mutableStateOf<String?>(null) }
    var masterImportMessage by remember { mutableStateOf<String?>(null) }
    var recountMessage by remember { mutableStateOf<String?>(null) }
    var isRecounting by remember { mutableStateOf(false) }

    // データ管理用の状態
    var exportDataType by remember { mutableStateOf(DataType.ALL) }
    var importDataType by remember { mutableStateOf(DataType.ALL) }
    var clearDataType by remember { mutableStateOf(DataType.ALL) }
    var clearMessage by remember { mutableStateOf<String?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }

    // 全データエクスポート用ランチャー
    val allExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = exportAllData(context, db, it)
                allExportMessage = result
            }
        }
    }

    // 全データインポート用ランチャー
    val allImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = importAllData(context, db, it)
                allImportMessage = result
            }
        }
    }

    // 購買伝票エクスポート用ランチャー
    val purchaseExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = exportPurchaseData(context, db, it)
                purchaseExportMessage = result
            }
        }
    }

    // 購買伝票インポート用ランチャー
    val purchaseImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = importPurchaseData(context, db, it)
                purchaseImportMessage = result
            }
        }
    }

    // 通帳データエクスポート用ランチャー
    val depositExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = exportDepositData(context, db, it)
                depositExportMessage = result
            }
        }
    }

    // 通帳データインポート用ランチャー
    val depositImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = importDepositData(context, db, it)
                depositImportMessage = result
            }
        }
    }

    // マスタデータエクスポート用ランチャー
    val masterExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = exportMasterData(context, db, it)
                masterExportMessage = result
            }
        }
    }

    // マスタデータインポート用ランチャー
    val masterImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = importMasterData(context, db, it)
                masterImportMessage = result
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
            // ========== 購買部門 ==========
            SettingSection(title = "🌾 購買部門")

            SettingItem(
                title = "入力年",
                subtitle = "令和${eraYear}年 / 西暦${2018 + eraYear}年"
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

            SettingItem(
                title = "OCR学習状況",
                subtitle = "誤認識パターンの学習データベースを表示"
            ) {
                TextButton(onClick = onNavigateToOcrLearningStatus) {
                    Text("表示")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ========== 預金部門 ==========
            SettingSection(title = "🏦 預金部門")

            SettingItem(
                title = "金額を非表示",
                subtitle = if (depositHideAmount) "金額は *** で表示" else "金額を表示"
            ) {
                Switch(
                    checked = depositHideAmount,
                    onCheckedChange = {
                        depositHideAmount = it
                        appPreferences.depositHideAmount = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ========== データ管理 ==========
            SettingSection(title = "💾 データ管理")

            // エクスポート
            DataManagementSection(
                title = "エクスポート",
                selectedType = exportDataType,
                onTypeSelected = { exportDataType = it },
                message = when (exportDataType) {
                    DataType.ALL -> allExportMessage
                    DataType.PURCHASE -> purchaseExportMessage
                    DataType.DEPOSIT -> depositExportMessage
                    DataType.MASTER -> masterExportMessage
                },
                onExecute = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    when (exportDataType) {
                        DataType.ALL -> allExportLauncher.launch("all_backup_$timestamp.json")
                        DataType.PURCHASE -> purchaseExportLauncher.launch("purchase_$timestamp.json")
                        DataType.DEPOSIT -> depositExportLauncher.launch("deposit_$timestamp.json")
                        DataType.MASTER -> masterExportLauncher.launch("master_$timestamp.json")
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // インポート
            DataManagementSection(
                title = "インポート",
                selectedType = importDataType,
                onTypeSelected = { importDataType = it },
                message = when (importDataType) {
                    DataType.ALL -> allImportMessage
                    DataType.PURCHASE -> purchaseImportMessage
                    DataType.DEPOSIT -> depositImportMessage
                    DataType.MASTER -> masterImportMessage
                },
                onExecute = {
                    when (importDataType) {
                        DataType.ALL -> allImportLauncher.launch(arrayOf("application/json"))
                        DataType.PURCHASE -> purchaseImportLauncher.launch(arrayOf("application/json"))
                        DataType.DEPOSIT -> depositImportLauncher.launch(arrayOf("application/json"))
                        DataType.MASTER -> masterImportLauncher.launch(arrayOf("application/json"))
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // データクリア
            DataManagementSection(
                title = "データクリア",
                selectedType = clearDataType,
                onTypeSelected = { clearDataType = it },
                message = clearMessage,
                onExecute = {
                    showClearConfirmDialog = true
                },
                isDestructive = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingItem(
                title = "購買品の使用回数を再カウント",
                subtitle = recountMessage ?: "登録済み伝票データから使用回数を再集計"
            ) {
                TextButton(
                    onClick = {
                        if (!isRecounting) {
                            scope.launch {
                                isRecounting = true
                                recountMessage = "再カウント中..."
                                val result = recountProductFrequency(db)
                                recountMessage = result
                                isRecounting = false
                            }
                        }
                    },
                    enabled = !isRecounting
                ) {
                    Text(if (isRecounting) "処理中..." else "実行")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ========== アプリ情報 ==========
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

    // カメラ情報ダイアログ
    if (showCameraInfo) {
        CameraInfoDialog(
            context = context,
            onDismiss = { showCameraInfo = false }
        )
    }

    // データクリア確認ダイアログ
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("データクリアの確認") },
            text = {
                Text("${clearDataType.displayName}を削除しますか？\nこの操作は取り消せません。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        if (!isClearing) {
                            scope.launch {
                                isClearing = true
                                clearMessage = "削除中..."
                                val result = clearData(db, clearDataType)
                                clearMessage = result
                                isClearing = false
                            }
                        }
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("キャンセル")
                }
            }
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
 * 設定サブセクションタイトル
 */
@Composable
private fun SettingSubSection(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
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
 * データ種類の列挙
 */
enum class DataType(val displayName: String) {
    ALL("全データ"),
    PURCHASE("購買伝票"),
    DEPOSIT("通帳データ"),
    MASTER("マスタデータ")
}

/**
 * データ管理セクション（ラジオボタン + 実行ボタン）
 */
@Composable
private fun DataManagementSection(
    title: String,
    selectedType: DataType,
    onTypeSelected: (DataType) -> Unit,
    message: String?,
    onExecute: () -> Unit,
    isDestructive: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ラジオボタングループ（2行表示）
        // 1行目: 全データ、マスタデータ
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(DataType.ALL, DataType.MASTER).forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    RadioButton(
                        selected = selectedType == type,
                        onClick = { onTypeSelected(type) },
                        colors = if (isDestructive) {
                            RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            RadioButtonDefaults.colors()
                        }
                    )
                    Text(
                        text = type.displayName,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
            }
        }
        // 2行目: 購買伝票、通帳データ
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(DataType.PURCHASE, DataType.DEPOSIT).forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    RadioButton(
                        selected = selectedType == type,
                        onClick = { onTypeSelected(type) },
                        colors = if (isDestructive) {
                            RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            RadioButtonDefaults.colors()
                        }
                    )
                    Text(
                        text = type.displayName,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // メッセージと実行ボタン
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message ?: "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onExecute,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text("実行")
            }
        }
    }
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
 * 購買伝票データエクスポート用のデータクラス
 */
data class PurchaseExportData(
    val exportDate: String,
    val dataType: String = "purchase",
    val receiptItems: List<com.example.receiptorc.data.ReceiptItem>,
    val sheetData: List<com.example.receiptorc.data.SheetData>,
    val monthlyData: List<com.example.receiptorc.data.MonthlyData>
)

/**
 * 通帳データエクスポート用のデータクラス
 */
data class DepositExportData(
    val exportDate: String,
    val dataType: String = "deposit",
    val depositMeisai: List<com.example.receiptorc.data.DepositMeisai>
)

/**
 * マスタデータエクスポート用のデータクラス
 */
data class MasterExportData(
    val exportDate: String,
    val dataType: String = "master",
    val version: Int = 2,
    val productMasters: List<com.example.receiptorc.data.ProductMaster>,
    val ocrVariants: List<com.example.receiptorc.data.OcrVariant>,
    val rakurakuTekiyou: List<com.example.receiptorc.data.RakurakuTekiyou>? = null,
    val tekiyouMatchingRules: List<com.example.receiptorc.data.TekiyouMatchingRule>? = null
)

/**
 * 全データエクスポート用のデータクラス
 */
data class AllExportData(
    val exportDate: String,
    val dataType: String = "all",
    val version: Int = 1,
    // 購買伝票
    val receiptItems: List<com.example.receiptorc.data.ReceiptItem>,
    val sheetData: List<com.example.receiptorc.data.SheetData>,
    val monthlyData: List<com.example.receiptorc.data.MonthlyData>,
    // 通帳データ
    val depositMeisai: List<com.example.receiptorc.data.DepositMeisai>,
    // マスタデータ
    val productMasters: List<com.example.receiptorc.data.ProductMaster>,
    val ocrVariants: List<com.example.receiptorc.data.OcrVariant>,
    val rakurakuTekiyou: List<com.example.receiptorc.data.RakurakuTekiyou>,
    val tekiyouMatchingRules: List<com.example.receiptorc.data.TekiyouMatchingRule>
)

/**
 * 全データをエクスポート
 */
private suspend fun exportAllData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        val exportData = AllExportData(
            exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            receiptItems = db.receiptDao().getAllReceiptItems(),
            sheetData = db.receiptDao().getAllSheetData(),
            monthlyData = db.receiptDao().getAllMonthlyData(),
            depositMeisai = db.depositMeisaiDao().getAll(),
            productMasters = db.productMasterDao().getAll(),
            ocrVariants = db.ocrVariantDao().getAll(),
            rakurakuTekiyou = db.rakurakuTekiyouDao().getAll(),
            tekiyouMatchingRules = db.tekiyouMatchingRuleDao().getAll()
        )

        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(exportData)

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(json.toByteArray())
        }

        "成功: 購買${exportData.receiptItems.size}件, 通帳${exportData.depositMeisai.size}件, マスタ${exportData.productMasters.size}件"
    } catch (e: Exception) {
        "失敗: ${e.message}"
    }
}

/**
 * 全データをインポート
 */
private suspend fun importAllData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes().toString(Charsets.UTF_8)
        } ?: return@withContext "ファイル読み込み失敗"

        val gson = Gson()
        val importData = gson.fromJson(json, AllExportData::class.java)

        // 購買伝票
        db.receiptDao().insertReceiptItems(importData.receiptItems)
        importData.sheetData.forEach { db.receiptDao().insertSheetData(it) }
        importData.monthlyData.forEach { db.receiptDao().insertMonthlyData(it) }

        // 通帳データ
        db.depositMeisaiDao().insertAll(importData.depositMeisai)

        // マスタデータ
        importData.productMasters.forEach { db.productMasterDao().insertIgnore(it) }
        importData.ocrVariants.forEach { db.ocrVariantDao().insertIgnore(it) }
        importData.rakurakuTekiyou.forEach { db.rakurakuTekiyouDao().insertIgnore(it) }
        importData.tekiyouMatchingRules.forEach { db.tekiyouMatchingRuleDao().insertIgnore(it) }

        "成功: 購買${importData.receiptItems.size}件, 通帳${importData.depositMeisai.size}件"
    } catch (e: Exception) {
        "失敗: ${e.message}"
    }
}

/**
 * 購買伝票データをエクスポート
 */
private suspend fun exportPurchaseData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        val receiptItems = db.receiptDao().getAllReceiptItems()
        val sheetData = db.receiptDao().getAllSheetData()
        val monthlyData = db.receiptDao().getAllMonthlyData()

        val exportData = PurchaseExportData(
            exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            receiptItems = receiptItems,
            sheetData = sheetData,
            monthlyData = monthlyData
        )

        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(exportData)

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(json.toByteArray())
        }

        "成功: ${receiptItems.size}件"
    } catch (e: Exception) {
        "失敗: ${e.message}"
    }
}

/**
 * 購買伝票データをインポート
 */
private suspend fun importPurchaseData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes().toString(Charsets.UTF_8)
        } ?: return@withContext "ファイル読み込み失敗"

        val gson = Gson()
        val importData = gson.fromJson(json, PurchaseExportData::class.java)

        db.receiptDao().insertReceiptItems(importData.receiptItems)
        importData.sheetData.forEach { db.receiptDao().insertSheetData(it) }
        importData.monthlyData.forEach { db.receiptDao().insertMonthlyData(it) }

        "成功: ${importData.receiptItems.size}件"
    } catch (e: Exception) {
        "失敗: ${e.message}"
    }
}

/**
 * 通帳データをエクスポート
 */
private suspend fun exportDepositData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        val depositMeisai = db.depositMeisaiDao().getAll()

        val exportData = DepositExportData(
            exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            depositMeisai = depositMeisai
        )

        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(exportData)

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(json.toByteArray())
        }

        "成功: ${depositMeisai.size}件"
    } catch (e: Exception) {
        "失敗: ${e.message}"
    }
}

/**
 * 通帳データをインポート
 */
private suspend fun importDepositData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes().toString(Charsets.UTF_8)
        } ?: return@withContext "ファイル読み込み失敗"

        val gson = Gson()
        val importData = gson.fromJson(json, DepositExportData::class.java)

        db.depositMeisaiDao().insertAll(importData.depositMeisai)

        "成功: ${importData.depositMeisai.size}件"
    } catch (e: Exception) {
        "失敗: ${e.message}"
    }
}

/**
 * マスタデータをエクスポート（購買品リスト + OCR学習データ）
 */
private suspend fun exportMasterData(
    context: Context,
    db: ReceiptDatabase,
    uri: android.net.Uri
): String = withContext(Dispatchers.IO) {
    try {
        // データベースから全データを取得
        val productMasters = db.productMasterDao().getAll()
        val ocrVariants = db.ocrVariantDao().getAll()
        val rakurakuTekiyou = db.rakurakuTekiyouDao().getAll()
        val tekiyouMatchingRules = db.tekiyouMatchingRuleDao().getAll()

        // エクスポート用データを作成
        val exportData = MasterExportData(
            exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            productMasters = productMasters,
            ocrVariants = ocrVariants,
            rakurakuTekiyou = rakurakuTekiyou,
            tekiyouMatchingRules = tekiyouMatchingRules
        )

        // JSONに変換
        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(exportData)

        // ファイルに書き込み
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(json.toByteArray())
        }

        "成功: 商品${productMasters.size}件, 学習${ocrVariants.size}件, 摘要${rakurakuTekiyou.size}件, ルール${tekiyouMatchingRules.size}件"
    } catch (e: Exception) {
        "エクスポート失敗: ${e.message}"
    }
}

/**
 * マスタデータをインポート（マージ方式）
 */
private suspend fun importMasterData(
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
        val importData = gson.fromJson(json, MasterExportData::class.java)

        var productAdded = 0
        var productSkipped = 0
        var variantAdded = 0
        var variantSkipped = 0

        // 購買品リストのインポート（商品名で重複チェック）
        val existingProducts = db.productMasterDao().getAll()
        val existingProductNames = existingProducts.map { it.canonicalName }.toSet()
        val productIdMap = mutableMapOf<Long, Long>() // 旧ID → 新ID

        for (product in importData.productMasters) {
            if (product.canonicalName in existingProductNames) {
                // 既存商品のIDをマッピング
                val existing = existingProducts.find { it.canonicalName == product.canonicalName }
                if (existing != null) {
                    productIdMap[product.id] = existing.id
                }
                productSkipped++
            } else {
                // 新規追加（IDは自動採番されるため、新しいIDを記録）
                val newId = db.productMasterDao().insert(product.copy(id = 0))
                productIdMap[product.id] = newId
                productAdded++
            }
        }

        // OCR学習データのインポート（normalizedText + productIdで重複チェック）
        for (variant in importData.ocrVariants) {
            // 旧productIdを新IDに変換
            val newProductId = productIdMap[variant.productId]
            if (newProductId == null) {
                variantSkipped++
                continue
            }

            // 重複チェック
            val existing = db.ocrVariantDao().findByNormalizedTextAndProduct(
                variant.normalizedText,
                newProductId
            )

            if (existing != null) {
                variantSkipped++
            } else {
                // 新規追加
                db.ocrVariantDao().insert(variant.copy(id = 0, productId = newProductId))
                variantAdded++
            }
        }

        // 摘要辞書のインポート
        var tekiyouAdded = 0
        importData.rakurakuTekiyou?.forEach { tekiyou ->
            val result = db.rakurakuTekiyouDao().insertIgnore(tekiyou.copy(id = 0))
            if (result > 0) tekiyouAdded++
        }

        // マッチングルールのインポート
        var ruleAdded = 0
        importData.tekiyouMatchingRules?.forEach { rule ->
            val result = db.tekiyouMatchingRuleDao().insertIgnore(rule.copy(id = 0))
            if (result > 0) ruleAdded++
        }

        "成功: 商品+${productAdded}, 学習+${variantAdded}, 摘要+${tekiyouAdded}, ルール+${ruleAdded}"
    } catch (e: Exception) {
        "インポート失敗: ${e.message}"
    }
}

/**
 * 購買品の使用回数を再カウント
 * 登録済み伝票データ（ReceiptItem）から各商品の出現回数を集計し、
 * ProductMasterのfrequencyCountを更新する
 */
private suspend fun recountProductFrequency(
    db: ReceiptDatabase
): String = withContext(Dispatchers.IO) {
    try {
        // 全ての伝票アイテムを取得
        val receiptItems = db.receiptDao().getAllReceiptItems()

        // 商品名ごとの出現回数をカウント
        val frequencyMap = mutableMapOf<String, Int>()
        for (item in receiptItems) {
            val productName = item.productName.trim()
            if (productName.isNotEmpty()) {
                frequencyMap[productName] = (frequencyMap[productName] ?: 0) + 1
            }
        }

        // 全てのProductMasterを取得
        val productMasters = db.productMasterDao().getAll()
        var updatedCount = 0

        // 各ProductMasterの使用回数を更新
        for (product in productMasters) {
            val newCount = frequencyMap[product.canonicalName] ?: 0
            if (product.frequencyCount != newCount) {
                db.productMasterDao().update(product.copy(frequencyCount = newCount))
                updatedCount++
            }
        }

        "再カウント完了: ${updatedCount}件更新\n(伝票${receiptItems.size}件から集計)"
    } catch (e: Exception) {
        "再カウント失敗: ${e.message}"
    }
}

/**
 * データクリア処理
 */
private suspend fun clearData(
    db: ReceiptDatabase,
    dataType: DataType
): String = withContext(Dispatchers.IO) {
    try {
        when (dataType) {
            DataType.ALL -> {
                // 購買伝票
                db.receiptDao().deleteAllReceiptItems()
                db.receiptDao().deleteAllMonthlyData()
                db.receiptDao().deleteAllSheetData()
                // 通帳データ
                db.depositMeisaiDao().deleteAll()
                // マスタデータ
                db.ocrVariantDao().deleteAll()
                db.productMasterDao().deleteAll()
                db.rakurakuTekiyouDao().deleteAll()
                db.tekiyouMatchingRuleDao().deleteAll()
                "全データを削除しました"
            }
            DataType.PURCHASE -> {
                db.receiptDao().deleteAllReceiptItems()
                db.receiptDao().deleteAllMonthlyData()
                db.receiptDao().deleteAllSheetData()
                "購買伝票を削除しました"
            }
            DataType.DEPOSIT -> {
                db.depositMeisaiDao().deleteAll()
                "通帳データを削除しました"
            }
            DataType.MASTER -> {
                db.ocrVariantDao().deleteAll()
                db.productMasterDao().deleteAll()
                db.rakurakuTekiyouDao().deleteAll()
                db.tekiyouMatchingRuleDao().deleteAll()
                "マスタデータを削除しました"
            }
        }
    } catch (e: Exception) {
        "削除失敗: ${e.message}"
    }
}
