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
}
