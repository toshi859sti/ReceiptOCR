package com.example.receiptorc.util

import android.util.Log
import com.example.receiptorc.data.ReceiptDao
import com.example.receiptorc.data.ReceiptItem

/**
 * 月全体のカテゴリを再計算するユーティリティ
 *
 * ロジック:
 * 1. 最初の小計行が出るまで：すべて「未分類」
 * 2. 小計の後：「未定」（次のカテゴリか不明）
 * 3. 2枚目以降で小計が判明したら、1枚目の「未定」を確定
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

        // 伝票番号でグループ化
        val itemsBySheet = allItems.groupBy { it.sheetNumber }.toSortedMap()

        // 2. 各伝票の小計行からカテゴリを検出
        val sheetCategories = mutableMapOf<Int, MutableList<String>>()

        itemsBySheet.forEach { (sheetNumber, items) ->
            val categories = mutableListOf<String>()

            items.sortedBy { it.itemNumber }.forEach { item ->
                // 小計行の判定（商品名に「小計」が含まれる）
                if (item.productName.contains("小計")) {
                    val category = detectCategoryFromSubtotal(item.productName)
                    categories.add(category)
                    Log.d(TAG, "伝票${sheetNumber}枚目: 小計検出 '${item.productName}' → $category")
                }
            }

            if (categories.isNotEmpty()) {
                sheetCategories[sheetNumber] = categories
            }
        }

        // 3. 各伝票の小計からカテゴリ順序を構築
        // 例: 1枚目の小計→一般購買、2枚目の小計→給油所、3枚目の小計→農業機械
        val categorySequence = mutableListOf<String>()
        sheetCategories.toSortedMap().forEach { (sheetNumber, categories) ->
            // 各伝票の最初の小計のみを使用
            if (categories.isNotEmpty()) {
                val firstSubtotal = categories.first()
                categorySequence.add(firstSubtotal)
                Log.d(TAG, "伝票${sheetNumber}枚目の小計: $firstSubtotal")
            }
        }
        Log.d(TAG, "カテゴリ順序: $categorySequence")

        // 4. 各伝票の各行にカテゴリを適用
        val updatedItems = mutableListOf<ReceiptItem>()

        itemsBySheet.forEach { (sheetNumber, items) ->
            // この伝票のカテゴリインデックス（0始まり）
            val sheetIndex = sheetNumber - 1

            // この伝票の小計より前の行のカテゴリ
            val beforeSubtotalCategory = if (sheetIndex < categorySequence.size) {
                categorySequence[sheetIndex]
            } else {
                "未分類" // デフォルト
            }

            // この伝票の小計より後の行のカテゴリ（次の伝票の小計で判明）
            val afterSubtotalCategory = if (sheetIndex + 1 < categorySequence.size) {
                categorySequence[sheetIndex + 1]
            } else {
                "未定" // まだ次の伝票がない
            }

            var subtotalPassed = false

            items.sortedBy { it.itemNumber }.forEach { item ->
                val newCategory = when {
                    // 小計行の場合: 検出されたカテゴリをそのまま使用
                    item.productName.contains("小計") -> {
                        subtotalPassed = true
                        detectCategoryFromSubtotal(item.productName)
                    }
                    // 小計より前の通常行
                    !subtotalPassed -> beforeSubtotalCategory
                    // 小計より後の通常行
                    else -> afterSubtotalCategory
                }

                // カテゴリが変更された場合のみ更新リストに追加
                if (item.category != newCategory) {
                    updatedItems.add(item.copy(category = newCategory))
                    Log.d(TAG, "伝票${sheetNumber}-行${item.itemNumber}: '${item.category}' → '$newCategory' (${item.productName})")
                }
            }
        }

        // 6. データベースを更新
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
