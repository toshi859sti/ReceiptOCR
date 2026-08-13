package com.example.greenframeocr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
