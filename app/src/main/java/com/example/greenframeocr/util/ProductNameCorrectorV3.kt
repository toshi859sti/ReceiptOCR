package com.example.greenframeocr.util

import android.util.Log
import com.example.greenframeocr.data.ConfidenceLevel
import com.example.greenframeocr.data.CorrectionLogDao
import com.example.greenframeocr.data.OcrScoreLog
import com.example.greenframeocr.data.OcrScoreLogDao
import com.example.greenframeocr.data.OcrVariant
import com.example.greenframeocr.data.OcrVariantDao
import com.example.greenframeocr.data.ProductMaster
import com.example.greenframeocr.data.ProductMasterDao
import com.example.greenframeocr.data.ScoreBreakdown
import com.example.greenframeocr.data.VariantSource
import com.example.greenframeocr.data.CorrectionLog as CorrectionLogEntity
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 商品名補正システム V3 - 低頻度利用向け再設計
 *
 * OCR学習システム設計思想:
 * ⚠ この学習は「頻度最適化」ではない
 * ⚠ 人間の最終確定のみを真実とする
 * ⚠ 時間減衰を入れたらこの設計は壊れる
 * ⚠ 手動修正も絶対視しない（autoFailCountは全レベルで有効）
 * ⚠ 同一バッチ内の重複は1回としてカウント
 *
 * 利用想定: 月1回〜年1回の低頻度利用
 *
 * 補正の3層構造:
 * - Layer 1: 確定知識（無条件適用）- LOCKED / 手動CONFIRMED
 * - Layer 2: 条件付き知識（スコア検証後に適用）- CONFIRMED（自動昇格）
 * - Layer 3: 観測データ（補正には使わない・学習素材のみ）- AUTO
 *
 * 変更履歴:
 * - 2026-01-18: 低頻度利用向けに全面再設計
 */
object ProductNameCorrectorV3 {
    private const val TAG = "ProductNameCorrectorV3"

    // ============================================
    // スコア定数（設計書準拠: 100点満点）
    // ============================================

    /** 文字類似度の最大値 */
    private const val MAX_TEXT_SIMILARITY = 60.0

    /** 先頭欠落ボーナスの最大値 */
    private const val MAX_PREFIX_BONUS = 10.0

    /** 濁点誤認識ボーナスの最大値 */
    private const val MAX_DAKUTEN_BONUS = 5.0

    /** 既存知識ボーナスの最大値 */
    private const val MAX_VARIANT_BONUS = 15.0

    /** 手動修正履歴ボーナスの最大値 */
    private const val MAX_HISTORY_BONUS = 10.0

    /** リスクペナルティの最大値 */
    private const val MAX_RISK_PENALTY = -30.0

    // ============================================
    // 判定閾値（設計書準拠）
    // ============================================

    /** 最低採用スコア（マスタ直接マッチ） */
    private const val MIN_ACCEPT = 78.0

    /** 2位との最低差分（マスタ直接マッチ） */
    private const val MIN_GAP = 12.0

    /** CONFIRMED知識の最低採用スコア */
    private const val MIN_ACCEPT_CONFIRMED = 75.0

    /** CONFIRMED知識の最低差分 */
    private const val MIN_GAP_CONFIRMED = 10.0

    /** 学習登録の最低スコア */
    private const val MIN_LEARNING_SCORE = 88.0

    /** 学習登録の最低差分 */
    private const val MIN_LEARNING_GAP = 12.0

    /** 学習登録の最低文字数 */
    private const val MIN_LEARNING_LENGTH = 3

    // ============================================
    // 結果クラス
    // ============================================

    /**
     * 補正結果
     */
    data class CorrectionResult(
        val correctedName: String,
        val productId: Long?,
        val finalScore: Double,
        val matched: Boolean,
        val reason: CorrectionReason,
        val scoreBreakdown: ScoreBreakdown?,
        val usedVariantId: Long? = null
    )

    /**
     * 補正理由
     */
    enum class CorrectionReason {
        NO_INPUT,
        NO_CANDIDATES,
        UNCONDITIONAL_VARIANT,    // Layer 1: LOCKED/手動CONFIRMED
        CONFIRMED_VARIANT,        // Layer 2: 自動CONFIRMED
        MASTER_MATCH,             // Layer 3: マスタ直接マッチ
        REJECT_SCORE_LOW,
        REJECT_GAP_INSUFFICIENT,
        REJECT_RISK_PENALTY
    }

    /**
     * スコア付き候補
     */
    private data class ScoredCandidate(
        val product: ProductMaster,
        val breakdown: ScoreBreakdown,
        val existingVariant: OcrVariant?
    )

    /**
     * 商品名の構成要素
     */
    private data class ProductParts(
        val baseName: String,
        val capacity: String,
        val unit: String
    )

    // ============================================
    // メイン入口
    // ============================================

    /**
     * 商品名補正（メイン入口）
     *
     * @param ocrRawText OCRで取得した商品名（生テキスト）
     * @param category カテゴリ（一般購買、給油所、農業機械）
     * @param productDao 商品マスタDAO
     * @param variantDao OCR誤認識パターンDAO
     * @param scoreLogDao スコアログDAO（オプション）
     * @param commitBatchId バッチID（オプション）
     * @return 補正結果
     */
    suspend fun correctProductName(
        ocrRawText: String,
        category: String,
        productDao: ProductMasterDao,
        variantDao: OcrVariantDao,
        scoreLogDao: OcrScoreLogDao? = null,
        commitBatchId: String? = null
    ): CorrectionResult {
        Log.d(TAG, "========== CORRECTION V3 START ==========")
        Log.d(TAG, "[INPUT] raw='$ocrRawText', category='$category'")

        // Step 0: 入力検証
        if (ocrRawText.isBlank()) {
            Log.d(TAG, "[RESULT] NO_INPUT")
            return createNoInputResult(ocrRawText)
        }

        val normalizedRaw = normalizeForCompare(ocrRawText)
        val ocrParts = parseProductName(ocrRawText)
        Log.d(TAG, "[PARSED] base='${ocrParts.baseName}', capacity='${ocrParts.capacity}'")

        // Step 1: Layer 1 - LOCKED / 手動CONFIRMED（無条件適用）
        val unconditionalVariant = variantDao.findUnconditionalVariant(normalizedRaw)
        if (unconditionalVariant != null) {
            val product = productDao.getById(unconditionalVariant.productId)
            if (product != null) {
                Log.d(TAG, "[LAYER1] Unconditional variant hit: ${unconditionalVariant.confidenceLevel}")
                logScore(
                    scoreLogDao,
                    ocrRawText,
                    product.id,
                    OcrScoreLog.DECISION_AUTO,
                    null,
                    commitBatchId
                )
                return CorrectionResult(
                    correctedName = product.canonicalName,
                    productId = product.id,
                    finalScore = 100.0,
                    matched = true,
                    reason = CorrectionReason.UNCONDITIONAL_VARIANT,
                    scoreBreakdown = null,
                    usedVariantId = unconditionalVariant.id
                )
            }
        }

        // Step 2: カテゴリ内商品を取得
        val allProducts = productDao.getByCategory(category)
        val validProducts = allProducts.filter { passHardConstraints(it, ocrParts) }
        Log.d(TAG, "[FILTER] ${allProducts.size} -> ${validProducts.size} products")

        if (validProducts.isEmpty()) {
            Log.d(TAG, "[RESULT] NO_CANDIDATES")
            logScore(scoreLogDao, ocrRawText, null, OcrScoreLog.DECISION_NO_MATCH, null, commitBatchId)
            return createNoCandidatesResult(ocrRawText)
        }

        // Step 3: Layer 2 - CONFIRMED（スコア検証後に適用）
        val confirmedVariants = variantDao.findAutoConfirmedVariants(normalizedRaw)
        val confirmedProductIds = confirmedVariants.map { it.productId }.toSet()
        val confirmedProducts = validProducts.filter { it.id in confirmedProductIds }

        if (confirmedProducts.isNotEmpty()) {
            val confirmedCandidates = generateAndScoreCandidates(
                normalizedRaw, ocrParts, confirmedProducts, variantDao
            ).sortedByDescending { it.breakdown.totalScore }

            if (confirmedCandidates.isNotEmpty()) {
                val top = confirmedCandidates[0]
                val second = confirmedCandidates.getOrNull(1)

                if (top.breakdown.totalScore >= MIN_ACCEPT_CONFIRMED) {
                    val gap = top.breakdown.totalScore - (second?.breakdown?.totalScore ?: 0.0)
                    if (second == null || gap >= MIN_GAP_CONFIRMED) {
                        Log.d(TAG, "[LAYER2] CONFIRMED match: score=${f(top.breakdown.totalScore)}")
                        logScore(
                            scoreLogDao,
                            ocrRawText,
                            top.product.id,
                            OcrScoreLog.DECISION_AUTO,
                            top.breakdown,
                            commitBatchId,
                            gap
                        )
                        return CorrectionResult(
                            correctedName = top.product.canonicalName,
                            productId = top.product.id,
                            finalScore = top.breakdown.totalScore,
                            matched = true,
                            reason = CorrectionReason.CONFIRMED_VARIANT,
                            scoreBreakdown = top.breakdown,
                            usedVariantId = top.existingVariant?.id
                        )
                    }
                }
            }
        }

        // Step 4: Layer 3 - マスタ直接マッチ
        val allCandidates = generateAndScoreCandidates(
            normalizedRaw, ocrParts, validProducts, variantDao
        ).sortedByDescending { it.breakdown.totalScore }

        if (allCandidates.isEmpty()) {
            Log.d(TAG, "[RESULT] NO_CANDIDATES (after scoring)")
            logScore(scoreLogDao, ocrRawText, null, OcrScoreLog.DECISION_NO_MATCH, null, commitBatchId)
            return createNoCandidatesResult(ocrRawText)
        }

        val top = allCandidates[0]
        val second = allCandidates.getOrNull(1)
        val gap = top.breakdown.totalScore - (second?.breakdown?.totalScore ?: 0.0)

        Log.d(TAG, "[LAYER3] Top: '${top.product.canonicalName}' score=${f(top.breakdown.totalScore)}")
        if (second != null) {
            Log.d(TAG, "[LAYER3] 2nd: '${second.product.canonicalName}' score=${f(second.breakdown.totalScore)}, gap=${f(gap)}")
        }

        // 判定
        val result = when {
            top.breakdown.totalScore < MIN_ACCEPT -> {
                Log.d(TAG, "[REJECT] Score too low: ${f(top.breakdown.totalScore)} < $MIN_ACCEPT")
                logScore(scoreLogDao, ocrRawText, top.product.id, OcrScoreLog.DECISION_NEED_CONFIRM, top.breakdown, commitBatchId, gap)
                CorrectionResult(
                    correctedName = ocrRawText,
                    productId = null,
                    finalScore = top.breakdown.totalScore,
                    matched = false,
                    reason = CorrectionReason.REJECT_SCORE_LOW,
                    scoreBreakdown = top.breakdown
                )
            }
            second != null && gap < MIN_GAP -> {
                Log.d(TAG, "[REJECT] Gap insufficient: ${f(gap)} < $MIN_GAP")
                logScore(scoreLogDao, ocrRawText, top.product.id, OcrScoreLog.DECISION_NEED_CONFIRM, top.breakdown, commitBatchId, gap)
                CorrectionResult(
                    correctedName = ocrRawText,
                    productId = null,
                    finalScore = top.breakdown.totalScore,
                    matched = false,
                    reason = CorrectionReason.REJECT_GAP_INSUFFICIENT,
                    scoreBreakdown = top.breakdown
                )
            }
            top.breakdown.riskPenalty <= MAX_RISK_PENALTY -> {
                Log.d(TAG, "[REJECT] Risk penalty: ${f(top.breakdown.riskPenalty)}")
                logScore(scoreLogDao, ocrRawText, top.product.id, OcrScoreLog.DECISION_NEED_CONFIRM, top.breakdown, commitBatchId, gap)
                CorrectionResult(
                    correctedName = ocrRawText,
                    productId = null,
                    finalScore = top.breakdown.totalScore,
                    matched = false,
                    reason = CorrectionReason.REJECT_RISK_PENALTY,
                    scoreBreakdown = top.breakdown
                )
            }
            else -> {
                Log.d(TAG, "[MATCH] '${ocrRawText}' -> '${top.product.canonicalName}'")
                logScore(scoreLogDao, ocrRawText, top.product.id, OcrScoreLog.DECISION_AUTO, top.breakdown, commitBatchId, gap)

                // 学習登録条件チェック
                if (shouldRegisterLearning(top.breakdown.totalScore, gap, normalizedRaw)) {
                    registerAutoLearning(variantDao, ocrRawText, normalizedRaw, top.product.id, top.breakdown.totalScore)
                }

                CorrectionResult(
                    correctedName = top.product.canonicalName,
                    productId = top.product.id,
                    finalScore = top.breakdown.totalScore,
                    matched = true,
                    reason = CorrectionReason.MASTER_MATCH,
                    scoreBreakdown = top.breakdown
                )
            }
        }

        Log.d(TAG, "========== CORRECTION V3 END ==========")
        return result
    }

    // ============================================
    // ハード制約
    // ============================================

    private fun passHardConstraints(product: ProductMaster, ocrParts: ProductParts): Boolean {
        val productParts = parseProductName(product.canonicalName)

        // 容量が両方あり、不一致ならNG
        if (ocrParts.capacity.isNotEmpty() && productParts.capacity.isNotEmpty()) {
            if (ocrParts.capacity != productParts.capacity) {
                return false
            }
        }

        return true
    }

    // ============================================
    // スコア計算
    // ============================================

    private suspend fun generateAndScoreCandidates(
        normalizedRaw: String,
        ocrParts: ProductParts,
        products: List<ProductMaster>,
        variantDao: OcrVariantDao
    ): List<ScoredCandidate> {
        val candidates = mutableListOf<ScoredCandidate>()

        for (product in products) {
            val productParts = parseProductName(product.canonicalName)
            val normalizedProduct = normalizeForCompare(productParts.baseName)

            // 既存知識を取得
            val existingVariant = variantDao.findVariantForBonus(normalizedRaw, product.id)

            // スコア計算
            val breakdown = calculateScore(normalizedRaw, normalizedProduct, existingVariant, ocrParts, productParts)

            candidates.add(ScoredCandidate(product, breakdown, existingVariant))
        }

        return candidates
    }

    /**
     * スコア計算（100点満点）
     */
    private fun calculateScore(
        normalizedRaw: String,
        normalizedProduct: String,
        existingVariant: OcrVariant?,
        ocrParts: ProductParts,
        productParts: ProductParts
    ): ScoreBreakdown {
        // 1. 文字類似度（最大60）
        val textSimilarity = calculateTextSimilarity(normalizedRaw, normalizedProduct)

        // 2. 先頭欠落ボーナス（最大10）
        val prefixBonus = calculatePrefixBonus(normalizedRaw, normalizedProduct)

        // 3. 濁点誤認識ボーナス（最大5）
        val dakutenBonus = calculateDakutenBonus(normalizedRaw, normalizedProduct)

        // 4. 既存知識ボーナス（最大15）
        val variantBonus = calculateVariantBonus(existingVariant)

        // 5. 手動修正履歴ボーナス（最大10）
        val historyBonus = calculateHistoryBonus(existingVariant)

        // 6. リスクペナルティ（最大-30）
        val riskPenalty = calculateRiskPenalty(ocrParts, productParts, normalizedRaw, normalizedProduct)

        val totalScore = textSimilarity + prefixBonus + dakutenBonus + variantBonus + historyBonus + riskPenalty

        return ScoreBreakdown(
            totalScore = totalScore.coerceIn(0.0, 100.0),
            textSimilarity = textSimilarity,
            prefixBonus = prefixBonus,
            dakutenBonus = dakutenBonus,
            variantBonus = variantBonus,
            historyBonus = historyBonus,
            riskPenalty = riskPenalty
        )
    }

    /**
     * 文字類似度（最大60）
     */
    private fun calculateTextSimilarity(raw: String, product: String): Double {
        val maxLen = max(raw.length, product.length)
        if (maxLen == 0) return 0.0

        val distance = levenshteinDistance(raw, product)
        val normalizedSimilarity = 1.0 - (distance.toDouble() / maxLen)
        return (normalizedSimilarity * MAX_TEXT_SIMILARITY).coerceIn(0.0, MAX_TEXT_SIMILARITY)
    }

    /**
     * 先頭欠落ボーナス（最大10）
     */
    private fun calculatePrefixBonus(raw: String, product: String): Double {
        // 先頭1文字欠落
        if (isPrefixDroppedMatch(raw, product, 1)) {
            return MAX_PREFIX_BONUS
        }
        // 先頭2文字欠落
        if (isPrefixDroppedMatch(raw, product, 2)) {
            return MAX_PREFIX_BONUS / 2
        }
        return 0.0
    }

    private fun isPrefixDroppedMatch(raw: String, product: String, dropCount: Int): Boolean {
        if (dropCount !in 1..2) return false
        if (product.length <= raw.length) return false
        if (product.length - raw.length != dropCount) return false

        val productWithoutPrefix = product.drop(dropCount)
        return raw == productWithoutPrefix || raw.startsWith(productWithoutPrefix.take(3))
    }

    /**
     * 濁点誤認識ボーナス（最大5）
     */
    private fun calculateDakutenBonus(raw: String, product: String): Double {
        if (raw == product) return 0.0
        if (removeDakuten(raw) == removeDakuten(product)) {
            return MAX_DAKUTEN_BONUS
        }
        return 0.0
    }

    /**
     * 既存知識ボーナス（最大15）
     */
    private fun calculateVariantBonus(existingVariant: OcrVariant?): Double {
        return when (existingVariant?.confidenceLevel) {
            ConfidenceLevel.LOCKED.name    -> MAX_VARIANT_BONUS
            ConfidenceLevel.CONFIRMED.name -> MAX_VARIANT_BONUS * 2 / 3  // 10
            ConfidenceLevel.TENTATIVE.name -> 0.0  // TENTATIVEは補正に使わない
            else -> 0.0
        }
    }

    /**
     * 手動修正履歴ボーナス（最大10）
     */
    private fun calculateHistoryBonus(existingVariant: OcrVariant?): Double {
        if (existingVariant == null) return 0.0
        return if (existingVariant.manualCorrectCount > 0) MAX_HISTORY_BONUS else 0.0
    }

    /**
     * リスクペナルティ（最大-30）
     */
    private fun calculateRiskPenalty(
        ocrParts: ProductParts,
        productParts: ProductParts,
        normalizedRaw: String,
        normalizedProduct: String
    ): Double {
        var penalty = 0.0

        // 容量違い
        if (ocrParts.capacity.isNotEmpty() && productParts.capacity.isNotEmpty() &&
            ocrParts.capacity != productParts.capacity) {
            penalty += -30.0
        }

        // 数字違い（商品名中の数字が異なる）
        val rawNumbers = extractNumbers(normalizedRaw)
        val productNumbers = extractNumbers(normalizedProduct)
        if (rawNumbers.isNotEmpty() && productNumbers.isNotEmpty() && rawNumbers != productNumbers) {
            penalty += -30.0
        }

        return penalty.coerceAtLeast(MAX_RISK_PENALTY)
    }

    private fun extractNumbers(text: String): String {
        return text.filter { it.isDigit() }
    }

    // ============================================
    // 濁点処理
    // ============================================

    private val dakutenMap = mapOf(
        'ガ' to 'カ', 'ギ' to 'キ', 'グ' to 'ク', 'ゲ' to 'ケ', 'ゴ' to 'コ',
        'ザ' to 'サ', 'ジ' to 'シ', 'ズ' to 'ス', 'ゼ' to 'セ', 'ゾ' to 'ソ',
        'ダ' to 'タ', 'ヂ' to 'チ', 'ヅ' to 'ツ', 'デ' to 'テ', 'ド' to 'ト',
        'バ' to 'ハ', 'ビ' to 'ヒ', 'ブ' to 'フ', 'ベ' to 'ヘ', 'ボ' to 'ホ',
        'パ' to 'ハ', 'ピ' to 'ヒ', 'プ' to 'フ', 'ペ' to 'ヘ', 'ポ' to 'ホ',
        'が' to 'か', 'ぎ' to 'き', 'ぐ' to 'く', 'げ' to 'け', 'ご' to 'こ',
        'ざ' to 'さ', 'じ' to 'し', 'ず' to 'す', 'ぜ' to 'せ', 'ぞ' to 'そ',
        'だ' to 'た', 'ぢ' to 'ち', 'づ' to 'つ', 'で' to 'て', 'ど' to 'と',
        'ば' to 'は', 'び' to 'ひ', 'ぶ' to 'ふ', 'べ' to 'へ', 'ぼ' to 'ほ',
        'ぱ' to 'は', 'ぴ' to 'ひ', 'ぷ' to 'ふ', 'ぺ' to 'へ', 'ぽ' to 'ほ'
    )

    private fun removeDakuten(text: String): String {
        return text.map { dakutenMap[it] ?: it }.joinToString("")
    }

    // ============================================
    // 正規化
    // ============================================

    /**
     * 比較用正規化
     *
     * 処理内容:
     * 1. 空白除去
     * 2. 記号除去
     * 3. 末尾の単独英字除去（OCRゴミ対策: 「灯油H」→「灯油」）
     * 4. 先頭の単独英字除去（OCRゴミ対策: 「p灯油」→「灯油」）
     * 5. 全角英数を半角に正規化
     */
    private fun normalizeForCompare(text: String): String {
        return text
            .replace(Regex("[\\s　]"), "")  // 空白除去
            .replace(Regex("[^一-龯ぁ-んァ-ンa-zA-Z0-9]"), "")  // 記号除去
            .replace(Regex("[a-zA-Z]$"), "")  // 末尾の単独英字除去
            .replace(Regex("^[a-zA-Z]"), "")  // 先頭の単独英字除去
            .map { normalizeChar(it) }
            .joinToString("")
    }

    private fun normalizeChar(c: Char): Char {
        // 全角英数を半角に
        return when {
            c in 'Ａ'..'Ｚ' -> (c.code - 0xFEE0).toChar()
            c in 'ａ'..'ｚ' -> (c.code - 0xFEE0).toChar()
            c in '０'..'９' -> (c.code - 0xFEE0).toChar()
            else -> c
        }
    }

    // ============================================
    // ユーティリティ
    // ============================================

    private fun parseProductName(name: String): ProductParts {
        val capacityPattern = Regex("""(\d+(?:\.\d+)?)(g|ｇ|kg|ｋｇ|ml|ｍｌ|mℓ|ｍℓ|L|ℓ|Ｌ|cc|ｃｃ|ＣＣ|ML|ＭＬ|個|本|枚|袋|缶)$""")
        val match = capacityPattern.find(name)

        return if (match != null) {
            val capacity = match.groupValues[1]
            val unit = match.groupValues[2]
            val baseName = name.removeSuffix(match.value).trim()
            ProductParts(baseName, capacity, unit)
        } else {
            ProductParts(name, "", "")
        }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[a.length][b.length]
    }

    private fun f(score: Double): String = "%.1f".format(score)

    // ============================================
    // 学習登録
    // ============================================

    private fun shouldRegisterLearning(score: Double, gap: Double, normalizedText: String): Boolean {
        return score >= MIN_LEARNING_SCORE &&
               gap >= MIN_LEARNING_GAP &&
               normalizedText.length >= MIN_LEARNING_LENGTH
    }

    private suspend fun registerAutoLearning(
        variantDao: OcrVariantDao,
        rawText: String,
        normalizedText: String,
        productId: Long,
        score: Double
    ) {
        try {
            variantDao.registerLearning(
                variantText = rawText,
                normalizedText = normalizedText,
                productId = productId,
                finalScore = score / 100.0,  // 0-1スケールに変換
                source = VariantSource.SYSTEM
            )
            Log.d(TAG, "[LEARN] Registered: '$normalizedText' -> productId=$productId")
        } catch (e: Exception) {
            Log.e(TAG, "[LEARN] Failed: ${e.message}")
        }
    }

    // ============================================
    // スコアログ
    // ============================================

    private suspend fun logScore(
        scoreLogDao: OcrScoreLogDao?,
        rawOcrText: String,
        candidateProductId: Long?,
        decision: String,
        breakdown: ScoreBreakdown?,
        commitBatchId: String?,
        gapToSecond: Double? = null
    ) {
        if (scoreLogDao == null) return

        try {
            scoreLogDao.insert(OcrScoreLog(
                rawOcrText = rawOcrText,
                candidateProductId = candidateProductId,
                decision = decision,
                totalScore = breakdown?.totalScore,
                textSimilarity = breakdown?.textSimilarity,
                prefixBonus = breakdown?.prefixBonus,
                dakutenBonus = breakdown?.dakutenBonus,
                variantBonus = breakdown?.variantBonus,
                historyBonus = breakdown?.historyBonus,
                riskPenalty = breakdown?.riskPenalty,
                gapToSecond = gapToSecond,
                commitBatchId = commitBatchId
            ))
        } catch (e: Exception) {
            Log.e(TAG, "[LOG] Failed to save score log: ${e.message}")
        }
    }

    // ============================================
    // 結果生成ヘルパー
    // ============================================

    private fun createNoInputResult(rawText: String): CorrectionResult {
        return CorrectionResult(
            correctedName = rawText,
            productId = null,
            finalScore = 0.0,
            matched = false,
            reason = CorrectionReason.NO_INPUT,
            scoreBreakdown = null
        )
    }

    private fun createNoCandidatesResult(rawText: String): CorrectionResult {
        return CorrectionResult(
            correctedName = rawText,
            productId = null,
            finalScore = 0.0,
            matched = false,
            reason = CorrectionReason.NO_CANDIDATES,
            scoreBreakdown = null
        )
    }

    // ============================================
    // 互換性維持用（旧API）
    // ============================================

    /**
     * 旧API互換: CorrectionLogDaoを使用する場合
     */
    suspend fun correctProductName(
        ocrRawText: String,
        category: String,
        productDao: ProductMasterDao,
        variantDao: OcrVariantDao,
        correctionLogDao: CorrectionLogDao?,
        sessionId: String?
    ): CorrectionResult {
        // 新APIを呼び出し（scoreLogDaoはnull）
        return correctProductName(
            ocrRawText = ocrRawText,
            category = category,
            productDao = productDao,
            variantDao = variantDao,
            scoreLogDao = null,
            commitBatchId = sessionId
        )
    }
}
