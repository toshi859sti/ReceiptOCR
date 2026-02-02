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
enum class TekiyouFilter(val label: String) {
    ALL("全て"),
    MISSING("摘要未設定")
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
    var kaikakeTekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var sortOrder by remember { mutableStateOf(SortOrder.FREQUENCY) }
    var tekiyouFilter by remember { mutableStateOf(TekiyouFilter.ALL) }
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
        // 有効な買掛摘要のみ取得
        kaikakeTekiyouList = database.rakurakuTekiyouDao().getEnabledByCategory("買掛", "購入")
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

        // 摘要フィルタ
        filtered = when (tekiyouFilter) {
            TekiyouFilter.ALL -> filtered
            TekiyouFilter.MISSING -> filtered.filter { it.kaikakeTekiyouId == null }
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
            kaikakeTekiyouList = database.rakurakuTekiyouDao().getEnabledByCategory("買掛", "購入")
            allProducts = database.productMasterDao().getAll()
            applyFiltersAndSort()
        }
    }

    // フィルタ/並び替え変更時
    LaunchedEffect(searchQuery, selectedCategory, sortOrder, tekiyouFilter, allProducts) {
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

            // 並び替え & 摘要フィルタ
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

                // 摘要フィルタ
                Text("絞込:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TekiyouFilter.values().forEach { filter ->
                    FilterChip(
                        selected = tekiyouFilter == filter,
                        onClick = { tekiyouFilter = filter },
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
                        kaikakeTekiyou = kaikakeTekiyouList.find { it.id == product.kaikakeTekiyouId },
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
            kaikakeTekiyouList = kaikakeTekiyouList,
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
            kaikakeTekiyouList = kaikakeTekiyouList,
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

    // 削除確認ダイアログ（OCR学習データ数チェック付き）
    if (showDeleteDialog && selectedProduct != null) {
        var ocrVariantCount by remember { mutableStateOf(0) }
        var isCheckingVariants by remember { mutableStateOf(true) }

        LaunchedEffect(selectedProduct) {
            ocrVariantCount = database.ocrVariantDao().countByProductId(selectedProduct!!.id)
            isCheckingVariants = false
        }

        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                selectedProduct = null
            },
            title = { Text("削除確認") },
            text = {
                Column {
                    Text("「${selectedProduct!!.canonicalName}」を削除しますか？")
                    if (!isCheckingVariants && ocrVariantCount > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "⚠️ この商品には ${ocrVariantCount}件 のOCR学習データがあります。\n削除すると学習データも一緒に削除されます。",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // OCR学習データも一緒に削除（CASCADE）
                            database.ocrVariantDao().deleteByProductId(selectedProduct!!.id)
                            database.productMasterDao().deleteById(selectedProduct!!.id)
                            loadProducts()
                        }
                        showDeleteDialog = false
                        selectedProduct = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    enabled = !isCheckingVariants
                ) {
                    Text(if (ocrVariantCount > 0) "全て削除" else "削除")
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
    kaikakeTekiyou: RakurakuTekiyou?,
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

            // 買掛摘要
            Text(
                text = "摘要: ${kaikakeTekiyou?.tekiyouName ?: "未設定"}",
                fontSize = 12.sp,
                color = if (kaikakeTekiyou != null)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
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
    kaikakeTekiyouList: List<RakurakuTekiyou>,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (ProductMaster) -> Unit
) {
    var name by remember { mutableStateOf(product?.canonicalName ?: "") }
    var category by remember { mutableStateOf(product?.category ?: categories.first()) }
    var selectedTekiyouId by remember { mutableStateOf(product?.kaikakeTekiyouId) }
    var showTekiyouPicker by remember { mutableStateOf(false) }
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

                // 買掛摘要
                OutlinedTextField(
                    value = kaikakeTekiyouList.find { it.id == selectedTekiyouId }?.tekiyouName ?: "未設定",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("買掛摘要") },
                    trailingIcon = {
                        Row {
                            if (selectedTekiyouId != null) {
                                IconButton(onClick = { selectedTekiyouId = null }) {
                                    Icon(Icons.Default.Clear, "クリア")
                                }
                            }
                            IconButton(onClick = { showTekiyouPicker = true }) {
                                Icon(Icons.Default.ArrowDropDown, "選択")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTekiyouPicker = true }
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
                            kaikakeTekiyouId = selectedTekiyouId
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

    // 買掛摘要選択ダイアログ
    if (showTekiyouPicker) {
        TekiyouPickerDialog(
            productName = name,
            tekiyouList = kaikakeTekiyouList,
            selectedId = selectedTekiyouId,
            onSelect = { id ->
                selectedTekiyouId = id
                showTekiyouPicker = false
            },
            onDismiss = { showTekiyouPicker = false }
        )
    }
}

/**
 * 買掛摘要選択ダイアログ
 */
@Composable
private fun TekiyouPickerDialog(
    productName: String,
    tekiyouList: List<RakurakuTekiyou>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredList = remember(searchQuery, tekiyouList) {
        if (searchQuery.isEmpty()) {
            tekiyouList
        } else {
            tekiyouList.filter {
                it.tekiyouName.contains(searchQuery, ignoreCase = true) ||
                        it.searchKey.contains(searchQuery, ignoreCase = true) ||
                        it.kamoku.contains(searchQuery, ignoreCase = true)
            }
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
                    text = "買掛摘要を選択",
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
                    items(filteredList) { tekiyou ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(tekiyou.id) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedId == tekiyou.id,
                                onClick = { onSelect(tekiyou.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tekiyou.tekiyouName,
                                    fontWeight = FontWeight.Medium
                                )
                                Row {
                                    Text(
                                        text = tekiyou.kamoku,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    tekiyou.businessRatio?.let { ratio ->
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "(${ratio}%)",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (tekiyou.searchKey.isNotEmpty()) {
                                Text(
                                    text = tekiyou.searchKey,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
