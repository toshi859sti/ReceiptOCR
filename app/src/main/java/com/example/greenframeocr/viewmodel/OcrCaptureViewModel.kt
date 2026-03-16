package com.example.greenframeocr.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.greenframeocr.data.ReceiptDao
import com.example.greenframeocr.data.ReceiptItem
import com.example.greenframeocr.data.SheetData
import com.example.greenframeocr.util.Category
import com.example.greenframeocr.util.GreenFrameDetector
import com.example.greenframeocr.util.OCRProcessor
import com.example.greenframeocr.util.ValidationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OCR撮影画面の ViewModel
 * GreenFrame検出 → 行ごとROIクロップ → ML Kit OCR → DB保存 のフローを管理
 */
class OcrCaptureViewModel(
    private val dao: ReceiptDao,
    private val issueYear: Int,
    private val issueMonth: Int
) : ViewModel() {

    // -------------------------------------------------------------------
    // データクラス
    // -------------------------------------------------------------------

    /**
     * 1行分の OCR 解析結果
     */
    data class ParsedRow(
        val rowIndex: Int,
        val date: String?,        // 取引日（生テキスト）
        val productName: String?, // 商品名
        val branch: String?,      // 取扱支店
        val quantity: Int?,       // 数量
        val unitPrice: Int?,      // 税込単価
        val amount: Int?,         // 税込金額
        val isAmountValid: Boolean // 単価×数量の検算OK?
    )

    // -------------------------------------------------------------------
    // ステップ定義
    // -------------------------------------------------------------------

    sealed class CaptureStep {
        object Initial    : CaptureStep()
        object Capturing  : CaptureStep()
        object Processing : CaptureStep()
        data class Complete(val rows: List<ParsedRow>) : CaptureStep()
    }

    // -------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------

    private val _currentStep = MutableStateFlow<CaptureStep>(CaptureStep.Initial)
    val currentStep: StateFlow<CaptureStep> = _currentStep.asStateFlow()

    private val _parsedRows = MutableStateFlow<List<ParsedRow>>(emptyList())
    val parsedRows: StateFlow<List<ParsedRow>> = _parsedRows.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var sheetNumber: Int = 1

    init {
        viewModelScope.launch {
            sheetNumber = dao.getMaxSheetNumberForMonth(issueYear, issueMonth) + 1
        }
    }

    // -------------------------------------------------------------------
    // 公開 API
    // -------------------------------------------------------------------

    fun startCapture() {
        _currentStep.value = CaptureStep.Capturing
        _errorMessage.value = null
    }

    /**
     * GreenFrameDetector の結果を受け取り、行ごとに OCR 処理する
     */
    fun processDetectionResult(result: GreenFrameDetector.DetectionResult) {
        viewModelScope.launch {
            _currentStep.value = CaptureStep.Processing
            try {
                val rows = withContext(Dispatchers.Default) {
                    result.rowBitmaps.mapIndexed { index, rowBitmap ->
                        parseRowBitmap(index, rowBitmap)
                    }
                }
                Log.d(TAG, "OCR完了: ${rows.size}行")
                _parsedRows.value = rows
                _currentStep.value = CaptureStep.Complete(rows)
            } catch (e: Exception) {
                Log.e(TAG, "OCR処理エラー", e)
                _errorMessage.value = "OCR処理エラー: ${e.message}"
                _currentStep.value = CaptureStep.Initial
            }
        }
    }

    /**
     * 結果を DB に保存する
     */
    suspend fun saveData(): Boolean {
        return try {
            val rows = _parsedRows.value
            if (rows.isEmpty()) {
                _errorMessage.value = "読み取りデータがありません"
                return false
            }

            val receiptItems = mutableListOf<ReceiptItem>()
            var totalAmount = 0

            rows.forEachIndexed { index, row ->
                val (year, month, day) = parseDateText(row.date)
                val amount = row.amount ?: 0
                totalAmount += amount

                receiptItems.add(
                    ReceiptItem(
                        issueYear    = issueYear,
                        issueMonth   = issueMonth,
                        sheetNumber  = sheetNumber,
                        itemNumber   = index + 1,
                        receiptYear  = year,
                        receiptMonth = month,
                        receiptDay   = day,
                        productName  = row.productName ?: "",
                        amount       = amount,
                        category     = Category.UNCLASSIFIED,
                        isOcrOverwriteTarget = false
                    )
                )
            }

            dao.insertReceiptItems(receiptItems)
            dao.insertSheetData(
                SheetData(
                    issueYear      = issueYear,
                    issueMonth     = issueMonth,
                    sheetNumber    = sheetNumber,
                    totalFromInput = if (receiptItems.isNotEmpty()) totalAmount else null,
                    subtotalGeneral = null,
                    subtotalGas     = null,
                    subtotalAgri    = null
                )
            )
            true
        } catch (e: Exception) {
            _errorMessage.value = "保存エラー: ${e.message}"
            false
        }
    }

    fun reset() {
        _currentStep.value = CaptureStep.Initial
        _parsedRows.value = emptyList()
        _errorMessage.value = null
    }

    fun getIssueYear(): Int = issueYear
    fun getIssueMonth(): Int = issueMonth
    fun getCurrentSheetNumber(): Int = sheetNumber

    // -------------------------------------------------------------------
    // 内部処理
    // -------------------------------------------------------------------

    /**
     * 1行ビットマップを ROI ごとにクロップして ML Kit OCR にかける。
     * X比率は GreenFrameDetector.DETAIL_ROIS と同じ値を使用。
     */
    private suspend fun parseRowBitmap(index: Int, rowBitmap: Bitmap): ParsedRow {
        val w = rowBitmap.width
        val h = rowBitmap.height

        fun crop(xStart: Float, xEnd: Float): Bitmap {
            val x     = (xStart * w).toInt().coerceIn(0, w - 1)
            val width = ((xEnd - xStart) * w).toInt().coerceIn(1, w - x)
            return Bitmap.createBitmap(rowBitmap, x, 0, width, h)
        }

        // DETAIL_ROIS と同じ X 範囲
        val dateBmp   = crop(0.02f, 0.10f)  // 取引日
        val nameBmp   = crop(0.10f, 0.46f)  // 商品名
        val branchBmp = crop(0.46f, 0.55f)  // 取扱支店
        val qtyBmp    = crop(0.55f, 0.62f)  // 数量
        val priceBmp  = crop(0.62f, 0.72f)  // 税込単価
        val amountBmp = crop(0.72f, 0.83f)  // 税込金額

        // 数字フィールドは Latin モデル（数字認識精度が高い）
        // 商品名は日本語モデル
        val dateText   = OCRProcessor.recognizeTextLatin(dateBmp)?.text?.trim()
        val nameText   = OCRProcessor.recognizeText(nameBmp)?.text?.trim()
        val branchText = OCRProcessor.recognizeTextLatin(branchBmp)?.text?.trim()
        val qtyText    = OCRProcessor.recognizeTextLatin(qtyBmp)?.text?.trim()
        val priceText  = OCRProcessor.recognizeTextLatin(priceBmp)?.text?.trim()
        val amountText = OCRProcessor.recognizeTextLatin(amountBmp)?.text?.trim()

        val qty    = qtyText?.let    { ValidationUtils.sanitizeNumber(it) }
        val price  = priceText?.let  { ValidationUtils.sanitizeNumber(it) }
        val amount = amountText?.let { ValidationUtils.sanitizeNumber(it) }

        val isValid = qty != null && price != null && amount != null &&
                ValidationUtils.validateWithRounding(price, qty, amount)

        Log.d(TAG, "Row $index: date=$dateText name=$nameText qty=$qty price=$price amount=$amount valid=$isValid")

        return ParsedRow(
            rowIndex      = index,
            date          = dateText,
            productName   = nameText,
            branch        = branchText,
            quantity      = qty,
            unitPrice     = price,
            amount        = amount,
            isAmountValid = isValid
        )
    }

    /**
     * OCR取得の日付テキスト → (year, month, day) に変換。
     * 入力例: "060130"（令和6年1月30日）or "0130"（月日のみ）
     */
    private fun parseDateText(dateText: String?): Triple<Int, Int, Int> {
        val digits = dateText?.filter { it.isDigit() } ?: ""
        return when {
            digits.length >= 6 -> Triple(
                digits.take(2).toIntOrNull() ?: 0,
                digits.drop(2).take(2).toIntOrNull() ?: 1,
                digits.drop(4).take(2).toIntOrNull() ?: 1
            )
            digits.length >= 4 -> Triple(
                0,
                digits.take(2).toIntOrNull() ?: 1,
                digits.drop(2).take(2).toIntOrNull() ?: 1
            )
            else -> Triple(0, 1, 1)
        }
    }

    companion object {
        private const val TAG = "OcrCaptureViewModel"
    }
}
