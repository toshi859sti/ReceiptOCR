package com.example.greenframeocr.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 区分Aの並び順
 */
private val CATEGORY_A_ORDER = listOf(
    "【資産】", "【負債】", "【資本】", "【経常損益】", "【引当金等】"
)

/**
 * 区分Bの並び順（区分Aごと）
 */
private val CATEGORY_B_ORDER = mapOf(
    "【資産】" to listOf("【流動資産】", "【固定資産】", "【繰延資産】", "【事業主貸】"),
    "【負債】" to listOf("【流動負債】", "【事業主借】"),
    "【資本】" to listOf("【資本】"),
    "【経常損益】" to listOf("【収入金額】", "【経費】"),
    "【引当金等】" to listOf("【繰戻額等】", "【繰入額等】")
)

/**
 * 区分Cの並び順（区分Bごと）
 */
private val CATEGORY_C_ORDER = mapOf(
    "【流動資産】" to listOf("【現金・預金】", "【売上債権】", "【有価証券】", "【棚卸資産】", "【他流動資産】"),
    "【固定資産】" to listOf("【有形固定資産】", "【無形固定資産】", "【投資等】"),
    "【流動負債】" to listOf("【仕入債務】", "【他流動負債】"),
    "【収入金額】" to listOf("【収入金額】", "【農産物棚卸高】"),
    "【経費】" to listOf("【経費】", "【農産外棚卸高】"),
    "【繰戻額等】" to listOf("【繰戻額等】"),
    "【繰入額等】" to listOf("【繰入額等】")
)

/**
 * 勘定科目の階層構造を表すデータクラス
 */
data class AccountHierarchy<T>(
    val categoryA: String,
    val categoryBGroups: List<CategoryBGroup<T>>
)

data class CategoryBGroup<T>(
    val categoryB: String,
    val categoryCGroups: List<CategoryCGroup<T>>
)

data class CategoryCGroup<T>(
    val categoryC: String,
    val accounts: List<T>
)

/**
 * 勘定科目設定画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit,
    onNavigateToYayoiEdit: (Long) -> Unit = {},
    initialTab: Int = 0
) {
    val scope = rememberCoroutineScope()

    // State
    var selectedTab by remember { mutableStateOf(initialTab) }
    var rakurakuAccounts by remember { mutableStateOf<List<RakurakuAccount>>(emptyList()) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedRakurakuAccount by remember { mutableStateOf<RakurakuAccount?>(null) }
    var selectedYayoiAccount by remember { mutableStateOf<YayoiAccount?>(null) }

    // 区分A選択状態
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }

    // 展開状態（区分B, C用）
    var expandedCategoryB by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedCategoryC by remember { mutableStateOf<Set<String>>(emptySet()) }

    val tabs = listOf("らくらく青色申告", "弥生会計")

    // 初期データ読み込み
    LaunchedEffect(Unit) {
        rakurakuAccounts = database.rakurakuAccountDao().getAll()
        yayoiAccounts = database.yayoiAccountDao().getAll()
    }

    fun loadAccounts() {
        scope.launch {
            rakurakuAccounts = database.rakurakuAccountDao().getAll()
            yayoiAccounts = database.yayoiAccountDao().getAll()
        }
    }

    // 階層構造に変換
    val rakurakuHierarchy = remember(rakurakuAccounts) {
        buildHierarchy(rakurakuAccounts) { Triple(it.categoryA, it.categoryB, it.categoryC) }
    }

    val yayoiHierarchy = remember(yayoiAccounts) {
        // categoryC は廃止のため categoryB を C にも使用（C ヘッダーは B と同名で非表示になる）
        buildHierarchy(yayoiAccounts) { Triple(it.categoryA, it.categoryB, it.categoryB) }
    }

    // 区分Aリスト（タブによって切り替え）
    val categoryAList = remember(rakurakuHierarchy, yayoiHierarchy, selectedTab) {
        if (selectedTab == 0) {
            rakurakuHierarchy.map { it.categoryA }
        } else {
            yayoiHierarchy.map { it.categoryA }
        }
    }

    // 選択中の区分Aがない場合、最初の区分Aを選択
    LaunchedEffect(categoryAList, selectedTab) {
        if (selectedCategoryA == null || selectedCategoryA !in categoryAList) {
            selectedCategoryA = categoryAList.firstOrNull()
        }
    }

    // 選択された区分Aでフィルタリング（タブ別）
    val filteredRakurakuHierarchy = remember(rakurakuHierarchy, selectedCategoryA) {
        rakurakuHierarchy.find { it.categoryA == selectedCategoryA }
    }
    val filteredYayoiHierarchy = remember(yayoiHierarchy, selectedCategoryA) {
        yayoiHierarchy.find { it.categoryA == selectedCategoryA }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("勘定科目設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                actions = {
                    // 全展開/折りたたみ（選択中の区分A内）
                    IconButton(onClick = {
                        if (expandedCategoryB.isNotEmpty()) {
                            expandedCategoryB = emptySet()
                            expandedCategoryC = emptySet()
                        } else {
                            val hierarchy = if (selectedTab == 0) filteredRakurakuHierarchy else filteredYayoiHierarchy
                            hierarchy?.let { h ->
                                expandedCategoryB = h.categoryBGroups.map { it.categoryB }.toSet()
                                expandedCategoryC = h.categoryBGroups.flatMap { b ->
                                    b.categoryCGroups.map { "${b.categoryB}/${it.categoryC}" }
                                }.toSet()
                            }
                        }
                    }) {
                        Icon(
                            if (expandedCategoryB.isNotEmpty()) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                            contentDescription = if (expandedCategoryB.isNotEmpty()) "すべて折りたたむ" else "すべて展開"
                        )
                    }
                    IconButton(onClick = {
                        if (selectedTab == 1) {
                            onNavigateToYayoiEdit(-1L)
                        } else {
                            showAddDialog = true
                        }
                    }) {
                        Icon(Icons.Default.Add, "追加")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // タブ
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            selectedCategoryA = null  // タブ切り替え時にリセット
                            expandedCategoryB = emptySet()
                            expandedCategoryC = emptySet()
                        },
                        text = { Text(title) }
                    )
                }
            }

            // 区分Aセレクター
            CategoryASelector(
                categories = categoryAList,
                selectedCategory = selectedCategoryA,
                onCategorySelected = { category ->
                    selectedCategoryA = category
                    expandedCategoryB = emptySet()
                    expandedCategoryC = emptySet()
                }
            )

            // 件数表示
            val filteredCount = when (selectedTab) {
                0 -> filteredRakurakuHierarchy?.categoryBGroups?.sumOf { b ->
                    b.categoryCGroups.sumOf { it.accounts.size }
                } ?: 0
                else -> filteredYayoiHierarchy?.categoryBGroups?.sumOf { b ->
                    b.categoryCGroups.sumOf { it.accounts.size }
                } ?: 0
            }
            Text(
                text = "${filteredCount}件",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()

            // 階層リスト（選択された区分A内のみ）
            when (selectedTab) {
                0 -> {
                    filteredRakurakuHierarchy?.let { hierarchy ->
                        FilteredAccountList(
                            categoryBGroups = hierarchy.categoryBGroups,
                            expandedCategoryB = expandedCategoryB,
                            expandedCategoryC = expandedCategoryC,
                            onToggleCategoryB = { key ->
                                expandedCategoryB = if (key in expandedCategoryB)
                                    expandedCategoryB - key else expandedCategoryB + key
                            },
                            onToggleCategoryC = { key ->
                                expandedCategoryC = if (key in expandedCategoryC)
                                    expandedCategoryC - key else expandedCategoryC + key
                            },
                            accountContent = { account ->
                                RakurakuAccountItem(
                                    account = account,
                                    childAccounts = rakurakuAccounts.filter { it.parentId == account.id },
                                    onClick = {
                                        selectedRakurakuAccount = account
                                        showEditDialog = true
                                    },
                                    onDelete = {
                                        selectedRakurakuAccount = account
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        )
                    }
                }
                1 -> {
                    filteredYayoiHierarchy?.let { hierarchy ->
                        FilteredAccountList(
                            categoryBGroups = hierarchy.categoryBGroups,
                            expandedCategoryB = expandedCategoryB,
                            expandedCategoryC = expandedCategoryC,
                            onToggleCategoryB = { key ->
                                expandedCategoryB = if (key in expandedCategoryB)
                                    expandedCategoryB - key else expandedCategoryB + key
                            },
                            onToggleCategoryC = { key ->
                                expandedCategoryC = if (key in expandedCategoryC)
                                    expandedCategoryC - key else expandedCategoryC + key
                            },
                            accountContent = { account ->
                                YayoiAccountItem(
                                    account = account,
                                    onClick = { onNavigateToYayoiEdit(account.id) },
                                    onDelete = {
                                        selectedYayoiAccount = account
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // 編集ダイアログ（らくらくのみ。弥生は専用画面へ遷移）
    if (showEditDialog) {
        selectedRakurakuAccount?.let { account ->
            RakurakuAccountEditDialog(
                title = "らくらく勘定科目編集",
                account = account,
                existingCategories = rakurakuAccounts.map { Triple(it.categoryA, it.categoryB, it.categoryC) }.distinct(),
                onDismiss = {
                    showEditDialog = false
                    selectedRakurakuAccount = null
                },
                onSave = { updatedAccount ->
                    scope.launch {
                        database.rakurakuAccountDao().update(updatedAccount)
                        loadAccounts()
                    }
                    showEditDialog = false
                    selectedRakurakuAccount = null
                }
            )
        }
    }

    // 追加ダイアログ（らくらくのみ。弥生は onNavigateToYayoiEdit(-1L) で遷移済み）
    if (showAddDialog) {
        RakurakuAccountEditDialog(
            title = "らくらく勘定科目追加",
            account = null,
            existingCategories = rakurakuAccounts.map { Triple(it.categoryA, it.categoryB, it.categoryC) }.distinct(),
            onDismiss = { showAddDialog = false },
            onSave = { newAccount ->
                scope.launch {
                    database.rakurakuAccountDao().insert(newAccount)
                    loadAccounts()
                }
                showAddDialog = false
            }
        )
    }

    // 削除確認ダイアログ
    if (showDeleteDialog) {
        val accountName = when (selectedTab) {
            0 -> selectedRakurakuAccount?.accountName ?: ""
            else -> selectedYayoiAccount?.accountName ?: ""
        }
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                selectedRakurakuAccount = null
                selectedYayoiAccount = null
            },
            title = { Text("削除確認") },
            text = { Text("「$accountName」を削除しますか？\n\nこの勘定科目を使用している購買品の設定も解除されます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            when (selectedTab) {
                                0 -> selectedRakurakuAccount?.let {
                                    database.rakurakuAccountDao().delete(it)
                                }
                                1 -> selectedYayoiAccount?.let {
                                    database.yayoiAccountDao().delete(it)
                                }
                            }
                            loadAccounts()
                        }
                        showDeleteDialog = false
                        selectedRakurakuAccount = null
                        selectedYayoiAccount = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    selectedRakurakuAccount = null
                    selectedYayoiAccount = null
                }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

/**
 * 階層構造を構築（並び順を適用）
 */
internal fun <T> buildHierarchy(
    accounts: List<T>,
    getCategoryKeys: (T) -> Triple<String, String, String>
): List<AccountHierarchy<T>> {
    val grouped = accounts.groupBy { getCategoryKeys(it).first }

    // 区分Aを指定順にソート
    return CATEGORY_A_ORDER
        .filter { it in grouped.keys }
        .map { categoryA ->
            val accountsA = grouped[categoryA] ?: emptyList()
            val bGrouped = accountsA.groupBy { getCategoryKeys(it).second }

            // 区分Bを指定順にソート
            val bOrder = CATEGORY_B_ORDER[categoryA] ?: emptyList()
            val sortedBGroups = bOrder
                .filter { it in bGrouped.keys }
                .map { categoryB ->
                    val accountsB = bGrouped[categoryB] ?: emptyList()
                    val cGrouped = accountsB.groupBy { getCategoryKeys(it).third }

                    // 区分Cを指定順にソート
                    val cOrder = CATEGORY_C_ORDER[categoryB] ?: emptyList()
                    val sortedCGroups = if (cOrder.isNotEmpty()) {
                        cOrder.filter { it in cGrouped.keys }.map { categoryC ->
                            CategoryCGroup(
                                categoryC = categoryC,
                                accounts = cGrouped[categoryC] ?: emptyList()
                            )
                        }
                    } else {
                        // 並び順が定義されていない場合はそのまま
                        cGrouped.map { (categoryC, accountsC) ->
                            CategoryCGroup(categoryC = categoryC, accounts = accountsC)
                        }
                    }

                    CategoryBGroup(
                        categoryB = categoryB,
                        categoryCGroups = sortedCGroups
                    )
                }

            AccountHierarchy(
                categoryA = categoryA,
                categoryBGroups = sortedBGroups
            )
        }
}

/**
 * 区分Aセレクター（横スクロール可能なチップ）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryASelector(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val isSelected = category == selectedCategory
            val displayName = category.removePrefix("【").removeSuffix("】")

            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                label = {
                    Text(
                        text = displayName,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

/**
 * フィルタリングされた勘定科目リスト（区分B以下を表示）
 */
@Composable
internal fun <T> FilteredAccountList(
    categoryBGroups: List<CategoryBGroup<T>>,
    expandedCategoryB: Set<String>,
    expandedCategoryC: Set<String>,
    onToggleCategoryB: (String) -> Unit,
    onToggleCategoryC: (String) -> Unit,
    accountContent: @Composable (T) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        categoryBGroups.forEach { categoryBGroup ->
            val keyB = categoryBGroup.categoryB

            // 区分B ヘッダー
            item(key = "B:$keyB") {
                CategoryHeader(
                    title = categoryBGroup.categoryB,
                    level = 0,  // 区分Aがセレクターになったので、区分Bがレベル0
                    isExpanded = keyB in expandedCategoryB,
                    itemCount = categoryBGroup.categoryCGroups.sumOf { it.accounts.size },
                    onClick = { onToggleCategoryB(keyB) }
                )
            }

            // 区分B の中身
            if (keyB in expandedCategoryB) {
                categoryBGroup.categoryCGroups.forEach { categoryCGroup ->
                    val keyC = "${keyB}/${categoryCGroup.categoryC}"

                    // 区分C ヘッダー（区分Bと異なる場合のみ表示）
                    if (categoryCGroup.categoryC != categoryBGroup.categoryB) {
                        item(key = "C:$keyC") {
                            CategoryHeader(
                                title = categoryCGroup.categoryC,
                                level = 1,  // 区分Cはレベル1
                                isExpanded = keyC in expandedCategoryC,
                                itemCount = categoryCGroup.accounts.size,
                                onClick = { onToggleCategoryC(keyC) }
                            )
                        }

                        // 区分C の中身
                        if (keyC in expandedCategoryC) {
                            items(
                                items = categoryCGroup.accounts,
                                key = { account ->
                                    when (account) {
                                        is RakurakuAccount -> "R:${account.id}"
                                        is YayoiAccount -> "Y:${account.id}"
                                        else -> account.hashCode()
                                    }
                                }
                            ) { account ->
                                accountContent(account)
                            }
                        }
                    } else {
                        // 区分Cが区分Bと同じ場合は直接アイテムを表示
                        items(
                            items = categoryCGroup.accounts,
                            key = { account ->
                                when (account) {
                                    is RakurakuAccount -> "R:${account.id}"
                                    is YayoiAccount -> "Y:${account.id}"
                                    else -> account.hashCode()
                                }
                            }
                        ) { account ->
                            accountContent(account)
                        }
                    }
                }
            }
        }
    }
}

/**
 * カテゴリヘッダー
 */
@Composable
internal fun CategoryHeader(
    title: String,
    level: Int,
    isExpanded: Boolean,
    itemCount: Int,
    onClick: () -> Unit
) {
    val backgroundColor = when (level) {
        0 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        1 -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    }
    val startPadding = (16 + level * 16).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(start = startPadding, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = if (isExpanded) "折りたたむ" else "展開",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.removePrefix("【").removeSuffix("】"),
            fontWeight = if (level == 0) FontWeight.Bold else FontWeight.Medium,
            fontSize = when (level) {
                0 -> 15.sp
                1 -> 14.sp
                else -> 13.sp
            },
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${itemCount}件",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * らくらく勘定科目アイテム
 */
@Composable
private fun RakurakuAccountItem(
    account: RakurakuAccount,
    childAccounts: List<RakurakuAccount>,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 48.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 勘定科目名
            Text(
                text = account.accountName,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            // サーチキー数字（コード）
            Text(
                text = account.accountCode,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // サーチキー英字
            if (account.searchKeyAlpha.isNotEmpty()) {
                Text(
                    text = account.searchKeyAlpha,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 貸借区分
            Text(
                text = account.debitCredit,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            // 購買バッジ
            if (account.usedForPurchase) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "購買",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            // 預金バッジ
            if (account.usedForDeposit) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "預金",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            // 削除ボタン
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 子科目を表示
        childAccounts.forEach { child ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(start = 64.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SubdirectoryArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = child.accountName,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Divider(modifier = Modifier.padding(start = 48.dp))
    }
}

/**
 * 弥生勘定科目アイテム
 */
@Composable
private fun YayoiAccountItem(
    account: YayoiAccount,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 48.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 勘定科目名
        Text(
            text = account.accountName,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        // サーチキー数字（コード）
        account.accountCode?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // サーチキー英字
        if (account.searchKeyAlpha.isNotEmpty()) {
            Text(
                text = account.searchKeyAlpha,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 貸借区分
        Text(
            text = account.debitCredit,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        // 購買バッジ
        if (account.usedForPurchase) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "購買",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        // 預金バッジ
        if (account.usedForDeposit) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "預金",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        // 削除ボタン
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    Divider(modifier = Modifier.padding(start = 48.dp))
}

/**
 * らくらく勘定科目編集ダイアログ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RakurakuAccountEditDialog(
    title: String,
    account: RakurakuAccount?,
    existingCategories: List<Triple<String, String, String>>,
    onDismiss: () -> Unit,
    onSave: (RakurakuAccount) -> Unit
) {
    var editName by remember { mutableStateOf(account?.accountName ?: "") }
    var editCode by remember { mutableStateOf(account?.accountCode ?: "") }
    var editSearchKey by remember { mutableStateOf(account?.searchKeyAlpha ?: "") }
    var editDebitCredit by remember { mutableStateOf(account?.debitCredit ?: "借") }
    var editCategoryA by remember { mutableStateOf(account?.categoryA ?: "") }
    var editCategoryB by remember { mutableStateOf(account?.categoryB ?: "") }
    var editCategoryC by remember { mutableStateOf(account?.categoryC ?: "") }
    var editUsedForPurchase by remember { mutableStateOf(account?.usedForPurchase ?: false) }

    var expandedCategoryA by remember { mutableStateOf(false) }
    var expandedCategoryB by remember { mutableStateOf(false) }
    var expandedCategoryC by remember { mutableStateOf(false) }

    val distinctCategoryA = existingCategories.map { it.first }.distinct().sorted()
    val distinctCategoryB = existingCategories.map { it.second }.distinct().sorted()
    val distinctCategoryC = existingCategories.map { it.third }.distinct().sorted()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("勘定科目名 *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editCode,
                        onValueChange = { editCode = it },
                        label = { Text("コード") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editSearchKey,
                        onValueChange = { editSearchKey = it },
                        label = { Text("英字キー") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // 借貸選択
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("借貸:", fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = editDebitCredit == "借",
                            onClick = { editDebitCredit = "借" }
                        )
                        Text("借")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = editDebitCredit == "貸",
                            onClick = { editDebitCredit = "貸" }
                        )
                        Text("貸")
                    }
                }

                // 区分A
                ExposedDropdownMenuBox(
                    expanded = expandedCategoryA,
                    onExpandedChange = { expandedCategoryA = it }
                ) {
                    OutlinedTextField(
                        value = editCategoryA,
                        onValueChange = { editCategoryA = it },
                        label = { Text("区分A (大分類) *") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategoryA) },
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCategoryA,
                        onDismissRequest = { expandedCategoryA = false }
                    ) {
                        distinctCategoryA.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    editCategoryA = category
                                    expandedCategoryA = false
                                }
                            )
                        }
                    }
                }

                // 区分B
                ExposedDropdownMenuBox(
                    expanded = expandedCategoryB,
                    onExpandedChange = { expandedCategoryB = it }
                ) {
                    OutlinedTextField(
                        value = editCategoryB,
                        onValueChange = { editCategoryB = it },
                        label = { Text("区分B (中分類) *") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategoryB) },
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCategoryB,
                        onDismissRequest = { expandedCategoryB = false }
                    ) {
                        distinctCategoryB.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    editCategoryB = category
                                    expandedCategoryB = false
                                }
                            )
                        }
                    }
                }

                // 区分C
                ExposedDropdownMenuBox(
                    expanded = expandedCategoryC,
                    onExpandedChange = { expandedCategoryC = it }
                ) {
                    OutlinedTextField(
                        value = editCategoryC,
                        onValueChange = { editCategoryC = it },
                        label = { Text("区分C (小分類) *") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategoryC) },
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCategoryC,
                        onDismissRequest = { expandedCategoryC = false }
                    ) {
                        distinctCategoryC.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    editCategoryC = category
                                    expandedCategoryC = false
                                }
                            )
                        }
                    }
                }

                // 購買取引使用
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = editUsedForPurchase,
                        onCheckedChange = { editUsedForPurchase = it }
                    )
                    Text("購買取引で使用")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (editName.isNotBlank() && editCategoryA.isNotBlank() &&
                        editCategoryB.isNotBlank() && editCategoryC.isNotBlank()) {
                        val newAccount = RakurakuAccount(
                            id = account?.id ?: 0,
                            accountName = editName.trim(),
                            accountCode = editCode.trim(),
                            searchKeyAlpha = editSearchKey.trim(),
                            debitCredit = editDebitCredit,
                            categoryA = editCategoryA.trim(),
                            categoryB = editCategoryB.trim(),
                            categoryC = editCategoryC.trim(),
                            usedForPurchase = editUsedForPurchase,
                            usedForDeposit = account?.usedForDeposit ?: true,
                            parentId = account?.parentId
                        )
                        onSave(newAccount)
                    }
                },
                enabled = editName.isNotBlank() && editCategoryA.isNotBlank() &&
                        editCategoryB.isNotBlank() && editCategoryC.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

/**
 * 弥生勘定科目編集ダイアログ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YayoiAccountEditDialog(
    title: String,
    account: YayoiAccount?,
    existingCategories: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (YayoiAccount) -> Unit
) {
    var editName by remember { mutableStateOf(account?.accountName ?: "") }
    var editCode by remember { mutableStateOf(account?.accountCode ?: "") }
    var editSearchKey by remember { mutableStateOf(account?.searchKeyAlpha ?: "") }
    var editDebitCredit by remember { mutableStateOf(account?.debitCredit ?: "借") }
    var editCategoryA by remember { mutableStateOf(account?.categoryA ?: "") }
    var editCategoryB by remember { mutableStateOf(account?.categoryB ?: "") }
    var editUsedForPurchase by remember { mutableStateOf(account?.usedForPurchase ?: false) }

    var expandedCategoryA by remember { mutableStateOf(false) }
    var expandedCategoryB by remember { mutableStateOf(false) }

    val distinctCategoryA = existingCategories.map { it.first }.distinct().sorted()
    val distinctCategoryB = existingCategories.map { it.second }.distinct().sorted()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("勘定科目名 *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editCode,
                        onValueChange = { editCode = it },
                        label = { Text("コード") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editSearchKey,
                        onValueChange = { editSearchKey = it },
                        label = { Text("英字キー") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // 借貸選択
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("借貸:", fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = editDebitCredit == "借",
                            onClick = { editDebitCredit = "借" }
                        )
                        Text("借")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = editDebitCredit == "貸",
                            onClick = { editDebitCredit = "貸" }
                        )
                        Text("貸")
                    }
                }

                // 区分A
                ExposedDropdownMenuBox(
                    expanded = expandedCategoryA,
                    onExpandedChange = { expandedCategoryA = it }
                ) {
                    OutlinedTextField(
                        value = editCategoryA,
                        onValueChange = { editCategoryA = it },
                        label = { Text("区分A (大分類) *") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategoryA) },
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCategoryA,
                        onDismissRequest = { expandedCategoryA = false }
                    ) {
                        distinctCategoryA.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    editCategoryA = category
                                    expandedCategoryA = false
                                }
                            )
                        }
                    }
                }

                // 区分B
                ExposedDropdownMenuBox(
                    expanded = expandedCategoryB,
                    onExpandedChange = { expandedCategoryB = it }
                ) {
                    OutlinedTextField(
                        value = editCategoryB,
                        onValueChange = { editCategoryB = it },
                        label = { Text("区分B (中分類) *") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategoryB) },
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCategoryB,
                        onDismissRequest = { expandedCategoryB = false }
                    ) {
                        distinctCategoryB.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    editCategoryB = category
                                    expandedCategoryB = false
                                }
                            )
                        }
                    }
                }

                // 購買取引使用
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = editUsedForPurchase,
                        onCheckedChange = { editUsedForPurchase = it }
                    )
                    Text("購買取引で使用")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (editName.isNotBlank() && editCategoryA.isNotBlank() && editCategoryB.isNotBlank()) {
                        val newAccount = YayoiAccount(
                            id = account?.id ?: 0,
                            accountName = editName.trim(),
                            accountCode = editCode.trim().ifEmpty { null },
                            searchKeyAlpha = editSearchKey.trim(),
                            debitCredit = editDebitCredit,
                            categoryA = editCategoryA.trim(),
                            categoryB = editCategoryB.trim(),
                            defaultTaxCategory = account?.defaultTaxCategory ?: "対象外",
                            usedForPurchase = editUsedForPurchase,
                            usedForDeposit = account?.usedForDeposit ?: false,
                            isEnabled = account?.isEnabled ?: true,
                            parentId = account?.parentId
                        )
                        onSave(newAccount)
                    }
                },
                enabled = editName.isNotBlank() && editCategoryA.isNotBlank() && editCategoryB.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

