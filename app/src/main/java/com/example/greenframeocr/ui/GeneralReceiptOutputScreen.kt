package com.example.greenframeocr.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralReceiptOutputScreen(
    viewModel: GeneralReceiptViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fromDate by remember { mutableStateOf("") }
    var toDate by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var pendingCsvContent by remember { mutableStateOf<String?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val csv = pendingCsvContent ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(csv.toByteArray(Charsets.UTF_8))
                    }
                }
                statusMessage = "CSVを出力しました"
            } catch (e: Exception) {
                statusMessage = "出力に失敗しました: ${e.message}"
            } finally {
                isExporting = false
                pendingCsvContent = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CSV出力") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "出力期間",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            OutlinedTextField(
                value = fromDate,
                onValueChange = { fromDate = it },
                label = { Text("開始日（yyyy-MM-dd、空欄で全期間）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = toDate,
                onValueChange = { toDate = it },
                label = { Text("終了日（yyyy-MM-dd、空欄で全期間）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            statusMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }

            Button(
                onClick = {
                    if (isExporting) return@Button
                    isExporting = true
                    statusMessage = null
                    scope.launch {
                        try {
                            val from = fromDate.takeIf { it.isNotBlank() }
                            val to = toDate.takeIf { it.isNotBlank() }
                            val csv = viewModel.buildCsvForExport(from, to)
                            pendingCsvContent = csv
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                .format(Date())
                            fileLauncher.launch("一般購買_$timestamp.csv")
                        } catch (e: Exception) {
                            statusMessage = "エラー: ${e.message}"
                            isExporting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isExporting
            ) {
                Text(if (isExporting) "出力中..." else "CSV出力")
            }

            Text(
                text = "出力形式: ID, 日付, 摘要, メモ（商品名）, 金額",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
