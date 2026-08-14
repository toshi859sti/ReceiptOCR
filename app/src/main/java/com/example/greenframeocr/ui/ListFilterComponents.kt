package com.example.greenframeocr.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * リスト画面共通の検索欄（購買品リスト・品目但し書き別マッチング・通帳摘要別リストで共用）。
 * 入力中の文字列をそのまま呼び出し元のフィルタに渡す想定（デバウンス等はしない）。
 */
@Composable
fun ListSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "検索"
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "クリア")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    )
}

/**
 * 並び替え・カテゴリ絞り込みなど「単一選択」のチップ行。ラベルプレフィックス＋選択肢チップ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> FilterChipGroup(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option), fontSize = 12.sp) }
                )
            }
        }
    }
}

/**
 * 「未マッチのみ」のような真偽フィルタ用のトグルチップ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToggleFilterChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = checked,
        onClick = { onCheckedChange(!checked) },
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = if (checked) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null,
        modifier = modifier
    )
}

/**
 * 「○件」の件数表示（検索・絞り込みチップ行の下に右寄せで置く想定）。
 */
@Composable
fun ListCountText(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = "${count}件",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * 検索・並び替え・絞り込みコントロール群をまとめる折りたたみパネル。
 * リスト表示領域を圧迫しないよう、既定では折りたたんだ状態にする想定。
 * hasActiveFilter=trueの間は折りたたみ時もヘッダーに「絞り込み中」を表示する。
 */
@Composable
fun CollapsibleFilterPanel(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "検索・並び替え・絞り込み",
    hasActiveFilter: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.FilterList,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (hasActiveFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (hasActiveFilter) "$title（絞り込み中）" else title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (hasActiveFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "折りたたむ" else "展開する"
            )
        }
        if (expanded) {
            Column(modifier = Modifier.animateContentSize()) {
                content()
            }
        }
    }
}
