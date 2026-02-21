package com.example.receiptorc.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import kotlin.math.abs

/**
 * 画像前処理ユーティリティ
 *
 * 文字高さを自動推定して最適な拡大率を決定し、OCR精度を向上させる。
 *
 * アプローチ:
 * 1. グレースケール化
 * 2. エッジ検出（縦画を拾う）
 * 3. 連結成分の高さを測定
 * 4. 中央値を「文字高さ」とみなす
 * 5. 高さに応じて拡大率を決定
 */
object ImagePreprocessor {
    private const val TAG = "ImagePreprocessor"

    /**
     * グレースケール化
     */
    fun toGray(src: Bitmap): Bitmap {
        Log.d(TAG, "toGray: input bitmap ${src.width}x${src.height}, config=${src.config}")

        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        // サンプルピクセルをチェック
        val samplePixel = Color.red(bmp.getPixel(bmp.width / 2, bmp.height / 2))
        Log.d(TAG, "toGray: output bitmap ${bmp.width}x${bmp.height}, sample pixel at center=${samplePixel}")

        return bmp
    }

    /**
     * コントラスト調整
     *
     * @param src 入力画像
     * @param contrast コントラスト値（1.0が標準、1.0より大きいと強調）
     */
    fun adjustContrast(src: Bitmap, contrast: Float): Bitmap {
        val scale = contrast
        val translate = (-.5f * scale + .5f) * 255f
        val cm = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        val bmp = Bitmap.createBitmap(src.width, src.height, src.config)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }

    /**
     * 適応的二値化（簡易版）
     */
    fun adaptiveThreshold(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = Color.red(src.getPixel(x, y))
                val v = if (c > 180) 255 else 0
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bmp
    }

    /**
     * Sobel風エッジ検出（軽量版）
     *
     * 横線より縦画を拾うので文字高さ推定に向く。
     * 閾値を15に下げて、より多くのエッジを検出（実測値に基づく調整）
     */
    fun edgeDetect(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c1 = Color.red(src.getPixel(x, y - 1))
                val c2 = Color.red(src.getPixel(x, y + 1))
                val v = abs(c2 - c1)
                val e = if (v > 15) 255 else 0  // 閾値: 25 → 15 に変更
                out.setPixel(x, y, Color.rgb(e, e, e))
            }
        }
        return out
    }

    /**
     * 連結成分から文字高さを推定
     *
     * @param edge エッジ検出済み画像
     * @return 推定された文字高さ（ピクセル）
     */
    fun estimateCharHeight(edge: Bitmap): Int {
        val w = edge.width
        val h = edge.height

        if (w <= 0 || h <= 0) {
            Log.w(TAG, "Invalid edge bitmap dimensions: ${w}x${h}")
            return 20  // デフォルト値
        }

        val heights = mutableListOf<Int>()

        // X方向にスキャン（列ごとに縦のエッジ区間を検出）
        for (x in 0 until w step 4) {  // 4ピクセルごとにサンプリング
            var start = -1
            for (y in 0 until h) {
                val v = Color.red(edge.getPixel(x, y))
                if (v > 0 && start < 0) {
                    start = y
                } else if (v == 0 && start >= 0) {
                    val height = y - start
                    if (height in 6..80) {
                        heights.add(height)
                    }
                    start = -1
                }
            }
            // 最後まで続いていた場合
            if (start >= 0) {
                val height = h - start
                if (height in 6..80) {
                    heights.add(height)
                }
            }
        }

        if (heights.isEmpty()) {
            Log.w(TAG, "No character heights detected, using default 20px")
            return 20  // デフォルト値（4K撮影時の一般的な文字サイズ）
        }

        heights.sort()
        val median = heights[heights.size / 2]

        Log.d(TAG, "Character height estimation: ${heights.size} components found, median=$median")
        return median
    }

    /**
     * 文字高さに基づいて拡大率を決定
     *
     * 判断基準:
     * - 10px以下 → 3倍拡大（必須）
     * - 15px以下 → 2倍拡大（軽く拡大）
     * - 25px以下 → 1倍（拡大しない）
     * - それ以上 → 1倍
     *
     * @param charHeight 推定文字高さ（ピクセル）
     * @return 拡大率（1, 2, 3）
     */
    fun decideScaleFactor(charHeight: Int): Int {
        val factor = when {
            charHeight <= 10 -> 3
            charHeight <= 15 -> 2
            charHeight <= 25 -> 1
            else -> 1
        }
        Log.d(TAG, "Scale factor decision: charHeight=$charHeight → scale=$factor")
        return factor
    }

    /**
     * 必要に応じて拡大
     *
     * @param src 入力画像
     * @param factor 拡大率（1の場合は拡大しない）
     * @return 拡大後の画像
     */
    fun scaleIfNeeded(src: Bitmap, factor: Int): Bitmap {
        return if (factor == 1) {
            src
        } else {
            Log.d(TAG, "Scaling image: ${src.width}x${src.height} → ${src.width * factor}x${src.height * factor} (${factor}x)")
            Bitmap.createScaledBitmap(
                src,
                src.width * factor,
                src.height * factor,
                true
            )
        }
    }

    /**
     * OCR用の前処理（文字高さ自動判定付き）
     *
     * グレースケール化 → エッジ検出 → 文字高さ推定 → 拡大率決定 → 拡大
     *
     * @param src 入力画像
     * @return 前処理後の画像
     */
    fun preprocessForOcr(src: Bitmap): Bitmap {
        val gray = toGray(src)
        val edge = edgeDetect(gray)
        val charHeight = estimateCharHeight(edge)
        val scale = decideScaleFactor(charHeight)

        Log.d(TAG, "Preprocessing for OCR: charHeight=$charHeight, scale=$scale")

        return scaleIfNeeded(gray, scale)
    }

    /**
     * 列切り出し
     *
     * @param src 入力画像
     * @param x 開始X座標
     * @param width 幅
     * @return 切り出された画像
     */
    fun cropColumn(src: Bitmap, x: Int, width: Int): Bitmap {
        return Bitmap.createBitmap(src, x, 0, width, src.height)
    }

    /**
     * 拡大（指定倍率）
     *
     * @param src 入力画像
     * @param factor 拡大率
     * @return 拡大後の画像
     */
    fun scale(src: Bitmap, factor: Int): Bitmap {
        return Bitmap.createScaledBitmap(
            src,
            src.width * factor,
            src.height * factor,
            true
        )
    }

    /**
     * 文字高さを実測（OpenCV + 輪郭検出）
     *
     * エッジ検出→膨張→輪郭抽出→高さ統計で文字高さを推定
     *
     * @param bitmap 入力画像（商品名列などのROI）
     * @return 推定文字高さ（px）、検出失敗時は0
     */
    fun estimateCharHeightPx(bitmap: Bitmap): Float {
        var src: org.opencv.core.Mat? = null
        var gray: org.opencv.core.Mat? = null
        var edges: org.opencv.core.Mat? = null
        var kernel: org.opencv.core.Mat? = null
        var hierarchy: org.opencv.core.Mat? = null
        var contours: MutableList<org.opencv.core.MatOfPoint>? = null

        try {
            // Bitmap → Mat
            src = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(bitmap, src)

            // グレースケール化
            gray = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(src, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)

            // エッジ検出（Canny）
            edges = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.Canny(gray, edges, 80.0, 160.0)

            // 膨張（文字をまとめる）
            kernel = org.opencv.imgproc.Imgproc.getStructuringElement(
                org.opencv.imgproc.Imgproc.MORPH_RECT,
                org.opencv.core.Size(3.0, 3.0)
            )
            org.opencv.imgproc.Imgproc.dilate(edges, edges, kernel)

            // 輪郭検出
            contours = mutableListOf()
            hierarchy = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                org.opencv.imgproc.Imgproc.RETR_EXTERNAL,
                org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 高さを収集
            val heights = contours!!.mapNotNull { cnt ->
                val rect = org.opencv.imgproc.Imgproc.boundingRect(cnt)
                when {
                    rect.height < 6 -> null       // ノイズ
                    rect.height > 80 -> null      // 行・罫線
                    else -> rect.height.toFloat()
                }
            }

            if (heights.isEmpty()) {
                Log.w(TAG, "estimateCharHeightPx: No valid contours found")
                return 0f
            }

            // 中央値（外れ値に強い）
            val sorted = heights.sorted()
            val median = sorted[sorted.size / 2]

            Log.d(TAG, "estimateCharHeightPx: found ${heights.size} contours, median height=${median}px")
            return median

        } catch (e: Exception) {
            Log.e(TAG, "estimateCharHeightPx: Error", e)
            return 0f
        } finally {
            // リソースを確実に解放
            src?.release()
            gray?.release()
            edges?.release()
            kernel?.release()
            hierarchy?.release()
            contours?.forEach { it.release() }
        }
    }

    /**
     * 最適拡大率を計算
     *
     * ML Kit日本語の最適域（30-40px）に収まるよう拡大率を決定
     *
     * @param currentCharPx 現在の文字高さ
     * @param targetCharPx 目標文字高さ（デフォルト32px）
     * @return 拡大率（1.0〜3.0）
     */
    fun calcScaleFactor(
        currentCharPx: Float,
        targetCharPx: Float = 32f
    ): Float {
        if (currentCharPx <= 0f) return 1f

        val rawScale = targetCharPx / currentCharPx

        // 1倍以上3倍以下に制限
        // 3倍超: 補間ノイズ地獄
        // 1倍未満: 縮小は無意味
        return rawScale.coerceIn(1.0f, 3.0f)
    }

    /**
     * 商品名列を自動スケーリング（文字高さベース）
     *
     * 文字高さを実測し、ML Kit最適域（30-40px）になるよう自動拡大
     *
     * @param itemBitmap 商品名列の画像
     * @return スケーリング後の画像
     */
    fun scaleItemColumnForOcr(itemBitmap: Bitmap): Bitmap {
        val charPx = estimateCharHeightPx(itemBitmap)
        val scale = calcScaleFactor(charPx)

        Log.d(TAG, "scaleItemColumnForOcr: charPx=${charPx}, scale=${scale}")

        if (scale == 1f) {
            Log.d(TAG, "scaleItemColumnForOcr: No scaling needed")
            return itemBitmap
        }

        val scaledBitmap = Bitmap.createScaledBitmap(
            itemBitmap,
            (itemBitmap.width * scale).toInt(),
            (itemBitmap.height * scale).toInt(),
            true  // bilinear filtering
        )

        Log.d(TAG, "scaleItemColumnForOcr: ${itemBitmap.width}x${itemBitmap.height} → ${scaledBitmap.width}x${scaledBitmap.height}")
        return scaledBitmap
    }

    /**
     * 安全なモルフォロジーOpen（ノイズ除去）
     *
     * 文字高さに連動したカーネルサイズで、点ノイズ・印刷カスを除去。
     * 文字自体は削らない安全設計。
     *
     * @param grayMat グレースケール画像（Mat）
     * @param charPx 文字高さ（ピクセル）
     * @return ノイズ除去後の画像（Mat）
     */
    fun safeMorphOpen(grayMat: org.opencv.core.Mat, charPx: Float): org.opencv.core.Mat {
        // カーネルサイズ計算（文字高さ連動）
        val k = maxOf(1, (charPx * 0.08f).toInt())
        if (k <= 1) {
            // 文字が小さい → Openしない（安全装置）
            Log.d(TAG, "safeMorphOpen: charPx=$charPx too small, skipping")
            return grayMat.clone()
        }

        // 奇数にする
        val kernelSize = if (k % 2 == 0) k + 1 else k
        val kernelSizeCapped = minOf(kernelSize, 5) // 最大5x5

        Log.d(TAG, "safeMorphOpen: charPx=$charPx, kernelSize=$kernelSizeCapped")

        val kernel = org.opencv.imgproc.Imgproc.getStructuringElement(
            org.opencv.imgproc.Imgproc.MORPH_RECT,
            org.opencv.core.Size(kernelSizeCapped.toDouble(), kernelSizeCapped.toDouble())
        )

        val result = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.morphologyEx(
            grayMat,
            result,
            org.opencv.imgproc.Imgproc.MORPH_OPEN,
            kernel,
            org.opencv.core.Point(-1.0, -1.0),
            1  // 1回のみ
        )

        kernel.release()
        return result
    }

    /**
     * 安全なAdaptive Threshold（二値化）
     *
     * 文字高さに連動したblockSizeで、局所的に二値化。
     * 条件付き適用が前提（charPx >= 18f）
     *
     * @param grayMat グレースケール画像（Mat）
     * @param charPx 文字高さ（ピクセル）
     * @return 二値化画像（Mat）
     */
    fun safeAdaptiveThreshold(grayMat: org.opencv.core.Mat, charPx: Float): org.opencv.core.Mat {
        // blockSize 計算（文字高さ × 1.8）
        var blockSize = (charPx * 1.8f).toInt()
        if (blockSize % 2 == 0) blockSize += 1
        blockSize = blockSize.coerceIn(31, 81) // 安全範囲

        val cValue = 5.0

        Log.d(TAG, "safeAdaptiveThreshold: charPx=$charPx, blockSize=$blockSize, C=$cValue")

        val binary = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.adaptiveThreshold(
            grayMat,
            binary,
            255.0,
            org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            org.opencv.imgproc.Imgproc.THRESH_BINARY,
            blockSize,
            cValue
        )

        return binary
    }

    /**
     * エッジ密度を計測
     *
     * グレーとバイナリの判定に使用
     *
     * @param grayMat グレースケール画像（Mat）
     * @return エッジ密度（0.0〜1.0）
     */
    fun calcEdgeDensity(grayMat: org.opencv.core.Mat): Double {
        var edges: org.opencv.core.Mat? = null
        try {
            edges = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.Canny(grayMat, edges, 50.0, 150.0)

            val edgeCount = org.opencv.core.Core.countNonZero(edges)
            val total = edges.rows() * edges.cols()

            val density = if (total > 0) edgeCount.toDouble() / total.toDouble() else 0.0
            Log.d(TAG, "calcEdgeDensity: $density")
            return density
        } finally {
            edges?.release()
        }
    }

    /**
     * 黒画素率を計算（二値OCR判定用）
     *
     * @param grayMat グレースケール画像
     * @return 黒画素率 0.0〜1.0
     */
    fun calcBlackRatio(grayMat: org.opencv.core.Mat): Double {
        try {
            val threshold = 128.0
            var blackCount = 0
            val total = grayMat.rows() * grayMat.cols()

            for (y in 0 until grayMat.rows()) {
                for (x in 0 until grayMat.cols()) {
                    val pixel = grayMat.get(y, x)[0]
                    if (pixel < threshold) {
                        blackCount++
                    }
                }
            }

            val ratio = blackCount.toDouble() / total.toDouble()
            Log.d(TAG, "calcBlackRatio: $ratio")
            return ratio

        } catch (e: Exception) {
            Log.e(TAG, "calcBlackRatio: Error", e)
            return 0.5
        }
    }

    /**
     * ストローク幅のばらつきを計算（二値OCR判定用）
     *
     * @param grayMat グレースケール画像
     * @return ストローク幅のばらつき 0.0〜1.0（低いほど良好）
     */
    fun calcStrokeWidthVariance(grayMat: org.opencv.core.Mat): Double {
        var edges: org.opencv.core.Mat? = null
        var hierarchy: org.opencv.core.Mat? = null
        var contours: MutableList<org.opencv.core.MatOfPoint>? = null

        try {
            // エッジ検出
            edges = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.Canny(grayMat, edges, 50.0, 150.0)

            // 輪郭検出
            contours = mutableListOf()
            hierarchy = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                org.opencv.imgproc.Imgproc.RETR_EXTERNAL,
                org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 各輪郭の幅を収集
            val widths = contours!!.mapNotNull { cnt ->
                val rect = org.opencv.imgproc.Imgproc.boundingRect(cnt)
                if (rect.width in 2..50) rect.width.toDouble() else null
            }

            if (widths.size < 3) {
                Log.d(TAG, "calcStrokeWidthVariance: Insufficient data")
                return 0.5
            }

            // 標準偏差を計算
            val mean = widths.average()
            val variance = widths.map { (it - mean) * (it - mean) }.average()
            val stdDev = kotlin.math.sqrt(variance)

            // 変動係数（CV）を計算（0.0〜1.0に正規化）
            val cv = if (mean > 0) (stdDev / mean).coerceIn(0.0, 1.0) else 0.5

            Log.d(TAG, "calcStrokeWidthVariance: $cv (mean=$mean, stdDev=$stdDev)")
            return cv

        } catch (e: Exception) {
            Log.e(TAG, "calcStrokeWidthVariance: Error", e)
            return 0.5
        } finally {
            // リソースを確実に解放
            edges?.release()
            hierarchy?.release()
            contours?.forEach { it.release() }
        }
    }

    /**
     * 二値OCR候補スコアを計算（段階A: 二値OCRを走らせるか判定）
     *
     * 設計思想:
     * - strokeWidthVarは「ハード条件」ではなく「減点要素」として使う
     * - 日本語印刷物は漢字・ひらがな・カタカナで自然にばらつきが出る
     * - 高いstrokeWidthVarは「即NG」ではなく「スコアが少し下がる」程度
     *
     * スコア関数:
     * strokePenalty =
     *   if (strokeWidthVar <= 0.3) 0.0
     *   else if (strokeWidthVar <= 0.6) 0.1
     *   else if (strokeWidthVar <= 0.9) 0.2
     *   else 0.3
     *
     * binaryCandidateScore =
     *   0.40 * charHeightNorm
     * + 0.40 * edgeDensityNorm
     * - 0.20 * strokePenalty
     *
     * @param charHeightPx 推定文字高さ
     * @param edgeDensity エッジ密度
     * @param blackRatio 黒画素率（ハード条件で使用）
     * @param strokeWidthVar ストローク幅のばらつき（減点要素）
     * @return 候補スコア 0.0〜1.0
     */
    fun calcBinaryCandidateScore(
        charHeightPx: Float,
        edgeDensity: Double,
        blackRatio: Double,
        strokeWidthVar: Double
    ): Double {
        // 各指標を正規化
        val charHeightNorm = (charHeightPx / 32f).coerceIn(0f, 1f).toDouble()
        val edgeDensityNorm = (edgeDensity / 0.1).coerceIn(0.0, 1.0)  // 0.1を最大値と仮定

        // strokeWidthVarを段階的ペナルティに変換（日本語印刷物の自然なばらつきを許容）
        val strokePenalty = when {
            strokeWidthVar <= 0.3 -> 0.0  // 理想的
            strokeWidthVar <= 0.6 -> 0.1  // 許容範囲
            strokeWidthVar <= 0.9 -> 0.2  // やや高いが試す価値あり
            else -> 0.3                    // 高い（それでも試す）
        }

        // 重み付きスコア計算
        val score = (
            0.40 * charHeightNorm +
            0.40 * edgeDensityNorm -
            0.20 * strokePenalty
        ).coerceIn(0.0, 1.0)

        Log.d(TAG, "calcBinaryCandidateScore: $score " +
            "(charH=${charHeightNorm.format(2)}, edge=${edgeDensityNorm.format(2)}, " +
            "strokePenalty=${strokePenalty.format(2)} [var=${strokeWidthVar.format(2)}])")

        return score
    }

    /**
     * 罫線除去（OCR前処理）
     *
     * 設計思想:
     * - 縦罫線は「1」に化ける元凶 → 最優先で除去
     * - 数字の縦線は短い → 残る
     * - 横罫線は必要に応じて除去
     *
     * @param grayMat グレースケール画像
     * @param removeVertical 縦罫線を除去するか
     * @param removeHorizontal 横罫線を除去するか
     * @return 罫線除去後の画像
     */
    fun removeLines(
        grayMat: org.opencv.core.Mat,
        removeVertical: Boolean = true,
        removeHorizontal: Boolean = false
    ): org.opencv.core.Mat {
        // 解放対象のMat（例外発生時に解放が必要）
        val toRelease = mutableListOf<org.opencv.core.Mat>()
        var result: org.opencv.core.Mat? = null

        try {
            result = grayMat.clone()

            // 縦罫線除去（最優先）
            if (removeVertical) {
                val verticalKernel = org.opencv.imgproc.Imgproc.getStructuringElement(
                    org.opencv.imgproc.Imgproc.MORPH_RECT,
                    org.opencv.core.Size(1.0, (grayMat.rows() * 0.6).toDouble())
                )
                toRelease.add(verticalKernel)

                val noVerticalLines = org.opencv.core.Mat()
                toRelease.add(noVerticalLines)
                org.opencv.imgproc.Imgproc.morphologyEx(
                    result,
                    noVerticalLines,
                    org.opencv.imgproc.Imgproc.MORPH_OPEN,
                    verticalKernel
                )

                val temp = org.opencv.core.Mat()
                org.opencv.core.Core.subtract(result, noVerticalLines, temp)

                result!!.release()
                result = temp

                Log.d(TAG, "removeLines: Removed vertical lines")
            }

            // 横罫線除去（必要なら）
            if (removeHorizontal) {
                val horizontalKernel = org.opencv.imgproc.Imgproc.getStructuringElement(
                    org.opencv.imgproc.Imgproc.MORPH_RECT,
                    org.opencv.core.Size((grayMat.cols() * 0.6).toDouble(), 1.0)
                )
                toRelease.add(horizontalKernel)

                val noHorizontalLines = org.opencv.core.Mat()
                toRelease.add(noHorizontalLines)
                org.opencv.imgproc.Imgproc.morphologyEx(
                    result,
                    noHorizontalLines,
                    org.opencv.imgproc.Imgproc.MORPH_OPEN,
                    horizontalKernel
                )

                val temp = org.opencv.core.Mat()
                org.opencv.core.Core.subtract(result, noHorizontalLines, temp)

                result!!.release()
                result = temp

                Log.d(TAG, "removeLines: Removed horizontal lines")
            }

            // 一時Matを解放（resultは戻り値なので解放しない）
            toRelease.forEach { it.release() }
            return result!!

        } catch (e: Exception) {
            Log.e(TAG, "removeLines: Error", e)
            // エラー時はすべて解放
            toRelease.forEach { it.release() }
            result?.release()
            return grayMat.clone()
        }
    }

    /**
     * 文字高さ推定（簡易版・数量列用）
     *
     * @param grayMat グレースケール画像
     * @return 推定文字高さ（px）
     */
    fun estimateCharHeightSimple(grayMat: org.opencv.core.Mat): Float {
        var binary: org.opencv.core.Mat? = null
        var hierarchy: org.opencv.core.Mat? = null
        var contours: MutableList<org.opencv.core.MatOfPoint>? = null

        try {
            // 軽めの二値化
            binary = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.adaptiveThreshold(
                grayMat,
                binary,
                255.0,
                org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_MEAN_C,
                org.opencv.imgproc.Imgproc.THRESH_BINARY_INV,
                31,
                5.0
            )

            // 輪郭抽出
            contours = mutableListOf()
            hierarchy = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.findContours(
                binary,
                contours,
                hierarchy,
                org.opencv.imgproc.Imgproc.RETR_EXTERNAL,
                org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 高さを収集
            val heights = contours!!.mapNotNull { cnt ->
                val rect = org.opencv.imgproc.Imgproc.boundingRect(cnt)
                if (rect.height in 8..80) rect.height.toFloat() else null
            }

            if (heights.isEmpty()) {
                Log.w(TAG, "estimateCharHeightSimple: No contours found, using default 20")
                return 20f
            }

            // 中央値
            val sorted = heights.sorted()
            val median = sorted[sorted.size / 2]

            Log.d(TAG, "estimateCharHeightSimple: median=${median}px (${heights.size} contours)")
            return median

        } catch (e: Exception) {
            Log.e(TAG, "estimateCharHeightSimple: Error", e)
            return 20f
        } finally {
            // リソースを確実に解放
            binary?.release()
            hierarchy?.release()
            contours?.forEach { it.release() }
        }
    }

    /**
     * Double値を指定桁数でフォーマット
     */
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
