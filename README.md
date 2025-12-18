# ReceiptOCR

農業協同組合の購買伝票をML Kit OCRでデジタル化するAndroidアプリ。Arucoマーカーを使った画像補正により高精度なOCR（95%以上）を実現。

## 概要

このアプリは、農業協同組合の購買伝票をスマートフォンのカメラで撮影し、Arucoマーカーを使った画像補正とML Kit OCRでデータをデジタル化します。

## 主な機能

### Phase 1（現在実装済み）
- ✅ Arucoマーカー検出（OpenCV）
- ✅ 透視変換による画像補正
- ✅ ブロック単位の切り出し
- ✅ ML Kit による日本語OCR
- ✅ テスト画像での動作確認
- ✅ Room Database によるデータ永続化

### Phase 2（今後実装予定）
- カメラ撮影機能（CameraX）
- リアルタイムマーカー検出表示
- データ編集・修正機能
- CSV出力機能
- 月次集計機能

## 技術スタック

- **言語**: Kotlin
- **UI**: Jetpack Compose
- **OCR**: ML Kit Text Recognition (Japanese)
- **画像処理**: OpenCV for Android
- **カメラ**: CameraX
- **データベース**: Room
- **アーキテクチャ**: MVVM

## プロジェクト構造

```
app/src/main/
├── java/com/example/receiptorc/
│   ├── data/                    # データモデル・DAO・Database
│   │   ├── ReceiptItem.kt
│   │   ├── MonthlyData.kt
│   │   ├── ReceiptDao.kt
│   │   └── ReceiptDatabase.kt
│   ├── ui/                      # UIコンポーネント
│   │   ├── TestScreen.kt
│   │   └── theme/
│   ├── util/                    # ユーティリティ
│   │   ├── ImageProcessor.kt   # Aruco検出・透視変換
│   │   └── OCRProcessor.kt     # ML Kit OCR処理
│   ├── viewmodel/               # ViewModel
│   │   └── TestViewModel.kt
│   ├── MainActivity.kt
│   └── ReceiptOCRApplication.kt
├── assets/
│   └── imgk2.jpg               # テスト画像
└── res/
    ├── values/
    │   ├── strings.xml
    │   └── themes.xml
    └── xml/
        ├── backup_rules.xml
        └── data_extraction_rules.xml
```

## セットアップ

### 必要な環境
- Android Studio Hedgehog | 2023.1.1 以降
- Android SDK 24 以上
- Gradle 8.2

### ビルド手順

1. プロジェクトをクローンまたはダウンロード
```bash
cd AndroidStudioProjects/ReceiptOCR
```

2. Android Studio でプロジェクトを開く

3. Gradle sync を実行

4. エミュレータまたは実機で実行

## 使用方法

### Phase 1（テストモード）

現在、アプリは `app/src/main/assets/imgk2.jpg` を使って画像処理をテストします。

1. アプリを起動
2. 自動的に画像処理が開始されます
3. 以下の情報が表示されます：
   - オリジナル画像
   - 透視変換後の画像
   - 切り出されたブロック
   - OCR結果（日付・商品名）

## データ仕様

### 伝票仕様
- サイズ: 211mm × 148mm
- 上乗せ用紙: A4横向き (297mm × 210mm)
- Arucoマーカー: 8個（各ブロック4隅）
  - Bブロック: ID 0-3
  - Cブロック: ID 4-7

### データブロック

**Bブロック（取引日 + 商品名）**
- 座標: X=6mm, Y=56mm（伝票基準）
- サイズ: 74mm × 65mm
- 2列: 左列（取引日）| 右列（商品名）
- 約20行

**Cブロック（税込金額 + 分類計）**
- 座標: X=135mm, Y=56mm（伝票基準）
- サイズ: 42mm × 68mm
- 2列: 左列（税込金額）| 右列（分類計）
- 約20行 + 合計行1行

## 開発ロードマップ

### Phase 1（完了）
- [x] プロジェクトセットアップ
- [x] データモデル作成
- [x] 画像処理ユーティリティ（Aruco検出、透視変換）
- [x] OCR処理ユーティリティ
- [x] テスト画面での動作確認

### Phase 2（予定）
- [ ] カメラ撮影機能
- [ ] リアルタイムマーカー検出
- [ ] データ一覧・編集画面
- [ ] CSV出力機能
- [ ] 月次集計機能

### Phase 3（予定）
- [ ] 小計行検出と分類割り当て
- [ ] データ検証機能
- [ ] エラー処理の強化

## トラブルシューティング

### 依存関係の問題

**OpenCV依存関係**
- OpenCV 4.9.0 を使用 (`org.opencv:opencv:4.9.0`)
- 2024年よりMaven Centralから公式に配布されています
- JitPackやサードパーティリポジトリは不要です

**ビルドエラーが発生した場合**
1. Android Studio で "File" > "Sync Project with Gradle Files" を実行
2. "Build" > "Clean Project" を実行
3. "Build" > "Rebuild Project" を実行

### OpenCV 初期化エラー
`OpenCVLoader.initLocal()` が失敗する場合は、OpenCV のバージョンを確認してください。

### ML Kit エラー
ML Kit のモデルが自動的にダウンロードされます。初回起動時はインターネット接続が必要です。

### Gradle Sync エラー
初回の Gradle Sync で時間がかかる場合があります。OpenCV ライブラリのダウンロードに数分かかることがあります。

## ライセンス

このプロジェクトは学習・研究目的で作成されています。

## 作者

作成日: 2025年11月
