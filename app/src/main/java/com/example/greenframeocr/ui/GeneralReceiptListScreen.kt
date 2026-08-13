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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.ReceiptItemPreview
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
                title = { Text("レシート一覧") },
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
        // 年フィルター（データがあれば常に表示、ドロップダウンで1年ずつ表示）
        if (availableYears.isNotEmpty()) {
            var yearDropdownExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = yearDropdownExpanded && !lockYearToWorking,
                    onExpandedChange = { if (!lockYearToWorking) yearDropdownExpanded = it },
                    modifier = Modifier.width(130.dp)
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
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        lockYearToWorking = !lockYearToWorking
                        appPreferences.lockYearToWorking = lockYearToWorking
                    }
                ) {
                    Checkbox(
                        checked = lockYearToWorking,
                        onCheckedChange = {
                            lockYearToWorking = it
                            appPreferences.lockYearToWorking = it
                        }
                    )
                    Text("作業年で固定", fontSize = 12.sp)
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
                                if (receipt.geminiUsed) {
                                    Text(
                                        text = "AI解析済み",
                                        fontSize = (fontSize - 4f).coerceAtLeast(9f).sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { },
        modifier = modifier
    ) {
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
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false }
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
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日付（yyyy-MM-dd）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Divider()
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> item.localId }) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutocompleteTextField(
                                value = item.itemName,
                                onValueChange = { items[index] = item.copy(itemName = it) },
                                suggestions = itemNameSuggestions,
                                label = "品目名",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(4.dp))
                            OutlinedTextField(
                                value = item.priceStr,
                                onValueChange = { items[index] = item.copy(priceStr = it.filter(Char::isDigit)) },
                                label = { Text("¥") },
                                singleLine = true,
                                modifier = Modifier.width(88.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            IconButton(
                                onClick = { items.removeAt(index) },
                                enabled = items.size > 1,
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
                        AutocompleteTextField(
                            value = editStoreName,
                            onValueChange = { editStoreName = it },
                            suggestions = storeNameSuggestions,
                            label = "店舗名",
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
                                    AutocompleteTextField(
                                        value = item.itemName,
                                        onValueChange = { editItems[index] = item.copy(itemName = it) },
                                        suggestions = itemNameSuggestions,
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
