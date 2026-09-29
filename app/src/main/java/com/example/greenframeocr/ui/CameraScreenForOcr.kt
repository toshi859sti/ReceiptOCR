package com.example.greenframeocr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.greenframeocr.util.GreenFrameDetector
import com.example.greenframeocr.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.filter

// ============================================================
// カメラ画面（緑枠OCR用）
// `ui/ReceiptInputScreen.kt` の CameraView から呼ばれる本番の撮影UI。
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
