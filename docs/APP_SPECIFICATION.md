# ReceiptOCR アプリケーション 詳細仕様書

> **⚠️ このドキュメントは古いプロトタイプ段階（ArUcoマーカー式台紙・アプリ名「Remoni」時代）の
> 記述であり、現行の設計（台紙なし・緑枠検出方式、アプリ名「JA仕訳変換」、Gemini Vision API
> によるJA伝票OCR）とは大きく乖離している。現行の仕様は `CLAUDE.md`・
> `docs/GreenFrameOCR_SPEC.md`・`docs/TASK_gemini_ocr_migration.md` を参照すること。
> Phase6（2026-08-11、ML Kit時代のJA伝票専用コード削除）に伴い、本ドキュメントが言及する
> `OcrCaptureScreen`・`SheetEditorScreen`・`OcrLearningStatusScreen`・`OCRProcessor`・
> `MultiScaleOcrProcessor`・`ProductNameCorrectorV2/V3`・`ExplicitJoinMatcher`は
> いずれも削除済み。**このファイル全体の内容は参考程度に留め、鵜呑みにしないこと。**

## 1. アプリの概要と目的

### アプリ基本情報
- **アプリ名**: Remoni（リモーニ）
- **パッケージ名**: com.example.receiptorc
- **バージョン**: 1.0
- **最小SDK**: 24（Android 7.0）
- **ターゲットSDK**: 34（Android 14）
- **ビルド言語**: Kotlin

### 主な目的と機能
このアプリは、農業経営における領収書・通帳の OCR デジタル化と会計自動化を実現するシステムです。

**主要機能**:
1. **購買部門** - 領収書の OCR 読取、商品名と金額の認識、買掛摘要への自動マッピング
2. **預金部門** - 通帳明細の自動取得、摘要パターンマッチング、科目の自動割当
3. **OCR学習システム** - 誤認識パターンの段階的学習と自動補正
4. **会計連携** - 弥生会計とらくらく青色申告（農業版）への出力

---

## 2. アーキテクチャ

### 使用ライブラリ・フレームワーク

**UI層**:
- Jetpack Compose（最新のAndroidモダンUI）
- Material3（デザインシステム）
- Compose Navigation（画面遷移）

**OCR・画像処理**:
- **ML Kit Text Recognition** - 日本語テキスト認識（v16.0.1）
- **OpenCV** - 画像処理・変換（v4.9.0）
  - ArUco マーカー検出（位置合わせ用）
  - 透視変換（領収書の台紙位置補正）
  - 画像前処理（二値化、ノイズ除去）

**カメラ機能**:
- CameraX（v1.3.0） - 4K解像度対応のモダンカメラAPI

**データ永続化**:
- **Room Database** - SQLiteORM（v2.6.0）
- SharedPreferences - アプリ設定管理

**非同期処理**:
- Kotlin Coroutines（v1.7.3）
- Flow（リアクティブストリーム）

**その他**:
- Gson（JSON処理、スコア内訳の保存）

### データベース構造

**エンティティ一覧（14テーブル）**:

| テーブル | 用途 | 主要フィールド |
|---------|------|--------------|
| `receipt_items` | 領収書行データ | issueYear, month, sheetNumber, productName, amount, category |
| `monthly_data` | 月ごとの集計 | issueYear, month, totalSheets, 分類別合計 |
| `sheet_data` | 伝票ごとのメタデータ | issueYear, month, sheetNumber, subtotals（3分類） |
| `product_master` | 商品マスタ辞書 | canonicalName, category, frequencyCount |
| `ocr_variants` | OCR誤認識学習データ | variantText, productId, confidenceLevel, hitCount, scores |
| `correction_logs` | 補正判定履歴 | rawText, topProduct, scores, decision |
| `ocr_score_logs` | スコア詳細ログ | rawOcrText, decision, 7種類のスコア成分 |
| `ocr_fallback_logs` | フォールバック発動ログ | rowIndex, rawText, textHeight, separatedTexts |
| `ocr_explicit_joins` | 文字分離結合パターン | normalizedPattern, joinedText, hitCount |
| `yayoi_accounts` | 弥生会計勘定科目 | accountName, code, searchKey, 分類 |
| `rakuraku_accounts` | らくらく青色申告科目 | accountName, code, searchKey, 分類 |
| `rakuraku_tekiyou` | らくらく摘要辞書 | mainCategory, subCategory, tekiyouName, taxRate |
| `tekiyou_matching_rules` | 摘要パターンマッチング | pattern, rakurakuTekiyouId, isRegex, isDeposit |
| `deposit_meisai` | 預金明細データ | transactionDate, tekiyou, amount, matchingRuleId |

**主要なDAO（Data Access Object）**（12個）:
- `ReceiptDao` - receipt_items, monthly_data, sheet_data（複数テーブル管理）
- `ProductMasterDao` - product_master
- `OcrVariantDao` - ocr_variants（昇格・降格ロジック含む）
- `OcrScoreLogDao` - ocr_score_logs
- `CorrectionLogDao` - correction_logs
- `OcrFallbackLogDao` - ocr_fallback_logs
- `OcrExplicitJoinDao` - ocr_explicit_joins
- `YayoiAccountDao` - yayoi_accounts
- `RakurakuAccountDao` - rakuraku_accounts
- `RakurakuTekiyouDao` - rakuraku_tekiyou
- `TekiyouMatchingRuleDao` - tekiyou_matching_rules
- `DepositMeisaiDao` - deposit_meisai

### 主要コンポーネント

**画面層（20個のComposable）**:
- MenuScreen - メインメニュー
- PurchaseMenuScreen - 購買部門メニュー
- DepositMenuScreen - 預金部門メニュー
- CameraScreen - カメラプレビュー
- OcrCaptureScreen - B/C/D ブロック個別撮影フロー
- ReceiptInputScreen - 伝票データ入力・編集
- SheetEditorScreen - 伝票個別編集
- OutputConfirmScreen - 出力前の確認（購買/預金共用）
- TekiyouMatchingScreen - 摘要パターンマッチング
- OcrLearningStatusScreen - 学習データの可視化
- ProductListScreen - 商品マスタ表示
- KaikakeTekiyouScreen - 買掛摘要辞書
- YokinTekiyouScreen - 預金摘要辞書
- RakurakuTekiyouScreen - らくらく摘要辞書
- PassbookDataScreen - 通帳CSV入力
- MonthlySummaryScreen - 月次サマリー
- SettingsScreen - アプリ設定
- AccountSettingsScreen - 科目設定
- UnderlayResultScreen - OCR結果表示
- TestScreen - テスト用

**ViewModels（4個）**:
- `CameraViewModel` - カメラ処理、OCR実行
- `OcrCaptureViewModel` - B/C ブロック撮影フロー制御
- `SheetEditorViewModel` - 伝票編集
- `TestViewModel` - テスト用

**ユーティリティ層**（15個）:
- `ImageProcessor` - Aruco検出、透視変換、セル抽出、列区切り検出
- `OCRProcessor` - ML Kitのラッパー、日本語・ラテン文字モデル切り替え
- `UnderlyingBaseProcessor` - B/C ブロックOCR、フォールバック処理
- `ProductNameCorrectorV3` - V3設計の補正システム（スコアリング、学習）
- `ProductNameCorrectorV2` - 旧版補正システム（互換性維持）
- `ProductNameCorrector` - 初期版補正システム
- `ExplicitJoinMatcher` - 分離文字の結合パターンマッチング
- `OcrQualityEvaluator` - 画像品質評価
- `OcrResultEvaluator` - OCR結果の信頼度評価
- `OcrResultMerger` - 複数OCR結果の統合
- `MultiScaleOcrProcessor` - マルチスケールOCR処理
- `CategoryRecalculator` - 分類別合計の再計算
- `YuvToRgbConverter` - カメラYUV→RGB変換
- `ImagePreprocessor` - 画像前処理（二値化等）
- `ValidationUtils` - 入力検証ユーティリティ

---

## 3. 主要な機能詳細

### 3.1 OCR機能フロー

**カメラ画像処理フロー**:
```
カメラ入力(YUV)
  ↓ YuvToRgbConverter
RGB Bitmap
  ↓ ImageProcessor.detectAruco()
Aruco 4個マーカー検出 (ID: 0,1,2,3)
  ↓ ImageProcessor.perspectiveTransform()
透視変換（A4台紙位置補正）
  ↓ ImageProcessor.extractBBlocks() / extractCBlocks()
B ブロック：取引日+商品名
C ブロック：税込金額+分類計
  ↓ ImageProcessor.detectColumnSeparator()
列区切り線検出（ハイブリッド方式）
  ↓ OCRProcessor.recognizeWholeBlock()
ブロック全体を2列OCR：
  - 左列: ラテン文字認識（数字・記号用）
  - 右列: 日本語認識（商品名用）
  ↓ UnderlyingBaseProcessor.processBBlock()
フォールバック（失敗時）
  → 全TextBoxをY座標グループ化
  → ExplicitJoinMatcher で分離文字を結合
  ↓ ProductNameCorrectorV3.correctProductName()
スコア算出・補正（3層構造）
```

### 3.2 OCR学習システム（V3設計）

**設計原則** - 低頻度利用（月1〜年1回）向け:
- ⚠ 人間の最終確定のみを真実とする
- ⚠ 時間減衰なし（固定的な知識を学習）
- ⚠ 失敗駆動の昇格・降格（失敗時は即座に無効化）

**信頼度レベル（ConfidenceLevel）**:
- `AUTO` - 自動学習（初期状態、補正に未使用）
- `CONFIRMED` - 確認済み（条件付きで補正に使用）
- `LOCKED` - 固定（絶対値、無条件で使用）

**補正の3層構造**:

| Layer | 対象 | 適用条件 |
|-------|------|---------|
| Layer 1 | LOCKED / 手動CONFIRMED | 無条件適用 |
| Layer 2 | 自動CONFIRMED | スコア検証後（≥75.0 + 差分≥10） |
| Layer 3 | AUTO | 補正未使用（学習素材のみ） |

**昇格条件**:

`AUTO → CONFIRMED`:
- **自動学習由来**: hitCount≥3 AND avgScore≥0.90 AND highScoreHits≥2 AND noFailures
- **手動修正由来**: manualCorrectCount≥2（異なるバッチで）

`CONFIRMED → LOCKED`:
- hitCount≥10 AND avgScore≥0.92 AND noFailures

**降格・無効化**:
- `AUTO`: autoFailCount≥1 → 無効化（DISABLE）
- `CONFIRMED`: autoFailCount≥1 → AUTO 降格
- `LOCKED`: 手動解除のみ

### 3.3 スコアリングシステム（100点満点）

**スコア成分**:
| 成分 | 最大値 | 説明 |
|------|-------|------|
| textSimilarity | 60 | 文字類似度（編集距離ベース） |
| prefixBonus | 10 | 先頭欠落補正ボーナス |
| dakutenBonus | 5 | 濁点誤認識補正ボーナス |
| variantBonus | 15 | 既存学習パターンボーナス |
| historyBonus | 10 | 手動修正履歴ボーナス |
| riskPenalty | -30 | リスク検出ペナルティ |

**判定閾値**:
- マスタ直接マッチ: 総合≥78 AND 2位との差≥12
- CONFIRMED知識: 総合≥75 AND 2位との差≥10
- 学習登録: 総合≥88 AND 差≥12 AND 文字数≥3

### 3.4 データ処理フロー（購買部門）

```
CSV/通帳インポート → 摘要パターンマッチング
        ↓
  月別データ読み込み
        ↓
OCR撮影 (B→C→D ブロック個別) → OcrCaptureScreen
        ↓
補正・学習ロジック → ProductNameCorrectorV3
        ↓
伝票入力画面 (ReceiptInputScreen)
  ├─ 表示: 年月選択 + 伝票選択 + グリッド表示
  ├─ 編集: セル個別編集、行追加・削除
  ├─ 再OCR: 金額など特定セルのみ上書き対象マーク
  └─ 保存: 全変更をDB に commit
        ↓
出力確認画面 (OutputConfirmScreen - 購買)
  ├─ 日付 + 買掛摘要 + メモ（商品名） + 金額
  ├─ チェックボックスで出力対象選択
  └─ CSV/PDF 出力（弥生会計用フォーマット）
```

### 3.5 テキスト分離・結合（ExplicitJoinMatcher）

**背景**: OCRが「灯」「油」と分離認識した場合、「灯油」に結合

**パターン学習**:
- 正規化パターン: `灯|油` ("|" で分離表現)
- 結合テキスト: `灯油`
- 信頼度レベル: AUTO → CONFIRMED → LOCKED
- 昇格条件: hitCount≥5 OR manualConfirmCount≥2

---

## 4. データモデル詳細

### 4.1 主要エンティティ

#### ReceiptItem
```kotlin
data class ReceiptItem(
    issueYear, issueMonth, sheetNumber, itemNumber,
    receiptYear, receiptMonth, receiptDay,
    productName, amount, category,
    isOcrOverwriteTarget  // 再OCR上書き対象フラグ
)
```

#### OcrVariant（学習データ）
```kotlin
data class OcrVariant(
    productId, variantText,
    normalizedText,  // 濁点分離、記号除去後
    confidenceLevel, // AUTO / CONFIRMED / LOCKED
    hitCount, highScoreHits,
    avgFinalScore, totalScore,
    firstSeenAt, lastSeenAt, uniqueDays,
    source,  // AUTO / USER / IMPORT
    isDisabled, disabledReason,
    manualCorrectCount, autoFailCount,
    lastManualCommitBatchId  // 重複カウント防止
)
```

#### OcrScoreLog（スコア詳細）
```kotlin
data class OcrScoreLog(
    rawOcrText, candidateProductId,
    decision,  // AUTO / NEED_CONFIRM / NO_MATCH
    totalScore, textSimilarity,
    prefixBonus, dakutenBonus, variantBonus, historyBonus,
    riskPenalty, gapToSecond,
    manualOverride, manualCorrectedProductId,
    commitBatchId, createdAt
)
```

#### ProductMaster（商品辞書）
```kotlin
data class ProductMaster(
    canonicalName,  // 正規化商品名
    category,  // 一般購買 / 給油所 / 農業機械
    frequencyCount,  // 使用頻度（優先マッチング用）
    kaikakeTekiyouId  // 買掛摘要辞書ID
)
```

#### OcrExplicitJoin（分離文字結合）
```kotlin
data class OcrExplicitJoin(
    productId,
    normalizedPattern,  // "灯|油"
    joinedText,  // "灯油"
    originalTexts,  // ["灯", "油"] (JSON)
    confidenceLevel, hitCount, manualConfirmCount,
    source, isDisabled,
    firstSeenAt, lastSeenAt
)
```

### 4.2 会計連携エンティティ

#### YayoiAccount / RakurakuAccount
```kotlin
data class YayoiAccount(
    accountName,  // 例: "種苗費"
    searchKeyAlpha,  // 例: "SHUBYOU"
    accountCode,  // 例: "602"
    debitCredit,  // 借 / 貸
    categoryA/B/C,  // 分類階層
    usedForPurchase, usedForDeposit,
    parentId  // 階層構造用
)
```

#### RakurakuTekiyou（摘要辞書）
```kotlin
data class RakurakuTekiyou(
    mainCategory,  // 現金 / 預金 / 売掛 / 買掛
    subCategory,  // 入金 / 出金 / 販売 / 購入
    tekiyouName,  // 例: "商品仕入"
    searchKey, kamoku,
    taxRate,  // 8% / 10% / 非 / 不 / 空
    businessRatio, isShared, isEnabled
)
```

#### TekiyouMatchingRule
```kotlin
data class TekiyouMatchingRule(
    pattern,  // マッチング用パターン（正規表現可）
    normalizedTekiyou,  // 正規化摘要名
    isRegex, rakurakuTekiyouId,
    sampleText, matchCount,
    isDeposit  // true: 入金, false: 出金
)
```

---

## 5. 設定とカスタマイズ

### 5.1 アプリ設定（AppPreferences）

SharedPreferences で管理：

| 設定項目 | キー | デフォルト | 説明 |
|---------|------|-----------|------|
| 年号 | era_year | 7 | 令和7年 |
| 現在月 | current_issue_month | 1 | OCR撮影時の月 |
| 年月固定 | fix_year_month | false | 年月を固定するか |
| カメラ解像度 | camera_resolution | 3840x2160 | 4K固定 |
| カメラプレビュー | camera_preview | false | プレビュー表示 |
| フラッシュ自動点灯 | camera_flash | false | 暗時自動点灯 |
| 預金金額表示 | deposit_hide_amount | false | 金額を非表示 |

### 5.2 カメラ解像度

enum class CameraResolution:
- HD (1280x720)
- Full HD (1920x1080)
- QHD (2560x1440)
- **4K UHD (3840x2160)** ← デフォルト固定

### 5.3 画像処理パラメータ

**ImageProcessor 定数**:
```kotlin
MARKER_SIZE_MM = 25.0
WARP_OUTPUT_WIDTH = 2400, HEIGHT = 1700
WARP_SCALE_PX_PER_MM = 8.1

// ブロック列幅（mm）
B_BLOCK_COL1_WIDTH_MM = 14.5  // 取引日列
C_BLOCK_COL1_WIDTH_MM = 21.0  // 税込金額列

NUM_ROWS = 20  // 固定20行
CELL_PADDING_PX = 3  // 罫線除外用パディング
```

### 5.4 コンパイルオプション

**build.gradle.kts**:
```kotlin
compileSdk = 34
minSdk = 24
targetSdk = 34
jvmTarget = "17"
kotlinCompilerExtensionVersion = "1.5.4"
```

---

## 6. ナビゲーション・画面遷移

**ナビゲーションスタック**:

```
Menu (メインメニュー)
├─ PurchaseMenu (購買部門メニュー)
│  ├─ OcrCapture (OCR撮影)
│  │  └─ SheetEditor (伝票個別編集)
│  ├─ ReceiptInput (伝票入力・編集)
│  ├─ ProductList (商品マスタ表示)
│  ├─ KaikakeTekiyou (買掛摘要辞書)
│  └─ PurchaseOutputConfirm (出力確認)
│
├─ DepositMenu (預金部門メニュー)
│  ├─ PassbookData (通帳CSV入力)
│  ├─ TekiyouMatching (摘要マッチング)
│  ├─ YokinTekiyou (預金摘要辞書)
│  └─ DepositOutputConfirm (出力確認)
│     # 預金明細はOutputConfirmScreenで表示
│
├─ Settings (設定)
│  ├─ AccountSettings (科目設定)
│  ├─ RakurakuTekiyou (らくらく摘要)
│  └─ ... その他設定
│
├─ MonthlySummary (月単位サマリー)
└─ OcrLearningStatus (学習データ可視化)
```

---

## 7. 初期データ（Assets）

**CSV ファイル**（assets/ 配下）:

| ファイル | 用途 | 行数(目安) |
|---------|------|----------|
| yayoi_accounts.csv | 弥生会計勘定科目マスタ | 数百 |
| rakuraku_accounts.csv | らくらく青色申告科目マスタ | 数百 |
| product_master.csv | 商品マスタ辞書 | 数千 |
| ocr_variants.csv | OCR誤認識初期パターン | 数千 |
| rakurakutekiyou.csv | らくらく摘要辞書 | 数百 |
| receipts_2025_01-11.csv | 領収書サンプル | — |

**初期化フロー** (DatabaseInitializer):
1. アプリ初回起動時チェック
2. 勘定科目が空 → CSV インポート
3. 商品マスタが空 → CSV インポート
4. OCR誤認識パターンが空 → CSV インポート

---

## 8. 権限要件

**AndroidManifest.xml**:
- `CAMERA` - カメラ撮影（required: false）
- `READ_EXTERNAL_STORAGE` - 外部ストレージ読取（maxSdk: 32）
- `WRITE_EXTERNAL_STORAGE` - ストレージ書込（maxSdk: 32、ScopedStorage対応）
- `READ_MEDIA_IMAGES` - 画像メディアアクセス

**インテント フィルター**:
- CSV ファイルの受け取り（SEND）
- CSV ファイルの直接打開（VIEW）

---

## 9. 依存関係要約

```
androidx.core:core-ktx:1.12.0
androidx.lifecycle:lifecycle-runtime-ktx:2.6.2
androidx.compose:compose-bom:2023.10.01
androidx.navigation:navigation-compose:2.7.5
com.google.mlkit:text-recognition:16.0.1
com.google.mlkit:text-recognition-japanese:16.0.1
org.opencv:opencv:4.9.0
androidx.camera:camera-camera2:1.3.0
androidx.camera:camera-lifecycle:1.3.0
androidx.camera:camera-view:1.3.0
androidx.room:room-runtime:2.6.0
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3
com.google.code.gson:gson:2.10.1
```

---

## 10. プロジェクト統計

- **Kotlin ファイル数**: 70+
  - UI スクリーン: 20
  - データモデル・DAO: 31
  - ユーティリティ: 15
  - ViewModel: 4
  - ナビゲーション: 1

- **データベース**:
  - テーブル数: 14
  - マイグレーション: 13段階（version 1 → 13）
  - DAO インターフェース: 12（ReceiptDaoが複数テーブルを管理）

- **リソース**:
  - Drawable: 最小限
  - レイアウト: 全て Compose（XML なし）
  - 文字列: 3項目（最小限）

---

## 11. 主な技術的特徴

### 11.1 高精度な位置検出
- ArUco マーカー 4個の 3D 位置推定
- 透視変換による台紙補正
- mm → pixel 変換による正確なセル抽出

### 11.2 ハイブリッド OCR
- B ブロック（商品名）: 日本語認識モデル
- C ブロック（金額）: ラテン文字認識モデル
- 列ごと全体 OCR + Y座標マッチング

### 11.3 段階的学習システム
- 失敗駆動の信頼度管理
- 低頻度利用向けの固定的知識
- 手動修正履歴の活用

### 11.4 フォールバック処理
- 商品名列 OCR 失敗時、全TextBox で分離認識
- 分離パターン学習（例: 「灯」「油」→「灯油」）
- テキスト高さ解析で異常検知

### 11.5 多層補正
- Layer 1: 確定知識（LOCKED / 手動CONFIRMED）
- Layer 2: 条件付き知識（自動CONFIRMED + スコア検証）
- Layer 3: 観測データ（AUTO: 補正未使用）

---

## 12. 今後の拡張可能性

現在の実装は以下の点で拡張可能に設計:

1. **学習精度向上** - V3設計で、さらなる統計的な昇降格ロジックを追加可能
2. **追加会計システム連携** - FreeeやMFクラウド対応
3. **OCR多言語対応** - ML Kit は英語、中国語など対応
4. **バッチ処理** - 複数伝票の一括処理効率化
5. **クラウド連携** - 学習データの共有・同期
6. **AI 補正** - Deep Learning ベースのテキスト正規化

---

**最終更新**: 2026年2月21日

このアプリケーションは、**農業経営者向けの実用的な会計デジタル化ツール**として、高度な OCR 技術と会計システム連携を組み合わせた、非常に専門的な設計となっています。
