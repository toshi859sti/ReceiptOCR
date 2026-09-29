package com.example.greenframeocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboAccountUsage
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.util.AoiroChoboUsageRules
import com.example.greenframeocr.util.AoiroChoboUsageRules.Usage
import kotlinx.coroutines.launch

private val COL_USAGE = 64.dp

/**
 * AoiroChobo 科目の用途ごとの絞り込み（JA 購買・レシート・預金）。
 *
 * 弥生の「フラグ一括設定」と同じ、セルをタップすると即保存される表。ただし付け外しできるのは
 * PC が許している用途だけで、許していない用途は「—」で触れない（PC のフラグの内側で減らすだけ）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AoiroChoboAccountUsageDialog(
    database: ReceiptDatabase,
    accounts: List<AoiroChoboAccount>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dao = database.aoiroChoboAccountUsageDao()
    var settings by remember { mutableStateOf<List<AoiroChoboAccountUsage>>(emptyList()) }
    LaunchedEffect(Unit) { settings = dao.getAll() }

    val rows = remember(accounts) { AoiroChoboUsageRules.configurableAccounts(accounts) }
    val byKey = settings.associateBy { it.accountKey }

    fun toggle(account: AoiroChoboAccount, usage: Usage) {
        val current = byKey[account.accountKey]
        val updated = AoiroChoboUsageRules.toggled(account, current, usage, kept = !usage.keptBy(current))
        scope.launch {
            dao.upsert(updated)
            settings = dao.getAll()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("用途の絞り込み", fontSize = 18.sp) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "閉じる") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                Text(
                    text = "●の科目が、その用途で科目を選ぶときと AI 提案の候補になります。タップで切り替え（すぐ保存）。" +
                        "「—」は PC 側がその用途に使わない科目で、変えられません。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

                // 見出し：用途ごとに「残している数 / PC が許す数」
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("勘定科目", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Usage.entries.forEach { usage ->
                        val allowed = rows.count { usage.allowedByPc(it) }
                        val kept = rows.count { usage.allowedByPc(it) && usage.keptBy(byKey[it.accountKey]) }
                        Text(
                            "${usage.label}\n$kept/$allowed",
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(COL_USAGE)
                        )
                    }
                }
                Divider()

                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.accountKey }) { account ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                account.displayGroup?.let {
                                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Usage.entries.forEach { usage ->
                                UsageCell(
                                    allowed = usage.allowedByPc(account),
                                    kept = usage.keptBy(byKey[account.accountKey]),
                                    onColor = when (usage) {
                                        Usage.PURCHASE -> MaterialTheme.colorScheme.primary
                                        Usage.RECEIPT -> MaterialTheme.colorScheme.secondary
                                        Usage.DEPOSIT -> MaterialTheme.colorScheme.tertiary
                                    },
                                    onClick = { toggle(account, usage) }
                                )
                            }
                        }
                        Divider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageCell(allowed: Boolean, kept: Boolean, onColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(COL_USAGE)
            .height(44.dp)
            .then(if (allowed) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when {
            !allowed -> Text("—", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
            kept -> Text("●", fontSize = 18.sp, color = onColor)
            else -> Text("○", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}
