# 連携契約 CHANGELOG

`schemaVersion` は `vocabulary.json` と `transactions.json` で共通に上げる。
破壊的変更のときだけ整数を +1 する。フィールド追加（前方互換）は版を上げず、
本ファイルに「minor」として記録する。

---

## schemaVersion 2 — 2026-09-22 minor（`accountName` を追加・**スマホ側の回答を反映**）

`schemaVersion` は据え置き（**2 のまま**）。前方互換の追加と文言修正のみ。

スマホ側の回答 [REPLY-phone-2026-09-22.md](REPLY-phone-2026-09-22.md) を受けて、
指摘された 3 点を直した。

### 変更 1：`entries[].debit/credit.accountName` を追加（穴だったものを埋めた）

2026-09-13 改訂（変更 4）で「PC 側が `accountName` のエコーを検証に使う」と決めたのに、
`transaction-import.md` §3 のスキーマにも `examples/` にも `accountName` が**存在しなかった**。
`accountKey` を引退させなくなった以上、これが古いスナップショットからの取込を検知する
唯一の機構なので、穴のまま残せない。

- 任意フィールド。値は「そのマッチングを決めたときに見えていた科目名」。
  `accountKey` が `null` なら `null`。**送れるときは必ず送ること。**
- 検証は `memoName` と同じ：入っていれば現在名と突き合わせる。**不一致はエラーにしない**
  （ファイルは通る）が、**その行は「要確認」に回す**。スマホ側の提案は「警告どまり」だったが、
  警告だけだと `matchStatus = "Matched"` のまま一括確定を素通りしてしまい、`accountName` を
  足した意味（キーは生きているのに中身が別の科目）が消えるため、§4.6 の当初方針を採った。
  **スマホ側の実装には影響しない**（送る内容は同じ）。
- `examples/transactions.sample.json` にも入れた。
- PC 側実装：`TransactionsFile.EntrySide.AccountName`・`TransactionsFileValidator`（`accountKey` が
  `null` なのに `accountName` が入っていれば違反）・`ImportTransactionsUseCase.StaleNameWarnings`
  （科目・摘要ともキー単位でまとめて警告）。**`memoName` 側の突き合わせも同時に実装した**
  （§11 に書いてあったが未実装だった）。
  「要確認」に回すために `ImportedTransaction` へ `DebitAccountName` / `CreditAccountName` /
  `StaleNameDetected` を追加（マイグレーション `AddImportedTransactionNameEcho`）。
  `NeedsReview` が拾い、一覧の「状態」列には**「名前が変わっている」**と出る。

### 変更 2：`transaction-import.md` §7 の返品の主語を限定

「元データが負なら借方／貸方を入れ替える」が `Deposit` にも掛かって読めていた。
**`Deposit` の出金は返品ではなく通常の資金移動**で `meta.isReturn = false`、借方／貸方は
§5 の表で入金／出金として既に分岐している。主語を `Purchase` / `Receipt` に限定した。
（2026-09-10 のスマホ側指摘が未反映のままだった。）

### 変更 3：`examples/vocabulary.sample.json` の `_note` が旧規約のままだった

「科目を作り替えると新しい `accountKey` が採番され、旧キーはこのファイルから消える」は
2026-09-13 改訂で**撤回済み**の内容。ゴールデン例を先に読んだ実装者が逆の実装をする危険が
あったため、現行（キーは据え置き・`name` だけ変わる／消えるのは無効化されたものだけ）に直した。

### スマホ側の回答で消えた宿題

- **`contentHash` の正規化仕様の突き合わせは不要になった。** スマホは再計算せず、
  受け取った値を `transactions.json` に**転記するだけ**。PC 側は自分で計算した現在値と
  比べるので（`ExportVocabularyUseCase.ComputeCurrentContentHashAsync`）、
  両側で正規化のバイト一致を保つ必要がそもそも無い。
- **`vocabulary.json` の取込経路**：スマホの 設定 > データ管理 に専用項目を追加。SAF で選択、
  Room の `aoirochobo_*` 3 テーブルへ保存（DB v33 → v34）。1 年度分のみ保持。自動同期はしない。
- **預金スロット**：スマホ設定で**固定**。既定 `1`（`0` は撤回）。設定 UI は
  `ledgerAffinity == "Bank" && bankSlotNo != null` の科目を**科目名で選ばせて**内部で番号を持つ。
  `vocabulary.json` 未取込のときは AoiroChobo 形式の出力自体をブロックする。
- **9/10 の §A（自前科目 → PC 科目の接続キーが無い）は取り下げ**。`rakurakuAccountCode` /
  `yayoiAccountCode` / `aliases` の追加依頼は消えた。スマホ側が自前マスタに `accountKey` 列を
  1 本足し、らくらくのサーチキー英字を**初期提案のヒント**にしてユーザーが確定する方式で閉じる
  （実測：27 件中 22 件が自動一致）。**契約フィールドの追加は無し。**

---

## schemaVersion 2 — 2026-09-14 minor（**PC 側の取込を実装**・契約フィールドの変更なし）

`schemaVersion` は据え置き（**2 のまま**）。JSON の形は変わらない。

これまで「実装予定」だった取込側（`transactions.json` の読み込み）が動くようになった。
導線はホーム →「スマホ連携」→「取引ファイルを取り込む」。

**スマホ側に関係する挙動**

- **契約違反は 1 件でもあればファイルごと中止する**（§11 の検証項目）。部分取込はしない。
  画面にはどの行のどの項目が問題かを行番号つきで出すので、報告があればそれを見てほしい。
  よくある違反：`entryDate` が空／実在しない日付、`amount` が 0、`externalId` の重複、
  `matchStatus=UnmatchedMemo` なのに `memoKey` が入っている。
- **`matchStatus != "Matched"` の行は自動で帳簿に入らない**。`UnmatchedMemo` / `Ambiguous` も
  含めてユーザーの確認を通す（§9 のとおり）。スマホ側は迷ったら素直に `Ambiguous` /
  `UnmatchedMemo` を返してよい——PC 側が拾う。
- **`ledgerType` は省略してよい**。PC が借方・貸方の科目の `ledgerAffinity` から推定する（§5）。
  実装・検証済み。
- **`vocabulary.contentHash` が一致しないときは警告を出すが取込は止めない**（§11 のとおり）。
- **`memoName` は照合にも表示にも使っていない**。帳簿に入る摘要名は当年度マスタの現在名。

---

## schemaVersion 2 — 2026-09-14 minor（`taxRate` に `"1"` 追加・**PC 側の書き出しを実装**）

`schemaVersion` は据え置き（**2 のまま**）。前方互換の追加のみ。

### 変更 1：`enums.taxRate` に `"1"`（食料品の軽減税率 1%）を追加

2027 年から食料品に限り軽減税率が 1% に引き下げられる予定のため、AoiroChobo 側で
先に選べるようにした。コードは `"1"`、AoiroChobo の表示は「1%軽」。

- `vocabulary.json` の `enums.taxRate` は
  `["10", "8", "1", "8_old", "non", "na", "men"]` になる。
- `transactions.json` の `debit.taxRate` / `credit.taxRate` に `"1"` が来得る。
- **スマホ側の対応**：未知の値として弾かないこと。当面は AoiroChobo 側で入力するだけなので
  スマホが `"1"` を**出す**必要はないが、`vocabulary.json` の摘要が `taxRate: "1"` を
  持ち始めるので、**読めること**は要る。
- 変換表は [README.md](README.md) §4。

### 変更 2：`vocabulary.json` の書き出しを実装した（AoiroChobo 側）

契約フィールドの変更なし。これまで「実装予定」だった発行側が動くようになった。

- 導線：ホーム →「スマホ連携」→「科目・摘要を書き出す」
- 実装：`ExportVocabularyUseCase`（`AoiroChobo.Core/UseCases/Integration/`）
- [vocabulary-snapshot.md](vocabulary-snapshot.md) §6 の検証項目は、生成コードとは**独立に
  実装したチェッカー**で全項目パスを確認済み（`contentHash` も別実装で再計算して一致）。
- 仕様に対する実装上の決定が 1 つ：**摘要が参照している科目が無効化されていた場合、
  その科目参照は `null` で書き出す**。§6 が「`counterAccountKey` は `accounts` 内に存在する
  `accountKey`」を要求しており、無効科目は `accounts` に出さない（§4.2）ため、
  そのまま出すと契約違反になる。書き出し画面には件数を警告として表示する。

---

## schemaVersion 2 — 2026-09-13 改訂（作り替え規約の見直し・**契約フィールドの変更なし**）

`schemaVersion` は据え置き（**2 のまま**）。JSON の形は 1 バイトも変わらない。変わるのは
**`accountKey` / `memoKey` の意味論と、スマホ側が学習を外す合図**なので、
**スマホ側の実装変更が必要**。

### 発端：科目・摘要は年度ごとに別の実体だった

`DuplicateAccountsAndMemoTemplatesUseCase` は年度繰越で `Account` / `MemoTemplate` を
**まるごと別の行として複製する**（同 UseCase の冒頭コメント：*"each fiscal year owns its own
Account/MemoTemplate rows, which keeps master-data edits and closed years from ever affecting
each other"*）。したがって **2026 年で科目名をどう変えても 2025 年のデータは物理的に変わらない**。

2026-09-12 版の設計は「作り替えると過去の記録が遡って書き換わる」を前提に組まれていたが、
これは当年度の中にしか当てはまらない。前提が崩れたので規約を組み直した。

### 変更 1：`accountKey` は作り替えでも据え置く（採番し直さない）

| | 2026-09-12 | **2026-09-13** |
|---|---|---|
| `accountKey` が指すもの | その科目が表す**概念** | **科目マスタ上のスロット** |
| 作り替えたとき | 新しいキーを採番し、旧キーはファイルから消える | **据え置き。`name` だけ変わる** |
| `AccountKeyRegistry` | 年度外テーブルを新設 | **作らない**（キーを引退させないので不要） |

```
2025 年度 :  { "accountKey": "acct-0007", "name": "研修費" }
2026 年度 :  { "accountKey": "acct-0007", "name": "水利費" }   ← 同じキー・名前だけ変化
```

キー採番の目的は「年度をまたいでキーを引く処理が黙って 2 概念を融合するのを防ぐ」ことだったが、
その経路 5 本すべてに個別の対処が決まったため不要になった——期首残高・期首棚卸は PC 側が
**変更・削除そのものを禁止**／`TransactionClip` は**削除**／スマホ学習は**下記**。

### 変更 2：学習を外す合図が「キーの消失」から「`name` の変化」へ（**スマホ側の実装変更**）

- **主機構（新）**：学習を保存するとき、そのとき見えていた `name` も一緒に持つ。新しい
  `vocabulary.json` で同じ `accountKey` / `memoKey` の `name` が（正規化しても）変わっていたら、
  **その学習を外す**（未設定に戻す）。2026-09-12 版で「保険」として書いていた `name` 差分検知を
  主機構に昇格させ、扱いを「要再確認」から「外す」に強めたもの。

  ```
  学習：電話代 → acct-x7（そのとき見た name「通信費」）
  新ファイル：acct-x7 の name が「研修費」  →  学習を外す  →  電話代は未設定
  ```

- **既存ルールも残る**：`vocabulary.json` に存在しないキーの学習は自動マッチに使わない。
  作り替えでキーが消えることは無くなったが、**科目・摘要の無効化**では消えるため。
- 打ち間違いの修正でも学習が外れるが、次に同じレシートを撮ってユーザーが選び直せば再学習する
  ＝安全側に失敗する。

### 変更 3：`memoKey` の書き方を訂正（意味は同じ）

「改名では不変／**作り替えでは新規採番**」という表現を改める。摘要の作り替えは既存行を書き換えず
**新しい行を作る**よう誘導するので、新しい行が自然に新しい `memoKey` を持つだけで、
**既存キーを振り直すわけではない**。実装上の挙動は 2026-09-12 版と同じ。

なお摘要は `accountKey` と非対称になる（`accountKey` ＝スロットに 1 対 1・作り替えでも据え置き／
`memoKey` ＝行に 1 対 1・作り替えは新しい行）。摘要はスロット制でなく無制限に追加できるため。

### 変更 4：PC 側が `accountName` / `memoName` のエコーを検証に使う

キーを引退させなくなったので「キーが消える」という検知の合図が無くなる。しばらく同期していない
スマホは `acct-x7 = 通信費` のつもりで送ってくるが、PC 側ではもう `acct-x7` は研修費で、
キーが有効なぶん**そのまま通ってしまう**。

対策として PC 側は、`transactions.json` が既に持っている `accountName` / `memoName`（人間可読の
エコー）を**現在名と突き合わせ、食い違っていたら「要確認」に回す**。受け皿は `ImportedTransaction`
（[transaction-import.md](transaction-import.md) §9）。

**スマホ側への要求**：`accountName` / `memoName` は任意フィールドのままだが、**送れるときは必ず
送ること**（この検証が効かなくなるため）。値は「そのマッチングを決めたときに見えていた名前」。

### 変更 5：確認ダイアログは科目・摘要とも一切出さない（PC 側 UX・契約影響なし）

同日中の再決定。摘要の相手科目・税率・事業割合を変えたときの 3 択ダイアログ案も撤回し、
**科目・摘要を通じて確認を出さない**ことにした。規約は 1 行になる：

> 名前でも相手科目でも税率でも、**変えたらその年の分はすべてそう変わる**。別のものにしたいなら新しく作る。

撤回理由は、書き換えない選択肢を残すと**旧行を消せなくなる**ため（既存仕訳が旧行を指したままで、
摘要表示が `MemoTemplateId` 経由なので `IsActive = 0` にしても削除できない）。辞書に死んだ行が溜まり、
帳簿には辞書に無い摘要が並ぶ。スマホ側への影響は無い（`memoKey` の性質は変わらない）。

### スマホ側の対応チェックリスト

- [ ] 学習エントリに「そのとき見た `name`」を保存する
- [ ] スナップショット取込時に `name` の変化を検出して該当学習を外す
- [ ] `accountName` / `memoName` を `transactions.json` に必ず載せる
- [ ] `keyRevision` を参照しているコードが残っていないか確認（2026-09-12 に廃止済み）

---

## schemaVersion 2 — 2026-09-12（マスタ同期のスキーマ見直し・**破壊的変更**）

PC 側のコードと突き合わせた設計レビューの結果、schemaVersion 1 の前提が 3 点崩れていたので改訂。
**スマホ側のレビュー・実装変更が必要。**

### 破壊的変更

- **摘要に一意キー `memoKey` を導入**（`vocabulary.memoTemplates[].memoKey`・
  `transactions.entries[].memoKey`）。schemaVersion 1 の「摘要は一意キーを持たず `memoName`（＝辞書の
  `name`）で指す」は**撤回**。理由：`name` はキーとして成立していない。
  - 摘要登録画面の新規行は既定名が「新規摘要」、コピー行は「◯◯（コピー）」。DB に
    `(LedgerType, Direction, Name)` の一意制約が無く、**同名の行が普通に作れる**。
  - `JournalEntry.MemoTemplateId` は実在の外部キーで、取込時に名前から一意に引けないと解決できない。
  - 摘要を改名すると、スマホ側スナップショットの `memoName` が一斉に引けなくなる。
  - → `memoName` は**任意・人間可読のエコー**に格下げ（PC は照合に使わない）。`memoSearchKey` は廃止。
  - 実体は `MemoTemplate.MemoKey`（Phase 4・`(FiscalYearId, MemoKey)` UNIQUE）。
  - `memoKey` は **改名では不変／作り替えでは新規採番**＝`accountKey` と同じ性質。当初は
    「摘要はスロット制でないので作り替えは起きない」としていたが、AoiroChobo では**摘要の相手科目を
    変えるとその摘要で登録済みの仕訳の科目がまとめて書き換わる**（意図的な一括修正機能）ため、
    既存行の転用は過去の帳簿を改変する。PC 側は作り替えを「無効化＋新しい行の作成」に誘導する
    （`vocabulary-snapshot.md` §4.8「摘要の変更と `memoKey`」）。契約フィールドの変更は無し。
- **`accounts[].keyRevision` を廃止**。科目の「作り替え」は世代番号ではなく
  **新しい `accountKey` の採番**で表す（`vocabulary-snapshot.md` §4.6 を全面改訂）。
  - 理由：同じキーが年度によって別概念を指すと、**年度をまたいでキーを引いている PC 側の処理が
    黙って 2 つの概念を融合させる**（年度非依存テーブルの `TransactionClip`、棚卸の期首引き継ぎ、
    期首残高の引き継ぎ）。エラーにならず数字だけ間違う。キーを新しくすれば安全側に失敗する。
  - スマホ側は「**`vocabulary.json` に無くなったキーの学習は使わない**」の 1 ルールでよくなる
    （無効化された科目とまったく同じ扱い）。`(accountKey, keyRevision)` の複合キーは不要。
  - 併せて「任意科目の転用は禁止」案は**却下**（任意スロットは有限＝任意経費 4・田畑 6 等しかなく、
    作り替えは現実に必要）。代わりに PC 側が作り替え可能条件（当年度の仕訳なし・残高 0・棚卸なし）を
    判定する。
- **`bankSlotNo` の有効値を `1`〜`5` に限定**。schemaVersion 1 の「`0` = 親『普通預金』」は**実装に存在しない**
  （親は見出し科目で `BankSlotNo = null`、それ自体の預金出納帳が無い）。`vocabulary-snapshot.md` §4.3 と
  `transaction-import.md` §6 が矛盾していた点も解消。

### 追加・明確化（非破壊）

- `vocabulary-snapshot.md` §4.8 新設：`memoKey` の性質（`accountKey` と対称）。
- `vocabulary-snapshot.md` §4.4：`accountKey` は「概念に 1 対 1」「再利用しない」を明記。採番は
  年度外の `AccountKeyRegistry` が持つ（`(FiscalYearId, AccountKey)` の UNIQUE は年度内一意しか
  保証しないため、年度内の最大値から採番すると引退キーを別概念へ再発行してしまう）。
- 無効化した科目・摘要は翌年度に複製されない＝その年度でキーの系譜が終わることを明記（README §2）。
- `transaction-import.md` §10：PC 側は**取込ステージングテーブル `ImportedTransaction`** に全行を保存し、
  確定した行だけ `JournalEntry` 化する。`JournalEntry` の科目列は非 NULL の外部キーなので、
  `accountKey = null`（`UnmatchedAccount`）の行をそのまま仕訳にできないため。「要確認」の状態も
  このテーブルが保持する。
- `JournalEntry.ExternalId` に UNIQUE 制約を張るので、再取込の「別行として追加」は `#2` `#3` の
  連番サフィックスを付ける（`transaction-import.md` §10）。
- `contentHash` の memoTemplates ソートキーを `ledgerType,direction,searchKey,name` →
  `memoKey` に変更（一意なので同点処理が不要になった）。
- 決定表に K2（摘要参照キー）・O（未解決行の受け皿）を追加、D・I・K・N を更新。

### PC 側の実装依存（Phase 4・schemaVersion 1 からの差分）

- `MemoTemplate.MemoKey` 追加＋`(FiscalYearId, MemoKey)` UNIQUE。年度複製で引き継ぐ。
- `Account.KeyRevision` は**追加しない**。
- `AccountKeyRegistry`（`AccountKey` / 初回年度 / 最終名称 / 引退年度 / `supersededBy`）新設。
  `MemoKey` の採番も同じ仕組み。
- `Account.AccountKey` のバックフィルは**年度横断 1 パス**（系譜単位に 1 キー）。年度ごとに
  一意判定すると、同じ論理科目が年度によって別キーになり「年度非依存で安定」が崩れる。
- `ImportedTransaction`（取込ステージング）新設。`JournalEntry.ExternalId` を UNIQUE 化。

---

## schemaVersion 1 — 2026-09-10（初版・ドラフト）

- `docs/integration/` 新設。契約一式を初出。
- 決定事項：
  - マッチングはスマホ担当（PC はクラウド AI を持たない）。
  - マスタ（勘定科目・摘要辞書）は AoiroChobo 所有。PC→スマホへ `vocabulary.json` を発行。
  - スマホ→PC の取引データは **JSON**（`transactions.json`）。CSV 案は取り下げ。
  - 科目参照は **`accountKey`**（不透明・不変・年度非依存・年度内で一意）。`Account.Code` は
    DB 制約のない検索用文字列なので契約キーにしない（2026-09-10 変更）。
  - 日付は西暦 ISO。金額は正の整数・返品は Dr/Cr 入替。
  - `externalId` スキーム：`ocr:purchase:…` / `ocr:deposit:…` / `ocr:receipt:{uuid}:{index}`。
- **ドラフト内の調整（2026-09-10・版は据え置き）**：
  - `vocabulary.json` の `Account` / `MemoTemplate` 出力を、テーブルの全列と 1:1 対応するよう整理
    （`vocabulary-snapshot.md` §4.1 / §4.2 に対応表を追加）。
  - `accounts[]` に `isSystem` を追加。
  - `memoTemplates[]` に `creditBusinessRatio`（Transfer 貸方側の事業割合）・`displayOrder`・`isPreset` を追加。
  - 摘要マッチングは「商品名→科目を先に解決 → 相手科目で `memoTemplates` を逆引き」を推奨手順として明記。
  - `accounts[].code` → `accounts[].searchKey` に改名（`memoTemplates[].searchKey` と対。実体の
    `Account.Code` も Phase 4 で `Account.SearchKey` に改称される）。
  - `accounts[]` に `ocrRoleExpenseDebit` / `ocrRoleDepositCounter` を追加（AI マッチングの候補フィルタ用。
    `vocabulary-snapshot.md` §4.5）。実体は `Account` の同名 2 カラム（Phase 4）。摘要側は既存スコープ
    フィールドで絞るため新フラグなし。
  - `accounts[]` に `keyRevision`（整数・既定 1）を追加。科目の「作り替え」対策（`vocabulary-snapshot.md` §4.6）。
    `accountKey` はスロットの系譜を指すため、任意科目を別用途に転用したときだけ +1。スマホは学習を
    `(accountKey, keyRevision)` で持つ。実体は `Account.KeyRevision`（Phase 4）。
  - `vocabulary-snapshot.md` §4.7 追加：科目以外の項目（税率・事業割合・インボイス・摘要）の出所を整理。
    商品名から予測できるのは科目だけ。税率＝科目の `defaultTaxCategory` フォールバック／事業割合＝スマホは
    `100` 固定（按分は PC 年末）／インボイス＝レシート現物の登録番号検出／摘要＝自由文字列。
    `memoTemplates` の逆引きは「記帳バンドル取得」から「摘要ラベル候補出し」に格下げ。フィールド追加なし。
  - `matching-rules.md` 新設：スマホ側マッチングエンジンの設計（ルールの型・照合パイプライン・
    「確定例 → ルール再コンパイル」方式・確信度段階・LLM 境界）。通帳/JA伝票は LLM なしで完結。
    契約フィールドの変更なし（スマホ側の実装指針）。
  - **`memoName` は閉じた語彙**（決定：2026-09-10）：`memoName` に入れてよいのは
    `vocabulary.memoTemplates[].name` のいずれか、または `null` のみ。スマホが文字列を生成しない。
    逆引き 0 件 → `memoName = null`＋`matchStatus = "UnmatchedMemo"`（従来は「フリーテキストのまま」＝廃止）。
    生テキスト（商品名・但し書き・通帳メモ）は `note` へ。PC は `null` の行を「要確認」で辞書選択／新規登録
    させる。`transaction-import.md` §9「memoName は閉じた語彙」／`vocabulary-snapshot.md` §4.2・§4.7。
- **PC 側の実装依存（Phase 4）**：
  - `Account.AccountKey` カラム追加＋`(FiscalYearId, AccountKey)` UNIQUE。`Code` が非空かつ年度内で一意なら
    その値を引き継ぎ（`genkin` `bank3` 等）、空・重複のみ `acct-<英数字>` を採番。
  - 同じマイグレーションで `Account.Code` → `Account.SearchKey` に改称、
    `TransactionClip.CounterAccountCode` → `CounterAccountKey` に改称し `AccountKey` 参照へ移行、
    `Account.OcrRoleExpenseDebit` / `Account.OcrRoleDepositCounter`（INTEGER bool）を追加しシーダーで科目ごとにセット、
    `Account.KeyRevision`（INTEGER・既定 1）を追加。科目マスタの名称変更で「別用途に作り替え」を選んだときだけ +1。
    年度複製は `AccountKey` / `KeyRevision` とも引き継ぐ。
  - `JournalEntry.EditedAfterImport` / `ImportBatchId`（由来表示・バッチ取り消し用）。
- ~~**未確定 / スマホ側の残り仕様書待ち**~~：**4 件とも 2026-09-22 の回答で決着**
  （取込経路・預金スロット・`contentHash`・往復検証。最上段の 2026-09-22 の項を見ること）。
  往復検証だけは**手順が確定しただけで、まだやっていない**。
