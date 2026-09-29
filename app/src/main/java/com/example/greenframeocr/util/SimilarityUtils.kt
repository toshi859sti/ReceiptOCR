package com.example.greenframeocr.util

import com.example.greenframeocr.data.GeneralItemGroup

// 類似グループ統合候補の1件。keep側に吸収し、merge側は解消される想定
// （件数が多い方をkeepにする。統合は必ずユーザーが確認ダイアログで承認してから実行する）
data class SimilarGroupPair(
    val keep: GeneralItemGroup,
    val merge: GeneralItemGroup,
    val distance: Int
)

private val numberRegex = Regex("\\d+")

/**
 * 「500ml」「250ml」のように数字部分だけが違う場合は容量・数量違いの別商品である
 * 可能性が高く、OCRノイズによる表記ゆれとは区別すべきなので、含まれる数字列が
 * 1つでも異なれば統合候補から除外する（数字がどちらにも無ければ対象外＝素通り）。
 */
private fun hasDifferentNumbers(a: String, b: String): Boolean {
    val numsA = numberRegex.findAll(a).map { it.value }.toList()
    val numsB = numberRegex.findAll(b).map { it.value }.toList()
    return numsA != numsB
}

private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prevDiag = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val temp = dp[j]
            dp[j] = if (a[i - 1] == b[j - 1]) prevDiag
                    else 1 + minOf(prevDiag, dp[j], dp[j - 1])
            prevDiag = temp
        }
    }
    return dp[b.length]
}

/**
 * レシートOCRはJA伝票ほど安定せず、「10種まき培土　40L」「10＃種まき培土　40L」のように
 * ノイズ文字1〜2文字の混入で別グループになりやすい。canonicalKeyの完全一致（表記ゆれ吸収）
 * だけでは拾えないこうした準一致を編集距離で検出し、統合候補として提示する。
 * 自動統合は行わない（誤爆すると別品目が合体してしまうため、必ずユーザーの手動承認を挟む）。
 */
fun findSimilarGroupPairs(groups: List<GeneralItemGroup>): List<SimilarGroupPair> {
    val candidates = groups.filter { it.canonicalKey.length >= 4 }
    val pairs = mutableListOf<SimilarGroupPair>()
    for (i in candidates.indices) {
        for (j in i + 1 until candidates.size) {
            val a = candidates[i]
            val b = candidates[j]
            if (hasDifferentNumbers(a.canonicalKey, b.canonicalKey)) continue
            val maxLen = maxOf(a.canonicalKey.length, b.canonicalKey.length)
            // 文字数が長いほどノイズ許容も広げる（短い名前で許容を広げると別品目まで拾ってしまう）
            val threshold = when {
                maxLen <= 6 -> 1
                maxLen <= 12 -> 2
                else -> 3
            }
            val dist = levenshteinDistance(a.canonicalKey, b.canonicalKey)
            if (dist in 1..threshold) {
                val (keep, merge) = if (a.count >= b.count) a to b else b to a
                pairs.add(SimilarGroupPair(keep, merge, dist))
            }
        }
    }
    return pairs.sortedBy { it.distance }
}
