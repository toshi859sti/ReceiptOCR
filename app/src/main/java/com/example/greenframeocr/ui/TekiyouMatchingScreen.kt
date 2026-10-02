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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.*
import com.example.greenframeocr.data.AccountingSoftware
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.util.AoiroChoboDepositRules
import com.example.greenframeocr.util.AoiroChoboUsageRules
import com.example.greenframeocr.util.GeminiApiException
import com.example.greenframeocr.util.RomajiSearch
import com.example.greenframeocr.util.GeminiApiKeyMissingException
import com.example.greenframeocr.util.GeminiQuotaExhaustedException
import com.example.greenframeocr.util.GeminiRateLimitException
import com.example.greenframeocr.util.GeminiReceiptClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// 摘要ルールの並び替え順。NAMEは既存のDAO取得順（入金/出金→五十音順）をそのまま使う
private enum class TekiyouSortOrder(val label: String) {
    NAME("五十音順"),
    COUNT("件数順"),
    UNMATCHED_FIRST("未マッチ優先")
}

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
    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }

    // State
    // 摘要パターン（摘要 → 科目）は全年で 1 つ。画面は選んだ年に明細があるパターンだけを、その年の件数で出す
    var allRules by remember { mutableStateOf<List<MatchingRuleWithTekiyou>>(emptyList()) }
    // 年 → パターン → その年の明細数（パターン＝正規化した摘要＋入金 _D／出金 _W）
    var patternCountsByYear by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var showEditDialog by remember { mutableStateOf(false) }
    var selectedRule by remember { mutableStateOf<MatchingRuleWithTekiyou?>(null) }
    var yayoiAccountList by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var activePatterns by remember { mutableStateOf<Set<String>>(emptySet()) }
    // あおいろ帳簿（相手科目・摘要は PC から取り込んだ辞書から選ぶ）
    val isAoiro = accountingSoftware == AccountingSoftware.AOIRO
    var aoiroAccounts by remember { mutableStateOf<List<AoiroChoboAccount>>(emptyList()) }
    var aoiroMemos by remember { mutableStateOf<List<AoiroChoboMemoTemplate>>(emptyList()) }
    var aoiroUsage by remember { mutableStateOf<List<AoiroChoboAccountUsage>>(emptyList()) }

    // 展開状態・個別アイテム
    var expandedRuleIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var meisaiByRuleId by remember { mutableStateOf<Map<Int, List<DepositMeisaiWithOverride>>>(emptyMap()) }
    var showIndividualDialog by remember { mutableStateOf(false) }
    var selectedMeisai by remember { mutableStateOf<DepositMeisaiWithOverride?>(null) }
    var selectedMeisaiGroupYayoiAccountName by remember { mutableStateOf<String?>(null) }

    // フィルタ・検索・並び替え
    var filterType by remember { mutableStateOf<Boolean?>(null) }
    var showOnlyWithData by remember { mutableStateOf(false) }
    var showOnlyUnmatched by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(TekiyouSortOrder.NAME) }
    var filterPanelExpanded by remember { mutableStateOf(false) }

    // AI提案（弥生モードのみ）
    var showAiTekiyouDialog by remember { mutableStateOf(false) }
    var aiTekiyouSuggestions by remember { mutableStateOf<List<GeminiReceiptClient.TekiyouMatchSuggestion>>(emptyList()) }
    var aiTekiyouUsageStats by remember { mutableStateOf<GeminiReceiptClient.AiUsageStats?>(null) }
    // あおいろの AI 提案。null = ダイアログを出さない、空 = 提案なし
    var aoiroAiSuggestions by remember { mutableStateOf<List<AiSuggestionRow<AoiroLinkSelection>>?>(null) }
    var isAiMatching by remember { mutableStateOf(false) }
    var aiMatchingError by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    // 年の絞り込み（null = 全年）。初期値は通帳データ画面と同じく作業年、その年が無ければ最新の年。
    // 「作業年で固定」が ON の間は作業年から動かさない
    val workingCalendarYear = remember { appPreferences.workingCalendarYear.toString() }
    val lockYearToWorking = remember { appPreferences.lockYearToWorking }
    val availableYears = remember(patternCountsByYear) { patternCountsByYear.keys.sortedDescending() }
    var selectedYear by remember(availableYears) {
        mutableStateOf(
            when {
                lockYearToWorking -> workingCalendarYear
                availableYears.contains(workingCalendarYear) -> workingCalendarYear
                else -> availableYears.firstOrNull()
            }
        )
    }
    // 年を選んでいるときは、その年に明細があるパターンだけ（件数はその年の分）。全年なら明細の無いパターンも出す
    val matchingRules = remember(allRules, patternCountsByYear, selectedYear) {
        val year = selectedYear
        if (year == null) allRules else {
            val counts = patternCountsByYear[year].orEmpty()
            allRules.mapNotNull { rule -> counts[rule.pattern]?.let { rule.copy(matchCount = it) } }
        }
    }

    fun isRuleMatched(rule: MatchingRuleWithTekiyou) = isRuleMatchedFor(rule, accountingSoftware)

    // あおいろの表示名は今の辞書から引く（PC で改名されていればそちら）。辞書から消えたキーは保存時の名前
    fun aoiroLabel(accountKey: String?, accountKeyName: String?, memoKey: String?, memoKeyName: String?): String? {
        accountKey ?: return null
        val account = aoiroAccounts.find { it.accountKey == accountKey }?.name ?: accountKeyName ?: accountKey
        val memo = memoKey?.let { key -> aoiroMemos.find { it.memoKey == key }?.name ?: memoKeyName ?: key }
        return if (memo != null) "$account ／ $memo" else account
    }

    val filteredRules = remember(matchingRules, filterType, showOnlyWithData, showOnlyUnmatched, activePatterns, searchText, sortOrder) {
        val filtered = matchingRules.filter { rule ->
            val typeMatch = when (filterType) {
                true -> rule.isDeposit
                false -> !rule.isDeposit
                null -> true
            }
            val dataMatch = if (showOnlyWithData) rule.pattern in activePatterns else true
            val unmatchedMatch = if (showOnlyUnmatched) !isRuleMatched(rule) else true
            val searchMatch = searchText.isBlank() ||
                rule.normalizedTekiyou.contains(searchText, ignoreCase = true) ||
                rule.sampleText.contains(searchText, ignoreCase = true)
            typeMatch && dataMatch && unmatchedMatch && searchMatch
        }
        when (sortOrder) {
            TekiyouSortOrder.NAME -> filtered // 既にDAOで入金/出金→五十音順にソート済み
            TekiyouSortOrder.COUNT -> filtered.sortedByDescending { it.matchCount }
            TekiyouSortOrder.UNMATCHED_FIRST -> filtered.sortedWith(compareBy({ isRuleMatched(it) }, { -it.matchCount }))
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
            allRules = database.tekiyouMatchingRuleDao().getAllWithTekiyou()
            val allYayoi = database.yayoiAccountDao().getAll().filter { it.isEnabled }
            yayoiAccountList = allYayoi
            if (isAoiro) {
                aoiroAccounts = database.aoiroChoboVocabDao().getAllAccounts()
                aoiroMemos = database.aoiroChoboVocabDao().getAllMemoTemplates()
                aoiroUsage = database.aoiroChoboAccountUsageDao().getAll()
            }
            val allMeisai = database.depositMeisaiDao().getAll()
            fun patternOf(meisai: DepositMeisai): String {
                val normalized = normalizeTekiyou(meisai.tekiyou)
                val suffix = if (meisai.amount >= 0) "_D" else "_W"
                return normalized + suffix
            }
            activePatterns = allMeisai.map(::patternOf).toSet()
            patternCountsByYear = allMeisai
                .filter { Regex("\\d{4}").matches(it.transactionDate.take(4)) }
                .groupBy { it.transactionDate.take(4) }
                .mapValues { (_, list) -> list.groupingBy(::patternOf).eachCount() }
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
        updateRulesFromMeisai(database)
        loadData()
    }

    // AI科目提案（あおいろ）。未マッチの摘要パターンに相手科目を提案させ、承認したものだけ保存する
    fun startAoiroAiMatching() {
        val unmatched = matchingRules.filter { it.accountKey == null }.take(60)
        if (unmatched.isEmpty()) {
            aiMatchingError = "未マッチングの摘要がありません"
            return
        }
        val accounts = AoiroChoboUsageRules.candidates(AoiroChoboUsageRules.Usage.DEPOSIT, aoiroAccounts, aoiroUsage)
        if (accounts.isEmpty()) {
            aiMatchingError = "あおいろ帳簿の科目がまだ取り込まれていません。設定画面から取り込んでください"
            return
        }
        // 摘要名は入金・出金の両方を渡す（パターンごとに向きが違うため）
        val memoNames = accounts.associate { a ->
            a.accountKey to (AoiroChoboDepositRules.memoCandidates(a.accountKey, true, aoiroMemos) +
                AoiroChoboDepositRules.memoCandidates(a.accountKey, false, aoiroMemos)).map { it.name }.distinct()
        }
        isAiMatching = true
        aiMatchingError = null
        scope.launch {
            try {
                val result = GeminiReceiptClient.matchTekiyouToAoiroAccounts(
                    tekiyou = unmatched.map { Triple(it.normalizedTekiyou, it.isDeposit, it.matchCount) },
                    accounts = accounts,
                    memoNamesByAccount = memoNames,
                    apiKey = appPreferences.geminiApiKey
                )
                val byKey = aoiroAccounts.associateBy { it.accountKey }
                aoiroAiSuggestions = result.matches.mapNotNull { m ->
                    val rule = unmatched.getOrNull(m.itemIndex) ?: return@mapNotNull null
                    val account = byKey[m.accountKey] ?: return@mapNotNull null
                    // AI が決めるのは相手科目だけ。摘要は空欄にして農家が選ぶ（空欄のままでもよい）
                    AiSuggestionRow(
                        productId = rule.id.toLong(),
                        productName = "${rule.normalizedTekiyou}（${if (rule.isDeposit) "入金" else "出金"}）",
                        key = AoiroLinkSelection(account.accountKey, account.name, null, null),
                        label = account.name,
                        reason = m.reason
                    )
                }
                aiTekiyouUsageStats = result.usageStats
                result.usageStats?.let { appPreferences.addTokenUsage(it.promptTokens, it.candidatesTokens, it.totalTokens) }
            } catch (e: GeminiApiKeyMissingException) {
                aiMatchingError = e.message
            } catch (e: GeminiQuotaExhaustedException) {
                aiMatchingError = e.message
            } catch (e: GeminiRateLimitException) {
                aiMatchingError = e.message
            } catch (e: GeminiApiException) {
                aiMatchingError = e.message
            } catch (e: Exception) {
                aiMatchingError = "エラー: ${e.message}"
            } finally {
                isAiMatching = false
            }
        }
    }

    // AI科目提案
    fun startAiMatching() {
        val unmatched = matchingRules.filter { it.yayoiAccountId == null }
        if (unmatched.isEmpty()) {
            aiMatchingError = "未マッチングの摘要がありません"
            return
        }
        if (yayoiAccountList.isEmpty()) {
            aiMatchingError = "弥生勘定科目が登録されていません"
            return
        }
        isAiMatching = true
        aiMatchingError = null
        scope.launch {
            try {
                val result = GeminiReceiptClient.matchTekiyouToAccounts(
                    rules = unmatched,
                    accounts = yayoiAccountList,
                    apiKey = appPreferences.geminiApiKey
                )
                aiTekiyouSuggestions = result.suggestions
                aiTekiyouUsageStats = result.usageStats
                result.usageStats?.let { appPreferences.addTokenUsage(it.promptTokens, it.candidatesTokens, it.totalTokens) }
                showAiTekiyouDialog = true
            } catch (e: GeminiApiKeyMissingException) {
                aiMatchingError = e.message
            } catch (e: GeminiQuotaExhaustedException) {
                aiMatchingError = e.message
            } catch (e: GeminiRateLimitException) {
                aiMatchingError = e.message
            } catch (e: GeminiApiException) {
                aiMatchingError = e.message
            } catch (e: Exception) {
                aiMatchingError = "エラー: ${e.message}"
            } finally {
                isAiMatching = false
            }
        }
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
                    FontSizeControl(
                        fontSize = listFontSize,
                        onDecrease = {
                            listFontSize = (listFontSize - 1f).coerceAtLeast(10f)
                            appPreferences.listFontSize = listFontSize
                        },
                        onIncrease = {
                            listFontSize = (listFontSize + 1f).coerceAtMost(20f)
                            appPreferences.listFontSize = listFontSize
                        }
                    )
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
            // 年の絞り込み。摘要パターンの設定は全年で共通なので、変えるとほかの年の同じ摘要にも効く
            if (availableYears.isNotEmpty()) {
                YearFilterRow(
                    years = availableYears,
                    selectedYear = selectedYear,
                    locked = lockYearToWorking,
                    onSelect = { selectedYear = it },
                    note = "相手科目・摘要の設定は、ほかの年の同じ摘要にも使われます"
                )
            }

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

            // AI科目提案ボタン（常時表示で見落としを防ぐ）
            if (accountingSoftware == AccountingSoftware.AOIRO) {
                val unmatchedForAi = matchingRules.count { it.accountKey == null }
                AiSuggestButton(
                    label = if (unmatchedForAi > 0) "未マッチ${unmatchedForAi}件をAIで一括提案" else "AI科目提案",
                    isLoading = isAiMatching,
                    onClick = { startAoiroAiMatching() }
                )
            }
            if (accountingSoftware == AccountingSoftware.YAYOI) {
                val unmatchedForAi = matchingRules.count { it.yayoiAccountId == null }
                AiSuggestButton(
                    label = if (unmatchedForAi > 0) "未マッチ${unmatchedForAi}件をAIで一括提案" else "AI科目提案",
                    isLoading = isAiMatching,
                    onClick = { startAiMatching() }
                )
            }

            CollapsibleFilterPanel(
                expanded = filterPanelExpanded,
                onExpandedChange = { filterPanelExpanded = it },
                hasActiveFilter = filterType != null || showOnlyWithData || showOnlyUnmatched || searchText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
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

                // 検索欄
                ListSearchField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = "摘要で検索",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 絞り込みチップ
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToggleFilterChip(
                        label = "通帳データあり",
                        checked = showOnlyWithData,
                        onCheckedChange = { showOnlyWithData = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ToggleFilterChip(
                        label = "未マッチのみ",
                        checked = showOnlyUnmatched,
                        onCheckedChange = { showOnlyUnmatched = it }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 並び替えチップ
                FilterChipGroup(
                    label = "並び替え:",
                    options = TekiyouSortOrder.values().toList(),
                    selected = sortOrder,
                    onSelect = { sortOrder = it },
                    optionLabel = { it.label },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    ListCountText(filteredRules.size)
                }
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
                            fontSize = listFontSize,
                            onToggleExpand = {
                                if (isExpanded) {
                                    expandedRuleIds = expandedRuleIds - rule.id
                                } else {
                                    expandedRuleIds = expandedRuleIds + rule.id
                                    loadMeisaiForRule(rule.id)
                                }
                            },
                            aoiroGroupLabel = if (isAoiro) aoiroLabel(rule.accountKey, rule.accountKeyName, rule.memoKey, rule.memoKeyName) else null,
                            aoiroOverrideLabel = { m ->
                                aoiroLabel(m.overrideAccountKey, m.overrideAccountKeyName, m.overrideMemoKey, m.overrideMemoKeyName)
                            },
                            onEditGroup = {
                                selectedRule = rule
                                showEditDialog = true
                            },
                            // 年で絞っているときはその年の明細だけ
                            meisaiItems = meisaiByRuleId[rule.id]?.let { list ->
                                selectedYear?.let { y -> list.filter { it.transactionDate.startsWith(y) } } ?: list
                            },
                            onEditIndividual = { meisai ->
                                selectedMeisai = meisai
                                selectedMeisaiGroupYayoiAccountName = if (isAoiro)
                                    aoiroLabel(rule.accountKey, rule.accountKeyName, rule.memoKey, rule.memoKeyName)
                                else rule.yayoiAccountName
                                showIndividualDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    // グループ編集ダイアログ（あおいろ・全件上書き）
    if (showEditDialog && selectedRule != null && isAoiro) {
        val rule = selectedRule!!
        AoiroLinkDialog(
            title = "グループ設定（あおいろ）",
            subject = "${if (rule.isDeposit) "入金" else "出金"}  ${rule.normalizedTekiyou}",
            kind = AoiroLinkKind.deposit(isIncome = rule.isDeposit),
            accounts = aoiroAccounts,
            memos = aoiroMemos,
            usage = aoiroUsage,
            initialAccountKey = rule.accountKey,
            initialAccountKeyName = rule.accountKeyName,
            initialMemoKey = rule.memoKey,
            initialMemoKeyName = rule.memoKeyName,
            note = "保存するとこのグループの明細の個別変更はリセットされます",
            resetHint = "相手科目を外すと、このグループの明細は「科目なし」で PC に送ります",
            onDismiss = {
                showEditDialog = false
                selectedRule = null
            },
            onSave = { selection ->
                scope.launch {
                    database.depositMeisaiDao().clearAoiroOverridesForRule(rule.id)
                    database.tekiyouMatchingRuleDao().getById(rule.id)?.let {
                        database.tekiyouMatchingRuleDao().update(
                            it.copy(
                                accountKey = selection.accountKey,
                                accountKeyName = selection.accountKeyName,
                                memoKey = selection.memoKey,
                                memoKeyName = selection.memoKeyName
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

    // グループ編集ダイアログ（全件上書き）
    if (showEditDialog && selectedRule != null && !isAoiro) {
        MatchingRuleEditDialog(
            rule = selectedRule!!,
            yayoiAccountList = yayoiAccountList,
            flaggedYayoiList = yayoiAccountList.filter { it.usedForDeposit },
            onDismiss = {
                showEditDialog = false
                selectedRule = null
            },
            onSave = { ruleId, yayoiAccountId ->
                scope.launch {
                    database.depositMeisaiDao().clearYayoiOverridesForRule(ruleId)
                    val existingRule = database.tekiyouMatchingRuleDao().getById(ruleId)
                    existingRule?.let {
                        database.tekiyouMatchingRuleDao().update(it.copy(yayoiAccountId = yayoiAccountId))
                    }
                    loadData()
                }
                showEditDialog = false
                selectedRule = null
            }
        )
    }

    // AI提案エラーダイアログ
    if (aiMatchingError != null) {
        AlertDialog(
            onDismissRequest = { aiMatchingError = null },
            title = { Text("AI提案エラー") },
            text = { Text(aiMatchingError ?: "") },
            confirmButton = { TextButton(onClick = { aiMatchingError = null }) { Text("OK") } }
        )
    }

    // AI摘要マッチングダイアログ（あおいろ）。保存は手で相手科目を設定したときと同じ（グループの個別変更は外れる）
    aoiroAiSuggestions?.let { rows ->
        AiMatchingDialog(
            rows = rows,
            usageStats = aiTekiyouUsageStats,
            title = "AI 科目提案（通帳摘要・あおいろ）",
            emptyMessage = "未マッチング摘要に対する提案が見つかりませんでした。",
            onDismiss = { aoiroAiSuggestions = null },
            onSave = { accepted ->
                scope.launch {
                    accepted.forEach { (ruleId, s) ->
                        val id = ruleId.toInt()
                        database.depositMeisaiDao().clearAoiroOverridesForRule(id)
                        database.tekiyouMatchingRuleDao().getById(id)?.let {
                            database.tekiyouMatchingRuleDao().update(
                                it.copy(
                                    accountKey = s.accountKey,
                                    accountKeyName = s.accountKeyName,
                                    memoKey = s.memoKey,
                                    memoKeyName = s.memoKeyName
                                )
                            )
                        }
                    }
                    loadData()
                }
                aoiroAiSuggestions = null
            }
        )
    }

    // AI摘要マッチングダイアログ
    if (showAiTekiyouDialog) {
        AiTekiyouMatchingDialog(
            suggestions = aiTekiyouSuggestions,
            accountList = yayoiAccountList,
            usageStats = aiTekiyouUsageStats,
            onDismiss = { showAiTekiyouDialog = false },
            onSave = { acceptedMap ->
                scope.launch {
                    acceptedMap.forEach { (ruleId, accountId) ->
                        val existingRule = database.tekiyouMatchingRuleDao().getById(ruleId)
                        existingRule?.let {
                            database.tekiyouMatchingRuleDao().update(it.copy(yayoiAccountId = accountId))
                        }
                    }
                    loadData()
                }
                showAiTekiyouDialog = false
            }
        )
    }

    // 個別オーバーライドダイアログ（あおいろ）
    if (showIndividualDialog && selectedMeisai != null && isAoiro) {
        val meisai = selectedMeisai!!
        AoiroLinkDialog(
            title = "個別変更（あおいろ）",
            subject = "${meisai.transactionDate}  ${meisai.tekiyou}",
            kind = AoiroLinkKind.deposit(isIncome = meisai.amount >= 0),
            accounts = aoiroAccounts,
            memos = aoiroMemos,
            usage = aoiroUsage,
            initialAccountKey = meisai.overrideAccountKey,
            initialAccountKeyName = meisai.overrideAccountKeyName,
            initialMemoKey = meisai.overrideMemoKey,
            initialMemoKeyName = meisai.overrideMemoKeyName,
            note = "保存するとグループ設定に関わらずこの明細にのみ適用されます",
            resetHint = "相手科目を外すとグループの設定に戻ります" +
                (selectedMeisaiGroupYayoiAccountName?.let { "（$it）" } ?: "（グループも未設定）"),
            onDismiss = {
                showIndividualDialog = false
                selectedMeisai = null
            },
            onSave = { selection ->
                val ruleId = meisai.matchingRuleId
                scope.launch {
                    database.depositMeisaiDao().updateOverrideAoiro(
                        meisai.id, selection.accountKey, selection.accountKeyName, selection.memoKey, selection.memoKeyName
                    )
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

    // 個別オーバーライドダイアログ（弥生）
    if (showIndividualDialog && selectedMeisai != null && accountingSoftware == AccountingSoftware.YAYOI) {
        IndividualYayoiOverrideDialog(
            meisai = selectedMeisai!!,
            groupAccountName = selectedMeisaiGroupYayoiAccountName,
            yayoiAccountList = yayoiAccountList,
            flaggedList = yayoiAccountList.filter { it.usedForDeposit },
            onDismiss = {
                showIndividualDialog = false
                selectedMeisai = null
            },
            onSave = { meisaiId, accountId ->
                val ruleId = selectedMeisai?.matchingRuleId
                scope.launch {
                    database.depositMeisaiDao().updateOverrideYayoiAccount(meisaiId, accountId)
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
private fun isRuleMatchedFor(rule: MatchingRuleWithTekiyou, accountingSoftware: AccountingSoftware) =
    when (accountingSoftware) {
        AccountingSoftware.YAYOI -> rule.yayoiAccountId != null
        AccountingSoftware.AOIRO -> rule.accountKey != null
    }

/**
 * @param aoiroGroupLabel あおいろモードのグループの「相手科目 ／ 摘要」（未設定なら null）
 * @param aoiroOverrideLabel あおいろモードの明細の個別指定の「相手科目 ／ 摘要」（個別指定なしなら null）
 */
@Composable
private fun MatchingRuleCard(
    rule: MatchingRuleWithTekiyou,
    accountingSoftware: AccountingSoftware,
    isExpanded: Boolean,
    fontSize: Float = AppPreferences.DEFAULT_LIST_FONT_SIZE,
    onToggleExpand: () -> Unit,
    aoiroGroupLabel: String? = null,
    aoiroOverrideLabel: (DepositMeisaiWithOverride) -> String? = { null },
    onEditGroup: (() -> Unit)?,
    meisaiItems: List<DepositMeisaiWithOverride>?,
    onEditIndividual: ((DepositMeisaiWithOverride) -> Unit)?
) {
    val isMatched = isRuleMatchedFor(rule, accountingSoftware)
    val isAoiro = accountingSoftware == AccountingSoftware.AOIRO
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
                        fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
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
                        fontSize = fontSize.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row {
                        if (rule.sampleText.isNotEmpty() && rule.sampleText != rule.normalizedTekiyou) {
                            Text(
                                text = "例: ${rule.sampleText}",
                                fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = "${rule.matchCount}件",
                            fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
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
                        isAoiro && aoiroGroupLabel != null -> {
                            val parts = aoiroGroupLabel.split(" ／ ", limit = 2)
                            Text(
                                parts[0],
                                fontWeight = FontWeight.Medium,
                                fontSize = (fontSize - 1f).coerceAtLeast(10f).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                parts.getOrNull(1) ?: "摘要なし",
                                fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
                                color = if (parts.size > 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        isMatched && accountingSoftware == AccountingSoftware.YAYOI -> {
                            Text(
                                rule.yayoiAccountName ?: "",
                                fontWeight = FontWeight.Medium,
                                fontSize = (fontSize - 1f).coerceAtLeast(10f).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                rule.yayoiAccountCode ?: "",
                                fontSize = (fontSize - 2f).coerceAtLeast(10f).sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                        else -> Text(
                            "未設定",
                            fontSize = (fontSize - 1f).coerceAtLeast(10f).sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // グループ編集アイコン（あおいろは非表示）
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
                                fontSize = (fontSize - 1f).coerceAtLeast(10f).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        else -> {
                            meisaiItems.forEach { meisai ->
                                if (isAoiro) {
                                    val override = aoiroOverrideLabel(meisai)
                                    DepositMeisaiLabeledRow(
                                        meisai = meisai,
                                        effectiveLabel = override ?: aoiroGroupLabel,
                                        isOverridden = override != null,
                                        onClick = onEditIndividual?.let { { onEditIndividual(meisai) } } ?: {}
                                    )
                                } else {
                                    DepositMeisaiItemRow(
                                        meisai = meisai,
                                        groupLabel = rule.yayoiAccountName,
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
}

/**
 * 個別明細行（展開時に表示）
 */
@Composable
private fun DepositMeisaiItemRow(
    meisai: DepositMeisaiWithOverride,
    groupLabel: String?,   // グループのデフォルトラベル（弥生の科目名）
    onClick: () -> Unit
) {
    val isOverridden = meisai.overrideYayoiAccountId != null
    val effectiveLabel = if (isOverridden) {
        buildString {
            append(meisai.overrideYayoiAccountName ?: "")
            meisai.overrideYayoiAccountCode?.takeIf { it.isNotEmpty() }?.let { append("（$it）") }
        }
    } else groupLabel
    DepositMeisaiLabeledRow(meisai, effectiveLabel, isOverridden, onClick)
}

/** 個別明細行の本体。[effectiveLabel] はこの明細に効いている科目（個別指定があればそちら） */
@Composable
private fun DepositMeisaiLabeledRow(
    meisai: DepositMeisaiWithOverride,
    effectiveLabel: String?,
    isOverridden: Boolean,
    onClick: () -> Unit
) {
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
                text = effectiveLabel ?: "未設定",
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
    yayoiAccountList: List<YayoiAccount>,        // 全有効科目
    flaggedYayoiList: List<YayoiAccount>,         // usedForDeposit=true の科目
    onDismiss: () -> Unit,
    onSave: (ruleId: Int, yayoiAccountId: Long?) -> Unit
) {
    var selectedYayoiAccountId by remember { mutableStateOf(rule.yayoiAccountId) }
    var searchText by remember { mutableStateOf("") }

    val typeLabel = if (rule.isDeposit) "入金" else "出金"
    val typeColor = if (rule.isDeposit) Color(0xFF4CAF50) else Color(0xFFE53935)

    // フラグ優先ロジック
    val hasFlaggedYayoi = flaggedYayoiList.isNotEmpty()
    var showAllYayoi by remember { mutableStateOf(!hasFlaggedYayoi) }
    var selectedCategoryA by remember { mutableStateOf<String?>(if (hasFlaggedYayoi) null else null) }

    val categoryAList = remember(yayoiAccountList) {
        sortYayoiCategoryA(yayoiAccountList.map { it.categoryA }.filter { it.isNotBlank() })
    }

    val baseYayoiList = if (showAllYayoi) yayoiAccountList else flaggedYayoiList

    val filteredYayoiList = remember(baseYayoiList, selectedCategoryA, searchText, showAllYayoi) {
        baseYayoiList.filter { acc ->
            (showAllYayoi.not() || selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText, ignoreCase = true) == true) ||
             RomajiSearch.matches(acc.searchKeyAlpha, searchText))
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
                // フラグ優先トグル + 区分Aフィルター
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

                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "検索") }
                )

                Text(
                    text = "弥生勘定科目から選択 (${filteredYayoiList.size}件)",
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
                        val noneSelected = selectedYayoiAccountId == null
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedYayoiAccountId = null }
                                .background(if (noneSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = noneSelected, onClick = { selectedYayoiAccountId = null })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("(マッチなし)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

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
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(rule.id, selectedYayoiAccountId) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
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
 * 個別オーバーライドダイアログ（弥生モード）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IndividualYayoiOverrideDialog(
    meisai: DepositMeisaiWithOverride,
    groupAccountName: String?,
    yayoiAccountList: List<YayoiAccount>,
    flaggedList: List<YayoiAccount>,
    onDismiss: () -> Unit,
    onSave: (meisaiId: Int, accountId: Long?) -> Unit
) {
    val hasFlagged = flaggedList.isNotEmpty()
    var showAll by remember { mutableStateOf(!hasFlagged) }
    var selectedAccountId by remember { mutableStateOf(meisai.overrideYayoiAccountId) }
    var searchText by remember { mutableStateOf("") }
    var selectedCategoryA by remember { mutableStateOf<String?>(null) }

    val categoryAList = remember(yayoiAccountList) {
        sortYayoiCategoryA(yayoiAccountList.map { it.categoryA }.filter { it.isNotBlank() })
    }

    val baseList = if (showAll) yayoiAccountList else flaggedList

    val filtered = remember(baseList, selectedCategoryA, searchText, showAll) {
        baseList.filter { acc ->
            (showAll.not() || selectedCategoryA == null || acc.categoryA == selectedCategoryA) &&
            (searchText.isEmpty() ||
             acc.accountName.contains(searchText, ignoreCase = true) ||
             (acc.accountCode?.contains(searchText, ignoreCase = true) == true) ||
             RomajiSearch.matches(acc.searchKeyAlpha, searchText))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("個別変更（弥生）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${meisai.transactionDate}  ${meisai.tekiyou}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "保存するとグループ設定に関わらずこの明細にのみ適用されます",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // フラグ/全科目トグル
                if (hasFlagged) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showAll,
                            onClick = { showAll = false; selectedCategoryA = null },
                            label = { Text("預金フラグのみ (${flaggedList.size}件)", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = showAll,
                            onClick = { showAll = true },
                            label = { Text("全科目", fontSize = 12.sp) }
                        )
                    }
                }

                if (showAll) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // グループのデフォルトに戻す
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccountId = null }
                                .background(
                                    if (selectedAccountId == null) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedAccountId == null, onClick = { selectedAccountId = null })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("グループのデフォルトに戻す", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (groupAccountName != null) {
                                    Text(
                                        text = groupAccountName,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    items(filtered, key = { it.id }) { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccountId = account.id }
                                .background(
                                    if (selectedAccountId == account.id) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedAccountId == account.id,
                                onClick = { selectedAccountId = account.id }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.accountName, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    account.accountCode?.let {
                                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(
                                        "${account.categoryA} / ${account.categoryB}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (account.searchKeyAlpha.isNotEmpty()) {
                                Text(
                                    account.searchKeyAlpha,
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
            TextButton(onClick = { onSave(meisai.id, selectedAccountId) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/**
 * 通帳摘要→弥生科目 AI提案ダイアログ
 */
@Composable
private fun AiTekiyouMatchingDialog(
    suggestions: List<GeminiReceiptClient.TekiyouMatchSuggestion>,
    accountList: List<YayoiAccount>,
    usageStats: GeminiReceiptClient.AiUsageStats?,
    onDismiss: () -> Unit,
    onSave: (Map<Int, Long>) -> Unit
) {
    data class SuggestionState(
        val ruleId: Int,
        val tekiyou: String,
        val accountId: Long,
        val accountName: String,
        val accountCode: String?,
        val reason: String,
        var accepted: Boolean = true
    )

    val accountMap = remember(accountList) { accountList.associateBy { it.id } }

    val states = remember(suggestions) {
        suggestions.map { s ->
            val account = accountMap[s.suggestedAccountId]
            androidx.compose.runtime.mutableStateOf(
                SuggestionState(
                    ruleId = s.ruleId,
                    tekiyou = s.normalizedTekiyou,
                    accountId = s.suggestedAccountId,
                    accountName = account?.accountName ?: "不明",
                    accountCode = account?.accountCode,
                    reason = s.reason,
                    accepted = true
                )
            )
        }
    }

    val acceptedCount = states.count { it.value.accepted }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("AI 科目提案（通帳摘要）", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (states.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${states.size}件の提案（${acceptedCount}件承認中）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { states.forEach { it.value = it.value.copy(accepted = true) } },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("全承認", fontSize = 12.sp) }
                            TextButton(
                                onClick = { states.forEach { it.value = it.value.copy(accepted = false) } },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("全解除", fontSize = 12.sp) }
                        }
                    }
                }
            }
        },
        text = {
            if (states.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "未マッチング摘要に対する提案が見つかりませんでした。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(states.size) { idx ->
                        val state by states[idx]
                        Surface(
                            color = if (state.accepted)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { states[idx].value = state.copy(accepted = !state.accepted) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Checkbox(
                                    checked = state.accepted,
                                    onCheckedChange = { states[idx].value = state.copy(accepted = it) },
                                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        state.tekiyou,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text("→", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        Text(
                                            buildString {
                                                append(state.accountName)
                                                state.accountCode?.takeIf { it.isNotEmpty() }
                                                    ?.let { append("（$it）") }
                                            },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (state.reason.isNotEmpty()) {
                                        Text(
                                            state.reason,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (usageStats != null) {
                    Text(
                        text = usageStats.toDisplayString(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Row {
                    TextButton(onClick = onDismiss) { Text("キャンセル") }
                    TextButton(
                        onClick = {
                            val accepted = states
                                .filter { it.value.accepted }
                                .associate { it.value.ruleId to it.value.accountId }
                            onSave(accepted)
                        },
                        enabled = acceptedCount > 0
                    ) { Text("${acceptedCount}件を保存") }
                }
            }
        },
        dismissButton = null
    )
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
