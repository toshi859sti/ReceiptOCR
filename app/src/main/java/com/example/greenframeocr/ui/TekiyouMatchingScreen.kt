package com.example.greenframeocr.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.example.greenframeocr.data.AccountingSoftware
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.util.importTekiyouFromCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

/**
 * 摘要マッチング画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TekiyouMatchingScreen(
    database: ReceiptDatabase,
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val accountingSoftware = appPreferences.accountingSoftware
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // State
    var matchingRules by remember { mutableStateOf<List<MatchingRuleWithTekiyou>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showEditDialog by remember { mutableStateOf(false) }
    var selectedRule by remember { mutableStateOf<MatchingRuleWithTekiyou?>(null) }
    var rakurakuTekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var yayoiAccountList by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var activePatterns by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 展開状態・個別アイテム
    var expandedRuleIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var meisaiByRuleId by remember { mutableStateOf<Map<Int, List<DepositMeisaiWithOverride>>>(emptyMap()) }
    var showIndividualDialog by remember { mutableStateOf(false) }
    var selectedMeisai by remember { mutableStateOf<DepositMeisaiWithOverride?>(null) }
    var selectedMeisaiGroupKamoku by remember { mutableStateOf<String?>(null) }

    // フィルタ
    var filterType by remember { mutableStateOf<Boolean?>(null) }
    var showOnlyWithData by remember { mutableStateOf(false) }
    var showOnlyUnmatched by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    fun isRuleMatched(rule: MatchingRuleWithTekiyou) = when (accountingSoftware) {
        AccountingSoftware.YAYOI -> rule.yayoiAccountId != null
        AccountingSoftware.BLUE_RETURN_PREP -> false
        else -> rule.rakurakuTekiyouId != null
    }

    val filteredRules = remember(matchingRules, filterType, showOnlyWithData, showOnlyUnmatched, activePatterns) {
        matchingRules.filter { rule ->
            val typeMatch = when (filterType) {
                true -> rule.isDeposit
                false -> !rule.isDeposit
                null -> true
            }
            val dataMatch = if (showOnlyWithData) rule.pattern in activePatterns else true
            val unmatchedMatch = if (showOnlyUnmatched) !isRuleMatched(rule) else true
            typeMatch && dataMatch && unmatchedMatch
        }
    }
    val matchedCount = filteredRules.count { isRuleMatched(it) }
    val totalCount = filteredRules.size
    val depositCount = matchingRules.count { it.isDeposit }
    val withdrawalCount = matchingRules.count { !it.isDeposit }

    fun loadMeisaiForRule(ruleId: Int) {
        scope.launch {
            val items = database.depositMeisaiDao().getAllWithOverrideByRuleId(ruleId)
            meisaiByRuleId = meisaiByRuleId + (ruleId to items)
        }
    }

    fun loadData() {
        scope.launch {
            isLoading = true
            matchingRules = database.tekiyouMatchingRuleDao().getAllWithTekiyou()
            rakurakuTekiyouList = database.rakurakuTekiyouDao().getEnabledByCategory("預金", "入金") +
                                  database.rakurakuTekiyouDao().getEnabledByCategory("預金", "出金")
            val allYayoi = database.yayoiAccountDao().getAll().filter { it.isEnabled }
            yayoiAccountList = allYayoi
            val allMeisai = database.depositMeisaiDao().getAll()
            activePatterns = allMeisai.map { meisai ->
                val normalized = normalizeTekiyou(meisai.tekiyou)
                val suffix = if (meisai.amount >= 0) "_D" else "_W"
                normalized + suffix
            }.toSet()
            // 展開中のグループのアイテムを再読み込み
            val updated = mutableMapOf<Int, List<DepositMeisaiWithOverride>>()
            for (ruleId in expandedRuleIds) {
                updated[ruleId] = database.depositMeisaiDao().getAllWithOverrideByRuleId(ruleId)
            }
            meisaiByRuleId = updated
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        importTekiyouFromCsv(context, database)
        val meisaiCount = database.depositMeisaiDao().getCount()
        if (meisaiCount == 0) {
            importMeisaiFromCsv(context, database)
        }
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
                    IconButton(onClick = {
                        scope.launch {
                            database.depositMeisaiDao().deleteAll()
                            importMeisaiFromCsv(context, database)
                            updateRulesFromMeisai(database)
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
                        val isExpanded = rule.id in expandedRuleIds
                        MatchingRuleCard(
                            rule = rule,
                            accountingSoftware = accountingSoftware,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                if (isExpanded) {
                                    expandedRuleIds = expandedRuleIds - rule.id
                                } else {
                                    expandedRuleIds = expandedRuleIds + rule.id
                                    loadMeisaiForRule(rule.id)
                                }
                            },
                            onEditGroup = if (accountingSoftware != AccountingSoftware.BLUE_RETURN_PREP) ({
                                selectedRule = rule
                                showEditDialog = true
                            }) else null,
                            meisaiItems = if (accountingSoftware == AccountingSoftware.RAKURAKU) meisaiByRuleId[rule.id] else null,
                            onEditIndividual = if (accountingSoftware == AccountingSoftware.RAKURAKU) ({ meisai ->
                                selectedMeisai = meisai
                                selectedMeisaiGroupKamoku = rule.kamoku
                                showIndividualDialog = true
                            }) else null
                        )
                    }
                }
            }
        }
    }

    // グループ編集ダイアログ（全件上書き）
    if (showEditDialog && selectedRule != null) {
        MatchingRuleEditDialog(
            rule = selectedRule!!,
            accountingSoftware = accountingSoftware,
            rakurakuTekiyouList = rakurakuTekiyouList,
            yayoiAccountList = yayoiAccountList,
            flaggedYayoiList = yayoiAccountList.filter { it.usedForDeposit },
            onDismiss = {
                showEditDialog = false
                selectedRule = null
            },
            onSave = { ruleId, rakurakuTekiyouId, yayoiAccountId ->
                scope.launch {
                    if (accountingSoftware == AccountingSoftware.RAKURAKU) {
                        database.depositMeisaiDao().clearOverridesForRule(ruleId)
                    }
                    val existingRule = database.tekiyouMatchingRuleDao().getById(ruleId)
                    existingRule?.let {
                        database.tekiyouMatchingRuleDao().update(
                            it.copy(
                                rakurakuTekiyouId = rakurakuTekiyouId,
                                yayoiAccountId = yayoiAccountId
                            )
                        )
                    }
                    loadData()
                }
                showEditDialog = false
                selectedRule = null
            }
        )
    }

    // 個別オーバーライドダイアログ
    if (showIndividualDialog && selectedMeisai != null) {
        IndividualOverrideDialog(
            meisai = selectedMeisai!!,
            groupKamoku = selectedMeisaiGroupKamoku,
            rakurakuTekiyouList = rakurakuTekiyouList,
            onDismiss = {
                showIndividualDialog = false
                selectedMeisai = null
            },
            onSave = { meisaiId, tekiyouId ->
                val ruleId = selectedMeisai?.matchingRuleId
                scope.launch {
                    database.depositMeisaiDao().updateOverrideTekiyou(meisaiId, tekiyouId)
                    if (ruleId != null) {
                        val items = database.depositMeisaiDao().getAllWithOverrideByRuleId(ruleId)
                        meisaiByRuleId = meisaiByRuleId + (ruleId to items)
                    }
                }
                showIndividualDialog = false
                selectedMeisai = null
            }
        )
    }
}

/**
 * マッチングルールカード（展開/折りたたみ対応）
 */
@Composable
private fun MatchingRuleCard(
    rule: MatchingRuleWithTekiyou,
    accountingSoftware: AccountingSoftware,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEditGroup: (() -> Unit)?,
    meisaiItems: List<DepositMeisaiWithOverride>?,
    onEditIndividual: ((DepositMeisaiWithOverride) -> Unit)?
) {
    val isMatched = when (accountingSoftware) {
        AccountingSoftware.YAYOI -> rule.yayoiAccountId != null
        AccountingSoftware.BLUE_RETURN_PREP -> false
        else -> rule.rakurakuTekiyouId != null
    }
    val isBrp = accountingSoftware == AccountingSoftware.BLUE_RETURN_PREP
    val typeColor = if (rule.isDeposit) Color(0xFF4CAF50) else Color(0xFFE53935)
    val typeLabel = if (rule.isDeposit) "入金" else "出金"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isMatched)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column {
            // グループヘッダー行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 展開トグルアイコン
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "折りたたむ" else "展開する",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

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
                    when {
                        isBrp -> Text(
                            "Windows側で管理",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                        isMatched && accountingSoftware == AccountingSoftware.YAYOI -> {
                            Text(
                                rule.yayoiAccountName ?: "",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                rule.yayoiAccountCode ?: "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                        isMatched -> {
                            Text(
                                rule.rakurakuTekiyouName ?: "",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                rule.kamoku ?: "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        else -> Text(
                            "未設定",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // グループ編集アイコン（BRPは非表示）
                if (onEditGroup != null) {
                    IconButton(onClick = onEditGroup, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "グループ編集",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 展開時：個別明細リスト
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Divider(modifier = Modifier.padding(horizontal = 8.dp))
                    when {
                        meisaiItems == null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                        meisaiItems.isEmpty() -> {
                            Text(
                                text = "データなし",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        else -> {
                            meisaiItems.forEach { meisai ->
                                DepositMeisaiItemRow(
                                    meisai = meisai,
                                    groupKamoku = rule.kamoku,
                                    onClick = onEditIndividual?.let { { onEditIndividual(meisai) } } ?: {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 個別明細行（展開時に表示）
 */
@Composable
private fun DepositMeisaiItemRow(
    meisai: DepositMeisaiWithOverride,
    groupKamoku: String?,
    onClick: () -> Unit
) {
    val isOverridden = meisai.overrideTekiyouId != null
    val effectiveKamoku = if (isOverridden) meisai.overrideKamoku else groupKamoku
    val amountColor = if (meisai.amount >= 0) Color(0xFF4CAF50) else Color(0xFFE53935)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 日付 (MM/dd)
        val dateDisplay = meisai.transactionDate.let {
            if (it.length == 10) "${it.substring(5, 7)}/${it.substring(8, 10)}" else it
        }
        Text(
            text = dateDisplay,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 摘要（原文）
        Text(
            text = meisai.tekiyou,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 金額
        Text(
            text = "¥${String.format("%,d", abs(meisai.amount))}",
            fontSize = 13.sp,
            color = amountColor,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 有効勘定科目（オーバーライド時は色を変えてアイコン表示）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = effectiveKamoku ?: "未設定",
                fontSize = 11.sp,
                color = if (isOverridden) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 80.dp)
            )
            if (isOverridden) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "個別変更済み",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/**
 * グループ編集ダイアログ（全件上書き・個別変更リセット）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchingRuleEditDialog(
    rule: MatchingRuleWithTekiyou,
    accountingSoftware: AccountingSoftware,
    rakurakuTekiyouList: List<RakurakuTekiyou>,
    yayoiAccountList: List<YayoiAccount>,        // 全有効科目
    flaggedYayoiList: List<YayoiAccount>,         // usedForDeposit=true の科目
    onDismiss: () -> Unit,
    onSave: (ruleId: Int, rakurakuTekiyouId: Int?, yayoiAccountId: Long?) -> Unit
) {
    var selectedTekiyouId by remember { mutableStateOf(rule.rakurakuTekiyouId) }
    var selectedYayoiAccountId by remember { mutableStateOf(rule.yayoiAccountId) }
    var searchText by remember { mutableStateOf("") }

    val subCategory = if (rule.isDeposit) "入金" else "出金"
    val typeLabel = if (rule.isDeposit) "入金" else "出金"
    val typeColor = if (rule.isDeposit) Color(0xFF4CAF50) else Color(0xFFE53935)
    val isYayoi = accountingSoftware == AccountingSoftware.YAYOI

    // 弥生モード用フラグ優先ロジック
    val hasFlaggedYayoi = flaggedYayoiList.isNotEmpty()
    var showAllYayoi by remember { mutableStateOf(!hasFlaggedYayoi) }
    var selectedCategoryA by remember { mutableStateOf<String?>(if (hasFlaggedYayoi) null else null) }

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

    val categoryAList = remember(yayoiAccountList) {
        yayoiAccountList.map { it.categoryA }.distinct().filter { it.isNotBlank() }.sorted()
    }

    val baseYayoiList = if (showAllYayoi) yayoiAccountList else flaggedYayoiList

    val filteredYayoiList = remember(baseYayoiList, selectedCategoryA, searchText, showAllYayoi) {
        baseYayoiList.filter { acc ->
            (showAllYayoi.not() || selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText, ignoreCase = true) == true) ||
             acc.searchKeyAlpha.contains(searchText, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.normalizedTekiyou,
                        fontSize = 18.sp,
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
                Text(
                    text = "保存するとグループ全件に適用され、個別変更はリセットされます",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 弥生モード：フラグ優先トグル + 区分Aフィルター
                if (isYayoi) {
                    if (hasFlaggedYayoi) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !showAllYayoi,
                                onClick = { showAllYayoi = false; selectedCategoryA = null },
                                label = { Text("預金フラグのみ (${flaggedYayoiList.size}件)", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = showAllYayoi,
                                onClick = { showAllYayoi = true },
                                label = { Text("全科目", fontSize = 12.sp) }
                            )
                        }
                    }
                    if (showAllYayoi) {
                        LazyRow(
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
                }

                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "検索") }
                )

                Text(
                    text = if (isYayoi) "弥生勘定科目から選択 (${filteredYayoiList.size}件)"
                           else "預金-$subCategory の摘要から選択 (${filteredTekiyouList.size}件)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Divider()

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // (マッチなし) 行
                    item {
                        val noneSelected = if (isYayoi) selectedYayoiAccountId == null else selectedTekiyouId == null
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isYayoi) selectedYayoiAccountId = null else selectedTekiyouId = null
                                }
                                .background(if (noneSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = noneSelected, onClick = { if (isYayoi) selectedYayoiAccountId = null else selectedTekiyouId = null })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("(マッチなし)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (isYayoi) {
                        items(filteredYayoiList, key = { it.id }) { account ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedYayoiAccountId = account.id }
                                    .background(if (selectedYayoiAccountId == account.id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedYayoiAccountId == account.id, onClick = { selectedYayoiAccountId = account.id })
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
                    } else {
                        items(filteredTekiyouList, key = { it.id }) { tekiyou ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTekiyouId = tekiyou.id }
                                    .background(if (selectedTekiyouId == tekiyou.id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedTekiyouId == tekiyou.id, onClick = { selectedTekiyouId = tekiyou.id })
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tekiyou.tekiyouName, fontWeight = FontWeight.Medium)
                                    Row {
                                        Text(tekiyou.kamoku, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        tekiyou.businessRatio?.let { ratio ->
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("(${ratio}%)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (tekiyou.searchKey.isNotEmpty()) {
                                    Text(tekiyou.searchKey, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    rule.id,
                    if (isYayoi) rule.rakurakuTekiyouId else selectedTekiyouId,
                    if (isYayoi) selectedYayoiAccountId else rule.yayoiAccountId
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/**
 * 個別オーバーライドダイアログ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IndividualOverrideDialog(
    meisai: DepositMeisaiWithOverride,
    groupKamoku: String?,
    rakurakuTekiyouList: List<RakurakuTekiyou>,
    onDismiss: () -> Unit,
    onSave: (meisaiId: Int, tekiyouId: Int?) -> Unit
) {
    val isDeposit = meisai.amount >= 0
    val subCategory = if (isDeposit) "入金" else "出金"
    var selectedTekiyouId by remember { mutableStateOf(meisai.overrideTekiyouId) }
    var searchText by remember { mutableStateOf("") }

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
                Text("個別変更", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${meisai.transactionDate}  ${meisai.tekiyou}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "検索") }
                )

                Divider()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // グループのデフォルトに戻す
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
                            Column {
                                Text(
                                    "グループのデフォルトに戻す",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (groupKamoku != null) {
                                    Text(
                                        text = groupKamoku,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
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
            TextButton(onClick = { onSave(meisai.id, selectedTekiyouId) }) {
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
 */
private suspend fun updateRulesFromMeisai(database: ReceiptDatabase) {
    withContext(Dispatchers.IO) {
        try {
            val allMeisai = database.depositMeisaiDao().getAll()

            data class PatternKey(val normalized: String, val isDeposit: Boolean)
            val patternGroups = mutableMapOf<PatternKey, MutableList<DepositMeisai>>()

            for (meisai in allMeisai) {
                val normalized = normalizeTekiyou(meisai.tekiyou)
                val isDeposit = meisai.amount >= 0
                val key = PatternKey(normalized, isDeposit)
                patternGroups.getOrPut(key) { mutableListOf() }.add(meisai)
            }

            for ((key, samples) in patternGroups) {
                val patternStr = key.normalized + "_" + if (key.isDeposit) "D" else "W"
                val existingRule = database.tekiyouMatchingRuleDao().getByPattern(patternStr)

                val ruleId: Int
                if (existingRule != null) {
                    database.tekiyouMatchingRuleDao().update(
                        existingRule.copy(
                            matchCount = samples.size,
                            sampleText = samples.firstOrNull()?.tekiyou ?: existingRule.sampleText
                        )
                    )
                    ruleId = existingRule.id
                } else {
                    ruleId = database.tekiyouMatchingRuleDao().insert(
                        TekiyouMatchingRule(
                            pattern = patternStr,
                            normalizedTekiyou = key.normalized,
                            isRegex = samples.size > 1,
                            sampleText = samples.firstOrNull()?.tekiyou ?: "",
                            matchCount = samples.size,
                            isDeposit = key.isDeposit
                        )
                    ).toInt()
                }

                // deposit_meisai.matchingRuleId を書き戻す
                for (meisai in samples) {
                    if (meisai.matchingRuleId != ruleId) {
                        database.depositMeisaiDao().updateMatchingRule(meisai.id, ruleId)
                    }
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
 */
private suspend fun extractAndCreateRules(database: ReceiptDatabase) {
    withContext(Dispatchers.IO) {
        try {
            val allMeisai = database.depositMeisaiDao().getAll()

            data class PatternKey(val normalized: String, val isDeposit: Boolean)
            val patternGroups = mutableMapOf<PatternKey, MutableList<DepositMeisai>>()

            for (meisai in allMeisai) {
                val normalized = normalizeTekiyou(meisai.tekiyou)
                val isDeposit = meisai.amount >= 0
                val key = PatternKey(normalized, isDeposit)
                patternGroups.getOrPut(key) { mutableListOf() }.add(meisai)
            }

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
    return tekiyou
        .replace(Regex("\\s+\\d{2}-\\d{2}$"), "")
        .replace(Regex("\\s+\\d{4}$"), "")
        .replace(Regex("\\s+$"), "")
        .trim()
}
