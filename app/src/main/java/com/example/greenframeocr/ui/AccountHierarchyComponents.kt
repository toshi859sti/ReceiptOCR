package com.example.greenframeocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greenframeocr.data.RakurakuAccount
import com.example.greenframeocr.data.YayoiAccount

internal val CATEGORY_A_ORDER = listOf(
    "【資産】", "【負債】", "【資本】", "【経常損益】", "【引当金等】"
)

internal val CATEGORY_B_ORDER = mapOf(
    "【資産】" to listOf("【流動資産】", "【固定資産】", "【繰延資産】", "【事業主貸】"),
    "【負債】" to listOf("【流動負債】", "【事業主借】"),
    "【資本】" to listOf("【資本】"),
    "【経常損益】" to listOf("【収入金額】", "【経費】"),
    "【引当金等】" to listOf("【繰戻額等】", "【繰入額等】")
)

internal val CATEGORY_C_ORDER = mapOf(
    "【流動資産】" to listOf("【現金・預金】", "【売上債権】", "【有価証券】", "【棚卸資産】", "【他流動資産】"),
    "【固定資産】" to listOf("【有形固定資産】", "【無形固定資産】", "【投資等】"),
    "【流動負債】" to listOf("【仕入債務】", "【他流動負債】"),
    "【収入金額】" to listOf("【収入金額】", "【農産物棚卸高】"),
    "【経費】" to listOf("【経費】", "【農産外棚卸高】"),
    "【繰戻額等】" to listOf("【繰戻額等】"),
    "【繰入額等】" to listOf("【繰入額等】")
)

data class AccountHierarchy<T>(
    val categoryA: String,
    val categoryBGroups: List<CategoryBGroup<T>>
)

data class CategoryBGroup<T>(
    val categoryB: String,
    val categoryCGroups: List<CategoryCGroup<T>>
)

data class CategoryCGroup<T>(
    val categoryC: String,
    val accounts: List<T>
)

internal fun <T> buildHierarchy(
    accounts: List<T>,
    getCategoryKeys: (T) -> Triple<String, String, String>
): List<AccountHierarchy<T>> {
    val grouped = accounts.groupBy { getCategoryKeys(it).first }
    return CATEGORY_A_ORDER
        .filter { it in grouped.keys }
        .map { categoryA ->
            val accountsA = grouped[categoryA] ?: emptyList()
            val bGrouped = accountsA.groupBy { getCategoryKeys(it).second }
            val bOrder = CATEGORY_B_ORDER[categoryA] ?: emptyList()
            val sortedBGroups = bOrder
                .filter { it in bGrouped.keys }
                .map { categoryB ->
                    val accountsB = bGrouped[categoryB] ?: emptyList()
                    val cGrouped = accountsB.groupBy { getCategoryKeys(it).third }
                    val cOrder = CATEGORY_C_ORDER[categoryB] ?: emptyList()
                    val sortedCGroups = if (cOrder.isNotEmpty()) {
                        cOrder.filter { it in cGrouped.keys }.map { categoryC ->
                            CategoryCGroup(categoryC = categoryC, accounts = cGrouped[categoryC] ?: emptyList())
                        }
                    } else {
                        cGrouped.map { (categoryC, accountsC) ->
                            CategoryCGroup(categoryC = categoryC, accounts = accountsC)
                        }
                    }
                    CategoryBGroup(categoryB = categoryB, categoryCGroups = sortedCGroups)
                }
            AccountHierarchy(categoryA = categoryA, categoryBGroups = sortedBGroups)
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryASelector(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val isSelected = category == selectedCategory
            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                label = {
                    Text(
                        text = category.removePrefix("【").removeSuffix("】"),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@Composable
internal fun <T> FilteredAccountList(
    categoryBGroups: List<CategoryBGroup<T>>,
    expandedCategoryB: Set<String>,
    expandedCategoryC: Set<String>,
    onToggleCategoryB: (String) -> Unit,
    onToggleCategoryC: (String) -> Unit,
    accountContent: @Composable (T) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        categoryBGroups.forEach { categoryBGroup ->
            val keyB = categoryBGroup.categoryB
            item(key = "B:$keyB") {
                CategoryHeader(
                    title = categoryBGroup.categoryB,
                    level = 0,
                    isExpanded = keyB in expandedCategoryB,
                    itemCount = categoryBGroup.categoryCGroups.sumOf { it.accounts.size },
                    onClick = { onToggleCategoryB(keyB) }
                )
            }
            if (keyB in expandedCategoryB) {
                categoryBGroup.categoryCGroups.forEach { categoryCGroup ->
                    val keyC = "${keyB}/${categoryCGroup.categoryC}"
                    if (categoryCGroup.categoryC != categoryBGroup.categoryB) {
                        item(key = "C:$keyC") {
                            CategoryHeader(
                                title = categoryCGroup.categoryC,
                                level = 1,
                                isExpanded = keyC in expandedCategoryC,
                                itemCount = categoryCGroup.accounts.size,
                                onClick = { onToggleCategoryC(keyC) }
                            )
                        }
                        if (keyC in expandedCategoryC) {
                            items(
                                items = categoryCGroup.accounts,
                                key = { account ->
                                    when (account) {
                                        is RakurakuAccount -> "R:${account.id}"
                                        is YayoiAccount -> "Y:${account.id}"
                                        else -> account.hashCode()
                                    }
                                }
                            ) { accountContent(it) }
                        }
                    } else {
                        items(
                            items = categoryCGroup.accounts,
                            key = { account ->
                                when (account) {
                                    is RakurakuAccount -> "R:${account.id}"
                                    is YayoiAccount -> "Y:${account.id}"
                                    else -> account.hashCode()
                                }
                            }
                        ) { accountContent(it) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryHeader(
    title: String,
    level: Int,
    isExpanded: Boolean,
    itemCount: Int,
    onClick: () -> Unit
) {
    val backgroundColor = when (level) {
        0 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        1 -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(start = (16 + level * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.removePrefix("【").removeSuffix("】"),
            fontWeight = if (level == 0) FontWeight.Bold else FontWeight.Medium,
            fontSize = when (level) { 0 -> 15.sp; 1 -> 14.sp; else -> 13.sp },
            modifier = Modifier.weight(1f)
        )
        Text(text = "${itemCount}件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
