package com.example.greenframeocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.launch

private enum class MainCategory(val displayName: String) {
    CASH("現金"),
    DEPOSIT("預金"),
    RECEIVABLE("売掛"),
    PAYABLE("買掛")
}

private val subCategoryMap = mapOf(
    MainCategory.CASH       to listOf("入金" to "入金", "出金" to "出金"),
    MainCategory.DEPOSIT    to listOf("入金" to "入金", "出金" to "出金"),
    MainCategory.RECEIVABLE to listOf("販売" to "販売", "入金" to "入金"),
    MainCategory.PAYABLE    to listOf("購入" to "購入", "出金" to "出金")
)

// 検索文字列列を表示するための画面幅しきい値
private val SEARCH_KEY_MIN_WIDTH = 380

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RakurakuTekiyouScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedMainCategory by remember { mutableStateOf(MainCategory.CASH) }
    var selectedSubCategory  by remember { mutableStateOf("入金") }
    var tekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }

    var showEditDialog   by remember { mutableStateOf(false) }
    var showAddDialog    by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedTekiyou  by remember { mutableStateOf<RakurakuTekiyou?>(null) }

    val subCategories = subCategoryMap[selectedMainCategory] ?: emptyList()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val showSearchKey = screenWidthDp >= SEARCH_KEY_MIN_WIDTH

    LaunchedEffect(selectedMainCategory) {
        selectedSubCategory = subCategories.firstOrNull()?.second ?: ""
    }

    fun loadData() {
        scope.launch {
            isLoading = true
            tekiyouList = database.rakurakuTekiyouDao().getByCategory(
                selectedMainCategory.displayName, selectedSubCategory
            )
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        scope.launch { importTekiyouFromCsv(context, database); loadData() }
    }
    LaunchedEffect(selectedMainCategory, selectedSubCategory) { loadData() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("摘要辞書") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "戻る") }
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
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // メインカテゴリ
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MainCategory.entries.forEach { category ->
                    FilterChip(
                        selected = selectedMainCategory == category,
                        onClick  = { selectedMainCategory = category },
                        label    = {
                            Text(
                                category.displayName,
                                fontWeight = if (selectedMainCategory == category) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // サブカテゴリ
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                subCategories.forEach { (display, value) ->
                    FilterChip(
                        selected = selectedSubCategory == value,
                        onClick  = { selectedSubCategory = value },
                        label    = {
                            Text(
                                display,
                                fontWeight = if (selectedSubCategory == value) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (subCategories.size < 4) {
                    repeat(4 - subCategories.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Text(
                "${tekiyouList.size}件",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // リストヘッダー
            TekiyouHeader(showSearchKey = showSearchKey)
            Divider()

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(tekiyouList, key = { _, item -> item.id }) { index, tekiyou ->
                        TekiyouRow(
                            tekiyou       = tekiyou,
                            zebra         = index % 2 != 0,
                            showSearchKey = showSearchKey,
                            onToggleEnabled = { id, enabled ->
                                scope.launch {
                                    database.rakurakuTekiyouDao().updateEnabled(id, enabled)
                                    loadData()
                                }
                            },
                            onEdit = { selectedTekiyou = it; showEditDialog = true }
                        )
                    }
                }
            }
        }
    }

    // 編集ダイアログ（既存項目）
    if (showEditDialog && selectedTekiyou != null) {
        TekiyouEditDialog(
            title     = "摘要編集",
            tekiyou   = selectedTekiyou,
            onDismiss = { showEditDialog = false; selectedTekiyou = null },
            onSave    = { updated ->
                scope.launch { database.rakurakuTekiyouDao().update(updated); loadData() }
                showEditDialog = false; selectedTekiyou = null
            },
            onDelete  = {
                // 編集ダイアログを閉じてから削除確認ダイアログを表示
                showEditDialog = false
                showDeleteDialog = true
            }
        )
    }

    // 追加ダイアログ
    if (showAddDialog) {
        TekiyouEditDialog(
            title   = "摘要追加",
            tekiyou = RakurakuTekiyou(
                mainCategory  = selectedMainCategory.displayName,
                subCategory   = selectedSubCategory,
                tekiyouName   = "",
                searchKey     = "",
                kamoku        = "",
                businessRatio = null
            ),
            onDismiss = { showAddDialog = false },
            onSave    = { newTekiyou ->
                scope.launch { database.rakurakuTekiyouDao().insert(newTekiyou); loadData() }
                showAddDialog = false
            },
            onDelete  = null   // 新規追加時は削除不要
        )
    }

    // 削除確認ダイアログ
    if (showDeleteDialog && selectedTekiyou != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; selectedTekiyou = null },
            title = { Text("削除確認") },
            text  = { Text("「${selectedTekiyou?.tekiyouName}」を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            selectedTekiyou?.let { database.rakurakuTekiyouDao().delete(it) }
                            loadData()
                        }
                        showDeleteDialog = false; selectedTekiyou = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; selectedTekiyou = null }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

// ── リストヘッダー ────────────────────────────────────────
@Composable
private fun TekiyouHeader(showSearchKey: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ColHead("使用", width = 40.dp, textAlign = TextAlign.Center)
        ColHead("摘要名", modifier = Modifier.weight(2f))
        if (showSearchKey) ColHead("検索文字", modifier = Modifier.weight(1.5f))
        ColHead("科目", modifier = Modifier.weight(2f))
        ColHead("割合", width = 48.dp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ColHead(
    text: String,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null,
    textAlign: TextAlign = TextAlign.Start
) {
    val m = if (width != null) modifier.width(width) else modifier
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = m.padding(horizontal = 2.dp),
        textAlign = textAlign,
        maxLines = 1
    )
}

// ── 摘要行（横一列）────────────────────────────────────────
@Composable
private fun TekiyouRow(
    tekiyou: RakurakuTekiyou,
    zebra: Boolean,
    showSearchKey: Boolean,
    onToggleEnabled: (Int, Boolean) -> Unit,
    onEdit: (RakurakuTekiyou) -> Unit
) {
    val alpha = if (tekiyou.isEnabled) 1f else 0.4f
    val bg    = if (zebra)
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onEdit(tekiyou) }
            .padding(end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 使用チェックボックス
        Checkbox(
            checked = tekiyou.isEnabled,
            onCheckedChange = { onToggleEnabled(tekiyou.id, it) },
            modifier = Modifier.size(40.dp)
        )
        // 摘要名
        Text(
            tekiyou.tekiyouName,
            modifier = Modifier.weight(2f).padding(horizontal = 2.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
        // 検索文字（幅が広い場合のみ）
        if (showSearchKey) {
            Text(
                tekiyou.searchKey,
                modifier = Modifier.weight(1.5f).padding(horizontal = 2.dp),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
        // 科目
        Text(
            tekiyou.kamoku,
            modifier = Modifier.weight(2f).padding(horizontal = 2.dp),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
        // 事業割合
        Text(
            tekiyou.businessRatio?.let { "$it%" } ?: "",
            modifier = Modifier.width(48.dp).padding(horizontal = 2.dp),
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
        )
    }
    Divider(Modifier.padding(start = 40.dp), thickness = 0.5.dp)
}

// ── 編集ダイアログ ────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TekiyouEditDialog(
    title: String,
    tekiyou: RakurakuTekiyou?,
    onDismiss: () -> Unit,
    onSave: (RakurakuTekiyou) -> Unit,
    onDelete: (() -> Unit)?          // null = 新規追加（削除ボタン非表示）
) {
    var tekiyouName       by remember { mutableStateOf(tekiyou?.tekiyouName ?: "") }
    var searchKey         by remember { mutableStateOf(tekiyou?.searchKey ?: "") }
    var kamoku            by remember { mutableStateOf(tekiyou?.kamoku ?: "") }
    var businessRatioText by remember { mutableStateOf(tekiyou?.businessRatio?.toString() ?: "") }
    var isEnabled         by remember { mutableStateOf(tekiyou?.isEnabled ?: true) }

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
                    singleLine = true,
                    isError = tekiyouName.isBlank()
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
                    singleLine = true,
                    isError = kamoku.isBlank()
                )
                OutlinedTextField(
                    value = businessRatioText,
                    onValueChange = { v ->
                        if (v.isEmpty() || (v.all { it.isDigit() } && (v.toIntOrNull() ?: 0) <= 100))
                            businessRatioText = v
                    },
                    label = { Text("事業割合 (%)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (isEnabled) "使用する" else "使用しない", fontSize = 14.sp)
                }

                // 削除ボタン（既存項目のみ）
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("この摘要を削除")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (tekiyouName.isNotBlank() && kamoku.isNotBlank()) {
                        onSave(
                            RakurakuTekiyou(
                                id            = tekiyou?.id ?: 0,
                                mainCategory  = tekiyou?.mainCategory ?: "",
                                subCategory   = tekiyou?.subCategory ?: "",
                                tekiyouName   = tekiyouName.trim(),
                                searchKey     = searchKey.trim(),
                                kamoku        = kamoku.trim(),
                                taxRate       = tekiyou?.taxRate ?: "",
                                businessRatio = businessRatioText.toIntOrNull(),
                                isShared      = tekiyou?.isShared,
                                isEnabled     = isEnabled
                            )
                        )
                    }
                },
                enabled = tekiyouName.isNotBlank() && kamoku.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
