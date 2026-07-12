package com.example.greenframeocr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.AppPreferences

/**
 * メインメニュー画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    appPreferences: AppPreferences,
    onNavigateToPurchaseMenu: () -> Unit,
    onNavigateToDepositMenu: () -> Unit,
    onNavigateToGeneralPurchaseMenu: () -> Unit = {},
    onNavigateToBookkeepingMenu: () -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onNavigateToDebugCapture: () -> Unit = {}
) {
    val eraYear = remember { appPreferences.eraYear }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JA仕訳変換") },
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
            // タイトル表示
            Text(
                text = "JA仕訳変換",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(64.dp))

            // メニューボタン（3項目）
            MenuButton(
                text = "JA購買伝票",
                onClick = onNavigateToPurchaseMenu
            )

            Spacer(modifier = Modifier.height(24.dp))

            MenuButton(
                text = "JA預金",
                onClick = onNavigateToDepositMenu
            )

            Spacer(modifier = Modifier.height(24.dp))

            MenuButton(
                text = "レシート・領収書",
                onClick = onNavigateToGeneralPurchaseMenu
            )

            Spacer(modifier = Modifier.height(24.dp))

            MenuButton(
                text = "簿記ソフト連携",
                onClick = onNavigateToBookkeepingMenu
            )

            Spacer(modifier = Modifier.height(24.dp))

            MenuButton(
                text = "設定",
                onClick = onNavigateToSettings
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onNavigateToDebugCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "デバッグ撮影",
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

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
