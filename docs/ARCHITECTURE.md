# 技術仕様書

## テクノロジースタック

| 分類 | 技術 | バージョン |
|---|---|---|
| 言語 | Kotlin | JVM 17 |
| UI フレームワーク | Jetpack Compose + Material3 | compose-bom:2023.10.01 |
| ナビゲーション | Navigation Compose | 2.7.5 |
| カメラ | CameraX (camera2) | 1.3.0 |
| 画像処理 | OpenCV for Android | 4.9.0 |
| OCR（日本語） | ML Kit text-recognition-japanese | 16.0.1 |
| OCR（ラテン文字） | ML Kit text-recognition | 16.0.1 |
| データベース | Room | 2.6.0 |
| DI / ビルドツール | KSP (Room コンパイラ用) | — |
| 非同期処理 | Kotlin Coroutines + Flow | 1.7.3 |
| JSON | Gson | 2.10.1 |
| ビルドシステム | Gradle (Kotlin DSL) | — |

---

## ビルド設定

```kotlin
// app/build.gradle.kts
namespace        = "com.example.greenframeocr"
applicationId    = "com.example.greenframeocr"
compileSdk       = 34
minSdk           = 24   // Android 7.0
targetSdk        = 34
versionCode      = 1
versionName      = "1.0"
jvmTarget        = "17"
kotlinCompilerExtensionVersion = "1.5.4"
```

---

## アーキテクチャ概要

```
+-------------------------------------------------------------+
|  UI Layer（Jetpack Compose）                                  |
|  18画面 / ViewModel                                          |
+-------------------------------------------------------------+
|  Domain / Util Layer                                         |
|  GreenFrameDetector / OCRProcessor / ProductNameCorrectorV3  |
|  UnderlyingBaseProcessor / CategoryRecalculator              |
|  ExplicitJoinMatcher / ValidationUtils                       |
+-------------------------------------------------------------+
|  Data Layer（Room Database v15）                              |
|  ReceiptDatabase / DAO x 14テーブル                          |
+-------------------------------------------------------------+
|  Hardware Layer                                              |
|  CameraX 4K -> OpenCV -> ML Kit                              |
+-------------------------------------------------------------+
```

### MVVM 構成

| クラス | 役割 |
|---|---|
| `CameraViewModel` | カメラ起動・フォーカス判定・GreenFrameDetector 呼び出し |
| `OcrCaptureViewModel` | OCR パイプライン全体の制御・DB 保存 |
| `SheetEditorViewModel` | 伝票個別編集・行追加削除・再 OCR |

---

## システムフロー

```
[CameraX ImageAnalysis 4K (3840x2160)]
         | YuvToRgbConverter
[RGB Bitmap]
         | GreenFrameDetector.process()
[緑枠検出 + 透視変換 → 3045×2220px]
         | OcrCaptureViewModel.processUnderlayingBase()
[ML Kit 全体 OCR + 数量列 OCR + 商品名列 OCR]
         | ProductNameCorrectorV3.correctProductName()
[商品名補正（スコアリング・学習）]
         | Room DB 保存
[ReceiptInputScreen / SheetEditorScreen で確認・修正]
         | OutputConfirmScreen
[らくらく CSV 出力]
```

---

## 主要コンポーネント詳細

### GreenFrameDetector
- `object GreenFrameDetector`（シングルトン）
- HSV 緑マスク → 輪郭検出 → 4 コーナー算出 → 透視変換
- **WARP_PX_PER_MM = 15.0**（出力: 3045×2220px・変更禁止）
- `debugMode=true` のとき Step7（行切り抜き）実行

### OCRProcessor
- ML Kit のラッパー
- 全体 OCR（日本語）・数量列 OCR（Latin・縦罫線除去）・商品名列 OCR（日本語）
- `cleanLeadingRuleNoise()`: 商品名先頭の `|` と日本語前の `I` を除去

### ProductNameCorrectorV3
- 3 層補正構造（LOCKED → CONFIRMED → AUTO）
- 100 点スコアリング（textSimilarity 60 + variantBonus 15 + 他）
- 失敗駆動の昇格・降格ロジック

### ExplicitJoinMatcher
- OCR 分離文字の結合パターン学習
- 例: `灯|油` → `灯油`（hitCount≥5 または manualConfirmCount≥2 で昇格）

### CategoryRecalculator
- 月全体の全伝票・全行を対象にカテゴリを再計算
- 小計行文字列マッチング（正規・誤認識パターン両対応）

---

## パフォーマンス計測結果（15px/mm・本番モード）

| ステップ | 時間 |
|---|---|
| Step1 緑マスク | 101ms |
| Step2 外側輪郭 | 46ms |
| Step3 中心枠検出 | 210ms |
| Step4 コーナー算出 | 446ms（4a+4b合計） |
| Step5 透視変換 | 48ms |
| Step6 中央枠検出 | 153ms |
| Step7 行切り抜き | スキップ（本番） |
| Step8 二値化 | 88ms |
| **GreenFrameDetector 合計** | **約1,225ms** |
| OCRProcessor 全体 | 約2,500ms |
| **全体合計** | **約3,700ms** |

ボトルネックは ML Kit（Step2 全体 OCR: 1,034ms、Step8.5 商品名列 OCR: 984ms）。

---

## データベース設計

- **DB 名**: `receipt_database`
- **バージョン**: 15
- **テーブル数**: 14
- **マイグレーション**: 1→2→...→15（全ステップ定義済み）
- `fallbackToDestructiveMigration()` は開発中のみ有効（本番リリース前に削除すること）

| テーブル | 用途 |
|---|---|
| `receipt_items` | 伝票明細行（商品・金額・カテゴリ） |
| `sheet_data` | 伝票単位の小計・合計 |
| `monthly_data` | 月次サマリー |
| `product_master` | 商品マスタ（正規名・確定フラグ） |
| `ocr_variants` | OCR 誤認識パターン学習（V3） |
| `yayoi_accounts` | 弥生会計 勘定科目マスタ |
| `rakuraku_accounts` | らくらく青色申告 勘定科目マスタ |
| `rakuraku_tekiyou` | 摘要辞書（購買・預金共用） |
| `correction_logs` | OCR 補正判定ログ |
| `ocr_score_logs` | OCR スコア詳細ログ |
| `deposit_meisai` | 通帳明細データ |
| `tekiyou_matching_rules` | 預金摘要マッチングルール |
| `ocr_fallback_logs` | OCR フォールバックログ |
| `ocr_explicit_joins` | 分離テキスト結合パターン学習 |

---

## 権限要件

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
```

---

## ビルド・デプロイ手順

```bash
# クリーンビルド
./gradlew clean assembleDebug

# 実機インストール
adb install -r app/build/outputs/apk/debug/app-debug.apk

# ログ確認（パフォーマンス）
adb logcat -s GreenFrameDetector:D OCRProcessor:D | grep PERF

# ログ確認（全体）
adb logcat -s GreenFrameDetector:D OCRProcessor:D CameraViewModel:D OcrCaptureViewModel:D
```

---

## 技術的制約

- ML Kit の日本語モデルはバンドル必須（オフライン動作のため assets に含める）
- OpenCV 4.9.0 は `org.opencv:opencv:4.9.0` の Maven 依存で取得（ネイティブライブラリ同梱）
- `Utils.bitmapToMat` は RGBA 4ch を返すため、OpenCV 処理前に必ず BGR 変換が必要
- ML Kit の最小文字高さ: 100px（40px 以下で精度急落）
- CameraX ImageAnalysis の 4K 解像度はデバイスによってサポート外の場合あり
- `WARP_PX_PER_MM = 15.0` は変更禁止（20px/mm は Step7 が 2.6 倍遅くなるため不採用済み）
- Room `fallbackToDestructiveMigration` は本番リリース前に削除すること
