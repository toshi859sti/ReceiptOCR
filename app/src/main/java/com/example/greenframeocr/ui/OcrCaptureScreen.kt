package com.example.greenframeocr.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.greenframeocr.util.GreenFrameDetector
import com.example.greenframeocr.viewmodel.CameraViewModel
import com.example.greenframeocr.viewmodel.OcrCaptureViewModel
import kotlinx.coroutines.launch

/**
 * OCR撮影画面
 * 緑枠検出 → 行OCR → 確認 → 保存 のフローを管理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrCaptureScreen(
    viewModel: OcrCaptureViewModel,
    onBack: () -> Unit,
    onComplete: (Int, Int, Int) -> Unit  // (year, month, sheetNumber)
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val parsedRows  by viewModel.parsedRows.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val scope = rememberCoroutineScope()

    when (currentStep) {
        is OcrCaptureViewModel.CaptureStep.Initial -> {
            InitialScreen(
                onStartCapture = { viewModel.startCapture() },
                onBack = onBack
            )
        }
        is OcrCaptureViewModel.CaptureStep.Capturing -> {
            CameraScreenForOcr(
                onOcrComplete = { detectionResult ->
                    viewModel.processDetectionResult(detectionResult)
                },
                onCancel = { viewModel.reset() }
            )
        }
        is OcrCaptureViewModel.CaptureStep.Processing -> {
            ProcessingScreen(message = "OCR処理中...")
        }
        is OcrCaptureViewModel.CaptureStep.Complete -> {
            CompleteScreen(
                rows = parsedRows,
                onSave = {
                    scope.launch {
                        val success = viewModel.saveData()
                        if (success) {
                            onComplete(
                                viewModel.getIssueYear(),
                                viewModel.getIssueMonth(),
                                viewModel.getCurrentSheetNumber()
                            )
                        }
                    }
                },
                onRetry = { viewModel.reset() },
                onBack = onBack,
                errorMessage = errorMessage
            )
        }
    }
}

// ============================================================
// 初期画面
// ============================================================

@Composable
private fun InitialScreen(
    onStartCapture: () -> Unit,
    onBack: () -> Unit = {},
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
            text = "OCR撮影",
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
                Text("1. 伝票を平らな面に置く")
                Text("2. 緑色の外枠が画面全体に収まるよう撮影")
                Text("3. 品質OKになったら自動撮影")
                Text("4. OCR結果を確認・保存")
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

// ============================================================
// カメラ画面（緑枠OCR用）
// ============================================================

@Composable
fun CameraScreenForOcr(
    onOcrComplete: (GreenFrameDetector.DetectionResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraViewModel: CameraViewModel = viewModel()
    val uiState by cameraViewModel.uiState.collectAsState()

    // 再撮影時に状態をリセット
    LaunchedEffect(Unit) {
        cameraViewModel.resetToPreview()
    }

    // 検出成功 → 自動で processDetectionResult に渡す
    LaunchedEffect(uiState) {
        if (uiState is CameraViewModel.CameraUiState.Success) {
            val detectionResult = (uiState as CameraViewModel.CameraUiState.Success).detectionResult
            onOcrComplete(detectionResult)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (uiState is CameraViewModel.CameraUiState.Success ||
            uiState is CameraViewModel.CameraUiState.Processing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("処理中...", fontSize = 18.sp)
                }
            }
        } else {
            CameraScreen(viewModel = cameraViewModel)

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Text("キャンセル")
            }
        }
    }
}

// ============================================================
// 処理中画面
// ============================================================

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

// ============================================================
// 完了画面（OCR結果確認）
// ============================================================

@Composable
private fun CompleteScreen(
    rows: List<OcrCaptureViewModel.ParsedRow>,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit = {},
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "OCR完了（${rows.size}行）",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

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

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rows) { row ->
                ParsedRowCard(row)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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

@Composable
private fun ParsedRowCard(row: OcrCaptureViewModel.ParsedRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (row.isAmountValid)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "日付: ${row.date ?: "---"}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (row.isAmountValid) "✓ 検算OK" else "✗ 要確認",
                    fontSize = 13.sp,
                    color = if (row.isAmountValid) Color.Green else Color.Red,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "商品: ${row.productName ?: "---"}",
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "数量: ${row.quantity ?: "---"}  単価: ${row.unitPrice?.let { "¥$it" } ?: "---"}",
                    fontSize = 13.sp
                )
                Text(
                    text = "金額: ${row.amount?.let { "¥$it" } ?: "---"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
