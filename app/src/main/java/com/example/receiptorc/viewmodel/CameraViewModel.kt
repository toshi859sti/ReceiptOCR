package com.example.receiptorc.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.receiptorc.util.ImageProcessor
import com.example.receiptorc.util.OCRProcessor
import com.example.receiptorc.util.UnderlyingBaseProcessor
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

    // 台紙タイプ（上に乗せる/下に敷く）
    private val _baseType = MutableStateFlow(BaseType.OVERLAY)
    val baseType: StateFlow<BaseType> = _baseType.asStateFlow()

    /**
     * 台紙タイプ
     */
    enum class BaseType {
        OVERLAY,   // 上に乗せるタイプ（既存）
        UNDERLAY   // 下に敷くタイプ（新規）
    }

    sealed class CameraUiState {
        object Preview : CameraUiState()
        object Processing : CameraUiState()
        data class Success(
            val originalBitmap: Bitmap?,
            val transformedBitmap: Bitmap?,
            val blockBitmap: Bitmap?,
            val ocrResults: List<Any>  // BBlockRow, CBlockRow, または ReceiptRow
        ) : CameraUiState()
        data class SuccessUnderlay(
            val originalBitmap: Bitmap?,
            val transformedBitmap: Bitmap?,
            val rows: List<UnderlyingBaseProcessor.ReceiptRow>,
            val subtotals: List<UnderlyingBaseProcessor.SubtotalData>
        ) : CameraUiState()
        data class Error(val message: String) : CameraUiState()
    }

    fun setUseUpscaling(enabled: Boolean) {
        _useUpscaling.value = enabled
        Log.d(TAG, "Upscaling mode: ${if (enabled) "4x upscaling" else "1x (no scaling)"}")
    }

    fun setBaseType(type: BaseType) {
        _baseType.value = type
        Log.d(TAG, "Base type changed to: $type")
    }

    fun processImage(bitmap: Bitmap, arucoResult: ImageProcessor.ArucoDetectionResult) {
        viewModelScope.launch {
            try {
                _uiState.value = CameraUiState.Processing

                val result = withContext(Dispatchers.Default) {
                    when (_baseType.value) {
                        BaseType.OVERLAY -> processOverlayType(bitmap, arucoResult)
                        BaseType.UNDERLAY -> processUnderlayType(bitmap, arucoResult)
                    }
                }

                _uiState.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                _uiState.value = CameraUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 上に乗せるタイプの処理（既存）
     */
    private suspend fun processOverlayType(
        bitmap: Bitmap,
        arucoResult: ImageProcessor.ArucoDetectionResult
    ): CameraUiState {
        val upscaling = _useUpscaling.value
        val scalingMode = if (upscaling) "4x upscaling" else "1x (no scaling)"
        Log.d(TAG, "[OVERLAY] Processing: ${bitmap.width}x${bitmap.height} with $scalingMode")

        // 透視変換
        val transformedBitmap = ImageProcessor.perspectiveTransform(
            bitmap,
            arucoResult.corners,
            arucoResult.ids,
            arucoResult.blockType!!
        ) ?: throw IllegalStateException("Perspective transform failed")

        Log.d(TAG, "[OVERLAY] Transformed image size: ${transformedBitmap.width}x${transformedBitmap.height}")

        // ブロック全体をOCR
        Log.d(TAG, "[OVERLAY] Starting OCR for ${arucoResult.blockType}...")

        val ocrResults: List<Any> = when (arucoResult.blockType) {
            ImageProcessor.BlockType.B_BLOCK -> {
                OCRProcessor.recognizeWholeBlock(
                    transformedBitmap,
                    arucoResult.blockType,
                    upscaling
                )
            }
            ImageProcessor.BlockType.C_BLOCK -> {
                OCRProcessor.recognizeWholeCBlock(
                    transformedBitmap,
                    arucoResult.blockType,
                    upscaling
                )
            }
        }

        Log.d(TAG, "[OVERLAY] OCR completed: ${ocrResults.size} rows")

        return CameraUiState.Success(
            originalBitmap = bitmap,
            transformedBitmap = transformedBitmap,
            blockBitmap = transformedBitmap,
            ocrResults = ocrResults
        )
    }

    /**
     * 下に敷くタイプの処理（新規）
     */
    private suspend fun processUnderlayType(
        bitmap: Bitmap,
        arucoResult: ImageProcessor.ArucoDetectionResult
    ): CameraUiState {
        Log.d(TAG, "[UNDERLAY] Processing: ${bitmap.width}x${bitmap.height}")

        // 透視変換（伝票全体）
        val transformedBitmap = ImageProcessor.perspectiveTransform(
            bitmap,
            arucoResult.corners,
            arucoResult.ids,
            arucoResult.blockType!!
        ) ?: throw IllegalStateException("Perspective transform failed")

        Log.d(TAG, "[UNDERLAY] Transformed image size: ${transformedBitmap.width}x${transformedBitmap.height}")

        // 下に敷くタイプのOCR処理
        Log.d(TAG, "[UNDERLAY] Starting OCR with row clustering...")
        val result = OCRProcessor.processUnderlayingBase(transformedBitmap)

        Log.d(TAG, "[UNDERLAY] OCR completed: ${result.rows.size} rows, ${result.subtotals.size} subtotals")

        return CameraUiState.SuccessUnderlay(
            originalBitmap = bitmap,
            transformedBitmap = transformedBitmap,
            rows = result.rows,
            subtotals = result.subtotals
        )
    }

    fun resetToPreview() {
        _uiState.value = CameraUiState.Preview
    }

    companion object {
        private const val TAG = "CameraViewModel"
    }
}
