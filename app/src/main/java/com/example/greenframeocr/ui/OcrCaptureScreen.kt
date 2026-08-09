package com.example.greenframeocr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import kotlinx.coroutines.flow.filter
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
                    viewModel.onDetectionResult(detectionResult)
                },
                onCancel = { viewModel.reset() }
            )
        }
        is OcrCaptureViewModel.CaptureStep.Preview -> {
            val step = currentStep as OcrCaptureViewModel.CaptureStep.Preview
            TransformPreviewScreen(
                detectionResult = step.detectionResult,
                onSend = { viewModel.processDetectionResult(step.detectionResult) },
                onRetry = { viewModel.retryFromPreview() }
            )
        }
        is OcrCaptureViewModel.CaptureStep.Processing -> {
            ProcessingScreen(message = "OCR処理中...")
        }
        is OcrCaptureViewModel.CaptureStep.Error -> {
            val step = currentStep as OcrCaptureViewModel.CaptureStep.Error
            OcrErrorScreen(
                message = step.message,
                onRetry = { viewModel.processDetectionResult(step.detectionResult) },
                onRetake = { viewModel.retryFromPreview() },
                onCancel = onBack
            )
        }
        is OcrCaptureViewModel.CaptureStep.Complete -> {
            val step = currentStep as OcrCaptureViewModel.CaptureStep.Complete
            CompleteScreen(
                rows = parsedRows,
                dateColumnAligned = step.dateColumnAligned,
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

    // リセット後に Success を受け付ける（古い Success で即時終了するのを防ぐ）
    LaunchedEffect(Unit) {
        cameraViewModel.resetToPreview()
        // reset 後の状態変化のみ監視
        cameraViewModel.uiState
            .filter { it is CameraViewModel.CameraUiState.Success }
            .collect { state ->
                onOcrComplete((state as CameraViewModel.CameraUiState.Success).detectionResult)
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
            CameraScreen(viewModel = cameraViewModel, showForceCapture = true)

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
// OCR失敗画面（Gemini API失敗時の専用エラーUI）
// ============================================================

/**
 * OCR処理（Gemini API呼び出し）が失敗した際に表示する専用エラー画面。
 * 撮影済みの画像を破棄せずに保持し、「再試行」で同じ画像のまま再送信できる。
 * ネットワーク瞬断など一過性のエラーで撮り直しを強制されるストレスを避けるため。
 */
@Composable
fun OcrErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "OCR処理に失敗しました",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp)
            )
        }
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("再試行（同じ画像で送信）", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRetake,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("撮り直す")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onCancel) {
            Text("キャンセル")
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
    dateColumnAligned: Boolean = true,
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

        if (!dateColumnAligned) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
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
                        text = "取引日の読み取りを確認できませんでした。まれに月がずれて読み取られる" +
                            "ことがあるため、各行の取引日をご確認ください。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

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
    val bgColor = when {
        row.isSubtotal -> androidx.compose.ui.graphics.Color(0xFFE8F5E9)
        else           -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "日付: ${row.date ?: "---"}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val nameLabel = if (row.isSubtotal) "小計" else "商品"
            Text(
                text = "$nameLabel: ${row.productName ?: "---"}",
                fontSize = 14.sp,
                fontWeight = if (row.isSubtotal) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (!row.isSubtotal) "カテゴリ: ${row.category}" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "金額: ${row.amount?.let { "¥%,d".format(it) } ?: "---"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
