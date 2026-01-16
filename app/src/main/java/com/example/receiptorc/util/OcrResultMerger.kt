package com.example.receiptorc.util

import android.util.Log
import kotlin.math.max

/**
 * OCR結果マージユーティリティ
 *
 * 複数スケールでのOCR結果を辞書ベースのスコアリングで統合し、
 * 最も信頼性の高い結果を選択する。
 */
object OcrResultMerger {
    private const val TAG = "OcrResultMerger"

    /**
     * レーベンシュタイン距離を計算（編集距離）
     *
     * 2つの文字列間の最小編集回数を計算。
     *
     * @param a 文字列A
     * @param b 文字列B
     * @return 編集距離
     */
    fun levenshtein(a: String, b: String): Int {
        val len1 = a.length
        val len2 = b.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // 初期化
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        // DP処理
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // 削除
                    dp[i][j - 1] + 1,       // 挿入
                    dp[i - 1][j - 1] + cost // 置換
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * 辞書とのスコアを計算
     *
     * テキストと辞書内の全単語との類似度を計算し、
     * 最も高い類似度を返す。
     *
     * スコア = 1.0 - (編集距離 / 最大文字列長)
     *
     * @param text 評価対象のテキスト
     * @param dict 辞書（正解候補のリスト）
     * @return スコア（0.0〜1.0、1.0が完全一致）
     */
    fun score(text: String, dict: List<String>): Double {
        if (text.isEmpty()) return 0.0
        if (dict.isEmpty()) return 0.0

        var best = 0.0
        var bestMatch = ""

        for (d in dict) {
            val dist = levenshtein(text, d)
            val maxLength = max(text.length, d.length)
            val s = 1.0 - dist.toDouble() / maxLength
            if (s > best) {
                best = s
                bestMatch = d
            }
        }

        Log.d(TAG, "Score for '$text': $best (best match: '$bestMatch')")
        return best
    }

    /**
     * 複数のOCR結果をマージして最良の結果を選択
     *
     * 各結果を辞書とのスコアで評価し、最もスコアの高い結果を返す。
     *
     * @param results OCR結果のリスト（複数スケール）
     * @param dict 辞書（正解候補のリスト）
     * @return 最良の結果
     */
    fun mergeResults(results: List<String>, dict: List<String>): String {
        if (results.isEmpty()) return ""
        if (results.size == 1) return results[0]

        Log.d(TAG, "Merging ${results.size} OCR results with dictionary (${dict.size} entries)")

        val scored = results.map { result ->
            val s = score(result, dict)
            Pair(result, s)
        }

        val best = scored.maxByOrNull { it.second }
        if (best != null) {
            Log.d(TAG, "Best result: '${best.first}' (score: ${best.second})")
            Log.d(TAG, "All results: ${scored.joinToString { "'${it.first}' (${it.second})" }}")
        }

        return best?.first ?: results[0]
    }

    /**
     * 複数のOCR結果をマージ（辞書なし版）
     *
     * 辞書がない場合、最も長い結果を選択する。
     *
     * @param results OCR結果のリスト（複数スケール）
     * @return 最良の結果
     */
    fun mergeResultsSimple(results: List<String>): String {
        if (results.isEmpty()) return ""
        if (results.size == 1) return results[0]

        Log.d(TAG, "Merging ${results.size} OCR results (no dictionary)")

        // 最も長い結果を選択（一般的に、より多くの文字が読めた方が良い）
        val best = results.maxByOrNull { it.length } ?: results[0]

        Log.d(TAG, "Best result: '$best' (length: ${best.length})")
        Log.d(TAG, "All results: ${results.joinToString { "'$it' (${it.length})" }}")

        return best
    }

    /**
     * 数量のOCR結果をマージ
     *
     * 数字のみを抽出し、最も信頼性の高い結果を返す。
     *
     * @param results OCR結果のリスト（複数スケール）
     * @return 最良の結果（数字のみ）
     */
    fun mergeQuantityResults(results: List<String>): String {
        if (results.isEmpty()) return ""
        if (results.size == 1) return results[0]

        Log.d(TAG, "Merging ${results.size} quantity OCR results")

        // 数字のみを抽出
        val normalized = results.map { result ->
            result.replace(Regex("[^0-9]"), "")
        }.filter { it.isNotEmpty() }

        if (normalized.isEmpty()) return ""

        // 最頻値を選択（同じ数字が複数回検出されたものを優先）
        val frequency = normalized.groupingBy { it }.eachCount()
        val best = frequency.maxByOrNull { it.value }?.key ?: normalized[0]

        Log.d(TAG, "Best quantity: '$best'")
        Log.d(TAG, "Frequency: $frequency")

        return best
    }
}
