package com.example.greenframeocr.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.RakurakuTekiyou
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.util.importTekiyouFromCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * メインカテゴリの定義
 */
private enum class MainCategory(val displayName: String) {
    CASH("現金"),
    DEPOSIT("預金"),
    RECEIVABLE("売掛"),
    PAYABLE("買掛")
}

/**
 * サブカテゴリのマッピング
 */
private val subCategoryMap = mapOf(
    MainCategory.CASH to listOf("入金" to "入金", "出金" to "出金"),
    MainCategory.DEPOSIT to listOf("入金" to "入金", "出金" to "出金"),
    MainCategory.RECEIVABLE to listOf("販売" to "販売", "入金" to "入金"),
    MainCategory.PAYABLE to listOf("購入" to "購入", "出金" to "出金")
)

/**
 * らくらく青色申告 摘要辞書画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RakurakuTekiyouScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // State
    var selectedMainCategory by remember { mutableStateOf(MainCategory.CASH) }
    var selectedSubCategory by remember { mutableStateOf("入金") }
    var tekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Edit/Add dialogs
    var showEditDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedTekiyou by remember { mutableStateOf<RakurakuTekiyou?>(null) }

    // サブカテゴリリスト
    val subCategories = subCategoryMap[selectedMainCategory] ?: emptyList()

    // メインカテゴリ変更時にサブカテゴリをリセット
    LaunchedEffect(selectedMainCategory) {
        selectedSubCategory = subCategories.firstOrNull()?.second ?: ""
    }

    // データ読み込み
    fun loadData() {
        scope.launch {
            isLoading = true
            tekiyouList = database.rakurakuTekiyouDao().getByCategory(
                selectedMainCategory.displayName,
                selectedSubCategory
            )
            isLoading = false
        }
    }

    // 初期データのインポート（CSVに新規追加された項目のみ挿入）
    fun importFromCsv() {
        scope.launch {
            importTekiyouFromCsv(context, database)
            loadData()
        }
    }

    LaunchedEffect(Unit) {
        importFromCsv()
    }

    LaunchedEffect(selectedMainCategory, selectedSubCategory) {
        loadData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("摘要辞書") },
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
            // メインカテゴリ選択
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MainCategory.entries.forEach { category ->
                    FilterChip(
                        selected = selectedMainCategory == category,
                        onClick = { selectedMainCategory = category },
                        label = {
                            Text(
                                text = category.displayName,
                                fontWeight = if (selectedMainCategory == category) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // サブカテゴリ選択
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                subCategories.forEach { (display, value) ->
                    FilterChip(
                        selected = selectedSubCategory == value,
                        onClick = { selectedSubCategory = value },
                        label = {
                            Text(
                                text = display,
                                fontWeight = if (selectedSubCategory == value) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                // 空白を埋める（2つのボタンのみの場合）
                if (subCategories.size < 4) {
                    repeat(4 - subCategories.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 件数表示
            Text(
                text = "${tekiyouList.size}件",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()

            // データグリッド
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                TekiyouGrid(
                    tekiyouList = tekiyouList,
                    showSharedColumn = selectedMainCategory == MainCategory.CASH || selectedMainCategory == MainCategory.DEPOSIT,
                    onEdit = { tekiyou ->
                        selectedTekiyou = tekiyou
                        showEditDialog = true
                    },
                    onDelete = { tekiyou ->
                        selectedTekiyou = tekiyou
                        showDeleteDialog = true
                    }
                )
            }
        }
    }

    // 編集ダイアログ
    if (showEditDialog && selectedTekiyou != null) {
        TekiyouEditDialog(
            title = "摘要編集",
            tekiyou = selectedTekiyou,
            showSharedField = selectedMainCategory == MainCategory.CASH || selectedMainCategory == MainCategory.DEPOSIT,
            onDismiss = {
                showEditDialog = false
                selectedTekiyou = null
            },
            onSave = { updated ->
                scope.launch {
                    database.rakurakuTekiyouDao().update(updated)
                    loadData()
                }
                showEditDialog = false
                selectedTekiyou = null
            }
        )
    }

    // 追加ダイアログ
    if (showAddDialog) {
        TekiyouEditDialog(
            title = "摘要追加",
            tekiyou = RakurakuTekiyou(
                mainCategory = selectedMainCategory.displayName,
                subCategory = selectedSubCategory,
                tekiyouName = "",
                searchKey = "",
                kamoku = "",
                taxRate = "",
                businessRatio = null,
                isShared = if (selectedMainCategory == MainCategory.CASH || selectedMainCategory == MainCategory.DEPOSIT) true else null
            ),
            showSharedField = selectedMainCategory == MainCategory.CASH || selectedMainCategory == MainCategory.DEPOSIT,
            onDismiss = { showAddDialog = false },
            onSave = { newTekiyou ->
                scope.launch {
                    database.rakurakuTekiyouDao().insert(newTekiyou)
                    loadData()
                }
                showAddDialog = false
            }
        )
    }

    // 削除確認ダイアログ
    if (showDeleteDialog && selectedTekiyou != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                selectedTekiyou = null
            },
            title = { Text("削除確認") },
            text = { Text("「${selectedTekiyou?.tekiyouName}」を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            selectedTekiyou?.let {
                                database.rakurakuTekiyouDao().delete(it)
                            }
                            loadData()
                        }
                        showDeleteDialog = false
                        selectedTekiyou = null
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
                    selectedTekiyou = null
                }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

/**
 * 摘要グリッド表示
 */
@Composable
private fun TekiyouGrid(
    tekiyouList: List<RakurakuTekiyou>,
    showSharedColumn: Boolean,
    onEdit: (RakurakuTekiyou) -> Unit,
    onDelete: (RakurakuTekiyou) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(scrollState)
    ) {
        // ヘッダー行
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp)
        ) {
            HeaderCell("摘要名", 180.dp)
            HeaderCell("検索文字", 100.dp)
            HeaderCell("科目", 180.dp)
            HeaderCell("税率", 60.dp)
            HeaderCell("事業割合", 80.dp)
            if (showSharedColumn) {
                HeaderCell("共有", 60.dp)
            }
            HeaderCell("操作", 80.dp)
        }

        Divider()

        // データ行
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(tekiyouList, key = { _, item -> item.id }) { index, tekiyou ->
                Row(
                    modifier = Modifier
                        .background(
                            if (index % 2 == 0) Color.Transparent
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                        .clickable { onEdit(tekiyou) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DataCell(tekiyou.tekiyouName, 180.dp)
                    DataCell(tekiyou.searchKey, 100.dp)
                    DataCell(tekiyou.kamoku, 180.dp)
                    DataCell(tekiyou.taxRate, 60.dp, TextAlign.Center)
                    DataCell(
                        tekiyou.businessRatio?.let { "$it%" } ?: "",
                        80.dp,
                        TextAlign.Center
                    )
                    if (showSharedColumn) {
                        DataCell(
                            if (tekiyou.isShared == true) "○" else "",
                            60.dp,
                            TextAlign.Center
                        )
                    }
                    // 操作ボタン
                    Row(
                        modifier = Modifier.width(80.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(
                            onClick = { onEdit(tekiyou) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "編集",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDelete(tekiyou) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "削除",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Divider()
            }
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    textAlign: TextAlign = TextAlign.Start
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp),
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        textAlign = textAlign
    )
}

@Composable
private fun DataCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    textAlign: TextAlign = TextAlign.Start
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp),
        fontSize = 14.sp,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 摘要編集ダイアログ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TekiyouEditDialog(
    title: String,
    tekiyou: RakurakuTekiyou?,
    showSharedField: Boolean,
    onDismiss: () -> Unit,
    onSave: (RakurakuTekiyou) -> Unit
) {
    var tekiyouName by remember { mutableStateOf(tekiyou?.tekiyouName ?: "") }
    var searchKey by remember { mutableStateOf(tekiyou?.searchKey ?: "") }
    var kamoku by remember { mutableStateOf(tekiyou?.kamoku ?: "") }
    var taxRate by remember { mutableStateOf(tekiyou?.taxRate ?: "") }
    var businessRatioText by remember { mutableStateOf(tekiyou?.businessRatio?.toString() ?: "") }
    var isShared by remember { mutableStateOf(tekiyou?.isShared ?: true) }

    var expandedTaxRate by remember { mutableStateOf(false) }
    val taxRateOptions = listOf("", "8%", "10%", "非", "不")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = tekiyouName,
                    onValueChange = { tekiyouName = it },
                    label = { Text("摘要名 *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    label = { Text("検索文字") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = kamoku,
                    onValueChange = { kamoku = it },
                    label = { Text("科目 *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 税率選択
                ExposedDropdownMenuBox(
                    expanded = expandedTaxRate,
                    onExpandedChange = { expandedTaxRate = it }
                ) {
                    OutlinedTextField(
                        value = taxRate,
                        onValueChange = { taxRate = it },
                        label = { Text("税率") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTaxRate) },
                        singleLine = true,
                        readOnly = true
                    )
                    ExposedDropdownMenu(
                        expanded = expandedTaxRate,
                        onDismissRequest = { expandedTaxRate = false }
                    ) {
                        taxRateOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option.isEmpty()) "(なし)" else option) },
                                onClick = {
                                    taxRate = option
                                    expandedTaxRate = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = businessRatioText,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                            val intValue = newValue.toIntOrNull()
                            if (intValue == null || intValue in 0..100) {
                                businessRatioText = newValue
                            }
                        }
                    },
                    label = { Text("事業割合 (%)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                if (showSharedField) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isShared,
                            onCheckedChange = { isShared = it }
                        )
                        Text("預金/現金と共有")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (tekiyouName.isNotBlank() && kamoku.isNotBlank()) {
                        val updated = RakurakuTekiyou(
                            id = tekiyou?.id ?: 0,
                            mainCategory = tekiyou?.mainCategory ?: "",
                            subCategory = tekiyou?.subCategory ?: "",
                            tekiyouName = tekiyouName.trim(),
                            searchKey = searchKey.trim(),
                            kamoku = kamoku.trim(),
                            taxRate = taxRate,
                            businessRatio = businessRatioText.toIntOrNull(),
                            isShared = if (showSharedField) isShared else null
                        )
                        onSave(updated)
                    }
                },
                enabled = tekiyouName.isNotBlank() && kamoku.isNotBlank()
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
}

