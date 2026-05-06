package com.example.greenframeocr.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
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
import com.example.greenframeocr.util.LearningDataExporter
import com.example.greenframeocr.util.LearningDataImporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * OCR学習状況画面（V3）
 *
 * データベースに蓄積された誤認識パターンの統計と一覧を表示
 *
 * V3設計:
 * - 時間減衰を廃止
 * - 日数条件を廃止
 * - 手動修正回数（manualCorrectCount）ベースの昇格
 * - autoFailCountによる降格/無効化
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrLearningStatusScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ocrVariantDao = database.ocrVariantDao()
    val productMasterDao = database.productMasterDao()
    val fallbackLogDao = database.ocrFallbackLogDao()

    // 統計データ
    var totalCount by remember { mutableIntStateOf(0) }
    var confidenceCounts by remember { mutableStateOf<List<ConfidenceLevelCount>>(emptyList()) }
    var sourceCounts by remember { mutableStateOf<List<SourceCount>>(emptyList()) }
    var recentPatterns by remember { mutableStateOf<List<OcrVariant>>(emptyList()) }
    var mostUsedPatterns by remember { mutableStateOf<List<OcrVariant>>(emptyList()) }
    var nearPromotionPatterns by remember { mutableStateOf<List<OcrVariant>>(emptyList()) }

    // フォールバック統計データ
    var fallbackTotalCount by remember { mutableIntStateOf(0) }
    var fallbackAvgHeight by remember { mutableStateOf<Float?>(null) }
    var fallbackHeightBuckets by remember { mutableStateOf<List<TextHeightBucketCount>>(emptyList()) }
    var recentFallbackLogs by remember { mutableStateOf<List<OcrFallbackLog>>(emptyList()) }

    // 商品名キャッシュ（productId → canonicalName）
    var productNameCache by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    // 表示タブ
    var selectedTab by remember { mutableIntStateOf(0) }

    // 削除確認ダイアログ用
    var showDeleteDialog by remember { mutableStateOf(false) }
    var patternToDelete by remember { mutableStateOf<OcrVariant?>(null) }

    // 固定確認ダイアログ用
    var showPresetDialog by remember { mutableStateOf(false) }
    var patternToPreset by remember { mutableStateOf<OcrVariant?>(null) }

    // データ再読み込み関数
    fun reloadData() {
        scope.launch {
            totalCount = ocrVariantDao.getTotalCount()
            confidenceCounts = ocrVariantDao.getCountByConfidenceLevel()
            sourceCounts = ocrVariantDao.getCountBySource()
            recentPatterns = ocrVariantDao.getRecentPatterns(20)
            mostUsedPatterns = ocrVariantDao.getMostUsedPatterns(20)
            nearPromotionPatterns = ocrVariantDao.getNearPromotionPatterns(10)

            // フォールバック統計
            fallbackTotalCount = fallbackLogDao.getTotalCount()
            fallbackAvgHeight = fallbackLogDao.getAverageTextHeight()
            fallbackHeightBuckets = fallbackLogDao.getCountByTextHeightBucket()
            recentFallbackLogs = fallbackLogDao.getRecent(20)

            // 商品名キャッシュを構築
            val productIds = (recentPatterns + mostUsedPatterns + nearPromotionPatterns)
                .map { it.productId }
                .distinct()
            val cache = mutableMapOf<Long, String>()
            for (id in productIds) {
                productMasterDao.getById(id)?.let {
                    cache[id] = it.canonicalName
                }
            }
            productNameCache = cache
        }
    }

    // エクスポート・インポート
    var showMenu by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            isBusy = true
            scope.launch {
                try {
                    LearningDataExporter.export(context, productMasterDao, ocrVariantDao, it)
                    snackbarHostState.showSnackbar("エクスポート完了")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("エクスポート失敗: ${e.message}")
                } finally {
                    isBusy = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isBusy = true
            scope.launch {
                try {
                    val result = LearningDataImporter.import(context, productMasterDao, ocrVariantDao, it)
                    snackbarHostState.showSnackbar(
                        "インポート完了: 商品 +${result.addedProducts} / バリアント +${result.addedVariants}" +
                        " (スキップ: 商品 ${result.skippedProducts} / バリアント ${result.skippedVariants})"
                    )
                    reloadData()
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("インポート失敗: ${e.message}")
                } finally {
                    isBusy = false
                }
            }
        }
    }

    // データ読み込み
    LaunchedEffect(Unit) {
        reloadData()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("OCR学習状況") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "戻る"
                        )
                    }
                },
                actions = {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("学習データをエクスポート") },
                                onClick = {
                                    showMenu = false
                                    val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                                        .format(Date())
                                    exportLauncher.launch("ocr_learning_$dateStr.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("学習データをインポート") },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 統計サマリー
            StatisticsSummary(
                totalCount = totalCount,
                confidenceCounts = confidenceCounts,
                sourceCounts = sourceCounts
            )

            // タブ
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("最近の学習") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("よく使う") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("昇格間近") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("フォールバック") }
                )
            }

            // パターン一覧
            when (selectedTab) {
                0 -> PatternList(
                    patterns = recentPatterns,
                    productNameCache = productNameCache,
                    emptyMessage = "まだ学習パターンがありません",
                    onDelete = { pattern -> patternToDelete = pattern; showDeleteDialog = true },
                    onPreset = { pattern -> patternToPreset = pattern; showPresetDialog = true }
                )
                1 -> PatternList(
                    patterns = mostUsedPatterns,
                    productNameCache = productNameCache,
                    emptyMessage = "まだ学習パターンがありません",
                    onDelete = { pattern -> patternToDelete = pattern; showDeleteDialog = true },
                    onPreset = { pattern -> patternToPreset = pattern; showPresetDialog = true }
                )
                2 -> PatternList(
                    patterns = nearPromotionPatterns,
                    productNameCache = productNameCache,
                    emptyMessage = "昇格間近のパターンはありません",
                    onDelete = { pattern -> patternToDelete = pattern; showDeleteDialog = true },
                    onPreset = { pattern -> patternToPreset = pattern; showPresetDialog = true }
                )
                3 -> FallbackStatisticsTab(
                    totalCount = fallbackTotalCount,
                    avgHeight = fallbackAvgHeight,
                    heightBuckets = fallbackHeightBuckets,
                    recentLogs = recentFallbackLogs
                )
            }
        }
    }

    // 削除確認ダイアログ
    if (showDeleteDialog && patternToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                patternToDelete = null
            },
            title = { Text("学習データ削除") },
            text = {
                Column {
                    Text("この学習パターンを削除しますか？")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "「${patternToDelete!!.variantText}」",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "→ ${productNameCache[patternToDelete!!.productId] ?: "不明"}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = patternToDelete ?: return@TextButton
                        showDeleteDialog = false
                        patternToDelete = null
                        scope.launch {
                            ocrVariantDao.delete(toDelete)
                            reloadData()
                        }
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
                    patternToDelete = null
                }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // 固定確認ダイアログ
    if (showPresetDialog && patternToPreset != null) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false; patternToPreset = null },
            title = { Text("LOCKED 固定登録") },
            text = {
                Column {
                    Text("このパターンを LOCKED に固定しますか？")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("「${patternToPreset!!.variantText}」", fontWeight = FontWeight.Medium)
                    Text(
                        text = "→ ${productNameCache[patternToPreset!!.productId] ?: "不明"}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "固定後はスコアに関係なく常に補正に使われます。削除で解除できます。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toPreset = patternToPreset ?: return@TextButton
                        showPresetDialog = false
                        patternToPreset = null
                        scope.launch {
                            ocrVariantDao.promoteToPreset(toPreset.id)
                            reloadData()
                        }
                    }
                ) {
                    Text("固定する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPresetDialog = false; patternToPreset = null }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

/**
 * 統計サマリーカード
 */
@Composable
private fun StatisticsSummary(
    totalCount: Int,
    confidenceCounts: List<ConfidenceLevelCount>,
    sourceCounts: List<SourceCount>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "学習データベース統計",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 総件数
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("総パターン数", fontSize = 14.sp)
                Text(
                    "$totalCount 件",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            // 信頼度レベル別
            Text(
                "信頼度レベル別",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            val tentativeCount = confidenceCounts.find { it.confidenceLevel == "TENTATIVE" }?.count ?: 0
            val confirmedCount = confidenceCounts.find { it.confidenceLevel == "CONFIRMED" }?.count ?: 0
            val lockedCount = confidenceCounts.find { it.confidenceLevel == "LOCKED" }?.count ?: 0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ConfidenceBadge("学習中", tentativeCount, Color(0xFFFF9800))
                ConfidenceBadge("承認済み", confirmedCount, Color(0xFF4CAF50))
                ConfidenceBadge("固定", lockedCount, Color(0xFF2196F3))
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            // ソース別
            Text(
                "登録元別",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            val systemCount = sourceCounts.find { it.source == "SYSTEM" }?.count ?: 0
            val captureCount = sourceCounts.find { it.source == "CAPTURE" }?.count ?: 0
            val presetCount = sourceCounts.find { it.source == "PRESET" }?.count ?: 0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SourceBadge("自動学習", systemCount)
                SourceBadge("手動修正", captureCount)
                SourceBadge("固定登録", presetCount)
            }
        }
    }
}

/**
 * 信頼度バッジ
 */
@Composable
private fun ConfidenceBadge(label: String, count: Int, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.2f)
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Text(
            text = "$count",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * ソースバッジ
 */
@Composable
private fun SourceBadge(label: String, count: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$count",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * パターン一覧
 */
@Composable
private fun PatternList(
    patterns: List<OcrVariant>,
    productNameCache: Map<Long, String>,
    emptyMessage: String,
    onDelete: (OcrVariant) -> Unit,
    onPreset: (OcrVariant) -> Unit = {}
) {
    if (patterns.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(patterns, key = { it.id }) { pattern ->
                PatternCard(
                    pattern = pattern,
                    productName = productNameCache[pattern.productId] ?: "不明",
                    onDelete = { onDelete(pattern) },
                    onPreset = { onPreset(pattern) }
                )
            }
        }
    }
}

/**
 * パターンカード
 */
@Composable
private fun PatternCard(
    pattern: OcrVariant,
    productName: String,
    onDelete: () -> Unit,
    onPreset: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 誤認識テキスト → 正解 + 削除ボタン
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pattern.variantText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = " → ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = productName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // LOCKED 未満のパターンにのみ「固定する」を表示
                if (pattern.confidenceLevel != "LOCKED") {
                    IconButton(
                        onClick = onPreset,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "固定する",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 統計情報（V3）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 信頼度レベル + ソース
                val levelColor = when (pattern.confidenceLevel) {
                    "LOCKED"    -> Color(0xFF2196F3)
                    "CONFIRMED" -> Color(0xFF4CAF50)
                    else        -> Color(0xFFFF9800)
                }
                val levelLabel = when (pattern.confidenceLevel) {
                    "LOCKED"    -> if (pattern.source == "PRESET") "固定" else "LOCKED"
                    "CONFIRMED" -> "承認済み"
                    else        -> "学習中"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = levelColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = levelLabel,
                            fontSize = 10.sp,
                            color = levelColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    val sourceLabel = when (pattern.source) {
                        "CAPTURE" -> "手動修正"
                        "PRESET"  -> "固定登録"
                        else      -> null
                    }
                    if (sourceLabel != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xFF9C27B0).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = sourceLabel,
                                fontSize = 9.sp,
                                color = Color(0xFF9C27B0),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // ヒット数 / 手動修正回数
                val hitText = if (pattern.manualCorrectCount > 0) {
                    "手動: ${pattern.manualCorrectCount}回"
                } else {
                    "ヒット: ${pattern.hitCount}回"
                }
                Text(
                    text = hitText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 平均スコア
                Text(
                    text = "スコア: ${"%.2f".format(pattern.avgFinalScore)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 失敗回数（あれば警告色）
                if (pattern.autoFailCount > 0) {
                    Text(
                        text = "失敗: ${pattern.autoFailCount}",
                        fontSize = 11.sp,
                        color = Color(0xFFF44336),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 昇格条件の進捗（学習中の場合）
            if (pattern.confidenceLevel == "TENTATIVE") {
                Spacer(modifier = Modifier.height(8.dp))
                PromotionProgress(pattern)
            }
        }
    }
}

/**
 * 昇格条件進捗バー（V3）
 */
@Composable
private fun PromotionProgress(pattern: OcrVariant) {
    // V3: 失敗履歴があれば昇格不可
    if (pattern.autoFailCount > 0) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "失敗履歴あり - 昇格不可",
                fontSize = 10.sp,
                color = Color(0xFFF44336)
            )
        }
        return
    }

    val (progress, statusText) = calculatePromotionProgressV3(pattern)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

/**
 * 昇格条件の進捗計算（V3設計）
 *
 * 手動修正由来（source = CAPTURE）:
 * - manualCorrectCount >= 2（異なるバッチで2回以上）
 *
 * 自動学習由来（source = SYSTEM）:
 * - hitCount >= 3
 * - avgFinalScore >= 0.90
 * - highScoreHits >= 2
 * - autoFailCount == 0
 */
private fun calculatePromotionProgressV3(pattern: OcrVariant): Pair<Float, String> {
    return if (pattern.source == "CAPTURE") {
        // 手動修正由来: manualCorrectCount >= 2
        val progress = (pattern.manualCorrectCount / 2f).coerceAtMost(1f)
        val remaining = 2 - pattern.manualCorrectCount
        val statusText = if (remaining > 0) {
            "あと${remaining}回の手動確定で昇格"
        } else {
            "確定に昇格条件達成"
        }
        Pair(progress, statusText)
    } else {
        // 自動学習由来
        val hitProgress = (pattern.hitCount / 3f).coerceAtMost(1f)
        val scoreProgress = ((pattern.avgFinalScore - 0.5) / 0.40).coerceIn(0.0, 1.0).toFloat()
        val highScoreProgress = (pattern.highScoreHits / 2f).coerceAtMost(1f)

        val totalProgress = (hitProgress + scoreProgress + highScoreProgress) / 3f
        val statusText = "確定に昇格まで"
        Pair(totalProgress, statusText)
    }
}

/**
 * フォールバック統計タブ
 */
@Composable
private fun FallbackStatisticsTab(
    totalCount: Int,
    avgHeight: Float?,
    heightBuckets: List<TextHeightBucketCount>,
    recentLogs: List<OcrFallbackLog>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // サマリーカード
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "フォールバック統計",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("総発動回数", fontSize = 14.sp)
                        Text(
                            "$totalCount 回",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("平均文字高さ", fontSize = 14.sp)
                        Text(
                            if (avgHeight != null) "${"%.1f".format(avgHeight)} px" else "N/A",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 文字高さ分布ヒストグラム
        if (heightBuckets.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "文字高さ別発生分布（5px刻み）",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val maxCount = heightBuckets.maxOfOrNull { it.count } ?: 1

                        heightBuckets.forEach { bucket ->
                            val progress = bucket.count.toFloat() / maxCount
                            val heightRange = "${bucket.heightBucket}-${bucket.heightBucket + 4}"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${heightRange}px",
                                    fontSize = 11.sp,
                                    modifier = Modifier.width(50.dp)
                                )

                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(16.dp)
                                        .padding(horizontal = 8.dp),
                                    color = if (bucket.heightBucket < 20) {
                                        Color(0xFFF44336)  // 小さい文字は赤（問題あり）
                                    } else if (bucket.heightBucket < 25) {
                                        Color(0xFFFF9800)  // 中程度はオレンジ（注意）
                                    } else {
                                        Color(0xFF4CAF50)  // 大きい文字は緑（正常）
                                    },
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                Text(
                                    text = "${bucket.count}",
                                    fontSize = 11.sp,
                                    modifier = Modifier.width(30.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "※ 20px未満: 小さい文字（OCR精度低下）、20-24px: 注意、25px以上: 正常",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 最近のフォールバックログ
        if (recentLogs.isNotEmpty()) {
            item {
                Text(
                    text = "最近のフォールバック",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(recentLogs) { log ->
                FallbackLogCard(log)
            }
        } else {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "フォールバックのログがありません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * フォールバックログカード
 */
@Composable
private fun FallbackLogCard(log: OcrFallbackLog) {
    val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = log.cleanedText.ifEmpty { log.rawText },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 文字高さバッジ
                val heightColor = when {
                    log.textHeight < 20 -> Color(0xFFF44336)
                    log.textHeight < 25 -> Color(0xFFFF9800)
                    else -> Color(0xFF4CAF50)
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = heightColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "${"%.0f".format(log.textHeight)}px",
                        fontSize = 10.sp,
                        color = heightColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "行${log.rowIndex} (Y=${log.rowY})",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Box数: ${log.boxCount}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = dateFormat.format(Date(log.createdAt)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 分離テキスト（ある場合）
            if (log.separatedTexts.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "分離: ${log.separatedTexts}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
