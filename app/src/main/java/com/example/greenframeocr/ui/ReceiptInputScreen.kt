package com.example.greenframeocr.ui

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowLeft
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 伝票入力画面（リニューアル版 v2）
 *
 * 機能:
 * - 月単位での編集・保存
 * - 伝票の追加・削除（編集モード中も可能）
 * - 行セレクタ、一行クリア
 * - 文字サイズ調整（10〜20）
 * - 固定列幅
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptInputScreen(
    eraYear: Int,
    database: com.example.greenframeocr.data.ReceiptDatabase,
    onBack: () -> Unit,
    onCapture: () -> Unit,
    onNavigateToSummary: (Int, Int) -> Unit = { _, _ -> }  // (year, month)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 基本状態
    var selectedMonth by remember { mutableIntStateOf(1) }
    var currentSheetNumber by remember { mutableIntStateOf(1) }
    var totalSheets by remember { mutableIntStateOf(0) }
    var viewMode by remember { mutableStateOf(ViewMode.VIEW) }
    var inputMode by remember { mutableStateOf(InputMode.OCR) }

    // 月全体のデータ（全伝票を保持）
    var allSheetsData by remember { mutableStateOf<Map<Int, List<ReceiptRowData>>>(emptyMap()) }
    var originalAllSheetsData by remember { mutableStateOf<Map<Int, List<ReceiptRowData>>>(emptyMap()) }

    // 現在の伝票データ
    val currentReceiptRows = allSheetsData[currentSheetNumber] ?: emptyReceiptRows(currentSheetNumber)

    // グリッド表示制御
    var selectedRowIndex by remember { mutableIntStateOf(-1) }
    var fontSize by remember { mutableFloatStateOf(12f) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showRowActionsBottomSheet by remember { mutableStateOf(false) }

    // カメラ表示状態
    var showCamera by remember { mutableStateOf(false) }
    var cameraSessionId by remember { mutableStateOf(0) }

    // 年月固定設定
    val appPreferences = remember { com.example.greenframeocr.data.AppPreferences(context) }
    var fixYearMonth by remember { mutableStateOf(appPreferences.fixYearMonth) }

    // 初回ロード
    LaunchedEffect(selectedMonth) {
        if (viewMode == ViewMode.VIEW) {
            loadMonthData(
                database = database,
                year = eraYear,
                month = selectedMonth,
                onDataLoaded = { sheets, sheetsData ->
                    totalSheets = sheets
                    allSheetsData = sheetsData
                    originalAllSheetsData = sheetsData
                    currentSheetNumber = if (sheets == 0) 1 else 1
                }
            )
        }
    }

    // 伝票削除確認ダイアログ
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("伝票削除の確認") },
            text = { Text("${currentSheetNumber}枚目の伝票を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 編集モード中はメモリ上のデータのみを削除
                        val updatedSheetsData = allSheetsData.toMutableMap()

                        // 現在のシートを削除
                        updatedSheetsData.remove(currentSheetNumber)

                        // 後続のシート番号を繰り上げる
                        val newSheetsData = mutableMapOf<Int, List<ReceiptRowData>>()
                        updatedSheetsData.keys.sorted().forEachIndexed { index, oldSheetNumber ->
                            val newSheetNumber = index + 1
                            newSheetsData[newSheetNumber] = updatedSheetsData[oldSheetNumber]!!
                        }

                        // カテゴリを再計算してから代入
                        allSheetsData = recalculateCategoriesInMemory(newSheetsData)
                        totalSheets = (totalSheets - 1).coerceAtLeast(0)

                        // 現在のシート番号を調整
                        currentSheetNumber = if (totalSheets == 0) {
                            1
                        } else {
                            currentSheetNumber.coerceAtMost(totalSheets)
                        }

                        showDeleteConfirmDialog = false
                    }
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // カメラとデータグリッドを排他的に表示
    if (showCamera) {
        CameraView(
            onOcrComplete = { parsedRows ->
                val ocrRows = convertParsedRowsToRowData(
                    parsedRows = parsedRows,
                    sheetNumber = currentSheetNumber,
                    fixYearMonth = fixYearMonth,
                    defaultYear = eraYear,
                    defaultMonth = selectedMonth
                )
                val currentRows = allSheetsData[currentSheetNumber] ?: emptyReceiptRows(currentSheetNumber)

                // 選択されたセルがあるかチェック
                val hasSelectedCells = currentRows.any { it.selectedCells.isNotEmpty() }

                val updatedRows = if (hasSelectedCells) {
                    // 選択セルのみを更新
                    currentRows.mapIndexed { index, row ->
                        if (row.selectedCells.isNotEmpty() && index < ocrRows.size) {
                            val ocrRow = ocrRows[index]
                            val newDate = if (row.selectedCells.contains(CellType.DATE)) ocrRow.date else row.date
                            val newProductName = if (row.selectedCells.contains(CellType.PRODUCT_NAME)) ocrRow.productName else row.productName
                            val newAmount = if (row.selectedCells.contains(CellType.AMOUNT)) ocrRow.amount else row.amount
                            row.copy(
                                date = newDate,
                                productName = newProductName,
                                amount = newAmount,
                                selectedCells = emptySet()
                            )
                        } else {
                            row.copy(selectedCells = emptySet())
                        }
                    }
                } else {
                    // 選択セルがない場合は全体を更新
                    ocrRows
                }

                // データを更新してカテゴリを再計算
                val tempSheetsData = allSheetsData.toMutableMap().apply {
                    put(currentSheetNumber, updatedRows)
                }
                allSheetsData = recalculateCategoriesInMemory(tempSheetsData)
                showCamera = false
            },
            onCancel = {
                showCamera = false
            }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("伝票データ")
                    },
                    navigationIcon = {
                        if (viewMode == ViewMode.VIEW) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, "戻る")
                            }
                        }
                    },
                    actions = {
                        Text(
                            text = if (viewMode == ViewMode.EDIT) "編集" else "閲覧",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (viewMode == ViewMode.EDIT)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            },
            floatingActionButton = {
                // 合計行以外で行が選択されている場合のみFABを表示
                if (selectedRowIndex >= 0 && selectedRowIndex < 20 && viewMode == ViewMode.EDIT && inputMode == InputMode.DIRECT) {
                    FloatingActionButton(
                        onClick = { showRowActionsBottomSheet = true }
                    ) {
                        Icon(Icons.Default.Edit, "行操作")
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1行目: 年月選択、伝票ナビゲーション
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 年表示（目立つように）
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "令和${eraYear}年",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 月選択
                    MonthSelector(
                        selectedMonth = selectedMonth,
                        onMonthChange = {
                            selectedMonth = it
                            scope.launch {
                                loadMonthData(
                                    database = database,
                                    year = eraYear,
                                    month = selectedMonth,
                                    onDataLoaded = { sheets, sheetsData ->
                                        totalSheets = sheets
                                        allSheetsData = sheetsData
                                        originalAllSheetsData = sheetsData
                                        currentSheetNumber = if (sheets == 0) 1 else 1
                                    }
                                )
                            }
                        },
                        enabled = viewMode == ViewMode.VIEW,
                        modifier = Modifier.width(80.dp)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    SheetNavigator(
                        current = if (totalSheets == 0) 0 else currentSheetNumber,
                        total = totalSheets,
                        onPrevious = {
                            if (currentSheetNumber > 1) {
                                currentSheetNumber--
                                selectedRowIndex = -1
                            }
                        },
                        onNext = {
                            if (currentSheetNumber < totalSheets) {
                                currentSheetNumber++
                                selectedRowIndex = -1
                            }
                        },
                        modifier = Modifier.width(140.dp)
                    )
                }

                // 2行目: 編集ボタン または 伝票追加・削除ボタン
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (viewMode == ViewMode.VIEW) {
                        Button(
                            onClick = {
                                viewMode = ViewMode.EDIT
                                originalAllSheetsData = allSheetsData
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("編集")
                        }
                    } else {
                        // 編集モード時は伝票追加・削除ボタンを表示
                        Button(
                            onClick = {
                                scope.launch {
                                    // 編集モード中はメモリ上の最大シート番号を使用
                                    val currentMaxSheet = allSheetsData.keys.maxOrNull() ?: 0
                                    val newSheetNumber = currentMaxSheet + 1
                                    val newTotalSheets = newSheetNumber

                                    totalSheets = newTotalSheets
                                    currentSheetNumber = newSheetNumber

                                    // 新しい伝票データを追加
                                    allSheetsData = allSheetsData.toMutableMap().apply {
                                        put(newSheetNumber, emptyReceiptRows(newSheetNumber))
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("伝票追加")
                        }

                        Button(
                            onClick = {
                                // データクリア（伝票は残す）
                                val tempSheetsData = allSheetsData.toMutableMap().apply {
                                    put(currentSheetNumber, emptyReceiptRows(currentSheetNumber))
                                }
                                // カテゴリを再計算
                                allSheetsData = recalculateCategoriesInMemory(tempSheetsData)
                                selectedRowIndex = -1
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ),
                            enabled = totalSheets > 0
                        ) {
                            Icon(Icons.Default.Clear, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("クリア")
                        }

                        Button(
                            onClick = {
                                showDeleteConfirmDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            enabled = totalSheets > 0
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("伝票削除")
                        }
                    }
                }

                // 3行目: OCR/直接、撮影
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InputModeToggle(
                        inputMode = inputMode,
                        onModeChange = { newMode ->
                            // 直接モードに切り替わった場合、選択セルをクリア
                            if (newMode == InputMode.DIRECT && inputMode == InputMode.OCR) {
                                val clearedRows = currentReceiptRows.map { it.copy(selectedCells = emptySet()) }
                                allSheetsData = allSheetsData.toMutableMap().apply {
                                    put(currentSheetNumber, clearedRows)
                                }
                            }
                            inputMode = newMode
                        },
                        enabled = viewMode == ViewMode.EDIT && totalSheets > 0,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            cameraSessionId++
                            showCamera = true
                        },
                        modifier = Modifier.weight(1f),
                        enabled = viewMode == ViewMode.EDIT && inputMode == InputMode.OCR && totalSheets > 0
                    ) {
                        Icon(Icons.Default.CameraAlt, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("撮影")
                    }
                }

                // 4行目: 年月固定、フォントサイズ、再計算
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 年月固定チェックボックス
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            fixYearMonth = !fixYearMonth
                            appPreferences.fixYearMonth = fixYearMonth
                        }
                    ) {
                        Checkbox(
                            checked = fixYearMonth,
                            onCheckedChange = {
                                fixYearMonth = it
                                appPreferences.fixYearMonth = it
                            },
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("年月固定", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // フォントサイズコントロール
                    Row(
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("文字", fontSize = 12.sp)
                        IconButton(
                            onClick = { fontSize = (fontSize - 1f).coerceAtLeast(10f) },
                            enabled = fontSize > 10f,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("-", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "${fontSize.toInt()}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(20.dp),
                            textAlign = TextAlign.Center
                        )
                        IconButton(
                            onClick = { fontSize = (fontSize + 1f).coerceAtMost(20f) },
                            enabled = fontSize < 20f,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 再計算ボタン
                    OutlinedButton(
                        onClick = {
                            allSheetsData = recalculateCategoriesInMemory(allSheetsData)
                        },
                        enabled = viewMode == ViewMode.EDIT && totalSheets > 0
                    ) {
                        Text("再計算", fontSize = 13.sp)
                    }
                }

                Divider()

                // データグリッド
                // 文字サイズに応じて列幅を最適化（基準: 12sp）
                val fontSizeRatio = fontSize / 12f
                val dateWidth = (80 * fontSizeRatio).dp
                val productNameWidth = (240 * fontSizeRatio).dp
                val amountWidth = (80 * fontSizeRatio).dp

                DataGrid(
                    rows = currentReceiptRows,
                    viewMode = viewMode,
                    inputMode = inputMode,
                    selectedRowIndex = selectedRowIndex,
                    fontSize = fontSize,
                    dateWidth = dateWidth,
                    productNameWidth = productNameWidth,
                    amountWidth = amountWidth,
                    onRowSelect = { index ->
                        selectedRowIndex = if (selectedRowIndex == index) -1 else index
                    },
                    onRowUpdate = { index, updatedRow ->
                        val tempSheetsData = allSheetsData.toMutableMap().apply {
                            val currentRows = get(currentSheetNumber)?.toMutableList() ?: mutableListOf()
                            if (index < currentRows.size) {
                                currentRows[index] = updatedRow
                            }
                            put(currentSheetNumber, currentRows)
                        }
                        allSheetsData = recalculateCategoriesInMemory(tempSheetsData)
                    },
                    defaultYear = eraYear,
                    defaultMonth = selectedMonth,
                    productMasterDao = database.productMasterDao(),
                    subtotalFlags = calculateSubtotalFlags(allSheetsData),
                    fixYearMonth = fixYearMonth
                )

                // 検証結果表示（全伝票対応版）
                if (totalSheets > 0) {
                    val validationResult = validateAllSheetsData(allSheetsData)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (validationResult.isValid && validationResult.calculatedTotal != 0)
                                MaterialTheme.colorScheme.primaryContainer
                            else if (!validationResult.isValid)
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "小計・合計の検証",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // カテゴリ別小計の検証
                            if (validationResult.categoryBreakdowns.isNotEmpty()) {
                                validationResult.categoryBreakdowns.forEach { category ->
                                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        // カテゴリ名と入力値
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "小計　${category.categoryName}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "入力値：${"%,d".format(category.enteredSubtotal)}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        }

                                        // 一致/不一致と計算値
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (category.isValid) "　一致" else "　不一致",
                                                fontSize = 12.sp,
                                                color = if (category.isValid)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "計算値：${"%,d".format(category.calculatedSubtotal)}",
                                                fontSize = 12.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        }

                                        // 内訳（各伝票）
                                        if (category.sheetBreakdowns.isNotEmpty()) {
                                            Text(
                                                text = "    （${category.sheetBreakdowns.joinToString(", ") {
                                                    "${it.sheetNumber}枚目 ${"%,d".format(it.amount)}"
                                                }}）",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                modifier = Modifier.padding(start = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 合計の検証
                            validationResult.totalBreakdown?.let { total ->
                                Divider(modifier = Modifier.padding(vertical = 4.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    // 合計と入力値
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "合計",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "入力値：${"%,d".format(total.enteredTotal)}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }

                                    // 一致/不一致と計算値
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (total.isValid) "　一致" else "　不一致",
                                            fontSize = 12.sp,
                                            color = if (total.isValid)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "計算値：${"%,d".format(total.calculatedTotal)}",
                                            fontSize = 12.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }

                                    // 内訳（各伝票）
                                    if (total.sheetBreakdowns.isNotEmpty()) {
                                        Text(
                                            text = "    （${total.sheetBreakdowns.joinToString(", ") {
                                                "${it.sheetNumber}枚目 ${"%,d".format(it.amount)}"
                                            }}）",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }


                // 決定・キャンセルボタン（編集モードのみ）
                if (viewMode == ViewMode.EDIT) {
                    Divider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                allSheetsData = originalAllSheetsData
                                viewMode = ViewMode.VIEW

                                // データ再ロード
                                scope.launch {
                                    loadMonthData(
                                        database = database,
                                        year = eraYear,
                                        month = selectedMonth,
                                        onDataLoaded = { sheets, sheetsData ->
                                            totalSheets = sheets
                                            allSheetsData = sheetsData
                                            originalAllSheetsData = sheetsData
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("キャンセル")
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    saveMonthData(
                                        database = database,
                                        year = eraYear,
                                        month = selectedMonth,
                                        allSheetsData = allSheetsData
                                    )

                                    // カテゴリ再計算を実行
                                    com.example.greenframeocr.util.CategoryRecalculator.recalculateMonthlyCategories(
                                        dao = database.receiptDao(),
                                        year = eraYear,
                                        month = selectedMonth
                                    )

                                    // データ再ロード
                                    loadMonthData(
                                        database = database,
                                        year = eraYear,
                                        month = selectedMonth,
                                        onDataLoaded = { sheets, sheetsData ->
                                            totalSheets = sheets
                                            allSheetsData = sheetsData
                                            originalAllSheetsData = sheetsData
                                        }
                                    )

                                    viewMode = ViewMode.VIEW
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("決定")
                        }
                    }
                }
            }
        }

        // 行操作ボトムシートメニュー
        if (showRowActionsBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showRowActionsBottomSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "行操作",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 挿入ボタン
                    TextButton(
                        onClick = {
                            if (selectedRowIndex >= 0) {
                                // 20行目が空白行かチェック
                                val rows = currentReceiptRows.toMutableList()
                                val lastRow = rows[19]

                                if (lastRow.date.isBlank() && lastRow.productName.isBlank() && lastRow.amount == 0) {
                                    // 最後の行が空白なら、選択行の下に空白行を挿入
                                    // 選択行+1から最後までを1つ下にシフト
                                    for (i in 19 downTo selectedRowIndex + 2) {
                                        rows[i] = rows[i - 1]
                                    }
                                    // 選択行の下に空白行を挿入
                                    rows[selectedRowIndex + 1] = ReceiptRowData(
                                        rowNumber = selectedRowIndex + 2,
                                        date = "",
                                        productName = "",
                                        amount = 0
                                    )

                                    allSheetsData = allSheetsData.toMutableMap().apply {
                                        put(currentSheetNumber, rows)
                                    }
                                }
                            }
                            showRowActionsBottomSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, "挿入", modifier = Modifier.padding(end = 8.dp))
                        Text("挿入")
                    }

                    // 削除ボタン
                    TextButton(
                        onClick = {
                            if (selectedRowIndex >= 0) {
                                val rows = currentReceiptRows.toMutableList()
                                // 選択行以降を1つ上にシフト
                                for (i in selectedRowIndex until 19) {
                                    rows[i] = rows[i + 1]
                                }
                                // 最後の行を空白に
                                rows[19] = ReceiptRowData(
                                    rowNumber = 20,
                                    date = "",
                                    productName = "",
                                    amount = 0
                                )

                                allSheetsData = allSheetsData.toMutableMap().apply {
                                    put(currentSheetNumber, rows)
                                }
                            }
                            showRowActionsBottomSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, "削除", modifier = Modifier.padding(end = 8.dp))
                        Text("削除")
                    }

                    // クリアボタン
                    TextButton(
                        onClick = {
                            if (selectedRowIndex >= 0) {
                                val rows = currentReceiptRows.toMutableList()
                                rows[selectedRowIndex] = ReceiptRowData(
                                    rowNumber = selectedRowIndex + 1,
                                    date = "",
                                    productName = "",
                                    amount = 0
                                )
                                allSheetsData = allSheetsData.toMutableMap().apply {
                                    put(currentSheetNumber, rows)
                                }
                            }
                            showRowActionsBottomSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Clear, "クリア", modifier = Modifier.padding(end = 8.dp))
                        Text("クリア")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * 月選択ドロップダウン
 */
@Composable
private fun MonthSelector(
    selectedMonth: Int,
    onMonthChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("${selectedMonth}月", fontSize = 14.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            (1..12).forEach { month ->
                DropdownMenuItem(
                    text = { Text("${month}月") },
                    onClick = {
                        onMonthChange(month)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 伝票ナビゲーター
 */
@Composable
private fun SheetNavigator(
    current: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = current > 1,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ArrowLeft, "前の伝票", Modifier.size(20.dp))
            }

            Text(
                text = "$current/$total",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = onNext,
                enabled = current < total,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ArrowRight, "次の伝票", Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 入力モードトグル
 */
@Composable
private fun InputModeToggle(
    inputMode: InputMode,
    onModeChange: (InputMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        InputMode.entries.forEach { mode ->
            val isSelected = inputMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSelected && enabled) MaterialTheme.colorScheme.secondary
                        else Color.Transparent,
                        MaterialTheme.shapes.small
                    )
                    .clickable(enabled = enabled) { onModeChange(mode) }
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.label,
                    color = if (isSelected && enabled) MaterialTheme.colorScheme.onSecondary
                    else if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    fontSize = 14.sp
                )
            }
        }
    }
}

/**
 * データグリッド（動的列幅版）
 */
@Composable
private fun DataGrid(
    rows: List<ReceiptRowData>,
    viewMode: ViewMode,
    inputMode: InputMode,
    selectedRowIndex: Int,
    fontSize: Float,
    dateWidth: Dp,
    productNameWidth: Dp,
    amountWidth: Dp,
    onRowSelect: (Int) -> Unit,
    onRowUpdate: (Int, ReceiptRowData) -> Unit,
    defaultYear: Int,
    defaultMonth: Int,
    productMasterDao: com.example.greenframeocr.data.ProductMasterDao,
    subtotalFlags: MonthlySubtotalFlags,
    fixYearMonth: Boolean = false
) {
    var editingCell by remember { mutableStateOf<EditingCell?>(null) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        // ヘッダー行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp)
                .horizontalScroll(scrollState)
        ) {
            GridHeaderCell("取引日", dateWidth, fontSize)
            GridHeaderCell("商品名", productNameWidth, fontSize)
            GridHeaderCell("税込金額", amountWidth, fontSize)
        }

        Divider()

        // データ行
        rows.forEachIndexed { index, row ->
            // 前の行が小計行かどうかをチェック
            val isAfterSubtotal = index > 0 && rows[index - 1].isSubtotal

            DataRow(
                row = row,
                viewMode = viewMode,
                inputMode = inputMode,
                isSelected = selectedRowIndex == index,
                fontSize = fontSize,
                dateWidth = dateWidth,
                productNameWidth = productNameWidth,
                amountWidth = amountWidth,
                scrollState = scrollState,
                onRowClick = { onRowSelect(index) },
                onCellClick = { cellType ->
                    if (viewMode == ViewMode.EDIT) {
                        // セルタップで行選択を解除
                        onRowSelect(-1)

                        if (inputMode == InputMode.DIRECT) {
                            editingCell = EditingCell(index, row, cellType)
                        } else if (inputMode == InputMode.OCR) {
                            // データがあるかどうかをチェック
                            val hasData = rows.any {
                                it.date.isNotBlank() || it.productName.isNotBlank() || it.amount != 0
                            }

                            // データがある場合のみセルを選択可能（部分的な再OCR）
                            if (hasData) {
                                // OCRモードではセルをトグル
                                val newSelectedCells = if (row.selectedCells.contains(cellType)) {
                                    row.selectedCells - cellType
                                } else {
                                    row.selectedCells + cellType
                                }
                                val updatedRow = row.copy(selectedCells = newSelectedCells)
                                onRowUpdate(index, updatedRow)
                            }
                            // データがない場合は何もしない（全体OCR）
                        }
                    }
                },
                isAfterSubtotal = isAfterSubtotal
            )
            if (index < rows.size - 1) {
                Divider()
            }
        }
    }

    // セル編集ダイアログ
    editingCell?.let { editing ->
        CellEditDialog(
            cellType = editing.cellType,
            currentRow = editing.row,
            defaultYear = defaultYear,
            defaultMonth = defaultMonth,
            productMasterDao = productMasterDao,
            subtotalFlags = subtotalFlags,
            fixYearMonth = fixYearMonth,
            onDismiss = { editingCell = null },
            onConfirm = { updatedRow ->
                onRowUpdate(editing.rowIndex, updatedRow)
                editingCell = null
            }
        )
    }
}

/**
 * グリッドヘッダーセル
 */
@Composable
private fun GridHeaderCell(
    text: String,
    width: Dp,
    fontSize: Float
) {
    Row(
        modifier = Modifier.width(width),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(30.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

/**
 * データ行
 */
@Composable
private fun DataRow(
    row: ReceiptRowData,
    viewMode: ViewMode,
    inputMode: InputMode,
    isSelected: Boolean,
    fontSize: Float,
    dateWidth: Dp,
    productNameWidth: Dp,
    amountWidth: Dp,
    scrollState: ScrollState,
    onRowClick: () -> Unit,
    onCellClick: (CellType) -> Unit,
    isAfterSubtotal: Boolean = false
) {
    // セル選択時の背景色
    val selectedCellColor = Color(0xFFFFCDD2) // 赤色

    // 行選択状態の背景色（セル選択と重ねる）
    val rowBackgroundColor = when {
        row.isTotalRow -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) // 合計行は水色背景
        row.isSubtotal -> Color(0xFFE8F5E9) // 小計行は薄い緑背景
        isAfterSubtotal -> Color(0xFFF5F5F5) // 小計後の1行は薄いグレーアウト（編集不可）
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackgroundColor)
            .padding(vertical = 8.dp)
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GridDataCell(
            text = row.date,
            width = dateWidth,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            isClickable = viewMode == ViewMode.EDIT && (inputMode == InputMode.DIRECT || inputMode == InputMode.OCR) && !isAfterSubtotal,
            backgroundColor = if (inputMode == InputMode.OCR && row.selectedCells.contains(CellType.DATE))
                selectedCellColor else Color.Transparent,
            onClick = { onCellClick(CellType.DATE) },
            onLongClick = onRowClick
        )

        GridDataCell(
            text = when {
                row.isTotalRow -> "■　${row.productName}" // 合計行
                row.isSubtotal && row.subtotalCategory != null -> "＊　小計（　${row.subtotalCategory.displayName}　　　　　）"
                row.isSubtotal -> "[小計] ${row.productName}"
                else -> row.productName
            },
            width = productNameWidth,
            fontSize = fontSize,
            textAlign = TextAlign.Start,
            isClickable = viewMode == ViewMode.EDIT && (inputMode == InputMode.DIRECT || inputMode == InputMode.OCR) && !row.isTotalRow && !isAfterSubtotal,
            backgroundColor = if (inputMode == InputMode.OCR && row.selectedCells.contains(CellType.PRODUCT_NAME))
                selectedCellColor else Color.Transparent,
            onClick = { onCellClick(CellType.PRODUCT_NAME) },
            onLongClick = onRowClick
        )

        GridDataCell(
            text = if (row.amount != 0) "%,d".format(row.amount) else "",
            width = amountWidth,
            fontSize = fontSize,
            textAlign = TextAlign.End,
            isClickable = viewMode == ViewMode.EDIT && (inputMode == InputMode.DIRECT || inputMode == InputMode.OCR) && !isAfterSubtotal,
            backgroundColor = if (inputMode == InputMode.OCR && row.selectedCells.contains(CellType.AMOUNT))
                selectedCellColor else Color.Transparent,
            onClick = { onCellClick(CellType.AMOUNT) },
            onLongClick = { if (!row.isTotalRow) onRowClick() }
        )
    }
}

/**
 * グリッドデータセル
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridDataCell(
    text: String,
    width: Dp,
    fontSize: Float,
    textAlign: TextAlign = TextAlign.Start,
    isClickable: Boolean = false,
    backgroundColor: Color = Color.Transparent,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier.width(width),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(backgroundColor)
                .combinedClickable(
                    enabled = isClickable,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = text,
                fontSize = fontSize.sp,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                color = if (isClickable) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = androidx.compose.ui.text.TextStyle(
                    fontFeatureSettings = "tnum"  // 等幅数字を強制
                )
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        )
    }
}

/**
 * カメラビュー
 */
@Composable
private fun CameraView(
    onOcrComplete: (List<com.example.greenframeocr.viewmodel.OcrCaptureViewModel.ParsedRow>) -> Unit,
    onCancel: () -> Unit
) {
    val ocrScope = rememberCoroutineScope()
    var isProcessingOcr by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraScreenForOcr(
            onOcrComplete = { detectionResult ->
                isProcessingOcr = true
                ocrScope.launch {
                    val parsed = detectionResult.rowBitmaps.mapIndexed { index, rowBitmap ->
                        val w = rowBitmap.width
                        val h = rowBitmap.height
                        fun crop(xStart: Float, xEnd: Float): android.graphics.Bitmap {
                            val x = (xStart * w).toInt().coerceIn(0, w - 1)
                            val width = ((xEnd - xStart) * w).toInt().coerceIn(1, w - x)
                            return android.graphics.Bitmap.createBitmap(rowBitmap, x, 0, width, h)
                        }
                        val dateText   = com.example.greenframeocr.util.OCRProcessor.recognizeTextLatin(crop(0.02f, 0.10f))?.text?.trim()
                        val nameText   = com.example.greenframeocr.util.OCRProcessor.recognizeText(crop(0.10f, 0.46f))?.text?.trim()
                        val qtyText    = com.example.greenframeocr.util.OCRProcessor.recognizeTextLatin(crop(0.55f, 0.62f))?.text?.trim()
                        val priceText  = com.example.greenframeocr.util.OCRProcessor.recognizeTextLatin(crop(0.62f, 0.72f))?.text?.trim()
                        val amountText = com.example.greenframeocr.util.OCRProcessor.recognizeTextLatin(crop(0.72f, 0.83f))?.text?.trim()
                        val qty    = qtyText?.let    { com.example.greenframeocr.util.ValidationUtils.sanitizeNumber(it) }
                        val price  = priceText?.let  { com.example.greenframeocr.util.ValidationUtils.sanitizeNumber(it) }
                        val amount = amountText?.let { com.example.greenframeocr.util.ValidationUtils.sanitizeNumber(it) }
                        val isValid = qty != null && price != null && amount != null &&
                                com.example.greenframeocr.util.ValidationUtils.validateWithRounding(price, qty, amount)
                        com.example.greenframeocr.viewmodel.OcrCaptureViewModel.ParsedRow(
                            rowIndex = index, date = dateText, productName = nameText,
                            branch = null, quantity = qty, unitPrice = price,
                            amount = amount, isAmountValid = isValid
                        )
                    }
                    isProcessingOcr = false
                    onOcrComplete(parsed)
                }
            },
            onCancel = onCancel,
            modifier = Modifier.fillMaxSize()
        )

        if (isProcessingOcr) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        FloatingActionButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Close, "閉じる")
        }
    }
}

// ========== データ型定義 ==========

enum class ViewMode(val label: String) {
    VIEW("閲覧"),
    EDIT("編集中")
}

enum class InputMode(val label: String) {
    OCR("OCR"),
    DIRECT("直接")
}

enum class SubtotalCategory(val displayName: String) {
    GENERAL_PURCHASE("一般購買"),
    AGRICULTURAL_MACHINERY("農業機械"),
    GAS_STATION("給油所")
}

/**
 * 月単位の小計フラグ管理
 * メモリ上で動的に計算される（DB永続化不要）
 */
data class MonthlySubtotalFlags(
    val hasGeneralPurchase: Boolean = false,
    val hasAgriculturalMachinery: Boolean = false,
    val hasGasStation: Boolean = false
) {
    /**
     * 使用済みでなければ選択可能（順序は強制しない）
     */
    fun isAvailable(category: SubtotalCategory): Boolean = when (category) {
        SubtotalCategory.GENERAL_PURCHASE -> !hasGeneralPurchase
        SubtotalCategory.AGRICULTURAL_MACHINERY -> !hasAgriculturalMachinery
        SubtotalCategory.GAS_STATION -> !hasGasStation
    }

    /**
     * 利用可能なカテゴリ一覧（表示順序: 一般購買→農業機械→給油所）
     */
    fun availableCategories(): List<SubtotalCategory> =
        listOf(
            SubtotalCategory.GENERAL_PURCHASE,
            SubtotalCategory.AGRICULTURAL_MACHINERY,
            SubtotalCategory.GAS_STATION
        ).filter { isAvailable(it) }
}

data class ReceiptRowData(
    val rowNumber: Int,
    val date: String,
    val productName: String,
    val amount: Int,
    val isSubtotal: Boolean = false,
    val isTotalRow: Boolean = false,
    val subtotalCategory: SubtotalCategory? = null,
    val selectedCells: Set<CellType> = emptySet(),
    val category: String = "未分類",  // データベースから読み込まれたカテゴリ
    // V3: 学習登録用
    val originalOcrName: String? = null,  // OCR取得時の原本（編集不可）
    val productMasterId: Long? = null     // 商品マスタID（確定時）
)

enum class CellType {
    DATE,
    PRODUCT_NAME,
    AMOUNT
}

data class EditingCell(
    val rowIndex: Int,
    val row: ReceiptRowData,
    val cellType: CellType
)

// ========== ユーティリティ関数 ==========

/**
 * 検証結果データ（全伝票対応版）
 */
data class ValidationResult(
    val isValid: Boolean,
    val message: String,
    val calculatedTotal: Int,
    val enteredTotal: Int,
    val subtotalValidations: List<SubtotalValidation> = emptyList(),
    val categoryBreakdowns: List<CategoryBreakdown> = emptyList(),
    val totalBreakdown: TotalBreakdown? = null
)

/**
 * 小計検証データ（旧形式、互換性のため残す）
 */
data class SubtotalValidation(
    val categoryName: String,
    val calculatedAmount: Int,
    val enteredAmount: Int,
    val isValid: Boolean
)

/**
 * カテゴリ別内訳データ
 */
data class CategoryBreakdown(
    val categoryName: String,
    val enteredSubtotal: Int,
    val calculatedSubtotal: Int,
    val sheetBreakdowns: List<SheetAmount>,
    val isValid: Boolean
)

/**
 * 合計内訳データ
 */
data class TotalBreakdown(
    val enteredTotal: Int,
    val calculatedTotal: Int,
    val sheetBreakdowns: List<SheetAmount>,
    val isValid: Boolean
)

/**
 * 伝票別金額
 */
data class SheetAmount(
    val sheetNumber: Int,
    val amount: Int
)

/**
 * 全伝票の小計・合計を検証（内訳付き）
 * メモリ上のデータは既にカテゴリが設定されているので、直接使用
 */
private fun validateAllSheetsData(allSheetsData: Map<Int, List<ReceiptRowData>>): ValidationResult {
    if (allSheetsData.isEmpty()) {
        return ValidationResult(
            isValid = true,
            message = "データなし",
            calculatedTotal = 0,
            enteredTotal = 0,
            categoryBreakdowns = emptyList(),
            totalBreakdown = null
        )
    }

    // カテゴリごとに集計
    val categoryData = mutableMapOf<String, MutableList<SheetAmount>>()
    val categoryEnteredSubtotals = mutableMapOf<String, Int>()
    val allCategories = mutableSetOf<String>()
    val sheetTotals = mutableListOf<SheetAmount>()
    var enteredTotal = 0

    allSheetsData.forEach { (sheetNumber, rows) ->
        val dataRows = rows.take(20)
        val totalRow = rows.getOrNull(20)

        // 1枚目の合計行から入力値を取得
        if (sheetNumber == 1 && totalRow != null && totalRow.isTotalRow) {
            enteredTotal = totalRow.amount
        }

        val sheetCategoryTotals = mutableMapOf<String, Int>()

        dataRows.forEach { row ->
            if (row.isSubtotal) {
                // 小計行：入力値を記録（未分類の場合は商品名から再検出）
                val category = if (row.category == "未分類" || row.category.isEmpty()) {
                    detectCategoryFromProductName(row.productName)
                } else {
                    row.category
                }
                allCategories.add(category)
                categoryEnteredSubtotals[category] =
                    (categoryEnteredSubtotals[category] ?: 0) + row.amount
            } else if (row.amount != 0) {
                // 通常行：カテゴリに加算（メモリ上のcategoryフィールドを使用）
                val category = row.category
                if (category.isNotEmpty() && category != "未定" && category != "未分類" && category != "") {
                    sheetCategoryTotals[category] =
                        (sheetCategoryTotals[category] ?: 0) + row.amount
                }
            }
        }

        // 各カテゴリの合計を記録
        sheetCategoryTotals.forEach { (categoryName, total) ->
            allCategories.add(categoryName)
            categoryData.getOrPut(categoryName) { mutableListOf() }
                .add(SheetAmount(sheetNumber, total))
        }

        // 伝票全体の合計
        val sheetTotal = dataRows.filter { !it.isSubtotal && it.amount != 0 }.sumOf { it.amount }
        if (sheetTotal != 0) {
            sheetTotals.add(SheetAmount(sheetNumber, sheetTotal))
        }
    }

    // カテゴリ別内訳を作成（すべてのカテゴリを含む）
    val categoryBreakdowns = allCategories.map { categoryName ->
        val sheetAmounts = categoryData[categoryName] ?: emptyList()
        val calculatedSubtotal = sheetAmounts.sumOf { it.amount }
        val enteredSubtotal = categoryEnteredSubtotals[categoryName] ?: 0

        CategoryBreakdown(
            categoryName = categoryName,
            enteredSubtotal = enteredSubtotal,
            calculatedSubtotal = calculatedSubtotal,
            sheetBreakdowns = sheetAmounts,
            isValid = calculatedSubtotal == enteredSubtotal
        )
    }.sortedBy { it.categoryName }  // カテゴリ名でソート

    // 合計内訳を作成
    val calculatedTotal = sheetTotals.sumOf { it.amount }
    val totalBreakdown = TotalBreakdown(
        enteredTotal = enteredTotal,
        calculatedTotal = calculatedTotal,
        sheetBreakdowns = sheetTotals,
        isValid = calculatedTotal == enteredTotal
    )

    val message = when {
        enteredTotal == 0 && calculatedTotal == 0 -> "データなし"
        calculatedTotal == enteredTotal -> "✓ 合計金額が一致しています"
        else -> "× 合計金額が一致しません (差額: ${"%,d".format(calculatedTotal - enteredTotal)}円)"
    }

    return ValidationResult(
        isValid = calculatedTotal == enteredTotal,
        message = message,
        calculatedTotal = calculatedTotal,
        enteredTotal = enteredTotal,
        categoryBreakdowns = categoryBreakdowns,
        totalBreakdown = totalBreakdown
    )
}

/**
 * 小計・合計の検証（単一伝票版、旧形式）
 */
private fun validateReceiptData(rows: List<ReceiptRowData>): ValidationResult {
    // 20行のデータ行と合計行を分離
    val dataRows = rows.take(20)
    val totalRow = rows.getOrNull(20)

    // 小計の検証
    val subtotalValidations = mutableListOf<SubtotalValidation>()
    var currentCategorySum = 0
    var lastSubtotalRow: ReceiptRowData? = null

    dataRows.forEachIndexed { index, row ->
        if (row.isSubtotal) {
            // 前回の小計から今回の小計までの金額を合計
            val enteredSubtotal = row.amount
            val categoryName = row.productName.ifBlank { "小計" }

            subtotalValidations.add(
                SubtotalValidation(
                    categoryName = categoryName,
                    calculatedAmount = currentCategorySum,
                    enteredAmount = enteredSubtotal,
                    isValid = currentCategorySum == enteredSubtotal
                )
            )

            // リセット
            currentCategorySum = 0
            lastSubtotalRow = row
        } else if (row.amount != 0) {
            // 通常行の金額を加算
            currentCategorySum += row.amount
        }
    }

    // 合計金額を計算（小計を除く全ての金額を合計）
    val calculatedTotal = dataRows.filter { !it.isSubtotal && it.amount != 0 }.sumOf { it.amount }
    val enteredTotal = totalRow?.amount ?: 0

    // 合計検証メッセージ
    val message = when {
        enteredTotal == 0 && calculatedTotal == 0 -> "データなし"
        calculatedTotal == enteredTotal -> "✓ 合計金額が一致しています"
        else -> "× 合計金額が一致しません (差額: ${"%,d".format(calculatedTotal - enteredTotal)}円)"
    }

    return ValidationResult(
        isValid = calculatedTotal == enteredTotal,
        message = message,
        calculatedTotal = calculatedTotal,
        enteredTotal = enteredTotal,
        subtotalValidations = subtotalValidations
    )
}

private fun emptyReceiptRows(sheetNumber: Int = 1): List<ReceiptRowData> {
    val dataRows = List(20) { index ->
        ReceiptRowData(
            rowNumber = index + 1,
            date = "",
            productName = "",
            amount = 0,
            isSubtotal = false,
            isTotalRow = false,
            selectedCells = emptySet()
        )
    }

    // 合計行は一枚目のみ
    return if (sheetNumber == 1) {
        dataRows + ReceiptRowData(
            rowNumber = 21,
            date = "",
            productName = "合計",
            amount = 0,
            isSubtotal = false,
            isTotalRow = true,
            selectedCells = emptySet()
        )
    } else {
        dataRows
    }
}

/**
 * 商品名（小計行）からカテゴリを検出
 * 小計行は必ず3つのカテゴリのいずれかに属する
 */
/**
 * メモリ上の全伝票データから小計フラグを計算
 */
private fun calculateSubtotalFlags(
    allSheetsData: Map<Int, List<ReceiptRowData>>
): MonthlySubtotalFlags {
    var hasGeneral = false
    var hasAgri = false
    var hasGas = false

    allSheetsData.values.flatten()
        .filter { it.isSubtotal }
        .forEach { row ->
            when (row.subtotalCategory) {
                SubtotalCategory.GENERAL_PURCHASE -> hasGeneral = true
                SubtotalCategory.AGRICULTURAL_MACHINERY -> hasAgri = true
                SubtotalCategory.GAS_STATION -> hasGas = true
                null -> {
                    // subtotalCategoryがnullの場合、categoryフィールドから判定
                    when (row.category) {
                        "一般購買" -> hasGeneral = true
                        "農業機械" -> hasAgri = true
                        "給油所" -> hasGas = true
                    }
                }
            }
        }

    return MonthlySubtotalFlags(
        hasGeneralPurchase = hasGeneral,
        hasAgriculturalMachinery = hasAgri,
        hasGasStation = hasGas
    )
}

/**
 * 一文字でもカテゴリを判別
 * 各カテゴリには固有文字があるため、一文字でも判別可能
 */
private fun detectCategoryBySingleChar(text: String): SubtotalCategory? {
    // 一般購買の固有文字（誤認識パターンも含む）
    val generalChars = setOf('般', '購', '買', '講', '課')

    // 農業機械の固有文字（誤認識パターンも含む）
    val agriChars = setOf('農', '機', '械', '展', '慢', '城', '来', '発', '検')

    // 給油所の固有文字（誤認識パターンも含む）
    val gasChars = setOf('給', '油', '所', '値', '造', '治', '抽')

    for (char in text) {
        when {
            generalChars.contains(char) -> return SubtotalCategory.GENERAL_PURCHASE
            agriChars.contains(char) -> return SubtotalCategory.AGRICULTURAL_MACHINERY
            gasChars.contains(char) -> return SubtotalCategory.GAS_STATION
        }
    }

    return null  // 判別不可
}

private fun detectCategoryFromProductName(productName: String): String {
    android.util.Log.d("ReceiptInputScreen", "detectCategoryFromProductName: productName='$productName'")

    val result = when {
        // 給油所の誤認識パターン（優先的にチェック）
        productName.contains("給油所") ||
        productName.contains("給値所") ||
        productName.contains("給造所") ||
        productName.contains("給治所") ||
        productName.contains("給抽所") ||
        productName.contains("給油") -> "給油所"

        // 農業機械の誤認識パターン
        productName.contains("農業機械") ||
        productName.contains("展業慢城") ||
        productName.contains("農来") ||
        productName.contains("農発検") ||
        productName.contains("農業機械") -> "農業機械"

        // 一般購買の誤認識パターン（厳格化：2文字以上の組み合わせ）
        productName.contains("一般購買") ||
        productName.contains("一般買") ||
        productName.contains("一般講買") ||
        productName.contains("一般課買") ||
        productName.contains("ー般講買") ||
        productName.contains("ー般購買") ||
        productName.contains("般購買") ||
        productName.contains("般講買") -> "一般購買"

        // パターンマッチ失敗時は一文字検出を試行
        else -> {
            val singleCharResult = detectCategoryBySingleChar(productName)
            singleCharResult?.displayName ?: "未分類"
        }
    }

    android.util.Log.d("ReceiptInputScreen", "detectCategoryFromProductName: result='$result'")
    return result
}

/**
 * メモリ上の全伝票データのカテゴリをリアルタイム再計算
 * 小計行の位置と内容に基づいて通常行のカテゴリを更新
 * 前の伝票も含めて遡って更新する
 */
private fun recalculateCategoriesInMemory(
    allSheetsData: Map<Int, List<ReceiptRowData>>
): Map<Int, List<ReceiptRowData>> {
    android.util.Log.d("ReceiptInputScreen", "=== recalculateCategoriesInMemory: 全伝票再計算開始 ===")

    // ステップ1: 全伝票から小計を収集（伝票番号順、行番号順）
    data class SubtotalInfo(
        val sheetNumber: Int,
        val rowIndex: Int,
        val category: String
    )
    val subtotals = mutableListOf<SubtotalInfo>()

    allSheetsData.toSortedMap().forEach { (sheetNumber, rows) ->
        rows.take(20).forEachIndexed { index, row ->
            if (row.isSubtotal) {
                val category = if (row.category == "未分類" || row.category.isEmpty()) {
                    detectCategoryFromProductName(row.productName)
                } else {
                    row.category
                }
                subtotals.add(SubtotalInfo(sheetNumber, index, category))
                android.util.Log.d("ReceiptInputScreen", "小計発見: 伝票$sheetNumber 行$index → $category")
            }
        }
    }

    android.util.Log.d("ReceiptInputScreen", "小計一覧: ${subtotals.map { "${it.sheetNumber}-${it.rowIndex}:${it.category}" }}")

    // ステップ2: 各行にカテゴリを割り当て
    val sheetRowCategories = mutableMapOf<Int, MutableList<String>>()

    allSheetsData.toSortedMap().forEach { (sheetNumber, rows) ->
        val rowCategories = mutableListOf<String>()
        rows.take(20).forEachIndexed { index, row ->
            when {
                row.isSubtotal -> {
                    // 小計行自身のカテゴリ
                    val subtotalInfo = subtotals.find { it.sheetNumber == sheetNumber && it.rowIndex == index }
                    rowCategories.add(subtotalInfo?.category ?: "未分類")
                }
                row.amount != 0 -> {
                    // 通常行: 次の小計のカテゴリを探す
                    val nextSubtotal = subtotals.find { subtotal ->
                        subtotal.sheetNumber > sheetNumber ||
                        (subtotal.sheetNumber == sheetNumber && subtotal.rowIndex > index)
                    }
                    val category = nextSubtotal?.category ?: "未定"
                    rowCategories.add(category)
                }
                else -> {
                    rowCategories.add("")
                }
            }
        }
        sheetRowCategories[sheetNumber] = rowCategories
    }

    // ステップ3: 最初の小計より前の行を遡って更新（前の伝票も含む）
    if (subtotals.isNotEmpty()) {
        val firstSubtotal = subtotals.first()
        android.util.Log.d("ReceiptInputScreen", "最初の小計: 伝票${firstSubtotal.sheetNumber} 行${firstSubtotal.rowIndex} → ${firstSubtotal.category}")

        // 最初の小計より前の全ての行を更新
        sheetRowCategories.toSortedMap().forEach { (sheetNumber, rowCategories) ->
            if (sheetNumber < firstSubtotal.sheetNumber) {
                // 最初の小計より前の伝票: 全行を更新
                for (i in rowCategories.indices) {
                    if (rowCategories[i] != "" && rowCategories[i] != firstSubtotal.category) {
                        android.util.Log.d("ReceiptInputScreen", "遡り更新: 伝票$sheetNumber 行$i: ${rowCategories[i]} → ${firstSubtotal.category}")
                        rowCategories[i] = firstSubtotal.category
                    }
                }
            } else if (sheetNumber == firstSubtotal.sheetNumber) {
                // 最初の小計がある伝票: 小計より前の行を更新
                for (i in 0 until firstSubtotal.rowIndex) {
                    if (rowCategories[i] != "" && rowCategories[i] != firstSubtotal.category) {
                        android.util.Log.d("ReceiptInputScreen", "遡り更新: 伝票$sheetNumber 行$i: ${rowCategories[i]} → ${firstSubtotal.category}")
                        rowCategories[i] = firstSubtotal.category
                    }
                }
            }
        }
    }

    // ステップ4: カテゴリを反映した新しいデータを作成
    val updatedSheetsData = mutableMapOf<Int, List<ReceiptRowData>>()
    allSheetsData.forEach { (sheetNumber, rows) ->
        val rowCategories = sheetRowCategories[sheetNumber] ?: mutableListOf()
        val dataRows = rows.take(20)
        val totalRow = rows.getOrNull(20)

        val updatedDataRows = dataRows.mapIndexed { index, row ->
            if (index < rowCategories.size && rowCategories[index].isNotEmpty()) {
                row.copy(category = rowCategories[index])
            } else {
                row
            }
        }

        val updatedRows = if (totalRow != null) {
            updatedDataRows + totalRow
        } else {
            updatedDataRows
        }

        updatedSheetsData[sheetNumber] = updatedRows
    }

    android.util.Log.d("ReceiptInputScreen", "=== recalculateCategoriesInMemory: 完了 ===")
    return updatedSheetsData
}

private fun convertParsedRowsToRowData(
    parsedRows: List<com.example.greenframeocr.viewmodel.OcrCaptureViewModel.ParsedRow>,
    sheetNumber: Int = 1,
    fixYearMonth: Boolean = false,
    defaultYear: Int = 7,
    defaultMonth: Int = 1
): List<ReceiptRowData> {
    val normalAndSubtotalRows = parsedRows
    val monthlyTotalRow: com.example.greenframeocr.viewmodel.OcrCaptureViewModel.ParsedRow? = null

    // 小計行の後に空白行を挿入する処理
    val rowsWithBlankAfterSubtotal = mutableListOf<ReceiptRowData>()
    var rowNumber = 1

    for (row in normalAndSubtotalRows) {
        // 取引日テキスト → フォーマット変換
        val digits = row.date?.filter { it.isDigit() } ?: ""
        val formattedDate = when {
            fixYearMonth && digits.length >= 2 -> {
                val day = digits.takeLast(2).toIntOrNull()?.coerceIn(1, 31) ?: 1
                "%02d/%02d/%02d".format(defaultYear % 100, defaultMonth, day)
            }
            digits.length >= 6 ->
                "${digits.substring(0, 2)}/${digits.substring(2, 4)}/${digits.substring(4, 6)}"
            else -> row.date ?: ""
        }

        val productName = row.productName ?: ""
        val finalAmount = row.amount ?: 0

        rowsWithBlankAfterSubtotal.add(
            ReceiptRowData(
                rowNumber = rowNumber++,
                date = formattedDate,
                productName = productName,
                amount = finalAmount,
                isSubtotal = false,
                isTotalRow = false,
                selectedCells = emptySet(),
                category = "未分類",
                subtotalCategory = null,
                originalOcrName = if (productName.isNotBlank()) productName else null
            )
        )

        // 20行に達したら終了
        if (rowsWithBlankAfterSubtotal.size >= 20) break
    }

    // 20行に満たない場合は空白行で埋める
    val dataRows = rowsWithBlankAfterSubtotal + (rowsWithBlankAfterSubtotal.size until 20).map { index ->
        ReceiptRowData(
            rowNumber = index + 1,
            date = "",
            productName = "",
            amount = 0,
            isSubtotal = false,
            isTotalRow = false,
            selectedCells = emptySet()
        )
    }

    // 合計行は一枚目のみ追加
    return if (sheetNumber == 1) {
        // OCRで読み取った月合計を使用（新フローでは常に0）
        val totalAmount = 0

        // 合計行を追加
        val totalRow = ReceiptRowData(
            rowNumber = 21,
            date = "",
            productName = "合計",
            amount = totalAmount,
            isSubtotal = false,
            isTotalRow = true,
            selectedCells = emptySet()
        )

        dataRows + totalRow
    } else {
        dataRows
    }
}

/**
 * 月全体のデータをロード
 */
private suspend fun loadMonthData(
    database: com.example.greenframeocr.data.ReceiptDatabase,
    year: Int,
    month: Int,
    onDataLoaded: (totalSheets: Int, sheetsData: Map<Int, List<ReceiptRowData>>) -> Unit
) {
    withContext(Dispatchers.IO) {
        val monthlyData = database.receiptDao().getMonthlyData("${year}_${month}")
        val totalSheets = monthlyData?.totalSheets ?: 0

        if (totalSheets == 0) {
            withContext(Dispatchers.Main) {
                onDataLoaded(0, emptyMap())
            }
        } else {
            val sheetsData = mutableMapOf<Int, List<ReceiptRowData>>()

            for (sheetNumber in 1..totalSheets) {
                val items = database.receiptDao().getReceiptItemsBySheet(year, month, sheetNumber)
                val rows = convertReceiptItemsToRows(items, sheetNumber)
                sheetsData[sheetNumber] = rows
            }

            withContext(Dispatchers.Main) {
                onDataLoaded(totalSheets, sheetsData)
            }
        }
    }
}

private fun convertReceiptItemsToRows(items: List<com.example.greenframeocr.data.ReceiptItem>, sheetNumber: Int = 1): List<ReceiptRowData> {
    val dataRows = (0 until 20).map { index ->
        val item = items.find { it.itemNumber == index + 1 }
        if (item != null) {
            val isSubtotal = item.productName.startsWith("[小計]")
            val productName = if (isSubtotal) {
                item.productName.removePrefix("[小計] ").trim()
            } else {
                item.productName
            }

            // 小計行の場合、categoryからSubtotalCategoryを復元
            val subtotalCategory = if (isSubtotal) {
                when (item.category) {
                    "一般購買" -> SubtotalCategory.GENERAL_PURCHASE
                    "給油所" -> SubtotalCategory.GAS_STATION
                    "農業機械" -> SubtotalCategory.AGRICULTURAL_MACHINERY
                    else -> SubtotalCategory.GENERAL_PURCHASE
                }
            } else {
                null
            }

            ReceiptRowData(
                rowNumber = index + 1,
                date = if (isSubtotal) "" else "%02d/%02d/%02d".format(item.receiptYear, item.receiptMonth, item.receiptDay),
                productName = productName,
                amount = item.amount,
                isSubtotal = isSubtotal,
                isTotalRow = false,
                selectedCells = emptySet(),
                category = item.category,  // データベースのカテゴリをコピー
                subtotalCategory = subtotalCategory  // 小計行の場合はSubtotalCategoryも設定
            )
        } else {
            ReceiptRowData(
                rowNumber = index + 1,
                date = "",
                productName = "",
                amount = 0,
                isSubtotal = false,
                isTotalRow = false,
                selectedCells = emptySet()
            )
        }
    }

    // 合計行は一枚目のみ追加
    return if (sheetNumber == 1) {
        // 合計行を追加（DBから読み込む場合は21行目のデータを探す、または商品名が「合計」のものを探す）
        val totalItem = items.find { it.itemNumber == 21 || it.productName == "合計" }
        val totalRow = if (totalItem != null) {
            ReceiptRowData(
                rowNumber = 21,
                date = "",
                productName = totalItem.productName,
                amount = totalItem.amount,
                isSubtotal = false,
                isTotalRow = true,
                selectedCells = emptySet()
            )
        } else {
            // 合計行がDB上にない場合は計算して作成
            val totalAmount = dataRows.filter { !it.isSubtotal }.sumOf { it.amount }
            ReceiptRowData(
                rowNumber = 21,
                date = "",
                productName = "合計",
                amount = totalAmount,
                isSubtotal = false,
                isTotalRow = true,
                selectedCells = emptySet()
            )
        }

        dataRows + totalRow
    } else {
        dataRows
    }
}

/**
 * 月全体のデータを保存
 *
 * V3: コミット時に手動修正を学習登録
 */
private suspend fun saveMonthData(
    database: com.example.greenframeocr.data.ReceiptDatabase,
    year: Int,
    month: Int,
    allSheetsData: Map<Int, List<ReceiptRowData>>
) {
    android.util.Log.d("ReceiptInputScreen", "saveMonthData called: year=$year, month=$month, sheets=${allSheetsData.keys}")

    // V3: コミットバッチID生成
    val commitBatchId = "${year}_${month}_${System.currentTimeMillis()}"
    withContext(Dispatchers.IO) {
        allSheetsData.forEach { (sheetNumber, rows) ->
            android.util.Log.d("ReceiptInputScreen", "Processing sheet $sheetNumber with ${rows.size} rows")
            // 既存データを削除
            database.receiptDao().deleteReceiptItemsBySheet(year, month, sheetNumber)
            android.util.Log.d("ReceiptInputScreen", "Deleted existing data for sheet $sheetNumber")

            // 新しいデータを挿入（合計行も含む）
            val items = rows
                .filter { it.date.isNotBlank() || it.productName.isNotBlank() || it.amount != 0 || it.isTotalRow }
                .map { row ->
                    val dateParts = row.date.split("/")
                    val receiptYear = dateParts.getOrNull(0)?.toIntOrNull() ?: year
                    val receiptMonth = dateParts.getOrNull(1)?.toIntOrNull() ?: month
                    val receiptDay = dateParts.getOrNull(2)?.toIntOrNull() ?: 1

                    val productName = when {
                        row.isTotalRow -> row.productName // 合計行は名前をそのまま保存
                        row.isSubtotal -> "[小計] ${row.productName}"
                        else -> row.productName
                    }

                    com.example.greenframeocr.data.ReceiptItem(
                        issueYear = year,
                        issueMonth = month,
                        sheetNumber = sheetNumber,
                        itemNumber = row.rowNumber,
                        receiptYear = receiptYear,
                        receiptMonth = receiptMonth,
                        receiptDay = receiptDay,
                        productName = productName,
                        amount = row.amount,
                        category = row.category,  // 既存のカテゴリを保持（新規は「未分類」）
                        isOcrOverwriteTarget = false
                    )
                }

            android.util.Log.d("ReceiptInputScreen", "Filtered items count: ${items.size}")
            if (items.isNotEmpty()) {
                database.receiptDao().insertReceiptItems(items)
                android.util.Log.d("ReceiptInputScreen", "Inserted ${items.size} items for sheet $sheetNumber")
            } else {
                android.util.Log.d("ReceiptInputScreen", "No items to insert for sheet $sheetNumber")
            }
        }

        // MonthlyDataの更新（totalSheetsを保存）
        val totalSheets = allSheetsData.keys.maxOrNull() ?: 0
        if (totalSheets > 0) {
            val monthlyDataId = "${year}_${month}"
            val existingMonthlyData = database.receiptDao().getMonthlyData(monthlyDataId)

            if (existingMonthlyData == null) {
                // 新規作成
                database.receiptDao().insertMonthlyData(
                    com.example.greenframeocr.data.MonthlyData(
                        id = monthlyDataId,
                        issueYear = year,
                        issueMonth = month,
                        totalSheets = totalSheets,
                        generalPurchaseTotal = 0,
                        agriculturalTotal = 0,
                        gasStationTotal = 0,
                        monthlyTotal = 0
                    )
                )
                android.util.Log.d("ReceiptInputScreen", "Created MonthlyData: totalSheets=$totalSheets")
            } else if (existingMonthlyData.totalSheets != totalSheets) {
                // 更新
                database.receiptDao().updateMonthlyData(
                    existingMonthlyData.copy(totalSheets = totalSheets)
                )
                android.util.Log.d("ReceiptInputScreen", "Updated MonthlyData: totalSheets=$totalSheets")
            }
        }

        // V3: 手動修正の学習登録
        registerManualCorrectionsOnCommit(database, allSheetsData, commitBatchId)
    }
    android.util.Log.d("ReceiptInputScreen", "saveMonthData completed")
}

/**
 * V3: コミット時に手動修正を学習登録
 *
 * 条件:
 * - originalOcrNameがある
 * - productMasterIdがある
 * - originalOcrNameとproductNameが異なる（手動修正された）
 * - originalOcrNameが3文字以上
 */
private suspend fun registerManualCorrectionsOnCommit(
    database: com.example.greenframeocr.data.ReceiptDatabase,
    allSheetsData: Map<Int, List<ReceiptRowData>>,
    commitBatchId: String
) {
    val variantDao = database.ocrVariantDao()
    var registeredCount = 0

    for ((_, rows) in allSheetsData) {
        for (row in rows) {
            // 条件チェック
            if (row.originalOcrName == null) continue
            if (row.productMasterId == null) continue
            if (row.originalOcrName == row.productName) continue  // 変更なし
            if (row.isTotalRow || row.isSubtotal) continue

            // 正規化テキスト
            val normalizedText = normalizeForLearning(row.originalOcrName)
            if (normalizedText.length < 3) continue  // 3文字未満は学習対象外

            try {
                variantDao.registerManualCorrection(
                    ocrText = row.originalOcrName,
                    normalizedText = normalizedText,
                    correctProductId = row.productMasterId,
                    commitBatchId = commitBatchId
                )
                registeredCount++
                android.util.Log.d(
                    "ReceiptInputScreen",
                    "[V3-LEARN] Manual correction: '${row.originalOcrName}' -> productId=${row.productMasterId}"
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "ReceiptInputScreen",
                    "[V3-LEARN] Failed: ${e.message}"
                )
            }
        }
    }

    android.util.Log.d("ReceiptInputScreen", "[V3-LEARN] Registered $registeredCount manual corrections")
}

/**
 * V3: 学習用正規化
 *
 * 処理内容:
 * 1. 空白除去
 * 2. 記号除去
 * 3. 末尾の単独英字除去（OCRゴミ対策: 「灯油H」→「灯油」）
 * 4. 先頭の単独英字除去（OCRゴミ対策: 「p灯油」→「灯油」）
 */
private fun normalizeForLearning(text: String): String {
    return text
        .replace(Regex("[\\s　]"), "")  // 空白除去
        .replace(Regex("[^一-龯ぁ-んァ-ンa-zA-Z0-9]"), "")  // 記号除去
        .replace(Regex("[a-zA-Z]$"), "")  // 末尾の単独英字除去
        .replace(Regex("^[a-zA-Z]"), "")  // 先頭の単独英字除去
}

private suspend fun addNewSheet(
    database: com.example.greenframeocr.data.ReceiptDatabase,
    year: Int,
    month: Int
): Pair<Int, Int> {
    return withContext(Dispatchers.IO) {
        val maxSheetNumber = database.receiptDao().getMaxSheetNumberForMonth(year, month)
        val newSheetNumber = maxSheetNumber + 1
        val newTotalSheets = newSheetNumber

        val monthlyDataId = "${year}_${month}"
        val monthlyData = database.receiptDao().getMonthlyData(monthlyDataId)

        if (monthlyData == null) {
            database.receiptDao().insertMonthlyData(
                com.example.greenframeocr.data.MonthlyData(
                    id = monthlyDataId,
                    issueYear = year,
                    issueMonth = month,
                    totalSheets = newTotalSheets,
                    generalPurchaseTotal = 0,
                    agriculturalTotal = 0,
                    gasStationTotal = 0,
                    monthlyTotal = 0
                )
            )
        } else {
            database.receiptDao().updateMonthlyData(
                monthlyData.copy(totalSheets = newTotalSheets)
            )
        }

        Pair(newSheetNumber, newTotalSheets)
    }
}

private suspend fun deleteSheet(
    database: com.example.greenframeocr.data.ReceiptDatabase,
    year: Int,
    month: Int,
    sheetNumber: Int
): Int {
    return withContext(Dispatchers.IO) {
        database.receiptDao().deleteReceiptItemsBySheet(year, month, sheetNumber)
        database.receiptDao().deleteSheetData(year, month, sheetNumber)

        val monthlyDataId = "${year}_${month}"
        val monthlyData = database.receiptDao().getMonthlyData(monthlyDataId)

        if (monthlyData != null) {
            val newTotalSheets = (monthlyData.totalSheets - 1).coerceAtLeast(0)
            database.receiptDao().updateMonthlyData(
                monthlyData.copy(totalSheets = newTotalSheets)
            )
            newTotalSheets
        } else {
            0
        }
    }
}

/**
 * セル編集ダイアログ（小計フラグ追加版）
 */
@Composable
private fun CellEditDialog(
    cellType: CellType,
    currentRow: ReceiptRowData,
    defaultYear: Int,
    defaultMonth: Int,
    productMasterDao: com.example.greenframeocr.data.ProductMasterDao,
    subtotalFlags: MonthlySubtotalFlags,
    fixYearMonth: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (ReceiptRowData) -> Unit
) {
    // 年月固定時は日のみを抽出して初期値にする
    val initialDateValue = when {
        cellType == CellType.DATE && fixYearMonth && currentRow.date.isNotBlank() -> {
            // "YY/MM/DD" から日だけを抽出
            val parts = currentRow.date.split("/")
            if (parts.size == 3) parts[2] else currentRow.date.replace("/", "")
        }
        cellType == CellType.DATE -> currentRow.date.replace("/", "")
        else -> ""
    }

    var inputValue by remember {
        mutableStateOf(
            when (cellType) {
                CellType.DATE -> initialDateValue
                CellType.PRODUCT_NAME -> currentRow.productName
                CellType.AMOUNT -> if (currentRow.amount != 0) Math.abs(currentRow.amount).toString() else ""
            }
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isNegative by remember { mutableStateOf(currentRow.amount < 0) }
    var isSubtotal by remember { mutableStateOf(currentRow.isSubtotal) }
    var selectedSubtotalCategory by remember { mutableStateOf(currentRow.subtotalCategory ?: SubtotalCategory.GENERAL_PURCHASE) }
    var productList by remember { mutableStateOf<List<com.example.greenframeocr.data.ProductMaster>>(emptyList()) }
    var showDropdown by remember { mutableStateOf(false) }
    var selectedProductId by remember { mutableStateOf<Long?>(currentRow.productMasterId) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(cellType) {
        if (cellType == CellType.PRODUCT_NAME) {
            productList = productMasterDao.getAll()
        }
    }

    // 年月固定時の最大日数を計算
    val maxDayInMonth = remember(defaultYear, defaultMonth) {
        val fullYear = if (defaultYear < 50) 2000 + defaultYear else 1900 + defaultYear
        when (defaultMonth) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (fullYear % 4 == 0 && (fullYear % 100 != 0 || fullYear % 400 == 0)) 29 else 28
            else -> 31
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    cellType == CellType.DATE && fixYearMonth -> "日を入力（R${defaultYear}年${defaultMonth}月）"
                    cellType == CellType.DATE -> "取引日を編集"
                    cellType == CellType.PRODUCT_NAME -> "商品名を編集"
                    cellType == CellType.AMOUNT -> "税込金額を編集"
                    else -> ""
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 商品名編集の場合は一番上にラジオボタン配置（合計行は除外）
                if (cellType == CellType.PRODUCT_NAME && !currentRow.isTotalRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isSubtotal = false },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !isSubtotal,
                                onClick = { isSubtotal = false }
                            )
                            Text("購買品", fontSize = 14.sp)
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isSubtotal = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSubtotal,
                                onClick = { isSubtotal = true }
                            )
                            Text("小計", fontSize = 14.sp)
                        }
                    }
                }

                Box {
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = {
                            inputValue = it
                            errorMessage = null
                        },
                        label = {
                            Text(
                                when {
                                    cellType == CellType.DATE && fixYearMonth -> "日を入力（1〜${maxDayInMonth}）"
                                    cellType == CellType.DATE -> "数字のみ（1〜6桁）"
                                    cellType == CellType.PRODUCT_NAME -> "商品名"
                                    cellType == CellType.AMOUNT -> "金額（半角数字）"
                                    else -> ""
                                }
                            )
                        },
                        placeholder = {
                            Text(
                                when {
                                    cellType == CellType.DATE && fixYearMonth -> "例: 1, 15, 31"
                                    cellType == CellType.DATE -> "例: 070105 / 70105 / 0105 / 105 / 05 / 5"
                                    cellType == CellType.PRODUCT_NAME -> "商品名を入力"
                                    cellType == CellType.AMOUNT -> "例: 1000"
                                    else -> ""
                                }
                            )
                        },
                        isError = errorMessage != null,
                        singleLine = cellType != CellType.PRODUCT_NAME,
                        maxLines = if (cellType == CellType.PRODUCT_NAME) 2 else 1,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (cellType) {
                                CellType.DATE -> KeyboardType.Number
                                CellType.AMOUNT -> KeyboardType.Number
                                CellType.PRODUCT_NAME -> KeyboardType.Text
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (cellType == CellType.PRODUCT_NAME && showDropdown) {
                        val filteredProducts = productList.filter {
                            it.canonicalName.contains(inputValue, ignoreCase = true)
                        }.take(10)

                        if (filteredProducts.isNotEmpty()) {
                            DropdownMenu(
                                expanded = true,
                                onDismissRequest = { showDropdown = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                filteredProducts.forEach { product ->
                                    DropdownMenuItem(
                                        text = { Text(product.canonicalName) },
                                        onClick = {
                                            inputValue = product.canonicalName
                                            selectedProductId = product.id
                                            showDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 商品名入力時のボタン（合計行は除外）
                if (cellType == CellType.PRODUCT_NAME && !currentRow.isTotalRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                inputValue = ""
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("クリア")
                        }

                        Button(
                            onClick = {
                                showDropdown = !showDropdown
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (showDropdown) "閉じる" else "検索")
                        }
                    }

                    // 小計カテゴリ選択（小計がチェックされている時のみ表示）
                    if (isSubtotal) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = "小計カテゴリを選択:",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            // 表示順序: 一般購買 → 農業機械 → 給油所
                            val orderedCategories = listOf(
                                SubtotalCategory.GENERAL_PURCHASE,
                                SubtotalCategory.AGRICULTURAL_MACHINERY,
                                SubtotalCategory.GAS_STATION
                            )
                            orderedCategories.forEach { category ->
                                // 現在の行のカテゴリは選択可能、それ以外は使用済みならグレーアウト
                                val isCurrentRowCategory = currentRow.subtotalCategory == category
                                val isAvailable = subtotalFlags.isAvailable(category) || isCurrentRowCategory
                                val isSelected = selectedSubtotalCategory == category

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (isAvailable) {
                                                Modifier.clickable { selectedSubtotalCategory = category }
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { if (isAvailable) selectedSubtotalCategory = category },
                                        enabled = isAvailable
                                    )
                                    Text(
                                        text = category.displayName,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(start = 8.dp),
                                        color = if (isAvailable)
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                    if (!isAvailable) {
                                        Text(
                                            text = " (使用済み)",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 取引日入力時のボタン
                if (cellType == CellType.DATE) {
                    OutlinedButton(
                        onClick = {
                            inputValue = ""
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("クリア")
                    }
                }

                // 金額入力時のボタン
                if (cellType == CellType.AMOUNT) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isNegative = !isNegative
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = if (isNegative) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            Text(if (isNegative) "－" else "＋")
                        }

                        OutlinedButton(
                            onClick = {
                                inputValue = ""
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("クリア")
                        }
                    }
                }

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (cellType) {
                        CellType.DATE -> {
                            if (fixYearMonth) {
                                // 年月固定モード: 日のみを入力
                                val day = inputValue.toIntOrNull()
                                if (day == null || day < 1 || day > maxDayInMonth) {
                                    errorMessage = "1〜${maxDayInMonth}の日を入力してください"
                                    return@TextButton
                                }
                                val formattedDate = "%02d/%02d/%02d".format(
                                    defaultYear % 100,
                                    defaultMonth,
                                    day
                                )
                                onConfirm(currentRow.copy(date = formattedDate))
                            } else {
                                // 通常モード: 従来通りの日付入力
                                val formattedDate = formatDateInput(
                                    input = inputValue,
                                    defaultYear = defaultYear,
                                    defaultMonth = defaultMonth
                                )
                                if (formattedDate == null) {
                                    errorMessage = "正しい日付を入力してください（6桁/4桁/2桁）"
                                    return@TextButton
                                }
                                onConfirm(currentRow.copy(date = formattedDate))
                            }
                        }
                        CellType.PRODUCT_NAME -> {
                            // 商品マスタIDを解決（ドロップダウン選択時は既にセット済み、手動入力時は検索）
                            coroutineScope.launch {
                                val resolvedProductId = if (selectedProductId != null) {
                                    // ドロップダウンで選択した商品名と一致する場合のみ使用
                                    val selectedProduct = productList.find { it.id == selectedProductId }
                                    if (selectedProduct?.canonicalName == inputValue) {
                                        selectedProductId
                                    } else {
                                        // 手動で書き換えた場合は再検索
                                        productMasterDao.getByName(inputValue)?.id
                                    }
                                } else {
                                    // 手動入力の場合は商品マスタを検索
                                    productMasterDao.getByName(inputValue)?.id
                                }

                                onConfirm(
                                    currentRow.copy(
                                        productName = inputValue,
                                        isSubtotal = isSubtotal,
                                        subtotalCategory = if (isSubtotal) selectedSubtotalCategory else null,
                                        date = if (isSubtotal) "" else currentRow.date,
                                        category = if (isSubtotal) selectedSubtotalCategory.displayName else currentRow.category,
                                        productMasterId = if (!isSubtotal) resolvedProductId else null
                                    )
                                )
                            }
                        }
                        CellType.AMOUNT -> {
                            val amount = inputValue.toIntOrNull()
                            if (inputValue.isNotBlank() && amount == null) {
                                errorMessage = "半角数字で入力してください"
                                return@TextButton
                            }

                            val finalAmount = if (amount == null) {
                                0
                            } else {
                                if (isNegative) -amount else amount
                            }

                            if (finalAmount < -999999 || finalAmount > 9999999) {
                                errorMessage = "金額は-999,999から9,999,999の範囲で入力してください"
                                return@TextButton
                            }

                            onConfirm(currentRow.copy(amount = finalAmount))
                        }
                    }
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

private fun formatDateInput(
    input: String,
    defaultYear: Int,
    defaultMonth: Int
): String? {
    if (input.isBlank()) return ""

    if (!input.matches(Regex("\\d+"))) return null

    val year: Int
    val month: Int
    val day: Int

    when (input.length) {
        6 -> {
            // YYMMDD
            year = input.substring(0, 2).toIntOrNull() ?: return null
            month = input.substring(2, 4).toIntOrNull() ?: return null
            day = input.substring(4, 6).toIntOrNull() ?: return null
        }
        5 -> {
            // YMMDD
            year = input.substring(0, 1).toIntOrNull() ?: return null
            month = input.substring(1, 3).toIntOrNull() ?: return null
            day = input.substring(3, 5).toIntOrNull() ?: return null
        }
        4 -> {
            // MMDD
            year = defaultYear % 100
            month = input.substring(0, 2).toIntOrNull() ?: return null
            day = input.substring(2, 4).toIntOrNull() ?: return null
        }
        3 -> {
            // MDD
            year = defaultYear % 100
            month = input.substring(0, 1).toIntOrNull() ?: return null
            day = input.substring(1, 3).toIntOrNull() ?: return null
        }
        2 -> {
            // DD
            year = defaultYear % 100
            month = defaultMonth
            day = input.toIntOrNull() ?: return null
        }
        1 -> {
            // D
            year = defaultYear % 100
            month = defaultMonth
            day = input.toIntOrNull() ?: return null
        }
        else -> return null
    }

    if (!isValidDate(year, month, day)) return null

    return "%02d/%02d/%02d".format(year, month, day)
}

private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
    if (month < 1 || month > 12) return false
    if (day < 1) return false

    val maxDay = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> {
            val fullYear = if (year < 50) 2000 + year else 1900 + year
            if (fullYear % 4 == 0 && (fullYear % 100 != 0 || fullYear % 400 == 0)) 29 else 28
        }
        else -> return false
    }

    return day <= maxDay
}

/**
 * 累積合計表示セクション
 */
@Composable
private fun CumulativeTotalSection(
    currentSheetNumber: Int,
    totalSheets: Int,
    allSheetsData: Map<Int, List<ReceiptRowData>>
) {
    // 現在の伝票の合計（小計・合計行を除く）
    val currentSheetTotal = allSheetsData[currentSheetNumber]?.let { rows ->
        rows.filter { !it.isSubtotal && !it.isTotalRow }
            .sumOf { it.amount ?: 0 }
    } ?: 0

    // 累積合計（1枚目～現在の伝票まで）
    val cumulativeTotal = (1..currentSheetNumber).sumOf { sheetNum ->
        allSheetsData[sheetNum]?.let { rows ->
            rows.filter { !it.isSubtotal && !it.isTotalRow }
                .sumOf { it.amount ?: 0 }
        } ?: 0
    }

    // 月全体の合計（参考値）
    val monthTotal = (1..totalSheets).sumOf { sheetNum ->
        allSheetsData[sheetNum]?.let { rows ->
            rows.filter { !it.isSubtotal && !it.isTotalRow }
                .sumOf { it.amount ?: 0 }
        } ?: 0
    }

    val numberFormat = java.text.NumberFormat.getNumberInstance(java.util.Locale.JAPAN)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "合計金額",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "この伝票（${currentSheetNumber}枚目）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "¥${numberFormat.format(currentSheetTotal)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "累積合計（1〜${currentSheetNumber}枚目）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "¥${numberFormat.format(cumulativeTotal)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }

            if (currentSheetNumber < totalSheets) {
                Divider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "月全体（全${totalSheets}枚）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "¥${numberFormat.format(monthTotal)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}
