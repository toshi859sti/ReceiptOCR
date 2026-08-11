package com.example.greenframeocr.util

import com.example.greenframeocr.data.ProductMasterDao

/**
 * Gemini Vision APIのJA伝票OCR結果（[GeminiReceiptClient.JaSheetParseResult]）を
 * DB保存用の行データに変換する共通処理。
 * `ui/ReceiptInputScreen.kt`のCameraViewから呼ばれる。
 */
object JaSheetOcrMapper {

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
        val confidence: String? = null,  // Geminiの自己申告確信度（"high"/"medium"/"low"）
        val productMasterId: Long? = null  // product_master.id への紐づけ（applyProductMasterCorrection()で設定）
    )

    /**
     * NORMAL行の商品名を購買品リスト（product_master）と照合し、一致すれば登録済みの
     * canonicalName に差し替え、productMasterId も紐づける。全角/半角スペース・英数字幅・
     * 半角カナの違いを吸収するため、product_master に事前計算済みの canonicalKey 列で
     * 比較する（表示文字列自体は書き換えない。OCR側の商品名だけその場で toCanonicalKey()
     * する）。productMasterId は CSV出力時の勘定科目/摘要引き当てを文字列完全一致ではなく
     * FKで行うために使う（`OutputConfirmScreen.kt`参照）。
     * SUBTOTAL/MONTHLY_TOTAL行は商品名ではないため対象外。
     */
    suspend fun applyProductMasterCorrection(
        rows: List<ParsedRow>,
        productMasterDao: ProductMasterDao
    ): List<ParsedRow> {
        val productByKey = productMasterDao.getAll().associateBy { it.canonicalKey }
        return rows.map { row ->
            if (row.isSubtotal || row.isMonthlyTotal || row.productName.isNullOrBlank()) {
                row
            } else {
                val matched = productByKey[toCanonicalKey(row.productName)]
                if (matched != null) {
                    row.copy(productName = matched.canonicalName, productMasterId = matched.id)
                } else {
                    row
                }
            }
        }
    }

    /**
     * GeminiReceiptClient.JaSheetParseResult → ParsedRow リストに変換する。
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
