package com.example.greenframeocr.util

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * OCR処理ユーティリティクラス
 * ML Kit Text Recognition を使用
 */
object OCRProcessor {
    private const val TAG = "OCRProcessor"

    // 日本語モデル（商品名など日本語テキスト用）
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    // Latinモデル（数字・記号に特化、日付列などに使用）
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 画像からテキストを認識（日本語モデル）
     */
    suspend fun recognizeText(bitmap: Bitmap): Text? {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(inputImage).await()
            Log.d(TAG, "Recognized text (Japanese): ${result.text}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing text", e)
            null
        }
    }

    /**
     * 画像からテキストを認識（Latinモデル - 数字・記号に強い）
     */
    suspend fun recognizeTextLatin(bitmap: Bitmap): Text? {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = latinRecognizer.process(inputImage).await()
            Log.d(TAG, "Recognized text (Latin): ${result.text}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing text with Latin model", e)
            null
        }
    }

    /**
     * リソースのクリーンアップ
     */
    fun close() {
        recognizer.close()
        latinRecognizer.close()
    }
}
