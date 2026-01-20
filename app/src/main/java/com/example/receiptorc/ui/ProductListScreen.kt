package com.example.receiptorc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.receiptorc.data.*
import kotlinx.coroutines.launch

/** 並び替え順 */
enum class SortOrder(val label: String) {
    FREQUENCY("使用回数順"),
    NAME("五十音順")
}

/** フィルタ種別 */
enum class AccountFilter(val label: String) {
    ALL("全て"),
    MISSING_RAKURAKU("らくらく未設定"),
    MISSING_YAYOI("弥生未設定")
}

/**
 * 購買品リスト画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductListScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // State
    var allProducts by remember { mutableStateOf<List<ProductMaster>>(emptyList()) }
    var displayProducts by remember { mutableStateOf<List<ProductMaster>>(emptyList()) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var rakurakuAccounts by remember { mutableStateOf<List<RakurakuAccount>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var sortOrder by remember { mutableStateOf(SortOrder.FREQUENCY) }
    var accountFilter by remember { mutableStateOf(AccountFilter.ALL) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRecalculateDialog by remember { mutableStateOf(false) }
    var selectedProduct by remember { mutableStateOf<ProductMaster?>(null) }
    var recalculateResult by remember { mutableStateOf<String?>(null) }
    var isRecalculating by remember { mutableStateOf(false) }

    val categories = listOf("一般購買", "給油所", "農業機械")

    // 初期データ読み込み
    LaunchedEffect(Unit) {
        yayoiAccounts = database.yayoiAccountDao().getAll()
        rakurakuAccounts = database.rakurakuAccountDao().getAll()
        allProducts = database.productMasterDao().getAll()
    }

    // フィルタ・並び替え適用
    fun applyFiltersAndSort() {
        var filtered = allProducts

        // カテゴリフィルタ
        if (selectedCategory != null) {
            filtered = filtered.filter { it.category == selectedCategory }
        }

        // 検索フィルタ
        if (searchQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.canonicalName.contains(searchQuery, ignoreCase = true)
            }
        }

        // 勘定科目フィルタ
        filtered = when (accountFilter) {
            AccountFilter.ALL -> filtered
            AccountFilter.MISSING_RAKURAKU -> filtered.filter { it.rakurakuAccountId == null }
            AccountFilter.MISSING_YAYOI -> filtered.filter { it.yayoiAccountId == null }
        }

        // 並び替え
        displayProducts = when (sortOrder) {
            SortOrder.FREQUENCY -> filtered.sortedByDescending { it.frequencyCount }
            SortOrder.NAME -> filtered.sortedBy { it.canonicalName }
        }
    }

    // データ再読み込み
    fun loadProducts() {
        scope.launch {
            allProducts = database.productMasterDao().getAll()
            applyFiltersAndSort()
        }
    }

    // フィルタ/並び替え変更時
    LaunchedEffect(searchQuery, selectedCategory, sortOrder, accountFilter, allProducts) {
        applyFiltersAndSort()
    }

    // 再集計処理
    fun recalculateProducts() {
        scope.launch {
            isRecalculating = true
            try {
                // 伝票から全商品名を取得
                val receiptProductNames = database.receiptDao().getAllDistinctProductNames()

                // 現在の購買品リストの商品名を取得
                val existingNames = allProducts.map { it.canonicalName }.toSet()

                // 新規商品を抽出
                val newProducts = receiptProductNames.filter { name ->
                    name.isNotBlank() && name !in existingNames
                }

                // 新規商品を購買品リストに追加
                var addedCount = 0
                for (name in newProducts) {
                    val product = ProductMaster(
                        canonicalName = name,
                        category = "一般購買",
                        frequencyCount = 1
                    )
                    database.productMasterDao().insert(product)
                    addedCount++
                }

                recalculateResult = if (addedCount > 0) {
                    "${addedCount}件の新規商品を追加しました"
                } else {
                    "新規商品はありませんでした"
                }

                // リスト再読み込み
                loadProducts()
            } catch (e: Exception) {
                recalculateResult = "エラー: ${e.message}"
            } finally {
                isRecalculating = false
                showRecalculateDialog = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("購買品リスト") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                actions = {
                    // 再集計ボタン
                    IconButton(
                        onClick = { recalculateProducts() },
                        enabled = !isRecalculating
                    ) {
                        if (isRecalculating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, "再集計")
                        }
                    }
                    // 追加ボタン
                    IconButton(onClick = { showAddDialog = true }) {
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
            // 検索バー
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("商品名で検索") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "クリア")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )

            // カテゴリフィルタ
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("全て") }
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = if (selectedCategory == category) null else category },
                        label = { Text(category) }
                    )
                }
            }

            // 並び替え & 勘定科目フィルタ
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 並び替え
                Text("並替:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SortOrder.values().forEach { order ->
                    FilterChip(
                        selected = sortOrder == order,
                        onClick = { sortOrder = order },
                        label = { Text(order.label, fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 勘定科目フィルタ
                Text("絞込:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AccountFilter.values().forEach { filter ->
                    FilterChip(
                        selected = accountFilter == filter,
                        onClick = { accountFilter = filter },
                        label = { Text(filter.label, fontSize = 12.sp) }
                    )
                }
            }

            // 件数表示
            Text(
                text = "${displayProducts.size}件",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()

            // 商品リスト
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(displayProducts, key = { it.id }) { product ->
                    ProductListItem(
                        product = product,
                        yayoiAccount = yayoiAccounts.find { it.id == product.yayoiAccountId },
                        rakurakuAccount = rakurakuAccounts.find { it.id == product.rakurakuAccountId },
                        onClick = {
                            selectedProduct = product
                            showEditDialog = true
                        },
                        onDelete = {
                            selectedProduct = product
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

    // 編集ダイアログ
    if (showEditDialog && selectedProduct != null) {
        ProductEditDialog(
            product = selectedProduct!!,
            yayoiAccounts = yayoiAccounts,
            rakurakuAccounts = rakurakuAccounts,
            categories = categories,
            onDismiss = {
                showEditDialog = false
                selectedProduct = null
            },
            onSave = { updatedProduct ->
                scope.launch {
                    database.productMasterDao().update(updatedProduct)
                    loadProducts()
                }
                showEditDialog = false
                selectedProduct = null
            }
        )
    }

    // 追加ダイアログ
    if (showAddDialog) {
        ProductEditDialog(
            product = null,
            yayoiAccounts = yayoiAccounts,
            rakurakuAccounts = rakurakuAccounts,
            categories = categories,
            onDismiss = { showAddDialog = false },
            onSave = { newProduct ->
                scope.launch {
                    database.productMasterDao().insert(newProduct)
                    loadProducts()
                }
                showAddDialog = false
            }
        )
    }

    // 削除確認ダイアログ
    if (showDeleteDialog && selectedProduct != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                selectedProduct = null
            },
            title = { Text("削除確認") },
            text = { Text("「${selectedProduct!!.canonicalName}」を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            database.productMasterDao().deleteById(selectedProduct!!.id)
                            loadProducts()
                        }
                        showDeleteDialog = false
                        selectedProduct = null
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
                    selectedProduct = null
                }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // 再集計結果ダイアログ
    if (showRecalculateDialog) {
        AlertDialog(
            onDismissRequest = { showRecalculateDialog = false },
            title = { Text("再集計完了") },
            text = { Text(recalculateResult ?: "") },
            confirmButton = {
                TextButton(onClick = { showRecalculateDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * 商品リストアイテム
 */
@Composable
private fun ProductListItem(
    product: ProductMaster,
    yayoiAccount: YayoiAccount?,
    rakurakuAccount: RakurakuAccount?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 商品名
            Text(
                text = product.canonicalName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // カテゴリ & 使用頻度
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryChip(product.category)
                Text(
                    text = "使用: ${product.frequencyCount}回",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 勘定科目
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "らくらく: ${rakurakuAccount?.accountName ?: "未設定"}",
                    fontSize = 12.sp,
                    color = if (rakurakuAccount != null)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Text(
                    text = "弥生: ${yayoiAccount?.accountName ?: "未設定"}",
                    fontSize = 12.sp,
                    color = if (yayoiAccount != null)
                        MaterialTheme.colorScheme.secondary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }

    Divider(modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * カテゴリチップ
 */
@Composable
private fun CategoryChip(category: String) {
    val color = when (category) {
        "一般購買" -> MaterialTheme.colorScheme.primaryContainer
        "給油所" -> MaterialTheme.colorScheme.secondaryContainer
        "農業機械" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Text(
        text = category,
        fontSize = 11.sp,
        modifier = Modifier
            .background(color, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 商品編集ダイアログ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductEditDialog(
    product: ProductMaster?,
    yayoiAccounts: List<YayoiAccount>,
    rakurakuAccounts: List<RakurakuAccount>,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (ProductMaster) -> Unit
) {
    var name by remember { mutableStateOf(product?.canonicalName ?: "") }
    var category by remember { mutableStateOf(product?.category ?: categories.first()) }
    var selectedYayoiId by remember { mutableStateOf(product?.yayoiAccountId) }
    var selectedRakurakuId by remember { mutableStateOf(product?.rakurakuAccountId) }
    var showYayoiPicker by remember { mutableStateOf(false) }
    var showRakurakuPicker by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val isNew = product == null
    val title = if (isNew) "購買品追加" else "購買品編集"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 商品名
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("商品名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // カテゴリ選択
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("カテゴリ") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    category = cat
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }

                // らくらく勘定科目
                OutlinedTextField(
                    value = rakurakuAccounts.find { it.id == selectedRakurakuId }?.accountName ?: "未設定",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("らくらく勘定科目") },
                    trailingIcon = {
                        Row {
                            if (selectedRakurakuId != null) {
                                IconButton(onClick = { selectedRakurakuId = null }) {
                                    Icon(Icons.Default.Clear, "クリア")
                                }
                            }
                            IconButton(onClick = { showRakurakuPicker = true }) {
                                Icon(Icons.Default.ArrowDropDown, "選択")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showRakurakuPicker = true }
                )

                // 弥生勘定科目
                OutlinedTextField(
                    value = yayoiAccounts.find { it.id == selectedYayoiId }?.accountName ?: "未設定",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("弥生勘定科目") },
                    trailingIcon = {
                        Row {
                            if (selectedYayoiId != null) {
                                IconButton(onClick = { selectedYayoiId = null }) {
                                    Icon(Icons.Default.Clear, "クリア")
                                }
                            }
                            IconButton(onClick = { showYayoiPicker = true }) {
                                Icon(Icons.Default.ArrowDropDown, "選択")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showYayoiPicker = true }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        val newProduct = ProductMaster(
                            id = product?.id ?: 0,
                            canonicalName = name.trim(),
                            category = category,
                            frequencyCount = product?.frequencyCount ?: 0,
                            yayoiAccountId = selectedYayoiId,
                            rakurakuAccountId = selectedRakurakuId
                        )
                        onSave(newProduct)
                    }
                },
                enabled = name.isNotBlank()
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

    // らくらく勘定科目選択ダイアログ
    if (showRakurakuPicker) {
        AccountPickerDialog(
            productName = name,
            accountType = "らくらく勘定科目",
            accounts = rakurakuAccounts.map { it.id to it.accountName },
            selectedId = selectedRakurakuId,
            onSelect = { id ->
                selectedRakurakuId = id
                showRakurakuPicker = false
            },
            onDismiss = { showRakurakuPicker = false }
        )
    }

    // 弥生勘定科目選択ダイアログ
    if (showYayoiPicker) {
        AccountPickerDialog(
            productName = name,
            accountType = "弥生勘定科目",
            accounts = yayoiAccounts.map { it.id to it.accountName },
            selectedId = selectedYayoiId,
            onSelect = { id ->
                selectedYayoiId = id
                showYayoiPicker = false
            },
            onDismiss = { showYayoiPicker = false }
        )
    }
}

/**
 * 勘定科目選択ダイアログ
 */
@Composable
private fun AccountPickerDialog(
    productName: String,
    accountType: String,
    accounts: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredAccounts = remember(searchQuery, accounts) {
        if (searchQuery.isEmpty()) {
            accounts
        } else {
            accounts.filter { it.second.contains(searchQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = productName.ifEmpty { "新規商品" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = accountType,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn {
                    items(filteredAccounts) { (id, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(id) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedId == id,
                                onClick = { onSelect(id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
        dismissButton = {
            TextButton(onClick = { onSelect(null) }) {
                Text("クリア")
            }
        }
    )
}
