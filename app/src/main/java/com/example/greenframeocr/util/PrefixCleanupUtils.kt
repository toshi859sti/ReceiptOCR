package com.example.greenframeocr.util

import com.example.greenframeocr.data.GeneralItemGroup

// 数字接頭辞除去候補の1件。highConfidence=trueは「＃」等の区切り文字を伴い伝票の行番号と
// 判断しやすいケース、falseは数字＋空白のみでサイズ表記等と紛らわしいため確認を要するケース
data class NumericPrefixCandidate(
    val group: GeneralItemGroup,
    val cleanedName: String,
    val highConfidence: Boolean
)

private val prefixWithDelimiter = Regex("^\\d{1,3}[#／/]\\s*")
private val prefixWithSpaceOnly = Regex("^\\d{1,3}\\s+")

/**
 * レシート品目名の先頭に付く伝票行番号らしき数字接頭辞（例：「10#種まき培土 40L」
 * 「05#1×4材 6F...」）を検出する。「＃」等の区切り文字を伴う場合は行番号の可能性が高いと
 * 判断し高信頼、数字＋空白のみ（例：「09 ノーパンクタイヤ」）は商品名の一部の数字と
 * 区別がつきにくいため低信頼として分ける。どちらも自動適用はせず候補提示のみに留める。
 */
fun findNumericPrefixCandidates(groups: List<GeneralItemGroup>): List<NumericPrefixCandidate> {
    val result = mutableListOf<NumericPrefixCandidate>()
    for (group in groups) {
        val name = group.itemName
        val delimiterMatch = prefixWithDelimiter.find(name)
        if (delimiterMatch != null) {
            val cleaned = name.removePrefix(delimiterMatch.value).trim()
            if (cleaned.length >= 2) {
                result.add(NumericPrefixCandidate(group, cleaned, highConfidence = true))
                continue
            }
        }
        val spaceMatch = prefixWithSpaceOnly.find(name)
        if (spaceMatch != null) {
            val cleaned = name.removePrefix(spaceMatch.value).trim()
            if (cleaned.length >= 2) {
                result.add(NumericPrefixCandidate(group, cleaned, highConfidence = false))
            }
        }
    }
    return result.sortedByDescending { it.highConfidence }
}
