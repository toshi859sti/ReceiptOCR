package com.example.greenframeocr.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.*
import kotlinx.coroutines.launch

/** 並び替え順 */
enum class SortOrder(val label: String) {
    FREQUENCY("使用回数順"),
    NAME("五十音順")
}

/** フィルタ種別 */
enum class TekiyouFilter(val label: String) {
    ALL("全て"),
    MISSING("摘要未設定"),
    CERTIFIED("確定済み")
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
    var showMergeDialog by remember { mutableStateOf(false) }
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
            TekiyouFilter.CERTIFIED -> filtered.filter { it.isCertified }
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
                // 小計・合計行として誤登録されたエントリを削除
                val subtotalProducts = allProducts.filter {
                    it.canonicalName.contains("小計") || it.canonicalName.contains("合計")
                }
                for (product in subtotalProducts) {
                    database.ocrVariantDao().deleteByProductId(product.id)
                    database.productMasterDao().deleteById(product.id)
                }

                // 削除後の最新リストを取得
                val currentProducts = database.productMasterDao().getAll()

                // 伝票から全商品名を取得（小計除外済み）
                val receiptProductNames = database.receiptDao().getAllDistinctProductNames()

                // 現在の購買品リストの商品名を正規化して取得（スペース正規化で重複防止）
                val existingNormalized = currentProducts.map { it.canonicalName.normalizeSpaces() }.toSet()

                // 新規商品を抽出（正規化後に既存と一致するものはスキップ）
                val newProducts = receiptProductNames.filter { name ->
                    name.isNotBlank() && name.normalizeSpaces() !in existingNormalized
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

                recalculateResult = buildString {
                    if (subtotalProducts.isNotEmpty()) {
                        append("小計エントリ ${subtotalProducts.size}件を削除\n")
                    }
                    append(if (addedCount > 0) "${addedCount}件の新規商品を追加しました" else "新規商品はありませんでした")
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
                        },
                        onMerge = {
                            selectedProduct = product
                            showMergeDialog = true
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
                val oldName = selectedProduct?.canonicalName
                scope.launch {
                    database.productMasterDao().update(updatedProduct)
                    // 商品名が変わった場合はReceiptItemも書き換え
                    if (oldName != null && oldName != updatedProduct.canonicalName) {
                        database.receiptDao().updateProductNameInReceiptItems(oldName, updatedProduct.canonicalName)
                    }
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

    // 統合ダイアログ
    if (showMergeDialog && selectedProduct != null) {
        ProductMergeDialog(
            source = selectedProduct!!,
            allProducts = allProducts,
            onConfirm = { target ->
                val src = selectedProduct!!
                scope.launch {
                    // OcrVariantを統合先に付け替え
                    database.ocrVariantDao().updateProductId(src.id, target.id)
                    // 統合元のcanonicalNameをOcrVariantとして統合先に登録
                    // → 既存のReceiptItemからの摘要逆引きが引き続き機能するよう保全
                    val now = System.currentTimeMillis()
                    val existingVariant = database.ocrVariantDao().getByText(src.canonicalName)
                    when {
                        existingVariant == null -> {
                            // 存在しない → 新規作成（LOCKED/USERで確実に機能させる）
                            database.ocrVariantDao().insert(
                                OcrVariant(
                                    productId = target.id,
                                    variantText = src.canonicalName,
                                    normalizedText = src.canonicalName,
                                    confidenceLevel = ConfidenceLevel.LOCKED.name,
                                    source = VariantSource.USER.name,
                                    firstSeenAt = now,
                                    lastSeenAt = now
                                )
                            )
                        }
                        existingVariant.productId != target.id -> {
                            // 別の商品を指している → 統合先に付け替え
                            database.ocrVariantDao().update(
                                existingVariant.copy(productId = target.id)
                            )
                        }
                        // else: 既に統合先を指している → そのまま
                    }
                    // 使用頻度を合算
                    database.productMasterDao().update(
                        target.copy(frequencyCount = target.frequencyCount + src.frequencyCount)
                    )
                    // ReceiptItemの商品名を統合先に書き換え
                    database.receiptDao().updateProductNameInReceiptItems(src.canonicalName, target.canonicalName)
                    // 統合元を削除
                    database.productMasterDao().deleteById(src.id)
                    loadProducts()
                }
                showMergeDialog = false
                selectedProduct = null
            },
            onDismiss = {
                showMergeDialog = false
                selectedProduct = null
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
    onDelete: () -> Unit,
    onMerge: () -> Unit
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

            // カテゴリ & 使用頻度 & 確定バッジ
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
                if (product.isCertified) {
                    Text(
                        text = "確定",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1565C0),
                        modifier = Modifier
                            .background(Color(0xFFE3F2FD), MaterialTheme.shapes.small)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
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

        IconButton(onClick = onMerge) {
            Icon(
                Icons.Default.MergeType,
                contentDescription = "統合",
                tint = MaterialTheme.colorScheme.secondary
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

    val fwCount = fullWidthCount(name)
    val fwMax = 20.0
    val counterColor = when {
        fwCount >= fwMax -> MaterialTheme.colorScheme.error
        fwCount >= fwMax * 0.9 -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 商品名（全角20文字制限・数字/スペースは自動全角変換）
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { newVal ->
                            val converted = toFullWidthProductName(newVal)
                            if (fullWidthCount(converted) <= fwMax) name = converted
                        },
                        label = { Text("商品名（全角20文字）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        supportingText = {
                            Text(
                                text = "${"%.1f".format(fwCount)} / ${"%.0f".format(fwMax)} 文字",
                                color = counterColor,
                                fontSize = 11.sp
                            )
                        }
                    )
                }

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
                            kaikakeTekiyouId = selectedTekiyouId,
                            isCertified = true  // 手動入力・訂正は常に確定
                        )
                        onSave(newProduct)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("確定保存")
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

/**
 * 商品統合ダイアログ
 * 統合元の OcrVariant を統合先に移行し、統合元を削除する
 */
@Composable
private fun ProductMergeDialog(
    source: ProductMaster,
    allProducts: List<ProductMaster>,
    onConfirm: (target: ProductMaster) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTarget by remember { mutableStateOf<ProductMaster?>(null) }

    val candidates = remember(searchQuery, allProducts, source) {
        allProducts
            .filter { it.id != source.id }
            .filter { p ->
                searchQuery.isEmpty() ||
                p.canonicalName.contains(searchQuery, ignoreCase = true)
            }
            .sortedBy { it.canonicalName }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("商品統合", fontWeight = FontWeight.Bold)
                Text(
                    text = "「${source.canonicalName}」を統合",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "統合先を選択してください。\nOCR学習データと使用回数が統合先に引き継がれ、この商品は削除されます。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(candidates, key = { it.id }) { product ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTarget = product }
                                .background(
                                    if (selectedTarget?.id == product.id)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTarget?.id == product.id,
                                onClick = { selectedTarget = product }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(product.canonicalName, fontSize = 14.sp)
                                Text(
                                    text = "${product.category} / 使用${product.frequencyCount}回",
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
            TextButton(
                onClick = { selectedTarget?.let { onConfirm(it) } },
                enabled = selectedTarget != null
            ) {
                Text("統合する", color = if (selectedTarget != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/** スペース正規化（半角・全角スペースを1つの半角スペースに統一し前後をトリム） */
private fun String.normalizeSpaces() = trim().replace(Regex("[\\s\u3000]+"), " ")

/**
 * 全角換算文字数を計算する。
 * 全角（漢字・かな・全角記号）= 1.0、半角（ASCII・半角カナ等）= 0.5
 */
private fun fullWidthCount(text: String): Double =
    text.sumOf { c ->
        when {
            c.code in 0x3000..0x9FFF -> 1.0  // CJK全般（かな・漢字）
            c.code in 0xF900..0xFAFF -> 1.0  // CJK互換漢字
            c.code in 0xFF01..0xFF60 -> 1.0  // 全角英数記号
            c.code in 0xFFE0..0xFFE6 -> 1.0  // 全角通貨記号等
            else -> 0.5                        // 半角（ASCII・kg/cc/cm等）
        }
    }

/**
 * 商品名入力用の自動全角変換。
 * - 半角数字 → 全角数字
 * - 半角スペース → 全角スペース
 * - 半角英字・kg/cc/cm などの単位はそのまま（半角許容）
 */
private fun toFullWidthProductName(text: String): String =
    text.map { c ->
        when {
            c in '0'..'9' -> '０' + (c - '0')
            c == ' ' -> '\u3000'
            else -> c
        }
    }.joinToString("")
