package com.example.greenframeocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * リスト上部に配置するコンパクトなフォントサイズコントロール。
 * 変更時の保存処理は呼び出し元が責任を持つ。
 */
@Composable
fun FontSizeControl(
    fontSize: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        IconButton(
            onClick = onDecrease,
            enabled = fontSize > 10f,
            modifier = Modifier.size(44.dp)
        ) {
            Text("A-", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            text = "${fontSize.toInt()}",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(30.dp),
            textAlign = TextAlign.Center
        )
        IconButton(
            onClick = onIncrease,
            enabled = fontSize < 20f,
            modifier = Modifier.size(44.dp)
        ) {
            Text("A+", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ─── 日付入力欄（カレンダーピッカー） ────────────────────────────────────────

// "yyyy-MM-dd" ⇔ UTC深夜0時ミリ秒（DatePickerStateはUTC基準のため、ローカルタイムゾーンで
// 変換すると日付がずれることがある）
private fun dateStringToUtcMillis(dateStr: String): Long? {
    val parts = dateStr.split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day)
    return cal.timeInMillis
}

private fun utcMillisToDateString(millis: Long): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = millis
    return "%04d-%02d-%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH)
    )
}

/**
 * カレンダーピッカーで選択するreadOnlyの日付入力欄（"yyyy-MM-dd"文字列で入出力）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                Icon(Icons.Default.CalendarMonth, contentDescription = "日付を選択")
            },
            modifier = Modifier.fillMaxWidth()
        )
        // OutlinedTextFieldはreadOnlyでもタップでフォーカスされるだけなので、
        // 透明なオーバーレイでタップを拾ってピッカーを開く
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { showPicker = true }
        )
    }
    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dateStringToUtcMillis(value) ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onValueChange(utcMillisToDateString(it)) }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("キャンセル") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}
