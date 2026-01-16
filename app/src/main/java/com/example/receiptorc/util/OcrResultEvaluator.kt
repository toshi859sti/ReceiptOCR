package com.example.receiptorc.util

import android.util.Log
import kotlin.math.abs
import kotlin.math.min

/**
 * OCR結果評価ユーティリティ
 *
 * グレー版OCRと二値版OCRの結果を比較し、辞書マッチングを含む
 * 総合スコアで最良の結果を選択する。
 */
object OcrResultEvaluator {
    private const val TAG = "OcrResultEvaluator"

    /**
     * OCR結果データクラス
     */
    data class OcrResult(
        val text: String,
        val confidence: Float? = null,
        val source: String = "unknown",  // "gray" or "binary"
        val binaryCandidateScore: Double = 0.0  // 段階Aスコア（二値OCRのみ）
    )

    /**
     * スコア詳細
     */
    data class ScoreDetails(
        val dictMatchScore: Double,
        val editDistanceScore: Double,
        val numericScore: Double,
        val confidenceScore: Double,
        val lengthScore: Double,
        val finalScore: Double
    )

    /**
     * 辞書一致スコア（最重要）
     *
     * @param ocr OCRテキスト
     * @param dict 辞書テキスト
     * @return スコア 0.0〜1.0
     */
    private fun dictMatchScore(ocr: String, dict: String): Double {
        if (ocr == dict) return 1.0
        if (dict.contains(ocr) || ocr.contains(dict)) return 0.8

        val dist = levenshteinDistance(ocr, dict)
        val ratio = 1.0 - dist.toDouble() / maxOf(ocr.length, dict.length)

        return when {
            ratio > 0.8 -> 0.6
            ratio > 0.6 -> 0.4
            else -> 0.0
        }
    }

    /**
     * 編集距離スコア（文字崩れ検出）
     *
     * @param a 文字列A
     * @param b 文字列B
     * @return スコア 0.0〜1.0
     */
    private fun editDistanceScore(a: String, b: String): Double {
        val dist = levenshteinDistance(a, b)
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0.0

        val ratio = 1.0 - dist.toDouble() / maxLen
        return ratio.coerceIn(0.0, 1.0)
    }

    /**
     * 数字整合スコア（ml / g / kg / W-30）
     *
     * 数字・単位だけ抽出して比較
     *
     * @param ocr OCRテキスト
     * @param dict 辞書テキスト
     * @return スコア 0.0〜1.0
     */
    private fun numericScore(ocr: String, dict: String): Double {
        val numRegex = Regex("""[0-9]+(\.[0-9]+)?""")

        val oNums = numRegex.findAll(ocr).map { it.value }.toSet()
        val dNums = numRegex.findAll(dict).map { it.value }.toSet()

        if (oNums.isEmpty() && dNums.isEmpty()) return 1.0
        if (oNums.isEmpty() || dNums.isEmpty()) return 0.0

        val match = oNums.intersect(dNums).size
        return match.toDouble() / dNums.size.toDouble()
    }

    /**
     * OCR信頼度スコア（ML Kit confidence）
     *
     * @param conf 信頼度
     * @return スコア 0.0〜1.0
     */
    private fun confidenceScore(conf: Float?): Double {
        return conf?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.5
    }

    /**
     * 文字数妥当性スコア（短すぎ検出）
     *
     * @param ocr OCRテキスト
     * @param dict 辞書テキスト
     * @return スコア 0.0〜1.0
     */
    private fun lengthScore(ocr: String, dict: String): Double {
        val ratio = ocr.length.toDouble() / dict.length.toDouble()
        return when {
            ratio in 0.8..1.2 -> 1.0
            ratio in 0.6..1.4 -> 0.7
            else -> 0.3
        }
    }

    /**
     * レーベンシュタイン距離（編集距離）
     *
     * @param s1 文字列1
     * @param s2 文字列2
     * @return 編集距離
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 削除
                    dp[i][j - 1] + 1,      // 挿入
                    dp[i - 1][j - 1] + cost // 置換
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * グレーOCRスコア計算
     *
     * grayScore =
     *   0.35 * confidence
     * + 0.20 * scriptScore (常に1.0と仮定)
     * + 0.25 * dictionaryScore
     * + 0.10 * bboxConsistency (常に1.0と仮定)
     * + 0.10 * lengthScore
     *
     * @param ocrText OCRテキスト
     * @param dictText 辞書テキスト
     * @param confidence OCR信頼度
     * @return スコア詳細
     */
    fun calculateGrayScore(
        ocrText: String,
        dictText: String,
        confidence: Float? = null
    ): ScoreDetails {
        val s1 = dictMatchScore(ocrText, dictText)
        val s2 = editDistanceScore(ocrText, dictText)
        val s3 = numericScore(ocrText, dictText)
        val s4 = confidenceScore(confidence)
        val s5 = lengthScore(ocrText, dictText)

        val finalScore = (
            0.35 * s4 +  // confidence
            0.20 * 1.0 + // scriptScore (仮定)
            0.25 * s1 +  // dictionaryScore
            0.10 * 1.0 + // bboxConsistency (仮定)
            0.10 * s5    // lengthScore
        )

        return ScoreDetails(
            dictMatchScore = s1,
            editDistanceScore = s2,
            numericScore = s3,
            confidenceScore = s4,
            lengthScore = s5,
            finalScore = finalScore
        )
    }

    /**
     * 二値OCRスコア計算（辞書重視）
     *
     * binaryScore =
     *   0.30 * confidence
     * + 0.15 * scriptScore (常に1.0と仮定)
     * + 0.35 * dictionaryScore
     * + 0.10 * bboxConsistency (常に1.0と仮定)
     * + 0.10 * lengthScore
     *
     * @param ocrText OCRテキスト
     * @param dictText 辞書テキスト
     * @param confidence OCR信頼度
     * @return スコア詳細
     */
    fun calculateBinaryScore(
        ocrText: String,
        dictText: String,
        confidence: Float? = null
    ): ScoreDetails {
        val s1 = dictMatchScore(ocrText, dictText)
        val s2 = editDistanceScore(ocrText, dictText)
        val s3 = numericScore(ocrText, dictText)
        val s4 = confidenceScore(confidence)
        val s5 = lengthScore(ocrText, dictText)

        val finalScore = (
            0.30 * s4 +  // confidence
            0.15 * 1.0 + // scriptScore (仮定)
            0.35 * s1 +  // dictionaryScore (最重視)
            0.10 * 1.0 + // bboxConsistency (仮定)
            0.10 * s5    // lengthScore
        )

        return ScoreDetails(
            dictMatchScore = s1,
            editDistanceScore = s2,
            numericScore = s3,
            confidenceScore = s4,
            lengthScore = s5,
            finalScore = finalScore
        )
    }

    /**
     * 旧版: 最終スコア計算（下位互換用）
     */
    @Deprecated("Use calculateGrayScore or calculateBinaryScore")
    fun calculateFinalScore(
        ocrText: String,
        dictText: String,
        confidence: Float? = null
    ): ScoreDetails {
        return calculateGrayScore(ocrText, dictText, confidence)
    }

    /**
     * 二値 vs グレー OCR結果を比較して最良を選択（段階B: 最終決定）
     *
     * 設計思想:
     * - グレーOCRは常に主系
     * - 二値OCRは「明確に勝った場合のみ」採用
     *
     * 採用条件（すべて満たす必要あり）:
     * 1. binaryCandidateScore >= 0.6
     * 2. binary.confidence >= 0.55
     * 3. binary.dictionaryScore >= 0.5
     * 4. binaryScore >= grayScore + 0.15
     *
     * @param grayResult グレー版OCR結果
     * @param binaryResult 二値版OCR結果（nullの場合はグレーを返す）
     * @param dictText 辞書テキスト（商品マスタの正規名）
     * @return 最良のOCR結果
     */
    fun chooseBestResult(
        grayResult: OcrResult,
        binaryResult: OcrResult?,
        dictText: String
    ): OcrResult {
        // グレーOCRスコア計算
        val grayScoreDetails = calculateGrayScore(grayResult.text, dictText, grayResult.confidence)
        val grayScore = grayScoreDetails.finalScore

        Log.d(TAG, "段階B - Gray OCR: '${grayResult.text}'")
        Log.d(TAG, "  grayScore=${String.format("%.3f", grayScore)} " +
            "(conf=${String.format("%.2f", grayScoreDetails.confidenceScore)}, " +
            "dict=${String.format("%.2f", grayScoreDetails.dictMatchScore)}, " +
            "len=${String.format("%.2f", grayScoreDetails.lengthScore)})")

        // 二値OCRがない場合はグレーを返す
        if (binaryResult == null) {
            Log.d(TAG, "  → Selected: Gray (二値OCRなし)")
            return grayResult
        }

        // 二値OCRスコア計算
        val binaryScoreDetails = calculateBinaryScore(binaryResult.text, dictText, binaryResult.confidence)
        val binaryScore = binaryScoreDetails.finalScore
        val binaryCandidateScore = binaryResult.binaryCandidateScore

        Log.d(TAG, "段階B - Binary OCR: '${binaryResult.text}'")
        Log.d(TAG, "  binaryScore=${String.format("%.3f", binaryScore)} " +
            "(conf=${String.format("%.2f", binaryScoreDetails.confidenceScore)}, " +
            "dict=${String.format("%.2f", binaryScoreDetails.dictMatchScore)}, " +
            "len=${String.format("%.2f", binaryScoreDetails.lengthScore)})")
        Log.d(TAG, "  binaryCandidateScore=${String.format("%.3f", binaryCandidateScore)}")

        // 段階B: 二値OCR採用条件（すべて満たす必要あり）
        val meetsCondition1 = binaryCandidateScore >= 0.6
        val meetsCondition2 = (binaryResult.confidence ?: 0f) >= 0.55f
        val meetsCondition3 = binaryScoreDetails.dictMatchScore >= 0.5
        val meetsCondition4 = binaryScore >= grayScore + 0.15

        Log.d(TAG, "  採用条件チェック:")
        Log.d(TAG, "    ① candidateScore >= 0.6: $meetsCondition1")
        Log.d(TAG, "    ② confidence >= 0.55: $meetsCondition2")
        Log.d(TAG, "    ③ dictScore >= 0.5: $meetsCondition3")
        Log.d(TAG, "    ④ binaryScore >= grayScore + 0.15: $meetsCondition4 " +
            "(diff=${String.format("%.3f", binaryScore - grayScore)})")

        // すべての条件を満たす場合のみ二値OCRを採用
        return if (meetsCondition1 && meetsCondition2 && meetsCondition3 && meetsCondition4) {
            Log.d(TAG, "  → Selected: Binary（全条件を満たす）")
            binaryResult
        } else {
            Log.d(TAG, "  → Selected: Gray（原則: グレーOCRを優先）")
            grayResult
        }
    }

    /**
     * スコアが採用閾値を超えているか判定
     *
     * @param score 最終スコア
     * @param threshold 閾値（デフォルト0.65）
     * @return 採用可否
     */
    fun isScoreAcceptable(score: Double, threshold: Double = 0.65): Boolean {
        return score >= threshold
    }
}
