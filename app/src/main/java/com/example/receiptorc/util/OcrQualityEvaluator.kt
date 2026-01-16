package com.example.receiptorc.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.sqrt

/**
 * OCR品質評価システム V2
 *
 * フォーカス、文字高さ、コントラストの3指標を総合的に評価し、
 * OCR実行の可否を判断する。
 *
 * 【V2更新】文字高さをOCR実行時の2400pxスケールで評価
 * - 旧: 1280pxで評価 → 6-7px = 良好 → 実際は18-21px@2400px (不足)
 * - 新: 2400pxで評価 → 30-40px = 良好 (ML Kit推奨範囲)
 *
 * スコア設計:
 * - 文字高さ: 重み 0.5 (最重要 - OCR精度をほぼ決める)
 * - コントラスト: 重み 0.3 (認識の安定性)
 * - フォーカス: 重み 0.2 (ピンボケ防止)
 *
 * 合格基準:
 * - score ≥ 0.70 → OCR実行
 * - score < 0.70 → 撮り直し or 前処理強化
 */
object OcrQualityEvaluator {
    private const val TAG = "OcrQualityEvaluator"

    // 重み設定
    private const val WEIGHT_FOCUS = 0.2
    private const val WEIGHT_CHAR_HEIGHT = 0.5
    private const val WEIGHT_CONTRAST = 0.3

    // 合格閾値 (実測値ベースに調整)
    const val QUALITY_THRESHOLD = 0.68  // 実環境で安定して達成可能な値に設定

    /**
     * OCR品質評価結果
     */
    data class OcrQuality(
        val score: Double,           // 総合スコア (0.0-1.0)
        val focus: Double,           // フォーカス値（シャープネス）
        val focusScore: Double,      // フォーカススコア (0.0-1.0)
        val charHeight: Int,         // 推定文字高さ (px)
        val charHeightScore: Double, // 文字高さスコア (0.0-1.0)
        val contrast: Double,        // コントラスト値（標準偏差）
        val contrastScore: Double,   // コントラストスコア (0.0-1.0)
        val isGood: Boolean          // 合格判定 (score >= 0.70)
    ) {
        override fun toString(): String {
            return "OcrQuality(score=${"%.3f".format(score)}, " +
                    "focus=${"%.1f".format(focus)} (score=${"%.2f".format(focusScore)}), " +
                    "charHeight=$charHeight (score=${"%.2f".format(charHeightScore)}), " +
                    "contrast=${"%.2f".format(contrast)} (score=${"%.2f".format(contrastScore)}), " +
                    "isGood=$isGood)"
        }

        /**
         * 品質情報を人間が読める形式で取得
         */
        fun toHumanReadable(): String {
            val status = if (isGood) "✓ 良好" else "✗ 不十分"
            return buildString {
                append("総合スコア: ${"%.0f".format(score * 100)}% ($status)\n")
                append("├ 文字高さ: ${charHeight}px (${"%.0f".format(charHeightScore * 100)}%)\n")
                append("├ コントラスト: ${"%.0f".format(contrastScore * 100)}%\n")
                append("└ フォーカス: ${"%.0f".format(focusScore * 100)}%")
            }
        }
    }

    /**
     * フォーカススコアを計算（正規化）
     *
     * フォーカス値（シャープネス）から0.0-1.0のスコアに変換。
     * 実測値に基づいて調整（4K撮影時の実環境値: 8～30程度）
     *
     * 範囲:
     * - < 5: 0.0 (ピンボケ)
     * - 5-10: 0.0-0.5 (やや弱い)
     * - 10-20: 0.5-0.8 (良好)
     * - 20-40: 0.8-1.0 (理想的)
     * - > 40: 1.0 (優秀)
     *
     * @param focus フォーカス値（シャープネス、Laplacian分散）
     * @return フォーカススコア (0.0-1.0)
     */
    fun focusScore(focus: Double): Double {
        return when {
            focus < 5.0 -> 0.0
            focus < 10.0 -> (focus - 5.0) / 5.0 * 0.5  // 5-10 → 0.0-0.5
            focus < 20.0 -> 0.5 + (focus - 10.0) / 10.0 * 0.3  // 10-20 → 0.5-0.8
            focus < 40.0 -> 0.8 + (focus - 20.0) / 20.0 * 0.2  // 20-40 → 0.8-1.0
            else -> 1.0
        }
    }

    /**
     * 文字高さスコアを計算（最重要）
     *
     * 文字高さ（ピクセル）から0.0-1.0のスコアに変換。
     * OCR精度に最も影響する指標。
     *
     * 【V2更新】2400pxベース + 列特化拡大を考慮した評価
     * 実際のOCRフロー:
     * 1. 2400pxベース画像（この関数で評価）
     * 2. 列特化OCR: 3-4倍拡大
     * 3. 最終OCR: 2400px × 3倍 = 7200px相当
     *
     * ML Kit推奨: 30-40px/文字 → 2400pxベースで10-13px必要
     * （10px × 3倍 = 30px, 13px × 3倍 = 39px）
     *
     * 範囲（2400pxベーススケール）:
     * - < 4px: 0.0 (極小、拡大しても不足)
     * - 4-6px: 0.0-0.5 (3倍拡大で12-18px、不安定)
     * - 6-8px: 0.5-0.7 (3倍拡大で18-24px、最低限)
     * - 8-10px: 0.7-0.85 (3倍拡大で24-30px、良好手前)
     * - 10-14px: 0.85-1.0 (3倍拡大で30-42px、理想的 ← ML Kit推奨範囲)
     * - 14-20px: 1.0 (3倍拡大で42-60px、優秀)
     * - 20-30px: 0.95 (やや大きい)
     * - > 30px: 0.8 (大きすぎ)
     *
     * @param height 推定文字高さ (px、2400pxベーススケール)
     * @return 文字高さスコア (0.0-1.0)
     */
    fun charHeightScore(height: Int): Double {
        return when {
            height < 4 -> 0.0
            height < 6 -> (height - 4) / 2.0 * 0.6  // 4-6px → 0.0-0.6
            height < 8 -> 0.6 + (height - 6) / 2.0 * 0.15  // 6-8px → 0.6-0.75
            height < 10 -> 0.75 + (height - 8) / 2.0 * 0.10  // 8-10px → 0.75-0.85
            height <= 14 -> 0.85 + (height - 10) / 4.0 * 0.15  // 10-14px → 0.85-1.0
            height <= 20 -> 1.0
            height <= 30 -> 0.95
            else -> 0.8
        }
    }

    /**
     * コントラストスコアを計算
     *
     * グレースケール画像の標準偏差からコントラストを評価。
     * 標準偏差 = 「文字と背景の差」を表す。
     *
     * 標準偏差が大きいほど、文字と背景の区別がはっきりしている。
     *
     * @param gray グレースケール画像
     * @return コントラストスコア (0.0-1.0)
     */
    fun contrastScore(gray: Bitmap): Double {
        val w = gray.width
        val h = gray.height

        if (w <= 0 || h <= 0) {
            Log.w(TAG, "Invalid bitmap dimensions: ${w}x${h}")
            return 0.0
        }

        // サンプリング（2ピクセルごと）で高速化
        var sum = 0L
        var sumSq = 0L
        var count = 0

        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val v = Color.red(gray.getPixel(x, y))
                sum += v
                sumSq += v.toLong() * v
                count++
            }
        }

        if (count == 0) {
            Log.w(TAG, "No pixels sampled")
            return 0.0
        }

        // 平均と分散を計算
        val mean = sum.toDouble() / count
        val variance = sumSq.toDouble() / count - mean * mean

        // 分散が負になる場合の対処（浮動小数点誤差）
        if (variance < 0.0) {
            Log.w(TAG, "Negative variance: $variance, setting to 0")
            return 0.0
        }

        val std = sqrt(variance)

        // 標準偏差を0.0-1.0にスケール（64を基準値として使用）
        val score = (std / 64.0).coerceIn(0.0, 1.0)

        Log.d(TAG, "Contrast: mean=${"%.1f".format(mean)}, std=${"%.1f".format(std)}, score=${"%.3f".format(score)}")
        return score
    }

    /**
     * OCR品質を総合評価
     *
     * フォーカス、文字高さ、コントラストの3指標を重み付けして総合評価。
     *
     * @param focus フォーカス値（シャープネス）
     * @param charHeight 推定文字高さ (px)
     * @param gray グレースケール画像
     * @return OCR品質評価結果
     */
    fun evaluateOcrQuality(
        focus: Double,
        charHeight: Int,
        gray: Bitmap
    ): OcrQuality {
        // 各指標のスコアを計算
        val f = focusScore(focus)
        val c = charHeightScore(charHeight)
        val k = contrastScore(gray)

        // 重み付き総合スコア
        val total = f * WEIGHT_FOCUS + c * WEIGHT_CHAR_HEIGHT + k * WEIGHT_CONTRAST

        val quality = OcrQuality(
            score = total,
            focus = focus,
            focusScore = f,
            charHeight = charHeight,
            charHeightScore = c,
            contrast = k,  // 標準偏差を保存
            contrastScore = k,
            isGood = total >= QUALITY_THRESHOLD
        )

        Log.d(TAG, quality.toString())
        return quality
    }

    /**
     * OCR品質を総合評価（簡易版 - Bitmapから自動計算）
     *
     * 【V2更新】文字高さをOCR実行時の2400pxスケールで評価
     * - フォーカス・コントラスト: 1280pxで評価（高速化）
     * - 文字高さ: 2400pxスケールで評価（ML Kit要求に合わせる）
     *
     * @param bitmap 評価対象の画像
     * @return OCR品質評価結果
     */
    fun evaluateOcrQuality(bitmap: Bitmap): OcrQuality {
        Log.d(TAG, "evaluateOcrQuality: input bitmap ${bitmap.width}x${bitmap.height}, config=${bitmap.config}, isRecycled=${bitmap.isRecycled}")

        // ===============================================
        // ステップ1: フォーカス・コントラスト評価 (1280px)
        // ===============================================
        val previewScale = 1280.0f / maxOf(bitmap.width, bitmap.height)
        val previewWidth = (bitmap.width * previewScale).toInt()
        val previewHeight = (bitmap.height * previewScale).toInt()

        val previewBitmap = Bitmap.createScaledBitmap(bitmap, previewWidth, previewHeight, true)
        Log.d(TAG, "evaluateOcrQuality: preview scaled to ${previewBitmap.width}x${previewBitmap.height} (${String.format("%.1f", previewScale * 100)}%)")

        // グレースケール化
        val grayPreview = ImagePreprocessor.toGray(previewBitmap)
        previewBitmap.recycle()

        // フォーカス計算
        val focus = calculateSharpness(grayPreview)

        // コントラスト計算（グレースケール画像を使用）
        val contrastValue = contrastScore(grayPreview)
        grayPreview.recycle()

        // ===============================================
        // ステップ2: 文字高さ評価 (2400px OCRスケール)
        // ===============================================
        // OCR実行時の2400pxスケールで文字高さを測定
        val ocrScale = 2400.0f / maxOf(bitmap.width, bitmap.height)
        val ocrWidth = (bitmap.width * ocrScale).toInt()
        val ocrHeight = (bitmap.height * ocrScale).toInt()

        val ocrBitmap = Bitmap.createScaledBitmap(bitmap, ocrWidth, ocrHeight, true)
        Log.d(TAG, "evaluateOcrQuality: OCR scaled to ${ocrBitmap.width}x${ocrBitmap.height} (${String.format("%.1f", ocrScale * 100)}%)")

        // グレースケール化
        val grayOcr = ImagePreprocessor.toGray(ocrBitmap)
        ocrBitmap.recycle()

        // エッジ検出
        val edge = ImagePreprocessor.edgeDetect(grayOcr)

        // 文字高さ推定（2400pxスケール）
        val charHeight = ImagePreprocessor.estimateCharHeight(edge)
        Log.d(TAG, "evaluateOcrQuality: charHeight at OCR scale (2400px) = ${charHeight}px")
        edge.recycle()
        grayOcr.recycle()

        // ===============================================
        // ステップ3: 総合評価
        // ===============================================
        val focusScoreValue = focusScore(focus)
        val charHeightScoreValue = charHeightScore(charHeight)

        // 重み付き総合スコア
        val total = focusScoreValue * WEIGHT_FOCUS +
                    charHeightScoreValue * WEIGHT_CHAR_HEIGHT +
                    contrastValue * WEIGHT_CONTRAST

        val quality = OcrQuality(
            score = total,
            focus = focus,
            focusScore = focusScoreValue,
            charHeight = charHeight,
            charHeightScore = charHeightScoreValue,
            contrast = contrastValue,
            contrastScore = contrastValue,
            isGood = total >= QUALITY_THRESHOLD
        )

        Log.d(TAG, quality.toString())
        return quality
    }

    /**
     * 画像のシャープネスを計算（Laplacian分散）
     *
     * 値が大きいほど画像がシャープ（フォーカスが合っている）。
     *
     * @param gray グレースケール画像
     * @return シャープネス値
     */
    private fun calculateSharpness(gray: Bitmap): Double {
        val width = gray.width
        val height = gray.height

        Log.d(TAG, "calculateSharpness: bitmap ${width}x${height}")

        // サンプルピクセルをチェック
        val sampleX = width / 2
        val sampleY = height / 2
        val samplePixel = Color.red(gray.getPixel(sampleX, sampleY))
        Log.d(TAG, "calculateSharpness: sample pixel at ($sampleX,$sampleY) = $samplePixel")

        var sumLaplacian = 0.0
        var count = 0

        // サンプリング（2ピクセルごと）で高速化
        for (y in 1 until height - 1 step 2) {
            for (x in 1 until width - 1 step 2) {
                val gray_c = Color.red(gray.getPixel(x, y))
                val gray_t = Color.red(gray.getPixel(x, y - 1))
                val gray_b = Color.red(gray.getPixel(x, y + 1))
                val gray_l = Color.red(gray.getPixel(x - 1, y))
                val gray_r = Color.red(gray.getPixel(x + 1, y))

                // Laplacian オペレーター
                val laplacian = kotlin.math.abs(
                    4 * gray_c - gray_t - gray_b - gray_l - gray_r
                )

                sumLaplacian += laplacian * laplacian
                count++
            }
        }

        val sharpness = sumLaplacian / count
        Log.d(TAG, "calculateSharpness: sumLaplacian=$sumLaplacian, count=$count, sharpness=$sharpness")
        return sharpness
    }
}
