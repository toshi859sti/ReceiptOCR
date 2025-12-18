package com.example.receiptorc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.receiptorc.data.AppPreferences

/**
 * 起動メニュー画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    appPreferences: AppPreferences,
    onNavigateToDataBrowser: () -> Unit,
    onNavigateToOcrCapture: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val eraYear = remember { appPreferences.eraYear }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt OCR") },
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
            // タイトルと年度表示
            Text(
                text = "Receipt OCR",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "令和${eraYear}年度",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(48.dp))

            // メニューボタン
            MenuButton(
                text = "データ閲覧",
                onClick = onNavigateToDataBrowser
            )

            Spacer(modifier = Modifier.height(16.dp))

            MenuButton(
                text = "OCR撮影",
                onClick = onNavigateToOcrCapture
            )

            Spacer(modifier = Modifier.height(16.dp))

            MenuButton(
                text = "設定",
                onClick = onNavigateToSettings
            )

            Spacer(modifier = Modifier.height(48.dp))

            // バージョン情報
            Text(
                text = "Version 1.0.0",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * メニューボタン
 */
@Composable
private fun MenuButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
