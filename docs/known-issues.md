# 既知バグ・制約・技術的負債

## 既知のバグ

- [x] 摘要集約リスト「データなし」バグ（修正済み 2026-04-05）
  - `updateRulesFromMeisai` でルール生成後に `deposit_meisai.matchingRuleId` を書き戻していなかった
- [ ] 小計カテゴリ認識精度（実機テスト未完）
  - 実撮影での認識精度は未確認。照明・角度により誤認識の可能性あり
- [ ] 表示ルール（小計後空行・合計行の順序）未確認
  - 実機テストで動作確認が必要

---

## 制約・注意事項

- `WARP_PX_PER_MM = 15.0` は変更禁止
  - 20px/mm に変更すると Step7 行切り抜きが 2.6 倍遅くなる（実測済み）
  - 変更する場合は全パイプラインの再計測が必要
- ML Kit の最小文字高さは実質 100px（40px 以下で精度急落）
  - 透視変換解像度を下げると商品名・数量の認識精度が著しく劣化する
- ML Kit 日本語モデルはオフライン動作のため assets にバンドルが必須
  - モデルサイズが大きく APK サイズに影響する
- CameraX ImageAnalysis の 4K 解像度はデバイスによってサポート外の場合あり
  - 非対応デバイスでは自動フォールバックするが精度低下の可能性あり
- `Utils.bitmapToMat` は RGBA 4ch を返す
  - OpenCV 処理前に `COLOR_RGBA2BGR` 変換が必須。忘れると色チャンネル不一致でマスク精度が劣化する
- `fallbackToDestructiveMigration()` は削除済み（2026-07-12）
  - 以後、DBスキーマ変更時はマイグレーション追加が必須
  - マイグレーションを書き忘れるとデータ消失ではなく**起動時クラッシュ**になる点に注意
- `OcrCaptureScreen`/`OcrCaptureViewModel`・`SheetEditorScreen`/`SheetEditorViewModel`は
  本番UIから到達不能と判明したため、Phase6（2026-08-11）で削除済み
  - 実際にユーザーが使う撮影導線（伝票データ→編集→伝票追加→撮影）は
    `ReceiptInputScreen.kt`内の独自`CameraView`実装（`showCamera`状態＋非公開`CameraView`
    コンポーザブル）。実際のカメラプレビューUI（`CameraScreen.kt`・`CameraViewModel.kt`）と
    OCR失敗画面（`OcrErrorScreen`、`ui/CameraScreenForOcr.kt`）は本番導線でも共有利用しており
    削除していない
  - 撮影・OCR処理まわりに手を入れる際は、`ReceiptInputScreen.kt`が主導線であることを
    前提にすること

---

## 未実装・将来対応

- [ ] `NtaInvoiceClient`（国税庁インボイス照会）の API 仕様が未検証（2026-07-12）
  - `API_BASE_URL` およびレスポンスJSONのフィールド名（`code`/`announcement`/`name`/`address`）は公式ドキュメントとの突合が未実施
  - アプリケーションID未設定時に実際に動作するかも未確認
  - 実機でのネットワーク照会テストが必要
- [ ] `RakurakuTekiyouScreen` が `SettingsScreen` から遷移できるか未確認
- [ ] 弥生会計・らくらく青色申告との直接連携
  - 現状は CSV ファイル出力のみ。API 連携は未実装
- [ ] 複数伝票の一括処理・一括確定機能
- [ ] ユニットテスト・UI テストの整備（現在ほぼ未実装）
- [ ] Windows マスタ管理アプリ（検討中）
  - adb pull → PC で商品マスタ・ocr_variants を編集 → adb push
  - 技術候補: Python + PySide6

---

## 技術的負債

（`ProductNameCorrectorV3`の自動補正学習システム・`ocr_score_logs`/`correction_logs`
テーブルはPhase6（2026-08-11）で削除済み。関連する技術的負債はあわせて解消）
