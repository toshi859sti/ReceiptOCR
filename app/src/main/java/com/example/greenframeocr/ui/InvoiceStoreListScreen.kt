package com.example.greenframeocr.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel
import com.example.greenframeocr.viewmodel.IssuerEntry

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InvoiceStoreListScreen(
    viewModel: GeneralReceiptViewModel,
    appPreferences: AppPreferences,
    onBack: () -> Unit
) {
    val stores by viewModel.issuerList.collectAsState()
    var deleteTarget by remember { mutableStateOf<IssuerEntry?>(null) }
    var editTarget by remember { mutableStateOf<IssuerEntry?>(null) }
    var listFontSize by remember { mutableFloatStateOf(appPreferences.listFontSize) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登録番号・店舗・発行者一覧") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    TextButton(onClick = { viewModel.backfillInvoiceStoresFromReceipts() }) {
                        Text("レシートから取込", fontSize = 12.sp)
                    }
                }
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
            if (stores.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                Text(
                    text = "登録番号・発行者がまだありません\nレシートをスキャンすると自動追加されます",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(stores, key = { it.registrationNumber ?: "name:${it.storeName}" }) { store ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { editTarget = store },
                                onLongClick = { if (store.registrationNumber != null) deleteTarget = store }
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = store.storeName.ifBlank { "（名称不明）" },
                                    fontWeight = FontWeight.Medium,
                                    fontSize = (listFontSize + 1f).sp
                                )
                                if (store.registrationNumber != null) {
                                    Text(
                                        text = store.registrationNumber,
                                        fontSize = (listFontSize - 2f).coerceAtLeast(10f).sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "登録番号なし（領収書発行者）",
                                        fontSize = (listFontSize - 2f).coerceAtLeast(10f).sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (store.address.isNotBlank()) {
                                    Text(
                                        text = store.address,
                                        fontSize = (listFontSize - 3f).coerceAtLeast(10f).sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = { editTarget = store },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "編集",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }

    // 編集ダイアログ
    editTarget?.let { target ->
        StoreNameEditDialog(
            store = target,
            onDismiss = { editTarget = null },
            onSave = { newName, feedback ->
                val registrationNumber = target.registrationNumber
                if (registrationNumber != null) {
                    viewModel.updateInvoiceStoreName(registrationNumber, newName, feedback)
                } else {
                    viewModel.renameUnregisteredIssuer(target.storeName, newName)
                }
                editTarget = null
            }
        )
    }

    // 削除確認ダイアログ（長押し、登録番号ありのみ対象）
    deleteTarget?.let { target ->
        val registrationNumber = target.registrationNumber
        if (registrationNumber != null) {
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("削除の確認") },
                text = {
                    Text("「${target.storeName.ifBlank { registrationNumber }}」を一覧から削除しますか？")
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.deleteInvoiceStoreByRegistrationNumber(registrationNumber); deleteTarget = null }) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
                }
            )
        }
    }
}

@Composable
private fun StoreNameEditDialog(
    store: IssuerEntry,
    onDismiss: () -> Unit,
    onSave: (newName: String, feedbackToReceipts: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(store.storeName) }
    var feedback by remember { mutableStateOf(true) }
    val isRegistered = store.registrationNumber != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRegistered) "法人名を編集" else "発行者名を編集") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (store.registrationNumber != null) {
                    Text(
                        text = store.registrationNumber,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isRegistered) "法人名" else "発行者名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isRegistered) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = feedback,
                            onCheckedChange = { feedback = it }
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text("既存レシートにも反映する", fontSize = 14.sp)
                            Text(
                                text = "この登録番号のレシートの店舗名を一括更新",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "この発行者名の全レシートに一括反映されます",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), feedback) },
                enabled = name.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
