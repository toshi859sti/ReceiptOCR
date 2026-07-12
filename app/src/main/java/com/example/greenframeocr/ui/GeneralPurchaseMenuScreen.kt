package com.example.greenframeocr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralPurchaseMenuScreen(
    onBack: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToList: () -> Unit,
    onNavigateToOutput: () -> Unit,
    onNavigateToStoreList: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("レシート・領収書") },
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
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "レシート・領収書",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(48.dp))

            GeneralMenuButton(text = "レシート撮影・OCR", onClick = onNavigateToCapture)
            Spacer(modifier = Modifier.height(16.dp))
            GeneralMenuButton(text = "レシート一覧", onClick = onNavigateToList)
            Spacer(modifier = Modifier.height(16.dp))
            GeneralMenuButton(text = "CSV出力", onClick = onNavigateToOutput)
            Spacer(modifier = Modifier.height(16.dp))
            GeneralMenuButton(text = "登録番号・店舗一覧", onClick = onNavigateToStoreList)
        }
    }
}

@Composable
private fun GeneralMenuButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary
        )
    ) {
        Text(text = text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}
