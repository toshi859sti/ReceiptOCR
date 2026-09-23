package com.example.greenframeocr.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabMeta
import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.data.toEntityOrNull
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AoiroChobo（PC会計アプリ）が書き出した vocabulary.json の取込。
 *
 * 契約は docs/integration/vocabulary-snapshot.md、スマホ側の方針は
 * docs/integration/REPLY-phone-2026-09-22.md の §2-1。取込時に走らせる処理は5つ:
 *
 * 1. schemaVersion を検証（2 以外は中止）
 * 2. contentHash が前回取込値と文字列一致ならスキップ
 * 3. name が変わった accountKey / memoKey の紐付けを外す  ← 主機構
 * 4. ファイルから消えたキーを指している紐付けを数えて知らせる
 * 5. 科目・摘要が未確定の学習件数を知らせる
 *
 * 3 について: 学習（商品名→科目／摘要など）は AoiroChobo のキーを**直接**持っている。
 * 弥生用の yayoiAccountId とは独立で、同じ商品でも弥生で A、あおいろで B を選ぶことがあるため、
 * 両者を結ぶ対応表は存在しない（2026-09-23 ユーザー確認）。
 * PC 側が科目を作り替えたら name が変わるので、そのキーを指している学習の
 * accountKey / memoKey を外す（弥生側の紐付けは無関係なので残る）。外した学習に当たった仕訳は
 * UnmatchedAccount になり、契約が求める「安全側に失敗する」を満たす。
 * 判定はキー単位で 1 回。同じ科目を指す学習が何件あっても再確認は科目 1 件で済む。
 *
 * 4 は専用の後始末を持たない。ミラー3テーブルは取込のたび丸ごと入れ替わるので、
 * 消えたキーは解決時の引き当てに失敗する＝自動的に自動マッチから外れる。
 */
object AoiroChoboVocabImporter {

    private const val TAG = "AoiroChoboVocabImport"

    const val SUPPORTED_SCHEMA_VERSION = 2
    private const val EXPECTED_KIND = "aoirochobo.vocabulary"

    // スマホが意味を知っている enum 値（schemaVersion 2 時点）。
    // これ以外が来ても弾かず、警告に留めて値はそのまま保持する（契約どおり）
    private val KNOWN_ACCOUNT_TYPES = setOf("Asset", "Liability", "Income", "Expense", "Capital")
    private val KNOWN_LEDGER_VALUES = setOf("Cash", "Bank", "AR", "AP", "Unpaid", "Transfer", "Any")
    private val KNOWN_TAX_RATES = setOf("10", "8", "1", "8_old", "non", "na", "men")
    private val KNOWN_TAX_CATEGORIES = setOf("Taxable", "NonTaxable", "NotApplicable", "TaxExempt", "NA")

    sealed interface Result {
        /** 読めない・契約違反で取り込まなかった */
        data class Aborted(val message: String) : Result

        /** 前回と同じスナップショットだったので何もしなかった */
        data class Skipped(val fiscalYear: Int, val ageDays: Long?) : Result

        data class Imported(
            val accountCount: Int,
            val memoCount: Int,
            val fiscalYear: Int,
            val fiscalYearChanged: Boolean,
            /** 「このマスタは N 日前のものです」。generatedAt が読めなければ null */
            val ageDays: Long?,
            /** name が変わったので外した紐付け（表示用の説明文） */
            val unlinkedByRename: List<String>,
            /** ファイルから消えたキーを指したままの紐付け件数 */
            val danglingLinks: Int,
            /** 科目がまだ決まっていない学習の件数（当たると UnmatchedAccount で出る） */
            val learningWithoutAccount: Int,
            /** 科目は決まったが摘要が未確定の学習の件数（UnmatchedMemo。科目は出せる） */
            val learningWithoutMemo: Int,
            /** 未知の enum 値など。中止はしない */
            val warnings: List<String>
        ) : Result
    }

    suspend fun import(context: Context, db: ReceiptDatabase, uri: Uri): Result =
        withContext(Dispatchers.IO) {
            val json = try {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ファイル読み込みに失敗", e)
                null
            } ?: return@withContext Result.Aborted("ファイルを読み込めませんでした")

            val file = try {
                Gson().fromJson(json, AoiroChoboVocabularyFile::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "JSONの解析に失敗", e)
                null
            } ?: return@withContext Result.Aborted("JSONとして読めませんでした")

            // ---- 1. schemaVersion の検証 ----
            if (file.kind != null && file.kind != EXPECTED_KIND) {
                return@withContext Result.Aborted(
                    "これは AoiroChobo の科目・摘要ファイルではありません（kind=${file.kind}）"
                )
            }
            if (file.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                return@withContext Result.Aborted(
                    "対応していない schemaVersion です（ファイル=${file.schemaVersion} / " +
                        "このアプリ=$SUPPORTED_SCHEMA_VERSION）。どちらかを更新してください"
                )
            }

            val accounts = file.accounts.orEmpty().mapNotNull { it.toEntityOrNull() }
            val memos = file.memoTemplates.orEmpty().mapNotNull { it.toEntityOrNull() }
            if (accounts.isEmpty()) {
                return@withContext Result.Aborted("科目が1件も入っていません")
            }

            val fiscalYear = file.fiscalYear?.year
                ?: return@withContext Result.Aborted("fiscalYear.year がありません")
            val ageDays = ageInDays(file.generatedAt)

            val vocabDao = db.aoiroChoboVocabDao()
            val previous = vocabDao.getMeta()

            // ---- 2. contentHash が前回と同じならスキップ ----
            // スマホ側では再計算しない。受け取った値の文字列一致だけを見る
            val contentHash = file.contentHash.orEmpty()
            if (contentHash.isNotBlank() && contentHash == previous?.contentHash) {
                return@withContext Result.Skipped(fiscalYear, ageDays)
            }

            val warnings = collectEnumWarnings(accounts, memos).toMutableList()
            // 配列名の取り違えなど「形が違う」ファイルを黙って通さない。
            // 実際に memos と memoTemplates を取り違えて摘要が0件になったことがある
            if (file.memoTemplates == null) {
                warnings += "摘要の配列（memoTemplates）がありません。" +
                    "ファイルの形がこのアプリの想定と違う可能性があります"
            }
            val droppedAccounts = file.accounts.orEmpty().size - accounts.size
            if (droppedAccounts > 0) {
                warnings += "accountKey か name が無い科目を ${droppedAccounts}件 読み飛ばしました"
            }
            val droppedMemos = file.memoTemplates.orEmpty().size - memos.size
            if (droppedMemos > 0) {
                warnings += "memoKey か name が無い摘要を ${droppedMemos}件 読み飛ばしました"
            }
            val fiscalYearChanged = previous != null && previous.fiscalYear != fiscalYear

            // ---- スナップショットを丸ごと入れ替える ----
            // 差分更新にすると「ファイルから消えた＝無効化」が表現できなくなる。
            // 年度が変わった場合もここで旧年度のデータごと消える（保持は常に1年度分）
            val meta = AoiroChoboVocabMeta(
                schemaVersion = file.schemaVersion,
                generatedAt = file.generatedAt.orEmpty(),
                generatedByApp = file.generatedBy?.app.orEmpty(),
                generatedByAppVersion = file.generatedBy?.appVersion.orEmpty(),
                fiscalYear = fiscalYear,
                fiscalStartDate = file.fiscalYear.startDate.orEmpty(),
                fiscalEndDate = file.fiscalYear.endDate.orEmpty(),
                contentHash = contentHash,
                enumsJson = file.enums?.toString().orEmpty()
            )
            vocabDao.replaceSnapshot(meta, accounts, memos)

            // ---- 3・4. name が変わったキーの紐付けを外し、消えたキーを数える ----
            val accountNames = accounts.associate { it.accountKey to it.name }
            val memoNames = memos.associate { it.memoKey to it.name }
            val unlinked = mutableListOf<String>()
            var dangling = 0

            // 紐付けはキー単位で見る。同じ科目を指す学習が何件あっても、作り替えの判定は 1 回。
            val linkDao = db.aoiroChoboLinkDao()

            linkDao.getLinkedAccountKeys().forEach { link ->
                when (val verdict = verify(link.vocabKey, link.name, accountNames)) {
                    is Verdict.Renamed -> {
                        // 摘要は科目に属する（counterAccountKey == accountKey のものしか選べない）ので、
                        // 科目を外すときは摘要も道連れにする。clearAccountKeyEverywhere がまとめて外す
                        linkDao.clearAccountKeyEverywhere(link.vocabKey)
                        unlinked += "科目「${verdict.before}」（${verdict.after} に変わった）"
                    }
                    Verdict.Missing -> dangling++
                    is Verdict.NameUnknown ->
                        linkDao.fillAccountKeyNameEverywhere(link.vocabKey, verdict.name)
                    Verdict.Unchanged -> Unit
                }
            }

            linkDao.getLinkedMemoKeys().forEach { link ->
                when (val verdict = verify(link.vocabKey, link.name, memoNames)) {
                    is Verdict.Renamed -> {
                        linkDao.clearMemoKeyEverywhere(link.vocabKey)
                        unlinked += "摘要「${verdict.before}」（${verdict.after} に変わった）"
                    }
                    Verdict.Missing -> dangling++
                    is Verdict.NameUnknown ->
                        linkDao.fillMemoKeyNameEverywhere(link.vocabKey, verdict.name)
                    Verdict.Unchanged -> Unit
                }
            }

            // ---- 5. 未確定の学習件数 ----
            Result.Imported(
                accountCount = accounts.size,
                memoCount = memos.size,
                fiscalYear = fiscalYear,
                fiscalYearChanged = fiscalYearChanged,
                ageDays = ageDays,
                unlinkedByRename = unlinked,
                danglingLinks = dangling,
                learningWithoutAccount = linkDao.countWithoutAccountKey(),
                learningWithoutMemo = linkDao.countWithoutMemoKey(),
                warnings = warnings
            )
        }

    /** 取込時の判定結果。ユニットテストから突けるよう internal にしている */
    internal sealed interface Verdict {
        /** キーは生きていて名前も同じ */
        object Unchanged : Verdict

        /** 名前が変わった＝作り替えられたので紐付けを外す */
        data class Renamed(val before: String, val after: String) : Verdict

        /** キーがファイルから消えた（科目・摘要の無効化） */
        object Missing : Verdict

        /** 紐付けはあるが「そのとき見た名前」を持っていない。今の名前を控える */
        data class NameUnknown(val name: String) : Verdict
    }

    internal fun verify(key: String, seenName: String?, currentNames: Map<String, String>): Verdict {
        val current = currentNames[key] ?: return Verdict.Missing
        if (seenName.isNullOrBlank()) return Verdict.NameUnknown(current)
        // 「（正規化しても）変わっていたら外す」（CHANGELOG 2026-09-13 改訂・変更2）
        return if (toCanonicalKey(seenName) == toCanonicalKey(current)) {
            Verdict.Unchanged
        } else {
            Verdict.Renamed(seenName, current)
        }
    }

    /** 未知の enum 値を拾う。中止はせず、警告として見せるだけ */
    private fun collectEnumWarnings(
        accounts: List<AoiroChoboAccount>,
        memos: List<AoiroChoboMemoTemplate>
    ): List<String> {
        val warnings = mutableListOf<String>()

        fun check(label: String, values: Collection<String?>, known: Set<String>) {
            val unknown = values.filterNotNull().filter { it.isNotBlank() && it !in known }.distinct()
            if (unknown.isNotEmpty()) {
                warnings += "$label にこのアプリが知らない値があります: ${unknown.joinToString(", ")}"
            }
        }

        check("勘定科目の種類", accounts.map { it.accountType }, KNOWN_ACCOUNT_TYPES)
        check("元帳の所属", accounts.map { it.ledgerAffinity }, KNOWN_LEDGER_VALUES)
        check("科目の税区分", accounts.map { it.defaultTaxCategory }, KNOWN_TAX_CATEGORIES)
        check("摘要の元帳種別", memos.map { it.ledgerType }, KNOWN_LEDGER_VALUES)
        check("税率", memos.flatMap { listOf(it.taxRate, it.creditTaxRate) }, KNOWN_TAX_RATES)

        // 預金スロットの有効値は 1〜5（0 や 6 以上は契約違反）
        val badSlots = (accounts.mapNotNull { it.bankSlotNo } + memos.mapNotNull { it.bankSlotNo })
            .filter { it !in 1..5 }
            .distinct()
        if (badSlots.isNotEmpty()) {
            warnings += "預金スロット番号の有効値は1〜5ですが ${badSlots.joinToString(", ")} が入っています"
        }
        return warnings
    }

    /** generatedAt（ISO8601）の日付部分だけ見て経過日数を出す。読めなければ null */
    fun ageInDays(generatedAt: String?): Long? {
        val datePart = generatedAt?.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
        return try {
            val generated = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(datePart) ?: return null
            TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - generated.time).coerceAtLeast(0)
        } catch (e: Exception) {
            null
        }
    }
}
