# CURRENT_TASK.md

## 作業タイトル
通帳明細の個別勘定科目オーバーライド機能の実装

## 目的・背景
GreenFrameDetector の実装と OCR パイプライン（processUnderlayingBase）は完成し
ビルドも通っている。実機撮影での精度を確認し、問題があれば調整する。

## 今回のタスク
- [x] DepositMeisai に overrideTekiyouId 列追加
- [x] DepositMeisaiDao に updateOverrideTekiyou / clearOverridesForRule / getAllWithOverrideByRuleId 追加
- [x] Room DB v13 → v14 マイグレーション追加
- [x] TekiyouMatchingScreen を展開UI + 個別オーバーライドダイアログに刷新
- [x] BUILD SUCCESSFUL 確認（2026-04-05）
- [x] 摘要集約リストタップ時「データなし」バグ修正（2026-04-05）
- [x] 購買品リスト：商品名エディタ改良（全角20文字制限・自動変換・確定フラグ）（2026-04-06）

## 保留タスク（OCR検証系）
- [ ] 実機テストで小計カテゴリ認識精度を確認
- [ ] 表示ルール（小計後空行・合計行）の動作確認
- [ ] 数量列 OCR の精度確認
- [ ] AccountSettingsScreen を Navigation に接続する

## 進捗メモ
- 2026-04-06 購買品リスト 商品名エディタ改良
  - DB v15: product_master.isCertified 追加
  - 入力時に半角数字→全角・半角スペース→全角へ自動変換
  - 全角換算20文字で入力制限（半角英字kg等は0.5文字）
  - 保存時に isCertified=true をセット
  - リストに「確定」バッジ表示・「確定済み」フィルタ追加
- 2026-04-05 摘要集約リスト「データなし」バグ修正
  - updateRulesFromMeisai でルール作成後に deposit_meisai.matchingRuleId を書き戻していなかった
- 2026-04-05 個別オーバーライド機能を実装・BUILD SUCCESSFUL
  - DB v14: deposit_meisai.overrideTekiyouId 追加
  - TekiyouMatchingScreen: グループ展開UI + IndividualOverrideDialog
  - グループ編集保存時に clearOverridesForRule を実行（全件上書き案B）
- 2026-03-19 BUILD SUCCESSFUL 確認済み
- GreenFrameDetector 本番モード合計: 約1,225ms（Step7スキップ後）
- OCRProcessor 合計: 約2,500ms（ML Kit 限界）
- 全体合計: 約3,700ms
- アダプティブアイコン設定済み（#0E6C48 ダークグリーン背景）
- 2026-03-25 PROJECT_TEMPLATE.md に基づき以下を生成・更新
  - CLAUDE.md（新規生成 → 本セッションで更新）
  - CURRENT_TASK.md（新規生成）
  - docs/product-requirements.md（新規）
  - docs/functional-design.md（新規）
  - docs/architecture.md（旧 ARCHITECTURE.md を上書き更新）
  - docs/repository-structure.md（新規）
  - docs/development-guidelines.md（新規）
  - docs/glossary.md（新規）
  - docs/known-issues.md（新規）

## 未解決事項・課題
- AccountSettingsScreen が Navigation に未接続（要確認・対応）
- RakurakuTekiyouScreen が SettingsScreen から遷移できるか未確認
- Windows マスタ管理アプリの設計（検討中・後述）

## Windows マスタ管理アプリ（検討中）
- **目的**: 商品マスタ・ocr_variants を PC 上で確認・編集・確定管理する
- **方針**: adb pull → Windows アプリで編集 → adb push で戻す（手動）
- **技術スタック候補**: Python + PySide6（推奨）
- **確定証明機能**:
  - `product_master` に `is_certified` 列を追加（INTEGER DEFAULT 0）
  - 確定済み（is_certified=1）アイテムは編集・削除不可
  - Android 側でも確定済みアイテムの編集・削除を禁止する
  - Room DB v13 → v14 マイグレーションが必要
- **未決事項**: is_certified を Room DB に追加するか、Windows 専用サイドカー DB にするか検討中

## 次回セッションの開始点
以下のどちらかを選んで開始：
1. 実機テスト（DebugCaptureScreen でカテゴリ認識確認）
2. Windows マスタ管理アプリの設計・実装開始

## 完了条件
- [ ] 3辺の緑枠を正しく検出し透視変換が正常に実行される
- [ ] 取引明細行のカテゴリ（給油所/農業機械/一般購買）が正しく割り当てられる
- [ ] 数量・商品名・税込金額が概ね正しく OCR される
- [ ] 弥生 CSV / らくらく CSV の出力フォーマットが正常
