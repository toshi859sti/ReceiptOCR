package com.example.greenframeocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.ReceiptItem
import com.example.greenframeocr.data.SheetData
import com.example.greenframeocr.util.Category
import com.example.greenframeocr.util.ValidationUtils
import com.example.greenframeocr.util.applyConversionToNewInput
import com.example.greenframeocr.util.convertAllToFullWidth
import com.example.greenframeocr.util.countFullWidthEquivalent
import com.example.greenframeocr.viewmodel.SheetEditorViewModel
import kotlinx.coroutines.launch

/**
 * 伝票編集画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetEditorScreen(
    viewModel: SheetEditorViewModel,
    eraYear: Int,
    onBack: () -> Unit,
    onNavigateToPreviousSheet: () -> Unit,
    onNavigateToNextSheet: () -> Unit,
    onReOcr: () -> Unit
) {
    val isOcrOverwriteMode by viewModel.isOcrOverwriteMode.collectAsState()
    val receiptItems by viewModel.receiptItems.collectAsState()
    val sheetData by viewModel.sheetData.collectAsState()
    val scope = rememberCoroutineScope()

    // 整合性チェック
    val validation = remember(receiptItems, sheetData) {
        if (sheetData != null) viewModel.validateData()
        else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("伝票編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                actions = {
                    // 編集モード切り替え
                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                        Icon(
                            imageVector = if (isOcrOverwriteMode) Icons.Default.Edit else Icons.Default.Create,
                            contentDescription = if (isOcrOverwriteMode) "再OCR上書きモード" else "手書き編集モード",
                            tint = if (isOcrOverwriteMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
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
            // ヘッダー情報
            SheetHeader(
                eraYear = eraYear,
                issueMonth = sheetData?.issueMonth ?: 1,
                sheetNumber = sheetData?.sheetNumber ?: 1,
                editMode = if (isOcrOverwriteMode) "再OCR上書き" else "手書き編集"
            )

            Divider()

            // 小計・合計セクション
            sheetData?.let { data ->
                SubtotalSection(
                    sheetData = data,
                    validation = validation,
                    isOcrOverwriteMode = isOcrOverwriteMode,
                    onUpdateSheetData = { viewModel.updateSheetData(it) },
                    onToggleOverwriteTarget = { field -> viewModel.toggleSubtotalOverwriteTarget(field) }
                )
                Divider()
            }

            // データグリッド
            if (receiptItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("データがありません")
                }
            } else {
                EditableDataGrid(
                    items = receiptItems,
                    isOcrOverwriteMode = isOcrOverwriteMode,
                    onUpdateItem = { viewModel.updateItem(it) },
                    onToggleOverwriteTarget = { itemId -> viewModel.toggleItemOverwriteTarget(itemId) },
                    modifier = Modifier.weight(1f)
                )
            }

            Divider()

            // ボトムボタン
            BottomButtons(
                canMoveToPrevious = viewModel.canMoveToPreviousSheet(),
                onPrevious = onNavigateToPreviousSheet,
                onNext = onNavigateToNextSheet,
                onAddRow = { viewModel.addNewItem() },
                onReOcr = onReOcr,
                onSave = {
                    scope.launch {
                        val success = viewModel.saveData()
                        if (success) {
                            onBack()
                        }
                    }
                }
            )
        }
    }
}

/**
 * ヘッダー情報
 */
@Composable
private fun SheetHeader(
    eraYear: Int,
    issueMonth: Int,
    sheetNumber: Int,
    editMode: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "令和${eraYear}年 ${issueMonth}月発行 ${sheetNumber}枚目",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "編集モード: $editMode",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 小計・合計セクション
 */
@Composable
private fun SubtotalSection(
    sheetData: SheetData,
    validation: com.example.greenframeocr.util.ValidationResult?,
    isOcrOverwriteMode: Boolean,
    onUpdateSheetData: (SheetData) -> Unit,
    onToggleOverwriteTarget: (String) -> Unit
) {
    var showEditDialog by remember { mutableStateOf<Pair<String, Int?>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 一般購買
            SubtotalField(
                label = "一般購買",
                value = sheetData.subtotalGeneral,
                isMatched = validation?.isSubtotalGeneralMatched ?: true,
                isOcrTarget = sheetData.isSubtotalGeneralOcrTarget,
                isOcrOverwriteMode = isOcrOverwriteMode,
                onClick = {
                    if (isOcrOverwriteMode) {
                        onToggleOverwriteTarget("general")
                    } else {
                        showEditDialog = "general" to sheetData.subtotalGeneral
                    }
                },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 給油所
            SubtotalField(
                label = "給油所",
                value = sheetData.subtotalGas,
                isMatched = validation?.isSubtotalGasMatched ?: true,
                isOcrTarget = sheetData.isSubtotalGasOcrTarget,
                isOcrOverwriteMode = isOcrOverwriteMode,
                onClick = {
                    if (isOcrOverwriteMode) {
                        onToggleOverwriteTarget("gas")
                    } else {
                        showEditDialog = "gas" to sheetData.subtotalGas
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 農業機械
            SubtotalField(
                label = "農業機械",
                value = sheetData.subtotalAgri,
                isMatched = validation?.isSubtotalAgriMatched ?: true,
                isOcrTarget = sheetData.isSubtotalAgriOcrTarget,
                isOcrOverwriteMode = isOcrOverwriteMode,
                onClick = {
                    if (isOcrOverwriteMode) {
                        onToggleOverwriteTarget("agri")
                    } else {
                        showEditDialog = "agri" to sheetData.subtotalAgri
                    }
                },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 合計
            SubtotalField(
                label = "合計",
                value = sheetData.totalFromInput,
                isMatched = validation?.isTotalMatched ?: true,
                isOcrTarget = sheetData.isTotalOcrTarget,
                isOcrOverwriteMode = isOcrOverwriteMode,
                onClick = {
                    if (isOcrOverwriteMode) {
                        onToggleOverwriteTarget("total")
                    } else {
                        showEditDialog = "total" to sheetData.totalFromInput
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // 編集ダイアログ
    showEditDialog?.let { (field, currentValue) ->
        SubtotalEditDialog(
            fieldName = when (field) {
                "general" -> "一般購買"
                "gas" -> "給油所"
                "agri" -> "農業機械"
                "total" -> "合計"
                else -> field
            },
            currentValue = currentValue,
            onDismiss = { showEditDialog = null },
            onConfirm = { newValue ->
                val updatedSheetData = when (field) {
                    "general" -> sheetData.copy(subtotalGeneral = newValue)
                    "gas" -> sheetData.copy(subtotalGas = newValue)
                    "agri" -> sheetData.copy(subtotalAgri = newValue)
                    "total" -> sheetData.copy(totalFromInput = newValue)
                    else -> sheetData
                }
                onUpdateSheetData(updatedSheetData)
                showEditDialog = null
            }
        )
    }
}

/**
 * 小計フィールド
 */
@Composable
private fun SubtotalField(
    label: String,
    value: Int?,
    isMatched: Boolean,
    isOcrTarget: Boolean,
    isOcrOverwriteMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(
                when {
                    isOcrTarget && isOcrOverwriteMode -> Color(0xFFFFE0E0)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .border(
                width = if (!isMatched) 2.dp else 1.dp,
                color = if (!isMatched) Color.Red else MaterialTheme.colorScheme.outline
            )
            .padding(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = value?.let { "%,d".format(it) } ?: "---",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isMatched) "✓" else "❌",
                color = if (isMatched) Color.Green else Color.Red,
                fontSize = 18.sp
            )
        }
    }
}

/**
 * 編集可能なデータグリッド（ヘッダー付き）
 */
@Composable
private fun EditableDataGrid(
    items: List<ReceiptItem>,
    isOcrOverwriteMode: Boolean,
    onUpdateItem: (ReceiptItem) -> Unit,
    onToggleOverwriteTarget: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showItemEditDialog by remember { mutableStateOf<ReceiptItem?>(null) }

    Column(modifier = modifier) {
        // ヘッダー
        GridHeader()
        Divider()

        // データ行
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items) { item ->
                EditableRow(
                    item = item,
                    isOcrOverwriteMode = isOcrOverwriteMode,
                    onClick = {
                        if (isOcrOverwriteMode) {
                            onToggleOverwriteTarget(item.id)
                        } else {
                            showItemEditDialog = item
                        }
                    }
                )
                Divider()
            }
        }
    }

    // アイテム編集ダイアログ
    showItemEditDialog?.let { item ->
        ItemEditDialog(
            item = item,
            onDismiss = { showItemEditDialog = null },
            onConfirm = { updatedItem ->
                onUpdateItem(updatedItem)
                showItemEditDialog = null
            }
        )
    }
}

/**
 * グリッドヘッダー
 */
@Composable
private fun GridHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Text(
            text = "日付",
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "商品名",
            modifier = Modifier.weight(2f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "金額",
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End
        )
        Text(
            text = "分類",
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 編集可能な行
 */
@Composable
private fun EditableRow(
    item: ReceiptItem,
    isOcrOverwriteMode: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                when {
                    item.isOcrOverwriteTarget && isOcrOverwriteMode -> Color(0xFFFFE0E0)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = ValidationUtils.formatDate(item.receiptMonth, item.receiptDay),
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = item.productName,
            modifier = Modifier.weight(2f),
            fontSize = 13.sp
        )
        Text(
            text = "%,d".format(item.amount),
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            textAlign = TextAlign.End
        )
        Text(
            text = item.category,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * ボトムボタン
 */
@Composable
private fun BottomButtons(
    canMoveToPrevious: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAddRow: () -> Unit,
    onReOcr: () -> Unit,
    onSave: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = canMoveToPrevious,
                modifier = Modifier.weight(1f)
            ) {
                Text("← 前")
            }
            OutlinedButton(
                onClick = onNext,
                modifier = Modifier.weight(1f)
            ) {
                Text("次 →")
            }
            OutlinedButton(
                onClick = onAddRow,
                modifier = Modifier.weight(1f)
            ) {
                Text("+行追加")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onReOcr,
                modifier = Modifier.weight(1f)
            ) {
                Text("再OCR")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f)
            ) {
                Text("保存")
            }
        }
    }
}

/**
 * 小計編集ダイアログ
 */
@Composable
private fun SubtotalEditDialog(
    fieldName: String,
    currentValue: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit
) {
    var textValue by remember { mutableStateOf(currentValue?.toString() ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$fieldName を編集") },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                        errorMessage = null
                    },
                    label = { Text("金額") },
                    placeholder = { Text("数値を入力（空欄でクリア）") },
                    isError = errorMessage != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (textValue.isBlank()) {
                        // 空欄の場合はnull
                        onConfirm(null)
                    } else {
                        val parsedValue = textValue.toIntOrNull()
                        if (parsedValue != null) {
                            onConfirm(parsedValue)
                        } else {
                            errorMessage = "数値を入力してください"
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


/**
 * アイテム編集ダイアログ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditDialog(
    item: ReceiptItem,
    onDismiss: () -> Unit,
    onConfirm: (ReceiptItem) -> Unit
) {
    var month by remember { mutableIntStateOf(item.receiptMonth) }
    var day by remember { mutableIntStateOf(item.receiptDay) }
    var productName by remember { mutableStateOf(item.productName) }
    var isAlphaFullWidth by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf(item.amount.toString()) }
    var category by remember { mutableStateOf(item.category) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCategoryMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アイテムを編集") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 日付
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = month.toString(),
                        onValueChange = {
                            month = it.toIntOrNull()?.coerceIn(1, 12) ?: month
                            errorMessage = null
                        },
                        label = { Text("月") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Text("/")
                    OutlinedTextField(
                        value = day.toString(),
                        onValueChange = {
                            day = it.toIntOrNull()?.coerceIn(1, 31) ?: day
                            errorMessage = null
                        },
                        label = { Text("日") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 商品名
                Column {
                    val charCount = countFullWidthEquivalent(productName)
                    val hasHalfWidthOdd = charCount % 1.0 != 0.0
                    val countText = if (!hasHalfWidthOdd) "${charCount.toInt()}" else "${"%.1f".format(charCount)}"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "商品名",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$countText/20",
                            fontSize = 11.sp,
                            color = when {
                                hasHalfWidthOdd -> MaterialTheme.colorScheme.error
                                charCount >= 20.0 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    OutlinedTextField(
                        value = productName,
                        onValueChange = {
                            productName = applyConversionToNewInput(productName, it, isAlphaFullWidth)
                            errorMessage = null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = hasHalfWidthOdd
                    )
                    if (hasHalfWidthOdd) {
                        Text(
                            text = "半角は2文字ひとまとまりで入力してください（kg・cm等）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "英字：",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = isAlphaFullWidth,
                            onClick = { isAlphaFullWidth = true },
                            label = { Text("全角", fontSize = 12.sp) },
                            modifier = Modifier.height(32.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FilterChip(
                            selected = !isAlphaFullWidth,
                            onClick = { isAlphaFullWidth = false },
                            label = { Text("半角", fontSize = 12.sp) },
                            modifier = Modifier.height(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { productName = convertAllToFullWidth(productName) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("一括全角", fontSize = 12.sp)
                        }
                    }
                }

                // 金額
                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                        errorMessage = null
                    },
                    label = { Text("金額") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 分類
                Box {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        label = { Text("分類") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { showCategoryMenu = !showCategoryMenu }) {
                                Icon(Icons.Default.ArrowDropDown, "分類選択")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = { showCategoryMenu = false }
                    ) {
                        listOf(
                            Category.UNCLASSIFIED,
                            Category.GENERAL,
                            Category.GAS_STATION,
                            Category.AGRICULTURAL
                        ).forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    category = cat
                                    showCategoryMenu = false
                                }
                            )
                        }
                    }
                }

                // エラーメッセージ
                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedAmount = amount.toIntOrNull()
                    val hasHalfWidthOdd = countFullWidthEquivalent(productName) % 1.0 != 0.0
                    if (parsedAmount == null) {
                        errorMessage = "金額は数値で入力してください"
                    } else if (productName.isBlank()) {
                        errorMessage = "商品名を入力してください"
                    } else if (hasHalfWidthOdd) {
                        errorMessage = "半角文字が奇数です。2文字ひとまとまりにしてください"
                    } else {
                        onConfirm(
                            item.copy(
                                receiptMonth = month,
                                receiptDay = day,
                                productName = productName,
                                amount = parsedAmount,
                                category = category
                            )
                        )
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
