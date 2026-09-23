package com.example.greenframeocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.RakurakuTekiyou
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.util.AoiroChoboMemoMapping
import kotlinx.coroutines.launch

/**
 * らくらくの摘要辞書に AoiroChobo の `memoKey` を割り当てる画面。
 *
 * らくらくのサポート終了にともなう移行のための画面でもある。ここで確定した摘要は、
 * それを指している学習（商品名→摘要・通帳パターン→摘要・預金の個別上書き）にも
 * その場で書き下ろされる。全部済めば `rakuraku_tekiyou` を消しても学習は残る。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AoiroChoboMemoMappingScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var tekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var memoTemplates by remember { mutableStateOf<List<AoiroChoboMemoTemplate>>(emptyList()) }
    var referenceCounts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var notYetMigrated by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pickerFor by remember { mutableStateOf<RakurakuTekiyou?>(null) }

    fun reload() {
        scope.launch {
            val dao = database.rakurakuTekiyouDao()
            tekiyouList = dao.getAll()
            memoTemplates = database.aoiroChoboVocabDao().getAllMemoTemplates()
            referenceCounts = tekiyouList.associate { it.id to dao.countLearningReferences(it.id) }
            notYetMigrated = dao.countLearningNotYetMigrated()
            loaded = true
        }
    }

    LaunchedEffect(Unit) { reload() }

    val suggestions = remember(tekiyouList, memoTemplates) {
        AoiroChoboMemoMapping.suggest(tekiyouList, memoTemplates)
    }
    val autoConfirmable = remember(suggestions) {
        AoiroChoboMemoMapping.autoConfirmable(suggestions)
    }
    val memoByKey = remember(memoTemplates) { memoTemplates.associateBy { it.memoKey } }

    val categories = remember(tekiyouList) {
        tekiyouList.map { it.mainCategory to it.subCategory }.distinct()
    }
    LaunchedEffect(categories) {
        if (categories.isNotEmpty() && selectedCategory !in categories) {
            selectedCategory = categories.first()
        }
    }
    val rows = remember(tekiyouList, selectedCategory) {
        tekiyouList.filter { (it.mainCategory to it.subCategory) == selectedCategory }
    }

    fun link(tekiyou: RakurakuTekiyou, memo: AoiroChoboMemoTemplate) {
        scope.launch {
            database.rakurakuTekiyouDao().linkMemoKey(tekiyou.id, memo.memoKey, memo.name)
            reload()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("あおいろ帳簿 — 摘要マッピング") },
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

                memoTemplates.isEmpty() -> MemoNotImportedYet()

                else -> {
                    MemoMappingProgress(
                        total = tekiyouList.size,
                        mapped = tekiyouList.count { it.memoKey != null },
                        notYetMigrated = notYetMigrated,
                        autoConfirmableCount = autoConfirmable.size,
                        onConfirmAll = {
                            scope.launch {
                                autoConfirmable.forEach { suggestion ->
                                    val memo = suggestion.candidates.first()
                                    database.rakurakuTekiyouDao()
                                        .linkMemoKey(suggestion.tekiyouId, memo.memoKey, memo.name)
                                }
                                reload()
                            }
                        }
                    )

                    ScrollableTabRow(
                        selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
                        edgePadding = 8.dp
                    ) {
                        categories.forEach { category ->
                            Tab(
                                selected = category == selectedCategory,
                                onClick = { selectedCategory = category },
                                text = { Text("${category.first}・${category.second}", fontSize = 13.sp) }
                            )
                        }
                    }

                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.id }) { tekiyou ->
                            MemoMappingRow(
                                tekiyou = tekiyou,
                                linked = tekiyou.memoKey?.let { memoByKey[it] },
                                suggestion = suggestions[tekiyou.id],
                                referenceCount = referenceCounts[tekiyou.id] ?: 0,
                                onConfirm = { memo -> link(tekiyou, memo) },
                                onUnlink = {
                                    scope.launch {
                                        database.rakurakuTekiyouDao().unlinkMemoKey(tekiyou.id)
                                        reload()
                                    }
                                },
                                onPick = { pickerFor = tekiyou }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
    }

    pickerFor?.let { tekiyou ->
        MemoTemplatePickerDialog(
            target = tekiyou,
            memoTemplates = memoTemplates,
            tekiyouList = tekiyouList,
            onSelect = { memo ->
                link(tekiyou, memo)
                pickerFor = null
            },
            onDismiss = { pickerFor = null }
        )
    }
}

@Composable
private fun MemoNotImportedYet() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("あおいろ帳簿の摘要がまだありません", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "設定 > データ管理 の「AoiroChobo 科目・摘要を取り込む」で " +
                "vocabulary.json を取り込むと、ここに摘要が並びます。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MemoMappingProgress(
    total: Int,
    mapped: Int,
    notYetMigrated: Int,
    autoConfirmableCount: Int,
    onConfirmAll: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("$total 件中 $mapped 件を紐付け済み", fontWeight = FontWeight.Medium)
        Text(
            if (notYetMigrated > 0) {
                "らくらくの摘要を指したままの学習が $notYetMigrated 件あります。" +
                    "紐付けるとその学習も一緒に移ります"
            } else {
                "学習はすべて移行済みです"
            },
            fontSize = 12.sp,
            color = if (notYetMigrated > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
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
private fun MemoMappingRow(
    tekiyou: RakurakuTekiyou,
    linked: AoiroChoboMemoTemplate?,
    suggestion: AoiroChoboMemoMapping.Suggestion?,
    referenceCount: Int,
    onConfirm: (AoiroChoboMemoTemplate) -> Unit,
    onUnlink: () -> Unit,
    onPick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tekiyou.tekiyouName, fontWeight = FontWeight.Medium)
                val sub = buildString {
                    append(tekiyou.kamoku)
                    if (tekiyou.taxRate.isNotBlank()) append("  ${tekiyou.taxRate}")
                    tekiyou.businessRatio?.let { append("  事業割合 $it%") }
                    if (referenceCount > 0) append("  学習 ${referenceCount}件が参照")
                }
                Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onPick) { Text(if (linked == null) "選ぶ" else "変更") }
        }

        if (linked != null) {
            Spacer(Modifier.height(4.dp))
            InputChip(
                selected = true,
                onClick = onUnlink,
                label = { Text(linked.name, fontSize = 13.sp) },
                trailingIcon = { Icon(Icons.Default.Close, "解除", Modifier.size(16.dp)) }
            )
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
                suggestion.candidates.forEach { memo ->
                    SuggestionChip(
                        onClick = { onConfirm(memo) },
                        label = { Text(memo.name, fontSize = 13.sp) }
                    )
                }
            }
        }
    }
}

/**
 * AoiroChobo の摘要を手で選ぶ。帳簿・向きが一致するものを先に出し、
 * それ以外も畳まずに続けて出す（らくらくに無い未払帳・振替の摘要を選びたいことがある）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoTemplatePickerDialog(
    target: RakurakuTekiyou,
    memoTemplates: List<AoiroChoboMemoTemplate>,
    tekiyouList: List<RakurakuTekiyou>,
    onSelect: (AoiroChoboMemoTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val ledger = remember(target) { AoiroChoboMemoMapping.ledgerOf(target) }
    val linkedNames = remember(tekiyouList) {
        tekiyouList.filter { it.memoKey != null }.associate { it.memoKey!! to it.tekiyouName }
    }

    val ordered = remember(query, memoTemplates, ledger) {
        val q = query.trim()
        memoTemplates
            .filter {
                q.isEmpty() ||
                    it.name.contains(q, ignoreCase = true) ||
                    it.searchKey.contains(q, ignoreCase = true)
            }
            .sortedByDescending { (it.ledgerType to it.direction) == ledger }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${target.tekiyouName}」に対応する摘要", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("摘要名・検索文字で絞る") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(ordered, key = { it.memoKey }) { memo ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(memo) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(memo.name, fontSize = 15.sp)
                            val sub = buildString {
                                append("${memo.ledgerType}/${memo.direction.ifBlank { "—" }}")
                                memo.counterAccountKey?.let { append("  $it") }
                                memo.taxRate?.let { append("  $it") }
                                linkedNames[memo.memoKey]?.let { append("  → 「$it」に紐付け済み") }
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
