package com.example.receiptorc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.receiptorc.data.ReceiptDao
import com.example.receiptorc.data.ReceiptItem
import com.example.receiptorc.data.SheetData
import com.example.receiptorc.util.Category
import com.example.receiptorc.util.OCRProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * OCR撮影画面のViewModel
 * B→OCR→C→OCRの個別撮影フローを管理
 */
class OcrCaptureViewModel(
    private val dao: ReceiptDao,
    private val issueYear: Int,
    private val issueMonth: Int
) : ViewModel() {

    // キャプチャステップ
    sealed class CaptureStep {
        object Initial : CaptureStep()           // 初期状態
        object CapturingBBlock : CaptureStep()   // Bブロック撮影中
        object ProcessingBBlock : CaptureStep()  // Bブロック処理中
        data class BBlockComplete(val rows: List<OCRProcessor.BBlockRow>) : CaptureStep()
        object CapturingCBlock : CaptureStep()   // Cブロック撮影中
        object ProcessingCBlock : CaptureStep()  // Cブロック処理中
        object AllComplete : CaptureStep()       // 全完了
    }

    private val _currentStep = MutableStateFlow<CaptureStep>(CaptureStep.Initial)
    val currentStep: StateFlow<CaptureStep> = _currentStep.asStateFlow()

    // Bブロック結果
    private val _bBlockRows = MutableStateFlow<List<OCRProcessor.BBlockRow>>(emptyList())
    val bBlockRows: StateFlow<List<OCRProcessor.BBlockRow>> = _bBlockRows.asStateFlow()

    // Cブロック結果（金額のみ）
    private val _amounts = MutableStateFlow<List<String>>(emptyList())
    val amounts: StateFlow<List<String>> = _amounts.asStateFlow()

    // エラーメッセージ
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 伝票番号（自動採番）
    private var sheetNumber: Int = 1

    init {
        // 伝票番号を取得
        viewModelScope.launch {
            sheetNumber = dao.getMaxSheetNumberForMonth(issueYear, issueMonth) + 1
        }
    }

    /**
     * Bブロックの撮影を開始
     */
    fun startBBlockCapture() {
        _currentStep.value = CaptureStep.CapturingBBlock
        _errorMessage.value = null
    }

    /**
     * Bブロックの処理を開始
     */
    fun processBBlock(rows: List<OCRProcessor.BBlockRow>) {
        _currentStep.value = CaptureStep.ProcessingBBlock
        _bBlockRows.value = rows
        _currentStep.value = CaptureStep.BBlockComplete(rows)
    }

    /**
     * Cブロックの撮影を開始
     */
    fun startCBlockCapture() {
        _currentStep.value = CaptureStep.CapturingCBlock
        _errorMessage.value = null
    }

    /**
     * Cブロックの処理を開始
     */
    fun processCBlock(rows: List<OCRProcessor.CBlockRow>) {
        _currentStep.value = CaptureStep.ProcessingCBlock

        // 金額のみを抽出
        _amounts.value = rows.map { it.amount }

        // すべて完了
        _currentStep.value = CaptureStep.AllComplete
    }

    /**
     * データを保存してデータベースに登録
     */
    suspend fun saveData(): Boolean {
        return try {
            val bRows = _bBlockRows.value
            val amountStrs = _amounts.value

            if (bRows.isEmpty()) {
                _errorMessage.value = "Bブロックのデータがありません"
                return false
            }

            // ReceiptItemのリストを作成
            val receiptItems = mutableListOf<ReceiptItem>()
            var totalAmount = 0

            for ((index, bRow) in bRows.withIndex()) {
                // 日付をパース（YY/MM/DD形式）
                val dateParts = bRow.date.split("/")
                val year = if (dateParts.size >= 3) dateParts[0].toIntOrNull() ?: 0 else 0
                val month = if (dateParts.size >= 3) dateParts[1].toIntOrNull() ?: 1 else 1
                val day = if (dateParts.size >= 3) dateParts[2].toIntOrNull() ?: 1 else 1

                // 金額をパース
                val amountStr = amountStrs.getOrNull(index) ?: "0"
                val amount = OCRProcessor.parseAmount(amountStr) ?: 0
                totalAmount += amount

                val item = ReceiptItem(
                    issueYear = issueYear,
                    issueMonth = issueMonth,
                    sheetNumber = sheetNumber,
                    itemNumber = index + 1,
                    receiptYear = year,
                    receiptMonth = month,
                    receiptDay = day,
                    productName = bRow.productName,
                    amount = amount,
                    category = Category.UNCLASSIFIED,  // 初期値は未分類
                    isOcrOverwriteTarget = false
                )

                receiptItems.add(item)
            }

            // データベースに保存
            dao.insertReceiptItems(receiptItems)

            // SheetDataを作成（Cブロックの合計を小計として保存）
            val sheetData = SheetData(
                issueYear = issueYear,
                issueMonth = issueMonth,
                sheetNumber = sheetNumber,
                totalFromInput = if (amountStrs.isNotEmpty()) totalAmount else null,
                subtotalGeneral = null,
                subtotalGas = null,
                subtotalAgri = null
            )
            dao.insertSheetData(sheetData)

            true
        } catch (e: Exception) {
            _errorMessage.value = "保存エラー: ${e.message}"
            false
        }
    }

    /**
     * リセット（最初からやり直す）
     */
    fun reset() {
        _currentStep.value = CaptureStep.Initial
        _bBlockRows.value = emptyList()
        _amounts.value = emptyList()
        _errorMessage.value = null
    }

    /**
     * 現在の発行年を取得
     */
    fun getIssueYear(): Int = issueYear

    /**
     * 現在の発行月を取得
     */
    fun getIssueMonth(): Int = issueMonth

    /**
     * 現在の伝票番号を取得
     */
    fun getCurrentSheetNumber(): Int = sheetNumber
}
