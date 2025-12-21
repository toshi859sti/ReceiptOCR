# Receipt OCR台紙仕様書（下に敷くタイプ）

## 概要

農協精算伝票のOCR処理用台紙仕様（下に敷くタイプ）。伝票を台紙の上に置いてスマートフォンで撮影し、ArUcoマーカー4点を用いた射影変換（warpPerspective）により、歪みを補正した正規化画像を生成する。

**本仕様の特徴**:
- **実測値ベース設計**: 理論値ではなく実測値から列範囲を決定
- **安全マージン**: 手置きズレ、罫線侵入、warp誤差を考慮
- **柔軟な行処理**: 固定グリッドではなく行クラスタリング
- **正規表現検証**: 列判定にテキストパターンを併用

---

## 1. 座標系の定義

### 原点

* **基準**: 伝票左上 (0, 0)
* **X軸**: 右方向が正
* **Y軸**: 下方向が正

この座標系は、warp後の画像で適用される。台紙座標系ではなく、**伝票自体を基準とした座標系**を使用する。

---

## 2. warp後のスケール

### 実測値

* **スケール**: **8.1 px/mm**
* **測定方法**: 実際にwarp後の画像で既知の距離を測定

### 変換式

```kotlin
fun mmToPx(mm: Double): Int = (mm * 8.1).toInt()
fun pxToMm(px: Int): Double = px / 8.1
```

---

## 3. 列範囲定義

### 3.1 実測値（mm）

| 列 | mm範囲 | 特徴 |
|---|---|---|
| 取引日 | 5.5 – 20.0 | 6桁数字（YYMMDD） |
| 商品名 | 20.0 – 79.5 | 日本語最大20文字 |
| （空白帯） | 79.5 – 134.5 | 隣列文字侵入あり、使用しない |
| 税込金額 | 134.5 – 156.0 | 数字、マイナス可 |
| 分類計 | 156.0 – 177.0 | 数字 |

### 3.2 理論px変換（8.1 px/mm）

| 列 | px範囲（理論） |
|---|---|
| 取引日 | 45 – 162 |
| 商品名 | 162 – 644 |
| 税込金額 | 1090 – 1264 |
| 分類計 | 1264 – 1434 |

### 3.3 実用範囲（安全マージン込み）⭐ **重要**

👉 **実装で使用する値**

| 列 | 使用px範囲 | マージン |
|---|---|---|
| 取引日 | 50 – 155 | ±5-7px |
| 商品名 | 170 – 620 | +8px, -24px |
| 税込金額 | 1110 – 1240 | +20px, -24px |
| 分類計 | 1280 – 1410 | +16px, -24px |

**マージンの理由**:
1. 罫線直近の文字侵入
2. 手置きズレ（±1mm程度）
3. warp後の誤差（±2-3px）
4. テキストボックスの中心位置判定のため

---

## 4. 行範囲定義

### 実測値

* **Y開始**: 55.0 mm
* **Y終了**: 119.5 mm
* **高さ**: 64.5 mm
* **最大行数**: 20 行

### px変換

* **Y開始**: 446 px
* **Y終了**: 968 px
* **1行高さ**: 約 3.2 mm ≒ 26 px

---

## 5. 行クラスタリング設計

### 基本方針

固定グリッド（20行強制分割）ではなく、**Y座標ベースの行クラスタリング**を使用する。

### パラメータ

```kotlin
const val ROW_THRESHOLD_PX = 15  // Y座標の閾値
```

### アルゴリズム

```kotlin
fun clusterRows(textBoxes: List<TextBox>): List<List<TextBox>> {
    val sorted = textBoxes.sortedBy { it.centerY }
    val rows = mutableListOf<MutableList<TextBox>>()

    var currentRow = mutableListOf<TextBox>()
    var lastY = -1000

    for (box in sorted) {
        if (box.centerY - lastY > ROW_THRESHOLD_PX) {
            // 新しい行を開始
            if (currentRow.isNotEmpty()) {
                rows.add(currentRow)
            }
            currentRow = mutableListOf(box)
        } else {
            // 同じ行に追加
            currentRow.add(box)
        }
        lastY = box.centerY
    }

    if (currentRow.isNotEmpty()) {
        rows.add(currentRow)
    }

    return rows
}
```

### メリット

* ✅ 空行に対応
* ✅ 印字ズレに対応
* ✅ 手置きズレに対応
* ✅ 柔軟な行数（20行未満でもOK）

---

## 6. 列判定ロジック

### 6.1 基本判定（X座標ベース）

```kotlin
enum class ColumnType {
    DATE,           // 取引日
    ITEM,           // 商品名
    AMOUNT,         // 税込金額
    CATEGORY_SUM,   // 分類計
    SUBTOTAL        // 小計・合計
}

fun detectColumn(cx: Int): ColumnType? = when (cx) {
    in 50..155 -> ColumnType.DATE
    in 170..620 -> ColumnType.ITEM
    in 1110..1240 -> ColumnType.AMOUNT
    in 1280..1410 -> ColumnType.CATEGORY_SUM
    else -> null  // 空白帯は無視
}
```

### 6.2 正規表現検証（テキストパターン）

```kotlin
fun validateColumn(column: ColumnType?, text: String): ColumnType? {
    return when {
        // 6桁数字 → 取引日確定
        text.matches(Regex("^\\d{6}$")) -> ColumnType.DATE

        // 数字のみ（マイナス可） → 税込金額 or 分類計
        text.matches(Regex("^-?\\d+$")) -> {
            // X座標で判別
            column
        }

        // それ以外 → 商品名
        else -> column
    }
}
```

### 6.3 隣列侵入対策

**やってはいけない**:
```kotlin
❌ val itemROI = Rect(162, y, 482, height)  // ぴったりで切る
```

**正解**:
```kotlin
✅ val itemROI = Rect(150, y, 520, height)  // 少し広く取る
✅ 判定は X中心 + 正規表現で行う
```

---

## 7. 合計行（小計行）の特別処理

### 位置

* **Y範囲**: 119.5 – 123.0 mm
* **Y範囲（px）**: 968 – 996 px

### 判定ロジック

```kotlin
fun detectSubtotal(box: TextBox): Boolean {
    return box.centerY in 968..996 &&
           box.centerX in 1280..1410
}

// 処理例
for (box in textBoxes) {
    if (detectSubtotal(box)) {
        subtotalValue = box.text
        continue  // 通常行としては処理しない
    }
    // 通常行の処理...
}
```

### 重要ポイント

* 通常行とは**完全に分離**して処理
* Y座標で判別（20行目の延長線上ではない）
* X座標で列判別（分類計の列）

---

## 8. OCR処理フロー（完成形）

### 全体フロー

```
1. ArUco検出 & warp
   ↓
2. ROI抽出（伝票全体）
   ↓
3. ML Kit OCR実行
   ↓
4. TextBox取得（bbox + text）
   ↓
5. 行クラスタリング（Y座標、閾値15px）
   ↓
6. 行ごとに列振り分け（X座標 + 正規表現）
   ↓
7. 小計行の特別処理（Y: 968-996, X: 1280-1410）
   ↓
8. データ構造化（ReceiptItem生成）
```

### データ構造

```kotlin
data class TextBox(
    val text: String,
    val bounds: Rect,
    val centerX: Int,
    val centerY: Int
)

data class ReceiptRow(
    val date: String?,         // 取引日（6桁）
    val itemName: String?,     // 商品名
    val amount: Int?,          // 税込金額
    val categorySum: Int?      // 分類計
)
```

---

## 9. 実装ガイド

### 9.1 新規クラス: `UnderlyingBaseProcessor.kt`

```kotlin
package com.example.receiptorc.util

object UnderlyingBaseProcessor {
    private const val TAG = "UnderlyingBaseProcessor"

    // スケール定数
    private const val SCALE = 8.1  // px/mm

    // 行クラスタリング
    private const val ROW_THRESHOLD_PX = 15

    // 列範囲（実用範囲）
    private val DATE_RANGE = 50..155
    private val ITEM_RANGE = 170..620
    private val AMOUNT_RANGE = 1110..1240
    private val CATEGORY_RANGE = 1280..1410

    // 小計行範囲
    private val SUBTOTAL_Y_RANGE = 968..996

    // 列判定
    fun detectColumn(cx: Int): ColumnType? = when (cx) {
        in DATE_RANGE -> ColumnType.DATE
        in ITEM_RANGE -> ColumnType.ITEM
        in AMOUNT_RANGE -> ColumnType.AMOUNT
        in CATEGORY_RANGE -> ColumnType.CATEGORY_SUM
        else -> null
    }

    // 小計判定
    fun isSubtotalRow(cy: Int, cx: Int): Boolean {
        return cy in SUBTOTAL_Y_RANGE && cx in CATEGORY_RANGE
    }

    // 行クラスタリング
    fun clusterRows(textBoxes: List<TextBox>): List<List<TextBox>> {
        // 実装...
    }

    // 正規表現検証
    fun validateByPattern(text: String): ColumnType? {
        return when {
            text.matches(Regex("^\\d{6}$")) -> ColumnType.DATE
            text.matches(Regex("^-?\\d+$")) -> null  // 金額は座標で判別
            else -> ColumnType.ITEM
        }
    }
}
```

### 9.2 列挙型

```kotlin
enum class ColumnType {
    DATE,           // 取引日
    ITEM,           // 商品名
    AMOUNT,         // 税込金額
    CATEGORY_SUM,   // 分類計
    SUBTOTAL        // 小計・合計
}
```

### 9.3 統合例

```kotlin
// OCRProcessor.kt に追加
fun processUnderlayingBase(warpedImage: Bitmap): List<ReceiptRow> {
    // 1. ML Kit OCR
    val textBoxes = recognizeText(warpedImage)

    // 2. 小計を分離
    val (subtotals, normalBoxes) = textBoxes.partition {
        UnderlyingBaseProcessor.isSubtotalRow(it.centerY, it.centerX)
    }

    // 3. 行クラスタリング
    val rows = UnderlyingBaseProcessor.clusterRows(normalBoxes)

    // 4. 各行を処理
    return rows.map { rowBoxes ->
        processRow(rowBoxes)
    }
}

private fun processRow(rowBoxes: List<TextBox>): ReceiptRow {
    var date: String? = null
    var itemName = StringBuilder()
    var amount: Int? = null
    var categorySum: Int? = null

    for (box in rowBoxes) {
        val column = UnderlyingBaseProcessor.detectColumn(box.centerX)
        val validated = UnderlyingBaseProcessor.validateByPattern(box.text)

        when (validated ?: column) {
            ColumnType.DATE -> date = box.text
            ColumnType.ITEM -> itemName.append(box.text)
            ColumnType.AMOUNT -> amount = box.text.toIntOrNull()
            ColumnType.CATEGORY_SUM -> categorySum = box.text.toIntOrNull()
            else -> {}  // 無視
        }
    }

    return ReceiptRow(date, itemName.toString(), amount, categorySum)
}
```

---

## 10. この設計の強み

### ✅ 実測値ベース
* 理論値ではなく実際の伝票から測定
* 0.1mm精度は不要、実用的な範囲設定

### ✅ ロバスト性
* 手置きズレに対応（±1mm）
* warp誤差に対応（±2-3px）
* 罫線侵入に対応（マージン設定）

### ✅ 柔軟性
* 固定グリッドではなく行クラスタリング
* 空行、印字ズレに対応
* 20行未満でもOK

### ✅ シンプル
* セル分割の複雑なロジック不要
* ML Kitの自動検出を活用
* 正規表現で検証

### ✅ 拡張性
* 列の追加が容易
* パラメータ調整が容易
* デバッグしやすい

---

## 11. パラメータチューニングガイド

### もし認識精度が低い場合

#### 列範囲の調整

```kotlin
// 商品名が欠ける場合
private val ITEM_RANGE = 165..625  // 左端を5px広げる

// 税込金額が誤認識される場合
private val AMOUNT_RANGE = 1115..1235  // 左端を5px狭める
```

#### 行クラスタリングの調整

```kotlin
// 行がまとまりすぎる場合
private const val ROW_THRESHOLD_PX = 12  // 閾値を下げる

// 行が分離しすぎる場合
private const val ROW_THRESHOLD_PX = 18  // 閾値を上げる
```

#### 小計行の調整

```kotlin
// 小計が検出されない場合
private val SUBTOTAL_Y_RANGE = 960..1000  // 範囲を広げる
```

---

## 12. デバッグ方法

### 列範囲の可視化

```kotlin
fun drawColumnRanges(image: Mat) {
    // 取引日（赤）
    Imgproc.rectangle(image,
        Point(50.0, 446.0), Point(155.0, 968.0),
        Scalar(0.0, 0.0, 255.0), 2)

    // 商品名（緑）
    Imgproc.rectangle(image,
        Point(170.0, 446.0), Point(620.0, 968.0),
        Scalar(0.0, 255.0, 0.0), 2)

    // 税込金額（青）
    Imgproc.rectangle(image,
        Point(1110.0, 446.0), Point(1240.0, 968.0),
        Scalar(255.0, 0.0, 0.0), 2)

    // 分類計（黄）
    Imgproc.rectangle(image,
        Point(1280.0, 446.0), Point(1410.0, 968.0),
        Scalar(0.0, 255.0, 255.0), 2)

    // 小計行（白）
    Imgproc.rectangle(image,
        Point(1280.0, 968.0), Point(1410.0, 996.0),
        Scalar(255.0, 255.0, 255.0), 3)
}
```

### TextBoxの可視化

```kotlin
fun drawTextBoxes(image: Mat, textBoxes: List<TextBox>) {
    for (box in textBoxes) {
        val column = UnderlyingBaseProcessor.detectColumn(box.centerX)
        val color = when (column) {
            ColumnType.DATE -> Scalar(0.0, 0.0, 255.0)  // 赤
            ColumnType.ITEM -> Scalar(0.0, 255.0, 0.0)  // 緑
            ColumnType.AMOUNT -> Scalar(255.0, 0.0, 0.0)  // 青
            ColumnType.CATEGORY_SUM -> Scalar(0.0, 255.0, 255.0)  // 黄
            else -> Scalar(128.0, 128.0, 128.0)  // 灰
        }

        Imgproc.rectangle(image, box.bounds, color, 2)
        Imgproc.circle(image,
            Point(box.centerX.toDouble(), box.centerY.toDouble()),
            5, color, -1)
    }
}
```

---

## まとめ

本仕様は、**実測値ベース** + **安全マージン** + **柔軟な行処理** により、高精度かつロバストなOCRを実現する。

### 実装の要点

1. ✅ 列範囲は実用範囲（マージン込み）を使用
2. ✅ 行は固定グリッドではなくクラスタリング
3. ✅ 列判定はX座標 + 正規表現の両方
4. ✅ 小計行は通常行と完全分離
5. ✅ 空白帯（79.5-134.5mm）は完全無視

---

**作成日**: 2025-12-21
**対象プロジェクト**: ReceiptOCR
**想定デバイス**: Android (Kotlin)
**ブランチ**: feature/underlay-base
**本仕様書は下に敷くタイプの最終版とし、実装・運用の基準とする。**
