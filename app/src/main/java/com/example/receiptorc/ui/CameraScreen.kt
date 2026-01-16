package com.example.receiptorc.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.receiptorc.util.ImageProcessor
import com.example.receiptorc.util.OCRProcessor
import com.example.receiptorc.util.OcrQualityEvaluator
import com.example.receiptorc.util.YuvToRgbConverter
import com.example.receiptorc.viewmodel.CameraViewModel
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    // AppPreferencesから設定を読み込む（毎回最新の値を参照）
    val appPreferences = remember { com.example.receiptorc.data.AppPreferences(context) }
    val showPreview = appPreferences.cameraPreview

    // デバッグ：設定値をログ出力
    SideEffect {
        Log.d("CameraScreen", "showPreview = $showPreview")
    }

    var isProcessing by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var isBrightnessGood by remember { mutableStateOf(false) }
    var qualityInfo by remember { mutableStateOf("") }
    var hasDetectedMarker by remember { mutableStateOf(false) }
    var detectedMarkerCount by remember { mutableStateOf(0) }
    var detectedMarkerIds by remember { mutableStateOf("") }
    var lastProcessTime by remember { mutableStateOf(0L) }
    var lastDetectionTime by remember { mutableStateOf(0L) }
    var consecutiveGoodFrames by remember { mutableStateOf(0) }  // フレーム安定性カウンター
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // 設定からフラッシュの自動点灯を読み込む
    val autoFlashEnabled = appPreferences.cameraFlash

    // UIStateがPreviewに戻った時に状態をリセット
    LaunchedEffect(uiState) {
        if (uiState is CameraViewModel.CameraUiState.Preview) {
            hasDetectedMarker = false
            isProcessing = false
            detectedMarkerCount = 0
            detectedMarkerIds = ""
            consecutiveGoodFrames = 0  // 安定性カウンターもリセット
            // 撮影完了後は自動でトーチを再点灯しない（手動操作またはカメラ起動時のみ）
        } else if (uiState is CameraViewModel.CameraUiState.Processing) {
            // OCR処理開始時にトーチを消灯
            isTorchOn = false
            camera?.cameraControl?.enableTorch(false)
            Log.d("CameraScreen", "OCR processing started, torch disabled")
        } else if (uiState is CameraViewModel.CameraUiState.Success) {
            // OCR処理完了時にトーチを消灯
            isTorchOn = false
            camera?.cameraControl?.enableTorch(false)
            Log.d("CameraScreen", "OCR completed, torch disabled")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 画面破棄時にトーチを強制的に消灯
            try {
                camera?.cameraControl?.enableTorch(false)
                Log.d("CameraScreen", "CameraScreen disposed, torch disabled")
            } catch (e: Exception) {
                Log.e("CameraScreen", "Failed to disable torch on dispose", e)
            }
            cameraExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("伝票OCR") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is CameraViewModel.CameraUiState.Preview -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                // 品質チェック・マーカー検出・OCR処理用（高解像度）
                                val imageAnalyzer = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setTargetResolution(android.util.Size(3840, 2160))  // 4K解像度
                                    .build()
                                    .also {
                                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                                            val currentTime = System.currentTimeMillis()
                                            val timeSinceLastDetection = currentTime - lastDetectionTime

                                            // 処理中でなく、かつ最小間隔が経過している場合のみ処理
                                            if (!isProcessing && timeSinceLastDetection >= MIN_DETECTION_INTERVAL_MS) {
                                                lastDetectionTime = currentTime
                                                processImage(
                                                    imageProxy,
                                                    ctx,
                                                    viewModel,
                                                    camera,
                                                    hasDetectedMarker,
                                                    consecutiveGoodFrames,
                                                    onFocusChange = { focused ->
                                                        isFocused = focused
                                                    },
                                                    onBrightnessChange = { good ->
                                                        isBrightnessGood = good
                                                    },
                                                    onQualityInfo = { info ->
                                                        qualityInfo = info
                                                    },
                                                    onMarkerDetected = { detected ->
                                                        hasDetectedMarker = detected
                                                    },
                                                    onMarkerInfo = { count, ids ->
                                                        detectedMarkerCount = count
                                                        detectedMarkerIds = ids
                                                        lastProcessTime = System.currentTimeMillis()
                                                    },
                                                    onProcessingChange = { processing ->
                                                        isProcessing = processing
                                                    },
                                                    onConsecutiveGoodFramesChange = { frames ->
                                                        consecutiveGoodFrames = frames
                                                    }
                                                )
                                            }
                                            imageProxy.close()
                                        }
                                    }

                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                                try {
                                    cameraProvider.unbindAll()
                                    camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalyzer
                                    )
                                    Log.d("CameraScreen", "Camera bound with ImageAnalysis (4K resolution)")

                                    // 設定でフラッシュがONの場合、カメラ起動時にトーチを点灯
                                    if (appPreferences.cameraFlash) {
                                        camera?.cameraControl?.enableTorch(true)
                                        isTorchOn = true
                                        Log.d("CameraScreen", "Auto torch enabled from settings")
                                    }

                                    // 連続オートフォーカスはデフォルトで有効
                                    // シャープネス計算によってフォーカス判定を行う
                                } catch (exc: Exception) {
                                    Log.e("CameraScreen", "Use case binding failed", exc)
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 品質情報表示（画面中央に大きく）
                    if (qualityInfo.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = qualityInfo,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 26.sp
                                )
                                // 総合ステータス
                                val allGood = isFocused && hasDetectedMarker
                                Text(
                                    text = if (allGood) "✓ 撮影準備完了" else "カメラを調整してください",
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                                    color = if (allGood) Color.Green else Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }
                        }
                    }

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
                        containerColor = if (isTorchOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                            contentDescription = if (isTorchOn) "フラッシュオフ" else "フラッシュオン",
                            tint = if (isTorchOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
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
                    CircularProgressIndicator()
                }
            }
            is CameraViewModel.CameraUiState.Success -> {
                // 下に敷くタイプの結果表示
                UnderlayResultScreen(
                    state = state,
                    viewModel = viewModel
                )
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

private fun processImage(
    imageProxy: ImageProxy,
    context: android.content.Context,
    viewModel: CameraViewModel,
    camera: Camera?,
    hasDetectedMarker: Boolean,
    consecutiveGoodFrames: Int,
    onFocusChange: (Boolean) -> Unit,
    onBrightnessChange: (Boolean) -> Unit,
    onQualityInfo: (String) -> Unit,
    onMarkerDetected: (Boolean) -> Unit,
    onMarkerInfo: (Int, String) -> Unit,
    onProcessingChange: (Boolean) -> Unit,
    onConsecutiveGoodFramesChange: (Int) -> Unit
) {
    try {
        // プレビュー用の低解像度画像（品質チェック・マーカー検出用）
        val bitmap = imageProxyToBitmap(imageProxy)
        if (bitmap == null || bitmap.isRecycled) {
            Log.w("CameraScreen", "Bitmap is null or recycled, skipping frame")
            return
        }

        // Bitmapが有効であることを確認
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            Log.w("CameraScreen", "Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}")
            return
        }

        // OCR品質を総合評価（フォーカス、文字高さ、コントラスト）
        val quality = if (DEBUG_SKIP_FOCUS_CHECK) {
            // デバッグモード：ダミーの良好な品質スコアを返す
            OcrQualityEvaluator.OcrQuality(
                score = 1.0,
                focus = 250.0,
                focusScore = 1.0,
                charHeight = 25,
                charHeightScore = 1.0,
                contrast = 0.8,
                contrastScore = 0.8,
                isGood = true
            )
        } else {
            OcrQualityEvaluator.evaluateOcrQuality(bitmap)
        }

        // 品質状態を更新
        val isQualityGood = quality.isGood
        onFocusChange(isQualityGood)  // 総合判定結果をフォーカスフラグに設定
        onBrightnessChange(true)  // 明るさは個別チェックせず、コントラストで評価

        // 品質情報を更新（詳細表示 + 目標値）
        val qualityStatus = buildString {
            // 総合スコア
            append("【撮影品質】\n")
            append("総合: ${(quality.score * 100).toInt()}% / 目標70%以上 ")
            append(if (quality.score >= 0.70) "✓" else "✗")
            append("\n\n")

            // 文字高さ（理想: 10-20px = 85-100%）
            val charIdeal = if (quality.charHeight in 10..20) "✓" else "✗"
            append("文字: ${quality.charHeight}px (${(quality.charHeightScore * 100).toInt()}%) $charIdeal\n")
            append("     目標10-20px (85%以上)\n")

            // コントラスト（理想: 50%以上）
            val contrastIdeal = if (quality.contrastScore >= 0.50) "✓" else "✗"
            append("コントラスト: ${(quality.contrastScore * 100).toInt()}% $contrastIdeal\n")
            append("     目標50%以上\n")

            // フォーカス（理想: 70%以上）
            val focusIdeal = if (quality.focusScore >= 0.70) "✓" else "✗"
            append("フォーカス: ${(quality.focusScore * 100).toInt()}% $focusIdeal\n")
            append("     目標70%以上")
        }
        onQualityInfo(qualityStatus)

        if (DEBUG_SKIP_FOCUS_CHECK) {
            Log.d("CameraScreen", "DEBUG MODE: Quality check skipped")
        } else {
            Log.d("CameraScreen", "Quality - ${quality}")
        }

        // Bitmapがまだ有効か再確認（calculateSharpness後）
        if (bitmap.isRecycled) {
            Log.e("CameraScreen", "Bitmap was recycled during sharpness calculation")
            return
        }

        // ArUco マーカー検出
        val arucoResult = ImageProcessor.detectArucoMarkers(bitmap)

        // マーカー検出状態を確認（フラグ更新は撮影後のみ）
        val markerDetected = if (DEBUG_SKIP_MARKER_CHECK) {
            Log.d("CameraScreen", "DEBUG MODE: Marker check skipped (forced true)")
            true
        } else {
            arucoResult.isValid && arucoResult.blockType != null
        }

        // マーカー情報を更新
        val markerCount = arucoResult.corners.size
        val markerIds = if (markerCount > 0) {
            try {
                (0 until arucoResult.ids.rows()).map { i ->
                    arucoResult.ids.get(i, 0)[0].toInt()
                }.joinToString(",")
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
        onMarkerInfo(markerCount, markerIds)

        // 検出状況をログ出力（デバッグ用）
        if (!markerDetected && arucoResult.corners.isNotEmpty()) {
            Log.d("CameraScreen", "Partial detection: ${arucoResult.corners.size} markers found but not enough for complete block")
        }

        // フレーム安定性チェック付き自動撮影
        // 条件: マーカー検出 + 品質OK + 未撮影
        if (markerDetected && isQualityGood && !hasDetectedMarker) {
            // 条件を満たすフレームをカウント
            val newCount = consecutiveGoodFrames + 1
            onConsecutiveGoodFramesChange(newCount)

            if (newCount >= MIN_STABLE_FOCUS_FRAMES) {
                // 安定した状態が続いたのでOCR処理開始
                Log.d("CameraScreen", "Auto-capture triggered: Stable for $newCount frames (quality=${quality.score}, marker=${arucoResult.blockType})")
                Log.d("CameraScreen", "Processing with ImageAnalysis: ${bitmap.width}x${bitmap.height}")

                // トーチを強制的に消灯（撮影開始直前）
                try {
                    camera?.cameraControl?.enableTorch(false)
                    Log.d("CameraScreen", "Torch disabled before OCR processing")
                } catch (e: Exception) {
                    Log.e("CameraScreen", "Failed to disable torch", e)
                }

                onProcessingChange(true)
                onMarkerDetected(true)  // 処理完了フラグを設定

                // ImageAnalysisのフレームを直接OCR処理
                viewModel.processImage(bitmap, arucoResult)

                onConsecutiveGoodFramesChange(0)  // カウンターリセット
            } else {
                Log.d("CameraScreen", "Quality good, waiting for stability: $newCount/$MIN_STABLE_FOCUS_FRAMES frames")
            }
        } else {
            // 条件を満たさなくなったらカウンターリセット
            if (consecutiveGoodFrames > 0) {
                Log.d("CameraScreen", "Quality dropped, resetting stability counter (was $consecutiveGoodFrames)")
                onConsecutiveGoodFramesChange(0)
            }

            // マーカーは検出されたが品質が不十分
            if (markerDetected && !hasDetectedMarker) {
                val reason = "品質不足 (score=${quality.score}, threshold=${OcrQualityEvaluator.QUALITY_THRESHOLD})"
                Log.d("CameraScreen", "Auto-capture skipped: $reason")
                Log.d("CameraScreen", "  Details: ${quality.toHumanReadable()}")
            }
        }
    } catch (e: Exception) {
        Log.e("CameraScreen", "Error processing image", e)
    }
}

// ============================================
// 定数
// ============================================

private const val DEBUG_SKIP_FOCUS_CHECK = false  // デバッグ用：trueにすると品質チェックをスキップ
private const val DEBUG_SKIP_MARKER_CHECK = false  // デバッグ用：trueにするとArUcoマーカー検出をスキップ
private const val MIN_DETECTION_INTERVAL_MS = 500L  // 連続検出の最小間隔（ミリ秒）
private const val MIN_STABLE_FOCUS_FRAMES = 1  // 品質が安定するまでのフレーム数（テスト用に1に設定）

// 注: calculateSharpness()、calculateBrightness()、FOCUS_THRESHOLD等は
// OcrQualityEvaluatorに移行しました

/**
 * ImageProxyからBitmapに変換
 * JPEG圧縮を使わず、直接YUV→RGB変換を行うことでArUcoマーカーの劣化を防ぐ
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        // YuvToRgbConverterを使用して高品質な変換を実現
        // JPEG圧縮による劣化を完全に回避
        val bitmap = YuvToRgbConverter.imageProxyToBitmapDirect(imageProxy)

        // Bitmapが正しく生成されたか確認
        if (!bitmap.isRecycled) {
            Log.d("CameraScreen", "Bitmap created (direct YUV→RGB): ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")
            bitmap
        } else {
            Log.e("CameraScreen", "Failed to create bitmap or bitmap is recycled")
            null
        }
    } catch (e: Exception) {
        Log.e("CameraScreen", "Error converting ImageProxy to Bitmap", e)
        null
    }
}

// ============================================
// プレビュー関数
// ============================================

/**
 * OCR結果表示のプレビュー
 */
@ComposePreview(showBackground = true, name = "OCR結果表示")
@Composable
fun PreviewOCRResultsList() {
    MaterialTheme {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // サンプルデータ
            val sampleResults = listOf(
                OCRProcessor.BBlockRow(
                    rowIndex = 0,
                    date = "06/15",
                    productName = "野菜種子一般小袋",
                    isDateValid = true
                ),
                OCRProcessor.BBlockRow(
                    rowIndex = 1,
                    date = "06/20",
                    productName = "花種子一般小袋",
                    isDateValid = true
                ),
                OCRProcessor.BBlockRow(
                    rowIndex = 2,
                    date = "13/45",  // 不正な日付
                    productName = "園芸資材",
                    isDateValid = false
                ),
                OCRProcessor.BBlockRow(
                    rowIndex = 3,
                    date = "06/22",
                    productName = "培養土",
                    isDateValid = true
                )
            )

            // 不正な日付の数をカウント
            val invalidDateCount = sampleResults.count { !it.isDateValid }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "OCR結果",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // 警告メッセージ（不正な日付がある場合）
                        if (invalidDateCount > 0) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⚠",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Column {
                                        Text(
                                            text = "${invalidDateCount}件の不正な日付を検出",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = "赤字の日付を確認してください",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            items(sampleResults) { row ->
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 警告アイコン（不正な日付の場合）
                        if (!row.isDateValid) {
                            Text(
                                text = "⚠",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.width(20.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.width(20.dp))
                        }

                        Text(
                            text = "行${row.rowIndex + 1}:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(50.dp)
                        )
                        Text(
                            text = "日付: ${row.date}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (!row.isDateValid) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.width(100.dp)
                        )
                        Text(
                            text = "商品: ${row.productName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
