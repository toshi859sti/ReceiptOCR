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
     * warp後のスケール（実測値）
     */
    private const val SCALE = 8.1  // px/mm

    /**
     * 行クラスタリングの閾値
     * Y座標の差がこの値以下なら同じ行とみなす
     */
    const val ROW_THRESHOLD_PX = 15

    /**
     * 列範囲（実用範囲、安全マージン込み）
     *
     * 実測値（mm）:
     * - 取引日: 5.5 - 20.0
     * - 商品名: 20.0 - 79.5
     * - 税込金額: 134.5 - 156.0
     * - 分類計: 156.0 - 177.0
     *
     * 理論px変換（8.1 px/mm）:
     * - 取引日: 45 - 162
     * - 商品名: 162 - 644
     * - 税込金額: 1090 - 1264
     * - 分類計: 1264 - 1434
     */
    private val DATE_RANGE = 50..155           // マージン: ±5-7px
    private val ITEM_RANGE = 170..620          // マージン: +8px, -24px
    private val AMOUNT_RANGE = 1110..1240      // マージン: +20px, -24px
    private val CATEGORY_RANGE = 1280..1410    // マージン: +16px, -24px

    /**
     * 小計行のY範囲（px）
     *
     * 実測値: 119.5 - 123.0 mm
     * 理論px: 968 - 996 px
     */
    private val SUBTOTAL_Y_RANGE = 968..996

    /**
     * 通常行のY範囲（px）
     *
     * 実測値: 55.0 - 119.5 mm
     * 理論px: 446 - 968 px
     */
    private val NORMAL_ROW_Y_RANGE = 446..968

    // ============================================
    // 列挙型
    // ============================================

    /**
     * 列タイプ
     */
    enum class ColumnType {
        DATE,           // 取引日（6桁数字）
        ITEM,           // 商品名（日本語）
        AMOUNT,         // 税込金額（数字、マイナス可）
        CATEGORY_SUM,   // 分類計（数字）
        SUBTOTAL        // 小計・合計（数字）
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
        val date: String?,         // 取引日（6桁、例: "060130"）
        val itemName: String?,     // 商品名
        val amount: Int?,          // 税込金額
        val categorySum: Int?      // 分類計
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
     * X座標から列タイプを判定
     *
     * @param cx テキストボックスの中心X座標
     * @return 列タイプ、または null（空白帯）
     */
    fun detectColumn(cx: Int): ColumnType? = when (cx) {
        in DATE_RANGE -> ColumnType.DATE
        in ITEM_RANGE -> ColumnType.ITEM
        in AMOUNT_RANGE -> ColumnType.AMOUNT
        in CATEGORY_RANGE -> ColumnType.CATEGORY_SUM
        else -> null  // 空白帯（79.5-134.5mm）は無視
    }

    /**
     * 小計行かどうかを判定
     *
     * @param cy テキストボックスの中心Y座標
     * @param cx テキストボックスの中心X座標
     * @return true: 小計行、false: 通常行
     */
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
     * 行クラスタリング
     *
     * Y座標が近いテキストボックスを同じ行にグループ化
     *
     * @param textBoxes テキストボックスのリスト
     * @return 行ごとにグループ化されたテキストボックス
     */
    fun clusterRows(textBoxes: List<TextBox>): List<List<TextBox>> {
        if (textBoxes.isEmpty()) {
            return emptyList()
        }

        // Y座標でソート
        val sorted = textBoxes.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<TextBox>>()

        var currentRow = mutableListOf<TextBox>()
        var lastY = -1000

        for (box in sorted) {
            // 通常行の範囲外は無視
            if (box.centerY !in NORMAL_ROW_Y_RANGE) {
                continue
            }

            if (box.centerY - lastY > ROW_THRESHOLD_PX) {
                // 新しい行を開始
                if (currentRow.isNotEmpty()) {
                    rows.add(currentRow)
                }
                currentRow = mutableListOf(box)
            } else {
                // 同じ行に追加
                currentRow.add(box)
            }
            lastY = box.centerY
        }

        // 最後の行を追加
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
        }

        Log.d(TAG, "Clustered ${textBoxes.size} boxes into ${rows.size} rows")
        return rows
    }

    /**
     * 1行のテキストボックスから ReceiptRow を生成
     *
     * @param rowBoxes 1行分のテキストボックス
     * @return ReceiptRow
     */
    fun processRow(rowBoxes: List<TextBox>): ReceiptRow {
        var date: String? = null
        val itemParts = mutableListOf<String>()
        var amount: Int? = null
        var categorySum: Int? = null

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
                    Log.d(TAG, "  DATE: ${box.text} at X=${box.centerX}")
                }
                ColumnType.ITEM -> {
                    itemParts.add(box.text)
                    Log.d(TAG, "  ITEM: ${box.text} at X=${box.centerX}")
                }
                ColumnType.AMOUNT -> {
                    amount = box.text.replace("-", "").toIntOrNull()
                    Log.d(TAG, "  AMOUNT: ${box.text} at X=${box.centerX}")
                }
                ColumnType.CATEGORY_SUM -> {
                    categorySum = box.text.toIntOrNull()
                    Log.d(TAG, "  CATEGORY_SUM: ${box.text} at X=${box.centerX}")
                }
                else -> {
                    Log.d(TAG, "  IGNORED: ${box.text} at X=${box.centerX} (空白帯)")
                }
            }
        }

        // 商品名を結合（スペース区切り）
        val itemName = if (itemParts.isNotEmpty()) {
            itemParts.joinToString(" ")
        } else {
            null
        }

        return ReceiptRow(date, itemName, amount, categorySum)
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
     * mm を px に変換
     */
    fun mmToPx(mm: Double): Int = (mm * SCALE).toInt()

    /**
     * px を mm に変換
     */
    fun pxToMm(px: Int): Double = px / SCALE
}
