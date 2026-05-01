package com.example.greenframeocr.util

fun charFullWidthWeight(c: Char): Double =
    if (c.code in 0x20..0x7E || c.code in 0xFF61..0xFF9F) 0.5 else 1.0

fun countFullWidthEquivalent(text: String): Double =
    text.sumOf { charFullWidthWeight(it) }

fun truncateToFullWidthLimit(text: String, limit: Double = 20.0): String {
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
        c in '0'..'9' -> (c.code + 0xFEE0).toChar()
        c in 'A'..'Z' -> (c.code + 0xFEE0).toChar()
        c in 'a'..'z' -> (c.code + 0xFEE0).toChar()
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
