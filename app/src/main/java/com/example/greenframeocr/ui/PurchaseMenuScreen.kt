package com.example.greenframeocr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 購買部門サブメニュー画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseMenuScreen(
    onBack: () -> Unit,
    onNavigateToReceiptInput: () -> Unit,
    onNavigateToYearSummary: () -> Unit,
    onNavigateToProductList: () -> Unit,
    onNavigateToOutputConfirm: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("購買部門") },
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
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "購買部門",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(48.dp))

            // メニューボタン（4項目）
            SubMenuButton(
                text = "伝票データ",
                onClick = onNavigateToReceiptInput
            )

            Spacer(modifier = Modifier.height(16.dp))

            SubMenuButton(
                text = "購買データ確認",
                onClick = onNavigateToYearSummary
            )

            Spacer(modifier = Modifier.height(16.dp))

            SubMenuButton(
                text = "購買品目別リスト",
                onClick = onNavigateToProductList
            )

            Spacer(modifier = Modifier.height(16.dp))

            SubMenuButton(
                text = "出力確認画面",
                onClick = onNavigateToOutputConfirm
            )
        }
    }
}

/**
 * サブメニューボタン
 */
@Composable
private fun SubMenuButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary
        )
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
