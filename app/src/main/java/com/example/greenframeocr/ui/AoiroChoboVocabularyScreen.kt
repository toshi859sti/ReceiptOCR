package com.example.greenframeocr.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabMeta
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.util.AoiroChoboAccountRules
import com.example.greenframeocr.util.AoiroChoboAccountRules.AccountTab
import com.example.greenframeocr.util.AoiroChoboMemoRules
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab

/**
 * 取り込んだ AoiroChobo の勘定科目と摘要辞書を見るだけの画面。
 *
 * 中身は PC 側が所有するミラーなので、ここでは編集させない。タブ・列・色は PC の「科目・残高登録」
 * 「摘要登録」画面に揃える（docs/integration/examples/AoiroChobo_2024_screens/）。
 * PC にあって JSON に無いもの（期首残高・空き枠）は出せない。
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
                0 -> AccountTable(accounts)
                else -> MemoTable(memos, accounts)
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

// ---- PC の画面の色 ----
// 表は PC の見た目に合わせるので、テーマ（ダークモード）に関係なく固定色で描く

private val AccountHeaderColor = Color(0xFFF08090)   // 科目画面の見出し（ピンク）
private val MemoHeaderColor = Color(0xFF4472C4)      // 摘要画面の見出し（青）
private val GroupNamedColor = Color(0xFFD9F7D2)      // グループ名あり（薄緑）
private val GroupNoneColor = Color(0xFFCCCCCC)       // グループ名なし・入力できない欄（灰）
private val SystemNameColor = Color(0xFFE2E0FB)      // システム科目の科目名（薄紫）
private val CellColor = Color.White
private val CellTextColor = Color(0xFF222222)
private val GridColor = Color(0xFFB0B0B0)

// ---- 勘定科目 ----

@Composable
private fun AccountTable(accounts: List<AoiroChoboAccount>) {
    val tabs = remember(accounts) {
        AccountTab.entries.filter { tab ->
            tab != AccountTab.OTHER || accounts.any { AoiroChoboAccountRules.tabOf(it) == AccountTab.OTHER }
        }
    }
    var selected by remember { mutableStateOf(AccountTab.ASSET) }
    val rows = remember(accounts, selected) { AoiroChoboAccountRules.rowsFor(selected, accounts) }

    Column(modifier = Modifier.fillMaxSize()) {
        PcTabBar(
            labels = tabs.map { it.label },
            selectedIndex = tabs.indexOf(selected),
            onSelect = { selected = tabs[it] }
        )

        // 資産・負債は内訳科目、収入・支出は課税区分を出す（PC と同じ列）
        val profitAndLoss = selected == AccountTab.INCOME || selected == AccountTab.EXPENSE
        val columns = if (profitAndLoss) {
            listOf("グループ" to 76.dp, "科目名" to 150.dp, "検索文字" to 104.dp,
                "有効な\n課税区分" to 72.dp, "既定の\n課税区分" to 72.dp)
        } else {
            listOf("グループ" to 76.dp, "科目名" to 150.dp, "内訳科目名" to 110.dp, "検索文字" to 104.dp)
        }

        PcTable(columns = columns, headerColor = AccountHeaderColor, rows = rows, scrollKey = selected, rowKey = { it.account.accountKey }) { row ->
            val a = row.account
            val group = if (row.isChild) null else a.displayGroup?.takeIf { it.isNotBlank() }
            // グループ欄は続く間ずっと同じ色で塗り、名前は先頭の行にだけ出す
            val groupColor = if (rowGroupName(row, rows) != null) GroupNamedColor else GroupNoneColor
            Cell(if (row.startsGroup) group.orEmpty() else "", 76.dp, groupColor, align = TextAlign.End,
                drawGrid = row.startsGroup)
            val nameColor = if (a.isSystem) SystemNameColor else CellColor
            if (profitAndLoss) {
                Cell(if (row.isChild) "└ ${a.name}" else a.name, 150.dp, nameColor)
                Cell(a.searchKey, 104.dp)
                val allowed = AoiroChoboAccountRules.allowedTaxLabel(a)
                Cell(allowed.orEmpty(), 72.dp, if (allowed == null) GroupNoneColor else CellColor, TextAlign.Center)
                val default = AoiroChoboAccountRules.taxCategoryLabel(a.defaultTaxCategory)
                Cell(default.orEmpty(), 72.dp, if (default == null) GroupNoneColor else CellColor, TextAlign.Center)
            } else {
                // 内訳科目は科目名の欄を空けて内訳欄に名前を出す（PC は親の欄を縦に結合している）
                Cell(if (row.isChild) "" else a.name, 150.dp, nameColor)
                val subColor = when {
                    row.isChild -> CellColor
                    row.hasChildren -> CellColor
                    else -> GroupNoneColor
                }
                Cell(if (row.isChild) a.name else "", 110.dp, subColor)
                Cell(a.searchKey, 104.dp)
            }
        }
    }
}

/** 内訳科目は親のグループに属する。色塗りのために親をさかのぼって引く */
private fun rowGroupName(
    row: AoiroChoboAccountRules.AccountRow,
    rows: List<AoiroChoboAccountRules.AccountRow>
): String? {
    val owner = if (row.isChild) {
        rows.firstOrNull { it.account.accountKey == row.account.parentAccountKey }?.account ?: row.account
    } else {
        row.account
    }
    return owner.displayGroup?.takeIf { it.isNotBlank() }
}

// ---- 摘要辞書 ----

/** PC の摘要画面の上段タブ（帳簿）と、その中の下段タブ */
private val MEMO_LEDGERS: List<Pair<String, List<MemoTab>>> = listOf(
    "現金" to listOf(MemoTab.CASH_IN, MemoTab.CASH_OUT),
    "預金" to listOf(MemoTab.BANK_IN, MemoTab.BANK_OUT),
    "売掛" to listOf(MemoTab.AR_IN, MemoTab.AR_OUT),
    "買掛" to listOf(MemoTab.AP_IN, MemoTab.AP_OUT),
    "未払" to listOf(MemoTab.UNPAID_IN, MemoTab.UNPAID_OUT),
    "振替" to listOf(MemoTab.TRANSFER)
)

@Composable
private fun MemoTable(memos: List<AoiroChoboMemoTemplate>, accounts: List<AoiroChoboAccount>) {
    var ledgerIndex by remember { mutableIntStateOf(0) }
    var subIndex by remember { mutableIntStateOf(0) }
    val (_, subTabs) = MEMO_LEDGERS[ledgerIndex]
    val selected = subTabs[subIndex.coerceAtMost(subTabs.lastIndex)]

    val accountNames = remember(accounts) { accounts.associate { it.accountKey to it.name } }
    val ratioSensitive = remember(memos) { AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos) }
    val shown = remember(memos, selected) {
        memos.filter { selected.contains(it) }.sortedBy { it.displayOrder }
    }
    fun nameOf(key: String?): String = key?.let { accountNames[it] ?: it }.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        PcTabBar(
            labels = MEMO_LEDGERS.map { it.first },
            selectedIndex = ledgerIndex,
            onSelect = { ledgerIndex = it; subIndex = 0 }
        )
        if (subTabs.size > 1) {
            PcTabBar(
                labels = subTabs.map { tab ->
                    "${tab.label.substringAfter('/')}（${memos.count { tab.contains(it) }}）"
                },
                selectedIndex = subIndex,
                onSelect = { subIndex = it }
            )
        }

        if (shown.any { it.memoKey in ratioSensitive }) {
            Text(
                text = "「要確定」は相手科目・税率が同じで事業割合だけ違う摘要です。" +
                    "取り違えると経費の額が変わるので、アプリが自動で選ばず、初回はあなたが選びます。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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
            return@Column
        }

        // 現金タブは「預金と共有」、預金タブは「現金と共有」。売掛・買掛・未払には無い（PC と同じ）
        val sharedHeader = when (selected) {
            MemoTab.CASH_IN, MemoTab.CASH_OUT -> "預金と\n共有"
            MemoTab.BANK_IN, MemoTab.BANK_OUT -> "現金と\n共有"
            else -> null
        }

        if (selected == MemoTab.TRANSFER) {
            val columns = listOf("摘要名" to 170.dp, "検索文字" to 96.dp,
                "借方科目" to 130.dp, "借方\n税率" to 60.dp, "借方\n事業割合" to 64.dp,
                "貸方科目" to 130.dp, "貸方\n税率" to 60.dp, "貸方\n事業割合" to 64.dp)
            PcTable(columns = columns, headerColor = MemoHeaderColor, rows = shown, scrollKey = selected, rowKey = { it.memoKey }) { m ->
                MemoNameCell(m, m.memoKey in ratioSensitive)
                Cell(m.searchKey, 96.dp)
                Cell(nameOf(m.debitAccountKey), 130.dp)
                OptionalCell(AoiroChoboAccountRules.taxRateLabel(m.taxRate), 60.dp)
                OptionalCell(m.businessRatio?.toString(), 64.dp)
                Cell(nameOf(m.creditAccountKey), 130.dp)
                OptionalCell(AoiroChoboAccountRules.taxRateLabel(m.creditTaxRate), 60.dp)
                OptionalCell(m.creditBusinessRatio?.toString(), 64.dp)
            }
        } else {
            val columns = buildList {
                add("摘要名" to 170.dp); add("検索文字" to 96.dp); add("科目" to 130.dp)
                add("税率" to 60.dp); add("事業\n割合(%)" to 64.dp)
                sharedHeader?.let { add(it to 56.dp) }
            }
            PcTable(columns = columns, headerColor = MemoHeaderColor, rows = shown, scrollKey = selected, rowKey = { it.memoKey }) { m ->
                MemoNameCell(m, m.memoKey in ratioSensitive)
                Cell(m.searchKey, 96.dp)
                Cell(nameOf(m.counterAccountKey), 130.dp)
                OptionalCell(AoiroChoboAccountRules.taxRateLabel(m.taxRate), 60.dp)
                OptionalCell(m.businessRatio?.toString(), 64.dp)
                if (sharedHeader != null) {
                    val shared = if (selected == MemoTab.CASH_IN || selected == MemoTab.CASH_OUT) m.showInBank else m.showInCash
                    Cell(if (shared) "✓" else "", 56.dp, align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun MemoNameCell(memo: AoiroChoboMemoTemplate, isRatioSensitive: Boolean) {
    Row(
        modifier = Modifier
            .width(170.dp)
            .fillMaxHeight()
            .background(CellColor)
            .border(0.5.dp, GridColor)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = memo.name + (memo.bankSlotNo?.let { "（口座$it）" } ?: ""),
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = CellTextColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (isRatioSensitive) {
            Text(
                text = "要確定",
                modifier = Modifier
                    .background(Color(0xFFFFE0B2), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp),
                fontSize = 10.sp,
                color = Color(0xFF8A4B00)
            )
        }
    }
}

// ---- 表の部品 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PcTabBar(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                label = { Text(label, fontSize = 13.sp) }
            )
        }
    }
}

/**
 * 横にはみ出す表。列見出しは縦スクロールしても上に残す。
 * 画面幅に収まらないので、表全体を横スクロールさせる（列幅は固定）。
 *
 * [scrollKey] が変わったらスクロール位置を先頭に戻す。タブを切り替えても同じ位置の部品として
 * 再利用されるので、渡さないと前のタブのスクロール位置のまま途中から表示される。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> PcTable(
    columns: List<Pair<String, Dp>>,
    headerColor: Color,
    rows: List<T>,
    scrollKey: Any,
    rowKey: (T) -> String,
    rowContent: @Composable RowScope.(T) -> Unit
) = key(scrollKey) {
    val tableWidth = columns.fold(0.dp) { acc, (_, w) -> acc + w }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
    ) {
        LazyColumn(modifier = Modifier.width(tableWidth).fillMaxHeight()) {
            stickyHeader(key = "header") {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    columns.forEach { (title, width) ->
                        Text(
                            text = title,
                            modifier = Modifier
                                .width(width)
                                .fillMaxHeight()
                                .background(headerColor)
                                .border(0.5.dp, GridColor)
                                .padding(vertical = 6.dp),
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (headerColor == MemoHeaderColor) Color.White else CellTextColor,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            items(rows, key = rowKey) { row ->
                Row(modifier = Modifier.height(IntrinsicSize.Min).heightIn(min = 36.dp)) {
                    rowContent(row)
                }
            }
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    background: Color = CellColor,
    align: TextAlign = TextAlign.Start,
    drawGrid: Boolean = true
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(background)
            .then(if (drawGrid) Modifier.border(0.5.dp, GridColor) else Modifier)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 13.sp,
            color = CellTextColor,
            textAlign = align,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 値が無ければ PC と同じく灰色の空欄 */
@Composable
private fun OptionalCell(text: String?, width: Dp) {
    Cell(text.orEmpty(), width, if (text == null) GroupNoneColor else CellColor, TextAlign.Center)
}
