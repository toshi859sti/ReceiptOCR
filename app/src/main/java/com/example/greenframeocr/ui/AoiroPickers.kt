package com.example.greenframeocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.util.AoiroChoboAccountRules
import com.example.greenframeocr.util.RomajiSearch

// あおいろ帳簿の科目・摘要を選ぶ部品。JA 購買の商品と通帳の摘要パターンで共用する。
// 用途ごとに違うのは「どの摘要を候補にするか」だけなので、それを memoCandidates で受け取る

/**
 * あおいろモードの「科目 → 摘要」欄。
 *
 * 摘要は科目を選ぶまで出さない。科目に候補の摘要が無ければ、摘要なしで保存してよいことを伝える
 * （PC は UnmatchedMemo として受け、PC 側で摘要を決める）。
 *
 * @param memoCandidates 科目キーからその用途の摘要候補を返す
 * @param memoTabLabel 摘要のタブ名（「買掛/仕入」「預金/出金」など）。候補が無いときの説明に使う
 * @param accountLabel 科目欄のラベル（預金は「相手科目」）
 */
@Composable
internal fun AoiroAccountAndMemoFields(
    accounts: List<AoiroChoboAccount>,
    memos: List<AoiroChoboMemoTemplate>,
    memoCandidates: (String) -> List<AoiroChoboMemoTemplate>,
    memoTabLabel: String,
    accountLabel: String = "あおいろ科目",
    accountKey: String?,
    memoKey: String?,
    fallbackAccountName: String?,
    fallbackMemoName: String?,
    onPickAccount: () -> Unit,
    onPickMemo: () -> Unit,
    onClearAccount: () -> Unit,
    onClearMemo: () -> Unit
) {
    if (accounts.isEmpty()) {
        Text(
            "あおいろ帳簿の科目・摘要がまだ取り込まれていません。設定画面の「AoiroChobo 科目・摘要を取り込む」から取り込んでください。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .padding(12.dp)
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val accountName = accountKey?.let { key ->
            accounts.find { it.accountKey == key }?.name ?: fallbackAccountName ?: key
        }
        PickerField(
            label = accountLabel,
            value = accountName ?: "未設定",
            hasValue = accountKey != null,
            onPick = onPickAccount,
            onClear = onClearAccount
        )

        if (accountKey != null) {
            val candidates = memoCandidates(accountKey)
            if (candidates.isEmpty() && memoKey == null) {
                Text(
                    "この科目には${memoTabLabel}の摘要がありません。摘要なしで PC に送り、PC 側で摘要を決めます。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val memo = memoKey?.let { key -> memos.find { it.memoKey == key } }
                PickerField(
                    label = "あおいろ摘要",
                    value = memo?.name ?: memoKey?.let { fallbackMemoName ?: it } ?: "未選択",
                    hasValue = memoKey != null,
                    onPick = onPickMemo,
                    onClear = onClearMemo
                )
                memo?.let {
                    Text(
                        memoDetail(it),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerField(
    label: String,
    value: String,
    hasValue: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            Row {
                if (hasValue) {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "クリア") }
                }
                IconButton(onClick = onPick) { Icon(Icons.Default.ArrowDropDown, "選択") }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick)
    )
}

/** 摘要が仕訳に持ち込む値（税率・事業割合）。PC は摘要側の事業割合を使う */
private fun memoDetail(memo: AoiroChoboMemoTemplate): String = buildList {
    memo.taxRate?.let { add("税率 " + (AoiroChoboAccountRules.taxRateLabel(it) ?: it)) }
    memo.businessRatio?.let { add("事業割合 $it%") }
}.joinToString("・")

/**
 * あおいろ科目の選択。その用途で使ってよい科目（契約 §4.5）だけを枠番号順に出す。
 * 科目ごとに候補の摘要の件数を添える（0 件なら摘要なしで送ることになる）。
 *
 * @param subject タイトルに出す対象（商品名・通帳の摘要）。空なら [emptySubject]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AoiroAccountPickerDialog(
    subject: String,
    emptySubject: String = "新規商品",
    accounts: List<AoiroChoboAccount>,
    allAccounts: List<AoiroChoboAccount>,
    memoCandidates: (String) -> List<AoiroChoboMemoTemplate>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 絞り込みで外した科目は既定で隠す。ただし今選ばれている科目が外側にあれば、最初から全部見せる
    val hidden = allAccounts.size - accounts.size
    var showAll by remember { mutableStateOf(selectedKey != null && accounts.none { it.accountKey == selectedKey }) }
    val shown = if (showAll) allAccounts else accounts
    // 弥生の科目選択と同じく、科目名か検索文字（PC の searchKey・ローマ字）で絞る。
    // 日本語キーボードで打ったかな（どう → douryoku）でも検索文字に当たる
    var searchQuery by remember { mutableStateOf("") }
    val query = searchQuery.trim()
    val filtered = shown.filter {
        query.isEmpty() || it.name.contains(query, ignoreCase = true) || RomajiSearch.matches(it.searchKey, query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(subject.ifEmpty { emptySubject }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("あおいろ科目を選択", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hidden > 0) {
                    FilterChip(
                        selected = showAll,
                        onClick = { showAll = !showAll },
                        label = { Text("絞り込み外も表示（${hidden}件）", fontSize = 12.sp) }
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("検索（科目名・検索文字）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }
            )
            Text(
                text = "${filtered.size}件",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
            )
            // 切り替え・検索のたびに先頭に戻す。そのままだと表示中の行が基準になり、上に増えた科目が画面外に隠れる
            key(showAll, searchQuery) { LazyColumn {
                items(filtered, key = { it.accountKey }) { account ->
                    val memoCount = memoCandidates(account.accountKey).size
                    PickerRow(
                        selected = account.accountKey == selectedKey,
                        title = account.name,
                        subtitle = listOfNotNull(
                            account.searchKey.ifBlank { null },
                            account.displayGroup,
                            if (memoCount == 0) "摘要なし" else "摘要 $memoCount 件"
                        ).joinToString("・"),
                        onClick = { onSelect(account.accountKey) }
                    )
                }
            } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/**
 * あおいろ摘要の選択。選んだ科目のその用途の摘要だけ（[memos] は絞った後の候補）。先頭に「摘要なし」を置く。
 * 事業割合だけ違う組の摘要には「要確定」を付ける（帳簿の金額が変わるので、ここで確定したものだけが使われる）。
 */
@Composable
internal fun AoiroMemoPickerDialog(
    subject: String,
    emptySubject: String = "新規商品",
    memos: List<AoiroChoboMemoTemplate>,
    ratioSensitive: Set<String>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(subject.ifEmpty { emptySubject }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("あおいろ摘要を選択", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                item(key = "none") {
                    PickerRow(
                        selected = selectedKey == null,
                        title = "摘要なし",
                        subtitle = "PC 側で摘要を決める",
                        onClick = { onSelect(null) }
                    )
                }
                items(memos, key = { it.memoKey }) { memo ->
                    PickerRow(
                        selected = memo.memoKey == selectedKey,
                        title = memo.name,
                        subtitle = memoDetail(memo),
                        tag = if (memo.memoKey in ratioSensitive) "要確定" else null,
                        onClick = { onSelect(memo.memoKey) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun PickerRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    tag: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        tag?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = Color(0xFF8A4B00),
                modifier = Modifier
                    .background(Color(0xFFFFE0B2), MaterialTheme.shapes.small)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}
