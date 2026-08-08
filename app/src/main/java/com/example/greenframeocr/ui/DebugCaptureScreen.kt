package com.example.greenframeocr.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.util.GeminiReceiptClient
import com.example.greenframeocr.util.GreenFrameDetector
import com.example.greenframeocr.util.OCRProcessor
import com.example.greenframeocr.util.UnderlyingBaseProcessor
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
    val greenOverlay by cameraViewModel.greenFrameOverlay.collectAsState()

    var ocrResults     by remember { mutableStateOf<List<String>>(emptyList()) }
    var columnBitmap   by remember { mutableStateOf<Bitmap?>(null) }
    var isOcrRunning   by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 検出成功時: 列範囲オーバーレイ生成 + 新パイプラインOCR
    LaunchedEffect(uiState) {
        if (uiState is CameraViewModel.CameraUiState.Success && ocrResults.isEmpty()) {
            val result = (uiState as CameraViewModel.CameraUiState.Success).detectionResult
            val dewarped = result.dewarpedBitmap ?: return@LaunchedEffect

            isOcrRunning = true
            scope.launch {
                withContext(Dispatchers.Default) {
                    // 列範囲オーバーレイ画像を生成
                    val mmRatio = dewarped.width / 203.0
                    UnderlyingBaseProcessor.initializeColumnRanges(mmRatio)
                    val mat = org.opencv.core.Mat()
                    org.opencv.android.Utils.bitmapToMat(dewarped, mat)
                    val bgrMat = org.opencv.core.Mat()
                    org.opencv.imgproc.Imgproc.cvtColor(mat, bgrMat, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR)
                    mat.release()
                    UnderlyingBaseProcessor.drawColumnRanges(bgrMat)
                    val rgbaMat = org.opencv.core.Mat()
                    org.opencv.imgproc.Imgproc.cvtColor(bgrMat, rgbaMat, org.opencv.imgproc.Imgproc.COLOR_BGR2RGBA)
                    bgrMat.release()
                    val overlayBmp = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888)
                    org.opencv.android.Utils.matToBitmap(rgbaMat, overlayBmp)
                    rgbaMat.release()
                    columnBitmap = overlayBmp

                    // 新パイプラインOCR
                    val ocrResult = OCRProcessor.processUnderlayingBase(dewarped, mmRatio)

                    // TextBoxの実際の分類をオーバーレイに重ねる
                    val mat2 = org.opencv.core.Mat()
                    org.opencv.android.Utils.bitmapToMat(columnBitmap!!, mat2)
                    val bgrMat2 = org.opencv.core.Mat()
                    org.opencv.imgproc.Imgproc.cvtColor(mat2, bgrMat2, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR)
                    mat2.release()
                    UnderlyingBaseProcessor.drawTextBoxes(bgrMat2, ocrResult.textBoxes)
                    val rgbaMat2 = org.opencv.core.Mat()
                    org.opencv.imgproc.Imgproc.cvtColor(bgrMat2, rgbaMat2, org.opencv.imgproc.Imgproc.COLOR_BGR2RGBA)
                    bgrMat2.release()
                    val overlayBmp2 = Bitmap.createBitmap(rgbaMat2.cols(), rgbaMat2.rows(), Bitmap.Config.ARGB_8888)
                    org.opencv.android.Utils.matToBitmap(rgbaMat2, overlayBmp2)
                    rgbaMat2.release()
                    columnBitmap = overlayBmp2

                    val texts = ocrResult.rowsWithCategories.mapIndexed { i, (row, cat) ->
                        val catSum = if (row.categorySum != null) " catSum=${row.categorySum}" else ""
                        val dateRect = row.dateBounds?.let { r ->
                            " dateRect=[${r.left},${r.top},${r.right},${r.bottom}](${r.width()}×${r.height()})"
                        } ?: ""
                        val amtRect = row.amountBounds?.let { r ->
                            " amtRect=[${r.left},${r.top},${r.right},${r.bottom}](${r.width()}×${r.height()})"
                        } ?: ""
                        "Row $i [$cat] date=${row.date} item=${row.itemName} qty=${row.quantity} amt=${row.amount}$catSum$dateRect$amtRect"
                    }
                    ocrResults = texts
                }
                isOcrRunning = false
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
                        showForceCapture = true,
                        debugMode = true
                    )
                    // フレーム検出オーバーレイ
                    FrameDetectionOverlay(
                        greenOverlay = greenOverlay,
                        modifier = Modifier.fillMaxSize()
                    )
                    // 緑枠検出状態テキスト（左上）
                    Text(
                        text = when {
                            greenOverlay == null   -> "緑枠: 待機中"
                            greenOverlay!!.success -> "緑枠: OK ✓"
                            else                   -> "緑枠: NG ✗"
                        },
                        color = when {
                            greenOverlay == null   -> Color.White
                            greenOverlay!!.success -> Color.Green
                            else                   -> Color.Red
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 8.dp, top = 72.dp)
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
                    columnBitmap = columnBitmap,
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
    columnBitmap: Bitmap?,
    isOcrRunning: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveMessage by remember { mutableStateOf("") }

    // Gemini OCR（Phase2動作確認用）
    var isGeminiRunning by remember { mutableStateOf(false) }
    var geminiResult by remember { mutableStateOf<GeminiReceiptClient.JaSheetParseResult?>(null) }
    var geminiError by remember { mutableStateOf<String?>(null) }

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
                DebugSection("③ 透視変換後（2100×1531）") {
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

        // ④ 列範囲オーバーレイ
        item {
            DebugSection("④ 列範囲オーバーレイ（赤=取引日 緑=商品名 青=金額 黄=分類計）") {
                if (isOcrRunning) {
                    Text("生成中...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    columnBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                        )
                    } ?: Text("生成失敗", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ⑤ 新パイプラインOCR結果
        item {
            DebugSection("⑤ OCR結果（新パイプライン）") {
                if (isOcrRunning) {
                    Text("OCR実行中...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (ocrResults.isEmpty()) {
                    Text("結果なし", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ocrResults.forEach { line ->
                            Text(line, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ⑥ 撮影情報
        item {
            val info = result.captureInfo
            DebugSection("⑥ 撮影情報") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("撮影時刻：${info.capturedAt}", fontSize = 13.sp)
                    Text("入力解像度（カメラ全体）：${info.inputWidth} × ${info.inputHeight} px  （${info.inputWidth * info.inputHeight / 1_000_000}MP）", fontSize = 13.sp)
                    Text("透視変換後：${info.warpWidth} × ${info.warpHeight} px", fontSize = 13.sp)
                    Text(
                        "鮮鋭度：${"%.1f".format(info.sharpness)}",
                        fontSize = 13.sp,
                        color = if (info.sharpness >= 1000) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                else androidx.compose.ui.graphics.Color(0xFFE65100)
                    )
                    if (info.cornerAngles.size == 4) {
                        val labels = listOf("TL", "TR", "BR", "BL")
                        val maxDev = info.cornerAngles.maxOf { Math.abs(it - 90.0) }
                        val angleText = labels.zip(info.cornerAngles)
                            .joinToString("  ") { (l, a) -> "$l:${"%.1f".format(a)}°" }
                        Text(
                            "コーナー角度：$angleText",
                            fontSize = 13.sp,
                            color = if (maxDev <= 1.5) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                    else androidx.compose.ui.graphics.Color(0xFFE65100)
                        )
                    }
                    if (info.brCorrected) {
                        Text(
                            "⚠ BR補正済み（平行四辺形則で置換）",
                            fontSize = 13.sp,
                            color = androidx.compose.ui.graphics.Color(0xFFE65100)
                        )
                    }
                }
            }
        }

        // ⑦ Gemini OCR（Phase2動作確認用・列クロップTwo-Pass方式）
        item {
            DebugSection("⑦ Gemini OCR（テスト・列クロップTwo-Pass）") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val dewarped = result.dewarpedBitmap
                            if (dewarped == null) {
                                geminiError = "透視変換画像がありません"
                                return@Button
                            }
                            val apiKey = AppPreferences(context).geminiApiKey
                            geminiError = null
                            geminiResult = null
                            isGeminiRunning = true
                            scope.launch {
                                try {
                                    val res = withContext(Dispatchers.IO) {
                                        GeminiReceiptClient.parseJaSheetFromImage(dewarped, apiKey)
                                    }
                                    geminiResult = res
                                } catch (e: Exception) {
                                    geminiError = e.message ?: e.toString()
                                } finally {
                                    isGeminiRunning = false
                                }
                            }
                        },
                        enabled = !isGeminiRunning
                    ) {
                        Text(if (isGeminiRunning) "送信中..." else "Geminiに送信")
                    }

                    geminiError?.let { err ->
                        Text(err, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }

                    geminiResult?.let { res ->
                        Text(
                            text = "行数: ${res.rows.size}  日付列アラインメント: ${if (res.dateColumnAligned) "OK" else "不一致（要フォールバック）"}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (res.dateColumnAligned) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                    else androidx.compose.ui.graphics.Color(0xFFE65100)
                        )
                        res.usageStats?.let { usage ->
                            Text(usage.toDisplayString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            res.rows.forEachIndexed { i, row ->
                                Text(
                                    "Row $i [${row.rowType}] date=${row.dateRaw} item=${row.itemName} " +
                                        "qty=${row.quantity} amt=${row.amount} catSum=${row.categorySum} " +
                                        "conf=${row.confidence}",
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
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
                        // 行画像は横長（2100×50px程度）なので横スクロールで表示
                        val rowAspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                        Row(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .height(120.dp)
                                    .aspectRatio(rowAspect),
                                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds
                            )
                        }
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
                                saveDebugImages(context, result, columnBitmap)
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
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            saveDebugText(context, result.captureInfo, ocrResults)
                        }
                        saveMessage = if (ok) "✓ テキストを保存" else "テキスト保存失敗"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Icon(Icons.Default.SaveAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("テキスト保存")
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
    result: GreenFrameDetector.DetectionResult,
    columnBitmap: Bitmap? = null
): Int {
    // 古い画像を削除してから保存
    clearOCRTestFolder(context)

    val timestamp = System.currentTimeMillis()
    val bitmaps = buildList {
        add("debug_${timestamp}_1_overlay" to result.debugBitmap)
        result.maskBitmap?.let { add("debug_${timestamp}_2_mask" to it) }
        result.dewarpedBitmap?.let { add("debug_${timestamp}_3_dewarped" to it) }
        columnBitmap?.let { add("debug_${timestamp}_4_columns" to it) }
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

private fun saveDebugText(
    context: Context,
    captureInfo: GreenFrameDetector.CaptureInfo,
    ocrResults: List<String>
): Boolean {
    return try {
        val timestamp = System.currentTimeMillis()
        val content = buildString {
            appendLine("=== JA仕訳変換 デバッグ情報 ===")
            appendLine("撮影時刻　: ${captureInfo.capturedAt}")
            appendLine("入力解像度: ${captureInfo.inputWidth} × ${captureInfo.inputHeight} px")
            appendLine("透視変換後: ${captureInfo.warpWidth} × ${captureInfo.warpHeight} px")
            appendLine("鮮鋭度　　: ${"%.1f".format(captureInfo.sharpness)}")
            appendLine()
            appendLine("=== OCR結果（${ocrResults.size}行）===")
            ocrResults.forEachIndexed { i, line -> appendLine(line) }
        }

        val fileName = "debug_${timestamp}_ocr.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$SAVE_FOLDER")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"), values
            ) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                SAVE_FOLDER
            ).also { it.mkdirs() }
            java.io.File(dir, fileName).writeText(content)
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("DebugCaptureScreen", "テキスト保存失敗", e)
        false
    }
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

// ============================================================
// フレーム検出オーバーレイ
// ============================================================

@Composable
private fun FrameDetectionOverlay(
    greenOverlay: CameraViewModel.FrameOverlayState?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        greenOverlay?.let { state ->
            if (state.corners.size == 4) {
                drawFrameOverlay(
                    corners = state.corners,
                    imageWidth = state.imageWidth,
                    imageHeight = state.imageHeight,
                    strokeColor = if (state.success) Color.Green else Color.Red,
                    dotColor = Color.Yellow,
                    strokeWidthPx = 6f,
                    dotRadiusPx = 16f
                )
            }
        }
    }
}

private fun DrawScope.drawFrameOverlay(
    corners: List<org.opencv.core.Point>,
    imageWidth: Int,
    imageHeight: Int,
    strokeColor: Color,
    dotColor: Color,
    strokeWidthPx: Float,
    dotRadiusPx: Float
) {
    // PreviewView は FILL_CENTER（デフォルト）: 長辺を合わせてクロップ
    val cameraScale = maxOf(size.width / imageWidth.toFloat(), size.height / imageHeight.toFloat())
    val offsetX = (size.width  - imageWidth  * cameraScale) / 2f
    val offsetY = (size.height - imageHeight * cameraScale) / 2f

    val screenCorners = corners.map { pt ->
        Offset(pt.x.toFloat() * cameraScale + offsetX, pt.y.toFloat() * cameraScale + offsetY)
    }

    val path = Path().apply {
        moveTo(screenCorners[0].x, screenCorners[0].y)
        screenCorners.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, strokeColor, style = Stroke(width = strokeWidthPx))

    screenCorners.forEach { pt ->
        drawCircle(dotColor, radius = dotRadiusPx, center = pt, style = Stroke(width = 3f))
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
