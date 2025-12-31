package com.example.receiptorc.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.sqrt

/**
 * OCR品質評価システム
 *
 * フォーカス、文字高さ、コントラストの3指標を総合的に評価し、
 * OCR実行の可否を判断する。
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

    // 合格閾値 (OCR精度最優先)
    const val QUALITY_THRESHOLD = 0.70  // 高品質な画像のみを撮影（OCR精度最優先）

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
     * 【重要】このスコアは1280px品質評価用にダウンスケールされた画像での文字高さを想定
     * スケール比: 1280px / 3264px ≈ 0.39
     *
     * 範囲（1280pxスケール）:
     * - < 3px: 0.0 (小さすぎ、読めない)
     * - 3px: 0.4 (最小限)
     * - 4-5px: 0.4-0.7 (遠距離撮影で許容可能)
     * - 6-7px: 0.7-0.85 (良好) ← 通常の撮影距離
     * - 8-14px: 0.85-1.0 (理想的)
     * - 15-20px: 0.8 (やや大きい)
     * - > 20px: 0.6 (大きすぎ、OCRが苦手)
     *
     * @param height 推定文字高さ (px、1280pxスケール)
     * @return 文字高さスコア (0.0-1.0)
     */
    fun charHeightScore(height: Int): Double {
        return when {
            height < 3 -> 0.0
            height < 4 -> 0.4
            height < 6 -> 0.4 + (height - 4) / 2.0 * 0.3  // 4-5px → 0.4-0.7
            height < 8 -> 0.7 + (height - 6) / 2.0 * 0.15  // 6-7px → 0.7-0.85
            height <= 14 -> 0.85 + (height - 8) / 6.0 * 0.15  // 8-14px → 0.85-1.0
            height <= 20 -> 0.8
            else -> 0.6
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
     * @param bitmap 評価対象の画像
     * @return OCR品質評価結果
     */
    fun evaluateOcrQuality(bitmap: Bitmap): OcrQuality {
        Log.d(TAG, "evaluateOcrQuality: input bitmap ${bitmap.width}x${bitmap.height}, config=${bitmap.config}, isRecycled=${bitmap.isRecycled}")

        // ダウンスケール（高速化のため）: 4K → 1280px
        // アスペクト比を維持しながら、長辺を1280pxに制限
        val maxDimension = 1280
        val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        Log.d(TAG, "evaluateOcrQuality: downscaled to ${scaledBitmap.width}x${scaledBitmap.height} (${String.format("%.1f", scale * 100)}%)")

        // サンプルピクセルをチェック（ダウンスケール後）
        if (!scaledBitmap.isRecycled) {
            val samplePixel = scaledBitmap.getPixel(scaledBitmap.width / 2, scaledBitmap.height / 2)
            val r = Color.red(samplePixel)
            val g = Color.green(samplePixel)
            val b = Color.blue(samplePixel)
            Log.d(TAG, "evaluateOcrQuality: sample pixel at center = RGB($r, $g, $b)")
        }

        // グレースケール化
        val gray = ImagePreprocessor.toGray(scaledBitmap)
        scaledBitmap.recycle()  // ダウンスケール画像を解放

        // エッジ検出
        val edge = ImagePreprocessor.edgeDetect(gray)

        // 文字高さ推定
        val charHeight = ImagePreprocessor.estimateCharHeight(edge)
        edge.recycle()

        // フォーカス計算（簡易的にLaplacian分散を計算）
        val focus = calculateSharpness(gray)

        // 総合評価
        val quality = evaluateOcrQuality(focus, charHeight, gray)
        gray.recycle()

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
