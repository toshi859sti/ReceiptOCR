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
- [x] PC側が UUID 方式に同意（`REPLY-pc-2026-09-22.md`・契約 §4 書き換え済み）→ **回答待ちはゼロ**
- [x] PC側からの同期分（`docs/integration/`）をコミット（`87f8abc`）
- [x] `receipt_items.uuid` を DB v35 で追加（行データにも持たせて保存・ロードで引き継ぎ）
- [x] 通帳CSV取込の合成番号（`DepositNumberAssigner` ＋ ユニットテスト7件）
- [x] スマホ側実装に着手：**DB v34 マイグレーション一式**（返信 §6 のタスク1・2・3・6）
- [x] vocabulary.json 取込 UI ＋ 取込処理1〜5（返信 §6 タスク4）
- [ ] 科目マッピング UI（サーチキー英字で自動提案 → ユーザー確定）（同 タスク5）

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

### 2026-09-22 第3ラウンド：DB v34 マイグレーション実装（返信 §6 タスク1・2・3・6）

`ReceiptDatabase.kt` に `MIGRATION_33_34` を追加し、version を 34 に上げた。内訳：

| # | 内容 |
|---|---|
| ① | `yayoi_accounts` / `rakuraku_accounts` に `accountKey: String?` ＋ `accountKeyName: String?` |
| ② | `rakuraku_tekiyou` に `memoKey: String?` ＋ `memoKeyName: String?` |
| ③ | `aoirochobo_accounts` / `aoirochobo_memo_templates` / `aoirochobo_vocab_meta` を新設 |
| ④ | `general_receipts.uuid`（NOT NULL）追加＋既存行バックフィル＋UNIQUE インデックス |

**「そのとき見た name」を置く層を、学習テーブルの葉ではなく接続キーの行にした**（§6 タスク3 の
文言からの意図的なずれ。契約への影響はなくスマホ内部の置き場所の話）。

- 理由：スマホの学習エントリ（`product_master.yayoiAccountId` 等）が指しているのは**自前の科目**で、
  ユーザーはその紐付けを**自前の科目名**を見て決めている。PC 側がスロットを作り替えても
  「肥料 → 肥料費」という判断自体は無効にならないし、この学習は弥生 CSV・らくらく CSV でも
  使い回しているので、PC 都合で消すと連携と無関係な機能が壊れる。
- 代わりに `yayoi_accounts.accountKey` / `rakuraku_tekiyou.memoKey` の行に「確定時に見えていた
  PC 側の名前」を持たせ、取込時に食い違ったら**その接続キーだけを外す**。結果として、その科目を
  経由する仕訳は全部 `matchStatus = "UnmatchedAccount"` になり、契約が求める「安全側に失敗する」を
  満たしつつ、再確認が科目1件で済む（学習 N 件を選び直させない）。

**取込でマッピングが消える経路を先に塞いだ**：`SettingsScreen.mergeYayoiAccounts` /
`mergeRakurakuAccounts` は `accountCode` 一致で既存行を `update` するため、`accountKey` を持たない
科目マスタ（旧バックアップ・CSV 由来）を取り込むとユーザーが確定したマッピングを上書きで失う。
`accountKey = account.accountKey ?: existing.accountKey` で既存値を残すようにした。
`TekiyouDictImporter` は未存在行しか insert しないので `memoKey` は無事（確認済み）。

**旧バックアップ復元時の `uuid` null 対策**：Gson はコンストラクタのデフォルト値を使わずフィールドを
null のまま残すため、`uuid` を持たない旧 JSON を復元すると NOT NULL 列に null が入って落ちる。
`withRestoredUuid()` を `importAllData` / `importReceiptData` の両方に噛ませて採番し直す。

**検証**（実機が接続できないため実行時検証は未了）：

- クリーンビルド BUILD SUCCESSFUL
- KSP 生成の `ReceiptDatabase_Impl.java` が持つ期待スキーマと、マイグレーションの CREATE 文を
  sqlite3 で突き合わせ、3テーブルとも `PRAGMA table_info` が完全一致することを確認
- `uuid` のバックフィル式（SQLite だけで UUID v4 を作る式）を sqlite3 で実行し、36桁・
  バージョン/バリアントのニブル・重複なしを確認
- `uuid` 列は `DEFAULT ''` 付きで ALTER するが、エンティティ側に `@ColumnInfo(defaultValue)` が
  無いので Room のスキーマ検証は既定値を比較しない（`MIGRATION_29_30` の `canonicalKey` と同じ形）

### 2026-09-22 第4ラウンド：PC側の回答受領 → DB v35 実装

PC側が §9-3 の依頼4件すべてに回答（`REPLY-pc-2026-09-22.md`）。**回答待ちはゼロになった。**
契約一式の同期分を編集せずコミット（`87f8abc`）。

| 依頼 | PC側の回答 |
|---|---|
| Purchase の externalId を UUID 方式に | **同意**。契約 §4 を `ocr:purchase:{rowUuid}` に書き換え済み |
| 再取込ガード（科目・note の変化で要確認） | **同意・実装済み**。借方だけでなく**貸方の accountKey も**対象 |
| Deposit は合成番号 | **同意**。ただし条件2つ（`#` 不可／同じCSVで同じ番号） |
| 往復検証の手順3 | **PC側で実施済み**。ゴールデン例は契約検証を通る（7行・違反0） |

PC側が追加で入れたもの：**重複の可能性**の検知（日付・金額・借方貸方が既存行と同じなら要確認）。
UUID の弱点＝月の入力し直しはこれで受け止めるので、**スマホ側に追加実装は不要**。

**DB v35 の内容**

1. `receipt_items.uuid`（NOT NULL・UNIQUE）＋既存行バックフィル。`ReceiptRowData` にも `uuid` を
   持たせ、保存（`saveMonthData`）とロード（`convertReceiptItemsToRows`）の両方で引き継ぐ。
   グリッドの挿入・削除は行オブジェクトごとシフトするので uuid は行の内容に付いて動き、
   「クリア」と新規空白行は新しい uuid になる
2. 預金明細の空欄の取引通番に合成番号（`x01`…）。既存の空欄行はマイグレーションで `x01` に埋める
   （UNIQUE 制約により空欄は1日1件しか存在しないので衝突しない）

**合成番号の冪等性**（PC側の条件2）は `DepositNumberAssigner` で、
「日付・摘要・金額・メモが同じ既存の合成番号行を先に再利用する」ことで満たす。
1行につき1回しか再利用しないので、内容まで同じ行が同じ日に2件あっても番号が安定する。
`DepositNumberAssignerTest`（7件）で担保。

**ついでに直したもの**：`NtaInvoiceClientTest.kt` が、2851ae1 で削除された `NtaInvoiceClient` を
参照したまま残っており、**それ以来 unit test がコンパイルできず1件も動かせない状態**だった。
消えた機能のテストなので削除。既存の `CsvUtilsTest`（14件）も動くようになった。

**PC側への次の連絡**：`vocabulary.json` の取込UI（§6 タスク4）ができたら知らせる。
往復検証の手順2（PC が本物の `vocabulary.json` を書き出して渡す）に進む。

### 2026-09-22 実機でのマイグレーション起動確認（moto g66j 5G / ZY32MD4V57）

**v33 の実データから v35 まで2段階を一度に通した。全項目パス。**
事前に `receipt_database` + `-wal` + `-shm` をセットでバックアップ（スクラッチパッドに保存）。

| 確認項目 | 結果 |
|---|---|
| 起動・クラッシュ | なし。Room のスキーマ検証も通過（不一致なら起動時に落ちる） |
| `PRAGMA user_version` | 33 → **35** |
| 新テーブル3つ | `aoirochobo_accounts` / `aoirochobo_memo_templates` / `aoirochobo_vocab_meta` 作成済み |
| 新カラム | `yayoi_accounts.accountKey`／`accountKeyName`・`rakuraku_accounts.accountKey`・`rakuraku_tekiyou.memoKey`・`receipt_items.uuid`・`general_receipts.uuid` すべて存在 |
| uuid バックフィル | 購買 73/73・レシート 20/20。**全件が一意・小文字・UUID v4形式・36桁** |
| UNIQUE インデックス | `index_receipt_items_uuid` / `index_general_receipts_uuid` とも unique=1 |
| 件数（データ消失の有無） | 購買73・レシート20・預金157・科目98・摘要101 で移行前と完全一致 |
| 預金の合成番号 | この端末には空欄の取引通番が0件だったため `x01` 埋めは no-op（実通番は `0000001` 形式で無傷） |

**uuid が保存を跨いで不変であることを実機で確認**（契約の肝）。
令和8年9月（35行）を編集モードで開いて「決定」→ ログは
`Inserted 21 items for sheet 1` / `Inserted 14 items for sheet 2` で全DELETE→全INSERTを確認。
`id` は 109〜143 → **144〜178 に振り直された**のに、35行の `uuid` は**1文字も変わらなかった**
（sheetNumber・itemNumber・productName とセットで diff し完全一致）。
＝ `ReceiptRowData.uuid` の持ち回りが実機で効いている。

未検証で残るもの：行の「挿入」「削除」で uuid が内容に付いて動くこと（ロジックは自明だが
実機では叩いていない）。`vocabulary.json` 取込UIの実装時に、伝票を1枚実際に編集して確かめる。

### 2026-09-22 第5ラウンド：vocabulary.json 取込UI（返信 §6 タスク4）

設定 > データ管理に「**AoiroChobo 科目・摘要を取り込む**」を追加。既存の「インポート」（自前DBの
バックアップ復元）とは性質が違うので、同じ選択肢に混ぜず独立した項目にした（§2-1 の方針どおり）。

- SAF（`OpenDocument` / `application/json`）でユーザーがファイルを選ぶ。自動同期はしない
- 項目のサブタイトルに鮮度を出す：「2026年度を取込済み（12日前に書き出したファイル）」
- 取込結果はダイアログで全部見せる（件数・年度・鮮度・外した紐付け・消えたキー・未マッピング数・警告）

**取込処理1〜5**（`util/AoiroChoboVocabImporter.kt`）

| # | 処理 | 実装 |
|---|---|---|
| 1 | `schemaVersion` の検証 | 2 以外と `kind` 不一致は中止。理由をダイアログに出す |
| 2 | `contentHash` 一致でスキップ | 再計算せず文字列一致だけ見る |
| 3 | `name` が変わったキーの紐付けを外す | `yayoi_accounts` / `rakuraku_accounts` / `rakuraku_tekiyou` の接続キーを NULL に。外した分は科目名つきでダイアログに列挙 |
| 4 | 消えたキーの紐付け | 専用の後始末は不要（ミラーを丸ごと入れ替えるので解決時に引き当て失敗する）。件数だけ知らせる |
| 5 | 未マッピングの科目数 | 弥生・らくらく別に件数を出す。確定はタスク5のマッピングUIの仕事 |

未知の enum 値は**弾かず**警告に留め、値はそのまま保持する（契約どおり）。
預金スロットが 1〜5 の範囲外なら警告する。

**実装中に踏んだバグ**：配列名を `memos` と書いていたが契約は **`memoTemplates`**。Gson は
知らないフィールドを黙って無視するので、**摘要が0件のまま「取り込みました」と表示された**。
実機で件数を見て気づいた。同じ取り違えが次に黙って通らないよう、`memoTemplates` が
**欠けている場合**と、`accountKey`/`memoKey`/`name` が無くて読み飛ばした行がある場合に
警告を出すようにした。

**実機確認**（moto g66j 5G・`examples/vocabulary.sample.json` を使用）

| 確認 | 結果 |
|---|---|
| 初回取込 | 科目27件・摘要12件、2026年度、12日前と表示 |
| DB | `aoirochobo_accounts` 27 / `aoirochobo_memo_templates` 12 / `aoirochobo_vocab_meta` 1行。`enumsJson` も生のまま保持 |
| 同じファイルを再取込 | 「前回と同じファイルです（contentHash が一致）」でスキップ |
| 未知の enum | `taxRate: "5"` を仕込んだファイルで警告が出て、**取込は止まらず値も保持**された |
| 預金スロット候補 | `bankSlotNo` 1=営農口座 / 2=直売口座 / 3=積立口座 を引けた（タスク7で使う） |

`AoiroChoboVocabImporterTest`（7件）で名前変化の判定を担保。実機では紐付けが1件も無い状態
（マッピングUIが未実装）なので、外す挙動そのものはユニットテスト側でしか通していない。

**端末に残っているもの**：テスト用にサンプルを取り込んだので、`aoirochobo_*` テーブルには
`contentHash: sha256:1111…` の架空スナップショットが入っている。本物を取り込めば丸ごと
入れ替わる（架空のハッシュは実ファイルと一致しないのでスキップにもならない）。

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
- 2026-09-22 第2ラウンド：PC側が指摘3点を全部修正（`accountName` 追加・§7 返品の主語限定・
  sample の `_note`）→ コミット `b93e3ec`。継続協議3件に回答し返信に §8 を追記 → コミット `0ec1bbd`
  - `accountName` の不一致を「警告」でなく「要確認」に回すPC側の判断に同意
  - Purchase の `externalId` を **UUID方式**（`ocr:purchase:{rowUuid}`）に変更することを提案
  - Deposit の通番空欄フォールバックは**ハッシュ不要**と回答（合成番号で取込バグごと解消）
  - 収入科目の税率は摘要の `taxRate` から引くで合意
  - `docs/known-issues.md` に2件追加（通帳CSV取込の無言スキップ／行挿入・削除の行番号シフト）
- 2026-09-22 第3ラウンド：**DB v33 → v34** を実装（`accountKey` / `memoKey` と確定時の名前・
  AoiroChobo ミラー3テーブル・`general_receipts.uuid`）。取込でマッピングが消える経路と
  旧バックアップ復元時の uuid null も塞いだ。クリーンビルド BUILD SUCCESSFUL
- 2026-09-22：PC側への返信に §9（第3ラウンド）を追記。v34 完了報告・`receipt_items.uuid` は
  v35 になる訂正・「そのとき見た name」を接続キーの行に置いた件と PC から見た挙動差・回答待ち4件
- 2026-09-22：`CLAUDE.md` の古い記述を実装に合わせて修正（DB v28→v34・ML Kit 前提の記述を
  Gemini に・`process()` のシグネチャ・存在しない `AccountSettingsScreen.kt` の要対応項目削除・
  docs 構成に `integration/` を追加）

### 未完了・中断した理由
- 不整合 #3（弥生CSVの列構成が購買/預金とレシートで不一致）・#4（弥生税区分文字列が
  やよい実仕様と不一致の疑い）は、弥生が現在使えず実インポート検証ができないため保留
- 2026-09-10 の未完了項目1〜4（`accountKey` 数値キーの詰め）は **2026-09-22 に解消**。
  v2 契約で `accountKey` は PC 所有の不透明文字列に確定したため、論点自体が消えた
- 2026-09-22 第3・第4ラウンド：DB v34・v35 とも実装・ビルド・ユニットテスト・**実機での
  起動確認まで完了**（v33 の実データから2段階を通し、uuid が保存を跨いで不変であることも確認）
- PC側の回答待ちは **ゼロになった**（Purchase の UUID 方式は同意を得て DB v35 で実装済み）。
  こちらから PC 側への次の連絡は「`vocabulary.json` の取込UIができたら知らせる」の1件だけ

### 次回セッションで最初にやること
科目マッピングUI（返信 §6 タスク5）を実装する：`rakuraku_accounts.searchKeyAlpha` と `aoirochobo_accounts.accountKey` の一致で初期提案を出し、ユーザーが確定して `accountKey` を埋める。実測で らくらく61件中22件は自動提案が当たるが、弥生98件は表記体系が別なので手動確定が主になる。

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
- externalId 用の `general_receipts.uuid` は DB v34 で追加・バックフィル済み（実機確認待ち）
- `searchKeyAlpha` は `rakuraku_accounts` 内で重複があり結合キーに使えない（既知化）
- **2026-09-22 追加（`docs/known-issues.md` に転記済み）**
  - 通帳CSV取込で「同一日・取引通番が空欄」の行は2件目以降が黙って捨てられる（既知のバグ・未修正）
  - JA伝票グリッドの行「挿入」「削除」は以降の行番号を全部シフトする（制約・注意事項）
