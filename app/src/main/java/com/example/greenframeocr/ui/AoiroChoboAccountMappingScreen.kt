package com.example.greenframeocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.AoiroChoboAccountMapping
import com.example.greenframeocr.util.AoiroChoboAccountMapping.Tab
import kotlinx.coroutines.launch

/**
 * 弥生の勘定科目に AoiroChobo の `accountKey` を割り当てる画面。
 *
 * 並べる主軸は AoiroChobo 側の科目で、タブも PC の科目・残高登録画面と同じ 4 つ。
 * `accountKey` が入っていない科目を使う仕訳は `matchStatus = "UnmatchedAccount"` で出るので、
 * ここが埋まるほど PC 側の「要確認」が減る。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AoiroChoboAccountMappingScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var aoiroAccounts by remember { mutableStateOf<List<AoiroChoboAccount>>(emptyList()) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(Tab.EXPENSE) }
    var pickerFor by remember { mutableStateOf<AoiroChoboAccount?>(null) }

    fun reload() {
        scope.launch {
            aoiroAccounts = database.aoiroChoboVocabDao().getAllAccounts()
            yayoiAccounts = database.yayoiAccountDao().getAll()
            loaded = true
        }
    }

    LaunchedEffect(Unit) { reload() }

    val suggestions = remember(aoiroAccounts, yayoiAccounts) {
        AoiroChoboAccountMapping.suggest(aoiroAccounts, yayoiAccounts)
    }
    val linkedByKey = remember(yayoiAccounts) {
        yayoiAccounts.filter { it.accountKey != null }.groupBy { it.accountKey!! }
    }
    val tabs = remember(aoiroAccounts) {
        val present = aoiroAccounts.map { AoiroChoboAccountMapping.tabOf(it) }.toSet()
        Tab.entries.filter { it in present }
    }

    LaunchedEffect(tabs) {
        if (tabs.isNotEmpty() && selectedTab !in tabs) selectedTab = tabs.first()
    }

    val rows = remember(aoiroAccounts, selectedTab) {
        aoiroAccounts.filter { AoiroChoboAccountMapping.tabOf(it) == selectedTab }
    }
    val autoConfirmable = remember(suggestions) {
        AoiroChoboAccountMapping.autoConfirmable(suggestions)
    }

    fun link(aoiro: AoiroChoboAccount, yayoi: YayoiAccount) {
        scope.launch {
            database.yayoiAccountDao().setAccountKey(yayoi.id, aoiro.accountKey, aoiro.name)
            reload()
        }
    }

    fun unlink(yayoi: YayoiAccount) {
        scope.launch {
            database.yayoiAccountDao().clearAccountKey(yayoi.id)
            reload()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("あおいろ帳簿 — 科目マッピング") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "戻る") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                !loaded -> Unit

                aoiroAccounts.isEmpty() -> NotImportedYet()

                else -> {
                    MappingProgress(
                        total = aoiroAccounts.size,
                        mapped = linkedByKey.keys.count { key -> aoiroAccounts.any { it.accountKey == key } },
                        autoConfirmableCount = autoConfirmable.size,
                        onConfirmAll = {
                            scope.launch {
                                autoConfirmable.forEach { suggestion ->
                                    val aoiro = aoiroAccounts.first { it.accountKey == suggestion.accountKey }
                                    database.yayoiAccountDao().setAccountKey(
                                        suggestion.candidates.first().id, aoiro.accountKey, aoiro.name
                                    )
                                }
                                reload()
                            }
                        }
                    )

                    TabRow(selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)) {
                        tabs.forEach { tab ->
                            androidx.compose.material3.Tab(
                                selected = tab == selectedTab,
                                onClick = { selectedTab = tab },
                                text = { Text(tab.label, fontSize = 14.sp) }
                            )
                        }
                    }

                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.accountKey }) { aoiro ->
                            MappingRow(
                                account = aoiro,
                                parentName = aoiro.parentAccountKey
                                    ?.let { parent -> aoiroAccounts.find { it.accountKey == parent }?.name },
                                linked = linkedByKey[aoiro.accountKey].orEmpty(),
                                suggestion = suggestions[aoiro.accountKey],
                                onConfirm = { yayoi -> link(aoiro, yayoi) },
                                onUnlink = { yayoi -> unlink(yayoi) },
                                onPick = { pickerFor = aoiro }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
    }

    pickerFor?.let { aoiro ->
        YayoiAccountPickerDialog(
            target = aoiro,
            yayoiAccounts = yayoiAccounts,
            aoiroNameOf = { key -> aoiroAccounts.find { it.accountKey == key }?.name ?: key },
            onSelect = { yayoi ->
                link(aoiro, yayoi)
                pickerFor = null
            },
            onDismiss = { pickerFor = null }
        )
    }
}

@Composable
private fun NotImportedYet() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("あおいろ帳簿の科目がまだありません", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "設定 > データ管理 の「AoiroChobo 科目・摘要を取り込む」で " +
                "vocabulary.json を取り込むと、ここに科目が並びます。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MappingProgress(
    total: Int,
    mapped: Int,
    autoConfirmableCount: Int,
    onConfirmAll: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("$total 件中 $mapped 件を紐付け済み", fontWeight = FontWeight.Medium)
        Text(
            "紐付いていない科目を使う仕訳は、PC 側に「要確認」で届きます",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (autoConfirmableCount > 0) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConfirmAll, modifier = Modifier.fillMaxWidth()) {
                Text("提案をまとめて確定（$autoConfirmableCount 件）")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingRow(
    account: AoiroChoboAccount,
    parentName: String?,
    linked: List<YayoiAccount>,
    suggestion: AoiroChoboAccountMapping.Suggestion?,
    onConfirm: (YayoiAccount) -> Unit,
    onUnlink: (YayoiAccount) -> Unit,
    onPick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (parentName != null) {
                    Text(
                        "$parentName ›",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(account.name, fontWeight = FontWeight.Medium)
                Text(
                    account.accountKey,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onPick) { Text(if (linked.isEmpty()) "選ぶ" else "追加") }
        }

        if (linked.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                linked.forEach { yayoi ->
                    InputChip(
                        selected = true,
                        onClick = { onUnlink(yayoi) },
                        label = { Text(yayoi.accountName, fontSize = 13.sp) },
                        trailingIcon = { Icon(Icons.Default.Close, "解除", Modifier.size(16.dp)) }
                    )
                }
            }
        } else if (suggestion != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                suggestion.basis.label + if (suggestion.isUnambiguous) "" else "（候補が複数）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestion.candidates.forEach { yayoi ->
                    SuggestionChip(
                        onClick = { onConfirm(yayoi) },
                        label = { Text(yayoi.accountName, fontSize = 13.sp) }
                    )
                }
            }
        }
    }
}

/**
 * 弥生科目を手で選ぶ。すでに別の accountKey に紐付いている科目も、
 * 紐付け先を見せたうえで選べるようにする（弥生科目 1 つは 1 つの accountKey にしか付かないので付け替えになる）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YayoiAccountPickerDialog(
    target: AoiroChoboAccount,
    yayoiAccounts: List<YayoiAccount>,
    aoiroNameOf: (String) -> String,
    onSelect: (YayoiAccount) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, yayoiAccounts) {
        val q = query.trim()
        yayoiAccounts
            .filter { it.isEnabled }
            .filter {
                q.isEmpty() ||
                    it.accountName.contains(q, ignoreCase = true) ||
                    it.searchKeyAlpha.contains(q, ignoreCase = true)
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${target.name}」に対応する弥生科目", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("科目名・サーチキーで絞る") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { yayoi ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(yayoi) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(yayoi.accountName, fontSize = 15.sp)
                            val sub = buildString {
                                append(yayoi.categoryA)
                                if (yayoi.searchKeyAlpha.isNotBlank()) append("  ${yayoi.searchKeyAlpha}")
                                yayoi.accountKey?.let { append("  → ${aoiroNameOf(it)} に紐付け済み") }
                            }
                            Text(
                                sub,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}
