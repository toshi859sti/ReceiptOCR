package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.RakurakuTekiyou

/**
 * らくらくの摘要辞書に AoiroChobo の `memoKey` を割り当てるときの、帳簿の対応づけと自動提案。
 *
 * 並べる主軸は **らくらく側の摘要**（科目マッピングと逆）。学習
 * （`product_master.kaikakeTekiyouId` / `tekiyou_matching_rules.rakurakuTekiyouId` /
 * `deposit_meisai.overrideTekiyouId`）が指しているのがこちらなので、
 * らくらく側を主軸にすると「学習が参照しているのに未マッピング」＝移行で失うものが数えられる。
 *
 * らくらくのサポートを終える際、`rakuraku_tekiyou` は消える。消す前にこの画面で
 * `memoKey` を確定させ、学習をキー直指しに張り替えておく必要がある。
 */
object AoiroChoboMemoMapping {

    /**
     * らくらくの (mainCategory, subCategory) → AoiroChobo の (ledgerType, direction)。
     *
     * `direction` は「お金の向き」ではなく帳簿上の発生／解消で、同じ「入金」でも
     * 現金は `In`、売掛は `Out`（回収）になる。だから sub だけでは決まらず、対で見る。
     *
     * らくらくには未払帳・振替伝票の摘要が無い（`Unpaid` / `Transfer` はあおいろ側にだけある）。
     */
    private val LEDGER_MAP: Map<Pair<String, String>, Pair<String, String>> = mapOf(
        ("現金" to "入金") to ("Cash" to "In"),
        ("現金" to "出金") to ("Cash" to "Out"),
        ("預金" to "入金") to ("Bank" to "In"),
        ("預金" to "出金") to ("Bank" to "Out"),
        ("売掛" to "販売") to ("AR" to "In"),
        ("売掛" to "入金") to ("AR" to "Out"),
        ("買掛" to "購入") to ("AP" to "In"),
        ("買掛" to "出金") to ("AP" to "Out"),
        // アセットの CSV には「買掛－購入」「買掛－支払」の表記ゆれがある
        ("買掛" to "仕入") to ("AP" to "In"),
        ("買掛" to "支払") to ("AP" to "Out")
    )

    /** らくらくの分類に対応する AoiroChobo の帳簿・向き。対応が無ければ null */
    fun ledgerOf(tekiyou: RakurakuTekiyou): Pair<String, String>? =
        LEDGER_MAP[normalizeCategory(tekiyou.mainCategory) to normalizeCategory(tekiyou.subCategory)]

    /** 「買掛－出金」のような全角ダッシュ・空白の混入を落とす */
    private fun normalizeCategory(value: String): String =
        value.replace(Regex("[－―—\\-‐・\\s　]"), "").trim()

    /**
     * 摘要名の表記ゆれを吸収する。括弧は**中身も含めて落とさない**——
     * 「買掛支払（現金）」と「買掛支払（普通預金）」は別の摘要で、
     * 落とすと取り違える。落とすのは括弧の種類と区切り文字の差だけ。
     */
    fun normalizeName(name: String): String =
        name
            .replace('（', '(')
            .replace('）', ')')
            .replace(Regex("[\\s　・･]"), "")
            .trim()

    enum class Basis(val label: String) {
        EXACT_NAME("摘要名が一致"),
        NORMALIZED_NAME("摘要名がほぼ一致"),
        SEARCH_KEY("検索文字が一致")
    }

    data class Suggestion(
        val tekiyouId: Int,
        val candidates: List<AoiroChoboMemoTemplate>,
        val basis: Basis
    ) {
        val isUnambiguous: Boolean get() = candidates.size == 1
    }

    /**
     * 未マッピングのらくらく摘要に、AoiroChobo の摘要候補を提案する。
     *
     * 候補は**同じ帳簿・同じ向きの中だけ**から探す。らくらくにもあおいろにも
     * 同名の摘要が帳簿をまたいで存在する（「米販売代金」が現金にも売掛にもある）ので、
     * 帳簿で絞らないと取り違える。
     */
    fun suggest(
        tekiyouList: List<RakurakuTekiyou>,
        memoTemplates: List<AoiroChoboMemoTemplate>
    ): Map<Int, Suggestion> {
        val alreadyLinked = tekiyouList.mapNotNull { it.memoKey }.toSet()
        val available = memoTemplates.filter { it.memoKey !in alreadyLinked }
        val byLedger = available.groupBy { it.ledgerType to it.direction }

        return tekiyouList
            .filter { it.memoKey == null && it.isEnabled }
            .mapNotNull { tekiyou ->
                val ledger = ledgerOf(tekiyou) ?: return@mapNotNull null
                val pool = byLedger[ledger].orEmpty()
                if (pool.isEmpty()) return@mapNotNull null

                val exact = pool.filter { it.name.trim() == tekiyou.tekiyouName.trim() }
                val normalized = pool.filter {
                    normalizeName(it.name) == normalizeName(tekiyou.tekiyouName)
                }
                val bySearchKey = tekiyou.searchKey.takeIf { it.isNotBlank() }?.let { key ->
                    pool.filter { it.searchKey.equals(key.trim(), ignoreCase = true) }
                }.orEmpty()

                val hit = when {
                    exact.isNotEmpty() -> exact to Basis.EXACT_NAME
                    normalized.isNotEmpty() -> normalized to Basis.NORMALIZED_NAME
                    bySearchKey.isNotEmpty() -> bySearchKey to Basis.SEARCH_KEY
                    else -> return@mapNotNull null
                }
                tekiyou.id to Suggestion(tekiyou.id, hit.first, hit.second)
            }
            .toMap()
    }

    /**
     * 一括確定に回してよい提案だけを取り出す。候補が 1 つに決まっているものに限り、
     * 同じ `memoKey` を 2 つのらくらく摘要が取り合わないよう、根拠が強いほうを 1 回だけ使う。
     */
    fun autoConfirmable(suggestions: Map<Int, Suggestion>): List<Suggestion> {
        val claimed = mutableSetOf<String>()
        return suggestions.values
            .filter { it.isUnambiguous }
            .sortedBy { it.basis.ordinal }
            .filter { claimed.add(it.candidates.first().memoKey) }
    }
}
