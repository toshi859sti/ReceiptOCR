# Receipt OCR Project - Change Log

過去の改善履歴を時系列で記録。

---

## 2026-01-12

### 15:00: 二段階Binary OCR評価システム実装

**背景:**
- Binary OCRは「補助火力」であり、主力ではない
- Gray OCRは常に主系、Binary OCRは「明確に勝った場合のみ」採用
- 線幅分散は「禁止条件」ではなく「ペナルティ要素」とすべき

**設計思想: 二段階評価**

**段階A: 実行判定 (Should Run Binary OCR?)**
- 目的: 計算コスト節約、明らかに不適な画像は実行しない
- ハード条件（最低要件）:
  ```kotlin
  val canTryBinary = (
      charPx >= 18f &&          // 文字高さ不足
      blackRatio <= 0.45 &&     // 黒画素過多（二値化失敗）
      edgeDensity >= 0.02       // エッジ不足（文字なし）
  )
  ```
- 候補スコア計算（binaryCandidateScore）:
  ```kotlin
  val charHeightNorm = ((charPx - 18f) / (40f - 18f)).coerceIn(0f, 1f)
  val edgeDensityNorm = ((edgeDensity - 0.02) / (0.10 - 0.02)).coerceIn(0.0, 1.0)

  // 線幅分散を段階的ペナルティに変更（日本語テキストは自然に分散が高い）
  val strokePenalty = when {
      strokeWidthVar <= 0.3 -> 0.0  // 理想的
      strokeWidthVar <= 0.6 -> 0.1  // 許容範囲
      strokeWidthVar <= 0.9 -> 0.2  // 高いが試す価値あり
      else -> 0.3                   // 非常に高い
  }

  score = 0.40 * charHeightNorm + 0.40 * edgeDensityNorm - 0.20 * strokePenalty
  ```
- 実行判定: `canTryBinary && binaryCandidateScore >= 0.5`

**段階B: 採用判定 (Should Adopt Binary Result?)**
- 目的: Gray vs Binary の最終決定、辞書の正規名で評価
- Gray/Binaryスコア計算:
  ```kotlin
  // Grayスコア（主系）
  grayScore = 0.35 * confidence + 0.20 * scriptScore + 0.25 * dictionaryScore
            + 0.10 * bboxConsistency + 0.10 * lengthScore

  // Binaryスコア（辞書重視）
  binaryScore = 0.30 * confidence + 0.15 * scriptScore + 0.35 * dictionaryScore
              + 0.10 * bboxConsistency + 0.10 * lengthScore
  ```
- Binary採用条件（すべて満たす必要あり）:
  ```kotlin
  val meetsCondition1 = binaryCandidateScore >= 0.6
  val meetsCondition2 = (binaryResult.confidence ?: 0f) >= 0.55f
  val meetsCondition3 = binaryScoreDetails.dictMatchScore >= 0.5
  val meetsCondition4 = binaryScore >= grayScore + 0.15

  return if (all conditions met) binaryResult else grayResult
  ```

**実装内容:**

1. **ImagePreprocessor.kt: 線幅分散を段階的ペナルティに変更**
   ```kotlin
   fun calcBinaryCandidateScore(...): Double {
       val strokePenalty = when {
           strokeWidthVar <= 0.3 -> 0.0
           strokeWidthVar <= 0.6 -> 0.1
           strokeWidthVar <= 0.9 -> 0.2
           else -> 0.3
       }
       score = 0.40 * charHeightNorm + 0.40 * edgeDensityNorm - 0.20 * strokePenalty
   }
   ```

2. **OCRProcessor.kt: 段階A実行判定**
   ```kotlin
   val canTryBinary = (
       charPx >= 18f &&
       blackRatio <= 0.45 &&
       edgeDensity >= 0.02
   )
   // strokeWidthVarをハード条件から削除

   val shouldUseBinary = canTryBinary && binaryCandidateScore >= 0.5
   ```

3. **OcrResultEvaluator.kt: 段階B最終決定**
   - `calculateGrayScore()`: Grayスコア計算
   - `calculateBinaryScore()`: Binaryスコア計算（辞書重視）
   - `chooseBestResult()`: 4条件チェックで最終決定

4. **CameraViewModel.kt: 辞書マッチング後に段階B評価**
   ```kotlin
   if (correctionResult.matched) {
       val doubleOcrResult = result.productNameDoubleOcrMap[index]
       if (doubleOcrResult != null) {
           val bestResult = OcrResultEvaluator.chooseBestResult(
               grayOcrResult,
               binaryOcrResult,
               correctionResult.correctedName  // 辞書の正規名で評価
           )
       }
       // 最終的には辞書の正規名を使用
       row.copy(itemName = correctionResult.correctedName)
   }
   ```

**テスト結果:**
```
段階A:
- charPx=19.0-23.0, blackRatio=0.29-0.35, edgeDensity=0.04-0.06
- strokeWidthVar=0.84-0.85 → strokePenalty=0.3
- binaryCandidateScore=0.586-0.671 → Binary OCR実行 ✅

段階B:
- Binary採用条件を満たさず → Gray OCR採用 ✅
- 理由: dictMatchScore不足、binaryScore < grayScore + 0.15
```

**効果:**
- Binary OCR実行率: ~30%（段階Aを通過）
- Binary OCR採用率: 0%（段階Bで正しくフィルタリング）
- 線幅分散が高い日本語テキストでもBinary OCR実行可能に

---

### 12:00: 数量列OCR完全再設計

**背景:**
- 固定4倍拡大は不適切（文字高さが異なる伝票で過剰/不足）
- 罫線が「1」として誤認識される問題（OCR前に物理除去すべき）
- 商品名列と同じく文字高さ正規化が必要

**設計思想:**
1. **固定倍率廃止**: 4倍 → 文字高さベース適応的スケーリング (1.0-3.0倍)
2. **罫線物理除去**: OCR前にモルフォロジー処理で除去
3. **行ごと処理**: 列全体ではなく行ごとに処理
4. **シェイプフィルタ**: OCR後にBboxアスペクト比・高さでフィルタリング

**実装内容:**

1. **ImagePreprocessor.kt: 罫線除去関数追加**
   ```kotlin
   fun removeLines(
       grayMat: Mat,
       removeVertical: Boolean = true,
       removeHorizontal: Boolean = false
   ): Mat {
       val result = grayMat.clone()

       if (removeVertical) {
           // 縦線除去（最優先）
           val verticalKernel = getStructuringElement(
               MORPH_RECT,
               Size(1.0, grayMat.rows() * 0.6)  // 高さ60%
           )
           val verticalMask = Mat()
           morphologyEx(result, verticalMask, MORPH_OPEN, verticalKernel)
           subtract(result, verticalMask, result)
       }

       if (removeHorizontal) {
           // 横線除去
           val horizontalKernel = getStructuringElement(
               MORPH_RECT,
               Size(grayMat.cols() * 0.6, 1.0)  // 幅60%
           )
           val horizontalMask = Mat()
           morphologyEx(result, horizontalMask, MORPH_OPEN, horizontalKernel)
           subtract(result, horizontalMask, result)
       }

       return result
   }
   ```

2. **ImagePreprocessor.kt: 軽量文字高さ推定**
   ```kotlin
   fun estimateCharHeightSimple(grayMat: Mat): Float {
       // 1. 軽量二値化（閾値150）
       val binaryMat = Mat()
       threshold(grayMat, binaryMat, 150.0, 255.0, THRESH_BINARY_INV)

       // 2. 輪郭検出
       val contours = ArrayList<MatOfPoint>()
       findContours(binaryMat, contours, ...)

       // 3. 高さ分布取得
       val heights = contours.map { boundingRect(it).height }

       // 4. 中央値返却
       return heights.sorted()[heights.size / 2].toFloat()
   }
   ```

3. **OCRProcessor.kt: extractQuantitiesFromColumn() 完全書き換え**
   ```kotlin
   private suspend fun extractQuantitiesFromColumn(...): Map<Int, String> {
       rows.forEachIndexed { rowIndex, row ->
           // 1. 行ROI抽出
           val rowRoiBitmap = Bitmap.createBitmap(...)

           // 2. グレースケール変換
           val grayMatGray = Mat()
           cvtColor(rowMat, grayMatGray, COLOR_RGBA2GRAY)

           // 3. 罫線除去（OCR前処理、最重要）
           val cleanedMat = ImagePreprocessor.removeLines(
               grayMatGray,
               removeVertical = true,
               removeHorizontal = false
           )

           // 4. 文字高さ推定
           val charPx = ImagePreprocessor.estimateCharHeightSimple(cleanedMat)

           // 5. 適応的スケーリング（固定4倍廃止）
           val targetHeight = 30.0
           val scale = (targetHeight / charPx).coerceIn(1.0, 3.0)

           // 6. Latin OCR実行
           val ocrText = recognizeTextLatin(scaledBitmap)

           // 7. Bboxシェイプフィルタ（OCR後フィルタ）
           val elements = recognizer.process(inputImage).await()
           for (textBlock in elements.textBlocks) {
               for (line in textBlock.lines) {
                   for (element in line.elements) {
                       val bounds = element.boundingBox ?: continue
                       val width = bounds.width()
                       val height = bounds.height()
                       val aspectRatio = width.toDouble() / height.toDouble()

                       // アスペクト比チェック（横線除外）
                       if (aspectRatio > 5.0) {
                           continue  // 横線として除外
                       }

                       // 高さチェック（細い線除外）
                       if (height < 12) {
                           continue  // 細い線として除外
                       }

                       // 8. 正規表現チェック（^[0-9]{1,3}$）
                       val text = element.text
                       if (text.matches(Regex("^[0-9]{1,3}$"))) {
                           quantityMap[rowIndex] = text
                       }
                   }
               }
           }
       }
   }
   ```

**テスト結果:**
```
適応的スケーリング:
- Row 0: charPx=16.5 → scale=1.82 (30.0/16.5)
- Row 1: charPx=11.0 → scale=2.73 (30.0/11.0)
- Row 2: charPx=17.0 → scale=1.76 (30.0/17.0)

罫線除去:
- 縦線が物理的に除去され、「1」誤認識が大幅減少

Bboxシェイプフィルタ:
- アスペクト比 > 5.0 → 横線除外 ✅
- 高さ < 12px → 細い線除外 ✅
```

**効果:**
- 文字高さに応じた最適なスケーリング（過剰拡大/不足を防止）
- 罫線誤認識の大幅削減
- 行ごと処理でより精密な制御

---

### 09:00: Y座標フィルタリング修正

**問題:**
- 合計行がY座標フィルタリングで「見る前に捨てられる」
- 実測: 合計行 Y=2138px（152.71mm）
- 設定: MONTHLY_TOTAL_Y_START_MM = 153.0mm（2142px）
- 結果: 合計行が範囲外（2142-2198px）で除外

**修正内容:**

1. **UnderlyingBaseProcessor.kt: 合計行開始位置を修正**
   ```kotlin
   // 修正前
   private const val MONTHLY_TOTAL_Y_START_MM = RECEIPT_TOP_MM + 122.0  // 153.0mm

   // 修正後
   private const val MONTHLY_TOTAL_Y_START_MM = RECEIPT_TOP_MM + 121.0  // 152.0mm
   ```

2. **OCRProcessor.kt: フィルタリングを行タイプ判定後に移動**
   ```kotlin
   // 修正前のフロー
   OCR → 行クラスタリング → Y座標フィルタ → 行タイプ判定

   // 修正後のフロー
   OCR → 行クラスタリング → 行タイプ判定 → 行タイプ別Y座標フィルタ

   // 実装
   val filteredRows = rows.filter { row ->
       val yMm = row.bounds.centerY() / mmToPixelRatio

       when (row.rowType) {
           RowType.NORMAL, RowType.SUBTOTAL -> {
               yMm >= UnderlyingBaseProcessor.NORMAL_ROW_Y_START_MM &&
               yMm <= UnderlyingBaseProcessor.SUBTOTAL_Y_END_MM
           }
           RowType.MONTHLY_TOTAL -> {
               yMm >= UnderlyingBaseProcessor.MONTHLY_TOTAL_Y_START_MM &&
               yMm <= UnderlyingBaseProcessor.MONTHLY_TOTAL_Y_END_MM
           }
           else -> false  // HEADER, FOOTER は除外
       }
   }
   ```

**テスト結果:**
```
修正前:
- 33行検出 → Y座標フィルタ → 20行残存
- 合計行 Y=2138px（152.71mm）→ 範囲外除外 ❌

修正後:
- 33行検出 → 行タイプ判定 → Y座標フィルタ → 20行残存
- 合計行 Y=2138px（152.71mm）→ 範囲内保持 ✅
- ヘッダー/フッター行を正しく除外 ✅
```

**効果:**
- 合計行が「見る前に捨てられる」問題を解消
- ヘッダー/フッター行を正しく除外
- 行タイプに応じた柔軟なY座標フィルタリング

---

## 2026-01-01

### 09:45: Double OCR + OcrResultEvaluator実装 & 全角容量対応

**実装内容:**
1. **OcrResultEvaluator.kt** (新規作成)
   - 辞書ベースのインテリジェント結果選択
   - スコアリング: 辞書マッチ40%, 編集距離25%, 数値20%, 信頼度10%, 長さ5%

2. **DoubleOCR処理**
   - Gray版 + Binary版の2つのOCR結果を評価比較
   - 前処理パイプライン: グレースケール → コントラスト → モルフォロジーOpen → エッジ密度チェック → 条件付き二値化

3. **ProductNameCorrectorV2.kt**
   - 全角容量パターン対応 (ｃｃ, ｍｌ, ｋｇ など)
   - 容量重複バグ修正 (例: "100cc100 co" → "100cc")

**テスト結果:**
- 検出行数: 17行
- 補正成功率: **100% (9/9 補正可能項目)**
- 容量重複問題: ✅ 解決

---

## 2025-12-31

### 23:30: 適応的解像度OCRシステムの実装

**背景:**
- 4K ImageAnalysis化成功 (3264×2448)
- 固定2400×1700透視変換により文字高さ不足
- 実測: ~35px期待に対し、商品名列OCRで文字化け多発

**実装内容:**

1. **動的透視変換** (ImageProcessor.kt:398-445)
   ```kotlin
   - ArUcoマーカー間距離からpx/mm比率を実測
   - 目標px/mm (10-14) 設定 → 文字30px以上確保
   - 出力サイズ = A4サイズ(mm) × 目標px/mm
   ```

2. **文字高さ測定** (ImagePreprocessor.kt:264-329)
   ```kotlin
   - Cannyエッジ検出 → 膨張 → 輪郭検出
   - バウンディングボックス高さの中央値 = 文字高さ
   ```

3. **適応的スケーリング** (ImagePreprocessor.kt:340-382)
   ```kotlin
   - 現在の文字高さ測定 → 目標32pxに対する倍率計算
   - scale = 32px / 現在高さ (1.0-3.0に制限)
   - 単一OCRパス (多スケール試行を廃止)
   ```

4. **商品名列OCRへの統合** (OCRProcessor.kt:700-741)
   - 固定3倍拡大 → 適応的スケーリング
   - 文字高さベースの動的倍率計算

**実測結果:**
```
透視変換:
- Measured px/mm: 9.47-9.78
- Target px/mm: 14.00 (自動ブースト)
- Output size: 4158×2940 px (旧2400×1700比 73%向上)

商品名列OCR:
- Character height: 測定値ベース
- Scale factor: 動的計算 (1.0-3.0)
```

**技術的意義:**
- 入力解像度に適応 (3264×2448 → 4158×2940出力)
- ML Kit推奨30-40pxに自動調整
- 処理効率化 (多スケール試行廃止)

---

### 20:50: ImageCapture高解像度問題 → ImageAnalysis 4K解決策

**問題:**
- ImageCapture実装で座標変換失敗
- Preview: 1600×1200 (横長 4:3)
- High-res: 1836×2448 (縦長 3:4) - カメラ自動90度回転
- ArUco検出失敗、座標スケーリング複雑化

**試行した失敗アプローチ:**
1. 単純座標スケーリング (1.1475, 2.04) → ヘッダー/フッター誤検出 ❌
2. ビットマップ+90度回転 + 座標+90度回転 → 誤領域検出 ❌
3. ビットマップ-90度回転 + 座標-90度回転 → 誤領域検出 ❌

**最終解決策:** ✅
```kotlin
// ImageCaptureを廃止、ImageAnalysisの解像度向上
val imageAnalyzer = ImageAnalysis.Builder()
    .setTargetResolution(android.util.Size(3840, 2160))  // 4K
    .build()
```

**実測結果:**
- 実解像度: 3264×2448 (デバイス最大)
- 検出行数: 31行 → 17行 (Y範囲フィルタ後)
- 商品名補正: 86% (6/7)

**技術的意義:**
- シンプル性: 複雑な座標変換不要
- 信頼性: ArUco検出とOCR処理が同一画像
- 性能: 解像度2倍向上 (1600×1200 → 3264×2448)
- 保守性: コード量削減 (500行以上)

---

### 19:40: 文字高さ評価のOCRスケール対応 (問題①)

**問題分析:**
```
現状のフロー:
1. 品質評価: 1280pxスケールで実施
   → 文字高さ 6-7px = 「良好」(score 0.70-0.85)

2. 実際のOCR: 2400pxスケールで実施
   → 6-7px × (2400/1280) = 18-21px

問題:
- ML Kit推奨: 30-40px/文字
- 18-21pxは不足 → 文字化け多発
- ❌「読めない状態を高品質と誤判定」
```

**修正内容:**

1. **文字高さスコアリング更新**
   ```kotlin
   // 修正前（1280pxスケール）:
   height < 6 -> 0.0-0.7
   height < 8 -> 0.7-0.85

   // 修正後（2400pxスケール = OCR実行時）:
   height < 20 -> 0.0-0.4   // 認識困難
   height < 25 -> 0.4-0.6   // 不安定
   height < 30 -> 0.6-0.75  // 最低限
   height <= 40 -> 0.75-1.0 // 良好
   ```

2. **評価フロー分離**
   ```kotlin
   // ステップ1: フォーカス・コントラスト (1280px - 高速)
   val previewBitmap = scale to 1280px

   // ステップ2: 文字高さ (2400px - OCRスケール)
   val ocrBitmap = scale to 2400px
   ```

---

### 15:30: 品質閾値調整とパフォーマンス最適化

**問題:**
- ユーザー: 「70超えません。68,69止まり」
- 品質スコア: 0.690 (69%)
- 内訳: focus=1.0, charHeight=0.4, contrast=0.97
- ボトルネック: 文字高さスコアが0.4固定

**根本原因:**
- 品質評価: 1280pxダウンスケール
- スケール比: 1280 / 3264 ≈ 0.39 (39%)
- 実測文字高さ: 6-7px (1280pxスケール)
- 元画像換算: 6÷0.39 ≈ 15-18px (理想的!)
- charHeightScore()が元解像度用閾値使用 → 誤判定

**修正内容:**
```kotlin
// 修正前（元解像度用）:
height < 6 -> 0.0
height < 7 -> 0.4

// 修正後（1280pxスケール用）:
height < 3 -> 0.0
height < 4 -> 0.4
height < 6 -> 0.4-0.7
height < 8 -> 0.7-0.85  ← 6-7px該当
```

**効果:**
- 6px: 0.40 → **0.70** (75%向上)
- 7px: 0.40 → **0.775** (93%向上)
- 総合スコア: 68.9% → **88.8%** ✅

**実機テスト:**
```
OcrQuality(score=0.888, focus=713.7 (1.00),
           charHeight=7 (0.77), contrast=1.00, isGood=true)
```

**パフォーマンス改善:**
- 品質評価速度: 10-20倍高速化 (4K → 1280px)

---

### 07:00: OCR品質評価システムの実装

**背景:**
- ユーザー要求: 「撮影されません」
- 初期問題: フォーカス値0、文字高さ0

**実装:**
1. **OcrQualityEvaluator.kt** (新規)
   - 3指標: フォーカス(20%), 文字高さ(50%), コントラスト(30%)
   - Laplacian分散によるシャープネス計算
   - 垂直エッジ検出による文字高さ推定
   - 標準偏差によるコントラスト測定

2. **ImagePreprocessor.kt** (新規)
   - エッジ検出 (Sobel-like vertical)
   - 文字高さ推定 (connected components)

**調整経緯:**
- a) 計算エラー解消 (Int/Long型、エッジ検出方向)
- b) 実測値ベーススコアリング調整
  - フォーカス: 5-40範囲 → 0.0-1.0
  - 文字高さ: 6-15px範囲 → 0.4-0.85
- c) 閾値段階的引き下げ
  - QUALITY_THRESHOLD: 0.70 → 0.45
  - MIN_STABLE_FOCUS_FRAMES: 3 → 1

**ArUcoマーカー検出問題:**
- 暫定対応: DEBUG_SKIP_MARKER_CHECK = true

---

### 01:00: 辞書補正精度大規模テスト

**テスト:**
- 3枚の伝票、57行分析

**結果:**
- 成功率: 35% (20/57) - 目標70%未達

**根本原因特定:**
- OCR精度低: 商品名列が拡大なし (482px)
- 比較: 数量列(4倍拡大)は良好、商品名列(拡大なし)は不良

**次の修正:**
- 商品名列に2-3倍拡大追加

---

## 2025-12-30

### 日付パターン最適化 & システム統合

**実装内容:**
1. **日付パターン改善**
   - 4つの正規表現パターン
   - E/e→8 OCR誤認識対応
   - 繰り返しマッチング (最大3回)

2. **統合テスト**
   - 検出行数: 20行
   - 辞書補正成功: 55%
   - 自動学習: 6件の新しいOCR誤認識パターン記録

---

## 2025-12-29

### 辞書ベース補正システム実装

**データベース設計:**
- ProductMaster, OcrVariant, YayoiAccount, RakurakuAccount
- 4つのDAO + Room Database migration (v2→v3)

**CSVインポート:**
- 113商品
- 15弥生勘定科目
- 15らくらく勘定科目
- 13 OCR誤認識パターン

**容量保護型マッチング:**
- レーベンシュタイン距離
- 類似度閾値0.7
- カテゴリ別フィルタリング (小計行から逆算)
- 自動学習機能

**テスト結果:**
- 補正成功率: 70% (14/20)

---

## 2025-12-25

### 00:00: OCR画像前処理 & 辞書ベース補正設計

**背景:**
- 誤字多発: "乳素"→"乳剤", "ガッリン"→"ガソリン"
- i/o/O 誤認識多数

**実装内容:**

1. **OCR画像前処理** (ImageProcessor.kt)
   ```kotlin
   fun enhanceImageForOCR(bitmap: Bitmap): Bitmap {
       // 1. グレースケール変換
       // 2. シャープニング (Unsharp Mask, weight=1.5, blur=-0.5)
       // 3. CLAHE (clipLimit=2.0, tileSize=8x8)
   }
   ```

2. **フォーカス閾値引き上げ**
   - 200.0 → 250.0

**テスト結果:**
```
改善された誤字:
- "乳素" → "乳剤" ✅
- "ガッリン" → "ガソリン" ✅

依然残る誤字:
- i/l/o/O 誤認識
- 一部の文字: "類"→"頼"

小計精度: 100% (53551, 15810) ✅
```

**改善効果:**
- 文字認識向上
- ML Kit OCRの限界認識
- 次の施策: 辞書ベース補正必要

---

## 2025-12-24

### 22:36: 商品名クリーニング & 総合品質管理システム

**背景:**
- 商品名に日付混入: "p71008米用紙袋"
- 金額正規化不足: "158 1o" → 15810失敗
- 撮影品質不安定: シャープネス 38-197

**実装内容:**

1. **商品名クリーニング** (UnderlyingBaseProcessor.kt:690-710)
   ```kotlin
   private fun cleanItemName(itemName: String): String {
       // パターン1: OCR誤認識を含む日付 (p71xxx, めE1021)
       val pattern1 = Regex("^.{0,3}[0-9oOlI.:/ ]{4,7}[|]?")

       // パターン2: 正確な6桁日付 (071011)
       val pattern2 = Regex("^\\d{6}[|]?")
   }
   ```

2. **金額・小計正規化強化**
   ```kotlin
   fun normalizeToDigits(text: String): String {
       return text
           .replace("o", "0").replace("O", "0")
           .replace("l", "1").replace("I", "1")
           .replace("S", "5").replace("s", "5")
   }
   ```

3. **総合品質管理システム** (CameraScreen.kt:380-550)
   - a) 輝度計算関数 (640x480スケール、グレースケール平均)
   - b) 品質閾値最適化
     ```kotlin
     FOCUS_THRESHOLD = 200.0      // 150.0 → 200.0
     BRIGHTNESS_MIN = 40.0
     BRIGHTNESS_MAX = 220.0
     ```
   - c) 3段階品質チェック + 自動撮影
   - d) リアルタイム品質表示UI

**テスト結果:**
```
実測値比較:
一般購買 小計: 53,551円 ✅
給油所 小計:   15,810円 ✅

画質メトリクス:
- シャープネス: 1558.6 (閾値200.0を大幅上回る)
- 輝度: 155.5 (40-220適正範囲)

商品名クリーニング:
- "p71008米用紙袋" → "米用紙袋" ✅
- すべての商品名から日付除去 ✅
```

**改善効果:**
- 小計精度: 0% → **100%** (2/2完全一致)
- 商品名品質: 日付混入解決
- 撮影品質: 不安定(38-197) → 安定(1500+)
- ユーザー体験: リアルタイム品質確認

---

### 08:30: フォーカス品質最適化 & Y軸範囲修正

**実施内容:**
- フォーカス閾値: 150.0に引き上げ (品質優先)
- Y軸有効範囲: 120.5mmに修正 (実測値ベース)

**効果:**
- 数量検出精度: 50% → 57% → 64%
- 小さい1桁数字(1, 2, 9)新規検出成功

---

### 07:15: 数量列特化OCR処理の実装

**課題:**
- ML Kit日本語モデルは小さい1桁数字を「ノイズ」として落とす
- 優先度: 日本語 > 数字、大きい文字 > 小さい文字

**解決策: 2段階OCR処理**
1. **全体OCR（日本語）** - 行検出、商品名、金額、行タイプ
2. **数量列特化OCR（Latin + 4倍拡大）** - 数量のみ再処理

**実装内容:**
1. **数量列ROI切り出し** (OCRProcessor.kt:522-638)
   ```kotlin
   // 1. 数量列切り出し (X: 1134-1296px)
   // 2. 4倍アップスケール (162px → 648px)
   // 3. Latin OCR（数字に強い）
   // 4. Y座標で行にマッピング → 上書き
   ```

2. **正規化関数**
   ```kotlin
   fun normalizeQuantity(raw: String): Int? {
       return raw
           .replace("o", "0").replace("O", "0")
           .replace("l", "1").replace("I", "1")
           .toIntOrNull()
   }
   ```

3. **返品処理**
   ```kotlin
   if (row.itemName?.contains("返品") == true && qty > 0) {
       quantity = -qty
   }
   ```

**効果:**
- 初期実装: 50% → 57%
- 「50」「2920」「2850」検出・正規化成功

---

## 2025-12-23

### 23:35: 行クラスタリング最適化

**実施内容:**

1. **行クラスタリング閾値最適化**
   - 旧: 25px (広すぎて異なる行が混在)
   - 新: 15px (実測値ベース: 64.5mm/20行≈3.2mm/行)
   - 結果: 28行 → 32行分離

2. **列範囲の再定義**
   ```
   旧: IGNORE_RANGE: 992-1437px (数量も無視)

   新:
   - DATE_RANGE:      392-510px
   - ITEM_RANGE:      510-992px
   - STORE_RANGE:     992-1133px  (無視)
   - QUANTITY_RANGE:  1133-1295px (取得！)
   - UNITPRICE_RANGE: 1295-1437px (無視)
   - AMOUNT_RANGE:    1437-1611px
   - CATEGORY_RANGE:  1611-1786px
   ```

3. **ReceiptRowデータ構造拡張**
   ```kotlin
   data class ReceiptRow(
       val rowType: RowType,
       val date: String?,
       val itemName: String?,
       val quantity: String?,  // ← 新規追加
       val amount: Int?,
       val categorySum: Int?,
       val rawText: String?
   )
   ```

---

## 記録終了

最新の状況は `.clinerules` のトップセクションを参照してください。
