package com.example.receiptorc.util

import android.graphics.Rect
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * 下に敷くタイプの台紙用OCR処理クラス
 *
 * 実測値ベース設計:
 * - warp後スケール: 8.1 px/mm
 * - 座標原点: 伝票左上 (0,0)
 * - 安全マージン考慮（手置きズレ、罫線侵入、warp誤差）
 * - 行クラスタリング（Y座標、閾値15px）
 * - 正規表現検証
 */
object UnderlyingBaseProcessor {
    private const val TAG = "UnderlyingBaseProcessor"

    // ============================================
    // 定数定義
    // ============================================

    /**
     * 行クラスタリングの閾値
     * 実測値: 64.5mm / 20行 ≈ 3.2mm/行 ≈ 25px/行
     * 閾値は行高の半分程度（12-15px）が最適
     */
    const val ROW_CLUSTERING_Y_THRESHOLD = 15  // 別行を分離

    /**
     * 伝票位置（mm、A4左上原点）
     */
    private const val RECEIPT_LEFT_MM = 43.0   // A4左端から伝票左端まで
    private const val RECEIPT_TOP_MM = 31.0    // A4上端から伝票上端まで

    /**
     * 列範囲（mm単位、A4左上原点、伝票左上原点からの実測値ベース）
     * 伝票左端43mm + 各列位置
     */
    private const val DATE_START_MM = RECEIPT_LEFT_MM + 6.0        // 49.0mm
    private const val DATE_END_MM = RECEIPT_LEFT_MM + 20.5         // 63.5mm
    private const val ITEM_START_MM = RECEIPT_LEFT_MM + 20.5       // 63.5mm
    private const val ITEM_END_MM = RECEIPT_LEFT_MM + 80.0         // 123.0mm
    private const val STORE_START_MM = RECEIPT_LEFT_MM + 80.0      // 123.0mm (取扱支店、無視)
    private const val STORE_END_MM = RECEIPT_LEFT_MM + 135.0       // 178.0mm
    private const val QUANTITY_START_MM = RECEIPT_LEFT_MM + 80.0   // 123.0mm (数量、計測不要範囲内)
    private const val QUANTITY_END_MM = RECEIPT_LEFT_MM + 135.0    // 178.0mm
    private const val UNITPRICE_START_MM = RECEIPT_LEFT_MM + 80.0  // 123.0mm (税込単価、無視)
    private const val UNITPRICE_END_MM = RECEIPT_LEFT_MM + 135.0   // 178.0mm
    private const val AMOUNT_START_MM = RECEIPT_LEFT_MM + 135.0    // 178.0mm (税込金額)
    private const val AMOUNT_END_MM = RECEIPT_LEFT_MM + 156.0      // 199.0mm
    private const val CATEGORY_START_MM = RECEIPT_LEFT_MM + 156.0  // 199.0mm (分類計)
    private const val CATEGORY_END_MM = RECEIPT_LEFT_MM + 177.5    // 220.5mm

    /**
     * Y座標範囲（mm単位、A4左上原点、伝票左上原点からの実測値ベース）
     * 伝票上端31mm + 各行位置
     */
    private const val NORMAL_ROW_Y_START_MM = RECEIPT_TOP_MM + 56.5   // 87.5mm（通常行・小計行）
    private const val NORMAL_ROW_Y_END_MM = RECEIPT_TOP_MM + 120.5    // 151.5mm（通常行・小計行）
    private const val SUBTOTAL_Y_START_MM = RECEIPT_TOP_MM + 56.5     // 87.5mm（小計行も通常行と同じ範囲）
    private const val SUBTOTAL_Y_END_MM = RECEIPT_TOP_MM + 120.5      // 151.5mm（小計行も通常行と同じ範囲）
    private const val MONTHLY_TOTAL_Y_START_MM = RECEIPT_TOP_MM + 121.0 // 152.0mm（合計欄、実測値Y=2138pxに対応）
    private const val MONTHLY_TOTAL_Y_END_MM = RECEIPT_TOP_MM + 126.0   // 157.0mm（合計欄、範囲を拡大）

    /**
     * 列範囲（px単位、実行時に初期化）
     */
    private var DATE_RANGE: IntRange = 50..155
    private var ITEM_RANGE: IntRange = 170..620
    private var STORE_RANGE: IntRange = 620..780       // 取扱支店（無視）
    private var QUANTITY_RANGE: IntRange = 780..950    // 数量（取得）
    private var UNITPRICE_RANGE: IntRange = 950..1110  // 税込単価（無視）
    private var AMOUNT_RANGE: IntRange = 1110..1240
    private var CATEGORY_RANGE: IntRange = 1280..1410

    /**
     * Y座標範囲（px単位、実行時に初期化）
     */
    private var SUBTOTAL_Y_RANGE: IntRange = 968..996
    private var NORMAL_ROW_Y_RANGE: IntRange = 446..968
    private var MONTHLY_TOTAL_Y_RANGE: IntRange = 1320..1530

    // ============================================
    // 列挙型
    // ============================================

    /**
     * 列タイプ
     */
    enum class ColumnType {
        DATE,           // 取引日（6桁数字）
        ITEM,           // 商品名（日本語）
        QUANTITY,       // 数量（数字）
        AMOUNT,         // 税込金額（数字、マイナス可）
        CATEGORY_SUM,   // 分類計（数字）
        SUBTOTAL        // 小計・合計（数字）
    }

    /**
     * 行タイプ
     */
    enum class RowType {
        NORMAL,         // 通常の取引行
        SUBTOTAL,       // 小計行（一般購買、給油所、農業機械）
        MONTHLY_TOTAL,  // 月合計行
        EMPTY           // 空白行
    }

    // ============================================
    // データクラス
    // ============================================

    /**
     * テキストボックス
     * ML KitのOCR結果から生成
     */
    data class TextBox(
        val text: String,
        val bounds: Rect,
        val centerX: Int,
        val centerY: Int
    )

    /**
     * 伝票の1行
     */
    data class ReceiptRow(
        val rowType: RowType,      // 行タイプ
        val date: String?,         // 取引日（6桁、例: "060130"）
        val itemName: String?,     // 商品名
        val quantity: String?,     // 数量
        val amount: Int?,          // 税込金額
        val categorySum: Int?,     // 分類計
        val rawText: String? = null  // 行全体のテキスト（デバッグ用）
    )

    /**
     * 小計データ
     */
    data class SubtotalData(
        val value: Int,            // 小計値
        val centerX: Int,          // X座標
        val centerY: Int           // Y座標
    )

    // ============================================
    // 公開関数
    // ============================================

    /**
     * 列範囲を初期化（透視変換で計算されたmm->px比率を使用）
     *
     * @param mmToPixelRatio mm→px変換率（例: 13.2 px/mm）
     */
    fun initializeColumnRanges(mmToPixelRatio: Double) {
        DATE_RANGE = (DATE_START_MM * mmToPixelRatio).toInt()..(DATE_END_MM * mmToPixelRatio).toInt()
        ITEM_RANGE = (ITEM_START_MM * mmToPixelRatio).toInt()..(ITEM_END_MM * mmToPixelRatio).toInt()
        STORE_RANGE = (STORE_START_MM * mmToPixelRatio).toInt()..(STORE_END_MM * mmToPixelRatio).toInt()
        QUANTITY_RANGE = (QUANTITY_START_MM * mmToPixelRatio).toInt()..(QUANTITY_END_MM * mmToPixelRatio).toInt()
        UNITPRICE_RANGE = (UNITPRICE_START_MM * mmToPixelRatio).toInt()..(UNITPRICE_END_MM * mmToPixelRatio).toInt()
        AMOUNT_RANGE = (AMOUNT_START_MM * mmToPixelRatio).toInt()..(AMOUNT_END_MM * mmToPixelRatio).toInt()
        CATEGORY_RANGE = (CATEGORY_START_MM * mmToPixelRatio).toInt()..(CATEGORY_END_MM * mmToPixelRatio).toInt()

        NORMAL_ROW_Y_RANGE = (NORMAL_ROW_Y_START_MM * mmToPixelRatio).toInt()..(NORMAL_ROW_Y_END_MM * mmToPixelRatio).toInt()
        SUBTOTAL_Y_RANGE = (SUBTOTAL_Y_START_MM * mmToPixelRatio).toInt()..(SUBTOTAL_Y_END_MM * mmToPixelRatio).toInt()
        MONTHLY_TOTAL_Y_RANGE = (MONTHLY_TOTAL_Y_START_MM * mmToPixelRatio).toInt()..(MONTHLY_TOTAL_Y_END_MM * mmToPixelRatio).toInt()

        Log.d(TAG, "Column ranges initialized with mmToPixelRatio=$mmToPixelRatio")
        Log.d(TAG, "  DATE_RANGE: $DATE_RANGE")
        Log.d(TAG, "  ITEM_RANGE: $ITEM_RANGE")
        Log.d(TAG, "  STORE_RANGE: $STORE_RANGE (無視)")
        Log.d(TAG, "  QUANTITY_RANGE: $QUANTITY_RANGE")
        Log.d(TAG, "  UNITPRICE_RANGE: $UNITPRICE_RANGE (無視)")
        Log.d(TAG, "  AMOUNT_RANGE: $AMOUNT_RANGE")
        Log.d(TAG, "  CATEGORY_RANGE: $CATEGORY_RANGE")
        Log.d(TAG, "  NORMAL_ROW_Y_RANGE: $NORMAL_ROW_Y_RANGE")
        Log.d(TAG, "  SUBTOTAL_Y_RANGE: $SUBTOTAL_Y_RANGE")
        Log.d(TAG, "  MONTHLY_TOTAL_Y_RANGE: $MONTHLY_TOTAL_Y_RANGE")
    }

    /**
     * 有効な商品行のY座標範囲を取得
     * @return Y座標範囲（px）
     */
    fun getNormalRowYRange(): IntRange = NORMAL_ROW_Y_RANGE

    /**
     * 合計行のY座標範囲を取得
     * @return Y座標範囲（px）
     */
    fun getMonthlyTotalYRange(): IntRange = MONTHLY_TOTAL_Y_RANGE

    /**
     * 有効な行のY座標範囲を取得（通常行+合計行）
     * @return Y座標範囲（px）
     */
    fun getValidRowYRange(): IntRange = NORMAL_ROW_Y_RANGE.first..MONTHLY_TOTAL_Y_RANGE.last

    /**
     * 数量列のX座標範囲を取得
     * @return X座標範囲（px）
     */
    fun getQuantityRange(): IntRange = QUANTITY_RANGE

    /**
     * 商品名列のX座標範囲を取得
     * @return X座標範囲（px）
     */
    fun getItemRange(): IntRange = ITEM_RANGE

    /**
     * X座標から列タイプを判定
     *
     * @param cx テキストボックスの中心X座標
     * @return 列タイプ、または null（無視する列）
     */
    fun detectColumn(cx: Int): ColumnType? = when (cx) {
        in STORE_RANGE -> null  // 取扱支店（無視）
        in UNITPRICE_RANGE -> null  // 税込単価（無視）
        in DATE_RANGE -> ColumnType.DATE
        in ITEM_RANGE -> ColumnType.ITEM
        in QUANTITY_RANGE -> ColumnType.QUANTITY
        in AMOUNT_RANGE -> ColumnType.AMOUNT
        in CATEGORY_RANGE -> ColumnType.CATEGORY_SUM
        else -> null  // その他の範囲外
    }

    /**
     * ノイズ除去（空文字、記号のみ、極小bbox）
     *
     * @param textBoxes テキストボックスのリスト
     * @return フィルタリングされたテキストボックス
     */
    fun filterNoise(textBoxes: List<TextBox>): List<TextBox> {
        val filtered = textBoxes.filter { box ->
            // 空文字を除外
            if (box.text.isBlank()) return@filter false

            // 極小bbox（幅・高さが5px未満）を除外
            val width = box.bounds.width()
            val height = box.bounds.height()
            if (width < 5 || height < 5) return@filter false

            // 記号のみ（※、＊、｜など）を除外
            val onlySymbols = box.text.matches(Regex("^[※＊｜\\s]+$"))
            if (onlySymbols) return@filter false

            true
        }

        Log.d(TAG, "Noise filtering: ${textBoxes.size} → ${filtered.size} boxes")
        return filtered
    }

    /**
     * 行タイプを判定（通常/小計/月合計/空白）
     *
     * 行が確定してから判定する（TextBox単位ではなく、行単位）
     *
     * @param rowBoxes 1行分のテキストボックス
     * @return 行タイプ
     */
    fun detectRowType(rowBoxes: List<TextBox>): RowType {
        if (rowBoxes.isEmpty()) {
            return RowType.EMPTY
        }

        // 行全体のテキストを結合
        val rowText = rowBoxes.joinToString(" ") { it.text }

        // 行のY座標（代表値として最初のボックスのY座標を使用）
        val rowY = rowBoxes.firstOrNull()?.centerY ?: 0

        // 小計判定（「小計」「一般購買」「給油所」「農業機械」などのキーワード + Y座標範囲）
        val subtotalKeywords = listOf("小計", "一般購買", "給油所", "農業機械")
        if (subtotalKeywords.any { rowText.contains(it) } && rowY in SUBTOTAL_Y_RANGE) {
            Log.d(TAG, "  Row type: SUBTOTAL (text='$rowText', Y=$rowY)")
            return RowType.SUBTOTAL
        }

        // 月合計判定
        // 条件1: 「月合計」「合計」などのキーワード + Y座標範囲
        // 条件2: Y座標範囲内 + 5-6桁の数値 + フッターキーワード（「※」「以下」「入金」など）
        val monthlyTotalKeywords = listOf("月合計", "合　計", "合計", "税込", "合計（税込）")
        val hasMonthlyTotalKeyword = monthlyTotalKeywords.any { rowText.contains(it) }
        val hasMoney = rowText.matches(Regex(".*\\d{5,6}.*"))  // 5-6桁の数値を含む
        val hasFooterKeyword = listOf("※", "以下", "入金").any { rowText.contains(it) }  // フッター関連キーワード

        if (rowY in MONTHLY_TOTAL_Y_RANGE) {
            // キーワードがあるか、または数値とフッターキーワードの両方がある場合
            if (hasMonthlyTotalKeyword || (hasMoney && hasFooterKeyword)) {
                Log.d(TAG, "  Row type: MONTHLY_TOTAL (text='$rowText', Y=$rowY, keyword=$hasMonthlyTotalKeyword, money=$hasMoney, footer=$hasFooterKeyword)")
                return RowType.MONTHLY_TOTAL
            }
        }

        // 通常行
        Log.d(TAG, "  Row type: NORMAL (Y=$rowY)")
        return RowType.NORMAL
    }

    /**
     * 小計行かどうかを判定（旧版、互換性のため残す）
     *
     * @deprecated 行タイプ判定は detectRowType() を使用してください
     */
    @Deprecated("Use detectRowType() instead")
    fun isSubtotalRow(cy: Int, cx: Int): Boolean {
        return cy in SUBTOTAL_Y_RANGE && cx in CATEGORY_RANGE
    }

    /**
     * テキストパターンから列タイプを検証
     *
     * X座標ベースの判定を補完するために使用
     *
     * @param text テキスト
     * @return 列タイプ、または null（パターンマッチしない）
     */
    fun validateByPattern(text: String): ColumnType? {
        return when {
            // 6桁数字 → 取引日確定
            text.matches(Regex("^\\d{6}$")) -> ColumnType.DATE

            // 数字のみ（マイナス可） → 金額系（X座標で判別）
            text.matches(Regex("^-?\\d+$")) -> null

            // それ以外 → 商品名（ただしX座標も確認）
            else -> null
        }
    }

    /**
     * 行クラスタリング（Y座標主軸方式）
     *
     * Y座標を基準に物理的な行構造を使ってグループ化
     * 日付の誤認識に依存しないため、より安定
     *
     * @param textBoxes テキストボックスのリスト
     * @return 行ごとにグループ化されたテキストボックス
     */
    fun clusterRows(textBoxes: List<TextBox>): List<List<TextBox>> {
        if (textBoxes.isEmpty()) {
            return emptyList()
        }

        Log.d(TAG, "Starting Y-based row clustering with ${textBoxes.size} text boxes")

        // ステップ1: 全TextBoxをcenterYでソート
        val sortedBoxes = textBoxes.sortedBy { it.centerY }
        Log.d(TAG, "Sorted ${sortedBoxes.size} boxes by Y coordinate")

        // ステップ2: Y座標差でクラスタ化
        val rows = mutableListOf<MutableList<TextBox>>()
        var currentRow = mutableListOf<TextBox>()
        var lastY = sortedBoxes[0].centerY

        for (box in sortedBoxes) {
            val yDiff = kotlin.math.abs(box.centerY - lastY)

            if (yDiff <= ROW_CLUSTERING_Y_THRESHOLD) {
                // 同じ行
                currentRow.add(box)
            } else {
                // 新しい行
                if (currentRow.isNotEmpty()) {
                    rows.add(currentRow)
                }
                currentRow = mutableListOf(box)
                lastY = box.centerY
            }
        }

        // 最後の行を追加
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
        }

        Log.d(TAG, "Clustered into ${rows.size} rows using Y-threshold=${ROW_CLUSTERING_Y_THRESHOLD}px")

        // ステップ3: 各行の情報をログ出力
        rows.forEachIndexed { index, row ->
            val avgY = row.map { it.centerY }.average().toInt()
            val texts = row.map { it.text }.joinToString(", ")
            Log.d(TAG, "  Row $index (Y=$avgY): ${row.size} boxes - [$texts]")
        }

        return rows
    }

    /**
     * 1行のテキストボックスから ReceiptRow を生成
     *
     * @param rowBoxes 1行分のテキストボックス
     * @param rowType 行タイプ
     * @return ReceiptRow
     */
    fun processRow(rowBoxes: List<TextBox>, rowType: RowType): ReceiptRow {
        // 行全体のテキスト（デバッグ用）
        val rawText = rowBoxes.joinToString(" ") { it.text }

        // 行タイプに応じた処理
        return when (rowType) {
            RowType.SUBTOTAL -> processSubtotalRow(rowBoxes, rawText)
            RowType.MONTHLY_TOTAL -> processMonthlyTotalRow(rowBoxes, rawText)
            RowType.EMPTY -> ReceiptRow(rowType, null, null, null, null, null, rawText)
            RowType.NORMAL -> processNormalRow(rowBoxes, rawText)
        }
    }

    /**
     * 通常行の処理
     */
    private fun processNormalRow(rowBoxes: List<TextBox>, rawText: String): ReceiptRow {
        var date: String? = null
        val itemParts = mutableListOf<String>()
        var quantity: String? = null
        var amount: Int? = null
        var categorySum: Int? = null

        // 全ボックスをX座標でソートして詳細ログ
        val sortedBoxes = rowBoxes.sortedBy { it.centerX }
        Log.d(TAG, "  Processing ${sortedBoxes.size} boxes (sorted by X):")
        sortedBoxes.forEach { box ->
            val column = detectColumn(box.centerX)
            Log.d(TAG, "    [X=${box.centerX}] '${box.text}' → column=$column")
        }

        for (box in rowBoxes) {
            // X座標で列判定
            val column = detectColumn(box.centerX)

            // 正規表現で検証
            val validated = validateByPattern(box.text)

            // 最終的な列タイプ
            val finalColumn = validated ?: column

            when (finalColumn) {
                ColumnType.DATE -> {
                    date = box.text
                    Log.d(TAG, "  ✓ DATE: ${box.text} at X=${box.centerX}")
                }
                ColumnType.ITEM -> {
                    // 商品名は列特化OCR（3倍拡大）で取得するため、全体OCRは無視
                    Log.d(TAG, "  ⊘ ITEM (SKIPPED): ${box.text} at X=${box.centerX} (use column OCR instead)")
                }
                ColumnType.QUANTITY -> {
                    // スペースとカンマを除去
                    quantity = box.text.replace(" ", "").replace(",", "")
                    Log.d(TAG, "  ✓ QUANTITY: ${box.text} → normalized: $quantity at X=${box.centerX}")
                }
                ColumnType.AMOUNT -> {
                    // マイナス記号の有無を確認
                    val isNegative = box.text.contains("-")
                    // 数字専用正規化を適用してOCR誤認識を修正
                    val normalized = normalizeToDigits(box.text)
                    val value = normalized.toIntOrNull()
                    amount = if (isNegative && value != null) -value else value
                    Log.d(TAG, "  ✓ AMOUNT: ${box.text} → normalized: ${normalized} (${if (isNegative) "negative" else "positive"}) at X=${box.centerX}")
                }
                ColumnType.CATEGORY_SUM -> {
                    // スペースとカンマを除去してから数値に変換
                    val normalized = box.text.replace(" ", "").replace(",", "")
                    categorySum = normalized.toIntOrNull()
                    Log.d(TAG, "  ✓ CATEGORY_SUM: ${box.text} → normalized: ${normalized} at X=${box.centerX}")
                }
                else -> {
                    Log.d(TAG, "  ✗ IGNORED: ${box.text} at X=${box.centerX} (列範囲外)")
                }
            }
        }

        // 商品名は列特化OCRで取得するため、ここではnull
        // （後のStep 8.5で上書きされる）
        val itemName: String? = null

        return ReceiptRow(RowType.NORMAL, date, itemName, quantity, amount, categorySum, rawText)
    }

    /**
     * 小計行の処理
     */
    private fun processSubtotalRow(rowBoxes: List<TextBox>, rawText: String): ReceiptRow {
        // 小計行は商品名列に「＊小計（カテゴリ名）」が入る
        val itemParts = mutableListOf<String>()
        var categorySum: Int? = null

        for (box in rowBoxes) {
            val column = detectColumn(box.centerX)

            when (column) {
                ColumnType.ITEM -> {
                    itemParts.add(box.text)
                }
                ColumnType.CATEGORY_SUM -> {
                    // 数字専用正規化を適用してOCR誤認識を修正
                    val normalized = normalizeToDigits(box.text)
                    categorySum = normalized.toIntOrNull()
                    Log.d(TAG, "  SUBTOTAL AMOUNT: ${box.text} → normalized: ${normalized}")
                }
                else -> {}
            }
        }

        val itemName = if (itemParts.isNotEmpty()) {
            itemParts.joinToString(" ")
        } else {
            null
        }

        return ReceiptRow(RowType.SUBTOTAL, null, itemName, null, null, categorySum, rawText)
    }

    /**
     * 月合計行の処理
     */
    private fun processMonthlyTotalRow(rowBoxes: List<TextBox>, rawText: String): ReceiptRow {
        // 月合計行は分類計列に金額が入る
        var categorySum: Int? = null

        for (box in rowBoxes) {
            val column = detectColumn(box.centerX)

            when (column) {
                ColumnType.CATEGORY_SUM -> {
                    categorySum = box.text.toIntOrNull()
                    Log.d(TAG, "  MONTHLY_TOTAL AMOUNT: ${box.text}")
                }
                else -> {}
            }
        }

        return ReceiptRow(RowType.MONTHLY_TOTAL, null, "月合計", null, null, categorySum, rawText)
    }

    /**
     * 小計データを抽出
     *
     * @param textBoxes テキストボックスのリスト
     * @return 小計データのリスト
     */
    fun extractSubtotals(textBoxes: List<TextBox>): List<SubtotalData> {
        return textBoxes
            .filter { isSubtotalRow(it.centerY, it.centerX) }
            .mapNotNull { box ->
                val value = box.text.toIntOrNull()
                if (value != null) {
                    SubtotalData(value, box.centerX, box.centerY)
                } else {
                    null
                }
            }
    }

    // ============================================
    // デバッグ用関数
    // ============================================

    /**
     * 列範囲を画像上に描画（デバッグ用）
     *
     * @param image warp後の画像（Mat）
     */
    fun drawColumnRanges(image: Mat) {
        val y1 = NORMAL_ROW_Y_RANGE.first.toDouble()
        val y2 = NORMAL_ROW_Y_RANGE.last.toDouble()

        // 取引日（赤）
        Imgproc.rectangle(
            image,
            Point(DATE_RANGE.first.toDouble(), y1),
            Point(DATE_RANGE.last.toDouble(), y2),
            Scalar(0.0, 0.0, 255.0), 2
        )

        // 商品名（緑）
        Imgproc.rectangle(
            image,
            Point(ITEM_RANGE.first.toDouble(), y1),
            Point(ITEM_RANGE.last.toDouble(), y2),
            Scalar(0.0, 255.0, 0.0), 2
        )

        // 税込金額（青）
        Imgproc.rectangle(
            image,
            Point(AMOUNT_RANGE.first.toDouble(), y1),
            Point(AMOUNT_RANGE.last.toDouble(), y2),
            Scalar(255.0, 0.0, 0.0), 2
        )

        // 分類計（黄）
        Imgproc.rectangle(
            image,
            Point(CATEGORY_RANGE.first.toDouble(), y1),
            Point(CATEGORY_RANGE.last.toDouble(), y2),
            Scalar(0.0, 255.0, 255.0), 2
        )

        // 小計行（白）
        Imgproc.rectangle(
            image,
            Point(CATEGORY_RANGE.first.toDouble(), SUBTOTAL_Y_RANGE.first.toDouble()),
            Point(CATEGORY_RANGE.last.toDouble(), SUBTOTAL_Y_RANGE.last.toDouble()),
            Scalar(255.0, 255.0, 255.0), 3
        )

        Log.d(TAG, "Column ranges drawn")
    }

    /**
     * テキストボックスを画像上に描画（デバッグ用）
     *
     * @param image warp後の画像（Mat）
     * @param textBoxes テキストボックスのリスト
     */
    fun drawTextBoxes(image: Mat, textBoxes: List<TextBox>) {
        for (box in textBoxes) {
            val column = detectColumn(box.centerX)
            val color = when (column) {
                ColumnType.DATE -> Scalar(0.0, 0.0, 255.0)          // 赤
                ColumnType.ITEM -> Scalar(0.0, 255.0, 0.0)          // 緑
                ColumnType.AMOUNT -> Scalar(255.0, 0.0, 0.0)        // 青
                ColumnType.CATEGORY_SUM -> Scalar(0.0, 255.0, 255.0)  // 黄
                else -> Scalar(128.0, 128.0, 128.0)                 // 灰（空白帯）
            }

            // 矩形を描画
            Imgproc.rectangle(
                image,
                Point(box.bounds.left.toDouble(), box.bounds.top.toDouble()),
                Point(box.bounds.right.toDouble(), box.bounds.bottom.toDouble()),
                color, 2
            )

            // 中心点を描画
            Imgproc.circle(
                image,
                Point(box.centerX.toDouble(), box.centerY.toDouble()),
                5, color, -1
            )
        }

        Log.d(TAG, "Drew ${textBoxes.size} text boxes")
    }

    /**
     * 行クラスタを画像上に描画（デバッグ用）
     *
     * @param image warp後の画像（Mat）
     * @param rows 行ごとにグループ化されたテキストボックス
     */
    fun drawRowClusters(image: Mat, rows: List<List<TextBox>>) {
        val colors = listOf(
            Scalar(255.0, 0.0, 0.0),      // 赤
            Scalar(0.0, 255.0, 0.0),      // 緑
            Scalar(0.0, 0.0, 255.0),      // 青
            Scalar(255.0, 255.0, 0.0),    // シアン
            Scalar(255.0, 0.0, 255.0),    // マゼンタ
            Scalar(0.0, 255.0, 255.0)     // 黄
        )

        rows.forEachIndexed { rowIndex, rowBoxes ->
            val color = colors[rowIndex % colors.size]
            val avgY = rowBoxes.map { it.centerY }.average().toInt()

            // 行全体に横線を描画
            Imgproc.line(
                image,
                Point(0.0, avgY.toDouble()),
                Point(image.cols().toDouble(), avgY.toDouble()),
                color, 2
            )
        }

        Log.d(TAG, "Drew ${rows.size} row clusters")
    }

    // ============================================
    // ユーティリティ関数
    // ============================================

    /**
     * 数字専用正規化（1文字）
     *
     * OCR誤認識を修正:
     * - O, o, p → 0
     * - I, i, l, |, : → 1
     * - B, b → 8
     * - S → 5
     *
     * @param char 正規化する文字
     * @return 正規化後の文字
     */
    private fun normalizeDigit(char: Char): Char = when (char) {
        'O', 'o', 'p' -> '0'
        'I', 'i', 'l', '|', ':' -> '1'
        'B', 'b' -> '8'
        'S' -> '5'
        else -> char
    }

    /**
     * 数字専用正規化（文字列）
     *
     * テキストから数字のみを抽出し、正規化を適用
     * 空白も削除
     *
     * @param text 正規化する文字列
     * @return 正規化後の数字のみの文字列
     */
    fun normalizeToDigits(text: String): String {
        return text
            .replace(" ", "")  // 空白を削除
            .map { normalizeDigit(it) }
            .filter { it.isDigit() }
            .joinToString("")
    }

    /**
     * 数量テキストを正規化して数値に変換
     *
     * OCR誤認識を修正:
     * - o, O → 0
     * - l, I → 1
     * - スペース、カンマ削除
     *
     * @param raw OCRで取得した生テキスト
     * @return 正規化後の数値、または null（変換失敗）
     */
    fun normalizeQuantity(raw: String): Int? {
        val normalized = raw
            .replace("o", "0")
            .replace("O", "0")
            .replace("l", "1")
            .replace("I", "1")
            .replace(" ", "")
            .replace(",", "")

        return normalized.toIntOrNull()
    }

    /**
     * 商品名から日付パターンを除去
     *
     * OCRで商品名に日付が混入している場合（例: "p71008米用紙袋"）に
     * 日付部分を除去して商品名のみを返す
     *
     * パターン例:
     * - "p71008米用紙袋" → "米用紙袋"
     * - "071011トミーネクサス" → "トミーネクサス"
     * - "b71021|グレーシア乳剤" → "グレーシア乳剤"
     * - "p7101 1トミーネクサス" → "トミーネクサス" (スペース混入)
     * - "p71o17レギュラー" → "レギュラー" (文字混入)
     *
     * @param itemName 元の商品名
     * @return 日付を除去した商品名
     */
    fun cleanItemName(itemName: String): String {
        var cleaned = itemName

        // 不要な文字列を最初に除去（合計欄と同じ行に出現する文字列）
        val unwantedStrings = listOf(
            "＊以下の方法にて、ご入金をお願いします。",
            "＊以下の方法にて、ご入金をお願いします",
            "*以下の方法にて、ご入金をお願いします。",
            "*以下の方法にて、ご入金をお願いします"
        )

        for (unwanted in unwantedStrings) {
            if (cleaned.contains(unwanted)) {
                cleaned = cleaned.replace(unwanted, "")
                Log.d(TAG, "  Removed unwanted string: '$unwanted'")
            }
        }

        // 前後の空白・区切り文字を除去
        cleaned = cleaned.trim('|', ' ', '　')

        var previousCleaned = ""
        var iteration = 0
        val maxIterations = 3  // 無限ループ防止

        // 複数の日付パターンを試行（優先順位順）
        // E/eを8の誤認識として扱う（他の誤認識: O→0, I→1, B→8, S→5）
        val patterns = listOf(
            // パターン1: アルファベット1-3文字 + 数字/誤認識文字2-8文字 + 区切り文字
            // 例: "j13|", "i16|", "p7062o|", "b70621|", "p70E01"
            Regex("^[a-zA-Z]{1,3}[0-9oOlIeEbBsS.:/ ]{2,8}[|]?"),

            // パターン2: 純粋な6桁数字 + 区切り文字（E/e含む）
            // 例: "070620|", "971011|", "07E621"
            Regex("^[0-9oOlIeEbBsS]{6}[|]?"),

            // パターン3: アルファベット + スペース + 数字 + 区切り文字
            // 例: "i1 6|", "p7101 1|", "e12 1"
            Regex("^[a-zA-Z][0-9oOlIeEbBsS]+\\s+[0-9oOlIeEbBsS]+[|]?"),

            // パターン4: より緩いパターン（最大12文字まで、日本語直前）
            // 例: "p7o6i16|", "1p70618|", "0708i19NK"
            Regex("^[a-zA-Z0-9oOlIeEbBsS.:/ |]{3,12}(?=[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF])"),

            // パターン5: 途中の日付パターン（スペース後）
            // 例: "テーブナー針 0708|12その他生産資材"
            Regex("\\s+[a-zA-Z0-9oOlIeEbBsS.:/ |]{4,12}(?=[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF])")
        )

        // 変化がなくなるまで繰り返し適用（複数の日付パターンが連続している場合に対応）
        while (iteration < maxIterations && cleaned != previousCleaned) {
            previousCleaned = cleaned

            for ((index, pattern) in patterns.withIndex()) {
                val result = cleaned.replace(pattern, "")
                if (result != cleaned) {
                    cleaned = result.trimStart('|', ' ', '　')
                    Log.d(TAG, "  Iteration ${iteration + 1}, Pattern ${index + 1}: '$previousCleaned' → '$cleaned'")
                    break  // 1つマッチしたら次のイテレーションへ
                }
            }

            iteration++
        }

        // 除去後が空または短すぎる場合は元の文字列を返す
        if (cleaned.isEmpty() || cleaned.length < 2) {
            cleaned = itemName
            Log.d(TAG, "  Item name too short after cleaning, keeping original: '$itemName'")
        } else if (cleaned != itemName) {
            Log.d(TAG, "  Item name cleaned (${iteration} iterations): '$itemName' → '$cleaned'")
        }

        return cleaned
    }

    /**
     * 小計行から逆算してカテゴリを判定
     *
     * 小計行に含まれるキーワードからカテゴリを特定し、
     * その前の通常行にカテゴリを遡って適用：
     * - "一般購買" → "一般購買"
     * - "給油所", "給値所" → "給油所" (OCR誤認識対応)
     * - "農業機械", "展業慢城", "農業" → "農業機械" (OCR誤認識対応)
     *
     * @param rows 全行データ（NORMAL, SUBTOTAL, MONTHLY_TOTAL）
     * @return カテゴリ付き行データ（各行にカテゴリを設定）
     */
    fun assignCategories(rows: List<ReceiptRow>): List<Pair<ReceiptRow, String>> {
        // 1. 小計行のインデックスとカテゴリを抽出
        val subtotalIndices = mutableListOf<Pair<Int, String>>()

        rows.forEachIndexed { index, row ->
            if (row.rowType == RowType.SUBTOTAL) {
                val categoryName = row.itemName ?: ""
                val category = when {
                    // 一般購買の誤認識パターン
                    categoryName.contains("一般購買") ||
                    categoryName.contains("一般買") ||   // "購" 欠落
                    categoryName.contains("一般講買") ||  // "購" → "講"
                    categoryName.contains("一般課買") ||  // "購" → "課"
                    categoryName.contains("ー般講買") ||  // "一" → "ー", "購" → "講"
                    categoryName.contains("ー般購買") ||  // "一" → "ー"
                    categoryName.contains("般購買") ||    // "一" 欠落
                    categoryName.contains("般講買") ->    // "一" 欠落, "購" → "講"
                        "一般購買"

                    // 給油所の誤認識パターン
                    categoryName.contains("給油所") ||
                    categoryName.contains("給値所") ||  // "油" → "値"
                    categoryName.contains("給造所") ||  // "油" → "造"
                    categoryName.contains("給治所") ||  // "油" → "治"
                    categoryName.contains("給抽所") ->  // "油" → "抽"
                        "給油所"

                    // 農業機械の誤認識パターン
                    categoryName.contains("農業機械") ||
                    categoryName.contains("展業慢城") ||
                    categoryName.contains("農来") ||  // 大幅に省略されたパターン
                    categoryName.contains("農発検") ||  // 別の誤認識パターン
                    categoryName.contains("農業") -> "農業機械"

                    // パターンマッチ失敗時は一文字検出を試行
                    else -> detectCategoryBySingleChar(categoryName) ?: "未分類"
                }
                subtotalIndices.add(Pair(index, category))
                Log.d(TAG, "Subtotal found at index $index: $categoryName → category: $category")
            }
        }

        // 2. 各行にカテゴリを適用（新ロジック）
        // 最初の小計行が出るまでは「未分類」
        // 最初の小計行より後は「未定」（2枚目以降で判明）
        val result = mutableListOf<Pair<ReceiptRow, String>>()

        // 最初の小計行のインデックスを探す（カテゴリに関係なく）
        val firstSubtotalIndex = subtotalIndices.firstOrNull()?.first

        rows.forEachIndexed { index, row ->
            val category = when (row.rowType) {
                RowType.SUBTOTAL -> {
                    // 小計行自身のカテゴリ
                    subtotalIndices.find { it.first == index }?.second ?: "未分類"
                }
                RowType.NORMAL -> {
                    // 通常行のカテゴリ判定
                    if (firstSubtotalIndex == null) {
                        // 小計行がない場合、すべて「未分類」
                        "未分類"
                    } else if (index < firstSubtotalIndex) {
                        // 最初の小計より前：「未分類」
                        "未分類"
                    } else {
                        // 最初の小計より後：「未定」（2枚目以降で判明）
                        "未定"
                    }
                }
                RowType.MONTHLY_TOTAL -> "月合計"
                RowType.EMPTY -> "空白"
            }

            result.add(Pair(row, category))
        }

        // 3. カテゴリ別の行数をログ出力
        val categoryCount = result.groupingBy { it.second }.eachCount()

        if (subtotalIndices.isEmpty()) {
            Log.d(TAG, "No subtotal rows found. Using default category: 未分類")
        }

        categoryCount.forEach { (category, count) ->
            Log.d(TAG, "Category '$category': $count rows")
        }

        return result
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
