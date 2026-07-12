package com.example.greenframeocr.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.GeneralItemGroup
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.GeminiReceiptClient
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralReceiptListScreen(
    viewModel: GeneralReceiptViewModel,
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val receipts by viewModel.receipts.collectAsState()
    val itemGroups by viewModel.itemGroups.collectAsState()
    val aiError by viewModel.aiError.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("レシート一覧", "品目別マッチング")
    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("レシート・領収書") },
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
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> ReceiptListTab(
                    receipts = receipts,
                    viewModel = viewModel,
                    onDelete = { viewModel.deleteReceipt(it) },
                    fontSize = listFontSize
                )
                1 -> ItemMatchingTab(
                    itemGroups = itemGroups,
                    viewModel = viewModel,
                    aiError = aiError
                )
            }
        }
    }
}

// ─── Tab1: レシート一覧 ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptListTab(
    receipts: List<GeneralReceipt>,
    viewModel: GeneralReceiptViewModel,
    onDelete: (GeneralReceipt) -> Unit,
    fontSize: Float = AppPreferences.DEFAULT_LIST_FONT_SIZE
) {
    var deleteTarget by remember { mutableStateOf<GeneralReceipt?>(null) }
    var detailTarget by remember { mutableStateOf<GeneralReceipt?>(null) }

    val currentCalendarYear = remember {
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
    }
    val availableYears = remember(receipts) {
        receipts.mapNotNull { Regex("20\\d{2}").find(it.date)?.value }
            .distinct()
            .sortedDescending()
    }
    // 当年データがあれば当年、なければ最新年、データなしはnull
    var selectedYear by remember(availableYears) {
        mutableStateOf(
            when {
                availableYears.contains(currentCalendarYear) -> currentCalendarYear
                availableYears.isNotEmpty() -> availableYears.first()
                else -> null
            }
        )
    }
    var selectedStore by remember { mutableStateOf<String?>(null) }

    // 年フィルター適用後（店舗の選択肢はこちらを元に算出）
    val yearFiltered = remember(receipts, selectedYear) {
        val year = selectedYear
        if (year == null) receipts else receipts.filter { it.date.contains(year) }
    }

    val availableStores = remember(yearFiltered) {
        yearFiltered.map { it.storeName }.filter { it.isNotBlank() }.distinct().sorted()
    }

    // 年が変わったら店舗フィルターをリセット
    LaunchedEffect(selectedYear) { selectedStore = null }

    val filteredReceipts = remember(yearFiltered, selectedStore) {
        val store = selectedStore
        if (store == null) yearFiltered else yearFiltered.filter { it.storeName == store }
    }

    val showFilters = availableYears.isNotEmpty() || availableStores.size >= 2

    Column(modifier = Modifier.fillMaxSize()) {
        // 年フィルター（データがあれば常に表示）
        if (availableYears.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedYear == null,
                        onClick = { selectedYear = null },
                        label = { Text("全て") }
                    )
                }
                items(availableYears) { year ->
                    FilterChip(
                        selected = selectedYear == year,
                        onClick = { selectedYear = if (selectedYear == year) null else year },
                        label = { Text("${year}年") }
                    )
                }
            }
        }

        // 店舗フィルター（現在の年フィルター内に2店舗以上ある場合のみ表示）
        if (availableStores.size >= 2) {
            var storeDropdownExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = storeDropdownExpanded,
                onExpandedChange = { storeDropdownExpanded = !storeDropdownExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = selectedStore ?: "全店舗",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("店舗") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = storeDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = storeDropdownExpanded,
                    onDismissRequest = { storeDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("全店舗") },
                        onClick = { selectedStore = null; storeDropdownExpanded = false }
                    )
                    availableStores.forEach { store ->
                        DropdownMenuItem(
                            text = { Text(store) },
                            onClick = { selectedStore = store; storeDropdownExpanded = false }
                        )
                    }
                }
            }
        }

        if (showFilters) Divider()

        if (filteredReceipts.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("レシートがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredReceipts, key = { it.id }) { receipt ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { detailTarget = receipt },
                                onLongClick = { deleteTarget = receipt }
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (receipt.storeName.isNotBlank()) receipt.storeName else "（店舗名なし）",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = fontSize.sp
                                )
                                Text(
                                    text = receipt.date.ifBlank { "日付不明" },
                                    fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (receipt.geminiUsed) {
                                    Text("AI解析済み", fontSize = (fontSize - 2f).coerceAtLeast(10f).sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(
                                text = "¥${"%,d".format(receipt.total)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = fontSize.sp
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("削除の確認") },
            text = {
                val label = if (target.storeName.isNotBlank()) target.storeName else target.date
                Text("「$label」を削除しますか？\nこの操作は取り消せません。")
            },
            confirmButton = {
                TextButton(onClick = { onDelete(target); deleteTarget = null }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
            }
        )
    }

    detailTarget?.let { receipt ->
        ReceiptDetailDialog(
            receipt = receipt,
            viewModel = viewModel,
            onDismiss = { detailTarget = null }
        )
    }
}

// ─── レシート詳細ダイアログ ──────────────────────────────────────────────────

private data class EditableItem(
    val localId: String = java.util.UUID.randomUUID().toString(),
    val originalId: Long? = null,
    val itemName: String = "",
    val priceStr: String = "0",
    val isExcluded: Boolean = false
)

private fun List<GeneralReceiptItem>.toEditableItems(): List<EditableItem> =
    map { EditableItem(originalId = it.id, itemName = it.itemName, priceStr = it.price.toString(), isExcluded = it.isExcluded) }

@Composable
private fun ReceiptDetailDialog(
    receipt: GeneralReceipt,
    viewModel: GeneralReceiptViewModel,
    onDismiss: () -> Unit
) {
    var originalItems by remember { mutableStateOf<List<GeneralReceiptItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isEditMode by remember { mutableStateOf(false) }
    var editStoreName by remember { mutableStateOf(receipt.storeName) }
    var editDate by remember { mutableStateOf(receipt.date) }
    val editItems = remember { mutableStateListOf<EditableItem>() }
    val coroutineScope = rememberCoroutineScope()

    fun resetEdit() {
        editStoreName = receipt.storeName
        editDate = receipt.date
        editItems.clear()
        editItems.addAll(originalItems.toEditableItems())
        isEditMode = false
    }

    LaunchedEffect(receipt.id) {
        val loaded = viewModel.getItemsForReceipt(receipt.id)
        originalItems = loaded
        editItems.clear()
        editItems.addAll(loaded.toEditableItems())
        isLoading = false
    }

    val calculatedTotal = editItems.sumOf { it.priceStr.toIntOrNull() ?: 0 }
    val calculatedExpenseTotal = editItems.filter { !it.isExcluded }.sumOf { it.priceStr.toIntOrNull() ?: 0 }

    AlertDialog(
        onDismissRequest = { if (!isEditMode) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (receipt.storeName.isNotBlank()) receipt.storeName else "（店舗名なし）",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = receipt.date.ifBlank { "日付不明" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { if (isEditMode) resetEdit() else isEditMode = true }) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Default.Close else Icons.Default.Edit,
                        contentDescription = if (isEditMode) "編集キャンセル" else "編集"
                    )
                }
            }
        },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    if (isEditMode) {
                        OutlinedTextField(
                            value = editStoreName,
                            onValueChange = { editStoreName = it },
                            label = { Text("店舗名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = editDate,
                            onValueChange = { editDate = it },
                            label = { Text("日付") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    } else if (receipt.registrationNumber.isNotBlank()) {
                        Text(
                            text = "登録番号: ${receipt.registrationNumber}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Divider()
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(if (isEditMode) 4.dp else 2.dp)
                    ) {
                        itemsIndexed(editItems, key = { _, item -> item.localId }) { index, item ->
                            if (isEditMode) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = !item.isExcluded,
                                        onCheckedChange = { editItems[index] = item.copy(isExcluded = !it) },
                                        modifier = Modifier.size(32.dp)
                                    )
                                    OutlinedTextField(
                                        value = item.itemName,
                                        onValueChange = { editItems[index] = item.copy(itemName = it) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = if (item.isExcluded)
                                            OutlinedTextFieldDefaults.colors(
                                                unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        else OutlinedTextFieldDefaults.colors()
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    OutlinedTextField(
                                        value = item.priceStr,
                                        onValueChange = { editItems[index] = item.copy(priceStr = it.filter(Char::isDigit)) },
                                        singleLine = true,
                                        modifier = Modifier.width(88.dp),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        label = { Text("¥") },
                                        colors = if (item.isExcluded)
                                            OutlinedTextFieldDefaults.colors(
                                                unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        else OutlinedTextFieldDefaults.colors()
                                    )
                                    IconButton(
                                        onClick = { editItems.removeAt(index) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "削除",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            } else {
                                val excluded = item.isExcluded
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (excluded) {
                                        Icon(
                                            Icons.Default.Block,
                                            contentDescription = "除外",
                                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                    Text(
                                        text = item.itemName.ifBlank { "（品目名なし）" },
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 1,
                                        color = if (excluded) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                else MaterialTheme.colorScheme.onSurface,
                                        textDecoration = if (excluded) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "¥${"%,d".format(item.priceStr.toIntOrNull() ?: 0)}",
                                        fontSize = 13.sp,
                                        fontWeight = if (excluded) FontWeight.Normal else FontWeight.Medium,
                                        color = if (excluded) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                else MaterialTheme.colorScheme.onSurface,
                                        textDecoration = if (excluded) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                }
                            }
                        }
                        if (isEditMode) {
                            item {
                                TextButton(
                                    onClick = { editItems.add(EditableItem()) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("品目を追加", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Divider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("合計", fontWeight = FontWeight.Bold)
                        Text(
                            text = "¥${"%,d".format(if (isEditMode) calculatedTotal else receipt.total)}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (isEditMode && calculatedExpenseTotal != calculatedTotal) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("経費計", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "¥${"%,d".format(calculatedExpenseTotal)}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isEditMode) {
                    coroutineScope.launch {
                        val updatedItems = editItems.map { ei ->
                            GeneralReceiptItem(
                                id = ei.originalId ?: 0L,
                                receiptId = receipt.id,
                                itemName = ei.itemName,
                                price = ei.priceStr.toIntOrNull() ?: 0,
                                isExcluded = ei.isExcluded
                            )
                        }
                        viewModel.saveReceiptEdits(
                            receipt.copy(storeName = editStoreName, date = editDate, total = calculatedTotal),
                            updatedItems,
                            originalItems
                        )
                        onDismiss()
                    }
                } else {
                    onDismiss()
                }
            }) {
                Text(if (isEditMode) "保存" else "閉じる")
            }
        },
        dismissButton = if (isEditMode) {
            { TextButton(onClick = { resetEdit() }) { Text("キャンセル") } }
        } else null
    )
}

// ─── Tab2: 品目別マッチング ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemMatchingTab(
    itemGroups: List<GeneralItemGroup>,
    viewModel: GeneralReceiptViewModel,
    aiError: String?
) {
    val matchedCount = itemGroups.count { it.yayoiAccountId != null }
    val totalCount = itemGroups.size
    val unmatchedCount = totalCount - matchedCount

    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val isAiMatching by viewModel.isAiMatching.collectAsState()
    val aiUsageStats by viewModel.aiUsageStats.collectAsState()

    var editTarget by remember { mutableStateOf<GeneralItemGroup?>(null) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }

    LaunchedEffect(Unit) {
        yayoiAccounts = viewModel.loadYayoiAccounts()
    }

    Column(modifier = Modifier.fillMaxSize()) {
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

        // AI一括割り当てボタン
        if (unmatchedCount > 0) {
            OutlinedButton(
                onClick = {
                    val unmatched = itemGroups.filter { it.yayoiAccountId == null }
                    viewModel.suggestAccountsForItems(unmatched, yayoiAccounts)
                },
                enabled = !isAiMatching && yayoiAccounts.isNotEmpty(),
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
                    Text("未マッチ ${unmatchedCount}件をAIで一括割り当て")
                }
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
                items(itemGroups, key = { it.itemName }) { group ->
                    ItemGroupCard(
                        group = group,
                        onClick = { editTarget = group }
                    )
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
                    viewModel.updateAccountForItemName(s.itemName, s.accountId)
                }
                viewModel.clearAiSuggestions()
            },
            onDismiss = { viewModel.clearAiSuggestions() }
        )
    }

    // 科目選択ダイアログ
    if (editTarget != null) {
        ItemAccountEditDialog(
            group = editTarget!!,
            yayoiAccounts = yayoiAccounts,
            onDismiss = { editTarget = null },
            onSave = { itemName, accountId ->
                viewModel.updateAccountForItemName(itemName, accountId)
                editTarget = null
            },
            onLoadAccounts = { accounts -> yayoiAccounts = accounts },
            viewModel = viewModel
        )
    }

    // AIエラーダイアログ
    if (aiError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearAiSuggestions() },
            title = { Text("AI提案エラー") },
            text = { Text(aiError) },
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

@Composable
private fun ItemGroupCard(
    group: GeneralItemGroup,
    onClick: () -> Unit
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${group.count}件  合計 ¥${"%,d".format(group.totalPrice)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "編集", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─── 科目選択ダイアログ ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemAccountEditDialog(
    group: GeneralItemGroup,
    yayoiAccounts: List<YayoiAccount>,
    onDismiss: () -> Unit,
    onSave: (itemName: String, accountId: Long?) -> Unit,
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
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                // 区分Aフィルター
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
            TextButton(onClick = { onSave(group.itemName, selectedAccountId) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
