package com.example.greenframeocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.ReceiptPaymentMethodRule
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.AoiroChoboReceiptRules
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel
import kotlinx.coroutines.launch

/**
 * 支払方法（Geminiが合計欄付近から抽出した印字テキスト）→相手科目（貸方勘定科目）の
 * 変換ルールを管理する画面。TekiyouMatchingScreenの摘要マッチングルールと同じ考え方で、
 * ユーザーが自由にキーワードと勘定科目の対応を登録・編集できる。
 * どのルールにも一致しない場合は出力時に「現金」へフォールバックする（ViewModel側で解決）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptPaymentMethodRuleScreen(
    viewModel: GeneralReceiptViewModel,
    isAoiro: Boolean = false,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<ReceiptPaymentMethodRule>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var aoiroAccounts by remember { mutableStateOf<List<AoiroChoboAccount>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var editTarget by remember { mutableStateOf<ReceiptPaymentMethodRule?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ReceiptPaymentMethodRule?>(null) }

    fun reload() {
        scope.launch {
            rules = viewModel.loadPaymentMethodRules()
            accounts = viewModel.loadYayoiAccounts()
            if (isAoiro) aoiroAccounts = viewModel.loadAoiroVocab().accounts
            isLoading = false
        }
    }

    /** あおいろの科目名。今の辞書の名前 → 保存時の名前 の順 */
    fun aoiroName(rule: ReceiptPaymentMethodRule): String? =
        rule.accountKey?.let { key -> aoiroAccounts.find { it.accountKey == key }?.name ?: rule.accountKeyName ?: key }

    /** あおいろモードで足したルールの弥生の科目。弥生の出力ではどのルールにも当たらないときと同じ「現金」になる */
    fun yayoiCashId(): Long = accounts.firstOrNull { it.accountName == "現金" }?.id ?: 0L
    LaunchedEffect(Unit) { reload() }

    val accountsById = remember(accounts) { accounts.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("支払方法の科目設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "ルール追加")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = if (isAoiro) {
                    "レシートに印字された支払方法（例:「クレジット」「PayPay」）に含まれる" +
                        "キーワードと、あおいろ帳簿に送る支払方法の科目（貸方）を対応付けます。" +
                        "どれにも一致しない場合や記載がない場合（手書き領収書等）は「現金」になります。" +
                        "一致したルールにあおいろの科目が無いと、そのレシートは「科目なし」で PC に送ります。"
                } else {
                    "レシートに印字された支払方法（例:「クレジット」「PayPay」）に含まれる" +
                        "キーワードと、弥生CSV出力時の支払方法の科目（貸方勘定科目）を対応付けます。" +
                        "どれにも一致しない場合や記載がない場合（手書き領収書等）は「現金」になります。"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
            Divider()

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "ルールがまだ登録されていません。右上の＋から追加してください",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rules, key = { it.id }) { rule ->
                        val account = accountsById[rule.yayoiAccountId]
                        val targetName = if (isAoiro) aoiroName(rule) else account?.accountName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editTarget = rule }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "「${rule.keyword}」を含む",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "→ ${targetName ?: if (isAoiro) "（あおいろ未設定）" else "（科目未登録）"}",
                                    fontSize = 13.sp,
                                    color = if (targetName != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                )
                            }
                            IconButton(onClick = { deleteTarget = rule }) {
                                Icon(Icons.Default.Delete, contentDescription = "削除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }

    // あおいろモード：キーワードとあおいろの科目だけを編集する（弥生の科目はそのまま）
    if (isAoiro && showAddDialog) {
        AoiroRuleEditDialog(
            rule = null,
            accounts = aoiroAccounts,
            onDismiss = { showAddDialog = false },
            onSave = { keyword, key, name ->
                viewModel.savePaymentMethodRule(
                    ReceiptPaymentMethodRule(
                        keyword = keyword, yayoiAccountId = yayoiCashId(), sortOrder = rules.size,
                        accountKey = key, accountKeyName = name
                    )
                )
                showAddDialog = false
                reload()
            }
        )
    }
    if (isAoiro) editTarget?.let { rule ->
        AoiroRuleEditDialog(
            rule = rule,
            accounts = aoiroAccounts,
            onDismiss = { editTarget = null },
            onSave = { keyword, key, name ->
                viewModel.savePaymentMethodRule(rule.copy(keyword = keyword, accountKey = key, accountKeyName = name))
                editTarget = null
                reload()
            }
        )
    }

    // ルール追加ダイアログ
    if (!isAoiro && showAddDialog) {
        RuleEditDialog(
            rule = null,
            accounts = accounts,
            onDismiss = { showAddDialog = false },
            onSave = { keyword, accountId ->
                viewModel.savePaymentMethodRule(
                    ReceiptPaymentMethodRule(keyword = keyword, yayoiAccountId = accountId, sortOrder = rules.size)
                )
                showAddDialog = false
                reload()
            }
        )
    }

    // ルール編集ダイアログ
    if (!isAoiro) editTarget?.let { rule ->
        RuleEditDialog(
            rule = rule,
            accounts = accounts,
            onDismiss = { editTarget = null },
            onSave = { keyword, accountId ->
                viewModel.savePaymentMethodRule(rule.copy(keyword = keyword, yayoiAccountId = accountId))
                editTarget = null
                reload()
            }
        )
    }

    // 削除確認ダイアログ
    deleteTarget?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("ルール削除") },
            text = { Text("「${rule.keyword}」のルールを削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePaymentMethodRule(rule)
                        deleteTarget = null
                        reload()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
            }
        )
    }
}

/**
 * あおいろモードのルール編集。科目は契約が挙げる 現金・未払金・事業主借 を既定の候補にし、
 * 「絞り込み外も表示」で口座・借入金などの資産・負債も選べる（[AoiroChoboReceiptRules]）。
 * 科目は外してもよい（そのルールに当たるレシートは「科目なし」で送る）。
 */
@Composable
private fun AoiroRuleEditDialog(
    rule: ReceiptPaymentMethodRule?,
    accounts: List<AoiroChoboAccount>,
    onDismiss: () -> Unit,
    onSave: (keyword: String, accountKey: String?, accountKeyName: String?) -> Unit
) {
    var keyword by remember { mutableStateOf(rule?.keyword ?: "") }
    var accountKey by remember { mutableStateOf(rule?.accountKey) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "ルール追加（あおいろ）" else "ルール編集（あおいろ）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("キーワード（例: クレジット、PayPay）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (accounts.isEmpty()) {
                    Text(
                        "あおいろ帳簿の科目がまだ取り込まれていません。設定画面から取り込んでください。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    PickerField(
                        label = "支払方法の科目（あおいろ）",
                        value = accountKey?.let { key ->
                            accounts.find { it.accountKey == key }?.name
                                ?: rule?.accountKeyName.takeIf { key == rule?.accountKey } ?: key
                        } ?: "未設定",
                        hasValue = accountKey != null,
                        onPick = { showPicker = true },
                        onClear = { accountKey = null }
                    )
                }
                if (rule != null) {
                    Text(
                        "弥生の科目は変わりません（弥生モードで設定）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = accountKey?.let { key ->
                        accounts.find { it.accountKey == key }?.name ?: rule?.accountKeyName.takeIf { key == rule?.accountKey }
                    }
                    onSave(keyword.trim(), accountKey, name)
                },
                enabled = keyword.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )

    if (showPicker) {
        AoiroAccountPickerDialog(
            subject = keyword,
            emptySubject = "支払方法",
            accounts = AoiroChoboReceiptRules.paymentCandidates(accounts),
            allAccounts = AoiroChoboReceiptRules.allPaymentCandidates(accounts),
            memoCandidates = null,
            selectedKey = accountKey,
            onSelect = { key ->
                accountKey = key
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditDialog(
    rule: ReceiptPaymentMethodRule?,
    accounts: List<YayoiAccount>,
    onDismiss: () -> Unit,
    onSave: (keyword: String, accountId: Long) -> Unit
) {
    var keyword by remember { mutableStateOf(rule?.keyword ?: "") }
    var selectedAccountId by remember { mutableStateOf(rule?.yayoiAccountId) }
    var searchText by remember { mutableStateOf("") }
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }

    val categoryAList = remember(accounts) {
        sortYayoiCategoryA(accounts.map { it.categoryA }.filter { it.isNotBlank() })
    }

    val filtered = remember(accounts, searchText, selectedCategoryA) {
        accounts.filter { acc ->
            (selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText, ignoreCase = true) == true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "ルール追加" else "ルール編集") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("キーワード（例: クレジット、PayPay）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "支払方法の科目を選択",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
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
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "検索") }
                )
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered, key = { it.id }) { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccountId = account.id }
                                .background(
                                    if (selectedAccountId == account.id)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedAccountId == account.id,
                                onClick = { selectedAccountId = account.id }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.accountName, fontWeight = FontWeight.Medium)
                                Text(
                                    "${account.categoryA} / ${account.categoryB}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedAccountId?.let { onSave(keyword.trim(), it) } },
                enabled = keyword.isNotBlank() && selectedAccountId != null
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
