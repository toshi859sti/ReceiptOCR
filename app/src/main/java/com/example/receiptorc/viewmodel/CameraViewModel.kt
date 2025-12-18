package com.example.receiptorc.viewmodel

import android.app.Application
import android.graphics.Bitmap
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

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Preview)
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _useUpscaling = MutableStateFlow(false)
    val useUpscaling: StateFlow<Boolean> = _useUpscaling.asStateFlow()

    sealed class CameraUiState {
        object Preview : CameraUiState()
        object Processing : CameraUiState()
        data class Success(
            val originalBitmap: Bitmap?,
            val transformedBitmap: Bitmap?,
            val blockBitmap: Bitmap?,
            val ocrResults: List<Any>  // BBlockRowまたはCBlockRow
        ) : CameraUiState()
        data class Error(val message: String) : CameraUiState()
    }

    fun setUseUpscaling(enabled: Boolean) {
        _useUpscaling.value = enabled
        Log.d(TAG, "Upscaling mode: ${if (enabled) "4x upscaling" else "1x (no scaling)"}")
    }

    fun processImage(bitmap: Bitmap, arucoResult: ImageProcessor.ArucoDetectionResult) {
        viewModelScope.launch {
            try {
                _uiState.value = CameraUiState.Processing

                val result = withContext(Dispatchers.Default) {
                    val upscaling = _useUpscaling.value
                    val scalingMode = if (upscaling) "4x upscaling" else "1x (no scaling)"
                    Log.d(TAG, "Processing captured image: ${bitmap.width}x${bitmap.height} with $scalingMode")

                    // 透視変換
                    val transformedBitmap = ImageProcessor.perspectiveTransform(
                        bitmap,
                        arucoResult.corners,
                        arucoResult.ids,
                        arucoResult.blockType!!
                    ) ?: throw IllegalStateException("Perspective transform failed")

                    Log.d(TAG, "Transformed image size: ${transformedBitmap.width}x${transformedBitmap.height}")

                    // ブロック全体をOCR
                    Log.d(TAG, "Starting whole block OCR for ${arucoResult.blockType}...")

                    val ocrResults: List<Any> = when (arucoResult.blockType) {
                        ImageProcessor.BlockType.B_BLOCK -> {
                            // Bブロック：日付 + 商品名
                            OCRProcessor.recognizeWholeBlock(
                                transformedBitmap,
                                arucoResult.blockType,
                                upscaling
                            )
                        }
                        ImageProcessor.BlockType.C_BLOCK -> {
                            // Cブロック：税込金額
                            OCRProcessor.recognizeWholeCBlock(
                                transformedBitmap,
                                arucoResult.blockType,
                                upscaling
                            )
                        }
                    }

                    Log.d(TAG, "Whole block OCR completed: ${ocrResults.size} rows")

                    CameraUiState.Success(
                        originalBitmap = bitmap,
                        transformedBitmap = transformedBitmap,
                        blockBitmap = transformedBitmap,  // ブロック全体をそのまま表示
                        ocrResults = ocrResults
                    )
                }

                _uiState.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                _uiState.value = CameraUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun resetToPreview() {
        _uiState.value = CameraUiState.Preview
    }

    companion object {
        private const val TAG = "CameraViewModel"
    }
}
