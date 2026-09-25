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
import com.example.greenframeocr.data.AccountingSoftware
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.util.AoiroChoboAccountRules
import com.example.greenframeocr.util.AoiroChoboMemoRules
import com.example.greenframeocr.util.AoiroChoboPurchaseRules
import com.example.greenframeocr.util.GeminiApiException
import com.example.greenframeocr.util.GeminiApiKeyMissingException
import com.example.greenframeocr.util.GeminiQuotaExhaustedException
import com.example.greenframeocr.util.GeminiRateLimitException
import com.example.greenframeocr.util.GeminiReceiptClient
import com.example.greenframeocr.util.normalizeSpaces
import com.example.greenframeocr.util.withComputedKey
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
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val accountingSoftware = appPreferences.accountingSoftware
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // State
    var allProducts by remember { mutableStateOf<List<ProductMaster>>(emptyList()) }
    var displayProducts by remember { mutableStateOf<List<ProductMaster>>(emptyList()) }
    var kaikakeTekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var yayoiAccountList by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var yayoiFlaggedList by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    // あおいろ帳簿（BLUE_RETURN_PREP）モードの科目・摘要。PC から取り込んだミラー
    var aoiroAccounts by remember { mutableStateOf<List<AoiroChoboAccount>>(emptyList()) }
    var aoiroMemos by remember { mutableStateOf<List<AoiroChoboMemoTemplate>>(emptyList()) }
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
    var showAiMatchingDialog by remember { mutableStateOf(false) }
    var aiSuggestions by remember { mutableStateOf<List<GeminiReceiptClient.AccountMatchSuggestion>>(emptyList()) }
    var aoiroSuggestions by remember { mutableStateOf<List<GeminiReceiptClient.AoiroAccountMatchSuggestion>>(emptyList()) }
    var aiUsageStats by remember { mutableStateOf<GeminiReceiptClient.AiUsageStats?>(null) }
    var isAiMatching by remember { mutableStateOf(false) }
    var aiMatchingError by remember { mutableStateOf<String?>(null) }
    var isRecalculating by remember { mutableStateOf(false) }
    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }
    var filterPanelExpanded by remember { mutableStateOf(false) }

    val categories = listOf("一般購買", "給油所", "農業機械")

    // 初期データ読み込み
    LaunchedEffect(Unit) {
        kaikakeTekiyouList = database.rakurakuTekiyouDao().getEnabledByCategory("買掛", "購入")
        val allYayoi = database.yayoiAccountDao().getAll().filter { it.isEnabled }
        yayoiAccountList = allYayoi
        yayoiFlaggedList = allYayoi.filter { it.usedForPurchase }
        aoiroAccounts = database.aoiroChoboVocabDao().getAllAccounts()
        aoiroMemos = database.aoiroChoboVocabDao().getAllMemoTemplates()
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

        // 摘要フィルタ（連携ソフトに応じてフィールドを切り替え）
        filtered = when (tekiyouFilter) {
            TekiyouFilter.ALL -> filtered
            TekiyouFilter.MISSING -> when (accountingSoftware) {
                AccountingSoftware.YAYOI -> filtered.filter { it.yayoiAccountId == null }
                AccountingSoftware.BLUE_RETURN_PREP -> filtered.filter { it.accountKey == null }
                else -> filtered.filter { it.kaikakeTekiyouId == null }
            }
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
            yayoiAccountList = database.yayoiAccountDao().getAll().filter { it.isEnabled }
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
                    ).withComputedKey()
                    database.productMasterDao().insertIgnore(product)
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

    // 商品統合の実処理（手動統合・類似グループ一括統合の両方から呼ばれる）
    suspend fun mergeProduct(src: ProductMaster, target: ProductMaster) {
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
                        source = VariantSource.CAPTURE.name,
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
    }

    // AI科目提案（あおいろ帳簿）。弥生版と同じ流れで、提案を承認したものだけ保存する
    fun startAoiroAiMatching() {
        val unmatched = allProducts.filter { it.accountKey == null }
        if (unmatched.isEmpty()) {
            aiMatchingError = "未マッチングの品目がありません"
            return
        }
        val accounts = AoiroChoboPurchaseRules.accountCandidates(aoiroAccounts)
        if (accounts.isEmpty()) {
            aiMatchingError = "あおいろ帳簿の科目がまだ取り込まれていません。設定画面から取り込んでください"
            return
        }
        val memoNames = accounts.associate { a ->
            a.accountKey to AoiroChoboPurchaseRules.memoCandidates(a.accountKey, aoiroMemos).map { it.name }
        }
        isAiMatching = true
        aiMatchingError = null
        scope.launch {
            try {
                val result = GeminiReceiptClient.matchProductsToAoiroAccounts(
                    productNames = unmatched.map { it.canonicalName to it.category },
                    accounts = accounts,
                    memoNamesByAccount = memoNames,
                    apiKey = appPreferences.geminiApiKey
                )
                aoiroSuggestions = result.suggestions
                aiUsageStats = result.usageStats
                result.usageStats?.let { appPreferences.addTokenUsage(it.promptTokens, it.candidatesTokens, it.totalTokens) }
                showAiMatchingDialog = true
            } catch (e: GeminiApiKeyMissingException) {
                aiMatchingError = e.message
            } catch (e: GeminiQuotaExhaustedException) {
                aiMatchingError = e.message
            } catch (e: GeminiRateLimitException) {
                aiMatchingError = e.message
            } catch (e: GeminiApiException) {
                aiMatchingError = e.message
            } catch (e: Exception) {
                aiMatchingError = "エラー: ${e.message}"
            } finally {
                isAiMatching = false
            }
        }
    }

    // AI科目提案
    fun startAiMatching() {
        val unmatched = allProducts.filter { it.yayoiAccountId == null }
        if (unmatched.isEmpty()) {
            aiMatchingError = "未マッチングの品目がありません"
            return
        }
        val accounts = if (yayoiFlaggedList.isNotEmpty()) yayoiFlaggedList else yayoiAccountList
        if (accounts.isEmpty()) {
            aiMatchingError = "弥生勘定科目が登録されていません"
            return
        }
        isAiMatching = true
        aiMatchingError = null
        scope.launch {
            try {
                val result = GeminiReceiptClient.matchProductsToAccounts(
                    productNames = unmatched.map { it.canonicalName to it.category },
                    accounts = accounts,
                    apiKey = appPreferences.geminiApiKey
                )
                aiSuggestions = result.suggestions
                aiUsageStats = result.usageStats
                result.usageStats?.let { appPreferences.addTokenUsage(it.promptTokens, it.candidatesTokens, it.totalTokens) }
                showAiMatchingDialog = true
            } catch (e: GeminiApiKeyMissingException) {
                aiMatchingError = e.message
            } catch (e: GeminiQuotaExhaustedException) {
                aiMatchingError = e.message
            } catch (e: GeminiRateLimitException) {
                aiMatchingError = e.message
            } catch (e: GeminiApiException) {
                aiMatchingError = e.message
            } catch (e: Exception) {
                aiMatchingError = "エラー: ${e.message}"
            } finally {
                isAiMatching = false
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "追加")
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

            // 統計カード
            val matchedProductCount = when (accountingSoftware) {
                AccountingSoftware.YAYOI -> allProducts.count { it.yayoiAccountId != null }
                AccountingSoftware.BLUE_RETURN_PREP -> allProducts.count { it.accountKey != null }
                else -> allProducts.count { it.kaikakeTekiyouId != null }
            }
            val unmatchedProductCount = allProducts.size - matchedProductCount
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("品目数", fontSize = 12.sp)
                        Text("${allProducts.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("マッチ済", fontSize = 12.sp)
                        Text(
                            "$matchedProductCount",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("未マッチ", fontSize = 12.sp)
                        Text(
                            "$unmatchedProductCount",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (unmatchedProductCount > 0) MaterialTheme.colorScheme.error else Color.Gray
                        )
                    }
                }
            }

            // AI科目提案ボタン（弥生・あおいろ。常時表示で見落としを防ぐ）
            if (accountingSoftware == AccountingSoftware.YAYOI || accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP) {
                val isAoiro = accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP
                val unmatchedForAi = allProducts.count { if (isAoiro) it.accountKey == null else it.yayoiAccountId == null }
                AiSuggestButton(
                    label = if (unmatchedForAi > 0) "未マッチ${unmatchedForAi}件をAIで一括提案" else "AI科目提案",
                    isLoading = isAiMatching,
                    onClick = { if (isAoiro) startAoiroAiMatching() else startAiMatching() }
                )
            }

            CollapsibleFilterPanel(
                expanded = filterPanelExpanded,
                onExpandedChange = { filterPanelExpanded = it },
                hasActiveFilter = searchQuery.isNotBlank() || selectedCategory != null ||
                    sortOrder != SortOrder.FREQUENCY || tekiyouFilter != TekiyouFilter.ALL,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 検索バー
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
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

                Spacer(Modifier.height(4.dp))

                // カテゴリフィルタ
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
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
                        .padding(horizontal = 8.dp, vertical = 4.dp),
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
                        val chipLabel = if (filter == TekiyouFilter.MISSING && accountingSoftware != AccountingSoftware.RAKURAKU)
                            "科目未設定" else filter.label
                        FilterChip(
                            selected = tekiyouFilter == filter,
                            onClick = { tekiyouFilter = filter },
                            label = { Text(chipLabel, fontSize = 12.sp) }
                        )
                    }
                }

                // 件数表示
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    ListCountText(displayProducts.size)
                }
            }

            Divider()

            // 商品リスト
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(displayProducts, key = { it.id }) { product ->
                    val matchLabel = when (accountingSoftware) {
                        AccountingSoftware.YAYOI ->
                            yayoiAccountList.find { it.id == product.yayoiAccountId }
                                ?.let { acc ->
                                    if (acc.accountCode.isNullOrEmpty()) acc.accountName
                                    else "${acc.accountName}（${acc.accountCode}）"
                                }
                        AccountingSoftware.BLUE_RETURN_PREP -> aoiroLabel(product, aoiroAccounts, aoiroMemos)
                        else -> kaikakeTekiyouList.find { it.id == product.kaikakeTekiyouId }?.tekiyouName
                    }
                    ProductListItem(
                        product = product,
                        matchLabel = matchLabel,
                        accountingSoftware = accountingSoftware,
                        fontSize = listFontSize,
                        onClick = {
                            selectedProduct = product
                            showEditDialog = true
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
            accountingSoftware = accountingSoftware,
            kaikakeTekiyouList = kaikakeTekiyouList,
            yayoiAccountList = yayoiAccountList,
            yayoiFlaggedList = yayoiFlaggedList,
            aoiroAccounts = aoiroAccounts,
            aoiroMemos = aoiroMemos,
            categories = categories,
            onDismiss = {
                showEditDialog = false
                selectedProduct = null
            },
            onSave = { updatedProduct ->
                val oldName = selectedProduct?.canonicalName
                scope.launch {
                    database.productMasterDao().update(updatedProduct)
                    if (oldName != null && oldName != updatedProduct.canonicalName) {
                        database.receiptDao().updateProductNameInReceiptItems(oldName, updatedProduct.canonicalName)
                    }
                    loadProducts()
                }
                showEditDialog = false
                selectedProduct = null
            },
            onDelete = {
                // 編集ダイアログを閉じて削除確認ダイアログへ
                showEditDialog = false
                showDeleteDialog = true
            }
        )
    }

    // 追加ダイアログ
    if (showAddDialog) {
        ProductEditDialog(
            product = null,
            accountingSoftware = accountingSoftware,
            kaikakeTekiyouList = kaikakeTekiyouList,
            yayoiAccountList = yayoiAccountList,
            yayoiFlaggedList = yayoiFlaggedList,
            aoiroAccounts = aoiroAccounts,
            aoiroMemos = aoiroMemos,
            categories = categories,
            onDismiss = { showAddDialog = false },
            onSave = { newProduct ->
                scope.launch {
                    database.productMasterDao().insertIgnore(newProduct.withComputedKey())
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
                        // id は起動前に取る。直後に selectedProduct = null にするので、
                        // コルーチンの中で読むと null になって落ちる（2026-09-25 実機で発覚）
                        val productId = selectedProduct!!.id
                        scope.launch {
                            // OCR学習データも一緒に削除（CASCADE）
                            database.ocrVariantDao().deleteByProductId(productId)
                            database.productMasterDao().deleteById(productId)
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
                    mergeProduct(src, target)
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

    // AI提案エラーダイアログ
    if (aiMatchingError != null) {
        AlertDialog(
            onDismissRequest = { aiMatchingError = null },
            title = { Text("AI提案エラー") },
            text = { Text(aiMatchingError ?: "") },
            confirmButton = { TextButton(onClick = { aiMatchingError = null }) { Text("OK") } }
        )
    }

    // AI提案ダイアログ（あおいろ）。承認した科目に、自動で埋めてよい摘要があれば一緒に保存する
    if (showAiMatchingDialog && accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP) {
        val rows = remember(aoiroSuggestions, allProducts, aoiroAccounts, aoiroMemos) {
            val productByName = allProducts.associateBy { it.canonicalName }
            val accountByKey = aoiroAccounts.associateBy { it.accountKey }
            aoiroSuggestions.mapNotNull { s ->
                val product = productByName[s.productName] ?: return@mapNotNull null
                val account = accountByKey[s.accountKey] ?: return@mapNotNull null
                val memo = AoiroChoboPurchaseRules.preselectedMemo(account.accountKey, aoiroMemos)
                AiSuggestionRow(
                    productId = product.id,
                    productName = product.canonicalName,
                    key = account.accountKey,
                    label = memo?.let { "${account.name} ／ ${it.name}" } ?: account.name,
                    reason = s.reason
                )
            }
        }
        AiMatchingDialog(
            rows = rows,
            usageStats = aiUsageStats,
            onDismiss = { showAiMatchingDialog = false },
            onSave = { acceptedMap ->
                scope.launch {
                    acceptedMap.forEach { (productId, accountKey) ->
                        val product = allProducts.find { it.id == productId } ?: return@forEach
                        val account = aoiroAccounts.find { it.accountKey == accountKey } ?: return@forEach
                        val memo = AoiroChoboPurchaseRules.preselectedMemo(accountKey, aoiroMemos)
                        database.productMasterDao().update(
                            product.copy(
                                accountKey = accountKey,
                                accountKeyName = account.name,
                                memoKey = memo?.memoKey,
                                memoKeyName = memo?.name
                            )
                        )
                    }
                    loadProducts()
                }
                showAiMatchingDialog = false
            }
        )
    }

    // AI提案ダイアログ（弥生）
    if (showAiMatchingDialog && accountingSoftware == AccountingSoftware.YAYOI) {
        val rows = remember(aiSuggestions, allProducts, yayoiAccountList) {
            val productByName = allProducts.associateBy { it.canonicalName }
            val accountMap = yayoiAccountList.associateBy { it.id }
            aiSuggestions.mapNotNull { s ->
                val product = productByName[s.productName] ?: return@mapNotNull null
                val account = accountMap[s.suggestedAccountId] ?: return@mapNotNull null
                AiSuggestionRow(
                    productId = product.id,
                    productName = s.productName,
                    key = s.suggestedAccountId,
                    label = account.accountCode?.takeIf { it.isNotEmpty() }
                        ?.let { "${account.accountName}（$it）" } ?: account.accountName,
                    reason = s.reason
                )
            }
        }
        AiMatchingDialog(
            rows = rows,
            usageStats = aiUsageStats,
            onDismiss = { showAiMatchingDialog = false },
            onSave = { acceptedMap ->
                scope.launch {
                    acceptedMap.forEach { (productId, accountId) ->
                        val product = allProducts.find { it.id == productId } ?: return@forEach
                        database.productMasterDao().update(product.copy(yayoiAccountId = accountId))
                    }
                    loadProducts()
                }
                showAiMatchingDialog = false
            }
        )
    }
}

/**
 * AI 提案 1 件。[key] は保存に使う科目の識別子（弥生は科目 id、あおいろは accountKey）、
 * [label] は「→」の右に出す科目の表示
 */
private data class AiSuggestionRow<K>(
    val productId: Long,
    val productName: String,
    val key: K,
    val label: String,
    val reason: String
)

/**
 * AI提案確認ダイアログ（弥生・あおいろ共通）
 * onSave: Map<productId, key> — 承認した提案のみ保存
 */
@Composable
private fun <K> AiMatchingDialog(
    rows: List<AiSuggestionRow<K>>,
    usageStats: GeminiReceiptClient.AiUsageStats?,
    onDismiss: () -> Unit,
    onSave: (Map<Long, K>) -> Unit
) {
    // 提案ごとに「承認するか」のチェック状態を管理
    data class SuggestionState(
        val row: AiSuggestionRow<K>,
        val accepted: Boolean = true
    ) {
        val productName get() = row.productName
        val reason get() = row.reason
    }

    val states = remember(rows) {
        rows.map { androidx.compose.runtime.mutableStateOf(SuggestionState(it)) }
    }

    val acceptedCount = states.count { it.value.accepted }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("AI 科目提案", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (states.isEmpty()) {
                    Text("提案なし", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${states.size}件の提案（${acceptedCount}件承認中）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { states.forEach { it.value = it.value.copy(accepted = true) } },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("全承認", fontSize = 12.sp) }
                            TextButton(
                                onClick = { states.forEach { it.value = it.value.copy(accepted = false) } },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("全解除", fontSize = 12.sp) }
                        }
                    }
                }
            }
        },
        text = {
            if (states.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "未マッチング品目に対する提案が見つかりませんでした。\n勘定科目リストを見直してください。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(states.size) { idx ->
                        val state by states[idx]
                        Surface(
                            color = if (state.accepted)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { states[idx].value = state.copy(accepted = !state.accepted) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Checkbox(
                                    checked = state.accepted,
                                    onCheckedChange = { states[idx].value = state.copy(accepted = it) },
                                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        state.productName,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            "→",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            state.row.label,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (state.reason.isNotEmpty()) {
                                        Text(
                                            state.reason,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (usageStats != null) {
                    Text(
                        text = usageStats.toDisplayString(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Row {
                    TextButton(onClick = onDismiss) { Text("キャンセル") }
                    TextButton(
                        onClick = {
                            val accepted = states
                                .filter { it.value.accepted }
                                .associate { it.value.row.productId to it.value.row.key }
                            onSave(accepted)
                        },
                        enabled = acceptedCount > 0
                    ) { Text("${acceptedCount}件を保存") }
                }
            }
        },
        dismissButton = null
    )
}

/**
 * あおいろモードの一覧に出す「科目 ／ 摘要」。科目が無ければ null（＝未設定として赤で出る）。
 *
 * 名前は今の辞書から引く（PC で改名されていればそちらを出す）。辞書から消えたキーは
 * 保存時の名前で出す。摘要は、選べる候補があるのに選んでいなければ「摘要未選択」と出す。
 */
private fun aoiroLabel(
    product: ProductMaster,
    accounts: List<AoiroChoboAccount>,
    memos: List<AoiroChoboMemoTemplate>
): String? {
    val accountKey = product.accountKey ?: return null
    val accountName = accounts.find { it.accountKey == accountKey }?.name ?: product.accountKeyName ?: accountKey
    val memoName = product.memoKey?.let { key -> memos.find { it.memoKey == key }?.name ?: product.memoKeyName ?: key }
    return when {
        memoName != null -> "$accountName ／ $memoName"
        AoiroChoboPurchaseRules.memoCandidates(accountKey, memos).isNotEmpty() -> "$accountName ／ 摘要未選択"
        else -> accountName
    }
}

/**
 * 商品リストアイテム
 */
@Composable
private fun ProductListItem(
    product: ProductMaster,
    matchLabel: String?,
    accountingSoftware: AccountingSoftware,
    fontSize: Float = AppPreferences.DEFAULT_LIST_FONT_SIZE,
    onClick: () -> Unit,
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
                fontSize = (fontSize + 1f).sp,
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
                    fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (product.isCertified) {
                    Text(
                        text = "確定",
                        fontSize = (fontSize - 4f).coerceAtLeast(10f).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1565C0),
                        modifier = Modifier
                            .background(Color(0xFFE3F2FD), MaterialTheme.shapes.small)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // マッチング科目/摘要
            val labelPrefix = when (accountingSoftware) {
                AccountingSoftware.YAYOI, AccountingSoftware.BLUE_RETURN_PREP -> "科目: "
                else -> "摘要: "
            }
            val labelText = matchLabel ?: "未設定"
            val isUnset = matchLabel == null
            Text(
                text = "$labelPrefix$labelText",
                fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
                color = when {
                    isUnset -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
            )
        }

        IconButton(onClick = onMerge) {
            Icon(
                Icons.Default.MergeType,
                contentDescription = "統合",
                tint = MaterialTheme.colorScheme.secondary
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
    accountingSoftware: AccountingSoftware,
    kaikakeTekiyouList: List<RakurakuTekiyou>,
    yayoiAccountList: List<YayoiAccount>,
    yayoiFlaggedList: List<YayoiAccount>,
    aoiroAccounts: List<AoiroChoboAccount>,
    aoiroMemos: List<AoiroChoboMemoTemplate>,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (ProductMaster) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(product?.canonicalName ?: "") }
    var category by remember { mutableStateOf(product?.category ?: categories.first()) }
    var selectedTekiyouId by remember { mutableStateOf(product?.kaikakeTekiyouId) }
    var selectedYayoiAccountId by remember { mutableStateOf(product?.yayoiAccountId) }
    var showTekiyouPicker by remember { mutableStateOf(false) }
    var showYayoiPicker by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var selectedAccountKey by remember { mutableStateOf(product?.accountKey) }
    var selectedMemoKey by remember { mutableStateOf(product?.memoKey) }
    var showAoiroAccountPicker by remember { mutableStateOf(false) }
    var showAoiroMemoPicker by remember { mutableStateOf(false) }
    val isAoiro = accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP

    val isNew = product == null
    val title = if (isNew) "購買品追加" else "購買品編集"

    val fwCount = fullWidthCount(name)
    val fwMax = 30.0
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
                // 商品名（全角30文字制限・数字/スペースは自動全角変換）
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { newVal ->
                            val converted = toFullWidthProductName(newVal)
                            if (fullWidthCount(converted) <= fwMax) name = converted
                        },
                        label = { Text("商品名（全角30文字）") },
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

                // 買掛摘要 / 弥生勘定科目 / BRP
                when (accountingSoftware) {
                    AccountingSoftware.BLUE_RETURN_PREP -> {
                        AoiroAccountAndMemoFields(
                            accounts = aoiroAccounts,
                            memos = aoiroMemos,
                            accountKey = selectedAccountKey,
                            memoKey = selectedMemoKey,
                            fallbackAccountName = product?.accountKeyName,
                            fallbackMemoName = product?.memoKeyName,
                            onPickAccount = { showAoiroAccountPicker = true },
                            onPickMemo = { showAoiroMemoPicker = true },
                            onClearAccount = { selectedAccountKey = null; selectedMemoKey = null },
                            onClearMemo = { selectedMemoKey = null }
                        )
                    }
                    AccountingSoftware.YAYOI -> {
                        val yayoiLabel = yayoiAccountList.find { it.id == selectedYayoiAccountId }
                            ?.let { acc ->
                                if (acc.accountCode.isNullOrEmpty()) acc.accountName
                                else "${acc.accountName}（${acc.accountCode}）"
                            } ?: "未設定"
                        OutlinedTextField(
                            value = yayoiLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("弥生勘定科目") },
                            trailingIcon = {
                                Row {
                                    if (selectedYayoiAccountId != null) {
                                        IconButton(onClick = { selectedYayoiAccountId = null }) {
                                            Icon(Icons.Default.Clear, "クリア")
                                        }
                                    }
                                    IconButton(onClick = { showYayoiPicker = true }) {
                                        Icon(Icons.Default.ArrowDropDown, "選択")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { showYayoiPicker = true }
                        )
                    }
                    else -> {
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
                            modifier = Modifier.fillMaxWidth().clickable { showTekiyouPicker = true }
                        )
                    }
                }
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
                            kaikakeTekiyouId = if (accountingSoftware == AccountingSoftware.YAYOI) product?.kaikakeTekiyouId else selectedTekiyouId,
                            yayoiAccountId = if (accountingSoftware == AccountingSoftware.YAYOI) selectedYayoiAccountId else product?.yayoiAccountId,
                            isCertified = true,
                            // フィールド列挙で組み直しているので、あおいろ側は明示的に書かないと
                            // 商品名を直しただけで消える。あおいろモード以外では元の値を引き継ぐ。
                            // 名前はそのとき見えていた PC 側の名前（取込時に変わっていたら紐付けを外す合図）
                            accountKey = if (isAoiro) selectedAccountKey else product?.accountKey,
                            accountKeyName = if (isAoiro) {
                                selectedAccountKey?.let { key ->
                                    aoiroAccounts.find { it.accountKey == key }?.name ?: product?.accountKeyName
                                }
                            } else product?.accountKeyName,
                            memoKey = if (isAoiro) selectedMemoKey else product?.memoKey,
                            memoKeyName = if (isAoiro) {
                                selectedMemoKey?.let { key ->
                                    aoiroMemos.find { it.memoKey == key }?.name ?: product?.memoKeyName
                                }
                            } else product?.memoKeyName
                        ).withComputedKey()
                        onSave(newProduct)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("確定保存")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("削除") }
                }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        }
    )

    // 弥生勘定科目選択ダイアログ
    if (showYayoiPicker) {
        YayoiAccountPickerDialog(
            productName = name,
            accountList = yayoiAccountList,
            flaggedList = yayoiFlaggedList,  // ProductEditDialog のパラメータ
            selectedId = selectedYayoiAccountId,
            onSelect = { id ->
                selectedYayoiAccountId = id
                showYayoiPicker = false
            },
            onDismiss = { showYayoiPicker = false }
        )
    }

    // あおいろ科目の選択。選び直したら摘要は作り直す（摘要は科目に属する）
    if (showAoiroAccountPicker) {
        AoiroAccountPickerDialog(
            productName = name,
            accounts = AoiroChoboPurchaseRules.accountCandidates(aoiroAccounts),
            memos = aoiroMemos,
            selectedKey = selectedAccountKey,
            onSelect = { key ->
                if (key != selectedAccountKey) {
                    selectedAccountKey = key
                    selectedMemoKey = AoiroChoboPurchaseRules.preselectedMemo(key, aoiroMemos)?.memoKey
                }
                showAoiroAccountPicker = false
            },
            onDismiss = { showAoiroAccountPicker = false }
        )
    }

    if (showAoiroMemoPicker && selectedAccountKey != null) {
        AoiroMemoPickerDialog(
            productName = name,
            memos = AoiroChoboPurchaseRules.memoCandidates(selectedAccountKey!!, aoiroMemos),
            ratioSensitive = remember(aoiroMemos) { AoiroChoboMemoRules.ratioSensitiveMemoKeys(aoiroMemos) },
            selectedKey = selectedMemoKey,
            onSelect = { key ->
                selectedMemoKey = key
                showAoiroMemoPicker = false
            },
            onDismiss = { showAoiroMemoPicker = false }
        )
    }

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
 * あおいろモードの「科目 → 摘要」欄。
 *
 * 摘要は科目を選ぶまで出さない。科目に買掛/仕入の摘要が無ければ、摘要なしで保存してよいことを伝える
 * （PC は UnmatchedMemo として受け、PC 側で摘要を決める）。
 */
@Composable
private fun AoiroAccountAndMemoFields(
    accounts: List<AoiroChoboAccount>,
    memos: List<AoiroChoboMemoTemplate>,
    accountKey: String?,
    memoKey: String?,
    fallbackAccountName: String?,
    fallbackMemoName: String?,
    onPickAccount: () -> Unit,
    onPickMemo: () -> Unit,
    onClearAccount: () -> Unit,
    onClearMemo: () -> Unit
) {
    if (accounts.isEmpty()) {
        Text(
            "あおいろ帳簿の科目・摘要がまだ取り込まれていません。設定画面の「AoiroChobo 科目・摘要を取り込む」から取り込んでください。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .padding(12.dp)
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val accountName = accountKey?.let { key ->
            accounts.find { it.accountKey == key }?.name ?: fallbackAccountName ?: key
        }
        PickerField(
            label = "あおいろ科目",
            value = accountName ?: "未設定",
            hasValue = accountKey != null,
            onPick = onPickAccount,
            onClear = onClearAccount
        )

        if (accountKey != null) {
            val candidates = AoiroChoboPurchaseRules.memoCandidates(accountKey, memos)
            if (candidates.isEmpty() && memoKey == null) {
                Text(
                    "この科目には買掛/仕入の摘要がありません。摘要なしで PC に送り、PC 側で摘要を決めます。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val memo = memoKey?.let { key -> memos.find { it.memoKey == key } }
                PickerField(
                    label = "あおいろ摘要",
                    value = memo?.name ?: memoKey?.let { fallbackMemoName ?: it } ?: "未選択",
                    hasValue = memoKey != null,
                    onPick = onPickMemo,
                    onClear = onClearMemo
                )
                memo?.let {
                    Text(
                        memoDetail(it),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerField(
    label: String,
    value: String,
    hasValue: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            Row {
                if (hasValue) {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "クリア") }
                }
                IconButton(onClick = onPick) { Icon(Icons.Default.ArrowDropDown, "選択") }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick)
    )
}

/** 摘要が仕訳に持ち込む値（税率・事業割合）。PC は摘要側の事業割合を使う */
private fun memoDetail(memo: AoiroChoboMemoTemplate): String = buildList {
    memo.taxRate?.let { add("税率 " + (AoiroChoboAccountRules.taxRateLabel(it) ?: it)) }
    memo.businessRatio?.let { add("事業割合 $it%") }
}.joinToString("・")

/**
 * あおいろ科目の選択。借方に使ってよい科目（契約 §4.5）だけを枠番号順に出す。
 * 科目ごとに買掛/仕入の摘要の件数を添える（0 件なら摘要なしで送ることになる）。
 */
@Composable
private fun AoiroAccountPickerDialog(
    productName: String,
    accounts: List<AoiroChoboAccount>,
    memos: List<AoiroChoboMemoTemplate>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(productName.ifEmpty { "新規商品" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("あおいろ科目を選択", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                items(accounts, key = { it.accountKey }) { account ->
                    val memoCount = AoiroChoboPurchaseRules.memoCandidates(account.accountKey, memos).size
                    PickerRow(
                        selected = account.accountKey == selectedKey,
                        title = account.name,
                        subtitle = listOfNotNull(
                            account.displayGroup,
                            if (memoCount == 0) "摘要なし" else "摘要 $memoCount 件"
                        ).joinToString("・"),
                        onClick = { onSelect(account.accountKey) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/**
 * あおいろ摘要の選択。選んだ科目の買掛/仕入の摘要だけ。先頭に「摘要なし」を置く。
 * 事業割合だけ違う組の摘要には「要確定」を付ける（帳簿の金額が変わるので、ここで確定したものだけが使われる）。
 */
@Composable
private fun AoiroMemoPickerDialog(
    productName: String,
    memos: List<AoiroChoboMemoTemplate>,
    ratioSensitive: Set<String>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(productName.ifEmpty { "新規商品" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("あおいろ摘要を選択", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                item(key = "none") {
                    PickerRow(
                        selected = selectedKey == null,
                        title = "摘要なし",
                        subtitle = "PC 側で摘要を決める",
                        onClick = { onSelect(null) }
                    )
                }
                items(memos, key = { it.memoKey }) { memo ->
                    PickerRow(
                        selected = memo.memoKey == selectedKey,
                        title = memo.name,
                        subtitle = memoDetail(memo),
                        tag = if (memo.memoKey in ratioSensitive) "要確定" else null,
                        onClick = { onSelect(memo.memoKey) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun PickerRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    tag: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        tag?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = Color(0xFF8A4B00),
                modifier = Modifier
                    .background(Color(0xFFFFE0B2), MaterialTheme.shapes.small)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
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
 * 弥生勘定科目選択ダイアログ
 * flaggedList: usedForPurchase=true の科目。空の場合は allAccounts + categoryAフィルターにフォールバック。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YayoiAccountPickerDialog(
    productName: String,
    accountList: List<YayoiAccount>,
    flaggedList: List<YayoiAccount>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val hasFlagged = flaggedList.isNotEmpty()
    var showAll by remember { mutableStateOf(!hasFlagged) }
    var searchQuery by remember { mutableStateOf("") }
    // フォールバック時のみ区分Aフィルターを使用
    var selectedCategoryA by remember { mutableStateOf(if (hasFlagged) null else "経費") }

    val categoryAList = remember(accountList) {
        sortYayoiCategoryA(accountList.map { it.categoryA }.filter { it.isNotBlank() })
    }

    // 表示リスト: フラグ優先 or 全件
    val baseList = if (showAll) accountList else flaggedList

    val filtered = remember(baseList, selectedCategoryA, searchQuery, showAll) {
        baseList.filter { acc ->
            (showAll.not() || selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchQuery.isEmpty() ||
             acc.accountName.contains(searchQuery, ignoreCase = true) ||
             (acc.accountCode?.contains(searchQuery, ignoreCase = true) == true) ||
             acc.searchKeyAlpha.contains(searchQuery, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(productName.ifEmpty { "新規商品" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("弥生勘定科目を選択", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (hasFlagged) {
                        Surface(
                            color = if (!showAll) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = if (!showAll) "購買フラグ (${flaggedList.size}件)" else "全科目",
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp)) {
                // フラグ/全科目トグル（フラグが存在する場合のみ）
                if (hasFlagged) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showAll,
                            onClick = { showAll = false; selectedCategoryA = null },
                            label = { Text("購買フラグのみ", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = showAll,
                            onClick = { showAll = true; selectedCategoryA = "経費" },
                            label = { Text("全科目", fontSize = 12.sp) }
                        )
                    }
                }

                // 全科目表示時のみ区分Aフィルターを表示
                if (showAll) {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
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
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("検索（科目名・コード）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )

                Text(
                    text = "${filtered.size}件",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                )

                LazyColumn {
                    items(filtered, key = { it.id }) { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(account.id) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedId == account.id, onClick = { onSelect(account.id) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.accountName, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    account.accountCode?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                                    Text("${account.categoryA} / ${account.categoryB}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (account.searchKeyAlpha.isNotEmpty()) {
                                Text(account.searchKeyAlpha, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        dismissButton = { TextButton(onClick = { onSelect(null) }) { Text("クリア") } }
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
