package com.example.greenframeocr.util

import android.util.Log
import com.example.greenframeocr.data.ProductMasterDao
import com.example.greenframeocr.data.OcrVariantDao
import kotlin.math.max
import kotlin.math.min

/**
 * 商品名補正システム V2
 *
 * 特徴:
 * - ブランド名重視のスコアリング（45%）
 * - 剤型揺れ対応（30%）
 * - 編集距離は補助（15%）
 * - OCR信頼度補正（10%）
 *
 * 従来の編集距離中心から「意味の芯」を重視する設計に変更
 */
object ProductNameCorrectorV2 {
    private const val TAG = "ProductNameCorrectorV2"

    /**
     * 補正採用閾値
     */
    private const val AUTO_CORRECT_THRESHOLD = 0.75
    private const val CANDIDATE_THRESHOLD = 0.60

    /**
     * 補正結果
     */
    data class CorrectionResult(
        val correctedName: String,
        val score: Double,
        val matched: Boolean,
        val similarity: Float = score.toFloat(),
        val details: String = ""
    )

    /**
     * 商品名の構成要素
     */
    private data class ProductParts(
        val baseName: String,
        val capacity: String
    )

    /**
     * 剤型揺れマップ（OCR誤認識パターン）
     *
     * 正規剤型 → OCR誤認識パターンのSet
     */
    private val formulationMap = mapOf(
        "顆粒" to setOf("類粒", "類立", "顆立", "粒", "状", "拉粒", "顆里"),
        "水和剤" to setOf("水初剤", "水和則", "水初則", "水和財", "水和到"),
        "乳剤" to setOf("刺", "乱", "乳", "孚剤", "乹剤"),
        "液剤" to setOf("液", "波", "浪", "液則", "液到"),
        "粉剤" to setOf("枌剤", "粉則", "粉到", "粉"),
        "フロアブル" to setOf("フロアフル", "フロブル", "フロフル", "7ロアブル", "フ口アブル"),
        "粒剤" to setOf("粒則", "粒到", "粒"),
        "乳油" to setOf("刺油", "乳由", "乳抽"),
        "溶剤" to setOf("溶則", "溶到", "溶財"),
        "粒状" to setOf("粒伏", "粒状物", "粒伏状")
    )

    /**
     * 商品名補正（メイン入口）
     *
     * @param ocrName OCRで取得した商品名
     * @param category カテゴリ（一般購買、給油所、農業機械）
     * @param productDao 商品マスタDAO
     * @param variantDao OCR誤認識パターンDAO
     * @return 補正結果
     */
    suspend fun correctProductName(
        ocrName: String,
        category: String,
        productDao: ProductMasterDao,
        variantDao: OcrVariantDao
    ): CorrectionResult {
        Log.d(TAG, "[CORRECTION-V2] Input: '$ocrName', Category: $category")

        // Step 0: 手動訂正データの確認（Layer 1: 無条件適用）
        val normalizedForVariant = normalizeForVariant(ocrName)
        val unconditionalVariant = variantDao.findUnconditionalVariant(normalizedForVariant)
        if (unconditionalVariant != null) {
            val product = productDao.getById(unconditionalVariant.productId)
            if (product != null) {
                Log.d(TAG, "[CORRECTION-V2] ✅ UNCONDITIONAL VARIANT HIT: '$ocrName' → '${product.canonicalName}' (source=${unconditionalVariant.source})")
                return CorrectionResult(
                    correctedName = product.canonicalName,
                    score = 1.0,
                    matched = true,
                    details = "Layer1: ${unconditionalVariant.source}"
                )
            }
        }

        // 1. 商品名をパース（ベース名 + 容量分離）
        val ocrParts = parseProductName(ocrName)
        Log.d(TAG, "[CORRECTION-V2]   Parsed: base='${ocrParts.baseName}', capacity='${ocrParts.capacity}'")

        // 2. カテゴリ内の商品を取得
        val products = productDao.getByCategory(category)
        Log.d(TAG, "[CORRECTION-V2]   Candidates: ${products.size} products in '$category'")

        if (products.isEmpty()) {
            return CorrectionResult(
                correctedName = ocrName,
                score = 0.0,
                matched = false,
                details = "No products in category"
            )
        }

        // 3. 各商品とのスコアを計算
        var bestScore = 0.0
        var bestProduct: com.example.greenframeocr.data.ProductMaster? = null
        var bestDetails = ""

        for (product in products) {
            val masterParts = parseProductName(product.canonicalName)

            // 容量が一致する商品のみマッチング対象
            if (ocrParts.capacity.isNotEmpty() && masterParts.capacity.isNotEmpty()) {
                if (ocrParts.capacity != masterParts.capacity) {
                    continue  // 容量不一致 → スキップ
                }
            }

            // スコア計算
            val score = scoreProduct(ocrParts.baseName, masterParts.baseName)
            val details = "brand=%.2f, form=%.2f, edit=%.2f, ocr=%.2f".format(
                brandScore(ocrParts.baseName, masterParts.baseName),
                formulationScore(ocrParts.baseName, masterParts.baseName),
                editScore(ocrParts.baseName, masterParts.baseName),
                ocrReliability(ocrParts.baseName)
            )

            Log.d(TAG, "[CORRECTION-V2]     '${product.canonicalName}' score=${"%.3f".format(score)} [$details]")

            if (score > bestScore) {
                bestScore = score
                bestProduct = product
                bestDetails = details
            }
        }

        // 4. 採用判定
        val result = if (bestProduct != null && bestScore >= AUTO_CORRECT_THRESHOLD) {
            val masterParts = parseProductName(bestProduct.canonicalName)
            val correctedName = if (ocrParts.capacity.isNotEmpty()) {
                "${masterParts.baseName}${ocrParts.capacity}"
            } else {
                bestProduct.canonicalName
            }

            Log.d(TAG, "[CORRECTION-V2] ✅ APPLIED: '$ocrName' → '$correctedName' (score=${"%.3f".format(bestScore)})")

            // 使用頻度インクリメント
            productDao.incrementFrequency(bestProduct.id)

            // 新規誤認識パターン記録（スコアが高い場合のみ）
            if (bestScore >= 0.80 && ocrParts.baseName != masterParts.baseName) {
                val existing = variantDao.getByText(ocrParts.baseName)
                if (existing != null) {
                    // 既存のパターンはカウントアップ
                    variantDao.incrementOccurrence(existing.id)
                } else {
                    // 新規パターンを登録
                    variantDao.insert(
                        com.example.greenframeocr.data.OcrVariant(
                            productId = bestProduct.id,
                            variantText = ocrParts.baseName,
                            normalizedText = ocrParts.baseName,
                            hitCount = 1,
                            source = com.example.greenframeocr.data.VariantSource.AUTO.name
                        )
                    )
                }
                Log.d(TAG, "[CORRECTION-V2]   📝 Learned variant: '${ocrParts.baseName}' for product ${bestProduct.id}")
            }

            CorrectionResult(
                correctedName = correctedName,
                score = bestScore,
                matched = true,
                details = bestDetails
            )
        } else {
            Log.d(TAG, "[CORRECTION-V2] ❌ NOT APPLIED: best score=${"%.3f".format(bestScore)} < $AUTO_CORRECT_THRESHOLD")
            CorrectionResult(
                correctedName = ocrName,
                score = bestScore,
                matched = false,
                details = bestDetails
            )
        }

        return result
    }

    // ============================================
    // スコア計算
    // ============================================

    /**
     * 商品名総合スコア（剤型の有無で重み配分を変更）
     *
     * 剤型あり商品:
     * - ブランド一致度: 30%
     * - 剤型一致度: 30%
     * - 編集距離: 25%
     * - OCR信頼度: 15%
     *
     * 剤型なし商品（レギュラーガソリン、灯油など）:
     * - ブランド一致度: 40%
     * - 編集距離: 35%
     * - OCR信頼度: 25%
     * ※ 剤型がないことはペナルティではなく情報欠落 → 重みを再配分
     */
    private fun scoreProduct(ocrBase: String, masterBase: String): Double {
        val brand = brandScore(ocrBase, masterBase)
        val formulation = formulationScore(ocrBase, masterBase)
        val edit = editScore(ocrBase, masterBase)
        val reliability = ocrReliability(ocrBase)

        // 剤型の有無を判定
        val hasFormulation = formulation > 0.0

        return if (hasFormulation) {
            // 通常商品（剤型あり）
            brand * 0.30 +
            formulation * 0.30 +
            edit * 0.25 +
            reliability * 0.15
        } else {
            // 剤型なし商品（レギュラーガソリン、灯油など）
            // 暴走防止: 高い一致率を要求
            if (brand >= 0.90 && edit >= 0.85) {
                brand * 0.40 +
                edit * 0.35 +
                reliability * 0.25
            } else {
                // 剤型なしで一致率が低い場合はスコアを低く抑える
                brand * 0.40 +
                edit * 0.35 +
                reliability * 0.25 * 0.5  // 信頼度を半減してリスク低減
            }
        }
    }

    /**
     * ブランド一致度
     *
     * 完全一致 > 前方一致 > N-gram一致 > 部分一致
     */
    private fun brandScore(ocr: String, master: String): Double {
        val o = normalizeForMatch(ocr)
        val m = normalizeForMatch(master)

        // 完全一致
        if (o == m) return 1.0

        // 前方一致（3文字以上）
        if (m.length >= 3 && o.startsWith(m.take(3))) return 0.8

        // N-gram一致（2-gram以上）
        if (hasNgramMatch(o, m, 2)) return 0.6

        // 部分一致（2文字以上）
        if (m.length >= 2 && o.contains(m.take(2))) return 0.4

        return 0.0
    }

    /**
     * 剤型一致度
     *
     * OCR誤認識パターンを考慮した柔軟なマッチング
     */
    private fun formulationScore(ocr: String, master: String): Double {
        val ocrFormulations = extractFormulations(ocr)
        val masterFormulations = extractFormulations(master)

        if (ocrFormulations.isEmpty() || masterFormulations.isEmpty()) {
            return 0.0
        }

        // マスタ側の剤型と一致チェック
        for (masterForm in masterFormulations) {
            for (ocrForm in ocrFormulations) {
                // 完全一致
                if (ocrForm == masterForm) return 1.0

                // OCR誤認識パターン一致
                if (formulationMap[masterForm]?.contains(ocrForm) == true) {
                    return 0.7
                }
            }
        }

        return 0.0
    }

    /**
     * 剤型トークンを抽出
     */
    private fun extractFormulations(text: String): Set<String> {
        val formulations = mutableSetOf<String>()

        // 正規剤型を検索
        for (key in formulationMap.keys) {
            if (text.contains(key)) {
                formulations.add(key)
            }
        }

        // OCR誤認識パターンを検索 → 正規剤型に逆引き
        for ((key, variants) in formulationMap) {
            for (variant in variants) {
                if (text.contains(variant)) {
                    formulations.add(key)  // 正規形で追加
                }
            }
        }

        return formulations
    }

    /**
     * 編集距離スコア
     *
     * レーベンシュタイン距離を使用（補助的な役割）
     */
    private fun editScore(a: String, b: String): Double {
        val dist = levenshteinDistance(normalizeForMatch(a), normalizeForMatch(b))
        val maxLen = max(a.length, b.length)
        return if (maxLen == 0) 0.0 else 1.0 - dist.toDouble() / maxLen
    }

    /**
     * レーベンシュタイン距離（編集距離）
     */
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

    /**
     * OCR信頼度
     *
     * 日本語文字率で判定（高い方が信頼できる）
     */
    private fun ocrReliability(text: String): Double {
        val jpCount = text.count {
            it in '\u3040'..'\u309F' ||  // ひらがな
            it in '\u30A0'..'\u30FF' ||  // カタカナ
            it in '\u4E00'..'\u9FFF'     // 漢字
        }
        val rate = jpCount.toDouble() / max(1, text.length)

        return when {
            rate > 0.8 -> 1.0
            rate > 0.6 -> 0.8
            rate > 0.4 -> 0.6
            else -> 0.4
        }
    }

    // ============================================
    // ユーティリティ
    // ============================================

    /**
     * 商品名をパース（ベース名 + 容量分離）
     *
     * 例: "フェニックス顆粒水和剤250g"
     *  → baseName="フェニックス顆粒水和剤", capacity="250g"
     */
    private fun parseProductName(name: String): ProductParts {
        // 容量パターン: 数字 + 単位（半角・全角対応）
        val capacityPattern = Regex("""(\d+(?:\.\d+)?)(g|ｇ|kg|ｋｇ|ml|ｍｌ|mℓ|ｍℓ|L|ℓ|Ｌ|cc|ｃｃ|ＣＣ|ML|ＭＬ|個|本|枚|袋|缶)$""")
        val match = capacityPattern.find(name)

        return if (match != null) {
            val capacity = match.value
            val baseName = name.removeSuffix(capacity).trim()
            ProductParts(baseName, capacity)
        } else {
            ProductParts(name, "")
        }
    }

    /**
     * マッチング用正規化
     *
     * OCR誤認識の標準的なパターンを吸収
     */
    private fun normalizeForMatch(text: String): String {
        return text
            // 記号・空白を除去
            .replace(Regex("[^一-龯ぁ-んァ-ンa-zA-Z0-9]"), "")
            // OCR誤認識の標準パターンを修正
            .replace("類", "顆")
            .replace("粒", "顆")  // 「粒」単独は顆粒の誤認識として扱う
            .replace("刺", "乳")
            .replace("則", "剤")
            .replace("初", "和")
            .replace("財", "剤")
            .replace("到", "剤")
    }

    /**
     * OcrVariant検索用正規化
     *
     * 手動訂正データとのマッチングに使用
     * ReceiptInputScreen.normalizeForLearningと同じ処理
     */
    private fun normalizeForVariant(text: String): String {
        return text
            .replace(Regex("[\\s　]"), "")  // 空白除去
            .replace(Regex("[^一-龯ぁ-んァ-ンa-zA-Z0-9]"), "")  // 記号除去
            .replace(Regex("[a-zA-Z]$"), "")  // 末尾の単独英字除去
            .replace(Regex("^[a-zA-Z]"), "")  // 先頭の単独英字除去
    }

    /**
     * N-gramマッチング
     *
     * 指定したN文字の連続で一致があるか
     */
    private fun hasNgramMatch(a: String, b: String, n: Int): Boolean {
        if (a.length < n || b.length < n) return false

        val aSet = a.windowed(n).toSet()
        val bSet = b.windowed(n).toSet()

        return aSet.intersect(bSet).isNotEmpty()
    }
}
