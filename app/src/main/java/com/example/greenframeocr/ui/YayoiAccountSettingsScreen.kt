package com.example.greenframeocr.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.data.YayoiAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

// yayoi_accounts.categoryA は「【流動資産】」のように【】付きで格納されているため、
// 実データの値そのものを列挙する（括弧なし文字列と比較していたため常に0件になっていたバグの修正）
private val TAISYAKU_CATS = setOf(
    "【流動資産】", "【固定資産】", "【繰延資産】",
    "【流動負債】", "【固定負債】",
    "【資本】", "【事業主貸】", "【事業主借】"
)
private val SONEKI_CATS   = setOf("【収入金額】", "【経費】", "【繰入額等】", "【繰戻額等】")

private val COL_DEBIT   = 36.dp
private val COL_TAX     = 56.dp
private val COL_USE     = 32.dp
private val COL_ENABLED = 36.dp
private val COL_USE_TOTAL = COL_USE * 3

private fun taxShort(tax: String) = when (tax) {
    "対象外"     -> "対象外"
    "課対仕入10" -> "仕10%"
    "課対仕入8"  -> "仕8%"
    "課税売上"   -> "売上"
    "非課税"     -> "非課"
    else         -> tax.take(4)
}

private sealed class TreeNode {
    data class CatA(val name: String, val isExpanded: Boolean) : TreeNode()
    data class CatB(val catA: String, val name: String, val isExpanded: Boolean) : TreeNode()
    data class Account(val account: YayoiAccount, val hasSubs: Boolean, val isExpanded: Boolean) : TreeNode()
    data class SubAccount(val account: YayoiAccount) : TreeNode()
}

private fun nodeKey(node: TreeNode): String = when (node) {
    is TreeNode.CatA       -> "A:${node.name}"
    is TreeNode.CatB       -> "B:${node.catA}/${node.name}"
    is TreeNode.Account    -> "acct:${node.account.id}"
    is TreeNode.SubAccount -> "sub:${node.account.id}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YayoiAccountSettingsScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit,
    onNavigateToEdit: (accountId: Long, parentId: Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var accounts        by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var selectedTab     by remember { mutableIntStateOf(0) }
    var expandedA       by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedB       by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedAccts   by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedId      by remember { mutableStateOf<Long?>(null) }
    var accountToDelete by remember { mutableStateOf<YayoiAccount?>(null) }
    var showEnabledOnly by remember { mutableStateOf(false) }
    var expansionLevel  by remember { mutableIntStateOf(0) }
    var importResultMessage by remember { mutableStateOf<String?>(null) }
    var showBulkFlagsDialog by remember { mutableStateOf(false) }

    fun reload() { scope.launch { accounts = database.yayoiAccountDao().getAll() } }
    LaunchedEffect(Unit) { reload() }

    val allParents = remember(accounts) { accounts.filter { it.parentId == null } }
    val subMap     = remember(accounts, showEnabledOnly) {
        accounts.filter { it.parentId != null && (!showEnabledOnly || it.isEnabled) }
                .groupBy { it.parentId }
    }

    val tabCats        = if (selectedTab == 0) TAISYAKU_CATS else SONEKI_CATS
    val parentAccounts = remember(allParents, selectedTab, showEnabledOnly) {
        allParents.filter { it.categoryA in tabCats && (!showEnabledOnly || it.isEnabled) }
    }

    // 展開ボタン用：現在タブの全キー
    val allCatANamesInTab = remember(parentAccounts) {
        parentAccounts.map { it.categoryA }.filter { it.isNotBlank() }.toSet()
    }
    val allCatBKeysInTab = remember(parentAccounts) {
        parentAccounts.map { "${it.categoryA}||${it.categoryB}" }.toSet()
    }
    val accountsWithSubsInTab = remember(parentAccounts, subMap) {
        parentAccounts.filter { (subMap[it.id]?.isNotEmpty()) == true }.map { it.id }.toSet()
    }

    fun setLevel(lvl: Int) {
        if (expansionLevel == lvl) {
            expansionLevel = 0
            expandedA = emptySet(); expandedB = emptySet(); expandedAccts = emptySet()
        } else {
            expansionLevel = lvl
            when (lvl) {
                1 -> { expandedA = allCatANamesInTab; expandedB = emptySet(); expandedAccts = emptySet() }
                2 -> { expandedA = allCatANamesInTab; expandedB = allCatBKeysInTab; expandedAccts = emptySet() }
                3 -> { expandedA = allCatANamesInTab; expandedB = allCatBKeysInTab; expandedAccts = accountsWithSubsInTab }
            }
        }
    }

    val treeNodes = remember(parentAccounts, subMap, expandedA, expandedB, expandedAccts) {
        buildList {
            val byA = parentAccounts.groupBy { it.categoryA }
            val sortedCatA = byA.keys.filter { it.isNotBlank() }.sortedBy { key ->
                (byA[key] ?: emptyList()).mapNotNull { it.accountCode?.toIntOrNull() }.minOrNull() ?: Int.MAX_VALUE
            }
            for (catA in sortedCatA) {
                val aExp = catA in expandedA
                add(TreeNode.CatA(catA, aExp))
                if (!aExp) continue

                val byB = (byA[catA] ?: emptyList()).groupBy { it.categoryB }
                val sortedCatB = byB.keys.filter { it.isNotBlank() }.sortedBy { key ->
                    (byB[key] ?: emptyList()).mapNotNull { it.accountCode?.toIntOrNull() }.minOrNull() ?: Int.MAX_VALUE
                }
                for (catB in sortedCatB) {
                    val bKey = "$catA||$catB"
                    val bExp = bKey in expandedB
                    add(TreeNode.CatB(catA, catB, bExp))
                    if (!bExp) continue

                    val sorted = (byB[catB] ?: emptyList()).sortedWith(
                        compareBy { it.accountCode?.toIntOrNull() ?: Int.MAX_VALUE }
                    )
                    for (acct in sorted) {
                        val subs    = subMap[acct.id] ?: emptyList()
                        val acctExp = acct.id in expandedAccts
                        add(TreeNode.Account(acct, subs.isNotEmpty(), acctExp))
                        if (acctExp) subs.sortedWith(compareBy { it.accountCode?.toIntOrNull() ?: Int.MAX_VALUE })
                                        .forEach { add(TreeNode.SubAccount(it)) }
                    }
                }
            }
        }
    }

    // 選択中アカウント
    val selectedAccount   = remember(selectedId, accounts) { selectedId?.let { id -> accounts.find { it.id == id } } }
    val selectedIsParent  = selectedId != null && selectedAccount?.parentId == null

    // 移動用：同一グループの兄弟リスト
    val siblings = remember(selectedAccount, accounts) {
        selectedAccount?.let { acct ->
            if (acct.parentId == null) {
                accounts.filter {
                    it.parentId == null &&
                    it.categoryA == acct.categoryA &&
                    it.categoryB == acct.categoryB
                }.sortedBy { it.accountCode?.toIntOrNull() ?: Int.MAX_VALUE }
            } else {
                accounts.filter { it.parentId == acct.parentId }
                        .sortedBy { it.accountCode?.toIntOrNull() ?: Int.MAX_VALUE }
            }
        } ?: emptyList()
    }
    val siblingIdx  = remember(siblings, selectedAccount) {
        selectedAccount?.let { acct -> siblings.indexOfFirst { it.id == acct.id } } ?: -1
    }
    val prevSibling = if (siblingIdx > 0) siblings[siblingIdx - 1] else null
    val nextSibling = if (siblingIdx in 0 until siblings.size - 1) siblings[siblingIdx + 1] else null
    val canMoveUp   = prevSibling != null && selectedAccount?.accountCode != null && prevSibling.accountCode != null
    val canMoveDown = nextSibling != null && selectedAccount?.accountCode != null && nextSibling.accountCode != null

    fun swapCodes(a: YayoiAccount, b: YayoiAccount) {
        scope.launch {
            database.yayoiAccountDao().update(a.copy(accountCode = b.accountCode))
            database.yayoiAccountDao().update(b.copy(accountCode = a.accountCode))
            reload()
        }
    }

    fun resetExpand() {
        selectedId = null; expansionLevel = 0
        expandedA = emptySet(); expandedB = emptySet(); expandedAccts = emptySet()
    }

    // CSVインポート（勘定科目,サーチキー英字,サーチキー数字,借貸,区分B,区分A,税区分,購買取引使用,預金取引使用,レシート取引使用）。
    // accountCodeが既存科目と一致すればその科目を更新、なければ新規追加するマージ方式
    // （初回インポート用のDatabaseInitializer.importYayoiAccounts()とはCSV書式を揃えている）
    fun importCsv(uri: Uri) {
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                            reader.readLine() // ヘッダー行をスキップ
                            reader.readLines().mapNotNull { line ->
                                val parts = line.split(",")
                                if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
                                YayoiAccount(
                                    accountName = parts[0].trim(),
                                    searchKeyAlpha = parts.getOrNull(1)?.trim() ?: "",
                                    accountCode = parts.getOrNull(2)?.trim()?.ifEmpty { null },
                                    debitCredit = parts.getOrNull(3)?.trim() ?: "",
                                    categoryB = parts.getOrNull(4)?.trim() ?: "",
                                    categoryA = parts.getOrNull(5)?.trim() ?: "",
                                    defaultTaxCategory = parts.getOrNull(6)?.trim() ?: "対象外",
                                    usedForPurchase = parts.getOrNull(7)?.trim()?.uppercase() == "TRUE",
                                    usedForDeposit = parts.getOrNull(8)?.trim()?.uppercase() == "TRUE",
                                    usedForReceipt = parts.getOrNull(9)?.trim()?.uppercase() == "TRUE"
                                )
                            }
                        }
                    } ?: emptyList()
                }

                if (parsed.isEmpty()) {
                    importResultMessage = "取り込み可能なデータがありませんでした"
                    return@launch
                }

                var updated = 0
                var inserted = 0
                withContext(Dispatchers.IO) {
                    val dao = database.yayoiAccountDao()
                    parsed.forEach { row ->
                        val existing = row.accountCode?.let { dao.getByCode(it) }
                        if (existing != null) {
                            dao.update(row.copy(id = existing.id, parentId = existing.parentId, isEnabled = existing.isEnabled))
                            updated++
                        } else {
                            dao.insert(row)
                            inserted++
                        }
                    }
                }
                importResultMessage = "新規${inserted}件・更新${updated}件を取り込みました"
                reload()
            } catch (e: Exception) {
                importResultMessage = "取込エラー: ${e.message}"
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { importCsv(it) } }

    importResultMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3000)
            importResultMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("弥生の青色申告 — 勘定科目") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "戻る") }
                },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch("text/*") }) {
                        Icon(Icons.Default.Add, contentDescription = "CSV取込")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            importResultMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center
                )
            }

            // ── アクションバー（横一列・スクロール可） ────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ActionBtn("勘定作成", enabled = true) { onNavigateToEdit(-1L, -1L) }
                ActionBtn("補助作成", enabled = selectedIsParent) {
                    selectedAccount?.let { onNavigateToEdit(-1L, it.id) }
                }
                ActionBtn("編集", enabled = selectedId != null) {
                    selectedId?.let { onNavigateToEdit(it, -1L) }
                }
                ActionBtn("削除", enabled = selectedId != null, isError = true) {
                    accountToDelete = selectedAccount
                }
                ActionBtn("上に移動", enabled = canMoveUp) {
                    selectedAccount?.let { a -> prevSibling?.let { b -> swapCodes(a, b) } }
                }
                ActionBtn("下に移動", enabled = canMoveDown) {
                    selectedAccount?.let { a -> nextSibling?.let { b -> swapCodes(a, b) } }
                }
            }
            Divider(thickness = 0.5.dp)

            // ── ツールバー：展開レベル + 有効のみ ────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("展開:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(1, 2, 3).forEach { lvl ->
                    FilterChip(
                        selected = expansionLevel == lvl,
                        onClick  = { setLevel(lvl) },
                        label    = { Text("$lvl", fontSize = 12.sp) }
                    )
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = showEnabledOnly,
                    onClick  = { showEnabledOnly = !showEnabledOnly },
                    label    = { Text("有効のみ", fontSize = 12.sp) }
                )
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(
                    onClick = { showBulkFlagsDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("フラグ一括設定", fontSize = 12.sp) }
            }
            Divider(thickness = 0.5.dp)

            // ── タブ ─────────────────────────────────────────────
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = {
                    selectedTab = 0; resetExpand()
                }, text = { Text("貸借科目") })
                Tab(selected = selectedTab == 1, onClick = {
                    selectedTab = 1; resetExpand()
                }, text = { Text("損益科目") })
            }
            TreeHeader()
            Divider()

            LazyColumn(Modifier.fillMaxSize()) {
                items(treeNodes, key = ::nodeKey) { node ->
                    when (node) {
                        is TreeNode.CatA -> CatARow(node) {
                            expansionLevel = 0
                            expandedA = if (node.name in expandedA)
                                expandedA - node.name else expandedA + node.name
                        }
                        is TreeNode.CatB -> CatBRow(node) {
                            expansionLevel = 0
                            val k = "${node.catA}||${node.name}"
                            expandedB = if (k in expandedB) expandedB - k else expandedB + k
                        }
                        is TreeNode.Account -> AccountRow(
                            node       = node,
                            isSelected = node.account.id == selectedId,
                            onToggle   = {
                                expansionLevel = 0
                                expandedAccts = if (node.account.id in expandedAccts)
                                    expandedAccts - node.account.id else expandedAccts + node.account.id
                            },
                            onSelect   = { selectedId = node.account.id },
                            onEdit     = { onNavigateToEdit(node.account.id, -1L) }
                        )
                        is TreeNode.SubAccount -> SubAccountRow(
                            node       = node,
                            isSelected = node.account.id == selectedId,
                            onSelect   = { selectedId = node.account.id },
                            onEdit     = { onNavigateToEdit(node.account.id, -1L) }
                        )
                    }
                }
            }
        }
    }

    // 削除確認ダイアログ
    accountToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = { Text("削除確認") },
            text  = { Text("「${target.accountName}」を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            database.yayoiAccountDao().delete(target)
                            selectedId = null
                            reload()
                        }
                        accountToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) { Text("キャンセル") }
            }
        )
    }

    // フラグ一括設定ダイアログ（購買・預金・領収・有効をタップで一気に切り替え）
    if (showBulkFlagsDialog) {
        BulkFlagsDialog(
            accounts = accounts,
            onToggle = { account, field ->
                scope.launch {
                    val updated = when (field) {
                        BulkFlagField.PURCHASE -> account.copy(usedForPurchase = !account.usedForPurchase)
                        BulkFlagField.DEPOSIT  -> account.copy(usedForDeposit = !account.usedForDeposit)
                        BulkFlagField.RECEIPT  -> account.copy(usedForReceipt = !account.usedForReceipt)
                        BulkFlagField.ENABLED  -> account.copy(isEnabled = !account.isEnabled)
                    }
                    database.yayoiAccountDao().update(updated)
                    accounts = database.yayoiAccountDao().getAll()
                }
            },
            onDismiss = { showBulkFlagsDialog = false }
        )
    }
}

// ── ヘッダー行 ──────────────────────────────────────────
@Composable
private fun TreeHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("勘定科目", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        ColHead("借貸", COL_DEBIT)
        ColHead("税区分", COL_TAX)
        ColHead("購買", COL_USE)
        ColHead("預金", COL_USE)
        ColHead("領収", COL_USE)
        ColHead("有効", COL_ENABLED)
    }
}

@Composable
private fun ColHead(label: String, width: androidx.compose.ui.unit.Dp) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.width(width), textAlign = TextAlign.Center)
}

// ── 大分類行 ───────────────────────────────────────────
@Composable
private fun CatARow(node: TreeNode.CatA, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (node.isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(node.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(COL_DEBIT + COL_TAX + COL_USE_TOTAL + COL_ENABLED))
    }
}

// ── 中分類行 ───────────────────────────────────────────
@Composable
private fun CatBRow(node: TreeNode.CatB, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(20.dp))
        Icon(
            if (node.isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(node.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(COL_DEBIT + COL_TAX + COL_USE_TOTAL + COL_ENABLED))
    }
}

// ── 勘定科目行 ─────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(
    node: TreeNode.Account,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    val acct = node.account
    val alpha = if (acct.isEnabled) 1f else 0.35f
    val bg    = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    else Color.Transparent

    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onSelect, onDoubleClick = onEdit)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(40.dp))
        if (node.hasSubs) {
            IconButton(onClick = onToggle, modifier = Modifier.size(20.dp)) {
                Icon(
                    if (node.isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            acct.accountName,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
        AccountColumns(
            acct.debitCredit, acct.defaultTaxCategory,
            acct.usedForPurchase, acct.usedForDeposit, acct.usedForReceipt,
            acct.isEnabled
        )
    }
    Divider(Modifier.padding(start = 64.dp), thickness = 0.5.dp)
}

// ── 補助科目行 ─────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubAccountRow(
    node: TreeNode.SubAccount,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    val acct  = node.account
    val alpha = if (acct.isEnabled) 0.75f else 0.3f
    val bg    = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    else Color.Transparent

    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onSelect, onDoubleClick = onEdit)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(64.dp))
        Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = null,
            modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            acct.accountName,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
        )
        AccountColumns(
            acct.debitCredit, acct.defaultTaxCategory,
            acct.usedForPurchase, acct.usedForDeposit, acct.usedForReceipt,
            acct.isEnabled, small = true
        )
    }
    Divider(Modifier.padding(start = 80.dp), thickness = 0.5.dp)
}

// ── 属性列（共通） ──────────────────────────────────────
@Composable
private fun AccountColumns(
    debitCredit: String,
    taxCategory: String,
    usedForPurchase: Boolean,
    usedForDeposit: Boolean,
    usedForReceipt: Boolean,
    isEnabled: Boolean,
    small: Boolean = false
) {
    val fs = if (small) 11.sp else 12.sp
    Text(
        debitCredit,
        fontSize = fs,
        modifier = Modifier.width(COL_DEBIT),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        taxShort(taxCategory),
        fontSize = if (small) 10.sp else 11.sp,
        modifier = Modifier.width(COL_TAX),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    UseFlagDot(usedForPurchase, fs, MaterialTheme.colorScheme.primary)
    UseFlagDot(usedForDeposit, fs, MaterialTheme.colorScheme.tertiary)
    UseFlagDot(usedForReceipt, fs, MaterialTheme.colorScheme.secondary)
    Text(
        if (isEnabled) "●" else "○",
        fontSize = fs,
        modifier = Modifier.width(COL_ENABLED),
        textAlign = TextAlign.Center,
        color = if (isEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    )
}

// 購買/預金/領収の使用フラグを○●で表示（有効列と同じ見た目に揃える）
@Composable
private fun UseFlagDot(used: Boolean, fontSize: androidx.compose.ui.unit.TextUnit, onColor: Color) {
    Text(
        if (used) "●" else "○",
        fontSize = fontSize,
        modifier = Modifier.width(COL_USE),
        textAlign = TextAlign.Center,
        color = if (used) onColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    )
}

// ── アクションボタン ────────────────────────────────────
@Composable
private fun ActionBtn(
    label: String,
    enabled: Boolean,
    isError: Boolean = false,
    onClick: () -> Unit
) {
    if (isError) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor   = MaterialTheme.colorScheme.onErrorContainer
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)
        ) { Text(label, fontSize = 12.sp) }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)
        ) { Text(label, fontSize = 12.sp) }
    }
}

// ── フラグ一括設定ダイアログ ──────────────────────────────
private enum class BulkFlagField { PURCHASE, DEPOSIT, RECEIPT, ENABLED }

private val COL_BULK_FLAG = 44.dp

/**
 * 科目ごとに開いて設定していた購買・預金・領収・有効フラグを、
 * 一覧表（エクセル風）でタップ一発に切り替えられるようにしたダイアログ。
 * タップごとに即DB反映（onToggleが呼び出し元でupdate）し、呼び出し元のaccountsをその都度更新する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkFlagsDialog(
    accounts: List<YayoiAccount>,
    onToggle: (account: YayoiAccount, field: BulkFlagField) -> Unit,
    onDismiss: () -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }

    val categoryAList = remember(accounts) {
        sortYayoiCategoryA(accounts.map { it.categoryA }.filter { it.isNotBlank() })
    }

    val filtered = remember(accounts, searchText, selectedCategoryA) {
        accounts.filter { acc ->
            (selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText, ignoreCase = true) == true))
        }.sortedWith(compareBy(
            { val idx = YAYOI_CATEGORY_A_ORDER.indexOf(it.categoryA); if (idx < 0) Int.MAX_VALUE else idx },
            { it.categoryB },
            { it.accountCode?.toIntOrNull() ?: Int.MAX_VALUE }
        ))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("フラグ一括設定", fontSize = 18.sp) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "閉じる") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                Text(
                    text = "セルをタップすると即座に切り替わります",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "検索") }
                )

                Spacer(Modifier.height(4.dp))

                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedCategoryA == null,
                            onClick = { selectedCategoryA = null },
                            label = { Text("全て", fontSize = 12.sp) }
                        )
                    }
                    items(categoryAList.size) { idx ->
                        val cat = categoryAList[idx]
                        FilterChip(
                            selected = selectedCategoryA == cat,
                            onClick = { selectedCategoryA = if (selectedCategoryA == cat) null else cat },
                            label = { Text(cat, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 表ヘッダー
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("勘定科目", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    ColHead("購買", COL_BULK_FLAG)
                    ColHead("預金", COL_BULK_FLAG)
                    ColHead("領収", COL_BULK_FLAG)
                    ColHead("有効", COL_BULK_FLAG)
                }
                Divider()

                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { acct ->
                        BulkFlagRow(account = acct, onToggle = { field -> onToggle(acct, field) })
                        Divider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkFlagRow(
    account: YayoiAccount,
    onToggle: (BulkFlagField) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                account.accountName,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (account.isEnabled) 1f else 0.4f)
            )
            Text(
                "${account.categoryA} / ${account.categoryB}",
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BulkFlagCell(account.usedForPurchase, MaterialTheme.colorScheme.primary) { onToggle(BulkFlagField.PURCHASE) }
        BulkFlagCell(account.usedForDeposit, MaterialTheme.colorScheme.tertiary) { onToggle(BulkFlagField.DEPOSIT) }
        BulkFlagCell(account.usedForReceipt, MaterialTheme.colorScheme.secondary) { onToggle(BulkFlagField.RECEIPT) }
        BulkFlagCell(account.isEnabled, Color(0xFF2E7D32)) { onToggle(BulkFlagField.ENABLED) }
    }
}

@Composable
private fun BulkFlagCell(checked: Boolean, onColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(COL_BULK_FLAG)
            .height(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (checked) "●" else "○",
            fontSize = 18.sp,
            color = if (checked) onColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        )
    }
}
