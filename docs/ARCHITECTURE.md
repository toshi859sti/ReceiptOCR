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
+-------------------------------------------------------------+
|  Domain / Util Layer                                         |
|  GreenFrameDetector / GeminiReceiptClient / JaSheetOcrMapper |
|  UnderlyingBaseProcessor / CategoryRecalculator / Validation |
+-------------------------------------------------------------+
|  Data Layer（Room Database v28）                              |
|  ReceiptDatabase / DAO                                       |
+-------------------------------------------------------------+
|  Hardware / External API Layer                               |
|  CameraX 4K -> OpenCV -> Gemini Vision API                   |
+-------------------------------------------------------------+
```

（画面数・DAO数の正確なカウントは`docs/repository-structure.md`参照。この図は層構造の
概略のみを示す）

### MVVM 構成

| クラス | 役割 |
|---|---|
| `CameraViewModel` | カメラ起動・フォーカス判定・GreenFrameDetector 呼び出し |

JA伝票の撮影・OCR結果確認・編集・保存は`viewmodel`を介さず`ui/ReceiptInputScreen.kt`が
単体で完結する（独自の`CameraView`・`ReceiptRowData`・`saveMonthData()`）。
`OcrCaptureViewModel`・`SheetEditorViewModel`はPhase6（2026-08-11）で削除済み。

---

## システムフロー（JA伝票、Gemini Vision API移行後）

```
[CameraX ImageAnalysis 4K (3840x2160)]
         | YuvToRgbConverter
[RGB Bitmap]
         | GreenFrameDetector.process()
[緑枠検出 + 透視変換 → 3045×2220px（dewarpedBitmap）]
         | GeminiReceiptClient.parseJaSheetFromImage()
[列クロップTwo-Pass：全体OCR + 取引日列OCR（並列実行・指数バックオフリトライ）]
         | JaSheetOcrMapper.mapGeminiResultToParsedRows() / applyProductMasterCorrection()
[カテゴリ仮判定 + 商品マスタ照合（canonicalKey）]
         | ReceiptInputScreen.saveMonthData()
[検算バリデーション・要確認バッジ・小計カテゴリ重複ガードを経てRoom DB保存]
         | OutputConfirmScreen
[らくらく／弥生 CSV 出力（productMasterId経由のFKルックアップ）]
```

一般レシート（`GeneralReceiptCaptureScreen.kt`）は本フローとは独立しており、
引き続きML Kit（テキスト抽出）+ オンライン時Gemini（構造化）のハイブリッド方式を使う。

---

## 主要コンポーネント詳細

### GreenFrameDetector
- `object GreenFrameDetector`（シングルトン）
- HSV 緑マスク → 輪郭検出 → 4 コーナー算出 → 透視変換
- **WARP_PX_PER_MM = 15.0**（出力: 3045×2220px・変更禁止）
- `debugMode=true` のとき Step7（行切り抜き）実行

### GeminiReceiptClient
- Gemini Vision API（`gemini-3.5-flash-lite`採用）のラッパー
- `parseJaSheetFromImage()`: 列クロップTwo-Pass方式（全体画像＋取引日列単独クロップを並列送信、
  NORMAL行数ベースで再アラインメント）
- `parseJaSheetPartial()`: Phase5の部分再OCR（選択セルの行範囲のみクロップして再送信、
  行数不一致時は自動的に伝票全体再送信へフォールバック）
- 429/500/503/通信エラーに対する指数バックオフリトライ（`callWithRetry()`）

### JaSheetOcrMapper
- Gemini OCR結果 → DB保存用`ParsedRow`への変換（`mapGeminiResultToParsedRows()`）
- 商品マスタとの`canonicalKey`照合による表記統一・`productMasterId`紐づけ
  （`applyProductMasterCorrection()`）
- 小計行の区分名からカテゴリを仮判定（`assignJaSheetCategories()`、最終確定は保存後の
  CategoryRecalculatorが担う）

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

（`OCRProcessor`（ML Kit）行はPhase6で削除済み。Gemini移行後は`GreenFrameDetector`の後段は
Gemini Vision APIへのネットワーク呼び出しになり、`gemini-3.5-flash-lite`で約4秒/回
（`docs/TASK_gemini_ocr_migration.md`のPhase0実測値）。ローカル処理と異なり通信状況に
左右されるため、単純な合算値としては扱わない）

---

## データベース設計

- **DB 名**: `receipt_database`
- **バージョン**: 28
- **マイグレーション**: 1→2→...→28（全ステップ定義済み、`fallbackToDestructiveMigration()`は
  2026-07-12に削除済み。以後マイグレーション必須）

| テーブル | 用途 |
|---|---|
| `receipt_items` | 伝票明細行（商品・金額・カテゴリ） |
| `sheet_data` | 伝票単位の小計・合計 |
| `monthly_data` | 月次サマリー |
| `product_master` | 商品マスタ（正規名・確定フラグ） |
| `ocr_variants` | OCR 誤認識パターン学習（Gemini経路の商品名照合・CSV出力FKフォールバックで現役） |
| `yayoi_accounts` | 弥生会計 勘定科目マスタ |
| `rakuraku_accounts` | らくらく青色申告 勘定科目マスタ |
| `rakuraku_tekiyou` | 摘要辞書（購買・預金共用） |
| `deposit_meisai` | 通帳明細データ |
| `tekiyou_matching_rules` | 預金摘要マッチングルール |
| `ocr_fallback_logs` | OCR フォールバックログ |
| `general_receipts` / `general_receipt_items` | 一般レシート（ML Kit + Gemini） |
| `invoice_stores` | 登録番号・店舗マスタ |

（`correction_logs`・`ocr_score_logs`・`ocr_explicit_joins`はPhase6（v27→v28、
`MIGRATION_27_28`）でDROP済み）

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
adb logcat -s GreenFrameDetector:D | grep PERF

# ログ確認（全体）
adb logcat -s GreenFrameDetector:D GeminiReceiptClient:D CameraViewModel:D ReceiptInputScreen:D
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
