package com.example.greenframeocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.greenframeocr.data.RakurakuTekiyou
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.util.importTekiyouFromCsv
import kotlinx.coroutines.launch

/**
 * 預金摘要辞書画面
 * 入金/出金切替ボタン付き、使用する・摘要名・科目・事業割合の4列グリッド表示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YokinTekiyouScreen(
    database: ReceiptDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf("入金") } // 入金 or 出金

    // データ読み込み
    fun loadData() {
        scope.launch {
            isLoading = true
            tekiyouList = database.rakurakuTekiyouDao().getByCategory("預金", selectedCategory)
            isLoading = false
        }
    }

    // 初回起動時にCSV差分インポート
    LaunchedEffect(Unit) {
        importTekiyouFromCsv(context, database)
        loadData()
    }

    LaunchedEffect(selectedCategory) {
        loadData()
    }

    // チェックボックス変更時
    fun onEnabledChange(tekiyou: RakurakuTekiyou, isEnabled: Boolean) {
        scope.launch {
            database.rakurakuTekiyouDao().updateEnabled(tekiyou.id, isEnabled)
            // リスト更新
            tekiyouList = tekiyouList.map {
                if (it.id == tekiyou.id) it.copy(isEnabled = isEnabled) else it
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("預金摘要辞書") },
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
            // 入金/出金 切替ボタン
            CategoryToggleButtons(
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ヘッダー行
            YokinTekiyouGridHeader()

            Divider(thickness = 2.dp)

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (tekiyouList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "データがありません",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tekiyouList, key = { it.id }) { tekiyou ->
                        YokinTekiyouGridRow(
                            tekiyou = tekiyou,
                            onEnabledChange = { isEnabled -> onEnabledChange(tekiyou, isEnabled) }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

/**
 * 入金/出金 切替ボタン
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryToggleButtons(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        FilterChip(
            onClick = { onCategorySelected("入金") },
            label = { Text("入金") },
            selected = selectedCategory == "入金",
            modifier = Modifier.padding(end = 8.dp)
        )
        FilterChip(
            onClick = { onCategorySelected("出金") },
            label = { Text("出金") },
            selected = selectedCategory == "出金"
        )
    }
}

/**
 * グリッドヘッダー
 */
@Composable
private fun YokinTekiyouGridHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "使用",
            modifier = Modifier.width(40.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "摘要名",
            modifier = Modifier.weight(1.8f),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Key",
            modifier = Modifier.weight(0.8f),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "科目",
            modifier = Modifier.weight(1.2f),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "事業",
            modifier = Modifier.weight(0.6f),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * グリッド行
 */
@Composable
private fun YokinTekiyouGridRow(
    tekiyou: RakurakuTekiyou,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = tekiyou.isEnabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = tekiyou.tekiyouName,
            modifier = Modifier.weight(1.8f),
            fontSize = 13.sp,
            textAlign = TextAlign.Start
        )
        Text(
            text = tekiyou.searchKey,
            modifier = Modifier.weight(0.8f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = tekiyou.kamoku,
            modifier = Modifier.weight(1.2f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = tekiyou.businessRatio?.let { "${it}%" } ?: "-",
            modifier = Modifier.weight(0.6f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}
