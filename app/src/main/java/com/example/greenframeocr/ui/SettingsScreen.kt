package com.example.greenframeocr.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AccountingSoftware
import com.example.greenframeocr.data.AppDarkMode
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.AppThemePreset
import com.example.greenframeocr.data.CameraResolution
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.util.withComputedKey
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
    onThemeChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ReceiptDatabase.getDatabase(context) }

    var selectedAccountingSoftware by remember { mutableStateOf(appPreferences.accountingSoftware) }
    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }
    var eraYear by remember { mutableIntStateOf(appPreferences.eraYear) }
    var cameraFlash by remember { mutableStateOf(appPreferences.cameraFlash) }
    var minSharpness by remember { mutableIntStateOf(appPreferences.minSharpness) }
    var depositHideAmount by remember { mutableStateOf(appPreferences.depositHideAmount) }
    var showCameraInfo by remember { mutableStateOf(false) }
    var selectedTheme by remember { mutableStateOf(appPreferences.themePreset) }
    var selectedDarkMode by remember { mutableStateOf(appPreferences.darkMode) }
    var geminiApiKey by remember { mutableStateOf(appPreferences.geminiApiKey) }
    var geminiKeyVisible by remember { mutableStateOf(false) }
    var cumulativePromptTokens by remember { mutableLongStateOf(appPreferences.cumulativePromptTokens) }
    var cumulativeCandidatesTokens by remember { mutableLongStateOf(appPreferences.cumulativeCandidatesTokens) }
    var cumulativeTotalTokens by remember { mutableLongStateOf(appPreferences.cumulativeTotalTokens) }
    var tokenUsageResetAt by remember { mutableLongStateOf(appPreferences.tokenUsageResetAt) }
    var showTokenResetConfirm by remember { mutableStateOf(false) }

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
            // ========== 作業年 ==========
            SettingSection(title = "作業年")

            Text(
                text = "JA購買伝票・JA預金・レシートの入力／一覧のデフォルト年になります（1月〜12月区切り）。年をまたぐ作業をする際に間違えやすいため、必要な時だけここで切り替えてください。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SettingItem(
                title = "現在の作業年",
                subtitle = "令和${eraYear}年 / 西暦${eraYear + 2018}年"
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

            Spacer(modifier = Modifier.height(24.dp))

            // ========== 連携会計ソフト ==========
            SettingSection(title = "📊 連携会計ソフト")

            Text(
                text = "購買品目リスト・通帳摘要リストのマッチング対象が切り替わります",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            AccountingSoftware.entries.forEach { software ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedAccountingSoftware = software
                            appPreferences.accountingSoftware = software
                        }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = selectedAccountingSoftware == software,
                        onClick = {
                            selectedAccountingSoftware = software
                            appPreferences.accountingSoftware = software
                        }
                    )
                    Column {
                        Text(software.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (software == AccountingSoftware.BLUE_RETURN_PREP) {
                            Text(
                                "マッチングはWindows側アプリで実施",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ========== テーマ ==========
            SettingSection(title = "🎨 テーマ")

            ThemePresetPicker(
                selected = selectedTheme,
                onSelect = { preset ->
                    selectedTheme = preset
                    appPreferences.themePreset = preset
                    onThemeChanged()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "ダークモード",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                AppDarkMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioButton(
                            selected = selectedDarkMode == mode,
                            onClick = {
                                selectedDarkMode = mode
                                appPreferences.darkMode = mode
                                onThemeChanged()
                            }
                        )
                        Text(text = mode.displayName, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ========== 表示設定 ==========
            SettingSection(title = "📱 表示設定")

            SettingItem(
                title = "一覧文字サイズ",
                subtitle = "通帳・レシート・摘要マッチング画面の文字サイズ（現在: ${listFontSize.toInt()}sp）"
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (listFontSize > 10f) {
                                listFontSize -= 1f
                                appPreferences.listFontSize = listFontSize
                            }
                        },
                        enabled = listFontSize > 10f
                    ) {
                        Text("A-", fontSize = 14.sp)
                    }
                    Text(
                        text = "${listFontSize.toInt()}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    IconButton(
                        onClick = {
                            if (listFontSize < 20f) {
                                listFontSize += 1f
                                appPreferences.listFontSize = listFontSize
                            }
                        },
                        enabled = listFontSize < 20f
                    ) {
                        Text("A+", fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ========== JA購買伝票 ==========
            SettingSection(title = "🌾 JA購買伝票")

            SettingItem(
                title = "最低鮮鋭度",
                subtitle = "撮影トリガーの鮮鋭度閾値（現在: $minSharpness）"
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (minSharpness > 500) {
                                minSharpness -= 100
                                appPreferences.minSharpness = minSharpness
                            }
                        }
                    ) {
                        Text("-", fontSize = 24.sp)
                    }
                    Text(
                        text = "$minSharpness",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(60.dp)
                    )
                    IconButton(
                        onClick = {
                            if (minSharpness < 3000) {
                                minSharpness += 100
                                appPreferences.minSharpness = minSharpness
                            }
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

            Spacer(modifier = Modifier.height(24.dp))

            // ========== JA預金 ==========
            SettingSection(title = "🏦 JA預金")

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

            // ========== レシート・領収書 ==========
            SettingSection(title = "🛒 レシート・領収書")

            Text(
                text = "Gemini APIキー",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            Text(
                text = "未設定・オフライン時は手動入力が必要です",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "無料枠のAPIキーは入力画像・出力内容がGoogle側のモデル改善に" +
                            "利用される場合があります。JA購買伝票には取引先情報が含まれるため、" +
                            "Google Cloud Consoleで請求先アカウントを設定した「課金有効化キー」の" +
                            "使用を推奨します（設定手順は開発ガイドライン参照）。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            OutlinedTextField(
                value = geminiApiKey,
                onValueChange = { geminiApiKey = it },
                placeholder = { Text("AIza...") },
                visualTransformation = if (geminiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        TextButton(onClick = { geminiKeyVisible = !geminiKeyVisible }) {
                            Text(if (geminiKeyVisible) "隠す" else "表示", fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            appPreferences.geminiApiKey = geminiApiKey
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "保存")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 累計トークン使用量（他者への請求目的の集計。JA購買伝票OCR・レシートOCR・
            // 3画面のAI科目提案すべての合算値。リセットボタンを押すまで加算し続ける）
            Text(
                text = "累計トークン使用量",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = if (tokenUsageResetAt > 0) {
                    SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date(tokenUsageResetAt)) + " 以降の累計"
                } else {
                    "リセットなし（記録開始以降の累計）"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("入力: ${cumulativePromptTokens}トークン", fontSize = 14.sp)
                    Text("出力: ${cumulativeCandidatesTokens}トークン", fontSize = 14.sp)
                    Text(
                        "合計: ${cumulativeTotalTokens}トークン",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            TextButton(
                onClick = { showTokenResetConfirm = true },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("リセット")
            }

            if (showTokenResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showTokenResetConfirm = false },
                    title = { Text("累計トークン数をリセット") },
                    text = { Text("現在の累計をリセットして、この時点から新たに集計を開始します。よろしいですか？") },
                    confirmButton = {
                        TextButton(onClick = {
                            appPreferences.resetTokenUsage()
                            cumulativePromptTokens = appPreferences.cumulativePromptTokens
                            cumulativeCandidatesTokens = appPreferences.cumulativeCandidatesTokens
                            cumulativeTotalTokens = appPreferences.cumulativeTotalTokens
                            tokenUsageResetAt = appPreferences.tokenUsageResetAt
                            showTokenResetConfirm = false
                        }) {
                            Text("リセットする", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTokenResetConfirm = false }) { Text("キャンセル") }
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

// テーマごとのライト・ダーク代表色（primary）
private val presetLightColors = mapOf(
    AppThemePreset.GREEN  to Color(0xFF2E7D52),
    AppThemePreset.PURPLE to Color(0xFF6650A4),
    AppThemePreset.BLUE   to Color(0xFF0061A4),
    AppThemePreset.TERRA  to Color(0xFF9A4335),
    AppThemePreset.MONO   to Color(0xFF424242),
)
private val presetDarkColors = mapOf(
    AppThemePreset.GREEN  to Color(0xFF9DD5AC),
    AppThemePreset.PURPLE to Color(0xFFD0BCFF),
    AppThemePreset.BLUE   to Color(0xFF9ECAFF),
    AppThemePreset.TERRA  to Color(0xFFFFB4A5),
    AppThemePreset.MONO   to Color(0xFFE0E0E0),
)

/**
 * テーマプリセット選択ピッカー
 */
@Composable
private fun ThemePresetPicker(
    selected: AppThemePreset,
    onSelect: (AppThemePreset) -> Unit
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppThemePreset.entries.forEach { preset ->
            val swatch = if (dark) presetDarkColors[preset]!! else presetLightColors[preset]!!
            val isSelected = preset == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(preset) }
                    .padding(vertical = 4.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        )
                ) {
                    if (isSelected) {
                        val lum = 0.299f * swatch.red + 0.587f * swatch.green + 0.114f * swatch.blue
                    val iconTint = if (lum < 0.55f) Color.White else Color.Black
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = preset.displayName,
                    fontSize = 10.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
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
    val receiptItems: List<com.example.greenframeocr.data.ReceiptItem>,
    val sheetData: List<com.example.greenframeocr.data.SheetData>,
    val monthlyData: List<com.example.greenframeocr.data.MonthlyData>
)

/**
 * 通帳データエクスポート用のデータクラス
 */
data class DepositExportData(
    val exportDate: String,
    val dataType: String = "deposit",
    val depositMeisai: List<com.example.greenframeocr.data.DepositMeisai>
)

/**
 * マスタデータエクスポート用のデータクラス
 */
data class MasterExportData(
    val exportDate: String,
    val dataType: String = "master",
    val version: Int = 2,
    val productMasters: List<com.example.greenframeocr.data.ProductMaster>,
    val ocrVariants: List<com.example.greenframeocr.data.OcrVariant>,
    val rakurakuTekiyou: List<com.example.greenframeocr.data.RakurakuTekiyou>? = null,
    val tekiyouMatchingRules: List<com.example.greenframeocr.data.TekiyouMatchingRule>? = null
)

/**
 * 全データエクスポート用のデータクラス
 */
data class AllExportData(
    val exportDate: String,
    val dataType: String = "all",
    val version: Int = 1,
    // 購買伝票
    val receiptItems: List<com.example.greenframeocr.data.ReceiptItem>,
    val sheetData: List<com.example.greenframeocr.data.SheetData>,
    val monthlyData: List<com.example.greenframeocr.data.MonthlyData>,
    // 通帳データ
    val depositMeisai: List<com.example.greenframeocr.data.DepositMeisai>,
    // マスタデータ
    val productMasters: List<com.example.greenframeocr.data.ProductMaster>,
    val ocrVariants: List<com.example.greenframeocr.data.OcrVariant>,
    val rakurakuTekiyou: List<com.example.greenframeocr.data.RakurakuTekiyou>,
    val tekiyouMatchingRules: List<com.example.greenframeocr.data.TekiyouMatchingRule>
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
        importData.productMasters.forEach { db.productMasterDao().insertIgnore(it.withComputedKey()) }
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
                val newId = db.productMasterDao().insert(product.copy(id = 0).withComputedKey())
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
