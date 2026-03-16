package com.example.greenframeocr.util

import android.util.Log
import com.example.greenframeocr.data.ReceiptDao
import com.example.greenframeocr.data.ReceiptItem

/**
 * 月全体のカテゴリを再計算するユーティリティ
 *
 * ロジック: 各行の「次の小計」を探してカテゴリを決定する。
 * 1伝票に複数小計がある場合も正しく処理する。
 * - 通常行: 自分より後にある最初の小計のカテゴリを使用
 * - 最初の小計より前の行: 最初の小計のカテゴリを使用（遡り）
 * - 最後の小計より後の行: 「未定」
 */
object CategoryRecalculator {
    private const val TAG = "CategoryRecalculator"

    /**
     * 指定された月の全伝票のカテゴリを再計算して更新
     *
     * @param dao ReceiptDao
     * @param year 発行年
     * @param month 発行月
     */
    suspend fun recalculateMonthlyCategories(
        dao: ReceiptDao,
        year: Int,
        month: Int
    ) {
        Log.d(TAG, "=== カテゴリ再計算開始: ${year}年${month}月 ===")

        // 1. その月の全データを取得（伝票番号順、行番号順）
        val allItems = dao.getAllReceiptItems()
            .filter { it.issueYear == year && it.issueMonth == month }
            .sortedWith(compareBy({ it.sheetNumber }, { it.itemNumber }))

        if (allItems.isEmpty()) {
            Log.d(TAG, "データなし")
            return
        }

        // 2. 全小計を収集（伝票番号・行番号順）
        // カテゴリは item.category を優先し、無効な場合のみ productName から検出する
        val validCategories = setOf("一般購買", "農業機械", "給油所")
        data class SubtotalInfo(
            val sheetNumber: Int,
            val itemNumber: Int,
            val category: String
        )
        val subtotals = allItems
            .filter { it.productName.contains("小計") }
            .map { item ->
                val category = if (item.category in validCategories) {
                    item.category
                } else {
                    detectCategoryFromSubtotal(item.productName)
                }
                SubtotalInfo(item.sheetNumber, item.itemNumber, category)
            }

        Log.d(TAG, "小計一覧: ${subtotals.map { "${it.sheetNumber}-${it.itemNumber}:${it.category}" }}")

        // 3. 各行に「次の小計」のカテゴリを割り当て
        val updatedItems = mutableListOf<ReceiptItem>()

        allItems.forEach { item ->
            val newCategory = when {
                // 小計行自身: 既存カテゴリを保持（再検出しない）
                item.productName.contains("小計") -> item.category

                // 金額のない行（空行・合計行等）はカテゴリ変更しない
                item.amount == 0 -> item.category

                // 通常行: 自分より後にある最初の小計を探す
                else -> {
                    val nextSubtotal = subtotals.find { subtotal ->
                        subtotal.sheetNumber > item.sheetNumber ||
                        (subtotal.sheetNumber == item.sheetNumber && subtotal.itemNumber > item.itemNumber)
                    }
                    if (nextSubtotal != null) {
                        nextSubtotal.category
                    } else {
                        // 次の小計なし → 最後の小計より後の行
                        "未定"
                    }
                }
            }

            if (item.category != newCategory) {
                updatedItems.add(item.copy(category = newCategory))
                Log.d(TAG, "伝票${item.sheetNumber}-行${item.itemNumber}: '${item.category}' → '$newCategory' (${item.productName})")
            }
        }

        // 4. 最初の小計より前の行を遡り更新（前の伝票含む）
        if (subtotals.isNotEmpty()) {
            val firstSubtotal = subtotals.first()
            Log.d(TAG, "最初の小計: 伝票${firstSubtotal.sheetNumber}-行${firstSubtotal.itemNumber} → ${firstSubtotal.category}")

            allItems.forEach { item ->
                val isBefore = item.sheetNumber < firstSubtotal.sheetNumber ||
                    (item.sheetNumber == firstSubtotal.sheetNumber && item.itemNumber < firstSubtotal.itemNumber)
                if (isBefore && !item.productName.contains("小計") && item.amount != 0) {
                    // updatedItems に既に追加済みなら更新、なければ新規追加
                    val existing = updatedItems.indexOfFirst { it.sheetNumber == item.sheetNumber && it.itemNumber == item.itemNumber }
                    val targetCategory = firstSubtotal.category
                    if (existing >= 0) {
                        if (updatedItems[existing].category != targetCategory) {
                            updatedItems[existing] = updatedItems[existing].copy(category = targetCategory)
                        }
                    } else if (item.category != targetCategory) {
                        updatedItems.add(item.copy(category = targetCategory))
                        Log.d(TAG, "遡り更新: 伝票${item.sheetNumber}-行${item.itemNumber}: '${item.category}' → '$targetCategory'")
                    }
                }
            }
        }

        // 5. データベースを更新
        if (updatedItems.isNotEmpty()) {
            dao.updateReceiptItems(updatedItems)
            Log.d(TAG, "更新完了: ${updatedItems.size}件")
        } else {
            Log.d(TAG, "変更なし")
        }

        Log.d(TAG, "=== カテゴリ再計算完了 ===")
    }

    /**
     * 小計行の文字列からカテゴリを検出
     */
    private fun detectCategoryFromSubtotal(subtotalText: String): String {
        return when {
            // 一般購買の誤認識パターン
            subtotalText.contains("一般購買") ||
            subtotalText.contains("一般買") ||
            subtotalText.contains("一般講買") ||
            subtotalText.contains("一般課買") ||
            subtotalText.contains("ー般講買") ||
            subtotalText.contains("ー般購買") ||
            subtotalText.contains("般購買") ||
            subtotalText.contains("般講買") -> "一般購買"

            // 給油所の誤認識パターン
            subtotalText.contains("給油所") ||
            subtotalText.contains("給値所") ||
            subtotalText.contains("給造所") ||
            subtotalText.contains("給治所") ||
            subtotalText.contains("給抽所") -> "給油所"

            // 農業機械の誤認識パターン
            subtotalText.contains("農業機械") ||
            subtotalText.contains("展業慢城") ||
            subtotalText.contains("農来") ||
            subtotalText.contains("農発検") ||
            subtotalText.contains("農業") -> "農業機械"

            // パターンマッチ失敗時は一文字検出を試行
            else -> detectCategoryBySingleChar(subtotalText) ?: "未分類"
        }
    }

    /**
     * 一文字でもカテゴリを判別
     * 各カテゴリには固有文字があるため、一文字でも判別可能
     */
    private fun detectCategoryBySingleChar(text: String): String? {
        // 一般購買の固有文字（誤認識パターンも含む）
        val generalChars = setOf('般', '購', '買', '講', '課')

        // 農業機械の固有文字（誤認識パターンも含む）
        val agriChars = setOf('農', '機', '械', '展', '慢', '城', '来', '発', '検')

        // 給油所の固有文字（誤認識パターンも含む）
        val gasChars = setOf('給', '油', '所', '値', '造', '治', '抽')

        for (char in text) {
            when {
                generalChars.contains(char) -> return "一般購買"
                agriChars.contains(char) -> return "農業機械"
                gasChars.contains(char) -> return "給油所"
            }
        }

        return null  // 判別不可
    }
}
