# 購買品 OCR 仕様書（GreenFrame 方式）

現行の GreenFrame 方式（2026-03-14〜）における OCR パイプライン全体仕様。
コードベースは `util/GreenFrameDetector.kt` / `util/OCRProcessor.kt` /
`util/UnderlyingBaseProcessor.kt` / `util/ProductNameCorrectorV3.kt`。

---

## 対象伝票

- **名称**: 島原雲仙農業協同組合 購買代金請求明細書
- **用紙サイズ**: A5横（203mm × 148mm）
- **枠**: 3辺のみ緑枠（右辺なし・コの字）

---

## 1. 撮影フロー（1回撮影方式）

```
CameraX 4K ImageAnalysis（3840×2160）
 ↓ YuvToRgbConverter → RGB Bitmap
 ↓ GreenFrameDetector.process(bitmap, debugMode=false)
   Step1: HSV 緑マスク
   Step2: 外側輪郭抽出
   Step3: 中央枠検出（適応二値化 + モルフォロジー）
   Step4: 4コーナー算出
   Step5: 透視変換 → 3045×2220px（固定）
   Step6: 中央枠内領域確定
   Step7: 行切り抜き（debugMode=true のみ）
   Step8: 適応二値化（表示用）
 ↓ warpedBitmap（3045×2220px）
 ↓ OCRProcessor.processUnderlayingBase(warpedBitmap, mmToPixelRatio)
   Step2〜Step11（後述）
 ↓ ProductNameCorrectorV3.correctProductName()
 ↓ Room DB 保存
```

---

## 2. 透視変換

| 項目 | 値 |
|---|---|
| 解像度定数 | `WARP_PX_PER_MM = 15.0`（**変更禁止**） |
| 出力サイズ | 3045 × 2220 px（203mm × 148mm） |
| mmToPixelRatio | `warpedBitmap.width / 203.0 ≈ 15.0` |

> 20px/mm は Step7 行切り抜きが 2.6 倍遅くなるため不採用。

---

## 3. 伝票座標定義

### 列範囲（伝票左上原点・mm単位）

| 列名 | mm 範囲 | px 範囲（15px/mm） | OCR | モデル |
|---|---|---|---|---|
| 取引日 (DATE) | 5.5 〜 20.0 mm | 83 〜 300 px | 取得 | ※全体 OCR から |
| 商品名 (ITEM) | 20.0 〜 79.5 mm | 300 〜 1193 px | 取得 | 日本語（列特化） |
| 取扱支店 (STORE) | 79.5 〜 97.0 mm | 1193 〜 1455 px | **無視** | — |
| 数量 (QUANTITY) | 97.0 〜 117.0 mm | 1455 〜 1755 px | 取得 | Latin（列特化） |
| 税込単価 (UNITPRICE) | 117.0 〜 134.5 mm | 1755 〜 2018 px | **無視** | — |
| 税込金額 (AMOUNT) | 134.5 〜 156.0 mm | 2018 〜 2340 px | 取得 | ※全体 OCR から |
| 分類計 (CATEGORY_SUM) | 156.0 〜 177.5 mm | 2340 〜 2663 px | 取得 | ※全体 OCR から |

### Y座標範囲（伝票上端原点・mm単位）

| 範囲種別 | mm 範囲 | px 範囲（15px/mm） |
|---|---|---|
| 通常行・小計行 | 56.5 〜 120.5 mm | 848 〜 1808 px |
| 月合計行 | 121.0 〜 135.0 mm | 1815 〜 2025 px |

> NORMAL_ROW_Y_START_MM=56.5 の根拠: ヘッダーテーブル（前月請求/当月請求等）を除外するため。40.0mm だとヘッダー行を巻き込む。

---

## 4. OCR パイプライン詳細（processUnderlayingBase）

### Step2: ML Kit 全体 OCR（日本語モデル）

```kotlin
val text = OCRProcessor.recognizeText(warpedBitmap)  // 日本語モデル
// → 約1,034ms（ML Kit限界・ボトルネック）
```

### Step3: TextBox 変換

各 `Line` を `TextBox(text, bounds, centerX, centerY)` に変換。
先頭6桁が数字のテキストは日付として別途 TextBox を生成する。

### Step4: ノイズ除去（filterNoise）

除去条件:
- テキストが空白のみ
- bbox の幅または高さが 5px 未満
- `※｜| ` のみの記号列（縦罫線誤認識）
- ただし `*` `＊` は小計識別文字のため除去しない

### Step5: 行クラスタリング（clusterRows）

Y座標でソートし、閾値以内のボックスを同一行にまとめる。

**動的閾値計算:**
```
heights = 全TextBoxの高さリスト
IQR法で外れ値除去 → 平均高さ avg
threshold = (avg × 0.6).coerceIn(10, 30)
```
デフォルトフォールバック: 15px

### Step6-7: 行処理 + Y座標フィルタリング

各行を `detectRowType()` で分類し、Y座標フィルタで範囲外行を除去。

**行タイプ判定（detectRowType）:**

| 判定 | 条件 |
|---|---|
| SUBTOTAL | 行テキストに `小計` / `一般購買` / `給油所` / `農業機械` を含む AND Y ∈ normalRange |
| MONTHLY_TOTAL | 行テキストに `月合計` / `合計` / `税込` 等を含む AND Y ∈ totalRange |
| NORMAL | 上記以外 |
| EMPTY | TextBox なし |

### Step8: 数量列特化 OCR（Latin モデル）

```
ROI: QUANTITY_RANGE x × (rowY ± 25px)
前処理:
  1. bitmapToMat → COLOR_RGBA2GRAY
  2. ImagePreprocessor.removeLines(removeVertical=true)  ← 縦罫線除去（必須）
  3. matToBitmap
OCR: recognizeTextLatin（Latin モデル）
フィルタ:
  - aspect > 5.0 → 除外（罫線誤認識）
  - height < 12px → 除外
  - 1〜3桁の数字のみ採用
アップスケーリング: なし（15px/mm で十分な解像度）
```

### Step8.5: 商品名列特化 OCR（日本語モデル・DoubleOCR）

```
ROI: ITEM_RANGE x × NORMAL_ROW_Y_RANGE y（列全体を1枚の画像として処理）
前処理:
  1. toGray → adjustContrast(1.2f)
  2. bitmapToMat → COLOR_RGBA2GRAY
  3. estimateCharHeightPx() で文字高さ推定
  4. calcEdgeDensity / calcBlackRatio / calcStrokeWidthVariance 計算
  5. calcBinaryCandidateScore(charPx, edgeDensity, blackRatio, strokeWidthVar)
  6. safeMorphOpen(grayMat, charPx)

グレー版 OCR: 常に実行（recognizeText・日本語モデル）
バイナリ版 OCR: 以下の全条件を満たす場合のみ実行
  - charPx ≥ 18
  - blackRatio ≤ 0.45
  - edgeDensity ≥ 0.02
  - binaryCandidateScore ≥ 0.5
  → safeAdaptiveThreshold(openedMat, charPx) で二値化

アップスケーリング: なし（scaleFactor = 1.0f 固定）
結果: DoubleOcrResult(grayText, binaryText, binaryCandidateScore)
```

### Step8.6: 商品名フォールバック

Step8.5 で商品名が空だった行に対し、全体 OCR の TextBox から Y 差 **15px 以内**のボックスを検索して商品名を復元する。

```
separatedTexts = itemRange 内のTextBoxをX順でソート
fallbackText = joinToString("")
dateRemovedText = 先頭6桁数字パターンを除去
cleanedFallback = cleanItemName(dateRemovedText)
```

### Step9: 数量・商品名の上書き

```
newQuantity = quantityMap[index] ?: row.quantity
newItemName = doubleResult?.grayText ?: doubleResult?.binaryText ?: row.itemName
// 「返品」を含む行かつ数量が正値 → 負値に変換
```

### Step10: 小計抽出

SUBTOTAL 行の `categorySum` を抽出。

### Step11: カテゴリ判定（assignCategories）

→ [6節 カテゴリ判定] 参照。

---

## 5. 数値正規化

### normalizeToDigits（金額・日付）

OCR 典型誤認識の補正テーブル:

| 誤認識 | 正解 |
|---|---|
| O / o / p | 0 |
| I / i / l / \| / : | 1 |
| B / b | 8 |
| S | 5 |

数字以外の文字をすべて除去して整数に変換。

### 商品名ノイズクリーニング（cleanLeadingRuleNoise）

縦罫線の誤認識を先頭から除去:

```kotlin
// 先頭の '|' を除去
var s = text.trimStart('|')
// 先頭が 'I' かつ直後がひらがな・カタカナ・漢字 → 縦罫線誤認識とみなして除去
if (s.length >= 2 && s[0] == 'I' &&
    s[1].code in (0x3040..0x30FF) || s[1].code in (0x4E00..0x9FFF)) {
    s = s.substring(1)
}
```

例: `|レギュラーガソリン` → `レギュラーガソリン`、`Iエンジンオイル` → `エンジンオイル`

### 商品名から日付パターン除去（cleanItemName）

商品名列に混入する日付テキストを除去する正規表現パターン（最大3回適用）:

```
Regex("^[a-zA-Z]{1,3}[0-9oOlIeEbBsS.:/ ]{2,8}[|]?")  // 例: p71022|
Regex("^[0-9oOlIeEbBsS]{6}[|]?")                       // 例: 071011
Regex("^[a-zA-Z][0-9oOlIeEbBsS]+\\s+[0-9oOlIeEbBsS]+[|]?")
Regex("^[a-zA-Z0-9...]{3,12}(?=[日本語文字])")          // 日本語直前の英数字
Regex("\\s+[a-zA-Z0-9...]{4,12}(?=[日本語文字])")
```

固定除去文字列（footer ノイズ）:
- `＊以下の方法にて、ご入金をお願いします。`
- `北有馬` / `東南部基` / `南部基幹` / `南有馬`

---

## 6. カテゴリ判定

小計行のテキストから購買カテゴリを判定する（assignCategories）。

**カテゴリ区分（4種）:**

| カテゴリ | 意味 |
|---|---|
| 一般購買 | 通常仕入商品 |
| 給油所 | 燃料・ガソリン |
| 農業機械 | 農業用機械・部品 |
| 未分類 | 判定不能 |

**キーワードマッチング（誤認識パターン含む）:**

| カテゴリ | マッチ文字列 |
|---|---|
| 一般購買 | 一般購買 / 一般買 / 一般講買 / ー般購買 / 般購買 / 般講買 |
| 給油所 | 給油所 / 給値所 / 給造所 / 給治所 / 給抽所 |
| 農業機械 | 農業機械 / 展業慢城 / 農来 / 農発検 / 農業 |

**一文字フォールバック:**

| 文字 | → カテゴリ |
|---|---|
| 般 / 購 / 買 / 講 / 課 | 一般購買 |
| 農 / 機 / 械 / 展 / 慢 / 城 / 来 / 発 / 検 | 農業機械 |
| 給 / 油 / 所 / 値 / 造 / 治 / 抽 | 給油所 |

**カテゴリ割り当てロジック:**
- 各行は「自行より後にある最初の SUBTOTAL 行」のカテゴリを継承
- 最初の SUBTOTAL 行より前の NORMAL 行は「未分類」
- MONTHLY_TOTAL 行は「月合計」

---

## 7. 商品名補正システム（ProductNameCorrectorV3）

### 3層補正構造

```
Layer 1（無条件）: LOCKED / 手動CONFIRMED バリアント
  → スコアに関係なく即時補正
Layer 2（スコア検証）: 自動CONFIRMED バリアント
  → finalScore ≥ 75 AND 2位との差 ≥ 10
Layer 3（マスタ直接）: product_master の canonicalName
  → finalScore ≥ 78 AND 差 ≥ 12 AND penalty > -30
```

### スコアリング（100点満点）

| 項目 | 最大点 | 計算方法 |
|---|---|---|
| 文字類似度 | 60点 | レーベンシュタイン距離ベース: `(1 - dist/maxLen) × 60` |
| 先頭欠落ボーナス | 10点 | 先頭1文字欠落: +10 / 先頭2文字欠落: +5 |
| 濁点誤認識ボーナス | 5点 | 濁点除去後に一致: +5 |
| 既存知識ボーナス | 15点 | LOCKED: +15 / CONFIRMED: +10 / AUTO: 0 |
| 手動修正履歴ボーナス | 10点 | manualCorrectCount > 0: +10 |
| リスクペナルティ | -30点 | 容量違い or 数字違い: -30（下限） |

### 判定閾値

| 条件 | 理由 | 動作 |
|---|---|---|
| Layer1 ヒット | LOCKED / 手動 CONFIRMED | `UNCONDITIONAL_VARIANT`（無条件補正） |
| Layer2 ヒット: score≥75 & 差≥10 | CONFIRMED 自動 | `CONFIRMED_VARIANT`（補正） |
| score≥78 & 差≥12 & penalty>-30 | マスタ直接 | `MASTER_MATCH`（補正） |
| score<78 または 差<12 | スコア不足 | `REJECT_SCORE_LOW` / `REJECT_GAP_INSUFFICIENT` |
| penalty≤-30 | リスク大 | `REJECT_RISK_PENALTY` |

### 自動学習登録条件

| 条件 | 値 |
|---|---|
| 最低スコア | ≥ 88点 |
| 最低差分 | ≥ 12点 |
| 最低文字数 | ≥ 3文字 |

### OcrVariant 昇格・降格条件

**昇格:**

| 遷移 | 条件 |
|---|---|
| AUTO → CONFIRMED（自動） | hitCount≥3 AND avgFinalScore≥0.90 AND highScoreHits≥2 AND autoFailCount=0 |
| AUTO → CONFIRMED（手動） | manualCorrectCount≥2（異なるバッチ） AND autoFailCount=0 |
| CONFIRMED → LOCKED | hitCount≥10 AND avgFinalScore≥0.92 AND autoFailCount=0 |

**降格・無効化:**

| 条件 | 動作 |
|---|---|
| AUTO AND autoFailCount≥1 | `isDisabled=true`（無効化） |
| CONFIRMED AND autoFailCount≥1 | AUTO に降格 |
| LOCKED AND autoFailCount≥1 | カウントのみ記録（手動解除が必要） |

> 設計思想: 時間減衰なし・同一バッチ内の重複は1回カウント・低頻度利用（月1回〜年1回）向け

---

## 8. 分離テキスト結合（ExplicitJoinMatcher）

OCR が分離して認識したテキストを正しく結合するパターンを学習する。

例: `灯|油` → `灯油`、`農業|機械` → `農業機械`

**昇格条件:**
- hitCount ≥ 5 OR manualConfirmCount ≥ 2 → CONFIRMED に昇格

---

## 9. カメラ品質管理

### フォーカス評価（Laplacian 分散）

- 閾値: 250.0（MIN_FOCUS_SCORE = 0.35 以下で強制不合格）
- 安定フレーム数: `MIN_STABLE_FOCUS_FRAMES = 3`

### 輝度

- 最小: 40.0（グレースケール平均）
- 最大: 220.0

---

## 10. パフォーマンス計測結果（15px/mm・本番モード）

| ステップ | 所要時間 |
|---|---|
| GreenFrameDetector 合計 | 約 1,225 ms |
| Step2 全体 OCR（日本語） | 約 1,034 ms |
| Step8.5 商品名列 OCR | 約 984 ms |
| **OCRProcessor 合計** | **約 2,500 ms** |
| **全体合計** | **約 3,700 ms** |

ボトルネック: ML Kit（Step2 + Step8.5）で約 2,000ms。ML Kit の API 限界であり改善余地なし。

---

## 更新履歴

| 日付 | 内容 |
|---|---|
| 2026-04-29 | GreenFrame 方式に全面書き直し（旧 ArUco 仕様を廃止） |
| 2026-03-14 | GreenFrame 方式移行・旧 ArUco 仕様をアーカイブ |
| 2026-02-02 | ProductNameCorrectorV3（100点満点・3層構造）採用 |
| 2026-01-18 | ProductNameCorrectorV3 全面再設計（低頻度利用向け） |
