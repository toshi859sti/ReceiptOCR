package com.example.greenframeocr.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.greenframeocr.data.ReceiptDao
import com.example.greenframeocr.data.ReceiptItem
import com.example.greenframeocr.data.SheetData
import com.example.greenframeocr.util.Category
import com.example.greenframeocr.util.GeminiReceiptClient
import com.example.greenframeocr.util.GreenFrameDetector
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
 * GeminiReceiptClient.parseJaSheetFromImage()（列クロップTwo-Pass方式）→
 * ParsedRow リスト → DB保存
 */
class OcrCaptureViewModel(
    private val dao: ReceiptDao,
    private val issueYear: Int,
    private val issueMonth: Int,
    private val geminiApiKey: String
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
        val isMonthlyTotal: Boolean = false,
        val confidence: String? = null  // Geminiの自己申告確信度（"high"/"medium"/"low"）。ML Kit経由はnull
    )

    // -------------------------------------------------------------------
    // ステップ定義
    // -------------------------------------------------------------------

    sealed class CaptureStep {
        object Initial    : CaptureStep()
        object Capturing  : CaptureStep()
        data class Preview(val detectionResult: GreenFrameDetector.DetectionResult) : CaptureStep()
        object Processing : CaptureStep()
        data class Error(val message: String, val detectionResult: GreenFrameDetector.DetectionResult) : CaptureStep()
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
     * カメラ撮影完了時の入り口。形状品質が基準を満たしていれば確認画面をスキップして
     * そのままOCR処理へ進み、満たしていなければTransformPreviewScreenで確認を挟む。
     */
    fun onDetectionResult(result: GreenFrameDetector.DetectionResult) {
        val valid = GreenFrameDetector.isValidShape(result)
        val angles = result.captureInfo.cornerAngles
        val maxDeviation = if (angles.size == 4) angles.maxOf { Math.abs(it - 90.0) } else -1.0
        Log.d(TAG, "isValidShape=$valid maxDeviation=${"%.2f".format(maxDeviation)}° angles=$angles → ${if (valid) "スキップして直接OCRへ" else "TransformPreviewScreenへ"}")
        if (valid) {
            processDetectionResult(result)
        } else {
            _currentStep.value = CaptureStep.Preview(result)
        }
    }

    /** TransformPreviewScreenで「撮り直す」を選んだ場合。伝票番号は保持したままカメラへ戻る */
    fun retryFromPreview() {
        _currentStep.value = CaptureStep.Capturing
        _errorMessage.value = null
    }

    /**
     * GreenFrameDetector の結果を受け取り、Gemini Vision API（列クロップTwo-Pass方式）で
     * OCR 処理する。dewarpedBitmap（透視変換後の全体画像）を使用する。
     */
    fun processDetectionResult(result: GreenFrameDetector.DetectionResult) {
        viewModelScope.launch {
            _currentStep.value = CaptureStep.Processing
            try {
                val dewarpedBitmap = result.dewarpedBitmap
                if (dewarpedBitmap == null) {
                    _currentStep.value = CaptureStep.Error("透視変換に失敗しました: ${result.errorMessage}", result)
                    return@launch
                }

                val rows = withContext(Dispatchers.Default) {
                    val geminiResult = GeminiReceiptClient.parseJaSheetFromImage(dewarpedBitmap, geminiApiKey)
                    if (!geminiResult.dateColumnAligned) {
                        Log.w(TAG, "取引日列のアラインメントが取れませんでした（要確認）")
                    }
                    mapGeminiResultToParsedRows(geminiResult)
                }

                Log.d(TAG, "OCR完了: ${rows.size}行")
                _parsedRows.value = rows
                _currentStep.value = CaptureStep.Complete(rows)

            } catch (e: Exception) {
                Log.e(TAG, "OCR処理エラー", e)
                _currentStep.value = CaptureStep.Error(e.message ?: "OCR処理エラー", result)
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
                        isOcrOverwriteTarget = false,
                        ocrConfidence = row.confidence
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

        /**
         * GeminiReceiptClient.JaSheetParseResult → ParsedRow リストに変換する。
         * ReceiptInputScreen.kt の CameraView からも呼ばれる共通処理。
         */
        fun mapGeminiResultToParsedRows(result: GeminiReceiptClient.JaSheetParseResult): List<ParsedRow> {
            val categorized = assignJaSheetCategories(result.rows)
            return categorized.mapIndexed { index, (row, category) ->
                val isSubtotal = row.rowType == "SUBTOTAL"
                val isMonthlyTotal = row.rowType == "MONTHLY_TOTAL"

                // 小計行・月合計行の「金額」は categorySum から取得
                val amount = if (isSubtotal || isMonthlyTotal) row.categorySum else row.amount

                ParsedRow(
                    rowIndex      = index,
                    date          = row.dateRaw,
                    productName   = row.itemName,
                    branch        = null,
                    quantity      = row.quantity?.toInt(),
                    unitPrice     = null,
                    amount        = amount,
                    isAmountValid = amount != null,
                    category      = category,
                    isSubtotal    = isSubtotal,
                    isMonthlyTotal = isMonthlyTotal,
                    confidence    = row.confidence
                )
            }
        }

        /**
         * 小計行の区分名からカテゴリを仮判定する（OCR時点の簡易判定）。
         * 通常行の最終カテゴリは保存後に CategoryRecalculator が月全体を見て確定させるため、
         * ここでは util/UnderlyingBaseProcessor.assignCategories() と同じ簡易ロジックを踏襲する。
         */
        private fun assignJaSheetCategories(
            rows: List<GeminiReceiptClient.JaSheetRow>
        ): List<Pair<GeminiReceiptClient.JaSheetRow, String>> {
            val subtotalIndices = mutableListOf<Pair<Int, String>>()
            rows.forEachIndexed { index, row ->
                if (row.rowType == "SUBTOTAL") {
                    val name = row.itemName
                    val category = when {
                        name.contains("一般購買") || name.contains("一般買") ||
                        name.contains("一般講買") || name.contains("ー般購買") ||
                        name.contains("般購買") || name.contains("般講買") -> Category.GENERAL

                        name.contains("給油所") || name.contains("給値所") ||
                        name.contains("給造所") || name.contains("給治所") ||
                        name.contains("給抽所") -> Category.GAS_STATION

                        name.contains("農業機械") || name.contains("展業慢城") ||
                        name.contains("農来") || name.contains("農発検") ||
                        name.contains("農業") -> Category.AGRICULTURAL

                        else -> Category.UNCLASSIFIED
                    }
                    subtotalIndices.add(index to category)
                }
            }

            val firstSubtotalIndex = subtotalIndices.firstOrNull()?.first
            return rows.mapIndexed { index, row ->
                val category = when (row.rowType) {
                    "SUBTOTAL" -> subtotalIndices.find { it.first == index }?.second ?: Category.UNCLASSIFIED
                    "NORMAL" -> when {
                        firstSubtotalIndex == null -> Category.UNCLASSIFIED
                        index < firstSubtotalIndex -> Category.UNCLASSIFIED
                        else -> "未定"
                    }
                    "MONTHLY_TOTAL" -> "月合計"
                    else -> Category.UNCLASSIFIED
                }
                row to category
            }
        }
    }
}
