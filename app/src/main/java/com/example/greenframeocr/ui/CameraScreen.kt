package com.example.greenframeocr.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import android.view.WindowInsetsController
import androidx.camera.core.*
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.greenframeocr.util.OcrQualityEvaluator
import com.example.greenframeocr.util.YuvToRgbConverter
import com.example.greenframeocr.viewmodel.CameraViewModel
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
    showForceCapture: Boolean = false,
    debugMode: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    val appPreferences = remember { com.example.greenframeocr.data.AppPreferences(context) }

    // カメラ画面は横向きに固定（伝票がA4横のため）
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val original = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = original
        }
    }

    // カメラ画面中はステータスバー・ナビゲーションバーを非表示（全画面）
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val controller = activity?.window?.insetsController
        controller?.hide(
            android.view.WindowInsets.Type.statusBars() or
            android.view.WindowInsets.Type.navigationBars()
        )
        controller?.systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
        }
    }

    var isProcessing by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var focusScore by remember { mutableStateOf(0.0) }
    var sharpness by remember { mutableStateOf(0.0) }
    var isCaptureTriggered by remember { mutableStateOf(false) }
    var consecutiveGoodFrames by remember { mutableStateOf(0) }
    var badFrameCount by remember { mutableStateOf(0) }
    var lastDetectionTime by remember { mutableStateOf(0L) }
    val lastOverlayUpdateMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var latestBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // uiState 変化時のリセット処理
    LaunchedEffect(uiState) {
        when (uiState) {
            is CameraViewModel.CameraUiState.Preview -> {
                isCaptureTriggered = false
                isProcessing = false
                consecutiveGoodFrames = 0
                badFrameCount = 0
                isTorchOn = false
                camera?.cameraControl?.enableTorch(false)
                OcrQualityEvaluator.resetStability()
            }
            is CameraViewModel.CameraUiState.Processing,
            is CameraViewModel.CameraUiState.Success,
            is CameraViewModel.CameraUiState.Error -> {
                isTorchOn = false
                camera?.cameraControl?.enableTorch(false)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                camera?.cameraControl?.enableTorch(false)
                // enableTorch(false) だけでは一部端末（HAL実装依存）でトーチが物理的に
                // 消灯しないことがあるため、カメラデバイス自体をクローズして確実に消灯させる
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.e("CameraScreen", "Failed to disable torch on dispose", e)
            }
            cameraExecutor.shutdown()
        }
    }

    Scaffold { paddingValues ->
        when (val state = uiState) {
            is CameraViewModel.CameraUiState.Preview -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // カメラプレビュー
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                            cameraProviderFuture.addListener({
                                val boundProvider = cameraProviderFuture.get()
                                cameraProvider = boundProvider

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalyzer = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setTargetResolution(android.util.Size(3840, 2160))
                                    .build()
                                    .also {
                                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                                            val now = System.currentTimeMillis()
                                            if (!isProcessing) {
                                                if ((now - lastDetectionTime) >= MIN_DETECTION_INTERVAL_MS) {
                                                    lastDetectionTime = now
                                                    lastOverlayUpdateMs.set(now)
                                                    analyzeFrame(
                                                        imageProxy = imageProxy,
                                                        viewModel = viewModel,
                                                        camera = camera,
                                                        isCaptureTriggered = isCaptureTriggered,
                                                        consecutiveGoodFrames = consecutiveGoodFrames,
                                                        badFrameCount = badFrameCount,
                                                        onFocusChange = { isFocused = it },
                                                        onQualityInfo = {},
                                                        onFocusScore = { focusScore = it },
                                                        onSharpnessChange = { sharpness = it },
                                                        minSharpness = appPreferences.minSharpness,
                                                        onCaptureTriggered = { isCaptureTriggered = it },
                                                        onProcessingChange = { isProcessing = it },
                                                        onConsecutiveChange = { consecutiveGoodFrames = it },
                                                        onBadFrameCountChange = { badFrameCount = it },
                                                        onLatestBitmap = { latestBitmap = it },
                                                        debugMode = debugMode
                                                    )
                                                } else if ((now - lastOverlayUpdateMs.get()) >= OVERLAY_INTERVAL_MS) {
                                                    lastOverlayUpdateMs.set(now)
                                                    updateOverlayOnly(imageProxy, viewModel)
                                                }
                                            }
                                            imageProxy.close()
                                        }
                                    }

                                try {
                                    boundProvider.unbindAll()
                                    camera = boundProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalyzer
                                    )
                                    if (appPreferences.cameraFlash) {
                                        camera?.cameraControl?.enableTorch(true)
                                        isTorchOn = true
                                    }
                                } catch (exc: Exception) {
                                    Log.e("CameraScreen", "Camera binding failed", exc)
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // sharp 値（大きく中央表示）
                    Text(
                        text = "${"%.0f".format(sharpness)}",
                        fontSize = 48.sp,
                        color = if (sharpness >= appPreferences.minSharpness.toDouble()) Color.Green else Color.Yellow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp)
                    )

                    // 品質ステータス（画面下部に1行表示）
                    val statusText = when {
                        isCaptureTriggered -> "撮影中..."
                        isFocused && consecutiveGoodFrames > 0 ->
                            "OK $consecutiveGoodFrames/$MIN_STABLE_FOCUS_FRAMES"
                        isFocused -> "✓ OK"
                        focusScore < 0.35 -> "フォーカス待ち"
                        else -> "調整中"
                    }
                    Text(
                        text = statusText,
                        fontSize = 14.sp,
                        color = if (isFocused) Color.Green else Color.Yellow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 80.dp)
                    )

                    // フラッシュボタン
                    FloatingActionButton(
                        onClick = {
                            camera?.let { cam ->
                                isTorchOn = !isTorchOn
                                cam.cameraControl.enableTorch(isTorchOn)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        containerColor = if (isTorchOn) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.surface
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Filled.FlashlightOn
                                          else Icons.Filled.FlashlightOff,
                            contentDescription = if (isTorchOn) "フラッシュオフ" else "フラッシュオン",
                            tint = if (isTorchOn) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 強制撮影ボタン（品質チェックなし）
                    if (showForceCapture) {
                        FloatingActionButton(
                            onClick = {
                                val bmp = latestBitmap
                                if (bmp != null && !isProcessing) {
                                    camera?.cameraControl?.enableTorch(false)
                                    isProcessing = true
                                    isCaptureTriggered = true
                                    viewModel.processImage(bmp, debugMode)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PhotoCamera,
                                contentDescription = "強制撮影",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            is CameraViewModel.CameraUiState.Processing -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("緑枠を検出中...", fontSize = 16.sp)
                    }
                }
            }

            is CameraViewModel.CameraUiState.Success -> {
                // CameraScreenForOcr の LaunchedEffect が自動遷移する
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("OCR処理中...", fontSize = 16.sp)
                    }
                }
            }

            is CameraViewModel.CameraUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "エラー: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(onClick = { viewModel.resetToPreview() }) {
                            Text("再試行")
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// フレーム解析（カメラ分析コールバック）
// ============================================================

private fun analyzeFrame(
    imageProxy: ImageProxy,
    viewModel: CameraViewModel,
    camera: Camera?,
    isCaptureTriggered: Boolean,
    consecutiveGoodFrames: Int,
    badFrameCount: Int,
    onFocusChange: (Boolean) -> Unit,
    onQualityInfo: (String) -> Unit,
    onFocusScore: (Double) -> Unit = {},
    onSharpnessChange: (Double) -> Unit = {},
    minSharpness: Int = com.example.greenframeocr.data.AppPreferences.DEFAULT_MIN_SHARPNESS,
    onCaptureTriggered: (Boolean) -> Unit,
    onProcessingChange: (Boolean) -> Unit,
    onConsecutiveChange: (Int) -> Unit,
    onBadFrameCountChange: (Int) -> Unit,
    onLatestBitmap: (android.graphics.Bitmap) -> Unit = {},
    debugMode: Boolean = false
) {
    try {
        // フル解像度 Bitmap（processImage に渡す用）
        val fullBitmap = imageProxyToBitmap(imageProxy) ?: return
        onLatestBitmap(fullBitmap)

        val isGood: Boolean
        val focusScoreVal: Double
        var sharpness = 0.0

        if (DEBUG_SKIP_FOCUS_CHECK) {
            focusScoreVal = 1.0
            isGood = true
        } else {
            // ── 軽量判定：960px 1枚で完結 ──────────────────────────────
            val analysisScale = ANALYSIS_PX.toFloat() / maxOf(fullBitmap.width, fullBitmap.height)
            val analysisW     = (fullBitmap.width  * analysisScale).toInt()
            val analysisH     = (fullBitmap.height * analysisScale).toInt()
            // bilinear=false で高速リサイズ
            val analysisBitmap = android.graphics.Bitmap.createScaledBitmap(
                fullBitmap, analysisW, analysisH, false)

            // (1) フォーカス（Laplacian 分散）
            val grayBitmap = com.example.greenframeocr.util.ImagePreprocessor.toGray(analysisBitmap)
            sharpness = OcrQualityEvaluator.calculateSharpness(grayBitmap)
            grayBitmap.recycle()
            focusScoreVal = OcrQualityEvaluator.focusScore(sharpness)

            // (2) 枠検出（640px 相当・GreenMask バウンディングボックス）
            val corners = com.example.greenframeocr.util.GreenFrameDetector.detectCornersFast(analysisBitmap)

            // 緑枠オーバーレイ更新（毎フレーム）
            viewModel.updateGreenOverlay(corners, analysisW, analysisH)
            analysisBitmap.recycle()

            // (3) 枠品質評価（面積比・アスペクト比・安定性）
            val detectionScore = OcrQualityEvaluator.evaluateDetectionQuality(corners, analysisW, analysisH)

            isGood = focusScoreVal >= MIN_FOCUS_SCORE && detectionScore >= MIN_DETECTION_SCORE && sharpness >= minSharpness
            Log.d("CameraScreen", "sharp=${"%.1f".format(sharpness)} focus=${"%.2f".format(focusScoreVal)} det=${"%.2f".format(detectionScore)} good=$isGood")
            onSharpnessChange(sharpness)
        }

        onFocusScore(focusScoreVal)
        onFocusChange(isGood)

        if (fullBitmap.isRecycled) return

        // 安定フレームカウント + 自動撮影トリガー
        if (isGood && !isCaptureTriggered) {
            onBadFrameCountChange(0)
            val newCount = consecutiveGoodFrames + 1
            onConsecutiveChange(newCount)

            if (newCount >= MIN_STABLE_FOCUS_FRAMES) {
                Log.d("CameraScreen", "Auto-capture triggered (${newCount} stable frames)")
                camera?.cameraControl?.enableTorch(false)
                onProcessingChange(true)
                onCaptureTriggered(true)
                viewModel.processImage(fullBitmap, debugMode, sharpness)   // フル解像度で本番処理
                onConsecutiveChange(0)
            }
        } else if (!isGood && consecutiveGoodFrames > 0) {
            // 2フレーム連続でbadのときのみリセット（自動露出の瞬間変動を無視）
            val newBadCount = badFrameCount + 1
            if (newBadCount >= MAX_BAD_FRAMES_BEFORE_RESET) {
                Log.d("CameraScreen", "Reset consecutive count after $newBadCount bad frames")
                onConsecutiveChange(0)
                onBadFrameCountChange(0)
            } else {
                onBadFrameCountChange(newBadCount)
            }
        } else {
            onBadFrameCountChange(0)
        }

    } catch (e: Exception) {
        Log.e("CameraScreen", "Error analyzing frame", e)
    }
}

// ============================================================
// 定数
// ============================================================

private const val DEBUG_SKIP_FOCUS_CHECK      = false
private const val MIN_DETECTION_INTERVAL_MS   = 200L
private const val OVERLAY_INTERVAL_MS         = 50L    // オーバーレイ専用更新間隔（20fps相当）
private const val OVERLAY_PX                  = 480    // オーバーレイ用縮小解像度   // 500ms → 200ms（軽量化により短縮可能）
private const val MIN_STABLE_FOCUS_FRAMES     = 3       // 3フレーム連続合格でトリガー
private const val MAX_BAD_FRAMES_BEFORE_RESET = 2       // 2フレーム連続badでカウントリセット
private const val ANALYSIS_PX                 = 960     // プレビュー評価用解像度
private const val MIN_FOCUS_SCORE             = 0.50    // フォーカス最低スコア
private const val MIN_DETECTION_SCORE         = 0.8     // 枠検出最低スコア
private const val MIN_SHARPNESS_FOR_KANJI     = 1000.0  // 複雑な漢字（雲・灌など）に必要な最低鮮鋭度

// ============================================================
// ImageProxy → Bitmap 変換
// ============================================================

private fun updateOverlayOnly(imageProxy: ImageProxy, viewModel: CameraViewModel) {
    try {
        val bitmap = YuvToRgbConverter.imageProxyToBitmapDirect(imageProxy)
        if (bitmap.isRecycled) return
        val scale = OVERLAY_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width  * scale).toInt()
        val h = (bitmap.height * scale).toInt()
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, false)
        val corners = com.example.greenframeocr.util.GreenFrameDetector.detectCornersFast(small)
        viewModel.updateGreenOverlay(corners, w, h)
        small.recycle()
    } catch (e: Exception) {
        Log.e("CameraScreen", "updateOverlayOnly error", e)
    }
}

private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val bitmap = YuvToRgbConverter.imageProxyToBitmapDirect(imageProxy)
        if (!bitmap.isRecycled) bitmap else null
    } catch (e: Exception) {
        Log.e("CameraScreen", "Error converting ImageProxy to Bitmap", e)
        null
    }
}
