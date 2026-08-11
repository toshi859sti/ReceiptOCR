package com.example.greenframeocr.util

import com.example.greenframeocr.data.ProductMaster

/**
 * product_master の一意キーを生成する。
 * 表示用 canonicalName からスペース除去・文字種統一した文字列を返す。
 *
 * 変換ルール:
 *   1. 半角・全角スペース除去
 *   2. 半角カタカナ（ﾞﾟ結合を含む）→ 全角カタカナ
 *   3. 全角英数字・全角記号（－／（）：等、U+FF01〜U+FF5E） → 半角
 *      （ダッシュ等の記号幅がOCR実行のたびにブレる既知の傾向に対応。2026-08-11修正）
 */
fun toCanonicalKey(name: String): String {
    // 半角カタカナ単独 → 全角カタカナ
    val baseMap = mapOf(
        'ｦ' to 'ヲ', 'ｧ' to 'ァ', 'ｨ' to 'ィ', 'ｩ' to 'ゥ', 'ｪ' to 'ェ',
        'ｫ' to 'ォ', 'ｬ' to 'ャ', 'ｭ' to 'ュ', 'ｮ' to 'ョ', 'ｯ' to 'ッ',
        'ｰ' to 'ー', 'ｱ' to 'ア', 'ｲ' to 'イ', 'ｳ' to 'ウ', 'ｴ' to 'エ',
        'ｵ' to 'オ', 'ｶ' to 'カ', 'ｷ' to 'キ', 'ｸ' to 'ク', 'ｹ' to 'ケ',
        'ｺ' to 'コ', 'ｻ' to 'サ', 'ｼ' to 'シ', 'ｽ' to 'ス', 'ｾ' to 'セ',
        'ｿ' to 'ソ', 'ﾀ' to 'タ', 'ﾁ' to 'チ', 'ﾂ' to 'ツ', 'ﾃ' to 'テ',
        'ﾄ' to 'ト', 'ﾅ' to 'ナ', 'ﾆ' to 'ニ', 'ﾇ' to 'ヌ', 'ﾈ' to 'ネ',
        'ﾉ' to 'ノ', 'ﾊ' to 'ハ', 'ﾋ' to 'ヒ', 'ﾌ' to 'フ', 'ﾍ' to 'ヘ',
        'ﾎ' to 'ホ', 'ﾏ' to 'マ', 'ﾐ' to 'ミ', 'ﾑ' to 'ム', 'ﾒ' to 'メ',
        'ﾓ' to 'モ', 'ﾔ' to 'ヤ', 'ﾕ' to 'ユ', 'ﾖ' to 'ヨ', 'ﾗ' to 'ラ',
        'ﾘ' to 'リ', 'ﾙ' to 'ル', 'ﾚ' to 'レ', 'ﾛ' to 'ロ', 'ﾜ' to 'ワ',
        'ﾝ' to 'ン'
    )
    // 半角カタカナ + ﾞ → 濁点全角
    val dakutenMap = mapOf(
        'ｶ' to 'ガ', 'ｷ' to 'ギ', 'ｸ' to 'グ', 'ｹ' to 'ゲ', 'ｺ' to 'ゴ',
        'ｻ' to 'ザ', 'ｼ' to 'ジ', 'ｽ' to 'ズ', 'ｾ' to 'ゼ', 'ｿ' to 'ゾ',
        'ﾀ' to 'ダ', 'ﾁ' to 'ヂ', 'ﾂ' to 'ヅ', 'ﾃ' to 'デ', 'ﾄ' to 'ド',
        'ﾊ' to 'バ', 'ﾋ' to 'ビ', 'ﾌ' to 'ブ', 'ﾍ' to 'ベ', 'ﾎ' to 'ボ',
        'ｳ' to 'ヴ'
    )
    // 半角カタカナ + ﾟ → 半濁点全角
    val handakutenMap = mapOf(
        'ﾊ' to 'パ', 'ﾋ' to 'ピ', 'ﾌ' to 'プ', 'ﾍ' to 'ペ', 'ﾎ' to 'ポ'
    )

    val sb = StringBuilder()
    var i = 0
    while (i < name.length) {
        val c = name[i]
        val next = name.getOrNull(i + 1)
        when {
            c == ' ' || c == '　' -> i++
            next == 'ﾞ' && dakutenMap.containsKey(c) -> { sb.append(dakutenMap[c]); i += 2 }
            next == 'ﾟ' && handakutenMap.containsKey(c) -> { sb.append(handakutenMap[c]); i += 2 }
            baseMap.containsKey(c) -> { sb.append(baseMap[c]); i++ }
            c.code in 0xFF01..0xFF5E -> { sb.append((c.code - 0xFEE0).toChar()); i++ }
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString()
}

/** INSERT 前に canonicalKey を canonicalName から自動計算する。 */
fun ProductMaster.withComputedKey(): ProductMaster =
    copy(canonicalKey = toCanonicalKey(canonicalName))

fun charFullWidthWeight(c: Char): Double =
    if (c.code in 0x20..0x7E || c.code in 0xFF61..0xFF9F) 0.5 else 1.0

fun countFullWidthEquivalent(text: String): Double =
    text.sumOf { charFullWidthWeight(it) }

fun truncateToFullWidthLimit(text: String, limit: Double = 30.0): String {
    var count = 0.0
    val result = StringBuilder()
    for (c in text) {
        val w = charFullWidthWeight(c)
        if (count + w > limit) break
        result.append(c)
        count += w
    }
    return result.toString()
}

fun convertAllToFullWidth(text: String): String = text.map { c ->
    when {
        c == ' ' -> '　'
        c.code in 0x21..0x7E -> (c.code + 0xFEE0).toChar()  // 記号・数字・英字すべて全角化
        else -> c
    }
}.joinToString("")

fun applyConversionToNewInput(oldText: String, newText: String, alphaFullWidth: Boolean): String {
    if (newText.length <= oldText.length) return newText

    var prefixLen = 0
    while (prefixLen < oldText.length && prefixLen < newText.length &&
           oldText[prefixLen] == newText[prefixLen]) prefixLen++

    var suffixLen = 0
    while (suffixLen < oldText.length - prefixLen &&
           suffixLen < newText.length - prefixLen &&
           oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]) suffixLen++

    val inserted = newText.substring(prefixLen, newText.length - suffixLen)
    val converted = inserted.map { c ->
        when {
            c == ' ' -> '　'
            c in '0'..'9' -> (c.code + 0xFEE0).toChar()
            c in 'A'..'Z' -> if (alphaFullWidth) (c.code + 0xFEE0).toChar() else c
            c in 'a'..'z' -> if (alphaFullWidth) (c.code + 0xFEE0).toChar() else c
            c in 'Ａ'..'Ｚ' -> if (alphaFullWidth) c else (c.code - 0xFEE0).toChar()
            c in 'ａ'..'ｚ' -> if (alphaFullWidth) c else (c.code - 0xFEE0).toChar()
            else -> c
        }
    }.joinToString("")

    val combined = newText.substring(0, prefixLen) + converted +
           (if (suffixLen > 0) newText.substring(newText.length - suffixLen) else "")
    return truncateToFullWidthLimit(combined)
}
