package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * レシート領収書の「支払方法テキスト → 相手科目（貸方勘定科目）」変換ルール。
 * Geminiが合計欄付近から抽出した支払方法の印字テキスト（例:「クレジット」「PayPay」）に対し、
 * keywordが部分一致すればyayoiAccountIdを相手科目として採用する（ユーザーが自由に登録・編集）。
 * どのルールにも一致しない場合は「現金」にフォールバックする（呼び出し側で解決）。
 *
 * 弥生の科目（yayoiAccountId）とあおいろの科目（accountKey）はどちらも null になり得る。
 * あおいろモードで足したルールは弥生の科目を持たない（DB v41 で NOT NULL を外した）。
 */
@Entity(tableName = "receipt_payment_method_rules")
data class ReceiptPaymentMethodRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    /**
     * 弥生の科目。null = 弥生では未設定（あおいろモードで足したルール）。
     * 弥生の出力はこのルールを飛ばして次のルールを見る（どれにも当たらなければ現金）
     */
    val yayoiAccountId: Long? = null,
    val sortOrder: Int = 0,

    /**
     * AoiroChobo の科目参照キー（貸方＝支払方法の科目）。
     * 弥生用の yayoiAccountId とは独立。貸方なので摘要は持たない。
     */
    val accountKey: String? = null,

    /** accountKey を確定したときに見えていた AoiroChobo 側の科目名（作り替え検知用） */
    val accountKeyName: String? = null
)
