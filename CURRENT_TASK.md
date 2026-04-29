# CURRENT_TASK.md

## 作業タイトル
実機テスト・動作確認

## 目的・背景
伝票編集ダイアログの文字幅変換機能（2026-04-08）まで実装が完了し、
ビルドも通っている。実機テストで OCR 精度・UI 動作を確認する。

## 今回のタスク
- [ ] 小計カテゴリ認識精度の確認（DebugCaptureScreen または通常撮影）
- [ ] 表示ルール確認（小計後空行・合計行の順序）
- [ ] 数量列 OCR 精度確認
- [ ] 伝票編集ダイアログの文字幅変換機能確認（新機能）
- [ ] AccountSettingsScreen を Navigation に接続する

## 完了条件
- 小計行カテゴリが正しく判定される（一般購買 / 給油所 / 農業機械）
- 数量・商品名・税込金額が概ね正しく OCR される
- 伝票編集ダイアログの文字幅変換が正常に動作する

## 進捗メモ
- 2026-04-29 実機接続確認、APK インストール済み（BUILD SUCCESSFUL）
- SheetEditorScreen.kt の FilterChip が ExperimentalMaterial3Api エラー → @OptIn 追加で解消
- 実機テスト開始前の状態

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- DoubleOCR 廃止・新前処理パイプライン実装（Green→CLAHE→UnsharpMask→MorphOpen）
- OCR_SPEC.md 全面書き直し（ArUco旧方式→現行GreenFrame方式）
- SheetEditorScreen.kt の @OptIn(ExperimentalMaterial3Api) ビルドエラー修正
- docs/ 全ドキュメント更新（architecture.md DB v13→v15修正ほか）
- CLAUDE.md 新規作成
- prepareItemColumnMat から safeMorphOpen を削除（漢字誤認識改善）→ BUILD SUCCESSFUL

### 未完了・中断した理由
- 漢字誤認識の改善が未実装。原因調査まで完了、修正コードは確定済み。

### 次回セッションで最初にやること
デバイスを USB 接続して `adb install -r app/build/outputs/apk/debug/app-debug.apk` でインストール後、DebugCaptureScreen で商品名列の漢字認識精度を確認する。

### 新たに発覚した問題・制約
- `safeMorphOpen`（kernel=charPx×0.08≈3px）が漢字の細いストロークを消している可能性
  → 「1画違い」ではなく「誤字が多い」という症状と一致
  → docs/known-issues.md に追記済みではないが要追記
