package com.example.greenframeocr.ui

// YayoiAccount.categoryA の表示順（勘定科目一覧と同じ「流動資産→…」の順）
internal val YAYOI_CATEGORY_A_ORDER = listOf(
    "【流動資産】", "【固定資産】", "【繰延資産】",
    "【流動負債】", "【固定負債】",
    "【資本】", "【事業主貸】", "【事業主借】",
    "【収入金額】", "【経費】", "【繰入額等】", "【繰戻額等】"
)

// カテゴリA文字列の集合を、上記の会計上の並び順に整列する（未知の値はアルファベット順で末尾に追加）
internal fun sortYayoiCategoryA(categories: Collection<String>): List<String> {
    val present = categories.toSet()
    val ordered = YAYOI_CATEGORY_A_ORDER.filter { it in present }
    val extras = present.filterNot { it in YAYOI_CATEGORY_A_ORDER }.sorted()
    return ordered + extras
}
