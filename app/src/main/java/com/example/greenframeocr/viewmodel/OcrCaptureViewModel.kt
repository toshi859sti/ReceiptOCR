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
import com.example.greenframeocr.util.UnderlyingBaseProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OCR撮影画面の ViewModel
 *
 * 処理フロー:
 * GreenFrame検出 → 透視変換後画像(dewarpedBitmap) →
 * OCRProcessor.processUnderlayingBase()（旧ArUco方式高精度パイプライン）→
 * ParsedRow リスト → DB保存
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
        val date: String?,
        val productName: String?,
        val branch: String?,       // 旧パイプラインでは非取得 → null
        val quantity: Int?,
        val unitPrice: Int?,       // 旧パイプラインでは非取得 → null
        val amount: Int?,
        val isAmountValid: Boolean,
        val category: String = Category.UNCLASSIFIED,
        val isSubtotal: Boolean = false,
        val isMonthlyTotal: Boolean = false
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

    private val _currentStep   = MutableStateFlow<CaptureStep>(CaptureStep.Initial)
    val currentStep: StateFlow<CaptureStep> = _currentStep.asStateFlow()

    private val _parsedRows    = MutableStateFlow<List<ParsedRow>>(emptyList())
    val parsedRows: StateFlow<List<ParsedRow>> = _parsedRows.asStateFlow()

    private val _errorMessage  = MutableStateFlow<String?>(null)
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
     * GreenFrameDetector の結果を受け取り、旧ArUco方式パイプラインで OCR 処理する。
     * dewarpedBitmap（透視変換後の全体画像）を使用し、行分割は OCR 後に行う。
     */
    fun processDetectionResult(result: GreenFrameDetector.DetectionResult) {
        viewModelScope.launch {
            _currentStep.value = CaptureStep.Processing
            try {
                val dewarpedBitmap = result.dewarpedBitmap
                if (dewarpedBitmap == null) {
                    _errorMessage.value = "透視変換に失敗しました: ${result.errorMessage}"
                    _currentStep.value = CaptureStep.Initial
                    return@launch
                }

                val rows = withContext(Dispatchers.Default) {
                    // mm→px比率: 伝票幅 203mm に対するピクセル数
                    val mmToPixelRatio = dewarpedBitmap.width / 203.0
                    Log.d(TAG, "dewarpedBitmap: ${dewarpedBitmap.width}×${dewarpedBitmap.height}, mmRatio=${"%.2f".format(mmToPixelRatio)}")

                    val ocrResult = OCRProcessor.processUnderlayingBase(dewarpedBitmap, mmToPixelRatio)
                    mapToParsedRows(ocrResult)
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
            var totalAmount  = 0

            rows.forEachIndexed { index, row ->
                // 小計・月合計行は ReceiptItem として保存しない
                if (row.isSubtotal) {
                    Log.d(TAG, "Row $index スキップ（小計行）: category=${row.category}")
                    return@forEachIndexed
                }

                val (year, month, day) = parseDateText(row.date)
                // 日付が読み取れない行はスキップ
                if (year == 0 && (row.date?.filter { it.isDigit() }?.length ?: 0) < 4) {
                    Log.d(TAG, "Row $index スキップ（日付なし）: date=${row.date}")
                    return@forEachIndexed
                }

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
                        category     = row.category,
                        isOcrOverwriteTarget = false
                    )
                )
            }

            // 小計を SheetData に保存
            val subtotalGeneral = rows.firstOrNull {
                it.isSubtotal && it.category == Category.GENERAL
            }?.amount
            val subtotalGas = rows.firstOrNull {
                it.isSubtotal && it.category == Category.GAS_STATION
            }?.amount
            val subtotalAgri = rows.firstOrNull {
                it.isSubtotal && it.category == Category.AGRICULTURAL
            }?.amount

            dao.insertReceiptItems(receiptItems)
            dao.insertSheetData(
                SheetData(
                    issueYear       = issueYear,
                    issueMonth      = issueMonth,
                    sheetNumber     = sheetNumber,
                    totalFromInput  = if (receiptItems.isNotEmpty()) totalAmount else null,
                    subtotalGeneral = subtotalGeneral,
                    subtotalGas     = subtotalGas,
                    subtotalAgri    = subtotalAgri
                )
            )
            true
        } catch (e: Exception) {
            _errorMessage.value = "保存エラー: ${e.message}"
            false
        }
    }

    fun reset() {
        _currentStep.value  = CaptureStep.Initial
        _parsedRows.value   = emptyList()
        _errorMessage.value = null
    }

    fun getIssueYear(): Int  = issueYear
    fun getIssueMonth(): Int = issueMonth
    fun getCurrentSheetNumber(): Int = sheetNumber

    // -------------------------------------------------------------------
    // 内部処理
    // -------------------------------------------------------------------

    /**
     * OCRProcessor.ProcessUnderlayingBaseResult → ParsedRow リスト に変換
     */
    private fun mapToParsedRows(
        ocrResult: OCRProcessor.ProcessUnderlayingBaseResult
    ): List<ParsedRow> {
        // rowsWithCategories は (ReceiptRow, categoryString) のペアリスト
        return ocrResult.rowsWithCategories.mapIndexed { index, (row, category) ->
            val isSubtotal = row.rowType == UnderlyingBaseProcessor.RowType.SUBTOTAL
            val isMonthlyTotal = row.rowType == UnderlyingBaseProcessor.RowType.MONTHLY_TOTAL

            // 小計行・月合計行の「金額」は categorySum から取得
            val amount = if (isSubtotal || isMonthlyTotal) row.categorySum else row.amount

            ParsedRow(
                rowIndex      = index,
                date          = row.date,
                productName   = row.itemName,
                branch        = null,
                quantity      = row.quantity?.toIntOrNull(),
                unitPrice     = null,
                amount        = amount,
                isAmountValid = amount != null,
                category      = category,
                isSubtotal    = isSubtotal,
                isMonthlyTotal = isMonthlyTotal
            )
        }
    }

    /**
     * OCR取得の日付テキスト → (year, month, day) に変換
     * 入力例: "060130"（令和6年1月30日）
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
