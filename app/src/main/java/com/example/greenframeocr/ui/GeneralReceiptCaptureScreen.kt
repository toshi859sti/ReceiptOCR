package com.example.greenframeocr.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.util.ImagePreprocessor
import com.example.greenframeocr.util.OcrQualityEvaluator
import com.example.greenframeocr.util.YuvToRgbConverter
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralReceiptCaptureScreen(
    viewModel: GeneralReceiptViewModel,
    onNavigateToConfirm: () -> Unit,
    onNavigateToList: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsState()
    val pendingReceipt by viewModel.pendingReceipt.collectAsState()
    val receipts by viewModel.receipts.collectAsState()
    // 撮影件数は領収書の購入日（date）ではなく、撮影・保存日時（createdAt）で判定する
    // （過去の日付の領収書を今日撮影するケースが多く、購入日で判定すると常に0件になるため）
    val todayLabel = remember {
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.JAPAN).format(java.util.Date())
    }
    val todayCount = remember(receipts, todayLabel) {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.JAPAN)
        receipts.count { fmt.format(java.util.Date(it.createdAt)) == todayLabel }
    }

    var isCapturing by remember { mutableStateOf(false) }
    var ocrStarted by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraRef by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var sharpness by remember { mutableStateOf(0.0) }

    val appPreferences = remember { AppPreferences(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val lastAnalysisTime = remember { AtomicLong(0L) }
    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    // 画面起動時に前回の残留データをクリア
    LaunchedEffect(Unit) {
        viewModel.clearPending()
    }

    // エラー → Snackbar
    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is GeneralReceiptViewModel.UiState.Error -> {
                snackbarMessage = s.message
                isCapturing = false
                ocrStarted = false
            }
            else -> {}
        }
    }

    // 処理完了 → 確認画面へ遷移
    LaunchedEffect(uiState, pendingReceipt) {
        if (ocrStarted &&
            uiState is GeneralReceiptViewModel.UiState.Idle &&
            pendingReceipt != null
        ) {
            onNavigateToConfirm()
        }
    }

    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        val cameraProvider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        imageCaptureRef = imageCapture

        // 鮮鋭度をリアルタイム表示するための解析用ユースケース（自動撮影はしない、表示のみ）
        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastAnalysisTime.get() >= 200L) {
                        lastAnalysisTime.set(now)
                        try {
                            val bitmap = YuvToRgbConverter.imageProxyToBitmapDirect(imageProxy)
                            if (!bitmap.isRecycled) {
                                val scale = 960f / maxOf(bitmap.width, bitmap.height)
                                val w = (bitmap.width * scale).toInt()
                                val h = (bitmap.height * scale).toInt()
                                val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
                                val gray = ImagePreprocessor.toGray(small)
                                sharpness = OcrQualityEvaluator.calculateSharpness(gray)
                                gray.recycle()
                                small.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e("GeneralReceiptCapture", "Sharpness calc failed: ${e.message}")
                        }
                    }
                    imageProxy.close()
                }
            }

        try {
            cameraProvider.unbindAll()
            cameraRef = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                imageAnalyzer
            )
            // JA伝票のCameraScreenと同じ「フラッシュ」設定を共有し、ONなら起動時に自動点灯
            if (appPreferences.cameraFlash) {
                cameraRef?.cameraControl?.enableTorch(true)
                isTorchOn = true
            }
        } catch (e: Exception) {
            Log.e("GeneralReceiptCapture", "Camera bind failed: ${e.message}")
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("レシート撮影（本日 ${todayCount}件）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToList) {
                        Icon(Icons.Default.FormatListBulleted, contentDescription = "レシート一覧")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // 鮮鋭度リアルタイム表示（閾値以上で緑、未満で黄。自動撮影はしない）
            Text(
                text = "${"%.0f".format(sharpness)}",
                fontSize = 40.sp,
                color = if (sharpness >= appPreferences.minSharpness.toDouble()) Color.Green else Color.Yellow,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )

            // ローディングオーバーレイ
            val isProcessing = uiState is GeneralReceiptViewModel.UiState.GeminiRunning ||
                    isCapturing
            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when (uiState) {
                                is GeneralReceiptViewModel.UiState.GeminiRunning -> "AIで解析中..."
                                else -> "処理中..."
                            },
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // 照明ボタン（左下）
            FloatingActionButton(
                onClick = {
                    cameraRef?.let { cam ->
                        isTorchOn = !isTorchOn
                        cam.cameraControl.enableTorch(isTorchOn)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 32.dp),
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

            // 下部コントロール（モード切替 + シャッター）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // シャッターボタン
                FloatingActionButton(
                    onClick = {
                        if (isProcessing) return@FloatingActionButton
                        val capture = imageCaptureRef ?: return@FloatingActionButton
                        isCapturing = true
                        ocrStarted = true

                        capture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap: Bitmap = image.toBitmap()
                                    image.close()
                                    cameraRef?.cameraControl?.enableTorch(false)
                                    isTorchOn = false
                                    isCapturing = false
                                    viewModel.onImageCaptured(bitmap)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("GeneralReceiptCapture", "Capture failed: ${exception.message}")
                                    isCapturing = false
                                    ocrStarted = false
                                    snackbarMessage = "撮影に失敗しました"
                                }
                            }
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "撮影",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

