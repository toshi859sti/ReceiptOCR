package com.example.greenframeocr.util

/**
 * 科目の検索文字（PC の searchKey・ローマ字）を、日本語キーボードからでも引けるようにする。
 *
 * スマホの日本語キーボードで「dou」と打つと「どう」になり、ローマ字の検索文字には当たらない。
 * そこで入力のかなをローマ字に直してから比べる。さらに PC の検索文字は綴りの流儀が混ざっている
 * （`syubyou` `tidai` は訓令式、`hiryoucyo` と `tyokubai` はチョの綴りが違う）ので、
 * 入力と検索文字の両方を 1 つの綴り（訓令式寄り）に揃えてから部分一致を見る。
 */
object RomajiSearch {

    /** [query] が [searchKey] に部分一致するか。かな・ヘボン式・訓令式のどれで打っても同じに扱う */
    fun matches(searchKey: String, query: String): Boolean {
        val q = normalize(kanaToRomaji(query.trim()))
        if (q.isEmpty()) return true
        return normalize(searchKey).contains(q)
    }

    /** 綴りの流儀を揃える（小文字化・ヘボン式 → 訓令式寄り） */
    internal fun normalize(romaji: String): String {
        var s = romaji.lowercase()
        // 長い綴りから先に置き換える（shi → si の前に sh を置き換えると sy + i になってしまう）
        for ((from, to) in HEPBURN) s = s.replace(from, to)
        return s
    }

    private val HEPBURN = listOf(
        "shi" to "si", "chi" to "ti", "tsu" to "tu", "fu" to "hu", "ji" to "zi",
        "sh" to "sy", "ch" to "ty", "cy" to "ty", "j" to "zy"
    )

    /** ひらがな・カタカナをローマ字（訓令式）に直す。かな以外の文字はそのまま残す */
    internal fun kanaToRomaji(text: String): String {
        val sb = StringBuilder()
        var i = 0
        var doubleNext = false
        while (i < text.length) {
            val c = toHiragana(text[i])
            // 拗音（きゃ・しゅ など）は 2 文字で 1 音
            val pair = if (i + 1 < text.length) "$c${toHiragana(text[i + 1])}" else null
            val romaji = pair?.let { YOON[it] }?.also { i++ } ?: KANA[c.toString()]
            when {
                c == 'っ' -> doubleNext = true
                c == 'ー' -> Unit  // 長音は検索文字に現れない（ou / uu で書かれる）
                romaji != null -> {
                    sb.append(if (doubleNext) romaji.first() + romaji else romaji)
                    doubleNext = false
                }
                else -> {
                    sb.append(c)
                    doubleNext = false
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun toHiragana(c: Char): Char = if (c in 'ァ'..'ヶ') c - 0x60 else c

    private val KANA: Map<String, String> = buildMap {
        val rows = listOf(
            "あいうえお" to listOf("a", "i", "u", "e", "o"),
            "かきくけこ" to listOf("ka", "ki", "ku", "ke", "ko"),
            "さしすせそ" to listOf("sa", "si", "su", "se", "so"),
            "たちつてと" to listOf("ta", "ti", "tu", "te", "to"),
            "なにぬねの" to listOf("na", "ni", "nu", "ne", "no"),
            "はひふへほ" to listOf("ha", "hi", "hu", "he", "ho"),
            "まみむめも" to listOf("ma", "mi", "mu", "me", "mo"),
            "やゆよ" to listOf("ya", "yu", "yo"),
            "らりるれろ" to listOf("ra", "ri", "ru", "re", "ro"),
            "わをん" to listOf("wa", "o", "n"),
            "がぎぐげご" to listOf("ga", "gi", "gu", "ge", "go"),
            "ざじずぜぞ" to listOf("za", "zi", "zu", "ze", "zo"),
            "だぢづでど" to listOf("da", "zi", "zu", "de", "do"),
            "ばびぶべぼ" to listOf("ba", "bi", "bu", "be", "bo"),
            "ぱぴぷぺぽ" to listOf("pa", "pi", "pu", "pe", "po"),
            "ぁぃぅぇぉ" to listOf("a", "i", "u", "e", "o")
        )
        for ((kana, romaji) in rows) kana.forEachIndexed { idx, ch -> put(ch.toString(), romaji[idx]) }
    }

    private val YOON: Map<String, String> = buildMap {
        val heads = mapOf(
            'き' to "ky", 'し' to "sy", 'ち' to "ty", 'に' to "ny", 'ひ' to "hy", 'み' to "my", 'り' to "ry",
            'ぎ' to "gy", 'じ' to "zy", 'ぢ' to "zy", 'び' to "by", 'ぴ' to "py"
        )
        for ((head, prefix) in heads) {
            put("${head}ゃ", "${prefix}a")
            put("${head}ゅ", "${prefix}u")
            put("${head}ょ", "${prefix}o")
        }
    }
}
