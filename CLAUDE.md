# CLAUDE.md — JA仕訳変換 プロジェクトメモリ

## プロジェクト概要

農業経営者向けの会計デジタル化 Android アプリ。
島原雲仙農業協同組合の購買代金請求明細書（A5横・3辺のみ緑枠・右辺なし）を
台紙（ArUco マーカー）なしで直接 OCR し、
弥生会計・らくらく青色申告（農業版）への仕訳 CSV を自動生成する。

- **アプリ名**: JA仕訳変換
- **パッケージ**: `com.example.greenframeocr`
- **minSdk**: 24 / **targetSdk**: 34 / **Kotlin JVM**: 17
- **ビルド状態**: BUILD SUCCESSFUL（2026-09-22）

---

## セッション開始時の必須手順

1. `CURRENT_TASK.md` を読む（存在しなければ下記ひな形で新規作成）
2. 作業に関連する `docs/` 内のファイルを読む
3. 必要であれば `.steering/` の過去作業を参照する
4. 現状を簡潔に報告してから作業を開始する

---

## ドキュメント構成

```
GreenFrameOCR/
├── CLAUDE.md                    ← このファイル（自動読み込み）
├── CURRENT_TASK.md              ← 今やっていること（常に最新・セッション起点）
├── docs/
│   ├── product-requirements.md  プロダクト要求定義
│   ├── functional-design.md     機能設計書
│   ├── architecture.md          技術仕様書
│   ├── repository-structure.md  リポジトリ構造
│   ├── development-guidelines.md 開発ガイドライン
│   ├── glossary.md              用語定義
│   ├── known-issues.md          既知バグ・制約・技術的負債
│   ├── PC_ACCOUNTING_INTEGRATION_SPEC.md  PC会計アプリ向けの連携仕様（Android側の出力仕様）
│   ├── integration/             PC会計アプリ(AoiroChobo)との契約一式と往復の返信
│   └── （上記以外にも仕様書・作業メモが多数ある。`ls docs/` で確認すること）
└── .steering/                   ← 完了済み作業のアーカイブ
    └── YYYYMMDD-タイトル/
```

---

## CURRENT_TASK.md のひな形

新しい作業を開始するとき、または `CURRENT_TASK.md` が存在しないときはこの形式で作成すること。

```markdown
# CURRENT_TASK.md

## 作業タイトル
（例：OCR精度改善、DB設計追加、バグ修正など）

## 目的・背景
なぜこの作業をするのか。

## 今回のタスク
- [ ] タスク1
- [ ] タスク2

## 完了条件
この作業が「完了」とみなせる状態を定義する。

## 進捗メモ
（作業中に気づいたこと、決定事項、変更点などを随時記録）

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
-

### 未完了・中断した理由
（完了した場合は「なし」と記入）

### 次回セッションで最初にやること
（具体的に1行で記入）

### 新たに発覚した問題・制約
（あれば docs/known-issues.md にも転記すること）
```

---

## 作業完了時・セッション終了時の手順

1. `CURRENT_TASK.md` の「作業終了時の記録」をすべて埋める
2. 新たに発覚した問題・制約があれば `docs/known-issues.md` に転記する
3. 作業が完全に完了した場合は `CURRENT_TASK.md` を `.steering/YYYYMMDD-タイトル/` にコピーしてアーカイブする
4. 設計変更があれば該当する `docs/` ファイルを更新する
5. Git コミットする

---

## 重要な技術ルール（必ず守ること）

### RGBA→BGR 変換（必須）
`Utils.bitmapToMat` は RGBA 4ch を返す。OpenCV 処理前に必ず BGR 変換すること。

```kotlin
// RGBA → BGR（OpenCV 処理前）
Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
```

OpenCV で加工した Mat を Bitmap に戻して Gemini に渡すときは、**必ず BGR→RGBA に戻してから**
`Utils.matToBitmap` を呼ぶこと。逆順のまま渡すと色チャンネルが反転し OCR 精度に悪影響が出る。

```kotlin
// BGR → RGBA（matToBitmap の前）
Imgproc.cvtColor(bgrMat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
Utils.matToBitmap(rgbaMat, bitmap)
// → GeminiReceiptClient.parseJaSheetFromImage(bitmap, apiKey) へ渡す
```

`toBitmap()` 拡張関数は BGR→RGBA 変換を内包しているため、src は常に BGR 3ch のまま扱う。

### GreenFrameDetector の debugMode
```kotlin
fun process(inputBitmap: Bitmap, debugMode: Boolean = false, sharpness: Double = 0.0): DetectionResult
```
- 本番（`debugMode=false`）: Step7（行切り抜き）スキップ → 約1,225ms
- デバッグ（`debugMode=true`）: Step7 実行。`debugMode` は `CameraScreen` → `CameraViewModel` →
  `GreenFrameDetector` と引き回されているが、**true を渡す箇所はどこにもない**
  （`DebugCaptureScreen` はPhase6（2026-08-11）で削除済み。将来デバッグツールを再実装する際の
  既存パラメータとして残している）

### 透視変換解像度（変更禁止）
`WARP_PX_PER_MM = 15.0` → 出力 3045×2220px（203mm×148mm）
20px/mm は Step7 が 2.6 倍遅くなるため不採用済み。

**解像度を下げてもいけない。** 元は ML Kit の制約（文字高さ 100px 必要・40px 以下で精度が急落）
として決めた値。ML Kit は2026-08-11に全廃してOCRはGeminiに移ったが、透視変換の出力は
そのまま Gemini に渡す画像なので、下げれば読み取り精度に直接効く。変えるなら実機で
精度を測り直すこと。

### Room DB バージョン（現在 v35）
バージョンアップ時は `ReceiptDatabase.kt` にマイグレーションを追加すること。
`ReceiptDatabase.kt` の `version` / `entities` が一次情報源。docs 側の記載は古くなることがある。
`fallbackToDestructiveMigration()` は削除済み（2026-07-12）。
スキーマ変更時にマイグレーションを書き忘れるとデータ消失ではなく**起動時クラッシュ**になる。

### Navigation に未登録の画面（正常）
`CameraScreen` と `TransformPreviewScreen` は `navigation/Navigation.kt` の NavHost にルートが無いが、
これは不具合ではない。どちらも他の画面（`ReceiptInputScreen` / `GeneralReceiptCaptureScreen` /
`CameraScreenForOcr`）の中に埋め込んで使うコンポーザブルなので、ルート登録は不要。
（かつてここに書かれていた未登録画面 `AccountSettingsScreen.kt` はファイルごと存在しない）

---

## ビルドコマンド

```bash
# クリーンビルド（推奨）
./gradlew clean assembleDebug

# 実機インストール
adb install -r app/build/outputs/apk/debug/app-debug.apk

# パフォーマンスログ確認
adb logcat -s GreenFrameDetector:D | grep PERF

# 全体ログ
adb logcat -s GreenFrameDetector:D GeminiReceiptClient:D CameraViewModel:D ReceiptInputScreen:D
```

---

## 各ドキュメントの役割

- **CLAUDE.md**: プロジェクト全体のルール・運用方針。更新時はユーザーの明示的な確認を取ること。
- **CURRENT_TASK.md**: 今取り組んでいる作業の内容・進捗・次のステップ。セッション終了前に必ず更新する。
- **docs/**: アプリの「何を作るか」「どう作るか」を定義。基本設計が変わらない限り更新しない。
- **.steering/**: 完了した作業の記録・経緯。命名規則 `YYYYMMDD-作業タイトル/`
