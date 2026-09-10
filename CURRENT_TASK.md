# CURRENT_TASK.md

## 作業タイトル
PC会計アプリ連携仕様書の作成

## 目的・背景
Androidアプリ「JA仕訳変換」の出力データを取り込み、マッチング・仕訳JSON出力を行う
PC会計アプリを同時進行で開発中。PC側のClaude Code / 開発者がAndroid側のデータ仕様
（摘要辞書・勘定科目・取引データのJSON、マッチングロジック、仕訳の借方/貸方の決め方）
を正確に理解できる一次情報が必要。

## 今回のタスク
- [x] Room DB（v33）全エンティティのスキーマ棚卸し
- [x] JSONバックアップ（AllExportData等）の形式調査
- [x] CSV出力（らくらく/弥生・購買/預金/レシート）の仕訳ロジック調査
- [x] マッチングロジック調査（canonicalKey / normalizeTekiyou / 支払方法ルール）
- [x] `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md` として文書化
- [x] 不整合 #1（預金CSVの個別オーバーライド反映）・#2（`isExcluded` 除外）を修正・ビルド確認
- [ ] 不整合 #3（弥生CSV列構成）・#4（弥生税区分文字列）は弥生が使える時に対応
- [x] PC側が契約一式を発行（`docs/integration/`）。3大前提変更（マッチングはスマホ／マスタはPC所有／JSON）を受け入れ
- [x] 契約レビュー回答を `docs/integration/REPLY-phone-2026-09-10.md` に作成（未確定事項A〜LをPC側へ返す）
- [ ] PC側の回答待ち：A(vocabularyに科目コード追加可否)・C(contentHash扱い)・E/F/G

## 完了条件
PC側のClaude Codeがこの1ファイルを読めば、Android出力の全データ構造・
マッチング仕様・仕訳生成規則を再現できる状態。

## 進捗メモ
- 新規ファイル: `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md`（12章構成）
- 調査中に判明した既存実装の不整合（仕様書 §11 に記載）:
  - 弥生CSVの列並びが購買/預金とレシートで違う（購買/預金は先頭 "2000" 欠落）
  - 預金の個別上書き overrideTekiyouId / overrideYayoiAccountId がCSV出力に未反映
  - レシート loadOutputItems() が isExcluded をフィルタしていない
  - YayoiAccount.defaultTaxCategory のDB値と弥生が受け付ける税区分文字列が不一致の可能性
- `docs/DATABASE_SCHEMA.md` はv11時点で大幅に古い（実際v33）
- `docs/yayoi-csv-export-spec.md` は別プロジェクト "AoiroChobo"(C#) 由来の参考資料

### 2026-09-10 セッション：PC側契約のレビューと科目キー設計相談
- PC(AoiroChobo)は「らくらく青色申告 農業版」の互換アプリ（作者本人専用）。摘要辞書・科目チャートは同型
- 弥生CSV出力は今後も残す（弥生ユーザーが多いため）→ スマホは自前 `yayoi_accounts`/`rakuraku_accounts` を維持
- AoiroChobo JSON出力は「3番目の出力経路」。らくらく側のデータから作る想定
- **科目マッピング方式を決定**：共有数値キー `AccountKey`（＝らくらく科目番号 100〜602、現 `rakuraku_accounts.csv` の「サーチキー数字」列）を新設し、両側の科目に持たせる。翻訳表は作らない
  - `vocabulary.json` の Account に `accountKey: Int` 追加（PC側）
  - スマホ `rakuraku_accounts` に `accountKey`（既存サーチキー数字）、`yayoi_accounts` に `accountKey: Int?`（弥生農業科目→らくらく番号の対応を seed、~25行）
  - `product_master` 等のマッチングテーブルは変更なし（`yayoiAccountId` のまま）。解決時に account master をホップ
  - `searchKeyAlpha` は重複（hiryou=肥料/肥料費 等）があり結合キーに使えない → 数値キー必須
- スマホ側の実コード確認済み事実：
  - `receipt_items` に (issueYear,issueMonth,sheetNumber,itemNumber) UNIQUE制約なし。保存時に月単位で全DELETE→全INSERT、`id` は毎回変わる。`itemNumber` は固定20行グリッド位置ベースで比較的安定
  - `general_receipts` に UUID カラムなし（externalId 用に追加＋既存行バックフィルが必要）
  - `deposit_meisai` は UNIQUE(transactionDate, transactionNumber) あり
  - `product_master` は旧 `rakurakuAccountId` をマイグレーションで削除済み。現在 `yayoiAccountId` + `kaikakeTekiyouId(→rakuraku_tekiyou)`

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md` を新規作成（PC会計アプリ向け連携仕様・全12章）
- 調査で判明した既存実装の不整合4点を `docs/known-issues.md` に転記（修正方針つき）
- 不整合 #1（預金CSVが個別オーバーライドを無視）を修正（`OutputConfirmScreen.loadDepositOutputItems`）
- 不整合 #2（レシート出力確認が `isExcluded` を除外しない）を修正（`GeneralReceiptViewModel.loadOutputItems`）
- クリーンビルド BUILD SUCCESSFUL 確認
- コミット: `a44a8e9`（仕様書・known-issues転記）、`486e75f`（#1・#2修正）

### 未完了・中断した理由
- 不整合 #3（弥生CSVの列構成が購買/預金とレシートで不一致）・#4（弥生税区分文字列が
  やよい実仕様と不一致の疑い）は、弥生が現在使えず実インポート検証ができないため保留
- 2026-09-10：PC側契約のレビュー中。`AccountKey` 方式に合意したが、以下の詰めが未完了で中断：
  1. `accountKey` null許容（弥生補助科目・独自科目）→ UnmatchedAccount 送り、で確定か
  2. AoiroChobo 側 `Account.accountKey` は内部PKと同一か別か・年度不変保証
  3. `code`(スラッグ) と `accountKey`(数値) の両建て、出力は `code` のまま、で確定か
  4. スマホ `rakuraku_accounts.accountCode`(文字列) は残す or `accountKey` にリネーム
- `REPLY-phone-2026-09-10.md` の A 節は旧案（rakurakuAccountCode 突き合わせ）のまま。AccountKey 方式へ書き直し未了

### 次回セッションで最初にやること
上記1〜4をユーザーに確認 → `docs/integration/REPLY-phone-2026-09-10.md` の A 節を AccountKey 方式へ書き直し、`vocabulary-snapshot.md` §4.1 への追記案（`accountKey` フィールド）をまとめる。

### 新たに発覚した問題・制約
- `docs/known-issues.md` の「既知のバグ」に4点追加済み（#1・#2修正済み、#3・#4保留）
- externalId 用に `general_receipts` へ UUID カラム追加＋既存行バックフィルが必要（DB v34 想定・未着手）
- `searchKeyAlpha` は `rakuraku_accounts` 内で重複があり結合キーに使えない（既知化）
