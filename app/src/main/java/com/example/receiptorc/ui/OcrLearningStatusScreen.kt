package com.example.receiptorc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.example.receiptorc.data.*
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
    val ocrVariantDao = database.ocrVariantDao()
    val productMasterDao = database.productMasterDao()

    // 統計データ
    var totalCount by remember { mutableIntStateOf(0) }
    var confidenceCounts by remember { mutableStateOf<List<ConfidenceLevelCount>>(emptyList()) }
    var sourceCounts by remember { mutableStateOf<List<SourceCount>>(emptyList()) }
    var recentPatterns by remember { mutableStateOf<List<OcrVariant>>(emptyList()) }
    var mostUsedPatterns by remember { mutableStateOf<List<OcrVariant>>(emptyList()) }
    var nearPromotionPatterns by remember { mutableStateOf<List<OcrVariant>>(emptyList()) }

    // 商品名キャッシュ（productId → canonicalName）
    var productNameCache by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    // 表示タブ
    var selectedTab by remember { mutableIntStateOf(0) }

    // データ読み込み
    LaunchedEffect(Unit) {
        scope.launch {
            totalCount = ocrVariantDao.getTotalCount()
            confidenceCounts = ocrVariantDao.getCountByConfidenceLevel()
            sourceCounts = ocrVariantDao.getCountBySource()
            recentPatterns = ocrVariantDao.getRecentPatterns(20)
            mostUsedPatterns = ocrVariantDao.getMostUsedPatterns(20)
            nearPromotionPatterns = ocrVariantDao.getNearPromotionPatterns(10)

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

    Scaffold(
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
            TabRow(selectedTabIndex = selectedTab) {
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
            }

            // パターン一覧
            when (selectedTab) {
                0 -> PatternList(
                    patterns = recentPatterns,
                    productNameCache = productNameCache,
                    emptyMessage = "まだ学習パターンがありません"
                )
                1 -> PatternList(
                    patterns = mostUsedPatterns,
                    productNameCache = productNameCache,
                    emptyMessage = "まだ学習パターンがありません"
                )
                2 -> PatternList(
                    patterns = nearPromotionPatterns,
                    productNameCache = productNameCache,
                    emptyMessage = "昇格間近のパターンはありません"
                )
            }
        }
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

            val autoCount = confidenceCounts.find { it.confidenceLevel == "AUTO" }?.count ?: 0
            val confirmedCount = confidenceCounts.find { it.confidenceLevel == "CONFIRMED" }?.count ?: 0
            val lockedCount = confidenceCounts.find { it.confidenceLevel == "LOCKED" }?.count ?: 0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ConfidenceBadge("AUTO", autoCount, Color(0xFFFF9800))
                ConfidenceBadge("CONFIRMED", confirmedCount, Color(0xFF4CAF50))
                ConfidenceBadge("LOCKED", lockedCount, Color(0xFF2196F3))
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

            val importCount = sourceCounts.find { it.source == "IMPORT" }?.count ?: 0
            val autoSourceCount = sourceCounts.find { it.source == "AUTO" }?.count ?: 0
            val userCount = sourceCounts.find { it.source == "USER" }?.count ?: 0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SourceBadge("インポート", importCount)
                SourceBadge("自動学習", autoSourceCount)
                SourceBadge("手動登録", userCount)
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
    emptyMessage: String
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
            items(patterns) { pattern ->
                PatternCard(
                    pattern = pattern,
                    productName = productNameCache[pattern.productId] ?: "不明"
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
    productName: String
) {
    val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 誤認識テキスト → 正解
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
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 統計情報（V3）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 信頼度レベル + ソース
                val levelColor = when (pattern.confidenceLevel) {
                    "LOCKED" -> Color(0xFF2196F3)
                    "CONFIRMED" -> Color(0xFF4CAF50)
                    else -> Color(0xFFFF9800)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = levelColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = pattern.confidenceLevel,
                            fontSize = 10.sp,
                            color = levelColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (pattern.source == "USER") {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xFF9C27B0).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "手動",
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

            // 昇格条件の進捗（AUTOの場合）
            if (pattern.confidenceLevel == "AUTO") {
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
 * 手動修正由来（source = USER）:
 * - manualCorrectCount >= 2（異なるバッチで2回以上）
 *
 * 自動学習由来（source != USER）:
 * - hitCount >= 3
 * - avgFinalScore >= 0.90
 * - highScoreHits >= 2
 * - autoFailCount == 0
 */
private fun calculatePromotionProgressV3(pattern: OcrVariant): Pair<Float, String> {
    return if (pattern.source == "USER") {
        // 手動修正由来: manualCorrectCount >= 2
        val progress = (pattern.manualCorrectCount / 2f).coerceAtMost(1f)
        val remaining = 2 - pattern.manualCorrectCount
        val statusText = if (remaining > 0) {
            "あと${remaining}回の手動確定で昇格"
        } else {
            "CONFIRMED昇格条件達成"
        }
        Pair(progress, statusText)
    } else {
        // 自動学習由来
        val hitProgress = (pattern.hitCount / 3f).coerceAtMost(1f)
        val scoreProgress = ((pattern.avgFinalScore - 0.5) / 0.40).coerceIn(0.0, 1.0).toFloat()
        val highScoreProgress = (pattern.highScoreHits / 2f).coerceAtMost(1f)

        val totalProgress = (hitProgress + scoreProgress + highScoreProgress) / 3f
        val statusText = "CONFIRMED昇格まで"
        Pair(totalProgress, statusText)
    }
}
