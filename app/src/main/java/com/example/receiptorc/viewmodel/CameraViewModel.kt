package com.example.receiptorc.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.receiptorc.data.ReceiptDatabase
import com.example.receiptorc.util.ImageProcessor
import com.example.receiptorc.util.OCRProcessor
import com.example.receiptorc.util.ProductNameCorrector
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
    private val _baseType = MutableStateFlow(BaseType.UNDERLAY)
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

        // 透視変換（伝票全体、固定2400×1700出力）
        val transformedBitmap = ImageProcessor.perspectiveTransform(
            bitmap,
            arucoResult.corners,
            arucoResult.ids,
            arucoResult.blockType!!,
            useFixedOutput = true  // UNDERLAY台紙用: A4全体を固定サイズで出力
        ) ?: throw IllegalStateException("Perspective transform failed")

        Log.d(TAG, "[UNDERLAY] Transformed image size: ${transformedBitmap.width}x${transformedBitmap.height}")

        // 画像前処理を適用（シャープ化、コントラスト強化）
        val enhancedBitmap = ImageProcessor.enhanceImageForOCR(transformedBitmap)
        Log.d(TAG, "[UNDERLAY] Applied image enhancement (sharpening + CLAHE)")

        // 固定2400×1700出力では mm->px比率は8.1で確定（再計算不要）
        val mmToPixelRatio = ImageProcessor.getMmToPixelRatio()
        Log.d(TAG, "[UNDERLAY] Using fixed mm->px ratio: $mmToPixelRatio (8.1px/mm)")

        // 下に敷くタイプのOCR処理
        Log.d(TAG, "[UNDERLAY] Starting OCR with row clustering...")
        val result = OCRProcessor.processUnderlayingBase(enhancedBitmap)

        Log.d(TAG, "[UNDERLAY] OCR completed: ${result.rows.size} rows, ${result.subtotals.size} subtotals")

        // カテゴリ判定（小計行から逆算）
        val rowsWithCategories = UnderlyingBaseProcessor.assignCategories(result.rows)
        Log.d(TAG, "[UNDERLAY] Categories assigned to ${rowsWithCategories.size} rows")

        // 辞書ベース商品名補正
        val database = ReceiptDatabase.getDatabase(getApplication())
        val productDao = database.productMasterDao()
        val variantDao = database.ocrVariantDao()

        val correctedRows = rowsWithCategories.map { (row, category) ->
            if (row.rowType == UnderlyingBaseProcessor.RowType.NORMAL && row.itemName != null) {
                // 通常行の商品名を補正
                val correctionResult = ProductNameCorrector.correctProductName(
                    ocrName = row.itemName,
                    category = category,
                    productDao = productDao,
                    variantDao = variantDao
                )

                if (correctionResult.matched) {
                    Log.d(TAG, "[CORRECTION] ${row.itemName} -> ${correctionResult.correctedName} (${correctionResult.similarity})")
                    row.copy(itemName = correctionResult.correctedName)
                } else {
                    row
                }
            } else {
                row
            }
        }

        Log.d(TAG, "[UNDERLAY] Product name correction completed")

        return CameraUiState.SuccessUnderlay(
            originalBitmap = bitmap,
            transformedBitmap = transformedBitmap,
            rows = correctedRows,
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
