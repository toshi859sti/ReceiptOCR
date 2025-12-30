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

    // UIStateがPreviewに戻った時に状態をリセット
    LaunchedEffect(uiState) {
        if (uiState is CameraViewModel.CameraUiState.Preview) {
            hasDetectedMarker = false
            isProcessing = false
            detectedMarkerCount = 0
            detectedMarkerIds = ""
            consecutiveGoodFrames = 0  // 安定性カウンターもリセット
            // フラッシュの状態を再適用
            camera?.cameraControl?.enableTorch(isTorchOn)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
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

                                val imageAnalyzer = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setTargetResolution(android.util.Size(1920, 1080))  // Full HD解像度
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
                                                    viewModel,
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

                    // 品質情報表示
                    if (qualityInfo.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = qualityInfo,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                // 総合ステータス
                                val allGood = isFocused && isBrightnessGood && hasDetectedMarker
                                Text(
                                    text = if (allGood) "✓ 撮影準備完了" else "カメラを調整してください",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (allGood) Color.Green else Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
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
                ResultContent(
                    originalBitmap = state.originalBitmap,
                    transformedBitmap = state.transformedBitmap,
                    blockBitmap = state.blockBitmap,
                    ocrResults = state.ocrResults,
                    showPreview = showPreview,
                    onRetry = {
                        viewModel.setUseUpscaling(false)
                        viewModel.resetToPreview()
                    },
                    onRetryWithUpscaling = {
                        viewModel.setUseUpscaling(true)
                        viewModel.resetToPreview()
                    },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is CameraViewModel.CameraUiState.SuccessUnderlay -> {
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
    viewModel: CameraViewModel,
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

        // 画像のシャープネスを計算してフォーカス判定
        val sharpness = calculateSharpness(bitmap)
        val isFocusGood = if (DEBUG_SKIP_FOCUS_CHECK) {
            true  // デバッグモード：常にフォーカスOK
        } else {
            sharpness > FOCUS_THRESHOLD
        }

        // 画像の明るさを計算
        val brightness = calculateBrightness(bitmap)
        val isBrightnessGood = brightness in BRIGHTNESS_MIN..BRIGHTNESS_MAX

        // フォーカスと明るさの状態を更新
        onFocusChange(isFocusGood)
        onBrightnessChange(isBrightnessGood)

        // 品質情報を更新
        val qualityStatus = buildString {
            append("鮮明度: ${sharpness.toInt()} ")
            append(if (isFocusGood) "✓" else "✗")
            append(" | 明るさ: ${brightness.toInt()} ")
            append(if (isBrightnessGood) "✓" else if (brightness < BRIGHTNESS_MIN) "暗い" else "明るい")
        }
        onQualityInfo(qualityStatus)

        if (DEBUG_SKIP_FOCUS_CHECK) {
            Log.d("CameraScreen", "DEBUG MODE: Focus check skipped (sharpness: $sharpness, brightness: $brightness)")
        } else {
            Log.d("CameraScreen", "Quality - Sharpness: $sharpness (${if (isFocusGood) "OK" else "NG"}), Brightness: $brightness (${if (isBrightnessGood) "OK" else "NG"})")
        }

        // Bitmapがまだ有効か再確認（calculateSharpness後）
        if (bitmap.isRecycled) {
            Log.e("CameraScreen", "Bitmap was recycled during sharpness calculation")
            return
        }

        // ArUco マーカー検出
        val arucoResult = ImageProcessor.detectArucoMarkers(bitmap)

        // マーカー検出状態を確認（フラグ更新は撮影後のみ）
        val markerDetected = arucoResult.isValid && arucoResult.blockType != null

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
        // 条件: マーカー検出 + フォーカスOK + 明るさOK + 未撮影
        if (markerDetected && isFocusGood && isBrightnessGood && !hasDetectedMarker) {
            // 条件を満たすフレームをカウント
            val newCount = consecutiveGoodFrames + 1
            onConsecutiveGoodFramesChange(newCount)

            if (newCount >= MIN_STABLE_FOCUS_FRAMES) {
                // 安定した状態が続いたので撮影
                Log.d("CameraScreen", "Auto-capture triggered: Stable for $newCount frames (sharpness=$sharpness, brightness=$brightness, marker=${arucoResult.blockType})")
                onProcessingChange(true)
                onMarkerDetected(true)  // 撮影完了フラグを設定
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
                val reason = when {
                    !isFocusGood -> "フォーカス不足 (sharpness=$sharpness < $FOCUS_THRESHOLD)"
                    !isBrightnessGood -> "明るさ不適切 (brightness=$brightness, range=$BRIGHTNESS_MIN..$BRIGHTNESS_MAX)"
                    else -> "不明"
                }
                Log.d("CameraScreen", "Auto-capture skipped: $reason")
            }
        }
    } catch (e: Exception) {
        Log.e("CameraScreen", "Error processing image", e)
    }
}

/**
 * 画像のシャープネスを計算（Laplacian分散を使用）
 * 値が大きいほど画像がシャープ（フォーカスが合っている）
 */
private fun calculateSharpness(bitmap: Bitmap): Double {
    // 計算効率のため、画像を縮小
    // filter=trueにして、必ず新しいBitmapを作成
    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 640, 480, true)

    // 元のBitmapと同じ参照でないことを確認
    val shouldRecycle = scaledBitmap != bitmap

    val width = scaledBitmap.width
    val height = scaledBitmap.height
    val pixels = IntArray(width * height)
    scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    // グレースケール変換とLaplacianフィルタの適用
    var sumLaplacian = 0.0
    var count = 0

    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val idx = y * width + x

            // グレースケール値を取得
            val gray = getGray(pixels[idx])
            val grayTop = getGray(pixels[(y - 1) * width + x])
            val grayBottom = getGray(pixels[(y + 1) * width + x])
            val grayLeft = getGray(pixels[y * width + (x - 1)])
            val grayRight = getGray(pixels[y * width + (x + 1)])

            // Laplacian オペレーター
            val laplacian = Math.abs(
                4 * gray - grayTop - grayBottom - grayLeft - grayRight
            )

            sumLaplacian += laplacian * laplacian
            count++
        }
    }

    // 元のBitmapと異なる場合のみリサイクル
    if (shouldRecycle) {
        scaledBitmap.recycle()
    }

    return sumLaplacian / count
}

private fun getGray(pixel: Int): Int {
    val r = (pixel shr 16) and 0xFF
    val g = (pixel shr 8) and 0xFF
    val b = pixel and 0xFF
    return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
}

/**
 * 画像の明るさを計算
 * 0-255の範囲で、値が大きいほど明るい
 */
private fun calculateBrightness(bitmap: Bitmap): Double {
    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 640, 480, true)
    val shouldRecycle = scaledBitmap != bitmap

    val width = scaledBitmap.width
    val height = scaledBitmap.height
    val pixels = IntArray(width * height)
    scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var sumBrightness = 0.0
    for (pixel in pixels) {
        sumBrightness += getGray(pixel)
    }

    if (shouldRecycle) {
        scaledBitmap.recycle()
    }

    return sumBrightness / pixels.size
}

private const val FOCUS_THRESHOLD = 250.0  // フォーカス閾値（150→200→250に引き上げ、画像前処理と併用）
private const val BRIGHTNESS_MIN = 40.0    // 最小明るさ（これより暗いと警告）
private const val BRIGHTNESS_MAX = 220.0   // 最大明るさ（これより明るいと警告）
private const val DEBUG_SKIP_FOCUS_CHECK = false  // デバッグ用：trueにするとフォーカスチェックをスキップ
private const val MIN_DETECTION_INTERVAL_MS = 500L  // 連続検出の最小間隔（ミリ秒）
private const val MIN_STABLE_FOCUS_FRAMES = 3  // フォーカスが安定するまでのフレーム数

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

/**
 * ブロック画像の上にOCR結果をオーバーレイ表示（テスト用）
 */
@Composable
private fun BlockImageWithTextOverlay(
    blockBitmap: Bitmap,
    ocrResults: List<Any>,  // BBlockRowまたはCBlockRow
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    // Canvasを使って直接描画する方が正確
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(blockBitmap.width.toFloat() / blockBitmap.height.toFloat())
    ) {
        // ブロック画像を表示
        Image(
            bitmap = blockBitmap.asImageBitmap(),
            contentDescription = "Block with OCR overlay",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // BoxとImageのサイズが一致するので、単純なスケール計算のみ
        // aspectRatioによりBoxが画像と同じ比率になる
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val bitmapWidth = blockBitmap.width.toFloat()
            val bitmapHeight = blockBitmap.height.toFloat()

            // スケール計算（オフセットは不要）
            val scaleX = canvasWidth / bitmapWidth
            val scaleY = canvasHeight / bitmapHeight

            Log.d("BlockOverlay", "Canvas: ${canvasWidth}x$canvasHeight, Bitmap: ${bitmapWidth}x$bitmapHeight")
            Log.d("BlockOverlay", "Scale: scaleX=$scaleX, scaleY=$scaleY")

            // 各OCR結果を描画（Bブロックのみ）
            ocrResults.filterIsInstance<OCRProcessor.BBlockRow>().forEach { row ->
                // 日付のオーバーレイ（左列）- 緑
                row.dateWithBounds?.boundingBox?.let { bounds ->
                    val rect = androidx.compose.ui.geometry.Rect(
                        left = bounds.left * scaleX,
                        top = bounds.top * scaleY,
                        right = bounds.right * scaleX,
                        bottom = bounds.bottom * scaleY
                    )

                    // 緑の半透明ボックス
                    drawRect(
                        color = Color.Green.copy(alpha = 0.3f),
                        topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                        style = androidx.compose.ui.graphics.drawscope.Fill
                    )

                    // 緑の枠線
                    drawRect(
                        color = Color.Green,
                        topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                    )
                }

                // 商品名のオーバーレイ（右列）- 青
                row.productNameWithBounds?.boundingBox?.let { bounds ->
                    val rect = androidx.compose.ui.geometry.Rect(
                        left = bounds.left * scaleX,
                        top = bounds.top * scaleY,
                        right = bounds.right * scaleX,
                        bottom = bounds.bottom * scaleY
                    )

                    // 青の半透明ボックス
                    drawRect(
                        color = Color.Blue.copy(alpha = 0.3f),
                        topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                        style = androidx.compose.ui.graphics.drawscope.Fill
                    )

                    // 青の枠線
                    drawRect(
                        color = Color.Blue,
                        topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultContent(
    originalBitmap: Bitmap?,
    transformedBitmap: Bitmap?,
    blockBitmap: Bitmap?,
    ocrResults: List<Any>,  // BBlockRowまたはCBlockRow
    showPreview: Boolean,
    onRetry: () -> Unit,
    onRetryWithUpscaling: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("新しい伝票を撮影")
            }
        }

        item {
            Button(
                onClick = onRetryWithUpscaling,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("新しい伝票を拡大して撮影")
            }
        }

        // プレビュー表示がONの場合のみ画像を表示
        if (showPreview) {
            if (originalBitmap != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "撮影画像",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Image(
                                bitmap = originalBitmap.asImageBitmap(),
                                contentDescription = "Original",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }

            if (transformedBitmap != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "透視変換後",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Image(
                                bitmap = transformedBitmap.asImageBitmap(),
                                contentDescription = "Transformed",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }

            if (blockBitmap != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "切り出されたブロック",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Image(
                                bitmap = blockBitmap.asImageBitmap(),
                                contentDescription = "Block",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp),
                                contentScale = ContentScale.Fit
                            )

                            // 画像情報を表示
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = "画像情報",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                text = "サイズ: ${blockBitmap.width} × ${blockBitmap.height} px",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "列区切り位置: (計算中...)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "※詳細なデバッグ情報はlogcatで確認できます",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                // テスト用：OCR結果をオーバーレイ表示
                if (ocrResults.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "OCR結果オーバーレイ（テスト）",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Text(
                                    text = "緑=日付、青=商品名",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                BlockImageWithTextOverlay(
                                    blockBitmap = blockBitmap,
                                    ocrResults = ocrResults,
                                    modifier = Modifier.height(400.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (ocrResults.isNotEmpty()) {
            // 不正な日付の数をカウント（Bブロックのみ）
            val bBlockResults = ocrResults.filterIsInstance<OCRProcessor.BBlockRow>()
            val invalidDateCount = bBlockResults.count { !it.isDateValid }

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

            items(bBlockResults) { row ->
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
