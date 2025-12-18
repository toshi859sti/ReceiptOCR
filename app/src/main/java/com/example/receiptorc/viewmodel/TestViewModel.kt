package com.example.receiptorc.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.receiptorc.util.ImageProcessor
import com.example.receiptorc.util.OCRProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * テスト用ViewModel
 */
class TestViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<TestUiState>(TestUiState.Idle)
    val uiState: StateFlow<TestUiState> = _uiState.asStateFlow()

    /**
     * UIステート
     */
    sealed class TestUiState {
        object Idle : TestUiState()
        object Loading : TestUiState()
        data class Success(
            val originalBitmap: Bitmap?,
            val transformedBitmap: Bitmap?,
            val blockBitmap: Bitmap?,
            val ocrResults: List<OCRProcessor.BBlockRow>
        ) : TestUiState()
        data class Error(val message: String) : TestUiState()
    }

    /**
     * テスト画像を処理
     * @param imagePath 画像ファイルパス
     * @param testMode trueの場合、Arucoマーカーなしでテスト（開発用）
     */
    fun processTestImage(imagePath: String, testMode: Boolean = true) {
        viewModelScope.launch {
            try {
                _uiState.value = TestUiState.Loading

                val result = withContext(Dispatchers.Default) {
                    // 画像を読み込み
                    val file = File(imagePath)
                    if (!file.exists()) {
                        throw IllegalArgumentException("Image file not found: $imagePath")
                    }

                    val originalBitmap = BitmapFactory.decodeFile(imagePath)
                        ?: throw IllegalArgumentException("Failed to decode image")

                    Log.d(TAG, "Original image size: ${originalBitmap.width}x${originalBitmap.height}")
                    Log.d(TAG, "Test mode: $testMode")

                    if (testMode) {
                        // テストモード：Arucoマーカーなしで画像全体を表示
                        Log.d(TAG, "Running in test mode - skipping Aruco detection")
                        TestUiState.Success(
                            originalBitmap = originalBitmap,
                            transformedBitmap = null,
                            blockBitmap = null,
                            ocrResults = emptyList()
                        )
                    } else {
                        // 通常モード：Arucoマーカーを検出して処理
                        // Arucoマーカーを検出
                        val arucoResult = ImageProcessor.detectArucoMarkers(originalBitmap)
                        Log.d(TAG, "Aruco detection: isValid=${arucoResult.isValid}, blockType=${arucoResult.blockType}")

                        if (!arucoResult.isValid || arucoResult.blockType == null) {
                            throw IllegalStateException("Aruco markers not detected or invalid block type")
                        }

                        // 透視変換
                        val transformedBitmap = ImageProcessor.perspectiveTransform(
                            originalBitmap,
                            arucoResult.corners,
                            arucoResult.ids,
                            arucoResult.blockType
                        ) ?: throw IllegalStateException("Perspective transform failed")

                        Log.d(TAG, "Transformed image size: ${transformedBitmap.width}x${transformedBitmap.height}")

                        // ブロックを切り出し
                        val blockResult = ImageProcessor.extractBlock(
                            transformedBitmap,
                            arucoResult.blockType
                        ) ?: throw IllegalStateException("Block extraction failed")

                        Log.d(TAG, "Block extracted: ${blockResult.cellImages.size} rows")

                        // OCR実行（Bブロックのみ）
                        val ocrResults = if (arucoResult.blockType == ImageProcessor.BlockType.B_BLOCK) {
                            val ocrResult = OCRProcessor.recognizeBlock(
                                blockResult.cellImages,
                                arucoResult.blockType
                            )
                            OCRProcessor.parseBBlockResult(ocrResult)
                        } else {
                            emptyList()
                        }

                        Log.d(TAG, "OCR completed: ${ocrResults.size} rows")

                        TestUiState.Success(
                            originalBitmap = originalBitmap,
                            transformedBitmap = transformedBitmap,
                            blockBitmap = blockResult.blockImage,
                            ocrResults = ocrResults
                        )
                    }
                }

                _uiState.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Error processing test image", e)
                _uiState.value = TestUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    companion object {
        private const val TAG = "TestViewModel"
    }
}
