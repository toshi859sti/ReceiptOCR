# CURRENT_TASK.md

## 作業タイトル
JA購買伝票OCRパイプライン Gemini Vision API移行（Phase 0：スパイクテスト）

## 目的・背景
詳細計画は `docs/TASK_gemini_ocr_migration.md` を参照。ML Kit日本語モデルの精度が
頭打ちのため、JA購買伝票OCRをGemini Vision APIへ移行する。UI・DB基盤を作り込む前に、
まず実測でGeminiの読み取り精度を検証する（Phase0）。

2026-08-07のレビューで確定した主な方針：
- APIキーはユーザー個別キー方式を維持（既存 `AppPreferences.geminiApiKey` を使用）
- JA伝票OCRは課金有効化キーを推奨（無料枠は入出力がGoogle側の学習に利用され得るため）
- 新規ラッパークラスは作らず既存 `util/GeminiReceiptClient.kt` を拡張
- DBマイグレーションは v25→v26
- `ocrConfidence` は補助表示のみ。強制ブロックは検算バリデーション不一致のみ
- ML Kit経路はユーザー向けトグルとして併存させない。Phase6の削除は本番安定稼働後

## 今回のタスク（Phase 0：スパイクテスト）
- [ ] 既存の実機確認済み `warpedBitmap`（3045×2220px）サンプルを数枚用意
- [ ] JA伝票用プロンプト素案（列定義・JSON構造指定）を作成
- [ ] 手動スクリプトまたはデバッグ経由でGemini Vision APIへ送信し、レスポンスを確認
- [ ] 数量列・商品名列（複雑な漢字）の読み取り精度をML Kit時代の既知の誤読サンプルと比較
- [ ] 後継モデル（Gemini 3 Flash / 3.1 Flash-Lite等、2.5 Flashは2026/10/16廃止予定）の
      速度・精度・コストを比較
- [ ] 送信画像リサイズ幅（2,000〜2,500px）を変えて精度への影響を確認し、最終値を決定

## 完了条件
- Gemini Vision APIでJA伝票の実サンプルを読み取り、精度がML Kit＋補正ロジックと
  同等以上であることを確認できる
- 採用モデル・リサイズ幅が実測に基づいて決定されている
- 検証結果が `docs/TASK_gemini_ocr_migration.md` の進捗メモに記録されている
- Phase1（確認画面UI）以降に進むかどうかの判断が下せる

## 進捗メモ
（作業中に気づいたこと、決定事項、変更点などを随時記録）

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- `docs/TASK_gemini_ocr_migration.md` のレビューと方針決定（Phase0スパイクテスト自体は未着手）：
  1. `GeminiOcrProcessor.kt`新設ではなく既存 `util/GeminiReceiptClient.kt` を拡張する方針に変更
  2. APIキーはユーザー個別キー方式を維持（BuildConfig埋め込み方式は不採用）
  3. Gemini APIのデータ利用ポリシーを調査（無料枠=モデル改善に利用され得る／有料枠=学習非利用）。
     JA伝票OCRは課金有効化キー推奨の注意文言を設定画面に追加する方針とした
  4. DBマイグレーション番号を v15→v16 から v25→v26（現在の実バージョン）に修正
  5. `ocrConfidence`は強制ブロックの根拠にせず、検算バリデーション不一致のみを強制ブロック条件とする方針を確定
  6. Phase0（スパイクテスト）を新設し、UI・DB基盤の作り込み前に精度検証を行うことにした
  7. ML Kit経路のユーザー向けトグル併存はしない方針を明記（Phase6削除は本番安定稼働後）
- 旧 `CURRENT_TASK.md`（レシート一覧UX改善・弥生CSV対応等）を `.steering/20260807-general-receipt-ux-invoice-yayoi-csv/` にアーカイブ

### 未完了・中断した理由
Phase0のスパイクテスト自体（サンプル画像でのGemini実測）は未着手。今回は計画レビューと方針決定のみ。

### 次回セッションで最初にやること
Phase0スパイクテストの準備：実機確認済みの`warpedBitmap`サンプルを数枚用意し、JA伝票用プロンプト素案を作ってGemini Vision APIに手動送信してみる。

### 新たに発覚した問題・制約
なし（今回はドキュメント・方針整理のみ、コード変更なし）
