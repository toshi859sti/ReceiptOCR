package com.example.receiptorc.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.receiptorc.data.ReceiptDatabase
import com.example.receiptorc.util.ImageProcessor
import com.example.receiptorc.util.OCRProcessor
import com.example.receiptorc.util.OcrResultEvaluator
import com.example.receiptorc.util.ProductNameCorrectorV2
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

    sealed class CameraUiState {
        object Preview : CameraUiState()
        object Processing : CameraUiState()
        data class Success(
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

    fun processImage(bitmap: Bitmap, arucoResult: ImageProcessor.ArucoDetectionResult) {
        viewModelScope.launch {
            try {
                _uiState.value = CameraUiState.Processing

                val result = withContext(Dispatchers.Default) {
                    processUnderlayType(bitmap, arucoResult)
                }

                _uiState.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                _uiState.value = CameraUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 下に敷くタイプの処理
     */
    private suspend fun processUnderlayType(
        bitmap: Bitmap,
        arucoResult: ImageProcessor.ArucoDetectionResult
    ): CameraUiState {
        Log.d(TAG, "[UNDERLAY] Processing: ${bitmap.width}x${bitmap.height}")

        // 透視変換（伝票全体、動的サイズ出力）
        val transformedBitmap = ImageProcessor.perspectiveTransform(
            bitmap,
            arucoResult.corners,
            arucoResult.ids,
            arucoResult.blockType ?: ImageProcessor.BlockType.B_BLOCK,  // Use default when bypassing marker check
            useFixedOutput = true  // UNDERLAY台紙用: A4全体を動的サイズで出力
        ) ?: throw IllegalStateException("Perspective transform failed")

        Log.d(TAG, "[UNDERLAY] Transformed image size: ${transformedBitmap.width}x${transformedBitmap.height}")

        // 画像前処理を適用（シャープ化、コントラスト強化）
        val enhancedBitmap = ImageProcessor.enhanceImageForOCR(transformedBitmap)
        Log.d(TAG, "[UNDERLAY] Applied image enhancement (sharpening + CLAHE)")

        // mm->px比率を取得（ArUcoマーカーから動的に計算済み）
        val mmToPixelRatio = ImageProcessor.getMmToPixelRatio()
        Log.d(TAG, "[UNDERLAY] Using mm->px ratio: $mmToPixelRatio px/mm (dynamically calculated)")

        // 下に敷くタイプのOCR処理
        Log.d(TAG, "[UNDERLAY] Starting OCR with row clustering...")
        val result = OCRProcessor.processUnderlayingBase(enhancedBitmap)

        Log.d(TAG, "[UNDERLAY] OCR completed: ${result.rows.size} rows, ${result.subtotals.size} subtotals")

        // カテゴリ判定（小計行から逆算）
        val rowsWithCategories = UnderlyingBaseProcessor.assignCategories(result.rows)
        Log.d(TAG, "[UNDERLAY] Categories assigned to ${rowsWithCategories.size} rows")

        // 辞書ベース商品名補正 + ダブルOCR選択
        val database = ReceiptDatabase.getDatabase(getApplication())
        val productDao = database.productMasterDao()
        val variantDao = database.ocrVariantDao()

        val correctedRows = rowsWithCategories.mapIndexed { index, (row, category) ->
            if (row.rowType == UnderlyingBaseProcessor.RowType.NORMAL && row.itemName != null) {
                // 通常行の商品名を補正（V2: スコアベース）
                val correctionResult = ProductNameCorrectorV2.correctProductName(
                    ocrName = row.itemName,
                    category = category,
                    productDao = productDao,
                    variantDao = variantDao
                )

                if (correctionResult.matched) {
                    // 辞書マッチング成功 → ダブルOCR結果があれば段階B評価で最良選択
                    val doubleOcrResult = result.productNameDoubleOcrMap[index]
                    if (doubleOcrResult != null) {
                        val grayText = doubleOcrResult.grayText
                        val binaryText = doubleOcrResult.binaryText
                        val binaryCandidateScore = doubleOcrResult.binaryCandidateScore

                        // 段階B: OCR結果の最良選択（グレーは常に主系）
                        if (grayText != null) {
                            val grayOcrResult = OcrResultEvaluator.OcrResult(
                                text = grayText,
                                confidence = null,
                                source = "gray",
                                binaryCandidateScore = 0.0  // グレーには段階Aスコアなし
                            )

                            val binaryOcrResult = if (binaryText != null) {
                                OcrResultEvaluator.OcrResult(
                                    text = binaryText,
                                    confidence = null,
                                    source = "binary",
                                    binaryCandidateScore = binaryCandidateScore
                                )
                            } else null

                            Log.d(TAG, "[OCR-SELECT] Row $index: Evaluating OCR results...")
                            val bestResult = OcrResultEvaluator.chooseBestResult(
                                grayOcrResult,
                                binaryOcrResult,
                                correctionResult.correctedName  // 辞書の正規名で評価
                            )
                            Log.d(TAG, "[OCR-SELECT] Row $index: Selected ${bestResult.source}")
                        }
                    }

                    // 最終的には辞書の正規名を使用
                    Log.d(TAG, "[CORRECTION-V2] Row $index: ${row.itemName} -> ${correctionResult.correctedName} (score=${correctionResult.score}, ${correctionResult.details})")
                    row.copy(itemName = correctionResult.correctedName)
                } else {
                    row
                }
            } else {
                row
            }
        }

        Log.d(TAG, "[UNDERLAY] Product name correction completed")

        return CameraUiState.Success(
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
