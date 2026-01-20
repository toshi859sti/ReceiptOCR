package com.example.receiptorc.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.receiptorc.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 勘定科目設定画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // State
    var selectedTab by remember { mutableStateOf(0) }
    var rakurakuAccounts by remember { mutableStateOf<List<RakurakuAccount>>(emptyList()) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showImportResultDialog by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var selectedRakurakuAccount by remember { mutableStateOf<RakurakuAccount?>(null) }
    var selectedYayoiAccount by remember { mutableStateOf<YayoiAccount?>(null) }

    val tabs = listOf("らくらく青色申告", "弥生会計")

    // 初期データ読み込み
    LaunchedEffect(Unit) {
        rakurakuAccounts = database.rakurakuAccountDao().getAll()
        yayoiAccounts = database.yayoiAccountDao().getAll()
    }

    fun loadAccounts() {
        scope.launch {
            rakurakuAccounts = database.rakurakuAccountDao().getAll()
            yayoiAccounts = database.yayoiAccountDao().getAll()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("勘定科目設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                actions = {
                    // インポートボタン（弥生会計タブのみ）
                    if (selectedTab == 1) {
                        IconButton(
                            onClick = { showImportDialog = true },
                            enabled = !isImporting
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Download, "インポート")
                            }
                        }
                    }
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
            // タブ
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            // 件数表示
            val count = if (selectedTab == 0) rakurakuAccounts.size else yayoiAccounts.size
            Text(
                text = "${count}件",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()

            // リスト
            when (selectedTab) {
                0 -> {
                    // らくらく勘定科目
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(rakurakuAccounts, key = { it.id }) { account ->
                            AccountListItem(
                                code = account.accountCode,
                                name = account.accountName,
                                onClick = {
                                    selectedRakurakuAccount = account
                                    showEditDialog = true
                                },
                                onDelete = {
                                    selectedRakurakuAccount = account
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
                1 -> {
                    // 弥生勘定科目
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(yayoiAccounts, key = { it.id }) { account ->
                            YayoiAccountListItem(
                                account = account,
                                onClick = {
                                    selectedYayoiAccount = account
                                    showEditDialog = true
                                },
                                onDelete = {
                                    selectedYayoiAccount = account
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 編集ダイアログ
    if (showEditDialog) {
        when (selectedTab) {
            0 -> selectedRakurakuAccount?.let { account ->
                AccountEditDialog(
                    title = "らくらく勘定科目編集",
                    code = account.accountCode,
                    name = account.accountName,
                    onDismiss = {
                        showEditDialog = false
                        selectedRakurakuAccount = null
                    },
                    onSave = { newCode, newName ->
                        scope.launch {
                            database.rakurakuAccountDao().update(
                                account.copy(accountCode = newCode, accountName = newName)
                            )
                            loadAccounts()
                        }
                        showEditDialog = false
                        selectedRakurakuAccount = null
                    }
                )
            }
            1 -> selectedYayoiAccount?.let { account ->
                YayoiAccountEditDialog(
                    title = "弥生勘定科目編集",
                    account = account,
                    onDismiss = {
                        showEditDialog = false
                        selectedYayoiAccount = null
                    },
                    onSave = { updatedAccount ->
                        scope.launch {
                            database.yayoiAccountDao().update(updatedAccount)
                            loadAccounts()
                        }
                        showEditDialog = false
                        selectedYayoiAccount = null
                    }
                )
            }
        }
    }

    // 追加ダイアログ
    if (showAddDialog) {
        if (selectedTab == 0) {
            AccountEditDialog(
                title = "らくらく勘定科目追加",
                code = "",
                name = "",
                onDismiss = { showAddDialog = false },
                onSave = { newCode, newName ->
                    scope.launch {
                        database.rakurakuAccountDao().insert(
                            RakurakuAccount(accountCode = newCode, accountName = newName)
                        )
                        loadAccounts()
                    }
                    showAddDialog = false
                }
            )
        } else {
            YayoiAccountEditDialog(
                title = "弥生勘定科目追加",
                account = null,
                onDismiss = { showAddDialog = false },
                onSave = { newAccount ->
                    scope.launch {
                        database.yayoiAccountDao().insert(newAccount)
                        loadAccounts()
                    }
                    showAddDialog = false
                }
            )
        }
    }

    // 削除確認ダイアログ
    if (showDeleteDialog) {
        val accountName = when (selectedTab) {
            0 -> selectedRakurakuAccount?.accountName ?: ""
            else -> selectedYayoiAccount?.accountName ?: ""
        }
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                selectedRakurakuAccount = null
                selectedYayoiAccount = null
            },
            title = { Text("削除確認") },
            text = { Text("「$accountName」を削除しますか？\n\nこの勘定科目を使用している購買品の設定も解除されます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            when (selectedTab) {
                                0 -> selectedRakurakuAccount?.let {
                                    database.rakurakuAccountDao().delete(it)
                                }
                                1 -> selectedYayoiAccount?.let {
                                    database.yayoiAccountDao().delete(it)
                                }
                            }
                            loadAccounts()
                        }
                        showDeleteDialog = false
                        selectedRakurakuAccount = null
                        selectedYayoiAccount = null
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
                    selectedRakurakuAccount = null
                    selectedYayoiAccount = null
                }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // インポート確認ダイアログ
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("弥生勘定科目インポート") },
            text = {
                Text("CSVファイルから弥生会計の勘定科目をインポートします。\n\n既存のデータは上書きされます。続行しますか？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        scope.launch {
                            isImporting = true
                            try {
                                val count = importYayoiAccountsFromCsv(context, database)
                                importResult = "${count}件の勘定科目をインポートしました"
                                loadAccounts()
                            } catch (e: Exception) {
                                importResult = "エラー: ${e.message}"
                            } finally {
                                isImporting = false
                                showImportResultDialog = true
                            }
                        }
                    }
                ) {
                    Text("インポート")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // インポート結果ダイアログ
    if (showImportResultDialog) {
        AlertDialog(
            onDismissRequest = { showImportResultDialog = false },
            title = { Text("インポート完了") },
            text = { Text(importResult) },
            confirmButton = {
                TextButton(onClick = { showImportResultDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * CSVから弥生勘定科目をインポート
 */
private suspend fun importYayoiAccountsFromCsv(
    context: Context,
    database: ReceiptDatabase
): Int = withContext(Dispatchers.IO) {
    val dao = database.yayoiAccountDao()
    val accounts = mutableListOf<YayoiAccount>()

    context.assets.open("yayoi_accounts.csv").use { inputStream ->
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            // ヘッダー行をスキップ
            reader.readLine()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split(",")
                if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                    val accountName = parts[0].trim()
                    // ヘッダー行（【で始まる）をスキップ
                    if (!accountName.startsWith("【")) {
                        accounts.add(
                            YayoiAccount(
                                id = 0,
                                accountName = accountName,
                                searchKeyAlpha = parts.getOrNull(1)?.trim() ?: "",
                                accountCode = parts.getOrNull(2)?.trim() ?: "",
                                debitCredit = parts.getOrNull(3)?.trim() ?: "",
                                taxCategory = parts.getOrNull(4)?.trim() ?: ""
                            )
                        )
                    }
                }
            }
        }
    }

    // 既存データを削除して新規インポート
    dao.deleteAll()
    dao.insertAll(accounts)

    accounts.size
}

/**
 * 勘定科目リストアイテム
 */
@Composable
private fun AccountListItem(
    code: String,
    name: String,
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
            Text(
                text = name,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            if (code.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "コード: $code",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
 * 勘定科目編集ダイアログ（らくらく用）
 */
@Composable
private fun AccountEditDialog(
    title: String,
    code: String,
    name: String,
    onDismiss: () -> Unit,
    onSave: (code: String, name: String) -> Unit
) {
    var editCode by remember { mutableStateOf(code) }
    var editName by remember { mutableStateOf(name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = editCode,
                    onValueChange = { editCode = it },
                    label = { Text("勘定科目コード") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("勘定科目名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (editName.isNotBlank()) {
                        onSave(editCode.trim(), editName.trim())
                    }
                },
                enabled = editName.isNotBlank()
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

/**
 * 弥生勘定科目リストアイテム
 */
@Composable
private fun YayoiAccountListItem(
    account: YayoiAccount,
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
            Text(
                text = account.accountName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "コード: ${account.accountCode}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (account.searchKeyAlpha.isNotEmpty()) {
                    Text(
                        text = "英字: ${account.searchKeyAlpha}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (account.debitCredit.isNotEmpty() || account.taxCategory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (account.debitCredit.isNotEmpty()) {
                        Text(
                            text = "借貸: ${account.debitCredit}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (account.taxCategory.isNotEmpty()) {
                        Text(
                            text = "税: ${account.taxCategory}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
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
 * 弥生勘定科目編集ダイアログ
 */
@Composable
private fun YayoiAccountEditDialog(
    title: String,
    account: YayoiAccount?,
    onDismiss: () -> Unit,
    onSave: (YayoiAccount) -> Unit
) {
    var editName by remember { mutableStateOf(account?.accountName ?: "") }
    var editSearchKeyAlpha by remember { mutableStateOf(account?.searchKeyAlpha ?: "") }
    var editCode by remember { mutableStateOf(account?.accountCode ?: "") }
    var editDebitCredit by remember { mutableStateOf(account?.debitCredit ?: "") }
    var editTaxCategory by remember { mutableStateOf(account?.taxCategory ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("勘定科目名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editSearchKeyAlpha,
                    onValueChange = { editSearchKeyAlpha = it },
                    label = { Text("サーチキー英字") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editCode,
                    onValueChange = { editCode = it },
                    label = { Text("サーチキー数字") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editDebitCredit,
                    onValueChange = { editDebitCredit = it },
                    label = { Text("借貸") },
                    placeholder = { Text("借 または 貸") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editTaxCategory,
                    onValueChange = { editTaxCategory = it },
                    label = { Text("税区分") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (editName.isNotBlank()) {
                        val newAccount = YayoiAccount(
                            id = account?.id ?: 0,
                            accountName = editName.trim(),
                            searchKeyAlpha = editSearchKeyAlpha.trim(),
                            accountCode = editCode.trim(),
                            debitCredit = editDebitCredit.trim(),
                            taxCategory = editTaxCategory.trim()
                        )
                        onSave(newAccount)
                    }
                },
                enabled = editName.isNotBlank()
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
