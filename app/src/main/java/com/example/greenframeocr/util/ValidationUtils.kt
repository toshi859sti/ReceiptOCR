package com.example.greenframeocr.util

import com.example.greenframeocr.data.ReceiptItem
import com.example.greenframeocr.data.SheetData
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * データ検証結果
 */
data class ValidationResult(
    val isSubtotalGeneralMatched: Boolean,
    val isSubtotalGasMatched: Boolean,
    val isSubtotalAgriMatched: Boolean,
    val isTotalMatched: Boolean,
    val calculatedSubtotalGeneral: Int,
    val calculatedSubtotalGas: Int,
    val calculatedSubtotalAgri: Int,
    val calculatedTotal: Int,
    val inputTotal: Int?
) {
    val isAllMatched: Boolean
        get() = isSubtotalGeneralMatched && isSubtotalGasMatched &&
                isSubtotalAgriMatched && isTotalMatched
}

/**
 * 分類の種類
 */
object Category {
    const val UNCLASSIFIED = "未分類"
    const val GENERAL = "一般購買"
    const val GAS_STATION = "給油所"
    const val AGRICULTURAL = "農業機械"
}

/**
 * データ検証ユーティリティ
 */
object ValidationUtils {

    /**
     * ReceiptItemの検証
     * @return エラーメッセージ（エラーがない場合はnull）
     */
    fun validateReceiptItem(item: ReceiptItem): String? {
        // 商品名が空欄
        if (item.productName.isBlank()) {
            return "商品名が空欄です"
        }

        // 日付の妥当性（発行月から±1ヶ月以内かチェック）
        try {
            val receiptDate = LocalDate.of(item.receiptYear, item.receiptMonth, item.receiptDay)
            val issueDate = LocalDate.of(item.issueYear, item.issueMonth, 1)
            val monthsDiff = kotlin.math.abs(ChronoUnit.MONTHS.between(receiptDate, issueDate))

            if (monthsDiff > 1) {
                return "日付が発行月から離れています（${monthsDiff}ヶ月差）"
            }
        } catch (e: Exception) {
            return "日付が不正です: ${e.message}"
        }

        return null // エラーなし
    }

    /**
     * 伝票の小計・合計の整合性チェック
     */
    fun validateSheet(
        items: List<ReceiptItem>,
        sheetData: SheetData
    ): ValidationResult {
        // カテゴリ別の計算値
        val calcGeneral = items.filter { it.category == Category.GENERAL }.sumOf { it.amount }
        val calcGas = items.filter { it.category == Category.GAS_STATION }.sumOf { it.amount }
        val calcAgri = items.filter { it.category == Category.AGRICULTURAL }.sumOf { it.amount }
        val calcTotal = calcGeneral + calcGas + calcAgri

        return ValidationResult(
            isSubtotalGeneralMatched = sheetData.subtotalGeneral == null || sheetData.subtotalGeneral == calcGeneral,
            isSubtotalGasMatched = sheetData.subtotalGas == null || sheetData.subtotalGas == calcGas,
            isSubtotalAgriMatched = sheetData.subtotalAgri == null || sheetData.subtotalAgri == calcAgri,
            isTotalMatched = sheetData.totalFromInput == null || sheetData.totalFromInput == calcTotal,
            calculatedSubtotalGeneral = calcGeneral,
            calculatedSubtotalGas = calcGas,
            calculatedSubtotalAgri = calcAgri,
            calculatedTotal = calcTotal,
            inputTotal = sheetData.totalFromInput
        )
    }

    /**
     * 分類の自動変更
     * OCRで小計を読み取った時、前の伝票の未分類アイテムを自動分類する
     *
     * @param allItems 月の全アイテム
     * @param currentSheet 現在の伝票番号
     * @param subtotalsByCategory 各カテゴリの小計 (カテゴリ名 -> 小計金額)
     * @return 分類が変更されたアイテムのリスト
     */
    fun autoClassifyItems(
        allItems: List<ReceiptItem>,
        currentSheet: Int,
        subtotalsByCategory: Map<String, Int>
    ): List<ReceiptItem> {
        val updatedItems = mutableListOf<ReceiptItem>()

        subtotalsByCategory.forEach { (category, subtotal) ->
            // 現在より前の伝票の未分類アイテム
            val unclassifiedItems = allItems.filter {
                it.sheetNumber < currentSheet && it.category == Category.UNCLASSIFIED
            }

            // 合計が一致する場合、分類を変更
            val unclassifiedSum = unclassifiedItems.sumOf { it.amount }
            if (unclassifiedSum == subtotal) {
                unclassifiedItems.forEach { item ->
                    updatedItems.add(item.copy(category = category))
                }
            }
        }

        return updatedItems
    }

    /**
     * 月全体の合計の検証
     * MonthlyDataの合計と全伝票の累計を比較
     */
    fun validateMonthTotal(
        monthlyTotal: Int,
        allSheets: List<SheetData>
    ): Boolean {
        val sheetTotalSum = allSheets.mapNotNull { it.totalFromInput }.sum()
        return monthlyTotal == sheetTotalSum
    }

    // -----------------------------------------------------------------------
    // Step 7: OCR検算バリデーション
    // -----------------------------------------------------------------------

    /** OCR誤認識の典型パターン補正テーブル */
    private val OCR_CORRECTIONS = mapOf("O" to "0", "l" to "1", "S" to "5", "B" to "8")

    /**
     * OCR結果の数字文字列をサニタイズして Int に変換する。
     * 誤認識補正 → 数字・マイナス以外を除去 → Int変換。失敗時は null。
     */
    fun sanitizeNumber(raw: String): Int? {
        var cleaned = raw
        OCR_CORRECTIONS.forEach { (wrong, correct) ->
            cleaned = cleaned.replace(wrong, correct)
        }
        return cleaned.filter { it.isDigit() || it == '-' }.toIntOrNull()
    }

    /**
     * 単価×数量=金額 の検算（端数処理3パターンで許容）。
     * ガソリン行（数量がリットル×100表記）の場合も自動対応。
     */
    fun validateWithRounding(unitPrice: Int, quantity: Int, amount: Int): Boolean {
        val exact = unitPrice.toBigDecimal() * quantity.toBigDecimal()
        val candidates = listOf(
            exact.setScale(0, java.math.RoundingMode.FLOOR).toInt(),
            exact.setScale(0, java.math.RoundingMode.HALF_UP).toInt(),
            exact.setScale(0, java.math.RoundingMode.CEILING).toInt()
        )
        return candidates.any { it == amount }
    }

    /**
     * 日付の文字列表現を取得
     * 例: "02/01" (2月1日)
     */
    fun formatDate(month: Int, day: Int): String {
        return "%02d/%02d".format(month, day)
    }

    /**
     * 金額の文字列表現を取得（カンマ区切り）
     * 例: "1,234,567円"
     */
    fun formatAmount(amount: Int): String {
        return "%,d円".format(amount)
    }
}
