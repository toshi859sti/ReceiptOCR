# CURRENT_TASK.md

## 作業タイトル
一般レシートOCRのGemini一本化・鮮鋭度表示追加

## 目的・背景
JA伝票OCR移行（Phase0〜6）完了・アーカイブ後の継続セッションで、一般レシート側の
Gemini呼び出しが廃止モデル（`gemini-2.5-flash`）を使っておりHTTP 404で失敗していることが
実機テストで発覚。修正した上で、ユーザー判断により一般レシートもML Kitを廃止して
Gemini画像直接送信（`parseReceiptFromImage`）に一本化した。さらに精度向上策の一環として、
JA伝票の`CameraScreen.kt`にある鮮鋭度リアルタイム表示を一般レシート撮影画面にも追加した。

## 今回のタスク
- [x] 一般レシートのGemini呼び出しが`gemini-2.5-flash`（廃止済み）を使っていたバグを修正
- [x] 一般レシートをGeminiのみに一本化、ML Kit依存を完全削除
- [x] 一般レシート撮影画面に鮮鋭度リアルタイム表示を追加

## 完了条件
- 一般レシートのGemini画像解析がHTTP 200で成功する（実機確認済み）
- 「ML Kit OCR」トグルがUIから消え、Gemini画像モード固定になっている（実機確認済み）
- 鮮鋭度の数値が撮影画面上部に表示され、閾値に応じて緑/黄に変化する（実機確認済み）

## 進捗メモ
- 一般レシートのGemini呼び出し（`matchProducts`/`matchTekiyou`/`parseReceiptFromImage`共通の
  `API_URL`定数）が`gemini-2.5-flash`のままだったため、JA伝票と同じく`gemini-3.5-flash-lite`に
  更新（`GENERAL_RECEIPT_MODEL`という別定数に分離）
- ついでに`AiUsageStats`（トークン使用量）をログ出力するようにし、画像モード（1,181トークン）と
  テキストモード（455トークン）の実測コスト差（約¥0.04/枚）を確認した
- ユーザーが精度差を実感し「Geminiのみでいきましょう」と判断。スコープ確認の結果、
  「ML Kit OCRトグルをUIから削除してGemini画像固定にする」で合意
  - `GeneralReceiptCaptureScreen.kt`のCaptureMode enum・ModeToggle・ML Kit呼び出しを削除
  - `GeneralReceiptViewModel.kt`の`onOcrCompleted()`/`fallbackToRawOcr()`
    （ML Kit経路専用、オフライン時に生テキストを保存する安全策だった）も削除。
    **この判断により、オフライン・APIキー未設定時のフォールバックが完全になくなった**
    （`onImageCaptured()`はエラー表示のみで何も保存しない）。ユーザー確認済みの意図的な選択
  - ML KitがGeneralReceiptCaptureScreen.kt以外のどこからも使われていないことを確認した上で、
    `build.gradle.kts`のML Kit依存2件・`proguard-rules.pro`のkeepルールも削除
  - `docs/TASK_general_receipt_ocr.md`・`docs/known-issues.md`のML Kit関連記述を更新
- 鮮鋭度表示：`ImageAnalysis`ユースケースを追加し、`OcrQualityEvaluator.calculateSharpness()`・
  `ImagePreprocessor.toGray()`・`YuvToRgbConverter.imageProxyToBitmapDirect()`をJA伝票の
  `CameraScreen.kt`と同じ要領で流用。960pxダウンスケール・200ms間隔の間引き、
  `appPreferences.minSharpness`（JA伝票と共有の設定値）を閾値に緑/黄で表示。
  **自動撮影は追加していない**（ユーザー判断：表示のみで十分、シャッターは引き続き手動）

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- 一般レシートのGemini呼び出し404バグ修正（`GENERAL_RECEIPT_MODEL = "gemini-3.5-flash-lite"`）
- 一般レシートのML Kit完全廃止、Gemini画像直接送信に一本化（ビルド依存ごと削除）
- 一般レシート撮影画面に鮮鋭度リアルタイム表示を追加（自動撮影なし、表示のみ）
- 上記すべて実機で動作確認済み（HTTP 200・トグル削除後のUI・鮮鋭度の数値表示と色変化）

### 未完了・中断した理由
なし。鮮鋭度表示の変更は `dd77796` としてコミット済み（前回の記録更新漏れ）。

### 次回セッションで最初にやること
前回セッションで話題に出た一般レシートの改善案のうち未着手のもの
（複数枚レシートの一括OCR・手書き領収書対応・長いレシートの複数回撮影対応）を
実際のニーズが出てきたタイミングで検討する

### 新たに発覚した問題・制約
- 一般レシートのオフライン・APIキー未設定時のフォールバックが廃止された（ユーザー判断による
  意図的な仕様）。今後「電波の悪い場所で一般レシートが撮れない」という声が出た場合は、
  この変更が原因であることを思い出すこと（`docs/known-issues.md`に記録済み）
