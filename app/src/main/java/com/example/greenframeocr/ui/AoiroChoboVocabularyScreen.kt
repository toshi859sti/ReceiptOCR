package com.example.greenframeocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabMeta
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.util.AoiroChoboMemoRules
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab

/**
 * 取り込んだ AoiroChobo の勘定科目と摘要辞書を見るだけの画面。
 *
 * 中身は PC 側が所有するミラーなので、ここでは編集させない。タブの分け方は PC の科目画面・摘要画面に揃える
 * （docs/integration/examples/AoiroChobo_2024_screens/）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AoiroChoboVocabularyScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    var meta by remember { mutableStateOf<AoiroChoboVocabMeta?>(null) }
    var accounts by remember { mutableStateOf<List<AoiroChoboAccount>>(emptyList()) }
    var memos by remember { mutableStateOf<List<AoiroChoboMemoTemplate>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val dao = database.aoiroChoboVocabDao()
        meta = dao.getMeta()
        accounts = dao.getAllAccounts()
        memos = dao.getAllMemoTemplates()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("あおいろ帳簿 科目・摘要") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!loaded) return@Column

            if (accounts.isEmpty() && memos.isEmpty()) {
                Text(
                    text = "まだ取り込まれていません。\n設定画面の「AoiroChobo 科目・摘要を取り込む」から" +
                        "vocabulary.json を取り込んでください。",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            meta?.let { VocabMetaLine(it) }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("勘定科目（${accounts.size}）") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("摘要辞書（${memos.size}）") }
                )
            }

            when (selectedTab) {
                0 -> AccountList(accounts)
                else -> MemoList(memos, accounts)
            }
        }
    }
}

@Composable
private fun VocabMetaLine(meta: AoiroChoboVocabMeta) {
    // generatedAt は "2026-09-23T19:40:16+09:00"。秒とオフセットは要らない
    val generated = meta.generatedAt.take(16).replace('T', ' ')
    Text(
        text = "${meta.fiscalYear}年度　PC で ${generated} に書き出し",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---- 勘定科目 ----

private val ACCOUNT_TYPE_ORDER = listOf(
    "Asset" to "資産",
    "Liability" to "負債",
    "Income" to "収入",
    "Expense" to "支出",
    "Capital" to "資本"
)

@Composable
private fun AccountList(accounts: List<AoiroChoboAccount>) {
    val knownTypes = ACCOUNT_TYPE_ORDER.map { it.first }.toSet()
    // PC が区分を増やしたら「その他」に出す（REPLY-pc-2026-09-23b.md §4）
    val sections = ACCOUNT_TYPE_ORDER.map { (type, label) ->
        label to accounts.filter { it.accountType == type }
    } + ("その他" to accounts.filter { it.accountType !in knownTypes })

    val byKey = accounts.associateBy { it.accountKey }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        sections.filter { it.second.isNotEmpty() }.forEach { (label, list) ->
            item(key = "type:$label") { SectionHeader("$label（${list.size}）") }
            // 親の直後に内訳科目を並べる。親が一覧に無い内訳はそのまま displayOrder の位置に出す
            val children = list.filter { it.parentAccountKey != null && it.parentAccountKey in byKey }
                .groupBy { it.parentAccountKey }
            val ordered = buildList {
                list.filter { it.parentAccountKey == null || it.parentAccountKey !in byKey }
                    .sortedBy { it.displayOrder }
                    .forEach { parent ->
                        add(parent)
                        children[parent.accountKey]?.sortedBy { it.displayOrder }?.let { addAll(it) }
                    }
            }
            items(ordered, key = { "acct:${it.accountKey}" }) { account ->
                AccountRow(account, isChild = account.parentAccountKey in byKey)
            }
        }
    }
}

@Composable
private fun AccountRow(account: AoiroChoboAccount, isChild: Boolean) {
    val notes = buildList {
        if (!isChild) account.groupName?.let { add(it) }
        account.bankSlotNo?.let { add("預金スロット$it") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isChild) 40.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isChild) "└ ${account.name}" else account.name,
                fontSize = 16.sp
            )
            if (notes.isNotEmpty()) {
                Text(
                    text = notes.joinToString("・"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        account.defaultTaxCategory?.let(::taxCategoryLabel)?.let { Tag(it) }
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

// NA は繰入額など税区分を持たない科目。タグを出しても情報が無いので null
private fun taxCategoryLabel(value: String): String? = when (value) {
    "Taxable" -> "課税"
    "NonTaxable" -> "非課税"
    "NotApplicable" -> "対象外"
    "TaxExempt" -> "免税"
    "NA" -> null
    else -> value
}

// ---- 摘要辞書 ----

@Composable
private fun MemoList(memos: List<AoiroChoboMemoTemplate>, accounts: List<AoiroChoboAccount>) {
    var selected by remember { mutableStateOf(MemoTab.CASH_IN) }
    val accountNames = remember(accounts) { accounts.associate { it.accountKey to it.name } }
    val ratioSensitive = remember(memos) { AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos) }
    val counts = remember(memos) { MemoTab.entries.associateWith { tab -> memos.count { tab.contains(it) } } }
    val shown = remember(memos, selected) {
        memos.filter { selected.contains(it) }.sortedBy { it.displayOrder }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 8.dp) {
            MemoTab.entries.forEach { tab ->
                Tab(
                    selected = selected == tab,
                    onClick = { selected = tab },
                    text = { Text("${tab.label}（${counts[tab] ?: 0}）", fontSize = 13.sp) }
                )
            }
        }

        if (selected.ordinal <= MemoTab.BANK_OUT.ordinal && shown.any { it.memoKey in ratioSensitive }) {
            Text(
                text = "「要確定」は相手科目・税率が同じで事業割合だけ違う摘要です。" +
                    "取り違えると経費の額が変わるので、アプリが自動で選ばず、初回はあなたが選びます。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (shown.isEmpty()) {
            Text(
                text = "このタブの摘要はありません",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.memoKey }) { memo ->
                    MemoRow(memo, accountNames, isRatioSensitive = memo.memoKey in ratioSensitive)
                }
            }
        }
    }
}

@Composable
private fun MemoRow(
    memo: AoiroChoboMemoTemplate,
    accountNames: Map<String, String>,
    isRatioSensitive: Boolean
) {
    fun nameOf(key: String?): String = key?.let { accountNames[it] ?: it } ?: "（未設定）"

    val accountLine = if (memo.ledgerType == "Transfer") {
        "借方 ${nameOf(memo.debitAccountKey)} ／ 貸方 ${nameOf(memo.creditAccountKey)}"
    } else {
        "相手科目 ${nameOf(memo.counterAccountKey)}"
    }
    val ratio = memo.businessRatio

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = memo.name, fontSize = 16.sp)
            Text(
                text = accountLine + (memo.bankSlotNo?.let { "・預金スロット$it 専用" } ?: ""),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                memo.taxRate?.let { Tag(taxRateLabel(it)) }
                if (ratio != null && ratio != 100) Tag("事業$ratio%")
            }
            if (isRatioSensitive) {
                Tag(
                    text = "要確定",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

private fun taxRateLabel(value: String): String = when (value) {
    "10" -> "10%"
    "8" -> "軽減8%"
    "8_old" -> "旧8%"
    "non" -> "非課税"
    "na" -> "対象外"
    "men" -> "免税"
    else -> value
}

// ---- 共通 ----

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Tag(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Text(
        text = text,
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 12.sp,
        color = content
    )
}
