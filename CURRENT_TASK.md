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
- [x] PC側が schemaVersion 2（2026-09-22版）を発行。受領分をコミット（`4f71a98`）
- [x] `HANDOVER.md` §5-1 の4件に方針を出し、`REPLY-phone-2026-09-22.md` として返信を作成
- [x] PC側が指摘3点を修正（`accountName` 追加・§7 isReturn 主語限定・sample の `_note`）→ コミット `b93e3ec`
- [x] 継続協議3件に回答（返信 §8）：Purchase の externalId を **UUID方式に変更提案**／Deposit 空欄は
      **ハッシュ不要**（合成番号でバグごと解消）／収入科目の税率は摘要から引くで了解
- [ ] PC側の回答待ち：Purchase の UUID 方式への同意（`transaction-import.md` §4 の書き換えが必要）
- [ ] スマホ側実装の着手（DB v34 マイグレーション一式から）

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

### 2026-09-22 セッション：schemaVersion 2 受領と §5-1 への回答
- **9/10 の「AccountKey＝らくらく科目番号（数値）」案は失効**。PC側の v2 契約では `accountKey` は
  **不透明な文字列**（システム科目は `genkin`/`hiryou` 等の歴史的スラッグ、ユーザー追加は `acct-<英数字>`）。
  数値キー・両建て・`code` との併記はいずれも不要になった → 9/10 の未完了項目1〜4はすべて解消
- 契約の重要変更（2026-09-13）：`accountKey` は科目マスタの**行**に1対1で、作り替えても**据え置き**
  （`name` だけ変わる）。`AccountKeyRegistry`/`keyRevision` は撤回。**学習を外す合図が「キーの消失」から
  「`name` の変化」へ** → スマホ側は学習エントリに「そのとき見た name」を保持する必要がある
- 摘要の参照キー `memoKey` 導入（`memoName` は表示用エコーに格下げ）。摘要は**閉じた語彙**
  （辞書のキーか `null` の二択、生テキストは `note` 列へ）
- `bankSlotNo` の有効値は **1〜5**（`0`＝親「普通預金」は実装に存在せず不正）→ 9/10 回答の既定値0を撤回
- `enums.taxRate` に `"1"`（2027年の食料品1%軽減）追加。スマホは**読めればよい**、出す必要はない
- **§A（自前科目→PC科目の接続キー問題）はスマホ側だけで閉じられると判断**し、PC側への依頼を取り下げ
  - `yayoi_accounts` / `rakuraku_accounts` に `accountKey: String?` を1本追加。マッチングテーブルは無変更
  - 実測：`vocabulary.sample.json` の accountKey 27件中**22件が rakuraku_accounts.searchKeyAlpha と完全一致**。
    非一致5件は `bank3`/`suitou`/`nougai`（スロット科目）と `zigyounusikari`/`zigyounusikas`
  - `yayoi_accounts`(98行) は表記体系が別で**16件しか一致しない**（内部重複6件）→ 弥生モードは手動確定が主
  - `rakuraku_accounts` の重複サーチキーは4件（`hiryou`/`kasidaore`/`totikairyou`/`zigyounusi`）
- 契約側に見つかった不整合3点（返信 §4 に記載）：
  1. **`accountName` が契約に未定義**。CHANGELOG/§4.6 は「送れるときは必ず送れ」と要求しているが
     `transaction-import.md` §3 の Entry にフィールドが無い → 古いスナップショット検知が機能しない（ブロッカー）
  2. `examples/vocabulary.sample.json` の `_note` が 2026-09-12 の旧規約のまま
  3. `transaction-import.md` §7 の isReturn 文言が Deposit 出金に誤適用され得る（9/10 指摘・未反映）

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md` を新規作成（PC会計アプリ向け連携仕様・全12章）
- 調査で判明した既存実装の不整合4点を `docs/known-issues.md` に転記（修正方針つき）
- 不整合 #1（預金CSVが個別オーバーライドを無視）を修正（`OutputConfirmScreen.loadDepositOutputItems`）
- 不整合 #2（レシート出力確認が `isExcluded` を除外しない）を修正（`GeneralReceiptViewModel.loadOutputItems`）
- クリーンビルド BUILD SUCCESSFUL 確認
- コミット: `a44a8e9`（仕様書・known-issues転記）、`486e75f`（#1・#2修正）
- 2026-09-22：PC側 schemaVersion 2（9/22版）の契約一式を受領・コミット（`4f71a98`）。
  `HANDOVER.md` §5-1 の4件（取込経路・預金スロット・contentHash・往復検証）に方針を出し、
  §A の取り下げと契約の穴3点を含めた返信 `docs/integration/REPLY-phone-2026-09-22.md` を作成

### 未完了・中断した理由
- 不整合 #3（弥生CSVの列構成が購買/預金とレシートで不一致）・#4（弥生税区分文字列が
  やよい実仕様と不一致の疑い）は、弥生が現在使えず実インポート検証ができないため保留
- 2026-09-10 の未完了項目1〜4（`accountKey` 数値キーの詰め）は **2026-09-22 に解消**。
  v2 契約で `accountKey` は PC 所有の不透明文字列に確定したため、論点自体が消えた
- 2026-09-22：スマホ側の実装は**未着手**。PC側の回答（契約の穴3点、特に `accountName` の追加）待ち。
  ただし `accountName` に依存しないタスク（DB v34 マイグレーション一式・取込UI）は先行着手できる

### 次回セッションで最初にやること
`docs/integration/REPLY-phone-2026-09-22.md` を PC 側セッションに渡して §4 の3点の回答を得る。並行して、スマホ側タスク1〜3・6（`accountKey` 列追加／`aoirochobo_*` 3テーブル／学習テーブルの name 列／`general_receipts.uuid`）をまとめて DB v34 のマイグレーションとして実装する。

### 2026-09-22 第2ラウンドで判明した実装上の事実
- `ReceiptInputScreen` の行「挿入」「削除」は**以降の行を全部シフト**（`rows[i] = rows[i-1]`）。
  保存時 `itemNumber` は `rowNumber = index + 1` で位置から振り直されるため、位置ベースの
  `externalId` は挿入1回で以降が全部ずれ、**同じIDが別商品を指す**。PC側の再取込規則では
  「内容差分があれば更新」＝黙って上書きされる → **Purchase も UUID 方式を提案**
- `deposit_meisai` は `UNIQUE(transactionDate, transactionNumber)` ＋ CSV取込が
  `insertAllIgnoreDuplicates`(IGNORE) なので、**同日・通番空欄の行は2件目以降が無言でスキップ**。
  ＝PC提案の連番サフィックスは発火しない。同時にこれは取込バグ（known-issues に登録済み）

### 新たに発覚した問題・制約
- `docs/known-issues.md` の「既知のバグ」に4点追加済み（#1・#2修正済み、#3・#4保留）
- externalId 用に `general_receipts` へ UUID カラム追加＋既存行バックフィルが必要（DB v34 想定・未着手）
- `searchKeyAlpha` は `rakuraku_accounts` 内で重複があり結合キーに使えない（既知化）
