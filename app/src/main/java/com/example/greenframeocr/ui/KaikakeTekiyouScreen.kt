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
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.RakurakuTekiyou
import com.example.greenframeocr.data.ReceiptDatabase
import kotlinx.coroutines.launch

/**
 * 買掛摘要辞書画面
 * 買掛・購入の摘要リストを使用する・摘要名・科目・事業割合の4列グリッドで表示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaikakeTekiyouScreen(
    database: ReceiptDatabase,
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tekiyouList by remember { mutableStateOf<List<RakurakuTekiyou>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }

    // データ読み込み
    fun loadData() {
        scope.launch {
            tekiyouList = database.rakurakuTekiyouDao().getByCategory("買掛", "購入")
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
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
                title = { Text("買掛摘要辞書") },
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
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
            }
            // ヘッダー行
            KaikakeTekiyouGridHeader(fontSize = listFontSize)

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
                        KaikakeTekiyouGridRow(
                            tekiyou = tekiyou,
                            fontSize = listFontSize,
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
 * グリッドヘッダー
 */
@Composable
private fun KaikakeTekiyouGridHeader(fontSize: Float = AppPreferences.DEFAULT_LIST_FONT_SIZE) {
    val sub = (fontSize - 2f).coerceAtLeast(10f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "使用",  modifier = Modifier.width(40.dp),  fontWeight = FontWeight.Bold, fontSize = sub.sp, textAlign = TextAlign.Center)
        Text(text = "摘要名", modifier = Modifier.weight(1.8f), fontWeight = FontWeight.Bold, fontSize = sub.sp, textAlign = TextAlign.Center)
        Text(text = "Key",   modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = sub.sp, textAlign = TextAlign.Center)
        Text(text = "科目",  modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = sub.sp, textAlign = TextAlign.Center)
        Text(text = "事業",  modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Bold, fontSize = sub.sp, textAlign = TextAlign.Center)
    }
}

/**
 * グリッド行
 */
@Composable
private fun KaikakeTekiyouGridRow(
    tekiyou: RakurakuTekiyou,
    fontSize: Float = AppPreferences.DEFAULT_LIST_FONT_SIZE,
    onEnabledChange: (Boolean) -> Unit
) {
    val sub = (fontSize - 2f).coerceAtLeast(10f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = tekiyou.isEnabled, onCheckedChange = onEnabledChange, modifier = Modifier.width(40.dp))
        Text(text = tekiyou.tekiyouName, modifier = Modifier.weight(1.8f), fontSize = (fontSize - 1f).sp, textAlign = TextAlign.Start)
        Text(text = tekiyou.searchKey,   modifier = Modifier.weight(0.8f), fontSize = sub.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = tekiyou.kamoku,      modifier = Modifier.weight(1.2f), fontSize = (fontSize - 1f).sp, textAlign = TextAlign.Center)
        Text(text = tekiyou.businessRatio?.let { "${it}%" } ?: "-", modifier = Modifier.weight(0.6f), fontSize = sub.sp, textAlign = TextAlign.Center)
    }
}
