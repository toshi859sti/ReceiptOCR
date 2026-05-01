# CURRENT_TASK.md

## 作業タイトル
複数年データ対応・預金CSV重複防止・撮影品質改善

## 目的・背景
- 農業経営の確定申告には過去5〜7年分のデータ保存が必要
- 現状DBは複数年対応済みだが、年を切り替えて閲覧・出力するUIがない
- JAアプリからの通帳CSVインポートで重複チェックはあるが、DB制約がない
- 複雑な漢字（雲・灌など）のOCR誤認識は鮮鋭度（sharpness）管理で対応済み

## 今回のタスク（前回セッション 2026-04-29〜05-01 で完了）
- [x] 断続的な枠検出失敗（自動露出の瞬間変動）→ MAX_BAD_FRAMES_BEFORE_RESET=2 で解消
- [x] 鮮鋭度リアルタイム表示（カメラ画面上部に48sp で表示・閾値超で緑色）
- [x] 最低鮮鋭度を設定画面（購買部門）で調整可能に（500〜3000、デフォルト1000）
- [x] 商品名列OCR：2倍拡大（Imgproc.resize）→ ML Kit へ渡す（~52px→~104px）
- [x] 商品名列OCR前処理をグレースケールのみに簡略化（CLAHE・UnsharpMask廃止）
- [x] Step8 二値化を削除（約88ms短縮）→ DetectionResult に CaptureInfo（撮影情報）追加
- [x] デバッグ画面 ⑥ を「撮影情報」パネルに変更（解像度・鮮鋭度・時刻）
- [x] コミット完了（7b47179）

## 次回以降の実装工程

### Phase 1：複数年対応・年別ナビゲーション（優先度：高）

**設計方針**
- DBは `issueYear`/`issueMonth` で伝票年月を管理済み → スキーマ変更不要
- 取引日付（receiptYear/Month/Day）と伝票年月（issueYear/Month）は既に分離済み
- 1月の伝票に前年12月の取引が含まれても `issueYear` で正しく分類できる

**実装タスク**
- [x] 年別サマリー画面（YearSummaryScreen）新設（2026-05-01）
  - データがある年を一覧表示（issueYear でグループ化・年降順）
  - 年カードをタップで展開→月別リスト表示（シート枚数・月合計）
  - 月行タップ → MonthlySummaryScreen へ遷移
- [x] Navigation.kt に YearSummaryScreen を登録（2026-05-01）
- [x] PurchaseMenuScreen に「購買データ確認」ボタン追加 → YearSummaryScreen へ遷移（2026-05-01）
- [x] ReceiptDao に `getAvailableYears()` / `getAvailableMonthsForYear()` クエリを追加（2026-05-01）
- [ ] 出力確認画面（OutputConfirmScreen）に年セレクター追加
  - 年単位でフィルタできるようにする（既存の日付範囲フィルタと併用）
- [ ] eraYear 設定の役割を「新規撮影時のデフォルト年」として明示

### Phase 2：預金CSV重複防止の強化（優先度：中）

**現状**
- アプリ層で `transactionDate + transactionNumber` の重複チェックあり → 通常使用では問題なし
- DB に UNIQUE 制約がないためアプリ層バグで重複が入るリスクがある
- N+1 クエリ（1件ずつ SELECT → INSERT）でパフォーマンスが低い

**実装タスク**
- [x] DB v16 へマイグレーション（2026-05-01）
  - `deposit_meisai` テーブルに `UNIQUE(transactionDate, transactionNumber)` 制約追加
  - テーブル再作成+重複排除マイグレーション実装
- [x] DAO に `insertAllIgnoreDuplicates()` を追加（2026-05-01）
- [x] PassbookDataScreen のインポートを一括 INSERT OR IGNORE に変更（N+1廃止）（2026-05-01）

### Phase 3：その他の継続タスク（優先度：低）

- [ ] AccountSettingsScreen を Navigation.kt の NavHost に登録
- [ ] 実機テストで小計カテゴリ認識精度の最終確認
- [ ] 表示ルール（小計後空行・合計行）の動作確認

## 完了条件
- 年別サマリー画面で過去の年データが閲覧・出力できる
- 同じCSVを何度インポートしても重複しない（DB制約で保証）

## 進捗メモ
- 2026-05-01 撮影品質改善コミット完了（7b47179）
- 複数年対応・重複防止の設計方針確定、次回セッションで実装開始
- 2026-05-01 Phase 1 完了：YearSummaryScreen・OutputConfirmScreen 年セレクター・DAO クエリ追加
- 2026-05-01 Phase 2 完了：DB v16・UNIQUE制約・一括 INSERT OR IGNORE

---

## 作業終了時の記録

### 今回完了したこと
- 鮮鋭度ベースの撮影品質管理（リアルタイム表示・設定化・閾値1000）
- 商品名列OCR 2倍拡大・グレースケール簡略化
- Step8 二値化削除 → CaptureInfo 表示に変更
- 複数年対応と預金CSV重複防止の設計方針確定

### 未完了・中断した理由
- Phase 1〜3 は次回セッション以降に実装

### 次回セッションで最初にやること
Phase 1 の年別サマリー画面（YearSummaryScreen）実装から開始する。
まず `ReceiptDao` に `getAvailableYears()` クエリを追加し、画面を新設する。

### 新たに発覚した問題・制約
- 複雑な漢字（雲・灌など）はsharpness≥1000でも完全な認識は難しい。機種依存が大きい。
- 商品名列の前処理（CLAHE・UnsharpMask）は現状では改善よりも悪化の傾向 → グレースケールのみで運用
