package com.example.greenframeocr.data

import androidx.room.Dao
import androidx.room.Query

/**
 * 学習テーブルが持つ AoiroChobo の紐付け（`accountKey` / `memoKey`）をまとめて扱う。
 *
 * 紐付けは 5 つのテーブルに散っている（商品名・通帳パターン・レシート品目・支払方法・
 * 預金の個別上書き）。`vocabulary.json` の取込は「name が変わったキーの紐付けを外す」を
 * **キー単位**で行う必要があるので、テーブルごとに DAO を呼び分けるのではなくここに集める。
 *
 * 外すのは学習の行そのものではなく `accountKey` / `memoKey` の列だけ。弥生用の
 * `yayoiAccountId` は残る（弥生 CSV 出力は AoiroChobo と無関係に動き続ける）。
 */
@Dao
interface AoiroChoboLinkDao {

    // ---- 科目キー ----

    /** 学習が使っている科目キーと、確定時に見えていた科目名 */
    @Query(
        """
        SELECT DISTINCT accountKey AS vocabKey, accountKeyName AS name FROM product_master WHERE accountKey IS NOT NULL
        UNION SELECT DISTINCT accountKey, accountKeyName FROM tekiyou_matching_rules WHERE accountKey IS NOT NULL
        UNION SELECT DISTINCT accountKey, accountKeyName FROM general_item_master WHERE accountKey IS NOT NULL
        UNION SELECT DISTINCT accountKey, accountKeyName FROM receipt_payment_method_rules WHERE accountKey IS NOT NULL
        UNION SELECT DISTINCT overrideAccountKey, overrideAccountKeyName FROM deposit_meisai WHERE overrideAccountKey IS NOT NULL
        UNION SELECT DISTINCT aoiroAccountKey, aoiroAccountKeyName FROM passbooks WHERE aoiroAccountKey IS NOT NULL
        """
    )
    suspend fun getLinkedAccountKeys(): List<LinkKeyName>

    /**
     * その科目キーの紐付けを全テーブルから外す。
     *
     * 摘要は科目に属する（`counterAccountKey == accountKey` のものしか選べない）ので、
     * 科目を外したら摘要も道連れにする。残すと科目の無い摘要という状態ができてしまう。
     */
    @Query(
        """
        UPDATE product_master SET accountKey = NULL, accountKeyName = NULL, memoKey = NULL, memoKeyName = NULL
        WHERE accountKey = :accountKey
        """
    )
    suspend fun clearAccountKeyInProducts(accountKey: String)

    @Query(
        """
        UPDATE tekiyou_matching_rules SET accountKey = NULL, accountKeyName = NULL, memoKey = NULL, memoKeyName = NULL
        WHERE accountKey = :accountKey
        """
    )
    suspend fun clearAccountKeyInMatchingRules(accountKey: String)

    @Query(
        """
        UPDATE general_item_master SET accountKey = NULL, accountKeyName = NULL, memoKey = NULL, memoKeyName = NULL
        WHERE accountKey = :accountKey
        """
    )
    suspend fun clearAccountKeyInItems(accountKey: String)

    @Query("UPDATE receipt_payment_method_rules SET accountKey = NULL, accountKeyName = NULL WHERE accountKey = :accountKey")
    suspend fun clearAccountKeyInPaymentRules(accountKey: String)

    @Query(
        """
        UPDATE deposit_meisai
        SET overrideAccountKey = NULL, overrideAccountKeyName = NULL,
            overrideMemoKey = NULL, overrideMemoKeyName = NULL
        WHERE overrideAccountKey = :accountKey
        """
    )
    suspend fun clearAccountKeyInDepositOverrides(accountKey: String)

    /** 通帳のあおいろ口座。学習ではないが、口座科目の作り替えで外す扱いは同じ */
    @Query("UPDATE passbooks SET aoiroAccountKey = NULL, aoiroAccountKeyName = NULL WHERE aoiroAccountKey = :accountKey")
    suspend fun clearAccountKeyInPassbooks(accountKey: String)

    suspend fun clearAccountKeyEverywhere(accountKey: String) {
        clearAccountKeyInPassbooks(accountKey)
        clearAccountKeyInProducts(accountKey)
        clearAccountKeyInMatchingRules(accountKey)
        clearAccountKeyInItems(accountKey)
        clearAccountKeyInPaymentRules(accountKey)
        clearAccountKeyInDepositOverrides(accountKey)
    }

    /** 名前を控えていなかった紐付けに、いま見えている名前を記録する（次回以降の変化検出のため） */
    @Query("UPDATE product_master SET accountKeyName = :name WHERE accountKey = :accountKey AND accountKeyName IS NULL")
    suspend fun fillAccountKeyNameInProducts(accountKey: String, name: String)

    @Query("UPDATE tekiyou_matching_rules SET accountKeyName = :name WHERE accountKey = :accountKey AND accountKeyName IS NULL")
    suspend fun fillAccountKeyNameInMatchingRules(accountKey: String, name: String)

    @Query("UPDATE general_item_master SET accountKeyName = :name WHERE accountKey = :accountKey AND accountKeyName IS NULL")
    suspend fun fillAccountKeyNameInItems(accountKey: String, name: String)

    @Query("UPDATE receipt_payment_method_rules SET accountKeyName = :name WHERE accountKey = :accountKey AND accountKeyName IS NULL")
    suspend fun fillAccountKeyNameInPaymentRules(accountKey: String, name: String)

    @Query("UPDATE deposit_meisai SET overrideAccountKeyName = :name WHERE overrideAccountKey = :accountKey AND overrideAccountKeyName IS NULL")
    suspend fun fillAccountKeyNameInDepositOverrides(accountKey: String, name: String)

    @Query("UPDATE passbooks SET aoiroAccountKeyName = :name WHERE aoiroAccountKey = :accountKey AND aoiroAccountKeyName IS NULL")
    suspend fun fillAccountKeyNameInPassbooks(accountKey: String, name: String)

    suspend fun fillAccountKeyNameEverywhere(accountKey: String, name: String) {
        fillAccountKeyNameInPassbooks(accountKey, name)
        fillAccountKeyNameInProducts(accountKey, name)
        fillAccountKeyNameInMatchingRules(accountKey, name)
        fillAccountKeyNameInItems(accountKey, name)
        fillAccountKeyNameInPaymentRules(accountKey, name)
        fillAccountKeyNameInDepositOverrides(accountKey, name)
    }

    // ---- 摘要キー ----

    @Query(
        """
        SELECT DISTINCT memoKey AS vocabKey, memoKeyName AS name FROM product_master WHERE memoKey IS NOT NULL
        UNION SELECT DISTINCT memoKey, memoKeyName FROM tekiyou_matching_rules WHERE memoKey IS NOT NULL
        UNION SELECT DISTINCT memoKey, memoKeyName FROM general_item_master WHERE memoKey IS NOT NULL
        UNION SELECT DISTINCT overrideMemoKey, overrideMemoKeyName FROM deposit_meisai WHERE overrideMemoKey IS NOT NULL
        """
    )
    suspend fun getLinkedMemoKeys(): List<LinkKeyName>

    @Query("UPDATE product_master SET memoKey = NULL, memoKeyName = NULL WHERE memoKey = :memoKey")
    suspend fun clearMemoKeyInProducts(memoKey: String)

    @Query("UPDATE tekiyou_matching_rules SET memoKey = NULL, memoKeyName = NULL WHERE memoKey = :memoKey")
    suspend fun clearMemoKeyInMatchingRules(memoKey: String)

    @Query("UPDATE general_item_master SET memoKey = NULL, memoKeyName = NULL WHERE memoKey = :memoKey")
    suspend fun clearMemoKeyInItems(memoKey: String)

    @Query("UPDATE deposit_meisai SET overrideMemoKey = NULL, overrideMemoKeyName = NULL WHERE overrideMemoKey = :memoKey")
    suspend fun clearMemoKeyInDepositOverrides(memoKey: String)

    suspend fun clearMemoKeyEverywhere(memoKey: String) {
        clearMemoKeyInProducts(memoKey)
        clearMemoKeyInMatchingRules(memoKey)
        clearMemoKeyInItems(memoKey)
        clearMemoKeyInDepositOverrides(memoKey)
    }

    @Query("UPDATE product_master SET memoKeyName = :name WHERE memoKey = :memoKey AND memoKeyName IS NULL")
    suspend fun fillMemoKeyNameInProducts(memoKey: String, name: String)

    @Query("UPDATE tekiyou_matching_rules SET memoKeyName = :name WHERE memoKey = :memoKey AND memoKeyName IS NULL")
    suspend fun fillMemoKeyNameInMatchingRules(memoKey: String, name: String)

    @Query("UPDATE general_item_master SET memoKeyName = :name WHERE memoKey = :memoKey AND memoKeyName IS NULL")
    suspend fun fillMemoKeyNameInItems(memoKey: String, name: String)

    @Query("UPDATE deposit_meisai SET overrideMemoKeyName = :name WHERE overrideMemoKey = :memoKey AND overrideMemoKeyName IS NULL")
    suspend fun fillMemoKeyNameInDepositOverrides(memoKey: String, name: String)

    suspend fun fillMemoKeyNameEverywhere(memoKey: String, name: String) {
        fillMemoKeyNameInProducts(memoKey, name)
        fillMemoKeyNameInMatchingRules(memoKey, name)
        fillMemoKeyNameInItems(memoKey, name)
        fillMemoKeyNameInDepositOverrides(memoKey, name)
    }

    // ---- 未確定の件数（取込結果ダイアログ用） ----

    /** 科目がまだ決まっていない学習の件数。この学習に当たった仕訳は UnmatchedAccount で出る */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM product_master WHERE accountKey IS NULL) +
               (SELECT COUNT(*) FROM tekiyou_matching_rules WHERE accountKey IS NULL) +
               (SELECT COUNT(*) FROM general_item_master WHERE accountKey IS NULL) +
               (SELECT COUNT(*) FROM receipt_payment_method_rules WHERE accountKey IS NULL)
        """
    )
    suspend fun countWithoutAccountKey(): Int

    /** 科目は決まったが摘要が未確定の学習の件数。UnmatchedMemo で出る（科目は出せる） */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM product_master WHERE accountKey IS NOT NULL AND memoKey IS NULL) +
               (SELECT COUNT(*) FROM tekiyou_matching_rules WHERE accountKey IS NOT NULL AND memoKey IS NULL) +
               (SELECT COUNT(*) FROM general_item_master WHERE accountKey IS NOT NULL AND memoKey IS NULL)
        """
    )
    suspend fun countWithoutMemoKey(): Int
}

/** キーと、確定時に控えた表示名。名前を控えていなければ null */
data class LinkKeyName(
    val vocabKey: String,
    val name: String?
)
