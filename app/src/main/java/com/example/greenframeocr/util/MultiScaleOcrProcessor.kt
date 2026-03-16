package com.example.greenframeocr.util

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * 複数スケールOCR処理
 *
 * 列タイプに応じて最適なスケールでOCRを実行し、結果をマージする。
 *
 * 戦略:
 * - 商品名列（日本語）: 等倍/2倍/3倍 → 辞書スコアで最良を選択
 * - 数量列（数字）: 4倍 → Latin OCR → 最頻値を選択
 */
object MultiScaleOcrProcessor {
    private const val TAG = "MultiScaleOcrProcessor"

    // ML Kit日本語OCR
    private val japaneseRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    // ML Kit Latin OCR（数字・アルファベット）
    private val latinRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    /**
     * 日本語OCR（単一スケール）
     *
     * @param bitmap 入力画像
     * @return OCR結果テキスト
     */
    suspend fun ocrJapanese(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = japaneseRecognizer.process(image).await()
        val text = result.text.trim()
        Log.d(TAG, "Japanese OCR result (${bitmap.width}x${bitmap.height}): '$text'")
        return text
    }

    /**
     * Latin OCR（単一スケール）
     *
     * @param bitmap 入力画像
     * @return OCR結果テキスト
     */
    suspend fun ocrLatin(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = latinRecognizer.process(image).await()
        val text = result.text.trim()
        Log.d(TAG, "Latin OCR result (${bitmap.width}x${bitmap.height}): '$text'")
        return text
    }

    /**
     * 商品名列の自動スケーリングOCR
     *
     * 戦略:
     * 1. グレースケール + コントラスト調整
     * 2. 文字高さを実測してML Kit最適域（30-40px）になるよう自動拡大
     * 3. OCR実行（1回のみ、無駄な試行なし）
     *
     * @param columnBitmap 列の画像
     * @param dictionary 商品名辞書（使用しない、互換性のため残す）
     * @return OCR結果
     */
    suspend fun ocrProductNameMultiScale(
        columnBitmap: Bitmap,
        dictionary: List<String> = emptyList()
    ): String {
        Log.d(TAG, "Product name adaptive OCR: ${columnBitmap.width}x${columnBitmap.height}")

        var gray: Bitmap? = null
        var enhanced: Bitmap? = null
        var scaled: Bitmap? = null

        try {
            // 前処理: グレースケール + コントラスト調整
            gray = ImagePreprocessor.toGray(columnBitmap)
            enhanced = ImagePreprocessor.adjustContrast(gray, 1.2f)

            // 文字高さベースの自動スケーリング（ML Kit最適域: 30-40px）
            scaled = ImagePreprocessor.scaleItemColumnForOcr(enhanced)

            // OCR実行（1回のみ）
            val result = ocrJapanese(scaled!!)

            Log.d(TAG, "Product name OCR result: '$result'")
            return result
        } finally {
            // リソースを確実に解放
            gray?.recycle()
            enhanced?.recycle()
            if (scaled !== enhanced) {
                scaled?.recycle()
            }
        }
    }

    /**
     * 数量列のOCR（4倍拡大）
     *
     * 戦略:
     * 1. グレースケール化
     * 2. 4倍拡大（数字は線が単純なので拡大に耐える）
     * 3. Latin OCR
     * 4. 数字のみを抽出
     *
     * @param columnBitmap 列の画像
     * @return OCR結果（数字のみ）
     */
    suspend fun ocrQuantity(columnBitmap: Bitmap): String {
        Log.d(TAG, "Quantity OCR: ${columnBitmap.width}x${columnBitmap.height}")

        var gray: Bitmap? = null
        var scaled4x: Bitmap? = null

        try {
            // 前処理: グレースケール化
            gray = ImagePreprocessor.toGray(columnBitmap)

            // 4倍拡大
            scaled4x = ImagePreprocessor.scale(gray, 4)

            // Latin OCR
            val result = ocrLatin(scaled4x!!)

            // 数字のみを抽出
            val digitsOnly = result.replace(Regex("[^0-9]"), "")

            Log.d(TAG, "Quantity result: '$result' → digits: '$digitsOnly'")
            return digitsOnly
        } finally {
            // リソースを確実に解放
            gray?.recycle()
            scaled4x?.recycle()
        }
    }

    /**
     * 商品名列の自動判定OCR
     *
     * 文字高さを自動推定して最適なスケールを決定し、
     * 必要に応じて複数スケールOCRを実行する。
     *
     * @param columnBitmap 列の画像
     * @param dictionary 商品名辞書
     * @return OCR結果
     */
    suspend fun ocrProductNameAuto(
        columnBitmap: Bitmap,
        dictionary: List<String> = emptyList()
    ): String {
        Log.d(TAG, "Product name auto OCR: ${columnBitmap.width}x${columnBitmap.height}")

        var gray: Bitmap? = null
        var edge: Bitmap? = null
        var enhanced: Bitmap? = null

        try {
            // 文字高さ推定
            gray = ImagePreprocessor.toGray(columnBitmap)
            edge = ImagePreprocessor.edgeDetect(gray)
            val charHeight = ImagePreprocessor.estimateCharHeight(edge!!)
            val recommendedScale = ImagePreprocessor.decideScaleFactor(charHeight)

            Log.d(TAG, "Auto OCR: charHeight=$charHeight, recommendedScale=$recommendedScale")

            // 推奨スケールが1なら等倍のみ、2以上なら複数スケール
            return if (recommendedScale == 1) {
                // 高解像度なので等倍でOK
                enhanced = ImagePreprocessor.adjustContrast(gray, 1.2f)
                ocrJapanese(enhanced!!)
            } else {
                // 低解像度なので複数スケールで試す
                ocrProductNameMultiScale(columnBitmap, dictionary)
            }
        } finally {
            // リソースを確実に解放
            gray?.recycle()
            edge?.recycle()
            enhanced?.recycle()
        }
    }

    /**
     * リソース解放
     */
    fun release() {
        japaneseRecognizer.close()
        latinRecognizer.close()
        Log.d(TAG, "OCR recognizers released")
    }
}
