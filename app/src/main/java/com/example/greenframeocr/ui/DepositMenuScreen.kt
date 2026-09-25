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
 * 預金部門サブメニュー画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositMenuScreen(
    onBack: () -> Unit,
    onNavigateToPassbookData: () -> Unit,
    onNavigateToTekiyouMatching: () -> Unit,
    onNavigateToOutputConfirm: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JA預金") },
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
        MenuColumn(paddingValues) {
            Text(
                text = "JA預金",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(48.dp))

            // メニューボタン（4項目）
            SubMenuButton(
                text = "通帳データ",
                onClick = onNavigateToPassbookData
            )

            Spacer(modifier = Modifier.height(16.dp))

            SubMenuButton(
                text = "通帳摘要別リスト",
                onClick = onNavigateToTekiyouMatching
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
            containerColor = MaterialTheme.colorScheme.tertiary
        )
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
