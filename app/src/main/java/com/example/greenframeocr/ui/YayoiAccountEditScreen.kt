package com.example.greenframeocr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.data.YayoiAccount
import kotlinx.coroutines.launch

private val TAX_CATEGORIES = listOf("対象外", "課対仕入10", "課対仕入8", "課税売上", "非課税")

@Composable
private fun ReadOnlyField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value.ifBlank { "—" }, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// categoryA から借貸区分を自動算出（勘定科目グループで完全に決まる）
private fun computeDebitCredit(catA: String): String = when (catA) {
    "資産", "経費" -> "借"
    "負債", "資本", "収入" -> "貸"
    else -> ""
}

// categoryA から税区分のデフォルト値を推定（新規追加時のみ使用）
private fun computeDefaultTaxCategory(catA: String): String = when (catA) {
    "収入" -> "課税売上"
    "経費" -> "課対仕入10"
    else   -> "対象外"  // 資産・負債・資本
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YayoiAccountEditScreen(
    accountId: Long,
    database: ReceiptDatabase,
    onBack: () -> Unit,
    initialParentId: Long? = null
) {
    val scope = rememberCoroutineScope()
    val isNew = accountId == -1L

    var accountName     by remember { mutableStateOf("") }
    var accountCode     by remember { mutableStateOf("") }
    var searchKeyAlpha  by remember { mutableStateOf("") }
    var categoryA       by remember { mutableStateOf("") }
    var categoryB       by remember { mutableStateOf("") }
    var defaultTaxCat   by remember { mutableStateOf("対象外") }
    var usedForPurchase by remember { mutableStateOf(false) }
    var usedForDeposit  by remember { mutableStateOf(false) }
    var usedForReceipt  by remember { mutableStateOf(false) }
    var isEnabled       by remember { mutableStateOf(true) }
    var selectedParentId by remember { mutableStateOf<Long?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var parentAccounts  by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var existingCatA    by remember { mutableStateOf<List<String>>(emptyList()) }
    var existingCatB    by remember { mutableStateOf<List<String>>(emptyList()) }

    var expandedCatA   by remember { mutableStateOf(false) }
    var expandedCatB   by remember { mutableStateOf(false) }
    var expandedTax    by remember { mutableStateOf(false) }
    var expandedParent by remember { mutableStateOf(false) }

    // 借貸区分は categoryA から自動算出
    val debitCredit by remember { derivedStateOf { computeDebitCredit(categoryA) } }

    LaunchedEffect(accountId) {
        val all = database.yayoiAccountDao().getAll()
        parentAccounts = all.filter { it.parentId == null && it.id != accountId }
        existingCatA = sortYayoiCategoryA(all.map { it.categoryA }.filter { it.isNotBlank() })
        existingCatB = all.map { it.categoryB }.distinct().filter { it.isNotBlank() }.sorted()

        if (!isNew) {
            database.yayoiAccountDao().getById(accountId)?.let { a ->
                accountName      = a.accountName
                accountCode      = a.accountCode ?: ""
                searchKeyAlpha   = a.searchKeyAlpha
                categoryA        = a.categoryA
                categoryB        = a.categoryB
                defaultTaxCat    = a.defaultTaxCategory
                usedForPurchase  = a.usedForPurchase
                usedForDeposit   = a.usedForDeposit
                usedForReceipt   = a.usedForReceipt
                isEnabled        = a.isEnabled
                selectedParentId = a.parentId
            }
        } else {
            selectedParentId = initialParentId
            // 補助作成の場合、親科目の区分をプリセット
            initialParentId?.let { pid ->
                parentAccounts.find { it.id == pid }?.let { parent ->
                    categoryA = parent.categoryA
                    categoryB = parent.categoryB
                }
            }
        }
    }

    val title  = if (isNew) "弥生勘定科目 新規追加" else "弥生勘定科目 編集"
    val canSave = accountName.isNotBlank() && categoryA.isNotBlank() && categoryB.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val account = YayoiAccount(
                                    id = if (isNew) 0L else accountId,
                                    accountName = accountName.trim(),
                                    accountCode = accountCode.trim().ifEmpty { null },
                                    searchKeyAlpha = searchKeyAlpha.trim(),
                                    debitCredit = debitCredit,
                                    categoryA = categoryA.trim(),
                                    categoryB = categoryB.trim(),
                                    defaultTaxCategory = defaultTaxCat,
                                    usedForPurchase = usedForPurchase,
                                    usedForDeposit = usedForDeposit,
                                    usedForReceipt = usedForReceipt,
                                    isEnabled = isEnabled,
                                    parentId = selectedParentId
                                )
                                database.yayoiAccountDao().upsert(account)
                                onBack()
                            }
                        },
                        enabled = canSave
                    ) {
                        Icon(Icons.Default.Save, "保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // 勘定科目名
            OutlinedTextField(
                value = accountName,
                onValueChange = { accountName = it },
                label = { Text("勘定科目名 *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = accountName.isBlank()
            )

            // コード / 英字キー
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = accountCode,
                    onValueChange = { accountCode = it },
                    label = { Text("コード") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("例: 700", fontSize = 13.sp) }
                )
                OutlinedTextField(
                    value = searchKeyAlpha,
                    onValueChange = { searchKeyAlpha = it },
                    label = { Text("英字キー") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("例: SOZEI", fontSize = 13.sp) }
                )
            }

            // 区分A（新規作成時のみ変更可。既存科目は読み取り専用）
            if (isNew) {
                ExposedDropdownMenuBox(
                    expanded = expandedCatA,
                    onExpandedChange = { expandedCatA = it }
                ) {
                    OutlinedTextField(
                        value = categoryA,
                        onValueChange = { categoryA = it },
                        label = { Text("区分A (大分類) *") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCatA) },
                        singleLine = true,
                        isError = categoryA.isBlank()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCatA,
                        onDismissRequest = { expandedCatA = false }
                    ) {
                        existingCatA.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    categoryA = cat
                                    defaultTaxCat = computeDefaultTaxCategory(cat)
                                    expandedCatA = false
                                }
                            )
                        }
                    }
                }
            } else {
                ReadOnlyField("区分A (大分類)", categoryA, Modifier.fillMaxWidth())
            }

            // 借貸区分（区分Aから自動算出・読み取り専用）
            if (categoryA.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("借貸区分:", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = when (debitCredit) {
                            "借" -> "借方（左側）"
                            "貸" -> "貸方（右側）"
                            else -> "—"
                        },
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("※ 区分Aで自動決定", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 区分B（新規作成時のみ変更可。既存科目は読み取り専用）
            if (isNew) {
                ExposedDropdownMenuBox(
                    expanded = expandedCatB,
                    onExpandedChange = { expandedCatB = it }
                ) {
                    OutlinedTextField(
                        value = categoryB,
                        onValueChange = { categoryB = it },
                        label = { Text("区分B (中分類) *") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCatB) },
                        singleLine = true,
                        isError = categoryB.isBlank()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCatB,
                        onDismissRequest = { expandedCatB = false }
                    ) {
                        existingCatB.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = { categoryB = cat; expandedCatB = false }
                            )
                        }
                    }
                }
            } else {
                ReadOnlyField("区分B (中分類)", categoryB, Modifier.fillMaxWidth())
            }

            // 税区分
            ExposedDropdownMenuBox(
                expanded = expandedTax,
                onExpandedChange = { expandedTax = it }
            ) {
                OutlinedTextField(
                    value = defaultTaxCat,
                    onValueChange = {},
                    label = { Text("税区分") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTax) },
                    readOnly = true
                )
                ExposedDropdownMenu(
                    expanded = expandedTax,
                    onDismissRequest = { expandedTax = false }
                ) {
                    TAX_CATEGORIES.forEach { tax ->
                        DropdownMenuItem(
                            text = { Text(tax) },
                            onClick = { defaultTaxCat = tax; expandedTax = false }
                        )
                    }
                }
            }

            // 親科目（新規追加時はドロップダウン、既存は読み取り専用テキスト）
            val parentLabel = selectedParentId
                ?.let { pid -> parentAccounts.find { it.id == pid }?.accountName ?: "（ID: $pid）" }
                ?: "なし（親科目）"
            if (isNew) {
                ExposedDropdownMenuBox(
                    expanded = expandedParent,
                    onExpandedChange = { expandedParent = it }
                ) {
                    OutlinedTextField(
                        value = parentLabel,
                        onValueChange = {},
                        label = { Text("親科目（補助科目の場合）") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedParent) },
                        readOnly = true
                    )
                    ExposedDropdownMenu(
                        expanded = expandedParent,
                        onDismissRequest = { expandedParent = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("なし（親科目）") },
                            onClick = { selectedParentId = null; expandedParent = false }
                        )
                        parentAccounts.forEach { parent ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(parent.accountName)
                                        Text(
                                            "${parent.categoryA} > ${parent.categoryB}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = { selectedParentId = parent.id; expandedParent = false }
                            )
                        }
                    }
                }
            } else {
                ReadOnlyField("親科目", parentLabel, Modifier.fillMaxWidth())
            }

            Divider()

            // フラグ群
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = usedForPurchase, onCheckedChange = { usedForPurchase = it })
                Text("購買取引で使用", modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = usedForDeposit, onCheckedChange = { usedForDeposit = it })
                Text("預金取引で使用", modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = usedForReceipt, onCheckedChange = { usedForReceipt = it })
                Text("レシート領収書取引で使用", modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                Spacer(Modifier.width(12.dp))
                Text(if (isEnabled) "表示する（有効）" else "非表示（無効）")
            }

            // 削除ボタン（既存科目のみ）
            if (!isNew) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("この勘定科目を削除")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // 削除確認ダイアログ
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("削除確認") },
            text  = { Text("「$accountName」を削除しますか？\nこの操作は取り消せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            database.yayoiAccountDao().getById(accountId)?.let { a ->
                                database.yayoiAccountDao().delete(a)
                            }
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            }
        )
    }
}
