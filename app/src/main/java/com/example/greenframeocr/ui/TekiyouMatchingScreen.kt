package com.example.greenframeocr.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.*
import com.example.greenframeocr.util.importTekiyouFromCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 摘要マッチング画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TekiyouMatchingScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // State
    var matchingRules by remember { mutableStateOf<List<MatchingRuleWithTekiyou>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showEditDialog by remember { mutableStateOf(false) }
    var selectedRule by remember { mutableStateOf<MatchingRuleWithTekiyou?>(null) }
    var rakurakuTekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var activePatterns by remember { mutableStateOf<Set<String>>(emptySet()) }  // 通帳データに存在するパターン

    // フィルタ
    var filterType by remember { mutableStateOf<Boolean?>(null) } // null=全て, true=入金, false=出金
    var showOnlyWithData by remember { mutableStateOf(false) }    // 通帳データありのみ表示
    var showOnlyUnmatched by remember { mutableStateOf(false) }   // 未マッチのみ表示

    // スクロール状態を保持
    val listState = rememberLazyListState()

    // 統計情報
    val filteredRules = remember(matchingRules, filterType, showOnlyWithData, showOnlyUnmatched, activePatterns) {
        matchingRules.filter { rule ->
            val typeMatch = when (filterType) {
                true -> rule.isDeposit
                false -> !rule.isDeposit
                null -> true
            }
            val dataMatch = if (showOnlyWithData) rule.pattern in activePatterns else true
            val unmatchedMatch = if (showOnlyUnmatched) rule.rakurakuTekiyouId == null else true
            typeMatch && dataMatch && unmatchedMatch
        }
    }
    val matchedCount = filteredRules.count { it.rakurakuTekiyouId != null }
    val totalCount = filteredRules.size
    val depositCount = matchingRules.count { it.isDeposit }
    val withdrawalCount = matchingRules.count { !it.isDeposit }

    fun loadData() {
        scope.launch {
            isLoading = true
            matchingRules = database.tekiyouMatchingRuleDao().getAllWithTekiyou()
            // 預金カテゴリの有効な摘要のみ取得
            rakurakuTekiyouList = database.rakurakuTekiyouDao().getEnabledByCategory("預金", "入金") +
                                  database.rakurakuTekiyouDao().getEnabledByCategory("預金", "出金")
            // 通帳データに存在するパターンを取得
            val allMeisai = database.depositMeisaiDao().getAll()
            activePatterns = allMeisai.map { meisai ->
                val normalized = normalizeTekiyou(meisai.tekiyou)
                val suffix = if (meisai.amount >= 0) "_D" else "_W"
                normalized + suffix
            }.toSet()
            isLoading = false
        }
    }

    // 初期化：CSVインポート＆パターン抽出
    LaunchedEffect(Unit) {
        // 摘要辞書CSVの差分インポート
        importTekiyouFromCsv(context, database)

        // 通帳データCSVをインポート（初回のみ）
        val meisaiCount = database.depositMeisaiDao().getCount()
        if (meisaiCount == 0) {
            importMeisaiFromCsv(context, database)
        }

        // ルールを更新（既存のマッチング情報は保持）
        updateRulesFromMeisai(database)

        loadData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通帳摘要別リスト") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                actions = {
                    // 通帳データ再読み込み（マッチングルールは保持）
                    IconButton(onClick = {
                        scope.launch {
                            database.depositMeisaiDao().deleteAll()
                            // マッチングルールは削除しない（らくらく摘要との紐付けを保持）
                            importMeisaiFromCsv(context, database)
                            updateRulesFromMeisai(database)  // 新パターン追加 & 件数更新
                            loadData()
                        }
                    }) {
                        Icon(Icons.Default.Refresh, "通帳再読込")
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
            // 統計情報
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("合計", fontSize = 12.sp)
                        Text("$totalCount", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("マッチ済", fontSize = 12.sp)
                        Text(
                            "$matchedCount",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("未マッチ", fontSize = 12.sp)
                        Text(
                            "${totalCount - matchedCount}",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (totalCount - matchedCount > 0) MaterialTheme.colorScheme.error else Color.Gray
                        )
                    }
                }
            }

            // フィルタチップ
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterType == null,
                    onClick = { filterType = null },
                    label = { Text("全て (${matchingRules.size})") }
                )
                FilterChip(
                    selected = filterType == true,
                    onClick = { filterType = true },
                    label = { Text("入金 ($depositCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.2f)
                    )
                )
                FilterChip(
                    selected = filterType == false,
                    onClick = { filterType = false },
                    label = { Text("出金 ($withdrawalCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE53935).copy(alpha = 0.2f)
                    )
                )
            }

            // フィルタチェックボックス行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 通帳データありのみ
                Checkbox(
                    checked = showOnlyWithData,
                    onCheckedChange = { showOnlyWithData = it }
                )
                Text(
                    text = "通帳データあり",
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { showOnlyWithData = !showOnlyWithData }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 未マッチのみ
                Checkbox(
                    checked = showOnlyUnmatched,
                    onCheckedChange = { showOnlyUnmatched = it }
                )
                Text(
                    text = "未マッチのみ",
                    fontSize = 13.sp,
                    color = if (showOnlyUnmatched) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { showOnlyUnmatched = !showOnlyUnmatched }
                )

                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "表示: ${filteredRules.size}件",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Divider()

            // リスト表示
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredRules, key = { it.id }) { rule ->
                        MatchingRuleCard(
                            rule = rule,
                            onClick = {
                                selectedRule = rule
                                showEditDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    // 編集ダイアログ
    if (showEditDialog && selectedRule != null) {
        MatchingRuleEditDialog(
            rule = selectedRule!!,
            rakurakuTekiyouList = rakurakuTekiyouList,
            onDismiss = {
                showEditDialog = false
                selectedRule = null
            },
            onSave = { ruleId, tekiyouId ->
                scope.launch {
                    val existingRule = database.tekiyouMatchingRuleDao().getById(ruleId)
                    existingRule?.let {
                        database.tekiyouMatchingRuleDao().update(
                            it.copy(rakurakuTekiyouId = tekiyouId)
                        )
                    }
                    loadData()
                }
                showEditDialog = false
                selectedRule = null
            }
        )
    }
}

/**
 * マッチングルールカード
 */
@Composable
private fun MatchingRuleCard(
    rule: MatchingRuleWithTekiyou,
    onClick: () -> Unit
) {
    val isMatched = rule.rakurakuTekiyouId != null
    val typeColor = if (rule.isDeposit) Color(0xFF4CAF50) else Color(0xFFE53935)
    val typeLabel = if (rule.isDeposit) "入金" else "出金"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isMatched)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 入金/出金ラベル
            Surface(
                color = typeColor.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = typeLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = typeColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // マッチング状態アイコン
            Icon(
                imageVector = if (isMatched) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (isMatched) "マッチ済" else "未マッチ",
                tint = if (isMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 摘要パターン情報
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.normalizedTekiyou,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    if (rule.sampleText.isNotEmpty() && rule.sampleText != rule.normalizedTekiyou) {
                        Text(
                            text = "例: ${rule.sampleText}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "${rule.matchCount}件",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 矢印
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // マッチング先
            Column(
                modifier = Modifier.widthIn(max = 120.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (isMatched) {
                    Text(
                        text = rule.rakurakuTekiyouName ?: "",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = rule.kamoku ?: "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "未設定",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "編集",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * マッチングルール編集ダイアログ
 * 預金カテゴリのみ表示、入金/出金は自動判定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchingRuleEditDialog(
    rule: MatchingRuleWithTekiyou,
    rakurakuTekiyouList: List<RakurakuTekiyou>,
    onDismiss: () -> Unit,
    onSave: (ruleId: Int, tekiyouId: Int?) -> Unit
) {
    var selectedTekiyouId by remember { mutableStateOf(rule.rakurakuTekiyouId) }
    var searchText by remember { mutableStateOf("") }

    // 入金/出金に応じてフィルタ
    val subCategory = if (rule.isDeposit) "入金" else "出金"
    val typeLabel = if (rule.isDeposit) "入金" else "出金"
    val typeColor = if (rule.isDeposit) Color(0xFF4CAF50) else Color(0xFFE53935)

    // フィルタリングされた摘要リスト（預金の入金or出金のみ）
    val filteredTekiyouList = remember(rakurakuTekiyouList, subCategory, searchText) {
        rakurakuTekiyouList.filter { tekiyou ->
            tekiyou.mainCategory == "預金" &&
            tekiyou.subCategory == subCategory &&
            (searchText.isEmpty() ||
             tekiyou.tekiyouName.contains(searchText, ignoreCase = true) ||
             tekiyou.searchKey.contains(searchText, ignoreCase = true) ||
             tekiyou.kamoku.contains(searchText, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.normalizedTekiyou,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = typeColor.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = typeLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = typeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 検索
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "検索") }
                )

                Text(
                    text = "預金-$subCategory の摘要から選択 (${filteredTekiyouList.size}件)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Divider()

                // 摘要リスト
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // マッチなし選択肢
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTekiyouId = null }
                                .background(
                                    if (selectedTekiyouId == null)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTekiyouId == null,
                                onClick = { selectedTekiyouId = null }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("(マッチなし)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    items(filteredTekiyouList, key = { it.id }) { tekiyou ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTekiyouId = tekiyou.id }
                                .background(
                                    if (selectedTekiyouId == tekiyou.id)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTekiyouId == tekiyou.id,
                                onClick = { selectedTekiyouId = tekiyou.id }
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
            TextButton(onClick = { onSave(rule.id, selectedTekiyouId) }) {
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
 * 預金明細CSVをインポート
 */
private suspend fun importMeisaiFromCsv(context: Context, database: ReceiptDatabase) {
    withContext(Dispatchers.IO) {
        try {
            val inputStream = context.assets.open("meisai.csv")
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            val meisaiList = mutableListOf<DepositMeisai>()

            var isFirstLine = true
            reader.forEachLine { line ->
                if (isFirstLine) {
                    isFirstLine = false
                    return@forEachLine
                }

                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) return@forEachLine

                // CSVパース（簡易版）
                val cells = trimmedLine.split(",").map { it.trim() }
                if (cells.size >= 4) {
                    val date = cells[0]
                    val number = cells[1]
                    val tekiyou = cells[2]
                    val amount = cells[3].toIntOrNull() ?: 0
                    val memo = cells.getOrNull(4) ?: ""

                    if (date.isNotEmpty() && tekiyou.isNotEmpty()) {
                        meisaiList.add(
                            DepositMeisai(
                                transactionDate = date,
                                transactionNumber = number,
                                tekiyou = tekiyou,
                                amount = amount,
                                memo = memo
                            )
                        )
                    }
                }
            }
            reader.close()

            if (meisaiList.isNotEmpty()) {
                database.depositMeisaiDao().insertAll(meisaiList)
            }
            Unit
        } catch (e: Exception) {
            android.util.Log.e("TekiyouMatchingScreen", "Failed to import meisai CSV", e)
            Unit
        }
    }
}

/**
 * 通帳データからルールを更新（既存のマッチング情報は保持）
 * - 新しいパターンは追加
 * - 既存パターンはmatchCountのみ更新
 * - らくらく摘要との紐付けは保持
 */
private suspend fun updateRulesFromMeisai(database: ReceiptDatabase) {
    withContext(Dispatchers.IO) {
        try {
            val allMeisai = database.depositMeisaiDao().getAll()

            // パターンをグルーピング
            data class PatternKey(val normalized: String, val isDeposit: Boolean)
            val patternGroups = mutableMapOf<PatternKey, MutableList<DepositMeisai>>()

            for (meisai in allMeisai) {
                val normalized = normalizeTekiyou(meisai.tekiyou)
                val isDeposit = meisai.amount >= 0
                val key = PatternKey(normalized, isDeposit)
                patternGroups.getOrPut(key) { mutableListOf() }.add(meisai)
            }

            // ルールを更新または作成
            for ((key, samples) in patternGroups) {
                val patternStr = key.normalized + "_" + if (key.isDeposit) "D" else "W"
                val existingRule = database.tekiyouMatchingRuleDao().getByPattern(patternStr)

                if (existingRule != null) {
                    // 既存ルール: matchCountとsampleTextのみ更新（マッチング情報は保持）
                    database.tekiyouMatchingRuleDao().update(
                        existingRule.copy(
                            matchCount = samples.size,
                            sampleText = samples.firstOrNull()?.tekiyou ?: existingRule.sampleText
                        )
                    )
                } else {
                    // 新規ルール: 追加
                    database.tekiyouMatchingRuleDao().insert(
                        TekiyouMatchingRule(
                            pattern = patternStr,
                            normalizedTekiyou = key.normalized,
                            isRegex = samples.size > 1,
                            sampleText = samples.firstOrNull()?.tekiyou ?: "",
                            matchCount = samples.size,
                            isDeposit = key.isDeposit
                        )
                    )
                }
            }
            Unit
        } catch (e: Exception) {
            android.util.Log.e("TekiyouMatchingScreen", "Failed to update rules", e)
            Unit
        }
    }
}

/**
 * ユニークな摘要パターンを抽出してルールを作成
 * 金額の符号から入金/出金を判定
 */
private suspend fun extractAndCreateRules(database: ReceiptDatabase) {
    withContext(Dispatchers.IO) {
        try {
            val allMeisai = database.depositMeisaiDao().getAll()

            // パターンをグルーピング（正規化テキスト + 入出金タイプ）
            data class PatternKey(val normalized: String, val isDeposit: Boolean)
            val patternGroups = mutableMapOf<PatternKey, MutableList<DepositMeisai>>()

            for (meisai in allMeisai) {
                // 正規化：末尾の数字部分を除去
                val normalized = normalizeTekiyou(meisai.tekiyou)
                val isDeposit = meisai.amount >= 0
                val key = PatternKey(normalized, isDeposit)
                patternGroups.getOrPut(key) { mutableListOf() }.add(meisai)
            }

            // ルールを作成
            for ((key, samples) in patternGroups) {
                val existingRule = database.tekiyouMatchingRuleDao().getByPattern(key.normalized + "_" + if (key.isDeposit) "D" else "W")
                if (existingRule == null) {
                    database.tekiyouMatchingRuleDao().insert(
                        TekiyouMatchingRule(
                            pattern = key.normalized + "_" + if (key.isDeposit) "D" else "W",
                            normalizedTekiyou = key.normalized,
                            isRegex = samples.size > 1,
                            sampleText = samples.firstOrNull()?.tekiyou ?: "",
                            matchCount = samples.size,
                            isDeposit = key.isDeposit
                        )
                    )
                }
            }
            Unit
        } catch (e: Exception) {
            android.util.Log.e("TekiyouMatchingScreen", "Failed to extract patterns", e)
            Unit
        }
    }
}

/**
 * 摘要を正規化（末尾の数字やスペースを除去）
 */
private fun normalizeTekiyou(tekiyou: String): String {
    // 末尾の数字（日付など）を除去
    // 例: "いんげん インゲン   1029" -> "いんげん インゲン"
    // 例: "電気料 デンリヨク 07-09" -> "電気料 デンリヨク"
    return tekiyou
        .replace(Regex("\\s+\\d{2}-\\d{2}$"), "")  // " 07-09" を除去
        .replace(Regex("\\s+\\d{4}$"), "")          // " 1029" を除去
        .replace(Regex("\\s+$"), "")                 // 末尾スペースを除去
        .trim()
}
