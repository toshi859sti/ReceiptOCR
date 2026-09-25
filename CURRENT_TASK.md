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
- [x] 科目マッピング UI（自動提案 → ユーザー確定）（同 タスク5）※実機確認のみ未了
- [ ] **らくらく青色申告農業版のサポート終了**（2026-09-23 方針決定）。出力・画面・学習の張り替え

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

### 2026-09-23：PC画面スクショ受領・科目マッピングUI・らくらく廃止の方針決定

**PC画面のスクショ22枚を受領**（`docs/integration/examples/AoiroChobo_2024_screens/`）。
科目5枚（全タブ）・摘要11枚。PC側から科目件数の回答も受領（`REPLY-pc-2026-09-23.md`）。

画面から確定したこと：

- **タブは `accountType` ではない**。`Capital` 6件が 資産/負債/支出 に散る
  （事業主貸→資産、事業主借・元入金・青申特別控除前の所得金額→負債、専従者給与・家計費→支出）。
  `displayOrder` は accountType 順の通し番号（Asset 1〜/…/Capital 59〜）なので**画面上の位置は導けない**
  → accountKey 6件の対応表をスマホに持つ。知らない Capital は「その他」タブに落として消さない
- 摘要画面は 帳簿タブ × 方向タブ で、「科目」列＝`counterAccountKey`。契約 §4.5 の逆引きが画面構造そのもの
- **買掛/仕入の摘要は10件・指す科目は8つだけ**（肥料費・農薬衛生費・農具費・諸材料費・種苗費・
  飼料費・動力光熱費・修繕費）＝JA購買伝票の出力に必要な科目マッピングはこれだけ
- **事業割合は摘要側にある**（電気料金40%・水道50%・ガソリン(自動車)50%）。同じ科目でも摘要で按分が
  変わるので、科目だけ合わせても仕訳は完成しない（契約 §4.7 の裏付け）
- C#/XAML は不要だった。親子は2列表示で名前の連結なし、摘要の絞り込みも §4.5 の条件以外なし

**自動提案の前提が変わった**。9/22 に「弥生は98件中16件しか当たらないので手動確定が主」と書いたが、
それは**サーチキー英字だけで突き合わせた数**だった。スクショから起こした64件の**科目名**で突き合わせると：

| 突き合わせ | 一致 |
|---|---|
| 科目名そのまま | **40 / 64** |
| ＋正規化（括弧内・中黒・末尾「等」を落とす） | **43 / 64**（農産物等・農機具等・家事消費 が追加） |

→ 弥生モードも**大半が自動提案**になる。手動が残るのは作物名スロット（水稲・インゲン・キュウリ類）と
表記が別物のもの（農薬衛生費↔農薬費、地代・賃借料↔地代家賃、建物・構築物↔建物/構築物）。

**科目マッピングUI（タスク5）を実装**（コミット `4c9e07d`）

- `util/AoiroChoboAccountMapping.kt`：タブ分け・名前正規化・3段の提案（完全一致→正規化一致→検索文字一致）
- `ui/AoiroChoboAccountMappingScreen.kt`：簿記ソフト連携メニューから開く。
  **あおいろ側の64件を主軸**に並べ、各行に弥生科目を割り当てる
- 列は `yayoi_accounts.accountKey` にあるので **1つの accountKey に複数の弥生科目**を付けられる
  （弥生「建物」「構築物」→「建物・構築物（資産）」）。この N:1 は あおいろ側を主軸にすると自然に書ける
- 一括確定は**候補が1つに決まった提案だけ**。同じ弥生科目を2つのキーが取り合わないよう、
  根拠が強いほうを優先して1回しか使わない
- 確定時に `accountKeyName`（そのとき見えていたPC側の科目名）も書く＝取込時の作り替え検知が効く
- ユニットテスト13件追加（計41件・全パス）。クリーンビルド BUILD SUCCESSFUL
- **実機未確認**（このセッションでは端末が接続されていなかった）

### 2026-09-23：らくらく青色申告農業版のサポート終了（方針決定・未着手）

ユーザー判断：**らくらくの画面はすべて差し替え、らくらく向けの出力も行わない。弥生とあおいろのみ対応。**

影響範囲（調査済み・実装はこれから）：

| 対象 | 現状 | 想定 |
|---|---|---|
| `AccountingSoftware` enum | `RAKURAKU` / `YAYOI` / `BLUE_RETURN_PREP（自作）` の3値。既定値・不正値のフォールバックとも `RAKURAKU` | `YAYOI` / `AOIROCHOBO` の2値に。既存 pref 値 `RAKURAKU` の移行が要る（64箇所で参照） |
| らくらくCSV出力 | `OutputConfirmScreen` / `GeneralReceiptOutputScreen` | 削除し、あおいろJSON（タスク8・9）に置き換え |
| `RakurakuAccountSettingsScreen` / `RakurakuTekiyouScreen` | 簿記ソフト連携メニューから到達 | 差し替え |
| `rakuraku_accounts`(61行) | `accountKey` 列を v34 で追加済み | 役目が「あおいろへの橋渡し」だけになる。弥生側で足りるなら廃止 |
| `rakuraku_tekiyou`(101行) | `product_master.kaikakeTekiyouId` が FK で参照（**学習データ**） | **要判断**（下記） |

**未判断の1点：摘要辞書と学習の扱い。**
`ProductListScreen.kt:1191` の通り、らくらくモードでは商品名→**摘要**を学習し（`kaikakeTekiyouId`）、
弥生モードでは商品名→**科目**を学習している（`yayoiAccountId`）。あおいろも「摘要が科目・税率・
事業割合を持つ」構造なので、らくらくの学習資産は `memoKey` にそのまま対応づく。選択肢：

- **A: `rakuraku_tekiyou` を残す** — `memoKey` 経由であおいろに橋渡し（v34 の設計のまま）。移行不要だが
  摘要辞書を2つ持ち続けることになる
- **B: `rakuraku_tekiyou` を廃止** — 学習を `memoKey` 直指しに張り替え、`aoirochobo_memo_templates` を
  唯一の摘要辞書にする。DB v36 の移行が要る。先に摘要マッピング（101件）が必要

B が素直だが、ユーザーの学習データを移すので**着手前に確認する**。

なお科目マッピングUI（タスク5）は**らくらく廃止の結論がどちらでも無駄にならない**。弥生CSV出力は残り、
`product_master.yayoiAccountId` も残るので、あおいろ出力には `yayoi_accounts.accountKey` が必ず要る。
らくらく側のマッピングUIは作っていない。

### 2026-09-23：B案決定 → DB v36 ＋ 摘要マッピングUI（学習の張り替え）

ユーザーが**B案**（`rakuraku_tekiyou` を廃止し、学習を `memoKey` 直指しに張り替え）を選択。

**調査で分かったこと：`rakuraku_tekiyou` を id で参照しているのは1つではなく3つ。**

| 参照元 | 列 | 内容 |
|---|---|---|
| `product_master` | `kaikakeTekiyouId`（FK） | 商品名→買掛摘要の学習 |
| `tekiyou_matching_rules` | `rakurakuTekiyouId`（FK） | 通帳摘要パターン→摘要の学習 |
| `deposit_meisai` | `overrideTekiyouId`（FKなし） | 預金明細の個別上書き |

**DB v36**：上記3テーブルに `memoKey`（＋学習2つには `memoKeyName`）を追加。

**マイグレーションでバックフィルはしない。** この時点で `rakuraku_tekiyou.memoKey` がまだ空で、
何に張り替えるべきかが決まっていないため。代わりに**摘要マッピングで1件確定するたびに、
その摘要を指している学習へ書き下ろす**（`RakurakuTekiyouDao.linkMemoKey`・1トランザクション）。
解除は逆をやる。取込で名前が変わったときの `clearMemoKey` も `unlinkMemoKey` に差し替えた
（学習側を外し忘れると、作り替えられた摘要を指したまま仕訳が Matched で出てしまう）。

**摘要マッピングUI**（`ui/AoiroChoboMemoMappingScreen.kt`・`util/AoiroChoboMemoMapping.kt`）

- 主軸は**らくらく側の摘要**（科目マッピングとは逆）。学習が指しているのがこちらなので、
  「学習N件が参照しているのに未マッピング」＝移行で失うものを数えられる。
  ヘッダに「らくらくの摘要を指したままの学習が N 件あります」を出す
- 候補は**同じ帳簿・同じ向きの中だけ**から探す。同名の摘要が帳簿をまたいで存在する
  （「米販売代金」が現金にも売掛にもある）ため
- **`direction` はお金の向きではない**。現金-入金→`Cash/In` だが**売掛-入金→`AR/Out`**（債権の解消）、
  買掛-購入→`AP/In`（債務の発生）。らくらくの (mainCategory, subCategory) は**対で**写像する
- 名前の正規化は**括弧の中身を落とさない**。「買掛支払（現金）」と「買掛支払（普通預金）」は
  別の摘要で、落とすと取り違える。落とすのは括弧の全角半角と区切り文字の差だけ
- らくらくには未払帳・振替の摘要が無い（`Unpaid`/`Transfer` はあおいろ側にだけある）。
  ピッカーでは帳簿一致を先頭に出しつつ、それ以外も選べるようにしてある

ユニットテスト14件追加（計55件・全パス）。v36の5列は KSP 生成の期待スキーマと一致（全て nullable TEXT・
既定値なし）を確認。クリーンビルド BUILD SUCCESSFUL。**実機未確認。**

**らくらく撤去の残り**（実機で移行を確認してから）：

1. `AccountingSoftware` enum を2値に（`RAKURAKU` 削除・`BLUE_RETURN_PREP`→`AOIROCHOBO` 改名・
   既定値と不正値フォールバックの変更・既存 pref 値 `RAKURAKU` の移行・64箇所の参照）
2. らくらくCSV出力の削除（`OutputConfirmScreen` / `GeneralReceiptOutputScreen`）
3. `RakurakuAccountSettingsScreen` / `RakurakuTekiyouScreen` の撤去
4. 解決経路を `memoKey` 直指しに切り替え（今は `kaikakeTekiyouId` を見ている箇所が残っている）
5. `rakuraku_tekiyou` / `rakuraku_accounts` テーブル削除（**最後**・移行完了を実機で確認してから）

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと（2026-09-23 セッション分は末尾にまとめて記載）
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
- 2026-09-22 第4ラウンド：PC側の回答一式を受領（依頼4件とも同意）。契約の同期分を編集せず
  コミット（`87f8abc`）し、**DB v35** を実装（`receipt_items.uuid` ＋ 通帳CSVの合成番号）→ `e851c95`
- 2026-09-22：**実機でマイグレーション起動確認**（v33 実データ → v34 → v35 を一度に通す）。
  件数一致・uuid 全件採番・保存を跨いだ uuid 不変まで確認 → `b4f236b`
- 2026-09-22 第5ラウンド：`vocabulary.json` の**取込UIと取込処理1〜5**を実装（§6 タスク4）。
  実機でサンプルを取り込み、`contentHash` スキップと未知 enum の警告まで確認 → `03bd7f8`。
  PC側への返信に §10 を追記（取込UI完成の連絡・実ファイルの件数を教えてほしい依頼）
- ユニットテストが `NtaInvoiceClientTest` のせいでコンパイルできず1件も動かせない状態だったのを解消。
  現在 41件（`CsvUtilsTest` 14 ＋ `DepositNumberAssignerTest` 7 ＋ `AoiroChoboVocabImporterTest` 7
  ＋ `AoiroChoboAccountMappingTest` 13）
- 2026-09-23：PC画面スクショ22枚と科目件数の回答を受領・コミット（`24da3cb`）。
  **科目マッピングUI（タスク5）を実装**（`4c9e07d`）。自動提案は科目名一致を主軸にして 64件中43件に到達
  （9/22 の「弥生は16件しか当たらない」はサーチキーだけで見た数で、前提が変わった）
- 2026-09-23：**らくらく青色申告農業版のサポート終了**をユーザーが決定（摘要辞書はB案）。影響範囲を調査して
  `CURRENT_TASK.md` に整理（実装は未着手・摘要辞書の扱いA/Bが未判断）

### 未完了・中断した理由
- 不整合 #3（弥生CSVの列構成が購買/預金とレシートで不一致）・#4（弥生税区分文字列が
  やよい実仕様と不一致の疑い）は、弥生が現在使えず実インポート検証ができないため保留
- 2026-09-10 の未完了項目1〜4（`accountKey` 数値キーの詰め）は **2026-09-22 に解消**。
  v2 契約で `accountKey` は PC 所有の不透明文字列に確定したため、論点自体が消えた
- 2026-09-22 第3・第4ラウンド：DB v34・v35 とも実装・ビルド・ユニットテスト・**実機での
  起動確認まで完了**（v33 の実データから2段階を通し、uuid が保存を跨いで不変であることも確認）
- PC側の回答待ちは **ゼロ**。こちらからの連絡も返信 §10 で済ませた
  （取込UI完成の報告＋実ファイルの科目・摘要の件数を教えてほしい、の2点）
- **往復検証の手順2はPC側待ち**：本物の `vocabulary.json` を書き出して渡してもらう段階。
  受け取ったら取り込んで件数・年度・鮮度・警告の有無を報告する
- 科目マッピングUI（§6 タスク5）が未着手のため、**「name が変わったら紐付けを外す」挙動は
  実機で通せていない**（紐付けが1件も存在しないため）。ユニットテスト側でのみ担保
- §6 の残タスク：5（科目マッピングUI）・7（預金スロット設定）・8（出力確認画面にAoiroChobo形式）・
  9（`transactions.json` ビルダー）・10（契約テスト）

### 次回セッションで最初にやること
**実機で v35 → v36 のマイグレーションと2つのマッピング画面を通す。**とくに摘要マッピングは学習の張り替えを伴うので、事前に `receipt_database` + `-wal` + `-shm` をセットでバックアップしてから、確定前後で `product_master` / `tekiyou_matching_rules` / `deposit_meisai` の件数と中身を突き合わせること。通ったら らくらく撤去の残り1〜5（上記）に進む。

### 旧「次回やること」（科目マッピングUIの実機確認・上記に統合済み）
科目マッピングUIを実機で確認する（サンプル vocabulary.json を取り込んだ状態で、提案の一括確定 → `yayoi_accounts.accountKey` が埋まること → 名前を変えたファイルを再取込して紐付けが外れることまで通す。この「外れる」挙動はまだユニットテストでしか通せていない）。あわせて、らくらく廃止の未判断事項（摘要辞書 A/B）をユーザーに確認する。

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
- externalId 用の uuid は `general_receipts`（v34）・`receipt_items`（v35）とも追加・バックフィル済み。**実機確認済み**
- `searchKeyAlpha` は `rakuraku_accounts` 内で重複があり結合キーに使えない（既知化）
- **2026-09-22 追加（`docs/known-issues.md` に転記済み）**
  - 通帳CSV取込で「同一日・取引通番が空欄」の行は2件目以降が黙って捨てられる（既知のバグ・未修正）
  - JA伝票グリッドの行「挿入」「削除」は以降の行番号を全部シフトする（制約・注意事項）
- **2026-09-22 追加（第4・第5ラウンド）**
  - 通帳CSV取込の無言スキップは**修正済み**（合成番号 `x01`…・`docs/known-issues.md` 反映済み）
  - 行番号シフトの件も**対策済み**（`receipt_items.uuid`・同上）
  - Gson は JSON に無いフィールドを黙って無視するため、**契約の配列名を1文字でも間違えると
    0件のまま「成功」になる**（`memos` と `memoTemplates` を取り違えて実際に踏んだ）。
    `docs/known-issues.md` の制約に転記済み

### 2026-09-23 追記（B案の実装分）
- **DB v36** と**摘要マッピングUI**を実装（`51bfa0b`）。らくらく摘要を1件確定するたびに、
  それを指している学習3テーブルへ `memoKey` を書き下ろす方式にした
- `rakuraku_tekiyou` を参照しているのは `product_master` だけではなく**3テーブル**だった
  （`tekiyou_matching_rules` / `deposit_meisai.overrideTekiyouId` も）。移行漏れの危険があったので
  画面に「まだ張り替わっていない学習 N 件」を常時出すようにした
- ユニットテスト計55件（`AoiroChoboAccountMappingTest` 13 ＋ `AoiroChoboMemoMappingTest` 14 ＋既存28）
- **らくらくのテーブル削除・画面撤去・enum 整理は未着手**。実機で移行を確認してからにする

### 2026-09-23 第2の訂正：弥生科目とあおいろ科目は無関係だった（設計やり直し）

ユーザーからの指摘2点で、この日に作ったものの大半が前提から崩れた。

1. **学習データはリリースビルド時に全削除する** → らくらくからの移行そのものが不要
2. **弥生の科目とあおいろの科目は全く別**。同じ商品でも弥生で A、あおいろで B になることがある
   → **1対1の対応表は作れない**

2 は既存コードの方が正しかった。`ProductListScreen.kt:1191` は最初から
「弥生モードなら `yayoiAccountId`、らくらくモードなら `kaikakeTekiyouId`」と**別々にユーザーが選ぶ**
作りで、両者を結ぶものは無い。`REPLY-phone-2026-09-22.md` §1 の「自前科目に `accountKey` を1本足して
解決時に1ホップ」という設計が、そもそも成り立っていなかった。

**さらに摘要の位置づけも訂正**（ユーザー指摘）：摘要は「相手科目・税区分・税率・事業割合」の
**不可分のセット**で、内容は変えられない。よって「摘要を選んで科目だけ上書き」はできない。
正しい順序は **科目 → その科目で摘要辞書をフィルタ → 残りはユーザーが決める**。

**相手科目・税区分・税率が同じで事業割合だけ違う摘要がある**（PC画面の預金/出金：
電気料金 動力光熱費 10% **40%** ／ 電気料金（事業専用）動力光熱費 10% **100%**）。
ここは商品名にも通帳文字列にも答えが無い＝その農家の按分方針なので、AIでは決まらない。
**学習が担当するのはまさにこの領域**。

#### 撤去したもの（コミット `7dcfcb7`）

- `yayoi_accounts.accountKey` / `accountKeyName`、`rakuraku_accounts.accountKey` / `accountKeyName`
- 科目マッピングUI（`AoiroChoboAccountMapping.kt` ＋ 画面 ＋ テスト13件）
- 摘要マッピングUI（`AoiroChoboMemoMapping.kt` ＋ 画面 ＋ テスト14件）
- 学習の張り替え機構（`RakurakuTekiyouDao.linkMemoKey` 一式）

#### 作り直した v36

学習テーブルが AoiroChobo のキーを**直接**持つ。弥生用の列とは独立で、互いに参照しない。

| テーブル | 弥生用（既存） | あおいろ用（追加） |
|---|---|---|
| `product_master` | `yayoiAccountId` | `accountKey` / `accountKeyName` / `memoKey` / `memoKeyName` |
| `tekiyou_matching_rules` | `yayoiAccountId` | 同上 |
| `general_item_master` | `yayoiAccountId` | 同上 |
| `receipt_payment_method_rules` | `yayoiAccountId` | `accountKey` / `accountKeyName`（貸方なので摘要なし） |
| `deposit_meisai` | `overrideYayoiAccountId` | `overrideAccountKey(+Name)` / `overrideMemoKey(+Name)` |

**科目と摘要を両方持つ理由**：摘要が0件の科目がある（租税公課は買掛/仕入の摘要10件に無い）。
科目を保存できないとその商品は毎回AI頼みになる。逆に食い違いは起きない——摘要は
`counterAccountKey == accountKey` のものしか選べないので、構造的に整合する。

**名前変化の検出は `AoiroChoboLinkDao` に集約**し、5テーブルを**キー単位**で横断する。
同じ科目を指す学習が何件あっても再確認は科目1件で済む。科目を外すときは摘要も道連れにする。

**フィールド列挙で新フィールドが落ちる罠を3か所で潰した**（`ProductListScreen` の商品編集、
`GeneralReceiptViewModel` のグループ既定科目変更と品目名リネーム）。記憶の
`feedback_pending_receipt_field_passthrough` と同じパターン。

**検証**：ユニットテスト28件パス。クリーンビルド BUILD SUCCESSFUL。
v36 の列は KSP 生成の期待スキーマと完全一致。**テーブル再作成は sqlite3 で実際に流して確認**
（v35 相当の表にデータを入れて実行 → 件数・id・AUTOINCREMENT の seq・parentId・インデックス名と
非ユニーク属性まで保存、`_new` の残骸なし）。`yayoi_accounts` / `rakuraku_accounts` を参照する
外部キーが無いことも生成物で確認済み。**実機未確認。**

#### AIの担当範囲（2026-09-23 整理）

摘要を当てるのは難しくない。むしろ弥生98科目より易しい：買掛/仕入の摘要は**10件**、
あおいろの経費科目でも**約24件**。加えて伝票の `給油所 / 農業機械 / 一般購買`
（`CategoryRecalculator`）が先に効き、給油所→ガソリン類購入はほぼ確定。
一度確定すれば `product_master` に学習されるのでAIは初出の商品だけ。

難しいのは3つ：①同義の摘要ペア（諸材料購入/資材購入、種苗購入/種苗費）はAIに区別できない
（ただし仕訳は同じなので実害小）②境界品目（マルチ・ヒモが諸材料費か農具費か）
③その農家固有の慣習。どれも未確定なら `UnmatchedMemo` で出してPC側が「要確認」で受ける。

#### PC側に伝えること（未送信）

- `matching-rules.md` §2 の「JA購買伝票は伝票の摘要カラム（肥料/農薬/諸材料/種苗/飼料）→科目が
  決定論的」は**実物と違う**。実際の伝票にその列は無く、取れるのは小計行の
  `一般購買 / 給油所 / 農業機械` の3分類だけ。「ほぼ完結」の見積もりの根拠が変わる
- 同 §1 は「予測するのは科目だけ、摘要は科目からの逆引き」だが、スマホ側は
  **科目を決めたあと摘要をユーザーに確定させて学習する**（事業割合だけ違う摘要があるため）。
  `transactions.json` に載るものは同じなので契約違反ではない
- `REPLY-phone-2026-09-22.md` §1（自前科目に `accountKey` を1本足す案）は**撤回**する

---

## 2026-09-23 セッション終了時の記録

### 今回完了したこと

- PC画面スクショ22枚と科目件数の回答を受領・コミット（`24da3cb` / `REPLY-pc-2026-09-23.md`）
- **DB v36** を実装（`7dcfcb7`）。学習テーブル5つが AoiroChobo の `accountKey` / `memoKey` を
  **直接**持つ形に。弥生用の列とは独立
- `AoiroChoboLinkDao` を新設し、取込時の名前変化検出を5テーブル横断・**キー単位**に集約
- v34 で入れた橋渡し列（`yayoi_accounts.accountKey` 等）をテーブル再作成で撤去
- フィールド列挙で新フィールドが落ちる箇所を3か所修正（`docs/known-issues.md` の制約に転記済み）
- PC側への返信 `REPLY-phone-2026-09-23.md` を作成（`4d5e3e1`）
- ユニットテスト28件パス・クリーンビルド成功・マイグレーションを sqlite3 で実地検証

### 今回作って撤去したもの（記録として残す）

科目マッピングUI・摘要マッピングUI・それぞれのテスト27件・学習の張り替え機構。
いずれも「弥生科目とあおいろ科目が1対1で対応する」前提に立っていて、その前提が誤りだった。
コミット `4c9e07d` / `51bfa0b` に残っているので、必要なら履歴から参照できる。

### 未完了・中断した理由

- **DB v36 は実機未確認**（このセッション中、端末が接続されていなかった）。
  テーブル再作成を含むので、次回は必ずバックアップを取ってから通す
- あおいろモードの商品編集UI（科目 → その科目で絞った摘要）は未着手。ここが次の実装本体
- `transactions.json` ビルダー・契約テストも未着手
- らくらく撤去の実作業（enum・CSV出力・画面・テーブル削除）は未着手。
  学習をリリース時に捨てる方針なので移行は不要になり、単純な削除作業になった
- 往復検証の手順2（本物の `vocabulary.json`）はPC側待ち。返信 §8 で依頼済み
- PC側への質問1件が未回答：`memoKey` を受けたとき摘要の `businessRatio` を適用するか（返信 §3）

### 次回セッションで最初にやること

実機で v35 → v36 を通す。`receipt_database` + `-wal` + `-shm` をセットでバックアップしてから、
`yayoi_accounts`（98件）と `rakuraku_accounts`（61件）の件数・id・`parentId` が保存されていること、
`PRAGMA user_version` が 36 になること、起動時に Room のスキーマ検証を通ることを確認する。

### 新たに発覚した問題・制約

- `docs/known-issues.md` の「制約・注意事項」に1件追加：エンティティを `.copy()` せず
  フィールド列挙で組み直している保存処理があり、列を足すと黙って null に戻る
- 契約内の未確定点を1件発見：`memoTemplates` は `businessRatio` を持つが、
  `vocabulary-snapshot.md` §4.7 はスマホに「100固定で出す」と指示している。
  事業割合だけ違う摘要が実在するため、PC側がどちらを使うかで実装の厳しさが変わる（返信 §3）
- `matching-rules.md` §2 の「JA購買伝票は伝票の摘要カラム→科目が決定論的」は事実誤認
  （実際の伝票にその列は無く、取れるのは小計行の3分類のみ）。返信 §4-1 で指摘済み
- **`CLAUDE.md` の「Room DB バージョン（現在 v35）」が古くなった**（実際 v36）。
  CLAUDE.md の更新はユーザー確認が必要なため未実施

---

## 2026-09-23 実機確認：DB v35 → v36（完了）

moto g66j 5G 実機で v36 ビルドを上書きインストールし、実データ入りの v35 DB を移行した。**問題なし。**

- バックアップ：`receipt_database` + `-wal` + `-shm` をアプリ停止後にセットで取得。
  `C:\Users\toshiro\GreenFrameOCR-db-backups\v35-20260923-190632\`（プロジェクト外に保存）
- `PRAGMA user_version` 35 → **36**、`integrity_check` ok、`foreign_key_check` 空
- `yayoi_accounts` 98件・`rakuraku_accounts` 61件：v36 に残る全列で**全行が移行前と完全一致**
  （id・`parentId` を含む。`sqlite_sequence` も 98 / 61 のまま）
- 撤去した `accountKey` / `accountKeyName` は移行前から全行 NULL だったので失ったデータなし
- インデックスも移行前と同じ（yayoi の `accountCode` は非ユニーク、rakuraku はユニーク）。`_new` の残骸なし
- 他テーブルの件数も不変（product_master 115 / tekiyou_matching_rules 39 / general_item_master 12 /
  receipt_payment_method_rules 3 / deposit_meisai 157 / receipt_items 73）。学習5テーブルに新列あり
- `room_master_table` の identity hash `57bdf5f4…` が KSP 生成物と一致。起動・再起動ともクラッシュなし

### 次回セッションで最初にやること（更新）

あおいろモードの商品編集UI（科目 → その科目で絞った摘要 → ユーザー確定）に着手する。

## 2026-09-23 往復検証・手順2（完了）と PC側 2通目への返信

- 本番 `vocabulary.json`（`docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260923_194016.json`）を実機に取込。
  科目64・摘要107・警告なし。`contentHash` を契約 §5 の手順で独立計算して一致
- PC側 `REPLY-pc-2026-09-23b.md` を受領（PC側がこの repo に直接コミット済み `1ecf3cd`）。
  回答済みの返信は書き換えず、新しく `REPLY-phone-2026-09-23b.md` を作成。PCへの未回答の質問はゼロ

### 実装に効く決定（商品編集UIで必ず反映すること）

- **businessRatio は B**：PC は `memoKey` が解決できたら**摘要側の事業割合を仕訳に入れる**。
  スマホが送る値は 100 固定のままでよい
- よって**事業割合だけ違う摘要は自動で選ばない**（帳簿の金額を守る唯一の防壁）。判定：同じ
  `ledgerType × direction × counterAccountKey × taxRate` の中で `businessRatio` が1つでも違えばその組は自動選択禁止。
  本番データでは Cash/Out の動力光熱費（8件）と雑費（4件）の2組。**AP には0組**なので JA 購買は自動選択してよい
- **買掛の摘要は本番で6件**（肥料・農薬・農具・諸材料・種苗・飼料）。`給油所`＝動力光熱費と修繕費は
  科目だけ確定して `memoKey = null`（UnmatchedMemo）。摘要件数を固定で作り込まない
- 摘要候補の絞り込み：`Purchase`→`AP`／`Receipt` クレカ→`Unpaid`／`Receipt` 現金→`ledgerType ∈ {Cash,Bank}` かつ
  `showInCash`／`Deposit`→`ledgerType ∈ {Cash,Bank}` かつ `showInBank`（`ledgerType == "Bank"` 単独で絞ると0件になる）

### 次回セッションで最初にやること（更新）

あおいろモードの商品編集UI（科目 → その科目で絞った摘要 → ユーザー確定）に着手する。上の4点を仕様として入れる。

## 2026-09-23 あおいろ科目・摘要の閲覧画面（完了）

- 入口：メニュー「簿記ソフト連携」→「あおいろ帳簿／勘定科目・摘要辞書」（`AoiroChoboVocabularyScreen.kt`・閲覧専用）
- 科目は資産/負債/収入/支出/資本（未知の区分は「その他」）、内訳科目は親の直下。摘要は PC と同じ11タブ
- 判定ロジックを `util/AoiroChoboMemoRules.kt` に分離（`MemoTab`＝帳簿タブの絞り込み、
  `ratioSensitiveMemoKeys`＝自動で選ばない組）。商品編集UIでもこれを使う
- テスト7件追加（本番 vocabulary.json を入力にした2件を含む）。全35件パス・実機で表示確認済み
- 注意：`AoiroChoboVocabDao.getMemoTemplatesFor(ledgerType, direction)` は ledgerType で絞るので
  預金には使えない（常に0件）。現在呼び出し元なし。商品編集UIでは `MemoTab` を使うこと

---

## 2026-09-23〜24 セッション終了時の記録

### 今回完了したこと

- **DB v35 → v36 を実機で確認**（データ保存・スキーマ検証とも問題なし。バックアップは
  `C:\Users\toshiro\GreenFrameOCR-db-backups\v35-20260923-190632\`）
- **往復検証の手順2**：本番 `vocabulary.json`（64科目・107摘要）を実機に取込、`contentHash` 一致
- PC側 `REPLY-pc-2026-09-23b.md` を受領し、`REPLY-phone-2026-09-23b.md` で返信。**PCへの未回答の質問はゼロ**
- businessRatio は **B**（PC が摘要側の事業割合を使う）に確定 → 「自動で選ばない組」の規則を実装
- あおいろ科目・摘要の**閲覧画面**を追加（`AoiroChoboVocabularyScreen.kt` ＋ `util/AoiroChoboMemoRules.kt`・テスト7件）

### 未完了・中断した理由

- **閲覧画面を PC の科目画面に似せる作り直し**：ユーザーに案を出したところで終了（返事待ち）。案の中身：
  - タブを PC と同じ4つ（資産・負債・収入・支出）に。Capital 6件は対応表で振り分け
    （`zigyounusikas`→資産、`zigyounusikari`/`motoire`/`kouzyo`→負債、`senzyuusya`/`kakei`→支出、未知は「その他」）。
    対応表は 7dcfcb7 で消した科目マッピングUIに一度あった。PC側も 23b §4 で了承済み
  - 列：資産・負債＝グループ｜科目名｜内訳科目名｜検索文字、収入・支出＝グループ｜科目名｜検索文字｜
    有効な課税区分（allowsTaxable/NonTaxable→すべて/課税のみ/課税以外）｜既定の課税区分
  - グループ名は先頭行のみ。名前ありは薄緑・なしは灰色（PC と同じ）
  - **表記の誤り**：NotApplicable を「対象外」と出しているが PC は「**不課税**」。直す
  - 期首残高・空き行・支出の「経費/(任意)経費」グループは JSON に無いので出せない（2026本番は groupName が空）
  - 摘要タブも PC の摘要画面に寄せるかはユーザー判断待ち
- 商品編集UI（科目 → 摘要）・`transactions.json` ビルダー・契約テスト・らくらく撤去は未着手

### 次回セッションで最初にやること

閲覧画面を PC 科目画面風に作り直すか、ユーザーに上の案の可否を確認してから着手する（摘要タブも寄せるかも聞く）。

### 新たに発覚した問題・制約

- `getMemoTemplatesFor` は預金に使えない（`docs/known-issues.md` の「制約・注意事項」に転記済み）
- 2024年の再現データと2026年の本番データで摘要・グループ名が違う（買掛の摘要 10件→6件、支出の groupName が空）。
  スクショを仕様の根拠にするときは年度に注意

---

## 2026-09-25 閲覧画面を PC の科目・摘要画面風に作り直し（実機確認のみ未了）

- 科目：タブを PC と同じ 資産・負債・収入・支出（未知の区分があるときだけ「その他」）。資本6件は accountKey で振り分け
  （`util/AoiroChoboAccountRules.kt`）。列は資産・負債＝グループ｜科目名｜内訳科目名｜検索文字、
  収入・支出＝グループ｜科目名｜検索文字｜有効な課税区分｜既定の課税区分。グループ名は続く間の先頭だけ・
  名前あり薄緑／なし灰色。システム科目の科目名は薄紫。表は横スクロール・見出し固定
- 表記の誤りを修正：NotApplicable・税率 na は「**不課税**」（旧「対象外」）
- 摘要：上段 現金・預金・売掛・買掛・未払・振替 ＋ 下段 入金/出金 等。列は PC と同じ
  （摘要名｜検索文字｜科目｜税率｜事業割合｜預金と共有／現金と共有）、振替は借方・貸方の各3列。「要確定」は摘要名の横に残した
- テスト5件追加（`AoiroChoboAccountRulesTest`・本番 JSON の64科目が4タブに漏れなく出ることを含む）。全テストパス・ビルド成功
- 実機未接続のため**見た目の確認は未了**

### 支出の「経費」グループ名が出ない件（ユーザー指摘・未解決）

- 科目そのものは揃っている（支出タブ26件：経費22・繰入額2・専従者給与・家計費）。欠けているのは
  **グループ欄のラベル「経費」「(任意) 経費」だけ**
- 原因：`vocabulary.json` の `groupName` が経費科目では全部 null。PC は画面上このラベルを `GroupName` 列ではなく
  科目マスタの枠の構成から描いている模様。2026 本番データでは通信費・作業委託料・水利費も `isSystem=true` なので、
  スマホ側で「(任意)」を見分ける手がかりが JSON に無い（`displayOrder` は契約上パース禁止）
- 対応案：PC 側に「画面に出しているグループ名をそのまま `groupName` に入れてほしい」と頼む（minor で済む）。
  スマホは `groupName` をそのまま出す作りにしてあるので、PC が出せば**スマホ側の変更なしで表示される**

### 次回セッションで最初にやること

実機をつないで閲覧画面の見た目を確認する（`displayOrder` 変更に合わせて PC から書き出し直した vocabulary.json を取り込み直してから）。

### 2026-09-25 追記：PC側へ groupName の依頼を送付

- `docs/integration/REPLY-phone-2026-09-25.md` を作成。画面のグループ名を `groupName` に入れてほしいと依頼
- PC 画面と照合した結果、欠けているのは経費だけでなく4種類：**経費**（19件）・**(任意) 経費**（通信費・作業委託料・水利費）・
  **資本**（元入金・青申特別控除前）・**繰入額**の専従者給与（これが無いとスマホで繰入額が3つに割れて見える）
- `groupName` の意味を広げるか、別キー（`displayGroup` 等）にするかは PC に選んでもらう → **PC の回答待ち**

### 2026-09-25 追記：PC の回答（displayGroup）を受けて DB v37

- PC は `groupName` を変えず、**別キー `accounts[].displayGroup`** で画面のグループ名を出した（契約 minor（5）・
  `REPLY-pc-2026-09-25.md`）。依頼の表と違う点：育成費用は「経費」、営農口座・直売口座は null
- スマホ：`aoirochobo_accounts.displayGroup` を追加（**DB v36 → v37**・`MIGRATION_36_37`）。閲覧画面のグループ欄を
  `displayGroup` に切り替え。本番の新ファイル `docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260925_120800.json`
  （`contentHash` sha256:05ba6bac…34bb）で支出タブが 経費／(任意) 経費／経費／繰入額／なし になることをテストで確認
- **既存 DB は displayGroup が null のまま**。新しいファイルを取り込み直せば埋まる
- CLAUDE.md の DB バージョン表記を v37 に更新済み（ユーザー確認済み）

### 次回セッションで最初にやること（更新）

実機で v36 → v37 の起動確認 → 新しい vocabulary.json（20260925_120800）を取り込み直す → 閲覧画面の見た目を確認する。

## 2026-09-25 実機確認：DB v36 → v37 と閲覧画面（完了）

- moto g66j 5G（ZY32MD4V57）。更新前の DB を `C:\Users\toshiro\GreenFrameOCR-db-backups\v36-20260925-123547\`（本体＋wal＋shm）に退避
- v37 へのマイグレーション：起動・画面表示とも問題なし。`PRAGMA user_version` = 37、科目64件そのまま
- 新しい `vocabulary.json`（20260925_120800）を取込：科目64・摘要107・警告なし・学習の外れなし
- 閲覧画面を PC のスクショと突き合わせて確認：
  - 支出：経費／(任意) 経費／経費（雑費・育成費用）／繰入額（専従者給与・貸倒引当金繰入）／灰（家計費）で PC と一致
  - 資産：内訳科目・償却資産の薄緑・灰色の空欄とも一致。摘要：現金/出金の列・要確定、振替の借方/貸方の列とも一致
- **見つけて直したバグ**：タブを切り替えると前のタブのスクロール位置のまま途中から表示された → `PcTable` に `scrollKey` を足して
  タブごとに先頭へ戻す。実機で直ったことを確認
- 気づいたこと（未対応）：支出タブの「既定の課税区分」列は画面の右端で切れていて、横スクロールしないと見えない。
  「要確定」の印が付いた摘要は名前が2行に折り返す

### 次回セッションで最初にやること

あおいろモードの商品編集UI（科目 → その科目で絞った摘要 → ユーザー確定）に着手する（2026-09-23 の「実装に効く決定」4点を仕様として入れる）。
