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
import com.example.greenframeocr.util.AoiroChoboUsageRules

/** あおいろの相手科目・摘要の選択結果。名前は選んだときの PC 側の名前（改名の検知に使う・契約 §4.6） */
data class AoiroLinkSelection(
    val accountKey: String?,
    val accountKeyName: String?,
    val memoKey: String?,
    val memoKeyName: String?
)

/**
 * 通帳の摘要パターン（グループ）または 1 明細に、あおいろの相手科目・摘要を付けるダイアログ。
 *
 * 預金口座の側は通帳ごとに決まっている（通帳の管理）ので、ここで選ぶのは相手科目だけ。
 * 摘要は入金なら「預金/入金」、出金なら「預金/出金」のタブから、選んだ科目のものだけを出す。
 *
 * @param resetHint 科目を外したときの意味。グループなら「未設定」、明細なら「グループの設定に戻す」
 */
@Composable
fun AoiroDepositLinkDialog(
    title: String,
    subject: String,
    isIncome: Boolean,
    accounts: List<AoiroChoboAccount>,
    memos: List<AoiroChoboMemoTemplate>,
    usage: List<AoiroChoboAccountUsage>,
    initialAccountKey: String?,
    initialAccountKeyName: String?,
    initialMemoKey: String?,
    initialMemoKeyName: String?,
    note: String,
    resetHint: String,
    onDismiss: () -> Unit,
    onSave: (AoiroLinkSelection) -> Unit
) {
    var accountKey by remember { mutableStateOf(initialAccountKey) }
    var memoKey by remember { mutableStateOf(initialMemoKey) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var showMemoPicker by remember { mutableStateOf(false) }
    val memoCandidates = { key: String -> AoiroChoboDepositRules.memoCandidates(key, isIncome, memos) }

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
                    memoTabLabel = if (isIncome) "預金/入金" else "預金/出金",
                    accountLabel = "相手科目（あおいろ）",
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
            accounts = AoiroChoboUsageRules.candidates(AoiroChoboUsageRules.Usage.DEPOSIT, accounts, usage),
            allAccounts = AoiroChoboDepositRules.accountCandidates(accounts),
            memoCandidates = memoCandidates,
            selectedKey = accountKey,
            onSelect = { key ->
                if (key != accountKey) {
                    accountKey = key
                    memoKey = AoiroChoboDepositRules.preselectedMemo(key, isIncome, memos)?.memoKey
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
