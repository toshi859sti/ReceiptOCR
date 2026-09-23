package com.example.greenframeocr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookkeepingMenuScreen(
    onBack: () -> Unit,
    onNavigateToYayoiAccounts: () -> Unit,
    onNavigateToRakurakuAccounts: () -> Unit,
    onNavigateToRakurakuTekiyou: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("簿記ソフト連携") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            SoftwareSection(
                title = "弥生の青色申告",
                items = listOf("勘定科目" to onNavigateToYayoiAccounts)
            )

            SoftwareSection(
                title = "らくらく青色申告農業版",
                items = listOf(
                    "勘定科目" to onNavigateToRakurakuAccounts,
                    "摘要辞書" to onNavigateToRakurakuTekiyou
                )
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SoftwareSection(
    title: String,
    items: List<Pair<String, () -> Unit>>
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        items.forEach { (label, onClick) ->
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        Divider()
    }
}
