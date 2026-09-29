package com.example.greenframeocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel

data class EditableGeneralItem(
    val originalId: Long = 0,
    val name: String = "",
    val priceText: String = "0"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralReceiptConfirmScreen(
    viewModel: GeneralReceiptViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val pendingReceipt by viewModel.pendingReceipt.collectAsState()
    val pendingItems by viewModel.pendingItems.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isLookingUpStore by viewModel.isLookingUpStore.collectAsState()
    val storeLookupError by viewModel.storeLookupError.collectAsState()

    var storeName by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    val editItems = remember { mutableStateListOf<EditableGeneralItem>() }
    var initialized by remember { mutableStateOf(false) }
    var counterAccountOverride by remember { mutableStateOf<Long?>(null) }
    var counterAccountName by remember { mutableStateOf<String?>(null) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var showPaymentAccountPicker by remember { mutableStateOf(false) }

    // ViewModel のデータで初期化（一度だけ）
    LaunchedEffect(pendingReceipt, pendingItems) {
        if (!initialized && pendingReceipt != null) {
            storeName = pendingReceipt!!.storeName
            dateText = pendingReceipt!!.date
            editItems.clear()
            editItems.addAll(pendingItems.map {
                EditableGeneralItem(it.id, it.itemName, it.price.toString())
            })
            initialized = true
        }
    }

    // 保存完了 → 連続撮影のため撮影画面へ戻る
    LaunchedEffect(uiState) {
        if (uiState is GeneralReceiptViewModel.UiState.Done) {
            onSaved()
        }
    }

    // ViewModel が店舗名を更新した場合（登録番号照会完了後）に反映
    LaunchedEffect(pendingReceipt?.storeName) {
        val newName = pendingReceipt?.storeName ?: return@LaunchedEffect
        if (initialized && storeName.isBlank() && newName.isNotBlank()) {
            storeName = newName
        }
    }

    val total = editItems.sumOf { it.priceText.toIntOrNull() ?: 0 }
    val geminiUsed = pendingReceipt?.geminiUsed ?: false
    val rawOcrText = pendingReceipt?.rawOcrText ?: ""
    val registrationNumber = pendingReceipt?.registrationNumber ?: ""
    val paymentMethodText = pendingReceipt?.paymentMethodText

    LaunchedEffect(Unit) {
        yayoiAccounts = viewModel.loadYayoiAccounts()
    }

    // 支払方法テキスト・個別上書きが変わるたびに相手科目のプレビューを再計算
    LaunchedEffect(paymentMethodText, counterAccountOverride) {
        counterAccountName = viewModel.resolveCounterAccountNameForReceipt(
            GeneralReceipt(
                date = "",
                paymentMethodText = paymentMethodText,
                paymentAccountOverride = counterAccountOverride
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("レシート確認・編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ヘッダー情報
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = storeName,
                        onValueChange = { storeName = it },
                        label = { Text("店舗名") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DateOutlinedField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = "日付",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (geminiUsed) "✓ Gemini解析済み" else "✗ 手動入力",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPaymentAccountPicker = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "支払方法の科目: ${counterAccountName ?: "…"}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (counterAccountOverride != null)
                                    MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.primary
                            )
                            paymentMethodText?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = "読取: $it",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "支払方法の科目を変更",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (registrationNumber.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "登録番号: $registrationNumber",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    viewModel.lookupStoreByRegistrationNumber(registrationNumber)
                                },
                                enabled = !isLookingUpStore,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                if (isLookingUpStore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("店舗名を検索", fontSize = 12.sp)
                                }
                            }
                        }
                        storeLookupError?.let { err ->
                            Text(err, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Gemini未使用時は生OCRテキストを表示
            if (!geminiUsed && rawOcrText.isNotBlank()) {
                Text(
                    text = "OCR読み取りテキスト（参考）",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    Text(
                        text = rawOcrText,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 明細一覧
            Text(
                text = "商品一覧",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            editItems.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = item.name,
                        onValueChange = { editItems[index] = item.copy(name = it) },
                        label = { Text("商品名") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = item.priceText,
                        onValueChange = { editItems[index] = item.copy(priceText = it) },
                        label = { Text("金額") },
                        modifier = Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    IconButton(onClick = { editItems.removeAt(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "削除")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 行追加ボタン
            TextButton(
                onClick = { editItems.add(EditableGeneralItem()) },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("行追加")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            // 合計
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "合計: ¥${"%,d".format(total)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ボタン行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("キャンセル")
                }
                Button(
                    onClick = {
                        val receipt = GeneralReceipt(
                            date = dateText,
                            storeName = storeName,
                            total = total,
                            rawOcrText = rawOcrText,
                            geminiUsed = geminiUsed,
                            registrationNumber = registrationNumber,
                            paymentMethodText = paymentMethodText,
                            paymentAccountOverride = counterAccountOverride
                        )
                        val items = editItems.map {
                            GeneralReceiptItem(
                                receiptId = 0,
                                itemName = it.name,
                                price = it.priceText.toIntOrNull() ?: 0
                            )
                        }
                        viewModel.saveReceipt(receipt, items)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = uiState !is GeneralReceiptViewModel.UiState.GeminiRunning
                ) {
                    Text("保存して次を撮影")
                }
            }
        }
    }

    if (showPaymentAccountPicker) {
        PaymentAccountPickerDialog(
            currentOverrideId = counterAccountOverride,
            accounts = yayoiAccounts,
            onDismiss = { showPaymentAccountPicker = false },
            onSelect = { accountId ->
                counterAccountOverride = accountId
                showPaymentAccountPicker = false
            }
        )
    }
}
