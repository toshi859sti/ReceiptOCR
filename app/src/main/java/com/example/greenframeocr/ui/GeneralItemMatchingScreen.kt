package com.example.greenframeocr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.GeneralItemGroup
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.GeminiReceiptClient
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel

// 科目名の表示色。グループのデフォルトでマッチした分は緑系、個別に上書きした分は赤系で固定
// （テーマプリセットによってprimary/tertiaryの色味が変わるため、区別のため固定色にしている）
private val MatchedAccountColor = Color(0xFF2E7D32)
private val OverriddenAccountColor = Color(0xFFC62828)

/**
 * 品目別マッチング画面。
 * 通帳摘要集約リスト（TekiyouMatchingScreen）と同じ「グループのデフォルト＋個別上書き」
 * 操作方式に合わせている：グループ行タップで展開、✏でグループのデフォルト変更（個別上書きは
 * 全解除）、展開後の個別明細タップで1件だけ上書き（「グループのデフォルトに戻す」あり）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralItemMatchingScreen(
    viewModel: GeneralReceiptViewModel,
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val itemGroups by viewModel.itemGroups.collectAsState()
    val aiError by viewModel.aiError.collectAsState()
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val isAiMatching by viewModel.isAiMatching.collectAsState()
    val aiUsageStats by viewModel.aiUsageStats.collectAsState()

    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }
    var expandedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupEditTarget by remember { mutableStateOf<GeneralItemGroup?>(null) }
    var itemEditTarget by remember { mutableStateOf<GeneralReceiptItem?>(null) }
    var itemEditGroupDefaultName by remember { mutableStateOf<String?>(null) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }

    LaunchedEffect(Unit) {
        yayoiAccounts = viewModel.loadYayoiAccounts()
    }

    val matchedCount = itemGroups.count { it.yayoiAccountId != null }
    val totalCount = itemGroups.size
    val unmatchedCount = totalCount - matchedCount

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("品目但し書き別マッチング") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                FontSizeControl(
                    fontSize = listFontSize,
                    onDecrease = {
                        listFontSize = (listFontSize - 1f).coerceAtLeast(10f)
                        appPreferences.listFontSize = listFontSize
                    },
                    onIncrease = {
                        listFontSize = (listFontSize + 1f).coerceAtMost(20f)
                        appPreferences.listFontSize = listFontSize
                    }
                )
            }

            // 統計カード
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatColumn("品目数", "$totalCount")
                    StatColumn("マッチ済", "$matchedCount", MaterialTheme.colorScheme.primary)
                    StatColumn(
                        "未マッチ", "$unmatchedCount",
                        if (unmatchedCount > 0) MaterialTheme.colorScheme.error else Color.Gray
                    )
                }
            }

            // AI一括割り当てボタン（常に表示。既マッチ済みグループも対象に含めて再提案できる）
            OutlinedButton(
                onClick = { viewModel.suggestAccountsForItems(itemGroups, yayoiAccounts) },
                enabled = !isAiMatching && yayoiAccounts.isNotEmpty() && itemGroups.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (isAiMatching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("AI提案中...")
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (unmatchedCount > 0) "未マッチ ${unmatchedCount}件を含む全${totalCount}件をAIで一括提案"
                        else "全${totalCount}件をAIで一括再提案"
                    )
                }
            }

            if (itemGroups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("品目データがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(itemGroups, key = { it.canonicalKey }) { group ->
                        val isExpanded = group.canonicalKey in expandedKeys
                        val matchedAccount = yayoiAccounts.find { it.id == group.yayoiAccountId }
                        ItemGroupCard(
                            group = group,
                            matchedAccount = matchedAccount,
                            yayoiAccounts = yayoiAccounts,
                            isExpanded = isExpanded,
                            fontSize = listFontSize,
                            viewModel = viewModel,
                            onToggleExpand = {
                                expandedKeys = if (isExpanded) expandedKeys - group.canonicalKey
                                               else expandedKeys + group.canonicalKey
                            },
                            onEditGroup = { groupEditTarget = group },
                            onEditIndividual = { item ->
                                itemEditTarget = item
                                itemEditGroupDefaultName = yayoiAccounts.find { it.id == group.yayoiAccountId }?.accountName
                            }
                        )
                    }
                }
            }
        }
    }

    // AI提案結果ダイアログ
    if (aiSuggestions.isNotEmpty()) {
        AiSuggestionDialog(
            suggestions = aiSuggestions,
            usageStats = aiUsageStats,
            onApply = { approved ->
                approved.forEach { s ->
                    viewModel.updateGroupDefaultAccount(s.canonicalKey, s.accountId)
                }
                viewModel.clearAiSuggestions()
            },
            onDismiss = { viewModel.clearAiSuggestions() }
        )
    }

    // グループのデフォルト科目編集ダイアログ（保存すると個別上書きは全解除）
    if (groupEditTarget != null) {
        GroupDefaultEditDialog(
            group = groupEditTarget!!,
            yayoiAccounts = yayoiAccounts,
            onDismiss = { groupEditTarget = null },
            onSave = { canonicalKey, accountId ->
                viewModel.updateGroupDefaultAccount(canonicalKey, accountId)
                groupEditTarget = null
            },
            onLoadAccounts = { accounts -> yayoiAccounts = accounts },
            viewModel = viewModel
        )
    }

    // 個別明細の上書きダイアログ
    if (itemEditTarget != null) {
        IndividualItemOverrideDialog(
            item = itemEditTarget!!,
            groupDefaultAccountName = itemEditGroupDefaultName,
            yayoiAccounts = yayoiAccounts,
            onDismiss = { itemEditTarget = null },
            onSave = { itemId, accountId ->
                viewModel.updateItemOverride(itemId, accountId)
                itemEditTarget = null
            }
        )
    }

    // AIエラーダイアログ
    aiError?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearAiSuggestions() },
            title = { Text("AI提案エラー") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAiSuggestions() }) { Text("OK") }
            }
        )
    }
}

// ─── AI提案結果ダイアログ ────────────────────────────────────────────────────

@Composable
private fun AiSuggestionDialog(
    suggestions: List<GeneralReceiptViewModel.AiSuggestion>,
    usageStats: GeminiReceiptClient.AiUsageStats?,
    onApply: (List<GeneralReceiptViewModel.AiSuggestion>) -> Unit,
    onDismiss: () -> Unit
) {
    val checked = remember(suggestions) {
        mutableStateListOf(*Array(suggestions.size) { true })
    }
    val approvedCount = checked.count { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI提案結果（${suggestions.size}件）") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                usageStats?.let {
                    Text(
                        text = it.toDisplayString(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(suggestions) { index, suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Checkbox(
                                checked = checked[index],
                                onCheckedChange = { checked[index] = it }
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 4.dp, top = 10.dp)
                            ) {
                                Text(
                                    text = suggestion.itemName,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("→ ", fontSize = 12.sp)
                                    Text(
                                        text = suggestion.accountName,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = suggestion.reason,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val approved = suggestions.filterIndexed { i, _ -> checked[i] }
                    onApply(approved)
                },
                enabled = approvedCount > 0
            ) {
                Text("承認（${approvedCount}件）")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

// ─── 共通コンポーネント ──────────────────────────────────────────────────────

@Composable
private fun StatColumn(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

// ─── グループカード（展開/折りたたみ対応） ────────────────────────────────────

@Composable
private fun ItemGroupCard(
    group: GeneralItemGroup,
    matchedAccount: YayoiAccount?,
    yayoiAccounts: List<YayoiAccount>,
    isExpanded: Boolean,
    fontSize: Float,
    viewModel: GeneralReceiptViewModel,
    onToggleExpand: () -> Unit,
    onEditGroup: () -> Unit,
    onEditIndividual: (GeneralReceiptItem) -> Unit
) {
    val isMatched = group.yayoiAccountId != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isMatched)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "折りたたむ" else "展開する",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (isMatched) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.itemName,
                        fontWeight = FontWeight.Medium,
                        fontSize = fontSize.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${group.count}件  合計 ¥${"%,d".format(group.totalPrice)}",
                        fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                // マッチング先（科目名・科目コード）
                Column(
                    modifier = Modifier.widthIn(max = 110.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    if (matchedAccount != null) {
                        Text(
                            text = matchedAccount.accountName,
                            fontWeight = FontWeight.Medium,
                            fontSize = (fontSize - 1f).coerceAtLeast(10f).sp,
                            color = MatchedAccountColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        matchedAccount.accountCode?.let { code ->
                            Text(
                                text = code,
                                fontSize = (fontSize - 3f).coerceAtLeast(9f).sp,
                                color = MatchedAccountColor,
                                maxLines = 1
                            )
                        }
                    } else {
                        Text(
                            "未設定",
                            fontSize = (fontSize - 1f).coerceAtLeast(10f).sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                IconButton(onClick = onEditGroup, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "グループ編集", modifier = Modifier.size(18.dp))
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Divider(modifier = Modifier.padding(horizontal = 8.dp))
                    val items by remember(group.canonicalKey) {
                        viewModel.getItemsByCanonicalKey(group.canonicalKey)
                    }.collectAsState(initial = null)

                    when {
                        items == null -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                        items!!.isEmpty() -> {
                            Text(
                                text = "データなし",
                                fontSize = (fontSize - 1f).coerceAtLeast(10f).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        else -> {
                            items!!.forEach { item ->
                                GeneralItemRow(
                                    item = item,
                                    groupDefaultAccount = matchedAccount,
                                    overrideAccount = yayoiAccounts.find { it.id == item.yayoiAccountId },
                                    fontSize = fontSize,
                                    onClick = { onEditIndividual(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralItemRow(
    item: GeneralReceiptItem,
    groupDefaultAccount: YayoiAccount?,
    overrideAccount: YayoiAccount?,
    fontSize: Float,
    onClick: () -> Unit
) {
    val isOverridden = item.yayoiAccountId != null
    val effectiveAccount = if (isOverridden) overrideAccount else groupDefaultAccount

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.itemName,
            fontSize = (fontSize - 2f).coerceAtLeast(11f).sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = effectiveAccount?.accountName ?: "未設定",
                fontSize = 11.sp,
                color = if (isOverridden) OverriddenAccountColor
                        else if (effectiveAccount != null) MatchedAccountColor
                        else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 80.dp)
            )
            if (isOverridden) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "個別変更済み",
                    tint = OverriddenAccountColor,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

// ─── グループのデフォルト科目編集ダイアログ ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDefaultEditDialog(
    group: GeneralItemGroup,
    yayoiAccounts: List<YayoiAccount>,
    onDismiss: () -> Unit,
    onSave: (canonicalKey: String, accountId: Long?) -> Unit,
    onLoadAccounts: (List<YayoiAccount>) -> Unit,
    viewModel: GeneralReceiptViewModel
) {
    var selectedAccountId by remember(group) { mutableStateOf(group.yayoiAccountId) }
    var searchText by remember { mutableStateOf("") }
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }
    var localAccounts by remember { mutableStateOf(yayoiAccounts) }

    LaunchedEffect(Unit) {
        val accounts = viewModel.loadYayoiAccounts()
        localAccounts = accounts
        onLoadAccounts(accounts)
    }

    val categoryAList = remember(localAccounts) {
        localAccounts.map { it.categoryA }.distinct().filter { it.isNotBlank() }.sorted()
    }

    val filtered = remember(localAccounts, searchText, selectedCategoryA) {
        localAccounts.filter { acc ->
            (selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText) == true) ||
             acc.searchKeyAlpha.contains(searchText, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(group.itemName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${group.count}件  ¥${"%,d".format(group.totalPrice)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "保存するとグループ全件に適用され、個別変更はリセットされます",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
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
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "検索") }
                )
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccountId = null }
                                .background(if (selectedAccountId == null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedAccountId == null, onClick = { selectedAccountId = null })
                            Spacer(Modifier.width(8.dp))
                            Text("（未設定）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(filtered, key = { it.id }) { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccountId = account.id }
                                .background(if (selectedAccountId == account.id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedAccountId == account.id, onClick = { selectedAccountId = account.id })
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.accountName, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    account.accountCode?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                                    Text("${account.categoryA} / ${account.categoryB}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(group.canonicalKey, selectedAccountId) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

// ─── 個別明細の上書きダイアログ ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IndividualItemOverrideDialog(
    item: GeneralReceiptItem,
    groupDefaultAccountName: String?,
    yayoiAccounts: List<YayoiAccount>,
    onDismiss: () -> Unit,
    onSave: (itemId: Long, accountId: Long?) -> Unit
) {
    var selectedAccountId by remember(item) { mutableStateOf(item.yayoiAccountId) }
    var searchText by remember { mutableStateOf("") }
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }

    val categoryAList = remember(yayoiAccounts) {
        yayoiAccounts.map { it.categoryA }.distinct().filter { it.isNotBlank() }.sorted()
    }

    val filtered = remember(yayoiAccounts, searchText, selectedCategoryA) {
        yayoiAccounts.filter { acc ->
            (selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText) == true) ||
             acc.searchKeyAlpha.contains(searchText, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("個別変更", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${item.itemName}　¥${"%,d".format(item.price)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
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
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "検索") }
                )
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccountId = null }
                                .background(if (selectedAccountId == null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedAccountId == null, onClick = { selectedAccountId = null })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("グループのデフォルトに戻す", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (groupDefaultAccountName != null) {
                                    Text(
                                        text = groupDefaultAccountName,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    items(filtered, key = { it.id }) { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccountId = account.id }
                                .background(if (selectedAccountId == account.id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedAccountId == account.id, onClick = { selectedAccountId = account.id })
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.accountName, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    account.accountCode?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                                    Text("${account.categoryA} / ${account.categoryB}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(item.id, selectedAccountId) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
