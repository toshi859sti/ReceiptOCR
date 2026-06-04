package com.example.greenframeocr.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class CaptureMode(val label: String) {
    ML_KIT("ML Kit OCR"),
    GEMINI_IMAGE("Gemini 画像")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralReceiptCaptureScreen(
    viewModel: GeneralReceiptViewModel,
    onNavigateToConfirm: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsState()
    val pendingReceipt by viewModel.pendingReceipt.collectAsState()

    var captureMode by remember { mutableStateOf(CaptureMode.ML_KIT) }
    var isCapturing by remember { mutableStateOf(false) }
    var ocrStarted by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

    val recognizer = remember {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    DisposableEffect(Unit) {
        onDispose { recognizer.close() }
    }

    // 画面起動時に前回の残留データをクリア
    LaunchedEffect(Unit) {
        viewModel.clearPending()
    }

    // エラー・GeminiUnavailable → Snackbar
    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is GeneralReceiptViewModel.UiState.GeminiUnavailable ->
                snackbarMessage = "APIキー未設定またはオフラインのため手動入力が必要です"
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

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
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
                title = { Text("レシート撮影") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
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

            // ローディングオーバーレイ
            val isProcessing = uiState is GeneralReceiptViewModel.UiState.OcrRunning ||
                    uiState is GeneralReceiptViewModel.UiState.GeminiRunning ||
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
                                is GeneralReceiptViewModel.UiState.OcrRunning -> "OCR処理中..."
                                else -> "処理中..."
                            },
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // 下部コントロール（モード切替 + シャッター）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // モード切替トグル
                ModeToggle(
                    selected = captureMode,
                    onSelect = { captureMode = it },
                    enabled = !isProcessing
                )

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

                                    when (captureMode) {
                                        CaptureMode.ML_KIT -> {
                                            val inputImage = InputImage.fromBitmap(bitmap, 0)
                                            recognizer.process(inputImage)
                                                .addOnSuccessListener { visionText ->
                                                    isCapturing = false
                                                    val ocrText = visionText.textBlocks
                                                        .joinToString("\n") { it.text }
                                                    viewModel.onOcrCompleted(ocrText)
                                                }
                                                .addOnFailureListener { e ->
                                                    Log.e("GeneralReceiptCapture", "OCR failed: ${e.message}")
                                                    isCapturing = false
                                                    ocrStarted = false
                                                    snackbarMessage = "OCR処理に失敗しました"
                                                }
                                        }
                                        CaptureMode.GEMINI_IMAGE -> {
                                            isCapturing = false
                                            viewModel.onImageCaptured(bitmap)
                                        }
                                    }
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

@Composable
private fun ModeToggle(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CaptureMode.entries.forEach { mode ->
            val isSelected = selected == mode
            Button(
                onClick = { onSelect(mode) },
                enabled = enabled,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else
                        Color.Transparent,
                    contentColor = Color.White,
                    disabledContainerColor = if (isSelected)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else
                        Color.Transparent,
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                elevation = null
            ) {
                Text(
                    text = mode.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
