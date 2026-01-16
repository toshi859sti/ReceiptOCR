# Receipt OCR - Technical Specifications

OCRシステムの技術仕様（座標、閾値、定数）を定義。

---

## 基本設定

### ArUcoマーカー
- **マーカーサイズ**: 25mm
- **辞書**: DICT_4X4_50
- **マーカーID**: 0, 1, 2, 3 (B_BLOCK)

### 出力サイズ
- **固定出力**: 2400×1700px (A4全体) - 旧方式
- **動的出力**: ~4158×2940px (可変、実測px/mm比率ベース) - 新方式 (2025-12-31~)
- **mm→px変換率 (固定)**: 8.1 px/mm
- **mm→px変換率 (動的)**: 10.0-14.0 px/mm (文字高さ30px以上を確保)

### 行クラスタリング
- **Y座標差閾値**: 15px
- **根拠**: 実測値 64.5mm/20行 ≈ 3.2mm/行

---

## 品質管理閾値

### フォーカス (シャープネス)
- **閾値**: 250.0 (Laplacian分散)
- **更新履歴**:
  - 初期: 150.0
  - 2025-12-24: 200.0
  - 2025-12-25: 250.0 (現在)

### 輝度
- **最小値**: 40.0 (グレースケール平均値)
- **最大値**: 220.0
- **計算方法**: グレースケール変換 (0.299R + 0.587G + 0.114B) → 平均

### OCR品質スコア
- **総合閾値**: 70% (0.70)
- **重み配分**:
  - フォーカス: 20%
  - 文字高さ: 50%
  - コントラスト: 30%

### 安定フレーム数
- **MIN_STABLE_FOCUS_FRAMES**: 1
- **更新履歴**: 3 → 1 (2025-12-31)

---

## OCR画像前処理パラメータ

### シャープニング (Unsharp Mask)
```kotlin
Imgproc.GaussianBlur(grayMat, blurred, Size(0.0, 0.0), 3.0)
Core.addWeighted(grayMat, 1.5, blurred, -0.5, 0.0, sharpened)
```
- **重み (元画像)**: 1.5
- **重み (ブラー)**: -0.5
- **Gaussianブラー sigma**: 3.0

### CLAHE (コントラスト制限適応ヒストグラム均等化)
```kotlin
val clahe = Imgproc.createCLAHE()
clahe.clipLimit = 2.0
clahe.tilesGridSize = Size(8.0, 8.0)
```
- **clipLimit**: 2.0
- **タイルサイズ**: 8×8

---

## 適応的解像度OCRシステム (2025-12-31~)

### 動的透視変換
- **ArUcoマーカー間距離測定**: ID 0-1 間 (247mm)
- **実測px/mm**: 9.47-9.78 (3264×2448入力時)
- **目標px/mm**:
  ```kotlin
  val targetPxPerMm = when {
      measuredPxPerMm < 10.0 -> 14.0  // 文字を救う
      measuredPxPerMm < 14.0 -> measuredPxPerMm
      else -> 14.0
  }
  ```
- **出力サイズ計算**:
  ```kotlin
  dstWidth = (paperWidthMm * targetPxPerMm).toInt()   // 4158px
  dstHeight = (paperHeightMm * targetPxPerMm).toInt() // 2940px
  ```

### 文字高さ測定
- **手法**: Cannyエッジ検出 → 膨張 → 輪郭検出
- **Cannyエッジ閾値**: 80.0-160.0
- **膨張カーネル**: 3×3 MORPH_RECT
- **有効高さ範囲**: 6-80px (ノイズ/罫線除外)
- **統計手法**: 中央値 (外れ値に強い)

### 適応的スケーリング
- **目標文字高さ**: 32px (ML Kit最適域: 30-40px)
- **スケール倍率**:
  ```kotlin
  val scaleFactor = (32f / currentCharPx).coerceIn(1.0f, 3.0f)
  ```
- **制限**: 1.0-3.0倍

### 文字高さスコアリング (2400pxスケール)
```kotlin
when {
    height < 15 -> 0.0
    height < 20 -> lerp(0.0, 0.4, (height - 15) / 5)   // 認識困難
    height < 25 -> lerp(0.4, 0.6, (height - 20) / 5)   // 不安定
    height < 30 -> lerp(0.6, 0.75, (height - 25) / 5)  // 最低限
    height <= 40 -> lerp(0.75, 1.0, (height - 30) / 10) // 良好
    height <= 50 -> 1.0                                 // 理想的
    height <= 70 -> 0.95
    else -> 0.7
}
```

---

## 伝票座標定義

### 伝票位置 (A4左上原点)
- **伝票左端**: 43.0mm (A4左端から)
- **伝票上端**: 31.0mm (A4上端から)

### 列範囲 (伝票左端からの相対位置 mm)
| 列名 | mm範囲 | px範囲 (8.1px/mm) | 用途 | 処理 |
|------|--------|-------------------|------|------|
| DATE | 5.5-20.0 | 392-510 | 取引日 | 取得 |
| ITEM | 20.0-79.5 | 510-992 | 商品名 | 取得 |
| STORE | 79.5-97.0 | 992-1133 | 取扱支店 | 無視 |
| QUANTITY | 97.0-117.0 | 1133-1295 | 数量 | 取得 |
| UNITPRICE | 117.0-134.5 | 1295-1437 | 税込単価 | 無視 |
| AMOUNT | 134.5-156.0 | 1437-1611 | 税込金額 | 取得 |
| CATEGORY | 156.0-177.5 | 1611-1786 | 分類計 | 取得 |

### Y座標範囲 (伝票上端からの相対位置 mm)
| 範囲種別 | mm範囲 | 説明 |
|----------|--------|------|
| 通常行 | 56.5-120.5 | 一般購買+給油所セクション |
| 小計行 | 120.5-132.0 | 分類小計行 |

**更新履歴:**
- 2025-12-24: 通常行上限 120.5mm に修正 (実測値ベース)

---

## 列別OCR設定

### 商品名列 (ITEM)

#### 旧方式 (固定倍率)
```kotlin
// 固定3倍拡大
val upscaledBitmap = Bitmap.createScaledBitmap(
    itemColumnBitmap, width * 3, height * 3, true
)
```

#### 新方式 (適応的スケーリング, 2025-12-31~)
```kotlin
// 1. グレースケール変換
val grayBitmap = ImagePreprocessor.toGray(itemColumnBitmap)

// 2. コントラスト強化 (1.2倍)
val enhancedBitmap = ImagePreprocessor.adjustContrast(grayBitmap, 1.2f)

// 3. 文字高さ測定 → 倍率計算
val charPx = ImagePreprocessor.estimateCharHeightPx(enhancedBitmap)
val scaleFactor = ImagePreprocessor.calcScaleFactor(charPx, 32f)

// 4. スケーリング実行 (1.0-3.0倍)
val upscaledBitmap = Bitmap.createScaledBitmap(
    enhancedBitmap,
    (enhancedBitmap.width * scaleFactor).toInt(),
    (enhancedBitmap.height * scaleFactor).toInt(),
    true
)
```

#### DoubleOCR (Gray + Binary, 2026-01-01~)
```kotlin
// 1. Gray版OCR
val grayResult = recognizeTextJapanese(grayBitmap)

// 2. Binary版OCR (条件付き)
val binaryResult = if (charPx >= 18 && edgeDensity >= 0.02) {
    val binaryBitmap = ImagePreprocessor.adaptiveThreshold(enhancedBitmap)
    recognizeTextJapanese(binaryBitmap)
} else null

// 3. 評価・選択
val selectedResult = OcrResultEvaluator.selectBest(
    grayResult, binaryResult, productDao, category
)
```

**前処理パイプライン:**
1. グレースケール変換
2. コントラスト強化 (1.2倍)
3. モルフォロジーOpen (kernel 3×3)
4. エッジ密度測定 → 二値化要否判定
5. 適応的二値化 (blockSize=11, C=2)

### 数量列 (QUANTITY)
```kotlin
// 1. 列切り出し (X: 1133-1295px)
val quantityColumnBitmap = Bitmap.createBitmap(
    warpedBitmap, quantityX, 0, quantityWidth, height
)

// 2. 4倍アップスケール (162px → 648px)
val upscaledBitmap = Bitmap.createScaledBitmap(
    quantityColumnBitmap, width * 4, height * 4, true
)

// 3. Latin OCR (数字に特化)
val text = recognizeTextLatin(upscaledBitmap)

// 4. Y座標マッピング → 上書き
```
- **拡大倍率**: 4倍 (固定)
- **OCRモード**: Latin (数字認識に強い)
- **正規化**: o→0, l→1, I→1, スペース削除

### 金額列 (AMOUNT)
```kotlin
// 負値対応 + 数値正規化
val isNegative = box.text.contains("-")
val normalized = normalizeToDigits(box.text)
val value = normalized.toIntOrNull()
amount = if (isNegative && value != null) -value else value
```

### 小計列 (CATEGORY_SUM)
```kotlin
// normalizeToDigits適用
val normalized = normalizeToDigits(box.text)
categorySum = normalized.toIntOrNull()
```

---

## 正規化関数

### normalizeToDigits
```kotlin
fun normalizeToDigits(text: String): String {
    return text
        .replace("o", "0").replace("O", "0")
        .replace("l", "1").replace("I", "1")
        .replace("S", "5").replace("s", "5")
        .replace(" ", "").replace(",", "")
}
```

### normalizeQuantity
```kotlin
fun normalizeQuantity(raw: String): Int? {
    return raw
        .replace("o", "0").replace("O", "0")
        .replace("l", "1").replace("I", "1")
        .replace(" ", "").replace(",", "")
        .toIntOrNull()
}
```

### cleanItemName (日付パターン除去)
```kotlin
private fun cleanItemName(itemName: String): String {
    // パターン1: OCR誤認識を含む日付 (p71xxx, めE1021)
    val pattern1 = Regex("^.{0,3}[0-9oOlI.:/ ]{4,7}[|]?")
    var cleaned = itemName.replace(pattern1, "")

    // パターン2: 正確な6桁日付 (071011)
    if (cleaned == itemName) {
        val pattern2 = Regex("^\\d{6}[|]?")
        cleaned = itemName.replace(pattern2, "")
    }

    // 前方区切り文字削除
    cleaned = cleaned.trimStart('|', ' ', '　')

    // 空文字列チェック
    if (cleaned.isEmpty() || cleaned.length < 2) {
        cleaned = itemName
    }

    return cleaned
}
```

---

## OcrResultEvaluator スコアリング (2026-01-01~)

### 総合スコア構成
```kotlin
val totalScore =
    dictionaryMatchScore * 0.40 +  // 辞書マッチ: 40%
    editDistanceScore * 0.25 +     // 編集距離: 25%
    numericScore * 0.20 +          // 数値正確性: 20%
    confidenceScore * 0.10 +       // ML Kit信頼度: 10%
    lengthScore * 0.05             // 長さ: 5%
```

### 各スコアリング詳細

#### 辞書マッチスコア
```kotlin
when {
    similarity >= 0.90 -> 1.0
    similarity >= 0.80 -> 0.9
    similarity >= 0.70 -> 0.8
    similarity >= 0.60 -> 0.6
    similarity >= 0.50 -> 0.4
    else -> 0.0
}
```

#### 編集距離スコア
```kotlin
val editSimilarity = 1f - (editDistance / maxLength.toFloat())
editSimilarity.coerceIn(0f, 1f)
```

#### 数値スコア
```kotlin
val digitRatio = digitCount / text.length.toFloat()
when {
    digitRatio > 0.5 -> 0.0   // 商品名なのに数字多すぎる
    digitRatio > 0.3 -> 0.5
    else -> 1.0
}
```

#### 長さスコア
```kotlin
when {
    length >= 3 -> 1.0
    length == 2 -> 0.7
    length == 1 -> 0.3
    else -> 0.0
}
```

---

## カメラ解像度

### ImageAnalysis解像度
- **目標解像度**: 3840×2160 (4K)
- **実際の解像度**: 3264×2448 (デバイス最大値)
- **更新履歴**:
  - 初期: 1920×1080 (FHD)
  - 2025-12-31: 3840×2160 (4K) ✅

### Preview解像度
- **参考値**: 1600×1200 (4:3)

---

## デバッグフラグ

### マーカー検出バイパス (暫定)
```kotlin
private const val DEBUG_SKIP_MARKER_CHECK = false  // true: マーカー検出スキップ
```
- **注意**: 本番環境では `false` 必須

---

## パフォーマンス最適化

### 品質評価スケール
- **プレビュースケール**: 1280px (フォーカス・コントラスト評価)
- **OCRスケール**: 2400px (文字高さ評価)
- **効果**: 品質評価速度 10-20倍高速化

### 処理フロー最適化
- **旧**: 多スケールOCR (1x/2x/3x試行)
- **新**: 単一適応スケール (文字高さ測定ベース)
- **効果**: 処理時間削減、精度向上

---

## 更新履歴

- **2026-01-01**: OcrResultEvaluator スコアリング追加、DoubleOCR実装
- **2025-12-31 23:30**: 適応的解像度OCRシステム実装
- **2025-12-31 20:50**: ImageAnalysis 4K化
- **2025-12-31 19:40**: 文字高さスコアリング2400pxスケール対応
- **2025-12-31 15:30**: 文字高さスコアリング1280pxスケール調整
- **2025-12-25**: フォーカス閾値250.0、OCR画像前処理追加
- **2025-12-24**: フォーカス閾値200.0、Y軸範囲修正、商品名クリーニング
- **2025-12-23**: 行クラスタリング閾値15px、列範囲再定義
