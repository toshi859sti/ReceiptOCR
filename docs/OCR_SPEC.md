# 購買品OCR仕様書（マーカー方式）

ArUcoマーカーを台紙に貼付して透視変換を行っていた旧方式のOCR仕様。
現行のGreenFrame方式への移行後も、OCRパイプライン（列定義・補正システム・検算）は本仕様を継承。

---

## 対象伝票

- **名称**: 島原雲仙農業協同組合 購買代金請求明細書
- **用紙サイズ**: A5横（A4を半分に折った左半分）

---

## 台紙・マーカー仕様

| 項目 | 値 |
|------|----|
| マーカー種別 | ArUco DICT_4X4_50 |
| マーカーID | 0, 1, 2, 3（B_BLOCK） |
| マーカーサイズ | 25mm |
| 台紙 | A4用紙に4隅貼付 |

---

## 透視変換

### 出力サイズ
| 方式 | 出力サイズ | px/mm | 採用時期 |
|------|------------|-------|----------|
| 固定 | 2400×1700px | 8.1 px/mm | 旧 |
| 動的（文字高さ保証） | ~4158×2940px | 10.0〜14.0 px/mm | 2025-12-31〜 |

### 動的 px/mm 決定ロジック
```kotlin
val targetPxPerMm = when {
    measuredPxPerMm < 10.0 -> 14.0  // 文字を救う
    measuredPxPerMm < 14.0 -> measuredPxPerMm
    else -> 14.0
}
dstWidth  = (paperWidthMm  * targetPxPerMm).toInt()  // 例: 4158px
dstHeight = (paperHeightMm * targetPxPerMm).toInt()  // 例: 2940px
```

---

## 撮影フロー（2ブロック方式）

旧方式では伝票を **Bブロック**（日付＋商品名）と **Cブロック**（金額）に分けて2回撮影した。

```
撮影① Bブロック
 → ArUcoマーカー検出 → 透視変換 → 列分割 → OCR
   ├ 左列（日付）: Latin OCR
   └ 右列（商品名）: Japanese OCR

撮影② Cブロック
 → ArUcoマーカー検出 → 透視変換 → 金額列 OCR
   └ 金額: Latin OCR

Y座標マッチング で Bブロック行 と Cブロック金額を結合
 → ParsedRow(date, productName, amount)
```

---

## 伝票座標定義（A4左上原点）

### 伝票位置
- 伝票左端: A4左端から 43.0mm
- 伝票上端: A4上端から 31.0mm

### 列範囲（伝票左端からの相対位置）

| 列名 | mm範囲 | px範囲（8.1px/mm） | 処理 | OCRモデル |
|------|--------|--------------------|------|-----------|
| 取引日 (DATE) | 5.5〜20.0mm | 392〜510px | 取得 | Latin |
| 商品名 (ITEM) | 20.0〜79.5mm | 510〜992px | 取得 | Japanese |
| 取扱支店 (STORE) | 79.5〜97.0mm | 992〜1133px | 無視 | — |
| 数量 (QUANTITY) | 97.0〜117.0mm | 1133〜1295px | 取得 | Latin |
| 税込単価 (UNITPRICE) | 117.0〜134.5mm | 1295〜1437px | 無視 | — |
| 税込金額 (AMOUNT) | 134.5〜156.0mm | 1437〜1611px | 取得 | Latin |
| 分類計 (CATEGORY_SUM) | 156.0〜177.5mm | 1611〜1786px | 取得 | Latin |

### Y座標範囲（伝票上端からの相対位置）

| 範囲種別 | mm範囲 | 備考 |
|----------|--------|------|
| 通常行（一般購買＋給油所） | 56.5〜120.5mm | 2025-12-24修正（実測値ベース） |
| 小計行 | 120.5〜132.0mm | 分類小計 |

---

## 列別OCR処理詳細

### 商品名列（ITEM）

#### DoubleOCR方式（2026-01-01〜）
グレー版OCRを主系とし、二値版が明確に優れている場合のみ採用する。

**前処理パイプライン:**
1. グレースケール変換
2. コントラスト強化（1.2倍）
3. 文字高さ測定 → 拡大倍率決定（1.0〜3.0倍、目標32px）
4. エッジ密度測定 → 二値化要否判定
5. （条件付き）適応的二値化（ADAPTIVE_THRESH_GAUSSIAN_C, blockSize=11, C=2）

**グレー版OCRスコア:**
```
grayScore = 0.35×confidence + 0.20×scriptScore + 0.25×dictScore + 0.10×bboxConsistency + 0.10×lengthScore
```

**二値版OCR採用条件（すべて満たす必要あり）:**
1. binaryCandidateScore ≥ 0.6
2. confidence ≥ 0.55
3. dictMatchScore ≥ 0.5
4. binaryScore ≥ grayScore + 0.15

**binaryCandidateScore（段階A判定）:**
```
strokePenalty =
    strokeWidthVar ≤ 0.3 → 0.0
    strokeWidthVar ≤ 0.6 → 0.1
    strokeWidthVar ≤ 0.9 → 0.2
    else → 0.3

binaryCandidateScore =
    0.40 × charHeightNorm
  + 0.40 × edgeDensityNorm
  - 0.20 × strokePenalty
```

#### 適応的スケーリング（2025-12-31〜）
```kotlin
// 文字高さ測定（OpenCV輪郭検出ベース）
val charPx = ImagePreprocessor.estimateCharHeightPx(enhancedBitmap)
// 拡大倍率: (32f / charPx).coerceIn(1.0f, 3.0f)
val scaleFactor = ImagePreprocessor.calcScaleFactor(charPx, targetCharPx = 32f)
```

#### 文字高さとスコアの関係
```
< 15px  → score 0.0（認識不能）
15〜20px → lerp(0.0, 0.4)（認識困難）
20〜25px → lerp(0.4, 0.6)（不安定）
25〜30px → lerp(0.6, 0.75)（最低限）
30〜40px → lerp(0.75, 1.0)（良好）  ← ML Kit最適域
40〜50px → 1.0（理想的）
50〜70px → 0.95
> 70px  → 0.7
```

### 数量列（QUANTITY）
```kotlin
// 4倍拡大 → Latin OCR → 数字のみ抽出
val scaled4x = ImagePreprocessor.scale(gray, 4)
val result = OCRProcessor.recognizeTextLatin(scaled4x)
val digits = result.filter { it.isDigit() }
```

### 金額列（AMOUNT）
```kotlin
// 負値対応 + 数値正規化
val isNegative = text.contains("-")
val normalized  = ValidationUtils.sanitizeNumber(text)
```

---

## 数値正規化（共通）

### sanitizeNumber
OCR典型誤認識の補正テーブル適用 → 数字とマイナスのみ残す:

| 誤認識 | 正解 |
|--------|------|
| O / o | 0 |
| l / I | 1 |
| S / s | 5 |
| B | 8 |

```kotlin
fun sanitizeNumber(raw: String): Int? {
    var s = raw
        .replace("O", "0").replace("o", "0")
        .replace("l", "1").replace("I", "1")
        .replace("S", "5").replace("s", "5")
        .replace("B", "8")
    return s.filter { it.isDigit() || it == '-' }.toIntOrNull()
}
```

### 商品名クリーニング（日付パターン除去）
商品名列に取引日がOCR混入する場合に除去:
```kotlin
// パターン1: OCR誤認識を含む日付 (例: p71xxx, めE1021)
val pattern1 = Regex("^.{0,3}[0-9oOlI.:/ ]{4,7}[|]?")
// パターン2: 正確な6桁日付 (例: 071011)
val pattern2 = Regex("^\\d{6}[|]?")
// 先頭の区切り文字削除
cleaned = cleaned.trimStart('|', ' ', '　')
```

---

## 日付パース

OCRで取得した日付テキスト → (year, month, day) に変換。

| 桁数 | 解釈 | 例 |
|------|------|----|
| 6桁以上 | YY MM DD | "060130" → 年06, 月01, 日30 |
| 4桁 | MM DD（年は0） | "0130" → 月01, 日30 |
| それ以外 | デフォルト (0, 1, 1) | — |

---

## 検算バリデーション（validateWithRounding）

単価×数量=金額 を端数処理3パターンで許容:

```kotlin
fun validateWithRounding(unitPrice: Int, quantity: Int, amount: Int): Boolean {
    val exact = unitPrice.toBigDecimal() * quantity.toBigDecimal()
    val candidates = listOf(
        exact.setScale(0, FLOOR).toInt(),
        exact.setScale(0, HALF_UP).toInt(),
        exact.setScale(0, CEILING).toInt()
    )
    return candidates.any { it == amount }
}
```

---

## 商品名補正システム

3バージョンが存在し、最新はV3（2026-01-18〜）。

### V3スコアリング（100点満点）

```
totalScore =
    文字類似度（最大60点）
  + 先頭欠落ボーナス（最大10点）
  + 濁点誤認識ボーナス（最大5点）
  + 既存知識ボーナス（最大15点）
  + 手動修正履歴ボーナス（最大10点）
  + リスクペナルティ（最大-30点）
```

#### 文字類似度（最大60点）
レーベンシュタイン距離ベース:
```kotlin
val distance = levenshteinDistance(normalizedRaw, normalizedProduct)
val similarity = 1.0 - (distance.toDouble() / maxLen)
textSimilarity = (similarity * 60.0).coerceIn(0.0, 60.0)
```

#### 先頭欠落ボーナス（最大10点）
OCR行先頭文字欠落パターンを救済:
- 先頭1文字欠落: +10点
- 先頭2文字欠落: +5点

#### 濁点誤認識ボーナス（最大5点）
濁点除去後に一致する場合: +5点

#### 既存知識ボーナス（最大15点）
OcrVariantテーブルの信頼度に連動:
| confidenceLevel | ボーナス |
|-----------------|--------|
| LOCKED | +15点 |
| CONFIRMED | +10点 |
| AUTO | 0点（補正に使用しない） |

#### 手動修正履歴ボーナス（最大10点）
manualCorrectCount > 0 の場合: +10点

#### リスクペナルティ（最大-30点）
- 容量違い（両方に容量がありかつ不一致）: -30点
- 数字違い（商品名中の数字が異なる）: -30点
- 下限: -30点

#### 判定閾値

| 条件 | 判定 | 動作 |
|------|------|------|
| Layer 1: LOCKED / 手動CONFIRMED バリアントに一致 | UNCONDITIONAL_VARIANT | 無条件補正 |
| Layer 2: CONFIRMED バリアント × スコア≥75 & 差≥10 | CONFIRMED_VARIANT | スコア検証後補正 |
| Layer 3: スコア≥78 & 差≥12 & ペナルティ>-30 | MASTER_MATCH | マスタ直接補正 |
| スコア<78 または 差<12 | REJECT | 補正なし |
| ペナルティ≤-30 | REJECT_RISK | 補正なし |

#### 自動学習登録条件
| 条件 | 値 |
|------|----|
| 最低スコア | 88点以上 |
| 最低差分 | 12点以上 |
| 最低文字数 | 3文字以上 |

### V3 OcrVariant 昇格条件

| 遷移 | 条件 |
|------|------|
| AUTO → CONFIRMED（自動学習由来） | hitCount≥3 & avgFinalScore≥0.90 & highScoreHits≥2 & autoFailCount=0 |
| AUTO → CONFIRMED（手動修正由来） | manualCorrectCount≥2（異なるバッチ） & autoFailCount=0 |
| CONFIRMED → LOCKED | hitCount≥10 & avgFinalScore≥0.92 & autoFailCount=0 |

| 降格条件 | 動作 |
|----------|------|
| AUTO & autoFailCount≥1 | 無効化（isDisabled=true） |
| CONFIRMED & autoFailCount≥1 | AUTO降格 |
| LOCKED & autoFailCount≥1 | カウントのみ（手動解除が必要） |

### V2スコアリング（参考・2025-12末〜2026-01-17）

剤型あり商品:
- ブランド一致度: 30%（前方一致・N-gram・部分一致）
- 剤型一致度: 30%（OCR誤認識パターンマップ使用）
- 編集距離: 25%
- OCR信頼度（日本語文字率）: 15%

剤型なし商品（ガソリン・灯油等）:
- ブランド一致度: 40%
- 編集距離: 35%
- OCR信頼度: 25%

採用閾値: スコア≥0.75（自動補正） / スコア≥0.60（候補表示）

### 剤型揺れマップ（V2で使用）
| 正規形 | OCR誤認識パターン例 |
|--------|---------------------|
| 顆粒 | 類粒, 顆立, 拉粒 |
| 水和剤 | 水初剤, 水和則, 水和財 |
| 乳剤 | 刺, 乱, 孚剤 |
| フロアブル | フロアフル, 7ロアブル |
| 粒剤 | 粒則, 粒到 |

---

## 品質管理（カメラ撮影時）

### フォーカス評価（Laplacian分散）
| 閾値 | 時期 |
|------|------|
| 150.0 | 初期 |
| 200.0 | 2025-12-24 |
| 250.0 | 2025-12-25〜 |

### 輝度
- 最小: 40.0（グレースケール平均）
- 最大: 220.0

### OCR品質スコア重み配分（OcrQualityEvaluator）
| 指標 | 旧重み | 新重み |
|------|--------|--------|
| フォーカス | 20% | 30% |
| 文字高さ | 50% | 40% |
| コントラスト | 30% | 30% |

- フォーカススコア < 0.35 → 総合スコアに関係なく強制不合格（MIN_FOCUS_SCORE）

### 安定フレーム数
- MIN_STABLE_FOCUS_FRAMES: 3フレーム連続OK → 撮影

---

## 画像前処理パラメータ

### シャープニング（Unsharp Mask）
```kotlin
Imgproc.GaussianBlur(grayMat, blurred, Size(0.0, 0.0), sigma=3.0)
Core.addWeighted(grayMat, 1.5, blurred, -0.5, 0.0, sharpened)
```

### CLAHE（コントラスト制限適応ヒストグラム均等化）
```kotlin
val clahe = Imgproc.createCLAHE()
clahe.clipLimit = 2.0
clahe.tilesGridSize = Size(8.0, 8.0)
```

### 行クラスタリング閾値
- Y座標差: 15px
- 根拠: 実測値 64.5mm/20行 ≈ 3.2mm/行

---

## カメラ解像度

| 用途 | 解像度 | 採用時期 |
|------|--------|----------|
| ImageAnalysis（OCR） | 1920×1080 (FHD) | 初期 |
| ImageAnalysis（OCR） | 3840×2160 (4K) → 実際 3264×2448 | 2025-12-31〜 |
| 品質評価スケール | 1280px | 速度最適化 |

---

## ROIクロップ（現行GreenFrame方式との対応）

現行の `OcrCaptureViewModel.parseRowBitmap()` では、透視変換後の行ビットマップをX比率でクロップ。

| 列名 | X比率 |
|------|-------|
| 取引日 | 0.02〜0.10 |
| 商品名 | 0.10〜0.46 |
| 取扱支店 | 0.46〜0.55 |
| 数量 | 0.55〜0.62 |
| 税込単価 | 0.62〜0.72 |
| 税込金額 | 0.72〜0.83 |

---

## 更新履歴

| 日付 | 内容 |
|------|------|
| 2026-03-14 | GreenFrame方式移行後にマーカー方式仕様として整理 |
| 2026-02-02 | ProductNameCorrectorV3（100点満点・3層構造）採用 |
| 2026-01-18 | ProductNameCorrectorV3全面再設計（低頻度利用向け） |
| 2026-01-01 | OcrResultEvaluator・DoubleOCR実装 |
| 2025-12-31 | 適応的解像度OCR・4K撮影・動的透視変換 |
| 2025-12-25 | フォーカス閾値250.0・OCR画像前処理強化 |
| 2025-12-24 | フォーカス閾値200.0・Y軸範囲修正・商品名クリーニング |
| 2025-12-23 | 行クラスタリング閾値15px・列範囲再定義 |
