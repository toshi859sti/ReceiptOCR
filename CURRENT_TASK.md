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

- [x] AccountSettingsScreen を Navigation.kt の NavHost に登録（2026-05-01）
  - SettingsScreen → 「勘定科目設定」→ AccountSettingsScreen
- [ ] 実機テストで小計カテゴリ認識精度の最終確認
- [ ] 表示ルール（小計後空行・合計行）の動作確認

### Phase 4：商品名エディタ全角化・学習データ共有（優先度：高）

- [x] 商品名エディタの全角化ロジック実装（2026-05-01）
  - 文字種ルール：全角基本、英字トグル（半角切替）、数字・スペース常時全角
  - 単位例外：kg/mm/cm等は半角許容（偶数文字）
  - 全角20文字制限（半角2文字＝全角1文字換算）・文字カウンター表示
  - 「一括全角」ボタン（旧「全て全角」を改名）
  - 差分検出：新規入力部分のみトグル状態適用
- [x] OCRProcessor への全角化前処理追加（2026-05-01）
  - OCR結果をエディタへ渡す前に全文字全角化（toFullWidthText）
  - 全角化済みテキストを DB に保存
  - ocr_variants.variantText は全角化済みで統一（生テキストをそのまま入れない）
- [x] 学習データ共有機能（エクスポート・インポート）の実装（2026-05-02）
  - product_master・ocr_variants を JSON エクスポート（1ファイル）
  - 他ユーザーの JSON をインポート（canonicalName+category 一致でスキップ）
  - 信頼度レベルをそのまま引き継ぐ、source は IMPORT に設定
- [x] 信頼度レベルのユーザー向け表示を「承認済み」「学習中」に統一（UI のみ）（2026-05-02）

## 完了条件
- 年別サマリー画面で過去の年データが閲覧・出力できる
- 同じCSVを何度インポートしても重複しない（DB制約で保証）

## 進捗メモ
- 2026-05-01 撮影品質改善コミット完了（7b47179）
- 複数年対応・重複防止の設計方針確定、次回セッションで実装開始
- 2026-05-01 Phase 1 完了：YearSummaryScreen・OutputConfirmScreen 年セレクター・DAO クエリ追加
- 2026-05-01 Phase 2 完了：DB v16・UNIQUE制約・一括 INSERT OR IGNORE
- 2026-05-01 設計決定（Phase 4 として実装予定）：
  - 商品名エディタ：全角基本、英字のみトグルで半角切替可、数字・スペース常時全角、単位（kg/mm/cm等）は半角例外、全角20文字制限、「一括全角」ボタン、差分検出方式
  - OCRパイプライン：エディタ渡し前に全文字全角化、全角化済みでDB保存、ocr_variants.variantText も全角化済みで統一
  - 信頼度表示：LOCKED/CONFIRMED → ユーザー表示「承認済み」、AUTO → 「学習中」（コード内定数名は変更しない）
  - 学習データ共有：product_master + ocr_variants を CSV エクスポート/インポート可能に、canonicalName+category 一致でスキップ、信頼度はそのまま引き継ぎ

---

## 作業終了時の記録

### 今回完了したこと（2026-05-02）
- Phase 4-c：学習データ共有（JSON エクスポート・インポート）
  - `util/LearningDataExporter.kt` 新設（Gson で product_master + ocr_variants を1JSON）
  - `util/LearningDataImporter.kt` 新設（canonicalName+category 重複スキップ、source=IMPORT）
  - `OcrLearningStatusScreen` TopAppBar に「⋮」メニュー追加（エクスポート・インポート）
  - Snackbar でインポート結果（追加数・スキップ数）表示
- Phase 4-d：信頼度表示 LOCKED/CONFIRMED → 「承認済み」、AUTO → 「学習中」に統一
- Phase 4-a：商品名エディタ全角化ロジック実装
  - `util/ProductNameInputUtils.kt` 新設（共有ユーティリティ）
  - スペース→全角スペース、数字→全角、英字トグル、記号→全角（一括全角）
  - 20文字制限（半角0.5換算）・文字カウンターをテキストボックス上に表示
  - 半角奇数（x.5）時はエラー表示＋保存ブロック
  - トグルボタン「全角」「半角」に変更
  - SheetEditorScreen・ReceiptInputScreen 両方に適用
- Phase 4-b：OCRProcessor 全角化前処理（記号含む 0x21〜0x7E 全対象）
- 伝票決定時（決定ボタン）の半角奇数バリデーション追加
- 小計重複検出時に OCR エラーダイアログ表示・再撮影促進
  - `convertParsedRowsToRowData` → `ParsedRowResult` に変更（重複カテゴリセット返却）
  - 重複あり → OCR 結果不適用 → ダイアログ（再撮影 / キャンセル）

### 未完了・中断した理由
なし（Phase 3 完了）

### 次回セッションで最初にやること
canonicalKey による既存重複データの整理が必要か確認する（購買品リスト画面で重複商品が見えるかチェック）。
その後、OutputConfirmScreen の年セレクター動作確認（年をまたいだデータがある場合のフィルタ）。

### 今回完了（追加: 2026-05-03 午後セッション）
- product_master に canonicalKey 列追加 + UNIQUE(canonicalKey, category) 制約（DB v17）
  - toCanonicalKey(): スペース除去・半角カタカナ全角化・全角英数半角化
  - ProductMaster.withComputedKey() 拡張関数：INSERT 前に自動計算
  - MIGRATION_16_17: 既存データの重複を MAX(id) 優先で解消してからテーブル再作成
  - 全 INSERT 箇所（DatabaseInitializer / ProductListScreen / SettingsScreen / LearningDataImporter）に適用
- Phase 3 実機テスト実施 → 以下のバグを発見・修正（commit 50ad376）
  - 小計行誤検知バグ：detectRowType を ITEM列テキストのみで小計キーワード判定するよう修正
    （分類計列・その他列に「一般購買」等が現れた場合の SUBTOTAL 誤判定を防止）
  - 取引日に漢字混入バグ：processNormalRow の DATE 代入を normalizeToDigits().take(6) に変更
    （「08o1o9免税軽油」のような日付+商品名混在テキストから6桁数字のみを抽出）
- 表示ルール確認：小計後空行・合計行はコードレベルで正常実装を確認
- SettingsScreen の「入力年」→「撮影・入力のデフォルト年」に変更（eraYear 役割を明示）

### 新たに発覚した問題・制約
- OCR が同カテゴリの小計を2回検出することがある（一般購買等）→ 今回の重複検出ダイアログで対応
- 複雑な漢字（雲・灌など）はsharpness≥1000でも完全な認識は難しい。機種依存が大きい → 学習補正で対応
- 商品名列の前処理（CLAHE・UnsharpMask）は現状では改善よりも悪化の傾向 → グレースケールのみで運用
- 日付と商品名が同一テキストボックスで読まれる場合（例：「80114灌水チューブ」）は取引日が空欄になる
  → 商品名側は step8.5 の列特化OCR で正しく取得されるため実用上は許容範囲
