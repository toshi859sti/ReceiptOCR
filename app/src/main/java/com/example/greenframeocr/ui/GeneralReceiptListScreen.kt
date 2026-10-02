package com.example.greenframeocr.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.example.greenframeocr.data.AccountingSoftware
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.ReceiptItemPreview
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.AoiroChoboReceiptRules
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
    val itemPreviews by viewModel.itemPreviews.collectAsState()

    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }
    var showNewReceiptDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("レシート領収書一覧") },
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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewReceiptDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "手入力で新規追加")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ReceiptListTab(
                receipts = receipts,
                itemPreviews = itemPreviews,
                viewModel = viewModel,
                onDelete = { viewModel.deleteReceipt(it) },
                fontSize = listFontSize,
                appPreferences = appPreferences
            )
        }
    }

    if (showNewReceiptDialog) {
        NewReceiptDialog(
            viewModel = viewModel,
            onDismiss = { showNewReceiptDialog = false }
        )
    }
}

// レシート一覧の並び替え順。DATE_DESCはDAOの既定順（日付降順→登録順降順）をそのまま使う
private enum class ReceiptSortOrder(val label: String) {
    DATE_DESC("日付順"),
    CREATED_DESC("登録順（新しい順）")
}

private fun isToday(epochMillis: Long): Boolean {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochMillis)) == fmt.format(java.util.Date())
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptListTab(
    receipts: List<GeneralReceipt>,
    itemPreviews: Map<Long, ReceiptItemPreview>,
    viewModel: GeneralReceiptViewModel,
    onDelete: (GeneralReceipt) -> Unit,
    appPreferences: AppPreferences,
    fontSize: Float = AppPreferences.DEFAULT_LIST_FONT_SIZE
) {
    var deleteTarget by remember { mutableStateOf<GeneralReceipt?>(null) }
    var detailTarget by remember { mutableStateOf<GeneralReceipt?>(null) }
    var filterPanelExpanded by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf(ReceiptSortOrder.DATE_DESC) }
    var todayOnly by remember { mutableStateOf(false) }

    // カードに出す「今効いている支払方法の科目」。上書き・ルールで変わるので、一覧が変わるたびに引き直す
    val isAoiro = appPreferences.accountingSoftware == AccountingSoftware.AOIRO
    var paymentAccountNames by remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }
    LaunchedEffect(receipts, isAoiro) {
        paymentAccountNames = viewModel.resolvePaymentAccountNames(receipts, isAoiro)
    }

    val workingCalendarYear = remember { appPreferences.workingCalendarYear.toString() }
    var lockYearToWorking by remember { mutableStateOf(appPreferences.lockYearToWorking) }
    val availableYears = remember(receipts) {
        receipts.mapNotNull { Regex("20\\d{2}").find(it.date)?.value }
            .distinct()
            .sortedDescending()
    }
    // 作業年のデータがあれば作業年、なければ最新年、データなしはnull
    var selectedYear by remember(availableYears) {
        mutableStateOf(
            when {
                availableYears.contains(workingCalendarYear) -> workingCalendarYear
                availableYears.isNotEmpty() -> availableYears.first()
                else -> null
            }
        )
    }
    // 「作業年で固定」がONの間は他の年を選べないよう強制的に作業年へ戻す
    LaunchedEffect(lockYearToWorking, workingCalendarYear) {
        if (lockYearToWorking) selectedYear = workingCalendarYear
    }
    var selectedStore by remember { mutableStateOf<String?>(null) }
    var selectedMonth by remember { mutableStateOf<String?>(null) }

    // 年フィルター適用後（月の選択肢はこちらを元に算出）
    val yearFiltered = remember(receipts, selectedYear) {
        val year = selectedYear
        if (year == null) receipts else receipts.filter { it.date.contains(year) }
    }

    val availableMonths = remember(yearFiltered) {
        yearFiltered.mapNotNull { Regex("\\d{4}-(\\d{2})-\\d{2}").find(it.date)?.groupValues?.get(1) }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 0 }
    }

    // 月フィルター適用後（店舗の選択肢はこちらを元に算出）
    val monthFiltered = remember(yearFiltered, selectedMonth) {
        val month = selectedMonth
        if (month == null) yearFiltered
        else yearFiltered.filter { Regex("\\d{4}-(\\d{2})-\\d{2}").find(it.date)?.groupValues?.get(1) == month }
    }

    val availableStores = remember(monthFiltered) {
        monthFiltered.map { it.storeName }.filter { it.isNotBlank() }.distinct().sorted()
    }

    // 年が変わったら月・店舗フィルターをリセット
    LaunchedEffect(selectedYear) { selectedMonth = null; selectedStore = null }

    val filteredReceipts = remember(monthFiltered, selectedStore) {
        val store = selectedStore
        if (store == null) monthFiltered else monthFiltered.filter { it.storeName == store }
    }

    val todayFiltered = remember(filteredReceipts, todayOnly) {
        if (!todayOnly) filteredReceipts else filteredReceipts.filter { isToday(it.createdAt) }
    }

    val sortedReceipts = remember(todayFiltered, sortOrder) {
        when (sortOrder) {
            ReceiptSortOrder.DATE_DESC -> todayFiltered
            ReceiptSortOrder.CREATED_DESC -> todayFiltered.sortedByDescending { it.createdAt }
        }
    }

    val hasActiveFilter = selectedMonth != null || selectedStore != null ||
        todayOnly || sortOrder != ReceiptSortOrder.DATE_DESC

    val showFilters = availableYears.isNotEmpty() || availableStores.size >= 2 || availableMonths.size >= 2

    Column(modifier = Modifier.fillMaxSize()) {
        if (showFilters) {
            CollapsibleFilterPanel(
                expanded = filterPanelExpanded,
                onExpandedChange = { filterPanelExpanded = it },
                hasActiveFilter = hasActiveFilter,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 年フィルター・作業年で固定
                if (availableYears.isNotEmpty()) {
                    var yearDropdownExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = yearDropdownExpanded && !lockYearToWorking,
                            onExpandedChange = { if (!lockYearToWorking) yearDropdownExpanded = it },
                            modifier = Modifier.width(148.dp)
                        ) {
                            OutlinedTextField(
                                value = selectedYear?.let { "${it}年" } ?: "-",
                                onValueChange = {},
                                readOnly = true,
                                enabled = !lockYearToWorking,
                                label = { Text("年", fontSize = 11.sp) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearDropdownExpanded && !lockYearToWorking)
                                },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                singleLine = true,
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = yearDropdownExpanded && !lockYearToWorking,
                                onDismissRequest = { yearDropdownExpanded = false }
                            ) {
                                availableYears.forEach { year ->
                                    DropdownMenuItem(
                                        text = { Text("${year}年") },
                                        onClick = { selectedYear = year; yearDropdownExpanded = false }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    lockYearToWorking = !lockYearToWorking
                                    appPreferences.lockYearToWorking = lockYearToWorking
                                }
                        ) {
                            Checkbox(
                                checked = lockYearToWorking,
                                onCheckedChange = {
                                    lockYearToWorking = it
                                    appPreferences.lockYearToWorking = it
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("作業年で固定", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // 月フィルター・店舗フィルター（それぞれ条件を満たす場合のみ表示）
                if (availableMonths.size >= 2 || availableStores.size >= 2) {
                    var monthDropdownExpanded by remember { mutableStateOf(false) }
                    var storeDropdownExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 月フィルター（選択中の年内に2ヶ月以上データがある場合のみ表示）
                        if (availableMonths.size >= 2) {
                            ExposedDropdownMenuBox(
                                expanded = monthDropdownExpanded,
                                onExpandedChange = { monthDropdownExpanded = it },
                                modifier = Modifier.width(100.dp)
                            ) {
                                OutlinedTextField(
                                    value = selectedMonth?.let { "${it.toIntOrNull() ?: it}月" } ?: "全月",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("月", fontSize = 11.sp) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = monthDropdownExpanded)
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    singleLine = true,
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                                )
                                ExposedDropdownMenu(
                                    expanded = monthDropdownExpanded,
                                    onDismissRequest = { monthDropdownExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("全月") },
                                        onClick = { selectedMonth = null; monthDropdownExpanded = false }
                                    )
                                    availableMonths.forEach { month ->
                                        DropdownMenuItem(
                                            text = { Text("${month.toIntOrNull() ?: month}月") },
                                            onClick = { selectedMonth = month; monthDropdownExpanded = false }
                                        )
                                    }
                                }
                            }
                            if (availableStores.size >= 2) Spacer(modifier = Modifier.width(4.dp))
                        }

                        // 店舗フィルター（現在の年・月フィルター内に2店舗以上ある場合のみ表示）
                        if (availableStores.size >= 2) {
                            ExposedDropdownMenuBox(
                                expanded = storeDropdownExpanded,
                                onExpandedChange = { storeDropdownExpanded = !storeDropdownExpanded },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = selectedStore ?: "全店舗",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("店舗・発行者") },
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
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 並び替え・今日の新着のみ
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToggleFilterChip(
                        label = "今日の新着のみ",
                        checked = todayOnly,
                        onCheckedChange = { todayOnly = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChipGroup(
                        label = "並び替え:",
                        options = ReceiptSortOrder.values().toList(),
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
                    ListCountText(sortedReceipts.size)
                }
            }
            Divider()
        }

        if (sortedReceipts.isEmpty()) {
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
                items(sortedReceipts, key = { it.id }) { receipt ->
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
                            verticalAlignment = Alignment.Top
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
                                val preview = itemPreviews[receipt.id]
                                if (!preview?.itemNamesPreview.isNullOrBlank()) {
                                    Text(
                                        text = preview!!.itemNamesPreview,
                                        fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                // 「印字 → 今効いている科目」。個別上書きしたものは科目を詳細画面と同じ色にする
                                val printed = receipt.paymentMethodText?.takeIf { it.isNotBlank() } ?: "未記載"
                                val overridden = if (isAoiro) receipt.paymentOverrideAccountKey != null
                                                 else receipt.paymentAccountOverride != null
                                val accountLabel = when {
                                    receipt.id !in paymentAccountNames -> "…"
                                    else -> paymentAccountNames[receipt.id] ?: "科目なし"
                                }
                                Text(
                                    text = buildAnnotatedString {
                                        append("$printed → ")
                                        withStyle(
                                            SpanStyle(
                                                color = when {
                                                    overridden -> MaterialTheme.colorScheme.tertiary
                                                    paymentAccountNames[receipt.id] == null &&
                                                        receipt.id in paymentAccountNames -> MaterialTheme.colorScheme.error
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                fontWeight = if (overridden) FontWeight.Bold else null
                                            )
                                        ) { append(accountLabel) }
                                    },
                                    fontSize = (fontSize - 4f).coerceAtLeast(9f).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp)
                                )
                                Text(
                                    text = "¥${"%,d".format(receipt.total)}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = fontSize.sp
                                )
                                itemPreviews[receipt.id]?.let { p ->
                                    Text(
                                        text = "${p.itemCount}点",
                                        fontSize = (fontSize - 4f).coerceAtLeast(9f).sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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
            isAoiro = appPreferences.accountingSoftware == AccountingSoftware.AOIRO,
            fontSize = fontSize,
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

// ─── オートコンプリート入力欄（過去の入力実績から候補表示。店舗名／品目名で共用） ─────

@Composable
private fun AutocompleteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    label: String? = null,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(value, suggestions) {
        if (value.isBlank()) emptyList()
        else suggestions.filter { it != value && it.contains(value) }.take(5)
    }
    var fieldWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 候補は入力中に出すので、フォーカスを取らない DropdownMenu にする。ExposedDropdownMenu は
    // フォーカスを奪い、候補が出たあとのキー入力（バックスペースなど）が入力欄に届かなくなる
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = label?.let { { Text(it) } },
            singleLine = true,
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { fieldWidthPx = it.size.width }
        )
        DropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false),
            modifier = Modifier.width(with(density) { fieldWidthPx.toDp() })
        ) {
            filtered.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ─── 新規レシート手入力ダイアログ ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewReceiptDialog(
    viewModel: GeneralReceiptViewModel,
    onDismiss: () -> Unit
) {
    val todayDate = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.JAPAN).format(java.util.Date())
    }
    val storeNameSuggestions by viewModel.storeNameSuggestions.collectAsState()
    val itemNameSuggestions by viewModel.itemNameSuggestions.collectAsState()
    var storeName by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayDate) }
    val items = remember { mutableStateListOf(EditableItem()) }

    val calculatedTotal = items.sumOf { it.priceStr.toIntOrNull() ?: 0 }
    val hasValidItem = items.any { it.itemName.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("レシートを手入力で追加") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                AutocompleteTextField(
                    value = storeName,
                    onValueChange = { storeName = it },
                    suggestions = storeNameSuggestions,
                    label = "店舗名",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                DateOutlinedField(
                    value = date,
                    onValueChange = { date = it },
                    label = "日付",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Divider()
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> item.localId }) { index, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            AutocompleteTextField(
                                value = item.itemName,
                                onValueChange = { items[index] = item.copy(itemName = it) },
                                suggestions = itemNameSuggestions,
                                label = "品目名",
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = item.priceStr,
                                    onValueChange = { items[index] = item.copy(priceStr = it.filter(Char::isDigit)) },
                                    label = { Text("¥") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                IconButton(
                                    onClick = { items.removeAt(index) },
                                    enabled = items.size > 1,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "削除",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    item {
                        TextButton(
                            onClick = { items.add(EditableItem()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("品目を追加", fontSize = 13.sp)
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
                        text = "¥${"%,d".format(calculatedTotal)}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val receipt = GeneralReceipt(
                        date = date,
                        storeName = storeName,
                        total = calculatedTotal,
                        geminiUsed = false
                    )
                    val receiptItems = items
                        .filter { it.itemName.isNotBlank() }
                        .map {
                            GeneralReceiptItem(
                                receiptId = 0,
                                itemName = it.itemName,
                                price = it.priceStr.toIntOrNull() ?: 0
                            )
                        }
                    viewModel.saveReceipt(receipt, receiptItems)
                    onDismiss()
                },
                enabled = hasValidItem
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptDetailDialog(
    receipt: GeneralReceipt,
    viewModel: GeneralReceiptViewModel,
    isAoiro: Boolean = false,
    fontSize: Float = AppPreferences.DEFAULT_LIST_FONT_SIZE,
    onDismiss: () -> Unit
) {
    val storeNameSuggestions by viewModel.storeNameSuggestions.collectAsState()
    val itemNameSuggestions by viewModel.itemNameSuggestions.collectAsState()
    var originalItems by remember { mutableStateOf<List<GeneralReceiptItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isEditMode by remember { mutableStateOf(false) }
    var editStoreName by remember { mutableStateOf(receipt.storeName) }
    var editDate by remember { mutableStateOf(receipt.date) }
    val editItems = remember { mutableStateListOf<EditableItem>() }
    val coroutineScope = rememberCoroutineScope()
    var counterAccountName by remember { mutableStateOf<String?>(null) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var showPaymentAccountPicker by remember { mutableStateOf(false) }
    var aoiroAccounts by remember { mutableStateOf<List<AoiroChoboAccount>>(emptyList()) }
    var aoiroVocab by remember { mutableStateOf<GeneralReceiptViewModel.AoiroVocab?>(null) }
    // 明細を押したとき：まず「この明細だけ／同じ品目すべて」を選ぶ（linkChoice）→ 設定ダイアログ（linkTarget）
    var linkChoice by remember { mutableStateOf<GeneralReceiptItem?>(null) }
    var linkTarget by remember { mutableStateOf<ReceiptLinkTarget?>(null) }
    // 支払方法の科目の上書きはこの画面で変えられる。receipt は開いたときの値なので、
    // 編集の保存で古い上書きを書き戻さないよう今の値をここで持つ
    var yayoiPaymentOverride by remember { mutableStateOf(receipt.paymentAccountOverride) }
    var aoiroPaymentOverrideKey by remember { mutableStateOf(receipt.paymentOverrideAccountKey) }
    var aoiroPaymentOverrideName by remember { mutableStateOf(receipt.paymentOverrideAccountKeyName) }

    fun currentReceipt() = receipt.copy(
        paymentAccountOverride = yayoiPaymentOverride,
        paymentOverrideAccountKey = aoiroPaymentOverrideKey,
        paymentOverrideAccountKeyName = aoiroPaymentOverrideName
    )

    fun reloadCounterAccountName() {
        coroutineScope.launch {
            counterAccountName = if (isAoiro) {
                viewModel.resolveAoiroPaymentNameForReceipt(currentReceipt()) ?: "未設定（科目なしで PC に送ります）"
            } else {
                viewModel.resolveCounterAccountNameForReceipt(currentReceipt())
            }
        }
    }

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
        yayoiAccounts = viewModel.loadYayoiAccounts()
        if (isAoiro) {
            val vocab = viewModel.loadAoiroVocab()
            aoiroVocab = vocab
            aoiroAccounts = vocab.accounts
        }
        reloadCounterAccountName()
    }

    // 明細ごとの科目・摘要。この画面で変えてもすぐ表示に出るよう、明細とグループは変更を追う
    val liveItems by remember(receipt.id) { viewModel.itemsForReceiptFlow(receipt.id) }.collectAsState(initial = null)
    val itemGroups by viewModel.itemGroups.collectAsState()
    val groupsByKey = remember(itemGroups) { itemGroups.associateBy { it.canonicalKey } }
    val liveItemsById = remember(liveItems) { liveItems.orEmpty().associateBy { it.id } }
    // 編集の保存は元の行を copy するので、ここで変えた個別変更を古い値で書き戻さないよう元の行も合わせる
    LaunchedEffect(liveItems) {
        val items = liveItems
        if (items != null && !isEditMode && !isLoading) originalItems = items
    }

    /** 明細に効いている科目（あおいろは「科目 ／ 摘要」）と、個別変更かどうか。設定できない明細は null */
    fun linkLabelOf(item: GeneralReceiptItem): Pair<String?, Boolean> {
        val group = groupsByKey[item.canonicalKey]
        return if (isAoiro) {
            aoiroItemLinkLabel(aoiroVocab, item, group) to (item.overrideAccountKey != null)
        } else {
            val overridden = item.yayoiAccountId != null
            val accountId = item.yayoiAccountId ?: group?.yayoiAccountId
            yayoiAccounts.find { it.id == accountId }?.accountName to overridden
        }
    }

    val calculatedTotal = editItems.sumOf { it.priceStr.toIntOrNull() ?: 0 }
    val calculatedExpenseTotal = editItems.filter { !it.isExcluded }.sumOf { it.priceStr.toIntOrNull() ?: 0 }

    val titleFontSize = (fontSize + 2f).coerceAtLeast(12f)
    val subFontSize = (fontSize - 2f).coerceAtLeast(10f)
    val bodyFontSize = fontSize
    val smallFontSize = (fontSize - 1f).coerceAtLeast(10f)

    Dialog(
        onDismissRequest = { if (!isEditMode) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (receipt.storeName.isNotBlank()) receipt.storeName else "（店舗名なし）",
                            fontWeight = FontWeight.Bold,
                            fontSize = titleFontSize.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = receipt.date.ifBlank { "日付不明" },
                            fontSize = subFontSize.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (isEditMode) resetEdit() else onDismiss() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { if (isEditMode) resetEdit() else isEditMode = true }) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.Close else Icons.Default.Edit,
                            contentDescription = if (isEditMode) "編集キャンセル" else "編集"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp)
                ) {
                    if (isEditMode) {
                        AutocompleteTextField(
                            value = editStoreName,
                            onValueChange = { editStoreName = it },
                            suggestions = storeNameSuggestions,
                            label = "店舗名",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        DateOutlinedField(
                            value = editDate,
                            onValueChange = { editDate = it },
                            label = "日付",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    } else if (receipt.registrationNumber.isNotBlank()) {
                        Text(
                            text = "登録番号: ${receipt.registrationNumber}",
                            fontSize = subFontSize.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (!isEditMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPaymentAccountPicker = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val overridden = if (isAoiro) aoiroPaymentOverrideKey != null else yayoiPaymentOverride != null
                            Text(
                                text = (if (isAoiro) "支払方法の科目（あおいろ）: " else "支払方法の科目（弥生CSV出力用）: ") +
                                    (counterAccountName ?: "…"),
                                fontSize = subFontSize.sp,
                                color = if (overridden)
                                    MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "支払方法の科目を変更",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Divider()
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(if (isEditMode) 4.dp else 2.dp)
                    ) {
                        itemsIndexed(editItems, key = { _, item -> item.localId }) { index, item ->
                            if (isEditMode) {
                                val fieldColors = if (item.isExcluded)
                                    OutlinedTextFieldDefaults.colors(
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                else OutlinedTextFieldDefaults.colors()
                                // 全画面表示になり幅に余裕があるため1行に収める
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
                                    AutocompleteTextField(
                                        value = item.itemName,
                                        onValueChange = { editItems[index] = item.copy(itemName = it) },
                                        suggestions = itemNameSuggestions,
                                        modifier = Modifier.weight(1f),
                                        colors = fieldColors
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    OutlinedTextField(
                                        value = item.priceStr,
                                        onValueChange = { editItems[index] = item.copy(priceStr = it.filter(Char::isDigit)) },
                                        singleLine = true,
                                        modifier = Modifier.width(110.dp),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        label = { Text("¥") },
                                        colors = fieldColors
                                    )
                                    IconButton(
                                        onClick = { editItems.removeAt(index) },
                                        modifier = Modifier.size(40.dp)
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
                                val itemColor = if (excluded) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                 else MaterialTheme.colorScheme.onSurface
                                val itemDecoration = if (excluded) TextDecoration.LineThrough else TextDecoration.None
                                // 科目・摘要は今の DB の行で出す。品目名が空・経費対象外の明細はグループが無いので設定できない
                                val live = item.originalId?.let { liveItemsById[it] }
                                val linkable = live != null && !live.isExcluded && live.itemName.isNotBlank() &&
                                    groupsByKey.containsKey(live.canonicalKey)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (linkable) Modifier.clickable { linkChoice = live } else Modifier)
                                        .padding(vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (excluded) {
                                            Icon(
                                                Icons.Default.Block,
                                                contentDescription = "除外",
                                                modifier = Modifier.size(14.dp).padding(end = 2.dp),
                                                tint = itemColor
                                            )
                                        }
                                        Text(
                                            text = item.itemName.ifBlank { "（品目名なし）" },
                                            fontSize = bodyFontSize.sp,
                                            modifier = Modifier.weight(1f),
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 2,
                                            color = itemColor,
                                            textDecoration = itemDecoration
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (linkable && live != null) {
                                            val (label, overridden) = linkLabelOf(live)
                                            Text(
                                                text = (label ?: "未設定") + if (overridden) "（個別）" else "",
                                                fontSize = (smallFontSize - 1f).coerceAtLeast(10f).sp,
                                                color = when {
                                                    overridden -> OverriddenAccountColor
                                                    label != null -> MatchedAccountColor
                                                    else -> MaterialTheme.colorScheme.error
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "科目を設定",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp).size(14.dp)
                                            )
                                        } else {
                                            Spacer(Modifier.weight(1f))
                                        }
                                        Text(
                                            text = "¥${"%,d".format(item.priceStr.toIntOrNull() ?: 0)}",
                                            fontSize = bodyFontSize.sp,
                                            fontWeight = if (excluded) FontWeight.Normal else FontWeight.Medium,
                                            color = itemColor,
                                            textDecoration = itemDecoration
                                        )
                                    }
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
                        Text("合計", fontWeight = FontWeight.Bold, fontSize = titleFontSize.sp)
                        Text(
                            text = "¥${"%,d".format(if (isEditMode) calculatedTotal else receipt.total)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = titleFontSize.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (isEditMode && calculatedExpenseTotal != calculatedTotal) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("経費計", fontSize = smallFontSize.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "¥${"%,d".format(calculatedExpenseTotal)}",
                                fontSize = smallFontSize.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (isEditMode) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            OutlinedButton(
                                onClick = { resetEdit() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("キャンセル")
                            }
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        // 既存の明細は元の行を copy する。組み直すと個別上書き（弥生・あおいろ）や
                                        // 出力済みの印（exportedAt）が黙って消える
                                        val originalsById = originalItems.associateBy { it.id }
                                        val updatedItems = editItems.map { ei ->
                                            val price = ei.priceStr.toIntOrNull() ?: 0
                                            ei.originalId?.let { originalsById[it] }?.copy(
                                                itemName = ei.itemName,
                                                price = price,
                                                isExcluded = ei.isExcluded
                                            ) ?: GeneralReceiptItem(
                                                receiptId = receipt.id,
                                                itemName = ei.itemName,
                                                price = price,
                                                isExcluded = ei.isExcluded
                                            )
                                        }
                                        viewModel.saveReceiptEdits(
                                            currentReceipt().copy(storeName = editStoreName, date = editDate, total = calculatedTotal),
                                            updatedItems,
                                            originalItems
                                        )
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("保存")
                            }
                        }
                    }
                }
            }
    }
    }
    }

    // 明細の科目・摘要：この明細だけ（個別変更）か、同じ品目名の明細すべて（グループ）かを選ぶ
    linkChoice?.let { item ->
        val group = groupsByKey[item.canonicalKey]
        AlertDialog(
            onDismissRequest = { linkChoice = null },
            title = { Text(if (isAoiro) "あおいろ科目・摘要の設定" else "勘定科目の設定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.itemName, fontWeight = FontWeight.Medium)
                    Text(
                        "「同じ品目すべて」は商品名・但し書きリストのグループの設定です。保存すると、そのグループの明細ごとの個別変更は解除します",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { linkChoice = null; linkTarget = ReceiptLinkTarget.Item(item) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("この明細だけ") }
                    OutlinedButton(
                        onClick = { linkChoice = null; linkTarget = ReceiptLinkTarget.Group(item.canonicalKey) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("同じ品目すべて（${group?.count ?: 0}件）") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { linkChoice = null }) { Text("キャンセル") } }
        )
    }
    linkTarget?.let { target ->
        ReceiptItemLinkEditor(
            target = target,
            isAoiro = isAoiro,
            viewModel = viewModel,
            onDismiss = { linkTarget = null },
            onYayoiAccountsChanged = { yayoiAccounts = it }
        )
    }

    if (showPaymentAccountPicker && isAoiro) {
        AoiroPaymentOverrideDialog(
            subject = receipt.paymentMethodText?.takeIf { it.isNotBlank() }
                ?.let { "${receipt.storeName}（印字: $it）" } ?: receipt.storeName,
            accounts = aoiroAccounts,
            currentKey = aoiroPaymentOverrideKey,
            currentName = aoiroPaymentOverrideName,
            onDismiss = { showPaymentAccountPicker = false },
            onSave = { key, name ->
                viewModel.updateReceiptPaymentAccountKeyOverride(receipt.id, key, name)
                aoiroPaymentOverrideKey = key
                aoiroPaymentOverrideName = name
                showPaymentAccountPicker = false
                reloadCounterAccountName()
            }
        )
    } else if (showPaymentAccountPicker) {
        PaymentAccountPickerDialog(
            currentOverrideId = yayoiPaymentOverride,
            accounts = yayoiAccounts,
            onDismiss = { showPaymentAccountPicker = false },
            onSelect = { accountId ->
                viewModel.updateReceiptPaymentAccountOverride(receipt.id, accountId)
                yayoiPaymentOverride = accountId
                showPaymentAccountPicker = false
                reloadCounterAccountName()
            }
        )
    }
}

/**
 * レシート単位のあおいろの支払方法の科目（貸方）の上書き。科目を外して保存すると支払方法のルールに戻る。
 * 候補は支払方法のルールと同じ（現金・未払金・事業主借。「絞り込み外も表示」で口座・借入金なども）
 */
@Composable
fun AoiroPaymentOverrideDialog(
    subject: String,
    accounts: List<AoiroChoboAccount>,
    currentKey: String?,
    currentName: String?,
    onDismiss: () -> Unit,
    onSave: (accountKey: String?, accountKeyName: String?) -> Unit
) {
    var accountKey by remember { mutableStateOf(currentKey) }
    var showPicker by remember { mutableStateOf(false) }
    fun nameOf(key: String): String? =
        accounts.find { it.accountKey == key }?.name ?: currentName.takeIf { key == currentKey }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("支払方法の科目を変更（あおいろ）", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (subject.isNotBlank()) {
                    Text(subject, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (accounts.isEmpty()) {
                    Text(
                        "あおいろ帳簿の科目がまだ取り込まれていません。設定画面から取り込んでください。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    PickerField(
                        label = "支払方法の科目（あおいろ）",
                        value = accountKey?.let { nameOf(it) ?: it } ?: "ルール判定に従う",
                        hasValue = accountKey != null,
                        onPick = { showPicker = true },
                        onClear = { accountKey = null }
                    )
                }
                Text(
                    "科目を外して保存すると、支払方法のルールで決まる科目に戻ります（どれにも当たらなければ既定の科目）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(accountKey, accountKey?.let { nameOf(it) }) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )

    if (showPicker) {
        AoiroAccountPickerDialog(
            subject = subject,
            emptySubject = "支払方法",
            accounts = AoiroChoboReceiptRules.paymentCandidates(accounts),
            allAccounts = AoiroChoboReceiptRules.allPaymentCandidates(accounts),
            memoCandidates = null,
            selectedKey = accountKey,
            onSelect = { key ->
                accountKey = key
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

/**
 * レシート単位の相手科目（貸方勘定科目）個別上書きピッカー。
 * 「ルール判定に戻す」を選ぶとpaymentAccountOverrideをnullに戻し、
 * ReceiptPaymentMethodRuleでの自動判定（またはデフォルト現金）に従う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentAccountPickerDialog(
    currentOverrideId: Long?,
    accounts: List<YayoiAccount>,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    val filtered = remember(accounts, searchText) {
        accounts.filter { acc ->
            searchText.isEmpty() ||
            acc.accountName.contains(searchText, ignoreCase = true) ||
            (acc.accountCode?.contains(searchText, ignoreCase = true) == true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("支払方法の科目を変更", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
                                .clickable { onSelect(null) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentOverrideId == null, onClick = { onSelect(null) })
                            Spacer(Modifier.width(8.dp))
                            Text("ルール判定に戻す（未一致時は現金）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(filtered, key = { it.id }) { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(account.id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentOverrideId == account.id, onClick = { onSelect(account.id) })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(account.accountName, fontWeight = FontWeight.Medium)
                                Text(
                                    "${account.categoryA} / ${account.categoryB}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}
