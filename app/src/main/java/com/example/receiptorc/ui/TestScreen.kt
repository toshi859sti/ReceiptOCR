package com.example.receiptorc.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.receiptorc.util.OCRProcessor
import com.example.receiptorc.viewmodel.TestViewModel
import java.io.File

/**
 * テスト画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(
    imagePath: String,
    viewModel: TestViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var testMode by remember { mutableStateOf(true) }
    var currentImagePath by remember { mutableStateOf(imagePath) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    // カメラで撮影
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            val file = File(photoUri!!.path!!)
            if (file.exists()) {
                currentImagePath = file.absolutePath
                viewModel.processTestImage(currentImagePath, testMode)
            }
        }
    }

    // ギャラリーから選択
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                // URIからファイルパスに保存
                val inputStream = context.contentResolver.openInputStream(it)
                val file = File(context.filesDir, "selected_image.jpg")
                file.outputStream().use { output ->
                    inputStream?.copyTo(output)
                }
                currentImagePath = file.absolutePath
                viewModel.processTestImage(currentImagePath, testMode)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // カメラパーミッション
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // パーミッションが許可されたらカメラを起動
            val file = File(context.filesDir, "camera_image_${System.currentTimeMillis()}.jpg")
            photoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            cameraLauncher.launch(photoUri)
        }
    }

    LaunchedEffect(imagePath) {
        if (uiState is TestViewModel.TestUiState.Idle) {
            viewModel.processTestImage(currentImagePath, testMode = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("画像処理テスト") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // カメラ・ギャラリーボタン
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Camera",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("カメラ撮影")
                }
                Button(
                    onClick = {
                        galleryLauncher.launch("image/*")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.ImageIcon,
                        contentDescription = "Gallery",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ギャラリー")
                }
            }

            // モード切り替えボタン
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        testMode = true
                        viewModel.processTestImage(currentImagePath, testMode = true)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (testMode) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (testMode) MaterialTheme.colorScheme.onPrimary
                                      else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("テストモード")
                }
                Button(
                    onClick = {
                        testMode = false
                        viewModel.processTestImage(currentImagePath, testMode = false)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!testMode) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (!testMode) MaterialTheme.colorScheme.onPrimary
                                      else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("通常モード")
                }
            }

            // コンテンツ表示
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                when (val state = uiState) {
                    is TestViewModel.TestUiState.Idle -> {
                        // 何もしない
                    }
                    is TestViewModel.TestUiState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    is TestViewModel.TestUiState.Success -> {
                        SuccessContent(
                            originalBitmap = state.originalBitmap,
                            transformedBitmap = state.transformedBitmap,
                            blockBitmap = state.blockBitmap,
                            ocrResults = state.ocrResults
                        )
                    }
                    is TestViewModel.TestUiState.Error -> {
                        ErrorContent(message = state.message)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(
    originalBitmap: Bitmap?,
    transformedBitmap: Bitmap?,
    blockBitmap: Bitmap?,
    ocrResults: List<OCRProcessor.BBlockRow>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // テストモードの通知
        if (originalBitmap != null && transformedBitmap == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "テストモード",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = "Arucoマーカー検出をスキップして画像を表示しています。\n実際の処理にはArucoマーカーが必要です。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        // オリジナル画像
        if (originalBitmap != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "オリジナル画像",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Image(
                            bitmap = originalBitmap.asImageBitmap(),
                            contentDescription = "Original image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        // 透視変換後の画像
        if (transformedBitmap != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "透視変換後",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Image(
                            bitmap = transformedBitmap.asImageBitmap(),
                            contentDescription = "Transformed image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        // ブロック画像
        if (blockBitmap != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "切り出されたブロック",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Image(
                            bitmap = blockBitmap.asImageBitmap(),
                            contentDescription = "Block image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        // OCR結果
        if (ocrResults.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "OCR結果",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }

            items(ocrResults) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "行${row.rowIndex + 1}:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(50.dp)
                        )
                        Text(
                            text = "日付: ${row.date}",
                            style = MaterialTheme.typography.bodyMedium,
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

@Composable
private fun ErrorContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "エラーが発生しました",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
