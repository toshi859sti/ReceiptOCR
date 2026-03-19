package com.example.greenframeocr.util

import android.graphics.Rect
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * 伝票OCR処理クラス（旧ArUco台紙方式のOCRパイプライン）
 *
 * 座標系:
 * - 原点: 透視変換後の伝票左上 (0, 0)
 * - X軸: 伝票左→右（203mm）
 * - Y軸: 伝票上→下（148mm）
 * - mm→px比率: warpedBitmap.width / 203.0
 *
 * 旧ReceiptOCRプロジェクトから移植。
 * ArUco基準のA4座標 → 伝票直接撮影の相対座標に変換済み。
 */
object UnderlyingBaseProcessor {
    private const val TAG = "UnderlyingBaseProcessor"

    // ============================================
    // 定数定義
    // ============================================

    /**
     * 行クラスタリングの閾値（デフォルト値、動的計算のフォールバック用）
     */
    private const val DEFAULT_ROW_CLUSTERING_Y_THRESHOLD = 15

    /**
     * 伝票サイズ（mm）
     * 203mm × 148mm（A5横、コの字枠）
     */
    private const val RECEIPT_WIDTH_MM = 203.0
    private const val RECEIPT_HEIGHT_MM = 148.0

    /**
     * 列範囲（mm単位、伝票左上原点）
     *
     * 根拠: 現行 GreenFrameDetector.DETAIL_ROIS の X比率 × 203mm
     * 取引日:0.02-0.10 / 商品名:0.10-0.46 / 取扱支店:0.46-0.55
     * 数量:0.55-0.62 / 税込単価:0.62-0.72 / 税込金額:0.72-0.83
     */
    // 列境界 = 印刷された縦罫線の位置（伝票左端起点、mm単位）
    // 根拠: 旧ReceiptOCRの実測値（px/mm=8.1の旧システムで確認済み）
    private const val DATE_START_MM = 5.5          // 取引日列開始
    private const val DATE_END_MM = 20.0           // 取引日│商品名 罫線
    private const val ITEM_START_MM = 20.0         // 商品名列開始
    private const val ITEM_END_MM = 79.5           // 商品名│取扱支店 罫線
    private const val STORE_START_MM = 79.5        // 取扱支店（OCR無視）
    private const val STORE_END_MM = 97.0          // 取扱支店│数量 罫線
    private const val QUANTITY_START_MM = 97.0     // 数量列開始
    private const val QUANTITY_END_MM = 117.0      // 数量│税込単価 罫線
    private const val UNITPRICE_START_MM = 117.0   // 税込単価（OCR無視）
    private const val UNITPRICE_END_MM = 134.5     // 税込単価│税込金額 罫線
    private const val AMOUNT_START_MM = 134.5      // 税込金額列開始
    private const val AMOUNT_END_MM = 156.0        // 税込金額│分類計 罫線
    private const val CATEGORY_START_MM = 156.0    // 分類計列開始
    private const val CATEGORY_END_MM = 177.5      // 分類計列終了

    /**
     * Y座標範囲（mm単位、伝票上端原点）
     *
     * 根拠: 旧ReceiptOCR実測値（RECEIPT_TOP_MM=31mmを差し引いた伝票相対値）
     * NORMAL_ROW_Y_START: RECEIPT_TOP + 56.5 - 31 = 56.5mm（ヘッダー部分を除外）
     * 40.0mmだとヘッダーテーブル（前月請求/当月請求等）を巻き込む
     */
    private const val NORMAL_ROW_Y_START_MM = 56.5    // 通常行開始（実測値: ヘッダー除外）
    private const val NORMAL_ROW_Y_END_MM = 120.5     // 通常行終了（実測値）
    // 小計行は通常行グリッド内に混在するため、NORMAL_ROW_Y と同じ範囲
    private const val SUBTOTAL_Y_START_MM = 56.5      // = NORMAL_ROW_Y_START_MM
    private const val SUBTOTAL_Y_END_MM = 120.5       // = NORMAL_ROW_Y_END_MM
    private const val MONTHLY_TOTAL_Y_START_MM = 121.0
    private const val MONTHLY_TOTAL_Y_END_MM = 135.0  // 合計（税込）行を含む

    /**
     * 列範囲（px単位、実行時に初期化）
     * デフォルト値: 4000×2916px (ratio≈19.70 px/mm) 想定
     */
    private var DATE_RANGE: IntRange = 79..404
    private var ITEM_RANGE: IntRange = 404..1842
    private var STORE_RANGE: IntRange = 1842..2203
    private var QUANTITY_RANGE: IntRange = 2203..2481
    private var UNITPRICE_RANGE: IntRange = 2481..2880
    private var AMOUNT_RANGE: IntRange = 2880..3321
    private var CATEGORY_RANGE: IntRange = 3321..3842

    /**
     * Y座標範囲（px単位、実行時に初期化）
     */
    private var SUBTOTAL_Y_RANGE: IntRange = 788..2483
    private var NORMAL_ROW_Y_RANGE: IntRange = 788..2483
    private var MONTHLY_TOTAL_Y_RANGE: IntRange = 2444..2916

    // ============================================
    // 列挙型
    // ============================================

    enum class ColumnType {
        DATE,
        ITEM,
        QUANTITY,
        AMOUNT,
        CATEGORY_SUM,
        SUBTOTAL
    }

    enum class RowType {
        NORMAL,
        SUBTOTAL,
        MONTHLY_TOTAL,
        EMPTY
    }

    // ============================================
    // データクラス
    // ============================================

    data class TextBox(
        val text: String,
        val bounds: Rect,
        val centerX: Int,
        val centerY: Int
    )

    data class ReceiptRow(
        val rowType: RowType,
        val date: String?,
        val itemName: String?,
        val quantity: String?,
        val amount: Int?,
        val categorySum: Int?,
        val rawText: String? = null
    )

    data class SubtotalData(
        val value: Int,
        val centerX: Int,
        val centerY: Int
    )

    // ============================================
    // 公開関数
    // ============================================

    /**
     * 列範囲を初期化（透視変換後の mm→px 比率を使用）
     *
     * @param mmToPixelRatio mm→px変換率（例: warpedBitmap.width / 203.0）
     */
    fun initializeColumnRanges(mmToPixelRatio: Double) {
        DATE_RANGE      = (DATE_START_MM * mmToPixelRatio).toInt()..(DATE_END_MM * mmToPixelRatio).toInt()
        ITEM_RANGE      = (ITEM_START_MM * mmToPixelRatio).toInt()..(ITEM_END_MM * mmToPixelRatio).toInt()
        STORE_RANGE     = (STORE_START_MM * mmToPixelRatio).toInt()..(STORE_END_MM * mmToPixelRatio).toInt()
        QUANTITY_RANGE  = (QUANTITY_START_MM * mmToPixelRatio).toInt()..(QUANTITY_END_MM * mmToPixelRatio).toInt()
        UNITPRICE_RANGE = (UNITPRICE_START_MM * mmToPixelRatio).toInt()..(UNITPRICE_END_MM * mmToPixelRatio).toInt()
        AMOUNT_RANGE    = (AMOUNT_START_MM * mmToPixelRatio).toInt()..(AMOUNT_END_MM * mmToPixelRatio).toInt()
        CATEGORY_RANGE  = (CATEGORY_START_MM * mmToPixelRatio).toInt()..(CATEGORY_END_MM * mmToPixelRatio).toInt()

        NORMAL_ROW_Y_RANGE     = (NORMAL_ROW_Y_START_MM * mmToPixelRatio).toInt()..(NORMAL_ROW_Y_END_MM * mmToPixelRatio).toInt()
        SUBTOTAL_Y_RANGE       = (SUBTOTAL_Y_START_MM * mmToPixelRatio).toInt()..(SUBTOTAL_Y_END_MM * mmToPixelRatio).toInt()
        MONTHLY_TOTAL_Y_RANGE  = (MONTHLY_TOTAL_Y_START_MM * mmToPixelRatio).toInt()..(MONTHLY_TOTAL_Y_END_MM * mmToPixelRatio).toInt()

        Log.d(TAG, "initializeColumnRanges: mmToPixelRatio=$mmToPixelRatio")
        Log.d(TAG, "  DATE_RANGE: $DATE_RANGE")
        Log.d(TAG, "  ITEM_RANGE: $ITEM_RANGE")
        Log.d(TAG, "  QUANTITY_RANGE: $QUANTITY_RANGE")
        Log.d(TAG, "  AMOUNT_RANGE: $AMOUNT_RANGE")
        Log.d(TAG, "  CATEGORY_RANGE: $CATEGORY_RANGE")
        Log.d(TAG, "  NORMAL_ROW_Y_RANGE: $NORMAL_ROW_Y_RANGE")
        Log.d(TAG, "  MONTHLY_TOTAL_Y_RANGE: $MONTHLY_TOTAL_Y_RANGE")
    }

    fun getNormalRowYRange(): IntRange = NORMAL_ROW_Y_RANGE
    fun getSubtotalYRange(): IntRange = SUBTOTAL_Y_RANGE
    fun getMonthlyTotalYRange(): IntRange = MONTHLY_TOTAL_Y_RANGE
    fun getValidRowYRange(): IntRange = NORMAL_ROW_Y_RANGE.first..MONTHLY_TOTAL_Y_RANGE.last
    fun getQuantityRange(): IntRange = QUANTITY_RANGE
    fun getItemRange(): IntRange = ITEM_RANGE

    /**
     * X座標から列タイプを判定
     */
    fun detectColumn(cx: Int): ColumnType? = when (cx) {
        in STORE_RANGE     -> null  // 取扱支店（無視）
        in UNITPRICE_RANGE -> null  // 税込単価（無視）
        in DATE_RANGE      -> ColumnType.DATE
        in ITEM_RANGE      -> ColumnType.ITEM
        in QUANTITY_RANGE  -> ColumnType.QUANTITY
        in AMOUNT_RANGE    -> ColumnType.AMOUNT
        in CATEGORY_RANGE  -> ColumnType.CATEGORY_SUM
        else               -> null
    }

    /**
     * ノイズ除去（空文字、記号のみ、極小bbox）
     */
    fun filterNoise(textBoxes: List<TextBox>): List<TextBox> {
        val filtered = textBoxes.filter { box ->
            if (box.text.isBlank()) return@filter false
            val width = box.bounds.width()
            val height = box.bounds.height()
            if (width < 5 || height < 5) return@filter false
            // 罫線のみのBoxは除去（縦棒系記号・※）、* ＊は小計識別文字なので除去しない
            val onlySymbols = box.text.matches(Regex("^[※｜|\\s]+$"))
            if (onlySymbols) return@filter false
            true
        }
        Log.d(TAG, "filterNoise: ${textBoxes.size} → ${filtered.size}")
        return filtered
    }

    /**
     * 行タイプを判定（通常/小計/月合計/空白）
     */
    fun detectRowType(rowBoxes: List<TextBox>): RowType {
        if (rowBoxes.isEmpty()) return RowType.EMPTY

        val rowText = rowBoxes.joinToString(" ") { it.text }
        val rowY    = rowBoxes.firstOrNull()?.centerY ?: 0

        val subtotalKeywords = listOf("小計", "一般購買", "給油所", "農業機械")
        if (subtotalKeywords.any { rowText.contains(it) } && rowY in SUBTOTAL_Y_RANGE) {
            Log.d(TAG, "  RowType: SUBTOTAL (Y=$rowY, text='$rowText')")
            return RowType.SUBTOTAL
        }

        val monthlyTotalKeywords = listOf("月合計", "合　計", "合計", "税込", "合計（税込）")
        val hasMonthlyTotalKeyword = monthlyTotalKeywords.any { rowText.contains(it) }
        val hasMoney        = rowText.matches(Regex(".*\\d{5,6}.*"))
        val hasFooterKeyword = listOf("※", "以下", "入金").any { rowText.contains(it) }

        if (rowY in MONTHLY_TOTAL_Y_RANGE) {
            if (hasMonthlyTotalKeyword || (hasMoney && hasFooterKeyword)) {
                Log.d(TAG, "  RowType: MONTHLY_TOTAL (Y=$rowY)")
                return RowType.MONTHLY_TOTAL
            }
        }

        Log.d(TAG, "  RowType: NORMAL (Y=$rowY)")
        return RowType.NORMAL
    }

    /**
     * テキストパターンから列タイプを検証
     */
    fun validateByPattern(text: String): ColumnType? = when {
        text.matches(Regex("^\\d{6}$")) -> ColumnType.DATE
        else -> null
    }

    /**
     * 行クラスタリング（Y座標主軸方式）
     */
    fun clusterRows(textBoxes: List<TextBox>): List<List<TextBox>> {
        if (textBoxes.isEmpty()) return emptyList()

        val sortedBoxes = textBoxes.sortedBy { it.centerY }
        val threshold = calculateRowClusteringThreshold(textBoxes)
        Log.d(TAG, "clusterRows: threshold=${threshold}px, ${textBoxes.size} boxes")

        val rows = mutableListOf<MutableList<TextBox>>()
        var currentRow = mutableListOf<TextBox>()
        var lastY = sortedBoxes[0].centerY

        for (box in sortedBoxes) {
            val yDiff = kotlin.math.abs(box.centerY - lastY)
            if (yDiff <= threshold) {
                currentRow.add(box)
            } else {
                if (currentRow.isNotEmpty()) rows.add(currentRow)
                currentRow = mutableListOf(box)
                lastY = box.centerY
            }
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        Log.d(TAG, "clusterRows: ${rows.size} rows")
        rows.forEachIndexed { i, row ->
            val avgY  = row.map { it.centerY }.average().toInt()
            val texts = row.map { it.text }.joinToString(", ")
            Log.d(TAG, "  Row $i (Y=$avgY): [$texts]")
        }
        return rows
    }

    private fun calculateRowClusteringThreshold(textBoxes: List<TextBox>): Int {
        val heights = textBoxes.map { it.bounds.height() }
        if (heights.size < 4) return DEFAULT_ROW_CLUSTERING_Y_THRESHOLD

        val sorted = heights.sorted()
        val q1 = sorted[(sorted.size * 0.25).toInt()]
        val q3 = sorted[(sorted.size * 0.75).toInt()]
        val iqr = q3 - q1
        val lower = q1 - 1.5 * iqr
        val upper = q3 + 1.5 * iqr

        val filtered = sorted.filter { it >= lower && it <= upper }
        val avgHeight = if (filtered.isNotEmpty()) filtered.average() else sorted.average()

        return (avgHeight * 0.6).toInt().coerceIn(10, 30)
    }

    /**
     * 1行のテキストボックスから ReceiptRow を生成
     */
    fun processRow(rowBoxes: List<TextBox>, rowType: RowType): ReceiptRow {
        val rawText = rowBoxes.joinToString(" ") { it.text }
        return when (rowType) {
            RowType.SUBTOTAL      -> processSubtotalRow(rowBoxes, rawText)
            RowType.MONTHLY_TOTAL -> processMonthlyTotalRow(rowBoxes, rawText)
            RowType.EMPTY         -> ReceiptRow(rowType, null, null, null, null, null, rawText)
            RowType.NORMAL        -> processNormalRow(rowBoxes, rawText)
        }
    }

    private fun processNormalRow(rowBoxes: List<TextBox>, rawText: String): ReceiptRow {
        var date: String? = null
        var quantity: String? = null
        var amount: Int? = null
        var categorySum: Int? = null

        val sortedBoxes = rowBoxes.sortedBy { it.centerX }
        sortedBoxes.forEach { box ->
            val column    = detectColumn(box.centerX)
            val validated = validateByPattern(box.text)
            val finalCol  = validated ?: column

            when (finalCol) {
                ColumnType.DATE -> {
                    date = box.text
                    Log.d(TAG, "  DATE: ${box.text}")
                }
                ColumnType.ITEM -> {
                    // 商品名列は列特化OCR（extractProductNamesFromColumn）で取得
                    Log.d(TAG, "  ITEM(skipped full-OCR): ${box.text}")
                }
                ColumnType.QUANTITY -> {
                    quantity = box.text.replace(" ", "").replace(",", "")
                    Log.d(TAG, "  QTY: $quantity")
                }
                ColumnType.AMOUNT -> {
                    val isNeg    = box.text.contains("-")
                    val norm     = normalizeToDigits(box.text)
                    val value    = norm.toIntOrNull()
                    amount = if (isNeg && value != null) -value else value
                    Log.d(TAG, "  AMOUNT: $amount")
                }
                ColumnType.CATEGORY_SUM -> {
                    val norm = box.text.replace(" ", "").replace(",", "")
                    categorySum = norm.toIntOrNull()
                    Log.d(TAG, "  CATEGORY_SUM: $categorySum")
                }
                else -> Log.d(TAG, "  IGNORED: ${box.text} (X=${box.centerX})")
            }
        }

        return ReceiptRow(RowType.NORMAL, date, null, quantity, amount, categorySum, rawText)
    }

    private fun processSubtotalRow(rowBoxes: List<TextBox>, rawText: String): ReceiptRow {
        // 小計行は商品名列に「＊小計（カテゴリ名）」が入る
        val itemParts = mutableListOf<String>()
        var categorySum: Int? = null

        for (box in rowBoxes) {
            when (detectColumn(box.centerX)) {
                ColumnType.ITEM -> {
                    // 罫線の誤認識で先頭に付く | ｜ を除去（* ＊は小計識別文字なので保持）
                    val cleaned = box.text.trimStart('|', '｜', ' ', '　')
                    if (cleaned.isNotBlank()) itemParts.add(cleaned)
                }
                ColumnType.CATEGORY_SUM -> {
                    categorySum = normalizeToDigits(box.text).toIntOrNull()
                    Log.d(TAG, "  SUBTOTAL AMOUNT: $categorySum")
                }
                else -> {}
            }
        }

        // 結合後も先頭の罫線文字を除去
        val itemName = itemParts.joinToString(" ").trimStart('|', '｜', ' ', '　').ifBlank { null }
        Log.d(TAG, "  SUBTOTAL ITEM: $itemName (raw: $rawText)")
        return ReceiptRow(RowType.SUBTOTAL, null, itemName, null, null, categorySum, rawText)
    }

    private fun processMonthlyTotalRow(rowBoxes: List<TextBox>, rawText: String): ReceiptRow {
        var categorySum: Int? = null
        for (box in rowBoxes) {
            if (detectColumn(box.centerX) == ColumnType.CATEGORY_SUM) {
                categorySum = box.text.toIntOrNull()
                Log.d(TAG, "  MONTHLY_TOTAL AMOUNT: $categorySum")
            }
        }
        return ReceiptRow(RowType.MONTHLY_TOTAL, null, "月合計", null, null, categorySum, rawText)
    }

    // ============================================
    // カテゴリ判定
    // ============================================

    /**
     * 小計行から逆算してカテゴリを判定
     */
    fun assignCategories(rows: List<ReceiptRow>): List<Pair<ReceiptRow, String>> {
        val subtotalIndices = mutableListOf<Pair<Int, String>>()

        rows.forEachIndexed { index, row ->
            if (row.rowType == RowType.SUBTOTAL) {
                val categoryName = row.itemName ?: ""
                val category = when {
                    categoryName.contains("一般購買") || categoryName.contains("一般買")  ||
                    categoryName.contains("一般講買") || categoryName.contains("ー般購買") ||
                    categoryName.contains("般購買")   || categoryName.contains("般講買")  -> "一般購買"

                    categoryName.contains("給油所") || categoryName.contains("給値所") ||
                    categoryName.contains("給造所") || categoryName.contains("給治所") ||
                    categoryName.contains("給抽所")  -> "給油所"

                    categoryName.contains("農業機械") || categoryName.contains("展業慢城") ||
                    categoryName.contains("農来")  || categoryName.contains("農発検")  ||
                    categoryName.contains("農業")   -> "農業機械"

                    else -> detectCategoryBySingleChar(categoryName) ?: "未分類"
                }
                subtotalIndices.add(index to category)
                Log.d(TAG, "Subtotal at $index: '$categoryName' → $category")
            }
        }

        val firstSubtotalIndex = subtotalIndices.firstOrNull()?.first
        val result = mutableListOf<Pair<ReceiptRow, String>>()

        rows.forEachIndexed { index, row ->
            val category = when (row.rowType) {
                RowType.SUBTOTAL      -> subtotalIndices.find { it.first == index }?.second ?: "未分類"
                RowType.NORMAL        -> when {
                    firstSubtotalIndex == null   -> "未分類"
                    index < firstSubtotalIndex   -> "未分類"
                    else                         -> "未定"
                }
                RowType.MONTHLY_TOTAL -> "月合計"
                RowType.EMPTY         -> "空白"
            }
            result.add(row to category)
        }

        return result
    }

    private fun detectCategoryBySingleChar(text: String): String? {
        val generalChars = setOf('般', '購', '買', '講', '課')
        val agriChars    = setOf('農', '機', '械', '展', '慢', '城', '来', '発', '検')
        val gasChars     = setOf('給', '油', '所', '値', '造', '治', '抽')

        for (char in text) {
            when {
                generalChars.contains(char) -> return "一般購買"
                agriChars.contains(char)    -> return "農業機械"
                gasChars.contains(char)     -> return "給油所"
            }
        }
        return null
    }

    // ============================================
    // ユーティリティ
    // ============================================

    fun normalizeToDigits(text: String): String =
        text.replace(" ", "")
            .map { normalizeDigit(it) }
            .filter { it.isDigit() }
            .joinToString("")

    private fun normalizeDigit(char: Char): Char = when (char) {
        'O', 'o', 'p' -> '0'
        'I', 'i', 'l', '|', ':' -> '1'
        'B', 'b' -> '8'
        'S' -> '5'
        else -> char
    }

    fun normalizeQuantity(raw: String): Int? =
        raw.replace("o", "0").replace("O", "0")
            .replace("l", "1").replace("I", "1")
            .replace(" ", "").replace(",", "")
            .toIntOrNull()

    /**
     * 商品名から日付パターンを除去
     */
    fun cleanItemName(itemName: String): String {
        var cleaned = itemName

        val unwantedStrings = listOf(
            "＊以下の方法にて、ご入金をお願いします。",
            "＊以下の方法にて、ご入金をお願いします",
            "*以下の方法にて、ご入金をお願いします。",
            "*以下の方法にて、ご入金をお願いします",
            "北有馬", "東南部基", "南部基幹", "南有馬"
        )
        for (unwanted in unwantedStrings) {
            if (cleaned.contains(unwanted)) {
                cleaned = cleaned.replace(unwanted, "")
            }
        }
        cleaned = cleaned.trim('|', ' ', '　')

        val patterns = listOf(
            Regex("^[a-zA-Z]{1,3}[0-9oOlIeEbBsS.:/ ]{2,8}[|]?"),
            Regex("^[0-9oOlIeEbBsS]{6}[|]?"),
            Regex("^[a-zA-Z][0-9oOlIeEbBsS]+\\s+[0-9oOlIeEbBsS]+[|]?"),
            Regex("^[a-zA-Z0-9oOlIeEbBsS.:/ |]{3,12}(?=[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF])"),
            Regex("\\s+[a-zA-Z0-9oOlIeEbBsS.:/ |]{4,12}(?=[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF])")
        )

        var previousCleaned = ""
        var iteration = 0
        while (iteration < 3 && cleaned != previousCleaned) {
            previousCleaned = cleaned
            for (pattern in patterns) {
                val result = cleaned.replace(pattern, "")
                if (result != cleaned) {
                    cleaned = result.trimStart('|', ' ', '　')
                    break
                }
            }
            iteration++
        }

        if (cleaned.isEmpty() || cleaned.length < 2) {
            cleaned = itemName
        } else if (cleaned != itemName) {
            Log.d(TAG, "cleanItemName: '$itemName' → '$cleaned'")
        }

        return cleaned
    }

    // ============================================
    // デバッグ描画
    // ============================================

    fun drawColumnRanges(image: Mat) {
        val y1 = NORMAL_ROW_Y_RANGE.first.toDouble()
        val y2 = NORMAL_ROW_Y_RANGE.last.toDouble()

        Imgproc.rectangle(image,
            Point(DATE_RANGE.first.toDouble(), y1), Point(DATE_RANGE.last.toDouble(), y2),
            Scalar(0.0, 0.0, 255.0), 2)
        Imgproc.rectangle(image,
            Point(ITEM_RANGE.first.toDouble(), y1), Point(ITEM_RANGE.last.toDouble(), y2),
            Scalar(0.0, 255.0, 0.0), 2)
        Imgproc.rectangle(image,
            Point(AMOUNT_RANGE.first.toDouble(), y1), Point(AMOUNT_RANGE.last.toDouble(), y2),
            Scalar(255.0, 0.0, 0.0), 2)
        Imgproc.rectangle(image,
            Point(CATEGORY_RANGE.first.toDouble(), y1), Point(CATEGORY_RANGE.last.toDouble(), y2),
            Scalar(0.0, 255.0, 255.0), 2)

        Log.d(TAG, "drawColumnRanges: done")
    }

    fun drawTextBoxes(image: Mat, textBoxes: List<TextBox>) {
        for (box in textBoxes) {
            val color = when (detectColumn(box.centerX)) {
                ColumnType.DATE         -> Scalar(0.0, 0.0, 255.0)
                ColumnType.ITEM         -> Scalar(0.0, 255.0, 0.0)
                ColumnType.AMOUNT       -> Scalar(255.0, 0.0, 0.0)
                ColumnType.CATEGORY_SUM -> Scalar(0.0, 255.0, 255.0)
                else                    -> Scalar(128.0, 128.0, 128.0)
            }
            Imgproc.rectangle(image,
                Point(box.bounds.left.toDouble(), box.bounds.top.toDouble()),
                Point(box.bounds.right.toDouble(), box.bounds.bottom.toDouble()),
                color, 2)
        }
        Log.d(TAG, "drawTextBoxes: ${textBoxes.size} boxes")
    }
}
