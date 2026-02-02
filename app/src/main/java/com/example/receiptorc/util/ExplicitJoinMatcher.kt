package com.example.receiptorc.util

import android.util.Log
import com.example.receiptorc.data.OcrExplicitJoin
import com.example.receiptorc.data.OcrExplicitJoinDao

/**
 * OCR明示的結合パターンマッチャー
 *
 * 分離認識されたTextBoxを既知の結合パターンに基づいて結合する。
 */
object ExplicitJoinMatcher {
    private const val TAG = "ExplicitJoinMatcher"

    /**
     * 分離されたテキストリストに対して結合パターンを適用
     *
     * @param texts 分離されたテキストリスト（X座標順）
     * @param joinDao 結合パターンDAO
     * @return 結合結果（パターンマッチした場合は結合後テキスト、しなかった場合は元のテキスト結合）
     */
    suspend fun applyJoinPatterns(
        texts: List<String>,
        joinDao: OcrExplicitJoinDao
    ): JoinResult {
        if (texts.isEmpty()) {
            return JoinResult(
                joinedText = "",
                patternUsed = null,
                originalTexts = texts
            )
        }

        if (texts.size == 1) {
            return JoinResult(
                joinedText = texts[0],
                patternUsed = null,
                originalTexts = texts
            )
        }

        // パターン検索（連続するテキストのパターンを検索）
        val normalizedPattern = OcrExplicitJoin.createNormalizedPattern(texts)
        val exactMatch = joinDao.findByPattern(normalizedPattern)

        if (exactMatch != null) {
            // 完全一致パターンが見つかった
            Log.d(TAG, "Exact pattern match: '$normalizedPattern' -> '${exactMatch.joinedText}'")
            joinDao.incrementHitCount(exactMatch.id)

            // 昇格チェック
            checkAndPromote(exactMatch, joinDao)

            return JoinResult(
                joinedText = exactMatch.joinedText,
                patternUsed = exactMatch,
                originalTexts = texts
            )
        }

        // 部分一致を試行（先頭から順にマッチング）
        val partialResult = tryPartialMatch(texts, joinDao)
        if (partialResult != null) {
            return partialResult
        }

        // パターンが見つからない場合は単純結合
        return JoinResult(
            joinedText = texts.joinToString(""),
            patternUsed = null,
            originalTexts = texts
        )
    }

    /**
     * 部分一致を試行
     *
     * 例: ["灯", "油", "18L"] に対して "灯|油" パターンがあれば
     *     "灯油" + "18L" = "灯油18L" を返す
     */
    private suspend fun tryPartialMatch(
        texts: List<String>,
        joinDao: OcrExplicitJoinDao
    ): JoinResult? {
        if (texts.size < 2) return null

        // 先頭2要素から順にパターンを探す
        for (endIdx in 2..texts.size) {
            val subTexts = texts.subList(0, endIdx)
            val pattern = OcrExplicitJoin.createNormalizedPattern(subTexts)
            val match = joinDao.findByPattern(pattern)

            if (match != null) {
                Log.d(TAG, "Partial pattern match: '$pattern' -> '${match.joinedText}'")
                joinDao.incrementHitCount(match.id)

                // 残りのテキストと結合
                val remaining = texts.subList(endIdx, texts.size)
                val joinedText = match.joinedText + remaining.joinToString("")

                checkAndPromote(match, joinDao)

                return JoinResult(
                    joinedText = joinedText,
                    patternUsed = match,
                    originalTexts = texts
                )
            }
        }

        return null
    }

    /**
     * 昇格条件をチェックして必要なら昇格
     */
    private suspend fun checkAndPromote(
        join: OcrExplicitJoin,
        joinDao: OcrExplicitJoinDao
    ) {
        val updated = join.copy(hitCount = join.hitCount + 1)

        when {
            updated.canPromoteToLocked() && join.confidenceLevel != "LOCKED" -> {
                joinDao.updateConfidenceLevel(join.id, "LOCKED")
                Log.d(TAG, "Promoted pattern ${join.id} to LOCKED")
            }
            updated.canPromoteToConfirmed() && join.confidenceLevel == "AUTO" -> {
                joinDao.updateConfidenceLevel(join.id, "CONFIRMED")
                Log.d(TAG, "Promoted pattern ${join.id} to CONFIRMED")
            }
        }
    }

    /**
     * 結合結果
     */
    data class JoinResult(
        val joinedText: String,
        val patternUsed: OcrExplicitJoin?,
        val originalTexts: List<String>
    ) {
        val wasPatternApplied: Boolean get() = patternUsed != null
    }

    /**
     * 新しい結合パターンを学習
     *
     * @param separatedTexts 分離認識されたテキストリスト
     * @param correctedText ユーザーが修正した正しいテキスト
     * @param productId 商品マスタID
     * @param joinDao 結合パターンDAO
     * @param isManual 手動修正かどうか
     */
    suspend fun learnJoinPattern(
        separatedTexts: List<String>,
        correctedText: String,
        productId: Long,
        joinDao: OcrExplicitJoinDao,
        isManual: Boolean = true
    ) {
        if (separatedTexts.size < 2) {
            Log.d(TAG, "Skip learning: less than 2 separated texts")
            return
        }

        // 分離テキストを結合したものと修正テキストを比較
        val concatenated = separatedTexts.joinToString("")
        if (concatenated != correctedText) {
            // 結合しても一致しない場合は学習しない
            // （文字自体が間違っている場合は別のシステムで対応）
            Log.d(TAG, "Skip learning: concatenated '$concatenated' != corrected '$correctedText'")
            return
        }

        val normalizedPattern = OcrExplicitJoin.createNormalizedPattern(separatedTexts)
        val existing = joinDao.findByPattern(normalizedPattern)

        if (existing != null) {
            // 既存パターンの更新
            if (isManual) {
                joinDao.incrementManualConfirmCount(existing.id)
                Log.d(TAG, "Updated manual confirm count for pattern: $normalizedPattern")

                // 昇格チェック
                val updated = existing.copy(manualConfirmCount = existing.manualConfirmCount + 1)
                when {
                    updated.canPromoteToLocked() && existing.confidenceLevel != "LOCKED" -> {
                        joinDao.updateConfidenceLevel(existing.id, "LOCKED")
                        Log.d(TAG, "Promoted pattern ${existing.id} to LOCKED")
                    }
                    updated.canPromoteToConfirmed() && existing.confidenceLevel == "AUTO" -> {
                        joinDao.updateConfidenceLevel(existing.id, "CONFIRMED")
                        Log.d(TAG, "Promoted pattern ${existing.id} to CONFIRMED")
                    }
                }
            } else {
                joinDao.incrementHitCount(existing.id)
            }
        } else {
            // 新規パターンの登録
            val newJoin = OcrExplicitJoin(
                productId = productId,
                normalizedPattern = normalizedPattern,
                joinedText = correctedText,
                originalTexts = separatedTexts.joinToString(","),  // シンプルなCSV形式
                confidenceLevel = if (isManual) "AUTO" else "AUTO",
                hitCount = 0,
                manualConfirmCount = if (isManual) 1 else 0,
                source = if (isManual) "USER" else "AUTO"
            )
            val id = joinDao.insert(newJoin)
            Log.d(TAG, "Learned new join pattern: $normalizedPattern -> $correctedText (id=$id)")
        }
    }
}
