package com.example.greenframeocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.Passbook
import com.example.greenframeocr.data.ReceiptDatabase
import kotlinx.coroutines.launch

/**
 * 通帳の管理（追加・名前・弥生の補助科目・あおいろの口座・削除）。最大 [Passbook.MAX_COUNT] 冊。
 *
 * 明細が残っている通帳は削除できない（先に「この通帳の明細を削除」で空にする）。
 * 通帳を消して明細が宙に浮く／道連れで消える、のどちらも起こさないため。
 *
 * @param onChanged 通帳や明細を変えたとき（呼び出し側が一覧を読み直す）
 */
@Composable
fun PassbookManageDialog(
    database: ReceiptDatabase,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passbooks by remember { mutableStateOf<List<Passbook>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var slotAccounts by remember { mutableStateOf<List<AoiroChoboAccount>>(emptyList()) }
    var editing by remember { mutableStateOf<Passbook?>(null) }
    var isNew by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            passbooks = database.passbookDao().ensureDefault()
            counts = passbooks.associate { it.id to database.depositMeisaiDao().countByPassbook(it.id) }
            slotAccounts = database.aoiroChoboVocabDao().getBankSlotAccounts()
        }
    }
    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通帳の管理") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "CSVを取り込むときに、どの通帳の明細かを選びます。最大${Passbook.MAX_COUNT}冊まで。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                passbooks.forEach { passbook ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isNew = false; editing = passbook }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(passbook.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("${counts[passbook.id] ?: 0}件", fontSize = 13.sp)
                            }
                            Text(
                                "弥生：普通預金" + passbook.yayoiSubAccountName.takeIf { it.isNotBlank() }
                                    ?.let { " ／ $it" }.orEmpty(),
                                fontSize = 12.sp
                            )
                            Text(
                                "あおいろ：" + (aoiroAccountLabel(passbook, slotAccounts) ?: "未設定"),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                if (passbooks.size < Passbook.MAX_COUNT) {
                    OutlinedButton(
                        onClick = {
                            isNew = true
                            editing = Passbook(
                                name = "通帳${passbooks.size + 1}",
                                displayOrder = (passbooks.maxOfOrNull { it.displayOrder } ?: 0) + 1
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("通帳を追加") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )

    editing?.let { target ->
        PassbookEditDialog(
            passbook = target,
            isNew = isNew,
            meisaiCount = counts[target.id] ?: 0,
            canDelete = !isNew && passbooks.size > 1,
            slotAccounts = slotAccounts,
            // 同じ口座を 2 冊に割り当てると PC 側で 1 つの預金出納帳に混ざる
            takenAccountKeys = passbooks.filter { it.id != target.id }.mapNotNull { it.aoiroAccountKey }.toSet(),
            onSave = { saved ->
                scope.launch {
                    if (isNew) database.passbookDao().insert(saved) else database.passbookDao().update(saved)
                    editing = null
                    reload()
                    onChanged()
                }
            },
            onDeleteMeisai = {
                scope.launch {
                    database.depositMeisaiDao().deleteByPassbook(target.id)
                    reload()
                    onChanged()
                }
            },
            onDelete = {
                scope.launch {
                    if (database.depositMeisaiDao().countByPassbook(target.id) == 0) {
                        database.passbookDao().delete(target.id)
                    }
                    editing = null
                    reload()
                    onChanged()
                }
            },
            onDismiss = { editing = null }
        )
    }
}

/** あおいろ口座の表示。取込済みの科目に無ければ「（取込済みの科目にありません）」を付ける */
private fun aoiroAccountLabel(passbook: Passbook, slotAccounts: List<AoiroChoboAccount>): String? {
    val key = passbook.aoiroAccountKey ?: return null
    val account = slotAccounts.firstOrNull { it.accountKey == key }
    return if (account != null) "${account.name}（口座${account.bankSlotNo}）"
    else "${passbook.aoiroAccountKeyName ?: key}（取込済みの科目にありません）"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassbookEditDialog(
    passbook: Passbook,
    isNew: Boolean,
    meisaiCount: Int,
    canDelete: Boolean,
    slotAccounts: List<AoiroChoboAccount>,
    takenAccountKeys: Set<String>,
    onSave: (Passbook) -> Unit,
    onDeleteMeisai: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(passbook.name) }
    var yayoiSub by remember { mutableStateOf(passbook.yayoiSubAccountName) }
    var aoiroKey by remember { mutableStateOf(passbook.aoiroAccountKey) }
    var aoiroExpanded by remember { mutableStateOf(false) }
    var confirmDeleteMeisai by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "通帳を追加" else "通帳の設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前（例：営農口座）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = yayoiSub,
                    onValueChange = { yayoiSub = it },
                    label = { Text("弥生：普通預金の補助科目") },
                    supportingText = { Text("弥生で作った補助科目名。空欄なら補助科目なし") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (slotAccounts.isEmpty()) {
                    Text(
                        "あおいろ帳簿の口座：科目・摘要を取り込むと選べます",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val selected = slotAccounts.firstOrNull { it.accountKey == aoiroKey }
                    ExposedDropdownMenuBox(expanded = aoiroExpanded, onExpandedChange = { aoiroExpanded = it }) {
                        OutlinedTextField(
                            value = selected?.let { "${it.name}（口座${it.bankSlotNo}）" }
                                ?: aoiroKey?.let { passbook.aoiroAccountKeyName ?: it } ?: "未設定",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("あおいろ帳簿の口座") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = aoiroExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = aoiroExpanded, onDismissRequest = { aoiroExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("未設定") },
                                onClick = { aoiroKey = null; aoiroExpanded = false }
                            )
                            slotAccounts.forEach { account ->
                                val taken = account.accountKey in takenAccountKeys
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${account.name}（口座${account.bankSlotNo}）" +
                                                if (taken) "　他の通帳で使用中" else ""
                                        )
                                    },
                                    enabled = !taken,
                                    onClick = { aoiroKey = account.accountKey; aoiroExpanded = false }
                                )
                            }
                        }
                    }
                }
                if (!isNew) {
                    Divider()
                    Text("明細 $meisaiCount 件", fontSize = 13.sp)
                    if (meisaiCount > 0) {
                        OutlinedButton(
                            onClick = { confirmDeleteMeisai = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("この通帳の明細を削除") }
                    }
                    if (canDelete) {
                        OutlinedButton(
                            onClick = onDelete,
                            enabled = meisaiCount == 0,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (meisaiCount == 0) "通帳を削除" else "通帳を削除（先に明細を削除）") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val account = slotAccounts.firstOrNull { it.accountKey == aoiroKey }
                    onSave(
                        passbook.copy(
                            name = name.trim(),
                            yayoiSubAccountName = yayoiSub.trim(),
                            aoiroAccountKey = aoiroKey,
                            // 選び直したときだけ名前を控え直す。触っていなければ前回控えた名前を残す（作り替え検知のため）
                            aoiroAccountKeyName = when {
                                aoiroKey == null -> null
                                aoiroKey == passbook.aoiroAccountKey -> passbook.aoiroAccountKeyName ?: account?.name
                                else -> account?.name
                            }
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )

    if (confirmDeleteMeisai) {
        AlertDialog(
            onDismissRequest = { confirmDeleteMeisai = false },
            title = { Text("確認") },
            text = { Text("「${passbook.name}」の明細 $meisaiCount 件を削除しますか？ 他の通帳の明細は残ります。") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteMeisai = false; onDeleteMeisai() }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteMeisai = false }) { Text("キャンセル") } }
        )
    }
}
