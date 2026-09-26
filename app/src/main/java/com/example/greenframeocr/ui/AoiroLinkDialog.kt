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
 * @param preselect 科目を選んだ直後に先に埋めてよい摘要
 * @param memoTabLabel 摘要のタブ名。候補が無いときの説明に使う
 * @param accountLabel 科目欄のラベル
 */
class AoiroLinkKind(
    val usage: AoiroChoboUsageRules.Usage,
    val allAccounts: (List<AoiroChoboAccount>) -> List<AoiroChoboAccount>,
    val memoCandidates: (String, List<AoiroChoboMemoTemplate>) -> List<AoiroChoboMemoTemplate>,
    val preselect: (String, List<AoiroChoboMemoTemplate>) -> AoiroChoboMemoTemplate?,
    val memoTabLabel: String,
    val accountLabel: String
) {
    companion object {
        /** 通帳の摘要パターン・明細の相手科目。[isIncome] は入金か */
        fun deposit(isIncome: Boolean) = AoiroLinkKind(
            usage = AoiroChoboUsageRules.Usage.DEPOSIT,
            allAccounts = AoiroChoboDepositRules::accountCandidates,
            memoCandidates = { key, memos -> AoiroChoboDepositRules.memoCandidates(key, isIncome, memos) },
            preselect = { key, memos -> AoiroChoboDepositRules.preselectedMemo(key, isIncome, memos) },
            memoTabLabel = if (isIncome) "預金/入金" else "預金/出金",
            accountLabel = "相手科目（あおいろ）"
        )

        /** レシートの品目グループの借方 */
        val receiptItem = AoiroLinkKind(
            usage = AoiroChoboUsageRules.Usage.RECEIPT,
            allAccounts = AoiroChoboReceiptRules::accountCandidates,
            memoCandidates = AoiroChoboReceiptRules::memoCandidates,
            preselect = AoiroChoboReceiptRules::preselectedMemo,
            memoTabLabel = "現金/出金",
            accountLabel = "あおいろ科目"
        )
    }
}

/**
 * 通帳の摘要パターン・明細、レシートの品目グループに、あおいろの科目・摘要を付けるダイアログ。
 * 科目 → その科目で絞った摘要 の順に選ぶ（摘要は科目に属する）。
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
                AoiroAccountAndMemoFields(
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

    // 科目を選び直したら摘要は作り直す（摘要は科目に属する）
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
                    memoKey = kind.preselect(key, memos)?.memoKey
                }
                showAccountPicker = false
            },
            onDismiss = { showAccountPicker = false }
        )
    }

    if (showMemoPicker && accountKey != null) {
        AoiroMemoPickerDialog(
            subject = subject,
            memos = memoCandidates(accountKey!!),
            ratioSensitive = remember(memos) { AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos) },
            selectedKey = memoKey,
            onSelect = { key ->
                memoKey = key
                showMemoPicker = false
            },
            onDismiss = { showMemoPicker = false }
        )
    }
}
