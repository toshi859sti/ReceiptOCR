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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
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
import com.example.greenframeocr.data.AccountingSoftware
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.GeneralItemGroup
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.GeminiReceiptClient
import com.example.greenframeocr.util.RomajiSearch
import com.example.greenframeocr.util.NumericPrefixCandidate
import com.example.greenframeocr.util.SimilarGroupPair
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel

// 科目名の表示色。グループのデフォルトでマッチした分は緑系、個別に上書きした分は赤系で固定
// （テーマプリセットによってprimary/tertiaryの色味が変わるため、区別のため固定色にしている）
internal val MatchedAccountColor = Color(0xFF2E7D32)
internal val OverriddenAccountColor = Color(0xFFC62828)

// 品目グループの並び替え順。COUNTはDAOの既定順（件数DESC・品目名ASC）をそのまま使う
private enum class ItemSortOrder(val label: String) {
    COUNT("件数順"),
    NAME("五十音順"),
    UNMATCHED_FIRST("未マッチ優先")
}

/**
 * 商品名・但し書きリスト画面。
 * 通帳摘要集約リスト（TekiyouMatchingScreen）と同じ「グループのデフォルト＋個別上書き」
 * 操作方式に合わせている：グループ行タップで展開、✏でグループのデフォルト変更（個別上書きは
 * 全解除）、展開後の個別明細タップで1件だけ上書き（「グループのデフォルトに戻す」あり）。
 * ✎でグループ内の全明細の品目名を一括リネーム（レジ番号等のノイズ除去用、canonicalKeyも再計算）。
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
    val aoiroAiSuggestions by viewModel.aoiroAiSuggestions.collectAsState()
    val similarGroupPairs by viewModel.similarGroupPairs.collectAsState()
    val isFindingSimilarGroups by viewModel.isFindingSimilarGroups.collectAsState()
    val numericPrefixCandidates by viewModel.numericPrefixCandidates.collectAsState()
    val isFindingNumericPrefixes by viewModel.isFindingNumericPrefixes.collectAsState()

    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }
    var expandedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupRenameTarget by remember { mutableStateOf<GeneralItemGroup?>(null) }
    // グループ・明細の科目（弥生）／科目・摘要（あおいろ）の設定。レシート詳細と同じダイアログ（ReceiptItemLinkEditor）
    var linkTarget by remember { mutableStateOf<ReceiptLinkTarget?>(null) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var yayoiFlaggedAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(ItemSortOrder.COUNT) }
    var unmatchedOnly by remember { mutableStateOf(false) }
    var filterPanelExpanded by remember { mutableStateOf(false) }
    var hasSearchedSimilarGroups by remember { mutableStateOf(false) }
    var hasSearchedNumericPrefixes by remember { mutableStateOf(false) }

    // あおいろ帳簿：グループに あおいろ科目・摘要 を付ける。明細ごとの個別上書きも弥生と同じようにできる（DB v41）
    val isAoiro = appPreferences.accountingSoftware == AccountingSoftware.AOIRO
    var aoiroVocab by remember { mutableStateOf<GeneralReceiptViewModel.AoiroVocab?>(null) }
    // 明細の行に出すレシートの日付・店名
    val receipts by viewModel.receipts.collectAsState()
    val receiptsById = remember(receipts) { receipts.associateBy { it.id } }

    LaunchedEffect(Unit) {
        val accounts = viewModel.loadYayoiAccounts()
        yayoiAccounts = accounts
        yayoiFlaggedAccounts = accounts.filter { it.usedForReceipt }
        if (isAoiro) aoiroVocab = viewModel.loadAoiroVocab()
    }

    fun isMatched(group: GeneralItemGroup) = if (isAoiro) group.accountKey != null else group.yayoiAccountId != null

    fun aoiroLabel(group: GeneralItemGroup): String? = aoiroLinkLabel(aoiroVocab, group)

    /** 明細に効いている あおいろ科目・摘要（個別上書きがあればそれ、無ければグループ） */
    fun aoiroItemLabel(item: GeneralReceiptItem, group: GeneralItemGroup): String? =
        aoiroItemLinkLabel(aoiroVocab, item, group)

    val matchedCount = itemGroups.count { isMatched(it) }
    val totalCount = itemGroups.size
    val unmatchedCount = totalCount - matchedCount

    val filteredGroups = remember(itemGroups, searchText, sortOrder, unmatchedOnly) {
        var list = itemGroups
        if (unmatchedOnly) list = list.filter { !isMatched(it) }
        if (searchText.isNotBlank()) list = list.filter { it.itemName.contains(searchText, ignoreCase = true) }
        when (sortOrder) {
            ItemSortOrder.COUNT -> list
            ItemSortOrder.NAME -> list.sortedBy { it.itemName }
            ItemSortOrder.UNMATCHED_FIRST -> list.sortedWith(compareBy({ isMatched(it) }, { -it.count }))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("商品名・但し書きリスト") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
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

            // AI一括割り当てボタン（弥生は既マッチ済みグループも対象に含めて再提案できる。あおいろは未マッチだけ）
            // 常時表示にして見落としを防ぐ（折りたたみパネルの中は展開しないと見えないため）
            if (itemGroups.isNotEmpty() && isAoiro) {
                AiSuggestButton(
                    label = if (unmatchedCount > 0) "未マッチ${unmatchedCount}件をAIで一括提案" else "未マッチの品目はありません",
                    isLoading = isAiMatching,
                    enabled = unmatchedCount > 0 && aoiroVocab != null,
                    onClick = { aoiroVocab?.let { viewModel.suggestAoiroAccountsForItems(itemGroups, it) } }
                )
            }
            if (itemGroups.isNotEmpty() && !isAoiro) {
                AiSuggestButton(
                    label = if (unmatchedCount > 0) "未マッチ${unmatchedCount}件を含む全${totalCount}件をAIで一括提案"
                        else "全${totalCount}件をAIで一括再提案",
                    isLoading = isAiMatching,
                    enabled = yayoiAccounts.isNotEmpty(),
                    onClick = {
                        val accounts = if (yayoiFlaggedAccounts.isNotEmpty()) yayoiFlaggedAccounts else yayoiAccounts
                        viewModel.suggestAccountsForItems(itemGroups, accounts)
                    }
                )
            }

            if (itemGroups.isNotEmpty()) {
                CollapsibleFilterPanel(
                    expanded = filterPanelExpanded,
                    onExpandedChange = { filterPanelExpanded = it },
                    hasActiveFilter = searchText.isNotBlank() || unmatchedOnly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListSearchField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        label = "品目名で検索",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToggleFilterChip(
                            label = "未マッチのみ",
                            checked = unmatchedOnly,
                            onCheckedChange = { unmatchedOnly = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChipGroup(
                            label = "並び替え:",
                            options = ItemSortOrder.values().toList(),
                            selected = sortOrder,
                            onSelect = { sortOrder = it },
                            optionLabel = { it.label },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        ListCountText(filteredGroups.size)
                    }

                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    // 類似グループ統合候補の検出ボタン。OCR誤読でノイズ文字が混入し、
                    // canonicalKeyの完全一致だけでは吸収できなかった別グループを編集距離で拾う
                    OutlinedButton(
                        onClick = {
                            hasSearchedSimilarGroups = true
                            viewModel.findSimilarGroups(itemGroups)
                        },
                        enabled = !isFindingSimilarGroups && itemGroups.size >= 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isFindingSimilarGroups) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("類似グループを検索中...")
                        } else {
                            Icon(Icons.Default.CallMerge, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("類似グループ候補を検出")
                        }
                    }

                    // 数字接頭辞（伝票行番号らしきノイズ）検出ボタン。正規表現ベースで判定するため
                    // AIは使わない（パターンが規則的でコスト・レイテンシをかける必要がないため）
                    OutlinedButton(
                        onClick = {
                            hasSearchedNumericPrefixes = true
                            viewModel.findNumericPrefixes(itemGroups)
                        },
                        enabled = !isFindingNumericPrefixes && itemGroups.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isFindingNumericPrefixes) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("数字接頭辞を検索中...")
                        } else {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("数字接頭辞候補を検出")
                        }
                    }
                }
            }

            if (itemGroups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("品目データがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (filteredGroups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("条件に一致する品目がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredGroups, key = { it.canonicalKey }) { group ->
                        val isExpanded = group.canonicalKey in expandedKeys
                        val matchedAccount = yayoiAccounts.find { it.id == group.yayoiAccountId }
                        ItemGroupCard(
                            group = group,
                            matchedAccount = matchedAccount,
                            yayoiAccounts = yayoiAccounts,
                            isExpanded = isExpanded,
                            fontSize = listFontSize,
                            viewModel = viewModel,
                            isAoiro = isAoiro,
                            aoiroLabel = if (isAoiro) aoiroLabel(group) else null,
                            aoiroItemLabel = { item -> aoiroItemLabel(item, group) },
                            receiptsById = receiptsById,
                            onToggleExpand = {
                                expandedKeys = if (isExpanded) expandedKeys - group.canonicalKey
                                               else expandedKeys + group.canonicalKey
                            },
                            onEditGroup = { linkTarget = ReceiptLinkTarget.Group(group.canonicalKey) },
                            onRenameGroup = { groupRenameTarget = group },
                            onEditIndividual = { item -> linkTarget = ReceiptLinkTarget.Item(item) }
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

    // AI提案結果ダイアログ（あおいろ）。承認するとグループの科目・摘要を保存し、グループ内の個別変更は外れる
    aoiroAiSuggestions?.let { suggestions ->
        AiMatchingDialog(
            rows = suggestions.mapIndexed { i, s ->
                AiSuggestionRow(
                    productId = i.toLong(),
                    productName = s.itemName,
                    key = s,
                    label = s.accountName,
                    reason = s.reason
                )
            },
            usageStats = aiUsageStats,
            title = "AI 科目提案（あおいろ）",
            emptyMessage = "未マッチの品目に対する提案が見つかりませんでした。",
            onDismiss = { viewModel.clearAiSuggestions() },
            onSave = { accepted -> viewModel.applyAoiroSuggestions(accepted.values.toList()) }
        )
    }

    // 類似グループ統合候補ダイアログ
    if (similarGroupPairs.isNotEmpty()) {
        SimilarGroupMergeDialog(
            pairs = similarGroupPairs,
            onApply = { approved ->
                viewModel.mergeGroups(approved)
                hasSearchedSimilarGroups = false
            },
            onDismiss = {
                viewModel.clearSimilarGroupPairs()
                hasSearchedSimilarGroups = false
            }
        )
    } else if (hasSearchedSimilarGroups && !isFindingSimilarGroups) {
        AlertDialog(
            onDismissRequest = { hasSearchedSimilarGroups = false },
            title = { Text("類似グループ候補") },
            text = { Text("類似している可能性のあるグループは見つかりませんでした") },
            confirmButton = {
                TextButton(onClick = { hasSearchedSimilarGroups = false }) { Text("OK") }
            }
        )
    }

    // 数字接頭辞除去候補ダイアログ
    if (numericPrefixCandidates.isNotEmpty()) {
        NumericPrefixCleanupDialog(
            candidates = numericPrefixCandidates,
            onApply = { approved ->
                viewModel.applyNumericPrefixCleanup(approved)
                hasSearchedNumericPrefixes = false
            },
            onDismiss = {
                viewModel.clearNumericPrefixCandidates()
                hasSearchedNumericPrefixes = false
            }
        )
    } else if (hasSearchedNumericPrefixes && !isFindingNumericPrefixes) {
        AlertDialog(
            onDismissRequest = { hasSearchedNumericPrefixes = false },
            title = { Text("数字接頭辞候補") },
            text = { Text("数字接頭辞らしきものは見つかりませんでした") },
            confirmButton = {
                TextButton(onClick = { hasSearchedNumericPrefixes = false }) { Text("OK") }
            }
        )
    }

    // グループ・明細の科目・摘要の設定（グループを保存するとグループ内の個別変更は解除）
    linkTarget?.let { target ->
        ReceiptItemLinkEditor(
            target = target,
            isAoiro = isAoiro,
            viewModel = viewModel,
            onDismiss = { linkTarget = null },
            onYayoiAccountsChanged = { accounts ->
                yayoiAccounts = accounts
                yayoiFlaggedAccounts = accounts.filter { it.usedForReceipt }
            }
        )
    }

    // グループ一括リネームダイアログ（レジ番号等のノイズ除去用）
    if (groupRenameTarget != null) {
        GroupRenameDialog(
            group = groupRenameTarget!!,
            onDismiss = { groupRenameTarget = null },
            onSave = { canonicalKey, newName ->
                viewModel.renameGroup(canonicalKey, newName)
                groupRenameTarget = null
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

// ─── 類似グループ統合候補ダイアログ ─────────────────────────────────────────────

@Composable
private fun SimilarGroupMergeDialog(
    pairs: List<SimilarGroupPair>,
    onApply: (List<SimilarGroupPair>) -> Unit,
    onDismiss: () -> Unit
) {
    val checked = remember(pairs) {
        mutableStateListOf(*Array(pairs.size) { true })
    }
    val approvedCount = checked.count { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("類似グループ候補（${pairs.size}件）") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Text(
                    text = "OCRの誤読で表記が少し異なるだけの同じ品目である可能性があります。" +
                        "統合すると件数の少ない方が多い方に吸収され、少ない方のグループ設定は破棄されます。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(pairs) { index, pair ->
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = pair.merge.itemName,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text("（${pair.merge.count}件）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("→ ", fontSize = 12.sp)
                                    Text(
                                        text = pair.keep.itemName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text("（${pair.keep.count}件）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(pairs.filterIndexed { i, _ -> checked[i] }) },
                enabled = approvedCount > 0
            ) {
                Text("統合（${approvedCount}件）")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

// ─── 数字接頭辞除去候補ダイアログ ───────────────────────────────────────────────

@Composable
private fun NumericPrefixCleanupDialog(
    candidates: List<NumericPrefixCandidate>,
    onApply: (List<NumericPrefixCandidate>) -> Unit,
    onDismiss: () -> Unit
) {
    // 高信頼（＃等の区切りあり）はデフォルトでチェック、低信頼（数字+空白のみ）は
    // 商品名の一部の数字と紛らわしいためデフォルトでチェックを外しておく
    val checked = remember(candidates) {
        mutableStateListOf(*Array(candidates.size) { candidates[it].highConfidence })
    }
    val approvedCount = checked.count { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数字接頭辞候補（${candidates.size}件）") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Text(
                    text = "伝票の行番号らしき数字が品目名の先頭に付いています。" +
                        "「＃」等の区切りがあるものは高信頼（デフォルトON）、数字と空白のみのものは" +
                        "商品名の一部の可能性もあるため低信頼（デフォルトOFF）としています。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(candidates) { index, candidate ->
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = candidate.group.itemName,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!candidate.highConfidence) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "低信頼",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("→ ", fontSize = 12.sp)
                                    Text(
                                        text = candidate.cleanedName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(candidates.filterIndexed { i, _ -> checked[i] }) },
                enabled = approvedCount > 0
            ) {
                Text("適用（${approvedCount}件）")
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
    isAoiro: Boolean = false,
    aoiroLabel: String? = null,   // あおいろモードのグループの「科目 ／ 摘要」（未設定なら null）
    aoiroItemLabel: (GeneralReceiptItem) -> String? = { null },   // あおいろモードの明細に効いている「科目 ／ 摘要」
    receiptsById: Map<Long, GeneralReceipt> = emptyMap(),   // 明細の行にレシートの日付・店名を出す
    onToggleExpand: () -> Unit,
    onEditGroup: () -> Unit,
    onRenameGroup: () -> Unit,
    onEditIndividual: (GeneralReceiptItem) -> Unit
) {
    val isMatched = if (isAoiro) aoiroLabel != null else group.yayoiAccountId != null

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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${group.count}件",
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
                    if (isAoiro && aoiroLabel != null) {
                        val parts = aoiroLabel.split(" ／ ", limit = 2)
                        Text(
                            text = parts[0],
                            fontWeight = FontWeight.Medium,
                            fontSize = (fontSize - 1f).coerceAtLeast(10f).sp,
                            color = MatchedAccountColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = parts.getOrNull(1) ?: "摘要なし",
                            fontSize = (fontSize - 3f).coerceAtLeast(9f).sp,
                            color = if (parts.size > 1) MatchedAccountColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (!isAoiro && matchedAccount != null) {
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
                IconButton(onClick = onRenameGroup, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "品目名を編集", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onEditGroup, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.AccountBalance, contentDescription = "勘定科目を設定", modifier = Modifier.size(18.dp))
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
                            // どのレシートの明細か分かるよう、日付・店名・金額を出す。新しいレシートから
                            items!!.sortedByDescending { receiptsById[it.receiptId]?.date.orEmpty() }.forEach { item ->
                                val receipt = receiptsById[item.receiptId]
                                val receiptInfo = receipt?.let { r ->
                                    listOf(r.date, r.storeName.ifBlank { "（店名なし）" }).joinToString("  ")
                                }
                                if (isAoiro) {
                                    GeneralItemLabelRow(
                                        itemName = item.itemName,
                                        receiptInfo = receiptInfo,
                                        price = item.price,
                                        label = aoiroItemLabel(item),
                                        isOverridden = item.overrideAccountKey != null,
                                        fontSize = fontSize,
                                        onClick = { onEditIndividual(item) }
                                    )
                                } else {
                                    GeneralItemRow(
                                        item = item,
                                        receiptInfo = receiptInfo,
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
}

@Composable
private fun GeneralItemRow(
    item: GeneralReceiptItem,
    receiptInfo: String?,
    groupDefaultAccount: YayoiAccount?,
    overrideAccount: YayoiAccount?,
    fontSize: Float,
    onClick: () -> Unit
) {
    val isOverridden = item.yayoiAccountId != null
    val effectiveAccount = if (isOverridden) overrideAccount else groupDefaultAccount
    GeneralItemLabelRow(item.itemName, receiptInfo, item.price, effectiveAccount?.accountName, isOverridden, fontSize, onClick)
}

/**
 * 明細行の本体。上の段にレシートの日付・店名（[receiptInfo]）と金額、下の段に品名と
 * この明細に効いている科目（[label]。未設定なら null）。[onClick] が null なら押せない
 */
@Composable
private fun GeneralItemLabelRow(
    itemName: String,
    receiptInfo: String?,
    price: Int,
    label: String?,
    isOverridden: Boolean,
    fontSize: Float,
    onClick: (() -> Unit)?
) {
    val smallSize = (fontSize - 3f).coerceAtLeast(10f).sp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = receiptInfo ?: "（レシート不明）",
                fontSize = (fontSize - 2f).coerceAtLeast(11f).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "¥${"%,d".format(price)}",
                fontSize = (fontSize - 2f).coerceAtLeast(11f).sp
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = itemName,
                fontSize = smallSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label ?: "未設定",
                fontSize = 11.sp,
                color = if (isOverridden) OverriddenAccountColor
                        else if (label != null) MatchedAccountColor
                        else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp)
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

// ─── グループ一括リネームダイアログ ─────────────────────────────────────────────

@Composable
private fun GroupRenameDialog(
    group: GeneralItemGroup,
    onDismiss: () -> Unit,
    onSave: (canonicalKey: String, newName: String) -> Unit
) {
    var text by remember(group) { mutableStateOf(group.itemName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("品目名を編集") },
        text = {
            Column {
                Text(
                    text = "レジ番号などのノイズを除いた品目名に修正できます。" +
                        "このグループの明細${group.count}件すべてに反映されます。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("品目名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(group.canonicalKey, text) },
                enabled = text.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

// ─── グループのデフォルト科目編集ダイアログ ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupDefaultEditDialog(
    group: GeneralItemGroup,
    yayoiAccounts: List<YayoiAccount>,
    yayoiFlaggedAccounts: List<YayoiAccount>,   // usedForReceipt=true の科目
    onDismiss: () -> Unit,
    onSave: (canonicalKey: String, accountId: Long?) -> Unit,
    onLoadAccounts: (List<YayoiAccount>) -> Unit,
    viewModel: GeneralReceiptViewModel
) {
    var selectedAccountId by remember(group) { mutableStateOf(group.yayoiAccountId) }
    var searchText by remember { mutableStateOf("") }
    var localAccounts by remember { mutableStateOf(yayoiAccounts) }
    var localFlaggedAccounts by remember { mutableStateOf(yayoiFlaggedAccounts) }

    LaunchedEffect(Unit) {
        val accounts = viewModel.loadYayoiAccounts()
        localAccounts = accounts
        localFlaggedAccounts = accounts.filter { it.usedForReceipt }
        onLoadAccounts(accounts)
    }

    val hasFlagged = localFlaggedAccounts.isNotEmpty()
    var showAll by remember(hasFlagged) { mutableStateOf(!hasFlagged) }
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }

    val categoryAList = remember(localAccounts) {
        sortYayoiCategoryA(localAccounts.map { it.categoryA }.filter { it.isNotBlank() })
    }

    val baseList = if (showAll) localAccounts else localFlaggedAccounts

    val filtered = remember(baseList, searchText, selectedCategoryA, showAll) {
        baseList.filter { acc ->
            (showAll.not() || selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText) == true) ||
             RomajiSearch.matches(acc.searchKeyAlpha, searchText))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(group.itemName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${group.count}件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "保存するとグループ全件に適用され、個別変更はリセットされます",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                // フラグ/全科目トグル（レシート取引フラグが設定された科目がある場合のみ）
                if (hasFlagged) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showAll,
                            onClick = { showAll = false; selectedCategoryA = null },
                            label = { Text("レシートフラグのみ (${localFlaggedAccounts.size}件)", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = showAll,
                            onClick = { showAll = true },
                            label = { Text("全科目", fontSize = 12.sp) }
                        )
                    }
                }
                if (showAll) {
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
internal fun IndividualItemOverrideDialog(
    item: GeneralReceiptItem,
    groupDefaultAccountName: String?,
    yayoiAccounts: List<YayoiAccount>,
    yayoiFlaggedAccounts: List<YayoiAccount>,   // usedForReceipt=true の科目
    onDismiss: () -> Unit,
    onSave: (itemId: Long, accountId: Long?) -> Unit
) {
    var selectedAccountId by remember(item) { mutableStateOf(item.yayoiAccountId) }
    var searchText by remember { mutableStateOf("") }
    val hasFlagged = yayoiFlaggedAccounts.isNotEmpty()
    var showAll by remember(hasFlagged) { mutableStateOf(!hasFlagged) }
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }

    val categoryAList = remember(yayoiAccounts) {
        sortYayoiCategoryA(yayoiAccounts.map { it.categoryA }.filter { it.isNotBlank() })
    }

    val baseList = if (showAll) yayoiAccounts else yayoiFlaggedAccounts

    val filtered = remember(baseList, searchText, selectedCategoryA, showAll) {
        baseList.filter { acc ->
            (showAll.not() || selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText) == true) ||
             RomajiSearch.matches(acc.searchKeyAlpha, searchText))
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
                // フラグ/全科目トグル（レシート取引フラグが設定された科目がある場合のみ）
                if (hasFlagged) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showAll,
                            onClick = { showAll = false; selectedCategoryA = null },
                            label = { Text("レシートフラグのみ (${yayoiFlaggedAccounts.size}件)", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = showAll,
                            onClick = { showAll = true },
                            label = { Text("全科目", fontSize = 12.sp) }
                        )
                    }
                }
                if (showAll) {
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
