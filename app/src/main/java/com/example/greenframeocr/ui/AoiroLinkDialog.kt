package com.example.greenframeocr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboAccountUsage
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.util.AoiroChoboDepositRules
import com.example.greenframeocr.util.AoiroChoboMemoRules
import com.example.greenframeocr.util.AoiroChoboPurchaseRules
import com.example.greenframeocr.util.AoiroChoboReceiptRules
import com.example.greenframeocr.util.AoiroChoboUsageRules

/** あおいろの科目・摘要の選択結果。名前は選んだときの PC 側の名前（改名の検知に使う・契約 §4.6） */
data class AoiroLinkSelection(
    val accountKey: String?,
    val accountKeyName: String?,
    val memoKey: String?,
    val memoKeyName: String?
)

/**
 * 用途ごとに違う「どの科目・摘要を候補にするか」。
 *
 * @param usage 農家の絞り込み（[AoiroChoboUsageRules]）の列
 * @param allAccounts PC が許す科目（「絞り込み外も表示」で出す全体）
 * @param memoCandidates 科目キー → その用途の摘要候補
 * @param memoTabLabel 摘要のタブ名。候補が無いときの説明に使う
 * @param accountLabel 科目欄のラベル
 */
class AoiroLinkKind(
    val usage: AoiroChoboUsageRules.Usage,
    val allAccounts: (List<AoiroChoboAccount>) -> List<AoiroChoboAccount>,
    val memoCandidates: (String, List<AoiroChoboMemoTemplate>) -> List<AoiroChoboMemoTemplate>,
    val memoTabLabel: String,
    val accountLabel: String
) {
    /**
     * 摘要の選択に出す候補すべて：PC がその用途に許す科目（[allAccounts]）それぞれの摘要候補を合わせたもの。
     * 農家の絞り込みで外した科目の摘要も出す（摘要から選ぶと科目が決まるので、外した科目にも届くように）
     */
    fun tabMemos(accounts: List<AoiroChoboAccount>, memos: List<AoiroChoboMemoTemplate>): List<AoiroChoboMemoTemplate> =
        allAccounts(accounts).flatMap { memoCandidates(it.accountKey, memos) }.sortedBy { it.displayOrder }

    companion object {
        /** 通帳の摘要パターン・明細の相手科目。[isIncome] は入金か */
        fun deposit(isIncome: Boolean) = AoiroLinkKind(
            usage = AoiroChoboUsageRules.Usage.DEPOSIT,
            allAccounts = AoiroChoboDepositRules::accountCandidates,
            memoCandidates = { key, memos -> AoiroChoboDepositRules.memoCandidates(key, isIncome, memos) },
            memoTabLabel = if (isIncome) "預金/入金" else "預金/出金",
            accountLabel = "相手科目（あおいろ）"
        )

        /**
         * レシートの品目の借方。摘要は [payment]（支払方法の科目）で決まる帳簿のものから選ぶ
         * （[AoiroChoboReceiptRules.ledgerOf]）。品目グループには既定の支払方法、明細の個別変更にはそのレシートの支払方法を渡す。
         * 支払方法が決まらなければ現金出納帳の摘要にする
         */
        fun receiptItem(payment: AoiroChoboAccount?): AoiroLinkKind {
            val ledger = AoiroChoboReceiptRules.ledgerOf(payment) ?: AoiroChoboReceiptRules.Ledger.CASH
            return AoiroLinkKind(
                usage = AoiroChoboUsageRules.Usage.RECEIPT,
                allAccounts = AoiroChoboReceiptRules::accountCandidates,
                memoCandidates = { key, memos ->
                    AoiroChoboReceiptRules.memoCandidates(key, ledger, payment?.accountKey, memos)
                },
                memoTabLabel = if (ledger == AoiroChoboReceiptRules.Ledger.TRANSFER) {
                    "${ledger.memoTabLabel}・貸方 ${payment?.name}"
                } else ledger.memoTabLabel,
                accountLabel = "あおいろ科目"
            )
        }

        /** JA 購買の商品の借方（商品編集ダイアログで使う） */
        val purchase = AoiroLinkKind(
            usage = AoiroChoboUsageRules.Usage.PURCHASE,
            allAccounts = AoiroChoboPurchaseRules::accountCandidates,
            memoCandidates = AoiroChoboPurchaseRules::memoCandidates,
            memoTabLabel = "買掛/仕入",
            accountLabel = "あおいろ科目"
        )
    }
}

/**
 * 通帳の摘要パターン・明細、レシートの品目グループに、あおいろの科目・摘要を付けるダイアログ。
 * 摘要（上）から選ぶと科目（下）はその摘要の相手科目になる。科目を先に選んでもよく、
 * そのとき科目に属さない摘要は外す（摘要は相手科目を 1 つ持つ）。摘要は空欄でもよい。
 *
 * @param resetHint 科目を外したときの意味（グループなら「未設定」、明細なら「グループの設定に戻す」）
 * @param extraNote 摘要欄の下に出す補足（なければ null）
 */
@Composable
fun AoiroLinkDialog(
    title: String,
    subject: String,
    kind: AoiroLinkKind,
    accounts: List<AoiroChoboAccount>,
    memos: List<AoiroChoboMemoTemplate>,
    usage: List<AoiroChoboAccountUsage>,
    initialAccountKey: String?,
    initialAccountKeyName: String?,
    initialMemoKey: String?,
    initialMemoKeyName: String?,
    note: String,
    resetHint: String,
    extraNote: String? = null,
    onDismiss: () -> Unit,
    onSave: (AoiroLinkSelection) -> Unit
) {
    var accountKey by remember { mutableStateOf(initialAccountKey) }
    var memoKey by remember { mutableStateOf(initialMemoKey) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var showMemoPicker by remember { mutableStateOf(false) }
    val memoCandidates = { key: String -> kind.memoCandidates(key, memos) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    subject,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(note, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AoiroMemoAndAccountFields(
                    accounts = accounts,
                    memos = memos,
                    memoCandidates = memoCandidates,
                    memoTabLabel = kind.memoTabLabel,
                    accountLabel = kind.accountLabel,
                    accountKey = accountKey,
                    memoKey = memoKey,
                    fallbackAccountName = initialAccountKeyName.takeIf { accountKey == initialAccountKey },
                    fallbackMemoName = initialMemoKeyName.takeIf { memoKey == initialMemoKey },
                    onPickAccount = { showAccountPicker = true },
                    onPickMemo = { showMemoPicker = true },
                    onClearAccount = { accountKey = null; memoKey = null },
                    onClearMemo = { memoKey = null }
                )
                if (accountKey == null && accounts.isNotEmpty()) {
                    Text(resetHint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (extraNote != null && accountKey != null) {
                    Text(extraNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = accounts.isNotEmpty(),
                onClick = {
                    // 選び直していない値は、今の辞書に無くても（PC で消えていても）保存時の名前のまま残す
                    val accountName = accountKey?.let { key ->
                        accounts.find { it.accountKey == key }?.name
                            ?: initialAccountKeyName.takeIf { key == initialAccountKey }
                    }
                    val memoName = memoKey?.let { key ->
                        memos.find { it.memoKey == key }?.name
                            ?: initialMemoKeyName.takeIf { key == initialMemoKey }
                    }
                    onSave(AoiroLinkSelection(accountKey, accountName, memoKey, memoName))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )

    // 科目を選び直したら、その科目に属さない摘要は外す（摘要は相手科目を 1 つ持つ）。摘要を勝手に埋めはしない
    if (showAccountPicker) {
        AoiroAccountPickerDialog(
            subject = subject,
            accounts = AoiroChoboUsageRules.candidates(kind.usage, accounts, usage),
            allAccounts = kind.allAccounts(accounts),
            memoCandidates = memoCandidates,
            selectedKey = accountKey,
            onSelect = { key ->
                if (key != accountKey) {
                    accountKey = key
                    if (memoKey != null && memoCandidates(key).none { it.memoKey == memoKey }) memoKey = null
                }
                showAccountPicker = false
            },
            onDismiss = { showAccountPicker = false }
        )
    }

    // 摘要を選んだら科目はその摘要の相手科目にする
    if (showMemoPicker) {
        AoiroMemoPickerDialog(
            subject = subject,
            tabMemos = remember(accounts, memos) { kind.tabMemos(accounts, memos) },
            accounts = accounts,
            currentAccountKey = accountKey,
            ratioSensitive = remember(memos) { AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos) },
            selectedKey = memoKey,
            onSelect = { memo ->
                memoKey = memo?.memoKey
                memo?.let(AoiroChoboMemoRules::accountKeyOf)?.let { accountKey = it }
                showMemoPicker = false
            },
            onDismiss = { showMemoPicker = false }
        )
    }
}
