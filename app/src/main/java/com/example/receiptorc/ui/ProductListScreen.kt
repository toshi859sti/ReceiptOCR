package com.example.receiptorc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var products by remember { mutableStateOf<List<ProductMaster>>(emptyList()) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var rakurakuAccounts by remember { mutableStateOf<List<RakurakuAccount>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedProduct by remember { mutableStateOf<ProductMaster?>(null) }

    val categories = listOf("一般購買", "給油所", "農業機械")

    // 初期データ読み込み
    LaunchedEffect(Unit) {
        yayoiAccounts = database.yayoiAccountDao().getAll()
        rakurakuAccounts = database.rakurakuAccountDao().getAll()
        products = database.productMasterDao().getAll()
    }

    // 検索/フィルタ
    fun loadProducts() {
        scope.launch {
            products = when {
                selectedCategory != null && searchQuery.isNotEmpty() ->
                    database.productMasterDao().searchByCategoryAndName(selectedCategory!!, searchQuery)
                selectedCategory != null ->
                    database.productMasterDao().getByCategory(selectedCategory!!)
                searchQuery.isNotEmpty() ->
                    database.productMasterDao().searchByName(searchQuery)
                else ->
                    database.productMasterDao().getAll()
            }
        }
    }

    // 検索/フィルタ変更時に再読み込み
    LaunchedEffect(searchQuery, selectedCategory) {
        loadProducts()
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

            // 件数表示
            Text(
                text = "${products.size}件",
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
                items(products, key = { it.id }) { product ->
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
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "弥生: ${yayoiAccount?.accountName ?: "未設定"}",
                    fontSize = 12.sp,
                    color = if (yayoiAccount != null)
                        MaterialTheme.colorScheme.secondary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
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
            title = "らくらく勘定科目",
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
            title = "弥生勘定科目",
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
    title: String,
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
        title = { Text(title) },
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
