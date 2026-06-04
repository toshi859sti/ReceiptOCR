package com.example.greenframeocr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.RakurakuAccount
import com.example.greenframeocr.data.ReceiptDatabase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RakurakuAccountSettingsScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var accounts by remember { mutableStateOf<List<RakurakuAccount>>(emptyList()) }
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }
    var expandedCategoryB by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedCategoryC by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedAccount by remember { mutableStateOf<RakurakuAccount?>(null) }

    fun reload() { scope.launch { accounts = database.rakurakuAccountDao().getAll() } }

    LaunchedEffect(Unit) { reload() }

    val hierarchy = remember(accounts) {
        buildHierarchy(accounts) { Triple(it.categoryA, it.categoryB, it.categoryC) }
    }
    val categoryAList = remember(hierarchy) { hierarchy.map { it.categoryA } }

    LaunchedEffect(categoryAList) {
        if (selectedCategoryA == null || selectedCategoryA !in categoryAList)
            selectedCategoryA = categoryAList.firstOrNull()
    }

    val filteredHierarchy = remember(hierarchy, selectedCategoryA) {
        hierarchy.find { it.categoryA == selectedCategoryA }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("らくらく青色申告農業版 — 勘定科目") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "戻る") }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "追加")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CategoryASelector(
                categories = categoryAList,
                selectedCategory = selectedCategoryA,
                onCategorySelected = { cat ->
                    selectedCategoryA = cat
                    expandedCategoryB = emptySet()
                    expandedCategoryC = emptySet()
                }
            )

            val filteredCount = filteredHierarchy?.categoryBGroups?.sumOf { b ->
                b.categoryCGroups.sumOf { it.accounts.size }
            } ?: 0
            Text(
                text = "${filteredCount}件",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()

            filteredHierarchy?.let { h ->
                FilteredAccountList(
                    categoryBGroups = h.categoryBGroups,
                    expandedCategoryB = expandedCategoryB,
                    expandedCategoryC = expandedCategoryC,
                    onToggleCategoryB = { key ->
                        expandedCategoryB = if (key in expandedCategoryB)
                            expandedCategoryB - key else expandedCategoryB + key
                    },
                    onToggleCategoryC = { key ->
                        expandedCategoryC = if (key in expandedCategoryC)
                            expandedCategoryC - key else expandedCategoryC + key
                    },
                    accountContent = { account ->
                        RakurakuAccountRow(
                            account = account,
                            childAccounts = accounts.filter { it.parentId == account.id },
                            onClick = {
                                selectedAccount = account
                                showEditDialog = true
                            }
                        )
                    }
                )
            }
        }
    }

    // 編集ダイアログ
    if (showEditDialog) {
        selectedAccount?.let { account ->
            RakurakuEditDialog(
                title = "勘定科目編集",
                account = account,
                existingCategories = accounts.map { Triple(it.categoryA, it.categoryB, it.categoryC) }.distinct(),
                onDismiss = { showEditDialog = false; selectedAccount = null },
                onSave = { updated ->
                    scope.launch { database.rakurakuAccountDao().update(updated); reload() }
                    showEditDialog = false; selectedAccount = null
                },
                onDelete = {
                    showEditDialog = false
                    showDeleteDialog = true
                }
            )
        }
    }

    // 追加ダイアログ
    if (showAddDialog) {
        RakurakuEditDialog(
            title = "勘定科目追加",
            account = null,
            existingCategories = accounts.map { Triple(it.categoryA, it.categoryB, it.categoryC) }.distinct(),
            onDismiss = { showAddDialog = false },
            onSave = { newAccount ->
                scope.launch { database.rakurakuAccountDao().insert(newAccount); reload() }
                showAddDialog = false
            }
        )
    }

    // 削除確認
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; selectedAccount = null },
            title = { Text("削除確認") },
            text = { Text("「${selectedAccount?.accountName}」を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            selectedAccount?.let { database.rakurakuAccountDao().delete(it) }
                            reload()
                        }
                        showDeleteDialog = false; selectedAccount = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; selectedAccount = null }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun RakurakuAccountRow(
    account: RakurakuAccount,
    childAccounts: List<RakurakuAccount>,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 48.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = account.accountName,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Text(account.accountCode, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (account.searchKeyAlpha.isNotEmpty()) {
                Text(account.searchKeyAlpha, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(account.debitCredit, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            if (account.usedForPurchase) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("購買", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (account.usedForDeposit) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("預金", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }

        childAccounts.forEach { child ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(child.accountName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Divider(modifier = Modifier.padding(start = 48.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RakurakuEditDialog(
    title: String,
    account: RakurakuAccount?,
    existingCategories: List<Triple<String, String, String>>,
    onDismiss: () -> Unit,
    onSave: (RakurakuAccount) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var editName by remember { mutableStateOf(account?.accountName ?: "") }
    var editCode by remember { mutableStateOf(account?.accountCode ?: "") }
    var editSearchKey by remember { mutableStateOf(account?.searchKeyAlpha ?: "") }
    var editDebitCredit by remember { mutableStateOf(account?.debitCredit ?: "借") }
    var editCategoryA by remember { mutableStateOf(account?.categoryA ?: "") }
    var editCategoryB by remember { mutableStateOf(account?.categoryB ?: "") }
    var editCategoryC by remember { mutableStateOf(account?.categoryC ?: "") }
    var editUsedForPurchase by remember { mutableStateOf(account?.usedForPurchase ?: false) }

    var expandedA by remember { mutableStateOf(false) }
    var expandedB by remember { mutableStateOf(false) }
    var expandedC by remember { mutableStateOf(false) }

    val distinctA = existingCategories.map { it.first }.distinct().sorted()
    val distinctB = existingCategories.map { it.second }.distinct().sorted()
    val distinctC = existingCategories.map { it.third }.distinct().sorted()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("勘定科目名 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editCode, onValueChange = { editCode = it }, label = { Text("コード") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = editSearchKey, onValueChange = { editSearchKey = it }, label = { Text("英字キー") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("借貸:", fontSize = 14.sp)
                    listOf("借", "貸").forEach { label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = editDebitCredit == label, onClick = { editDebitCredit = label })
                            Text(label)
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = expandedA, onExpandedChange = { expandedA = it }) {
                    OutlinedTextField(value = editCategoryA, onValueChange = { editCategoryA = it }, label = { Text("区分A *") }, modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedA) }, singleLine = true)
                    ExposedDropdownMenu(expanded = expandedA, onDismissRequest = { expandedA = false }) {
                        distinctA.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { editCategoryA = it; expandedA = false }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = expandedB, onExpandedChange = { expandedB = it }) {
                    OutlinedTextField(value = editCategoryB, onValueChange = { editCategoryB = it }, label = { Text("区分B *") }, modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedB) }, singleLine = true)
                    ExposedDropdownMenu(expanded = expandedB, onDismissRequest = { expandedB = false }) {
                        distinctB.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { editCategoryB = it; expandedB = false }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = expandedC, onExpandedChange = { expandedC = it }) {
                    OutlinedTextField(value = editCategoryC, onValueChange = { editCategoryC = it }, label = { Text("区分C *") }, modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedC) }, singleLine = true)
                    ExposedDropdownMenu(expanded = expandedC, onDismissRequest = { expandedC = false }) {
                        distinctC.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { editCategoryC = it; expandedC = false }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = editUsedForPurchase, onCheckedChange = { editUsedForPurchase = it })
                    Text("購買取引で使用")
                }
                if (account != null && onDelete != null) {
                    Divider(modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text("この科目を削除")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (editName.isNotBlank() && editCategoryA.isNotBlank() && editCategoryB.isNotBlank() && editCategoryC.isNotBlank()) {
                        onSave(RakurakuAccount(
                            id = account?.id ?: 0,
                            accountName = editName.trim(),
                            accountCode = editCode.trim(),
                            searchKeyAlpha = editSearchKey.trim(),
                            debitCredit = editDebitCredit,
                            categoryA = editCategoryA.trim(),
                            categoryB = editCategoryB.trim(),
                            categoryC = editCategoryC.trim(),
                            usedForPurchase = editUsedForPurchase,
                            usedForDeposit = account?.usedForDeposit ?: true,
                            parentId = account?.parentId
                        ))
                    }
                },
                enabled = editName.isNotBlank() && editCategoryA.isNotBlank() && editCategoryB.isNotBlank() && editCategoryC.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
