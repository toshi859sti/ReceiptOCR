package com.example.receiptorc.util

import android.util.Log
import com.example.receiptorc.data.ProductMaster
import com.example.receiptorc.data.ProductMasterDao
import com.example.receiptorc.data.OcrVariantDao

/**
 * 商品名補正ユーティリティ
 *
 * OCR結果の商品名を辞書ベースで補正。
 * 容量保護型マッチングにより、容量違いの商品への誤変換を防止。
 */
object ProductNameCorrector {

    private const val TAG = "ProductNameCorrector"

    /** 類似度の閾値（0.0-1.0）。これ以上なら補正対象 */
    private const val SIMILARITY_THRESHOLD = 0.7f

    /**
     * 商品名の構成要素
     */
    data class ProductNameParts(
        val baseName: String,   // ベース名 (例: "フェニックス顆粒水和剤")
        val capacity: String?,  // 容量 (例: "250g")
        val fullName: String    // 完全名 (例: "フェニックス顆粒水和剤250g")
    )

    /**
     * 補正結果
     */
    data class CorrectionResult(
        val correctedName: String,  // 補正後の商品名
        val similarity: Float,      // 類似度 (0.0-1.0)
        val matched: Boolean,       // マッチング成功フラグ
        val matchedProduct: ProductMaster? = null  // マッチした商品マスタ
    )

    /**
     * 商品名を補正
     *
     * @param ocrName OCRで読み取った商品名
     * @param category カテゴリ（"一般購買", "給油所", "農業機械"）
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
        // 1. まず誤認識パターンから完全一致を検索
        val variant = variantDao.getByText(ocrName)
        if (variant != null) {
            val product = productDao.getById(variant.productId)
            if (product != null) {
                Log.d(TAG, "Exact match from variant: $ocrName -> ${product.canonicalName}")
                // 出現回数を更新
                variantDao.incrementOccurrence(variant.id)
                return CorrectionResult(
                    correctedName = product.canonicalName,
                    similarity = 1.0f,
                    matched = true,
                    matchedProduct = product
                )
            }
        }

        // 2. 商品名を分解
        val ocrParts = parseProductName(ocrName)

        // 3. カテゴリ内の商品を取得
        val products = productDao.getByCategory(category)
        if (products.isEmpty()) {
            Log.d(TAG, "No products found for category: $category")
            return CorrectionResult(ocrName, 0f, false)
        }

        // 4. 容量保護型マッチング
        var bestMatch: ProductMaster? = null
        var bestSimilarity = 0f

        for (product in products) {
            val masterParts = parseProductName(product.canonicalName)

            // ベース名のみで類似度を計算
            val baseSimilarity = calculateSimilarity(
                ocrParts.baseName,
                masterParts.baseName
            )

            // 容量がある場合は、容量も一致する商品のみ候補に
            if (ocrParts.capacity != null && masterParts.capacity != null) {
                // 容量が一致する場合のみマッチング対象
                if (normalizeCapacity(ocrParts.capacity) == normalizeCapacity(masterParts.capacity)) {
                    if (baseSimilarity > bestSimilarity) {
                        bestSimilarity = baseSimilarity
                        bestMatch = product
                    }
                }
            } else if (ocrParts.capacity == null && masterParts.capacity == null) {
                // 両方とも容量なし
                if (baseSimilarity > bestSimilarity) {
                    bestSimilarity = baseSimilarity
                    bestMatch = product
                }
            } else if (ocrParts.capacity == null) {
                // OCRには容量がないが、マスタには容量がある場合
                // ベース名の類似度が非常に高い場合のみマッチング
                if (baseSimilarity > 0.9f && baseSimilarity > bestSimilarity) {
                    bestSimilarity = baseSimilarity
                    bestMatch = product
                }
            }
        }

        // 5. 補正結果を生成
        return if (bestSimilarity >= SIMILARITY_THRESHOLD && bestMatch != null) {
            val masterParts = parseProductName(bestMatch.canonicalName)

            // ベース名を補正、容量はOCRを保持（容量がある場合）
            val correctedName = if (ocrParts.capacity != null && masterParts.capacity != null) {
                "${masterParts.baseName}${ocrParts.capacity}"
            } else {
                bestMatch.canonicalName
            }

            Log.d(TAG, "Corrected: $ocrName -> $correctedName (similarity: $bestSimilarity)")

            // 使用頻度をインクリメント
            productDao.incrementFrequency(bestMatch.id)

            // 新しい誤認識パターンとして記録（完全一致でない場合）
            if (ocrName != bestMatch.canonicalName) {
                val existingVariant = variantDao.getByText(ocrName)
                if (existingVariant == null) {
                    variantDao.insert(
                        com.example.receiptorc.data.OcrVariant(
                            productId = bestMatch.id,
                            variantText = ocrName,
                            normalizedText = ocrName,
                            hitCount = 1,
                            source = com.example.receiptorc.data.VariantSource.AUTO.name
                        )
                    )
                    Log.d(TAG, "Registered new OCR variant: $ocrName")
                }
            }

            CorrectionResult(correctedName, bestSimilarity, true, bestMatch)
        } else {
            Log.d(TAG, "No match found for: $ocrName (best similarity: $bestSimilarity)")
            CorrectionResult(ocrName, bestSimilarity, false)
        }
    }

    /**
     * 商品名を分解（ベース名 + 容量）
     *
     * 容量パターン: 数字 + 単位
     * 単位: g, kg, ml, mℓ, L, ℓ, cc, 個, 本, 枚, 袋, 缶
     */
    fun parseProductName(name: String): ProductNameParts {
        // 容量パターン: 数字 + 単位（末尾）
        val capacityPattern = Regex("""(\d+(?:\.\d+)?)(g|kg|ml|mℓ|L|ℓ|cc|個|本|枚|袋|缶)\s*$""")
        val match = capacityPattern.find(name)

        return if (match != null) {
            val capacity = match.value.trim()
            val baseName = name.removeSuffix(match.value).trim()
            ProductNameParts(baseName, capacity, name)
        } else {
            ProductNameParts(name, null, name)
        }
    }

    /**
     * 容量を正規化（比較用）
     *
     * OCR誤認識を吸収:
     * - 全角数字 → 半角数字
     * - ml/mℓ → 統一
     * - L/ℓ → 統一
     */
    private fun normalizeCapacity(capacity: String): String {
        return capacity
            .replace("mℓ", "ml")
            .replace("ℓ", "L")
            .replace(Regex("[０-９]")) { matchResult ->
                // 全角数字を半角に変換
                ('0'.code + (matchResult.value[0].code - '０'.code)).toChar().toString()
            }
            .trim()
    }

    /**
     * レーベンシュタイン距離を計算（編集距離）
     *
     * 2つの文字列間の最小編集回数を計算。
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // 初期化
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        // DP処理
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
     * 類似度を計算（0.0-1.0）
     *
     * 類似度 = 1 - (編集距離 / 最大文字列長)
     */
    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f

        val distance = levenshteinDistance(s1, s2)
        val maxLength = maxOf(s1.length, s2.length)
        return 1f - (distance.toFloat() / maxLength)
    }
}
