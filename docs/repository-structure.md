# リポジトリ構造定義書

## フォルダ構成

```
GreenFrameOCR/
├── CLAUDE.md                        ← プロジェクトメモリ（Claude Code 自動読み込み）
├── CURRENT_TASK.md                  ← 現在の作業・進捗（セッション起点）
├── PROJECT_TEMPLATE.md              ← ドキュメント生成テンプレート
├── docs/                            ← 永続的設計書
│   ├── product-requirements.md      プロダクト要求定義
│   ├── functional-design.md         機能設計書（画面遷移・ER図・OCRフロー）
│   ├── architecture.md              技術仕様書
│   ├── repository-structure.md      このファイル
│   ├── development-guidelines.md    開発ガイドライン
│   ├── glossary.md                  用語定義
│   └── known-issues.md              既知バグ・制約・技術的負債
├── .steering/                       ← 完了済み作業アーカイブ
│   └── YYYYMMDD-タイトル/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/                  ← 初期データCSV（商品/勘定科目/摘要マスタ）・アイコン素材
│       └── java/com/example/greenframeocr/
│           ├── MainActivity.kt
│           ├── ReceiptOCRApplication.kt
│           ├── data/                ← Room エンティティ・DAO・DB
│           ├── navigation/          ← NavGraph
│           ├── ui/                  ← Compose 画面
│           ├── ui/theme/            ← テーマ・カラー
│           ├── util/                ← ドメインロジック
│           └── viewmodel/           ← ViewModel
├── build.gradle.kts
└── settings.gradle.kts
```

---

## ディレクトリの役割

### `data/`

Room データベース関連クラスを配置する。

| ファイル | 役割 |
|---|---|
| `ReceiptDatabase.kt` | `@Database` 定義・マイグレーション・シングルトン |
| `ReceiptItem.kt` | 伝票明細行エンティティ |
| `SheetData.kt` | 伝票単位サマリーエンティティ |
| `MonthlyData.kt` | 月次サマリーエンティティ |
| `ProductMaster.kt` | 商品マスタエンティティ |
| `OcrVariant.kt` | OCR バリアント学習エンティティ（Gemini経路の商品名照合・CSV出力FKフォールバックで現役） |
| `YayoiAccount.kt` | 弥生会計 勘定科目エンティティ |
| `DepositMeisai.kt` | 通帳明細エンティティ |
| `TekiyouMatchingRule.kt` | 預金摘要マッチングルールエンティティ |
| `OcrFallbackLog.kt` | OCR フォールバックログエンティティ |
| `AppPreferences.kt` | SharedPreferences ラッパー |
| `DatabaseInitializer.kt` | 初回起動時の初期データ投入 |
| `*Dao.kt` | 各テーブルの DAO インターフェース |

### `navigation/`

| ファイル | 役割 |
|---|---|
| `Navigation.kt` | NavHost・全 route 定義・画面遷移グラフ |

### `ui/`

Jetpack Compose の画面ファイル。1ファイル1画面が原則。

| ファイル | 画面名 | route |
|---|---|---|
| `MenuScreen.kt` | メインメニュー | `menu` |
| `PurchaseMenuScreen.kt` | 購買メニュー | `purchase_menu` |
| `DepositMenuScreen.kt` | 預金メニュー | `deposit_menu` |
| `ReceiptInputScreen.kt` | 購買リスト・撮影・編集（主導線） | `receipt_input` |
| `CameraScreen.kt` | カメラプレビュー（共通・トーチ制御含む） | — |
| `CameraScreenForOcr.kt` | カメラ撮影ラッパー・OCR失敗画面（`ReceiptInputScreen`から利用） | — |
| `MonthlySummaryScreen.kt` | 月次サマリー | `monthly_summary/{year}/{month}` |
| `ProductListScreen.kt` | 商品マスタリスト | `product_list` |
| `OutputConfirmScreen.kt` | CSV 出力確認（購買・預金共用） | `purchase_output_confirm` / `deposit_output_confirm` |
| `PassbookDataScreen.kt` | 通帳データ入力 | `passbook_data` |
| `TekiyouMatchingScreen.kt` | 摘要マッチング設定 | `tekiyou_matching` |
| `SettingsScreen.kt` | 設定・データ管理 | `settings` |
| `AccountSettingsScreen.kt` | 勘定科目設定 | **Navigation 未接続** |

### `util/`

ドメインロジック・画像処理・OCR 処理を配置する。ViewModel や UI に依存させない。

| ファイル | 役割 |
|---|---|
| `GreenFrameDetector.kt` | 緑枠検出・透視変換（OpenCV） |
| `GeminiReceiptClient.kt` | Gemini Vision API 呼び出し（JA伝票OCR・列クロップTwo-Pass・部分再OCR） |
| `JaSheetOcrMapper.kt` | Gemini OCR結果→DB保存用行データへの変換・商品マスタ照合 |
| `UnderlyingBaseProcessor.kt` | 行分割・列検出・OCR パイプライン |
| `CategoryRecalculator.kt` | カテゴリ再計算 |
| `ImagePreprocessor.kt` | 画像前処理ユーティリティ |
| `OcrQualityEvaluator.kt` | OCR 結果品質評価 |
| `Category.kt` | JA購買の区分名（一般購買・給油所・農業機械・未分類） |
| `YuvToRgbConverter.kt` | CameraX YUV→RGB 変換 |

### `viewmodel/`

| ファイル | 役割 |
|---|---|
| `CameraViewModel.kt` | カメラ・GreenFrameDetector 制御 |

---

## ファイル配置ルール

- 画面ファイル: `ui/` に配置。画面名 + `Screen.kt`
- エンティティ: `data/` に配置。テーブル名（キャメルケース）+ `.kt`
- DAO: `data/` に配置。エンティティ名 + `Dao.kt`
- ドメインロジック: `util/` に配置。UI・ViewModel に依存させない
- ViewModel: `viewmodel/` に配置。画面に1対1対応
- テスト: `app/src/test/` および `app/src/androidTest/`（現在ほぼ未実装）
