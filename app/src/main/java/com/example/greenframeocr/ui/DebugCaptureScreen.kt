package com.example.greenframeocr.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.greenframeocr.util.GreenFrameDetector
import com.example.greenframeocr.util.OCRProcessor
import com.example.greenframeocr.viewmodel.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * デバッグ撮影画面
 * GreenFrameDetector の各中間結果（デバッグ画像・透視変換・二値化・行ビットマップ・OCRテキスト）を表示する。
 * DB保存はしない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugCaptureScreen(onBack: () -> Unit) {
    val cameraViewModel: CameraViewModel = viewModel()
    val uiState by cameraViewModel.uiState.collectAsState()

    var ocrResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var isOcrRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 検出成功時に自動でOCRを実行する
    LaunchedEffect(uiState) {
        if (uiState is CameraViewModel.CameraUiState.Success && ocrResults.isEmpty()) {
            val result = (uiState as CameraViewModel.CameraUiState.Success).detectionResult
            if (result.rowBitmaps.isNotEmpty()) {
                isOcrRunning = true
                scope.launch {
                    val texts = withContext(Dispatchers.Default) {
                        result.rowBitmaps.mapIndexed { i, bmp ->
                            val full = OCRProcessor.recognizeText(bmp)?.text?.trim()
                            "Row $i: ${full ?: "(認識なし)"}"
                        }
                    }
                    ocrResults = texts
                    isOcrRunning = false
                }
            }
        }
    }

    Scaffold { paddingValues ->
        when (val state = uiState) {
            is CameraViewModel.CameraUiState.Preview,
            is CameraViewModel.CameraUiState.Processing -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 品質チェックあり・強制撮影ボタンあり
                    CameraScreen(
                        viewModel = cameraViewModel,
                        showForceCapture = true
                    )
                    // 戻るボタンだけオーバーレイ
                    IconButton(
                        onClick = {
                            cameraViewModel.resetToPreview()
                            onBack()
                        },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "戻る",
                            tint = Color.White
                        )
                    }
                }
            }

            is CameraViewModel.CameraUiState.Success -> {
                DebugResultView(
                    result = state.detectionResult,
                    ocrResults = ocrResults,
                    isOcrRunning = isOcrRunning,
                    onRetry = {
                        ocrResults = emptyList()
                        cameraViewModel.resetToPreview()
                    },
                    onBack = {
                        cameraViewModel.resetToPreview()
                        onBack()
                    },
                    modifier = Modifier.padding(paddingValues)
                )
            }

            is CameraViewModel.CameraUiState.Error -> {
                // エラー時もデバッグ情報（元画像＋マスク）を表示
                val errResult = state.detectionResult
                LazyColumn(

                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "検出失敗: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (errResult != null) {
                        item {
                            DebugSection("元画像（撮影フレーム）＋検出コーナー") {
                                Image(
                                    bitmap = errResult.debugBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                )
                            }
                        }
                        errResult.maskBitmap?.let { mask ->
                            item {
                                DebugSection("二値化マスク（白=黒枠として検出された領域）") {
                                    Image(
                                        bitmap = mask.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                    )
                                }
                            }
                        } ?: item {
                            Text(
                                "二値化マスク: 生成前にエラー",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { cameraViewModel.resetToPreview() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("再撮影")
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 中間結果表示ビュー
// ============================================================

@Composable
private fun DebugResultView(
    result: GreenFrameDetector.DetectionResult,
    ocrResults: List<String>,
    isOcrRunning: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveMessage by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "黒枠検出 OK",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // ① 二値化マスク
        result.maskBitmap?.let { mask ->
            item {
                DebugSection("① 二値化マスク（白=黒枠として検出）") {
                    Image(
                        bitmap = mask.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                    )
                }
            }
        }

        // ② デバッグ画像（検出コーナー重ね合わせ）
        item {
            DebugSection("② デバッグ画像（コーナー検出）") {
                Image(
                    bitmap = result.debugBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                )
            }
        }

        // ③ 透視変換後
        result.dewarpedBitmap?.let { bmp ->
            item {
                DebugSection("③ 透視変換後（2100×840）") {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    )
                }
            }
        } ?: item {
            Text(
                "② 透視変換: なし（検出失敗）",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
        }

        // ④ 二値化
        result.binaryBitmap?.let { bmp ->
            item {
                DebugSection("④ 二値化（グレースケール適応的）") {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    )
                }
            }
        } ?: item {
            Text(
                "③ 二値化: なし",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
        }

        // ⑤ 検出行数サマリー
        item {
            Text(
                text = "⑤ 検出行数: ${result.rowBitmaps.size} 行",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (result.rowBitmaps.isEmpty()) {
            item {
                Text(
                    "行が検出されませんでした。二値化・閾値を確認してください。",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
        }

        // ⑤ 各行ビットマップ + OCRテキスト
        result.rowBitmaps.forEachIndexed { i, bmp ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Row $i",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 80.dp)
                                .padding(top = 4.dp)
                        )
                        when {
                            ocrResults.size > i -> Text(
                                text = ocrResults[i],
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            isOcrRunning -> Text(
                                "OCR実行中...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            if (saveMessage.isNotEmpty()) {
                Text(
                    text = saveMessage,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("再撮影")
                }
                Button(
                    onClick = {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                saveDebugImages(context, result)
                            }
                            saveMessage = if (saved > 0) "✓ ${saved}枚をギャラリーに保存" else "保存失敗"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("画像保存")
                }
            }
        }
    }
}

// ============================================================
// 画像保存ユーティリティ
// ============================================================

private const val SAVE_FOLDER = "OCRTest"

private fun saveDebugImages(
    context: Context,
    result: GreenFrameDetector.DetectionResult
): Int {
    // 古い画像を削除してから保存
    clearOCRTestFolder(context)

    val timestamp = System.currentTimeMillis()
    val bitmaps = buildList {
        add("debug_${timestamp}_1_overlay" to result.debugBitmap)
        result.maskBitmap?.let { add("debug_${timestamp}_2_mask" to it) }
        result.dewarpedBitmap?.let { add("debug_${timestamp}_3_dewarped" to it) }
        result.binaryBitmap?.let { add("debug_${timestamp}_4_binary" to it) }
        result.rowBitmaps.forEachIndexed { i, bmp ->
            add("debug_${timestamp}_5_row${i}" to bmp)
        }
    }

    var saved = 0
    for ((name, bmp) in bitmaps) {
        if (saveBitmapToGallery(context, bmp, name)) saved++
    }
    return saved
}

/** OCRTest フォルダ内の既存画像をすべて削除する */
private fun clearOCRTestFolder(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("${Environment.DIRECTORY_PICTURES}/$SAVE_FOLDER/%")
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                selection, selectionArgs
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                SAVE_FOLDER
            )
            dir.listFiles()?.forEach { it.delete() }
        }
    } catch (e: Exception) {
        android.util.Log.w("DebugCaptureScreen", "OCRTestフォルダ削除失敗", e)
    }
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, name: String): Boolean {
    return try {
        val stream: OutputStream?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SAVE_FOLDER")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            stream = context.contentResolver.openOutputStream(uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                SAVE_FOLDER
            ).also { it.mkdirs() }
            val file = java.io.File(dir, "$name.png")
            stream = java.io.FileOutputStream(file)
        }
        stream?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
    } catch (e: Exception) {
        android.util.Log.e("DebugCaptureScreen", "画像保存失敗: $name", e)
        false
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}
