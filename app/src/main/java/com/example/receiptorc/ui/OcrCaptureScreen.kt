package com.example.receiptorc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.receiptorc.util.OCRProcessor
import com.example.receiptorc.viewmodel.CameraViewModel
import com.example.receiptorc.viewmodel.OcrCaptureViewModel
import kotlinx.coroutines.launch

/**
 * OCR撮影画面
 * B→OCR→C→OCRの個別撮影フローを実装
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrCaptureScreen(
    viewModel: OcrCaptureViewModel,
    onBack: () -> Unit,
    onComplete: (Int, Int, Int) -> Unit  // (year, month, sheetNumber) -> 編集画面へ遷移
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val bBlockRows by viewModel.bBlockRows.collectAsState()
    val amounts by viewModel.amounts.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR撮影") },
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
        when (currentStep) {
            is OcrCaptureViewModel.CaptureStep.Initial -> {
                InitialScreen(
                    onStartCapture = { viewModel.startBBlockCapture() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is OcrCaptureViewModel.CaptureStep.CapturingBBlock -> {
                CameraScreenForOcr(
                    targetBlock = "Bブロック（取引日・商品名）",
                    expectedMarkerIds = "0, 1, 2, 3",
                    onOcrComplete = { rows ->
                        viewModel.processBBlock(rows as List<OCRProcessor.BBlockRow>)
                    },
                    onCancel = { viewModel.reset() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is OcrCaptureViewModel.CaptureStep.ProcessingBBlock -> {
                ProcessingScreen(
                    message = "Bブロックを処理中...",
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is OcrCaptureViewModel.CaptureStep.BBlockComplete -> {
                BBlockResultScreen(
                    rows = (currentStep as OcrCaptureViewModel.CaptureStep.BBlockComplete).rows,
                    onContinue = { viewModel.startCBlockCapture() },
                    onRetry = { viewModel.reset() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is OcrCaptureViewModel.CaptureStep.CapturingCBlock -> {
                CameraScreenForOcr(
                    targetBlock = "Cブロック（税込金額・分類計）",
                    expectedMarkerIds = "4, 5, 6, 7",
                    onOcrComplete = { rows ->
                        viewModel.processCBlock(rows as List<OCRProcessor.CBlockRow>)
                    },
                    onCancel = { viewModel.reset() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is OcrCaptureViewModel.CaptureStep.ProcessingCBlock -> {
                ProcessingScreen(
                    message = "Cブロックを処理中...",
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is OcrCaptureViewModel.CaptureStep.AllComplete -> {
                AllCompleteScreen(
                    bBlockRows = bBlockRows,
                    amounts = amounts,
                    onSave = {
                        scope.launch {
                            val success = viewModel.saveData()
                            if (success) {
                                // 編集画面へ遷移
                                onComplete(
                                    viewModel.getIssueYear(),
                                    viewModel.getIssueMonth(),
                                    viewModel.getCurrentSheetNumber()
                                )
                            }
                        }
                    },
                    onRetry = { viewModel.reset() },
                    errorMessage = errorMessage,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

/**
 * 初期画面
 */
@Composable
private fun InitialScreen(
    onStartCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "OCR撮影フロー",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "撮影手順",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text("1. Bブロック（取引日・商品名）を撮影")
                Text("2. OCR結果を確認")
                Text("3. Cブロック（税込金額・分類計）を撮影")
                Text("4. OCR結果を確認")
                Text("5. データを保存")
            }
        }

        Button(
            onClick = onStartCapture,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("撮影開始", fontSize = 18.sp)
        }
    }
}

/**
 * カメラ画面（OCR用）
 */
@Composable
private fun CameraScreenForOcr(
    targetBlock: String,
    expectedMarkerIds: String,
    onOcrComplete: (List<Any>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // targetBlockをキーにして、B/Cブロックごとに異なるViewModelインスタンスを作成
    val cameraViewModel: CameraViewModel = viewModel(key = targetBlock)
    val uiState by cameraViewModel.uiState.collectAsState()

    // プレビュー設定を読み込む
    val appPreferences = remember { com.example.receiptorc.data.AppPreferences(context) }
    val showPreview = appPreferences.cameraPreview

    // 再撮影時にViewModelの状態をリセット
    LaunchedEffect(targetBlock) {
        cameraViewModel.resetToPreview()
    }

    // OCR完了時の処理（プレビュー表示OFFの場合のみ自動で次へ）
    LaunchedEffect(uiState) {
        if (uiState is CameraViewModel.CameraUiState.Success) {
            if (!showPreview) {
                val successState = uiState as CameraViewModel.CameraUiState.Success
                onOcrComplete(successState.ocrResults)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ガイダンス（撮影前のみ表示）
        if (uiState !is CameraViewModel.CameraUiState.Success) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "撮影対象: $targetBlock",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "マーカーID: $expectedMarkerIds",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // カメラプレビュー / 結果表示
        Box(modifier = Modifier.weight(1f)) {
            CameraScreen(viewModel = cameraViewModel)
        }

        // ボタン
        if (uiState is CameraViewModel.CameraUiState.Success && showPreview) {
            // プレビュー表示ONの場合：次へボタン
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { cameraViewModel.resetToPreview() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("再撮影")
                }
                Button(
                    onClick = {
                        val successState = uiState as CameraViewModel.CameraUiState.Success
                        onOcrComplete(successState.ocrResults)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("次へ")
                }
            }
        } else if (uiState !is CameraViewModel.CameraUiState.Success) {
            // 撮影前：キャンセルボタン
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("キャンセル")
            }
        }
    }
}

/**
 * 処理中画面
 */
@Composable
private fun ProcessingScreen(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = message, fontSize = 18.sp)
        }
    }
}

/**
 * Bブロック結果確認画面
 */
@Composable
private fun BBlockResultScreen(
    rows: List<OCRProcessor.BBlockRow>,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Bブロック OCR結果",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 不正な日付の数
        val invalidDateCount = rows.count { !it.isDateValid }
        if (invalidDateCount > 0) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 24.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "${invalidDateCount}件の不正な日付を検出",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // 結果リスト
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rows) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (!row.isDateValid) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "日付: ${row.date}",
                                fontSize = 14.sp,
                                color = if (!row.isDateValid) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = if (!row.isDateValid) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "商品: ${row.productName}",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (!row.isDateValid) {
                            Text(
                                text = "⚠️",
                                fontSize = 20.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ボタン
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("再撮影")
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f)
            ) {
                Text("次へ（Cブロック）")
            }
        }
    }
}

/**
 * 全完了画面
 */
@Composable
private fun AllCompleteScreen(
    bBlockRows: List<OCRProcessor.BBlockRow>,
    amounts: List<String>,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "OCR完了",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // エラーメッセージ
        errorMessage?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // 統合結果リスト
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(bBlockRows.size) { index ->
                val bRow = bBlockRows.getOrNull(index)
                val amount = amounts.getOrNull(index) ?: "---"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (bRow?.isDateValid == false) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "行${index + 1}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (bRow?.isDateValid == false) {
                                Text(
                                    text = "⚠️",
                                    fontSize = 16.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "日付: ${bRow?.date ?: "---"}",
                            fontSize = 14.sp,
                            color = if (bRow?.isDateValid == false) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = "商品: ${bRow?.productName ?: "---"}",
                            fontSize = 14.sp
                        )
                        Text(
                            text = "金額: $amount",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ボタン
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("最初から")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("保存して編集")
            }
        }
    }
}
