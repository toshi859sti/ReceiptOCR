# AoiroChobo ⇔ GreenFrameOCR 連携契約（AoiroChobo 側発行）

> **この文書の読者**
> Android アプリ「JA仕訳変換」(`com.example.greenframeocr`) の開発者／Claude Code。
> AoiroChobo（農業用青色申告帳簿アプリ・Windows デスクトップ）と連携するための
> **AoiroChobo 側から見た仕様**。スマホ側の一次仕様書
> `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md` への回答・すり合わせ資料でもある。
>
> AoiroChobo がマスタ（勘定科目・摘要辞書）の所有者であり、この契約の所有者。
> スマホ側はこのフォルダを copy / submodule で取り込んで参照する。

対象バージョン：`schemaVersion: 2`（2026-09-12 改訂／初版 `1` は 2026-09-10）
関連：[REVIEW-notes.md](REVIEW-notes.md)（スマホ側一次仕様書のレビュー所見）

---

## 0. まず結論（スマホ側一次仕様書との差分）

スマホ側一次仕様書は「PC 側が旧モデルで動く」前提で書かれている。実際の AoiroChobo は
以下で確定済み。**スマホ側の設計をこちらに合わせてほしい。**

| 論点 | スマホ側一次仕様書の前提 | AoiroChobo の確定方針 |
|---|---|---|
| ① 商品名・摘要 → 勘定科目のマッチング | PC がやる（`toCanonicalKey` 等を PC に移植する前提） | **スマホがやる**。§1 |
| ② 勘定科目・摘要辞書マスタの所有者 | スマホが持ち、全テーブルを JSON バックアップで PC に渡す | **AoiroChobo が持つ**。PC→スマホへ `vocabulary.json` を渡す。§2 |
| ③ スマホ→PC の取引データ形式 | §10 で JSON を提案（ただし本文は CSV 前提の記述が残る） | **JSON で確定**。§3・[transaction-import.md](transaction-import.md) |

①②はスマホ側の弥生／らくらく出力には影響しない（それらは今のままでよい）。
**AoiroChobo 向け出力を作るときだけ**この契約に従う＝スマホ側に「3 番目の出力形式」を追加する話。

---

## 1. マッチングはスマホ担当

### 何を「マッチング」と呼ぶか

レシート／伝票に書かれた文字列（商品名・通帳摘要・支払方法など）から、
**どの勘定科目・どの摘要・どの税率で記帳するか**を決める処理。表記ゆれの吸収・
あいまい検索・AI 推論が要る"賢い"部分。

### なぜスマホ側でやるか

- AoiroChobo は**オフラインファースト**（Windows デスクトップ・クラウド AI 非搭載）。
  API キー管理・課金・プライバシーの問題を PC に持ち込まない。
- スマホ側は Gemini OCR ＋マッチングが**既に動いている**。同じ処理を PC に二重実装する意味がない。
- スマホ側一次仕様書 §4（`toCanonicalKey` / `normalizeTekiyou` / `tekiyou_matching_rules`）を
  **PC に移植する必要はない**。それらはスマホ側の弥生／らくらく出力向けにスマホ側に残す。

### 役割分担（AoiroChobo 連携のとき）

```
┌─────────────── スマホ（JA仕訳変換）───────────────┐   ┌──────── PC（AoiroChobo）────────┐
│ 伝票/レシート/通帳の OCR・明細データ化              │   │ 勘定科目・摘要辞書マスタを所有       │
│ 商品名/摘要 → 勘定科目・摘要・税率のマッチング       │   │ vocabulary.json を発行（PC→スマホ）  │
│ 借方/貸方の組み立て（二重仕訳化）                    │   │                                    │
│ ↑ AoiroChobo の vocabulary.json を語彙表として参照   │←──│                                    │
│ 個別上書き・除外・集計行の除去を適用済みにする       │   │                                    │
│ transactions.json を出力（スマホ→PC）               │──→│ 取込：accountKey を当年度科目に解決  │
│                                                    │   │ 解決できなければ「要確認」タブへ     │
│                                                    │   │ 取込後の仕訳は AoiroChobo が所有・編集 │
└────────────────────────────────────────────────────┘   └────────────────────────────────────┘
```

### スマホ側の責務（この契約で守ってほしいこと）

1. **勘定科目は解決済みの `accountKey` で出す。** Id・科目名・`searchKey`（検索用文字列）では出さない。
   `accountKey` は `vocabulary.json` の `accounts[].accountKey`（不透明・年度非依存の一意キー。§2）。
   相手科目（貸方）も同様に `accountKey` で出す（「現金」「クレジット」等の生テキストは `meta` に補助情報として）。
2. **税率は AoiroChobo のコード体系に変換して出す**（§4 の変換表）。
3. **日付は西暦 ISO `yyyy-MM-dd`**。令和→西暦変換はスマホ側で済ませる。
4. **金額は税込・正の整数（円）。** 返品・値引きは借方／貸方を入れ替えて正数化する（§3）。
5. **個別上書き・`isExcluded`・小計/合計行**は、AoiroChobo 向け出力を作る時点で
   すべて適用済み（上書き反映・除外行は出さない・集計行は出さない）にする。
6. マッチできなかった行も**落とさず** `matchStatus` を付けて出す（PC 側で「要確認」にまわす）。
6b. AI マッチング前に **source で候補科目・候補摘要を絞る**（`vocabulary.accounts[].ocrRole*` フラグ、
   `memoTemplates` のスコープ。[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.5）。ハードな壁ではなく事前分布。
7. `accountKey` に解決できない支払方法（「PayPay」「クレジット」等）は、
   `vocabulary.json` の中の相当科目（未払金・事業主借など。`accountKey` はそれぞれ `mibarai` /
   `zigyounusikari` 等）に寄せる。
8. **摘要は解決済みの `memoKey` で出す**（`vocabulary.memoTemplates[].memoKey` か `null` の二択＝閉じた語彙）。
   摘要名（`name`）は参照キーにしない（同名が存在し得る・改名され得る）。`memoName` は任意のエコー。
   スマホが文字列を生成しない。逆引き 0 件は `null`＋`UnmatchedMemo`。OCR の生テキスト（商品名・
   但し書き・通帳メモ）は `note`（メモ欄）に入れる。決定表 N・[transaction-import.md](transaction-import.md)。
9. **税率・事業割合・インボイスは商品名から予測しない**。税率＝科目の `defaultTaxCategory` フォールバック
   （レシートの税率マークがあればそちら）、事業割合＝常に `100` で出す（PC は解決できた `memoKey` の
   摘要の事業割合で置き換える）、インボイス＝レシート
   現物の登録番号 `T\d{13}` の有無。決定表 L・[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.7。

---

## 2. マスタは AoiroChobo 所有

### 何が変わるか

- スマホ側一次仕様書 §5 は「PC が Android の JSON バックアップ（`yayoi_accounts` /
  `rakuraku_accounts` / `rakuraku_tekiyou`）を食う」前提。**これは AoiroChobo 連携では使わない。**
- 代わりに **AoiroChobo が `vocabulary.json` を発行**し、スマホがそれを取り込む。
  スマホの AI はこの `vocabulary.json` を語彙テーブルとして使い、マッチング結果を
  `vocabulary.json` 内の `accounts[].accountKey` で表現する。

### なぜ AoiroChobo 所有か

- 勘定科目・摘要辞書はユーザーが **AoiroChobo の画面で追加・改名・無効化**する。
  正本が 2 か所にあると必ずズレる。所有者を 1 つに固定する。
- AoiroChobo の科目・摘要は**年度スコープ**（`FiscalYearId` を持つ）。スナップショットは
  「どの年度のマスタか」を明示して発行する。

### 科目の参照キー ＝ `accountKey`

- スマホが科目を指すキーは **`accountKey`**：不透明・不変・年度非依存で、1 つの `vocabulary.json`
  （＝ 1 会計年度）の中で一意。同じ論理科目はどの年度のスナップショットでも同じ値。
- AoiroChobo の `Account.Code`（`genkin` `hiryou` 等・Phase 4 で `Account.SearchKey` に改称予定）は
  **使わない**。DB の UNIQUE 制約が無く、科目登録画面で検索用文字列として露出しているだけなので、
  契約キーには向かない。
- PC 側の裏付け：`Account.AccountKey` カラム（Phase 4 のマイグレーションで追加・`(FiscalYearId,
  AccountKey)` に UNIQUE）。既存のシステム科目は歴史的スラッグ（`genkin` 等）をそのまま `accountKey`
  として引き継ぎ、ユーザー追加科目は `acct-<英数字>` を採番する。詳細は
  [vocabulary-snapshot.md](vocabulary-snapshot.md) §4.4。
- `accountKey` は**その科目が表す概念**に 1 対 1（画面上のスロット位置ではない）。科目マスタは
  有限スロットなので、ユーザーが任意科目を別用途に作り替える運用は普通に起こる
  （例「研修費」→「水利費」）。このとき**新しい `accountKey` が採番され、古いキーはその年度から消える**。
  スマホは「`vocabulary.json` に無くなったキーの学習は使わない」という 1 ルールだけ持てばよい
  （無効化された科目とまったく同じ扱い）。詳細は [vocabulary-snapshot.md](vocabulary-snapshot.md) §4.6。

### 摘要の参照キー ＝ `memoKey`

- スマホが摘要を指すキーは **`memoKey`**：不透明・不変・年度非依存・年度内で一意。**改名では不変**
  （作り替えは既存行を書き換えず新しい行を作るので、新しい行が自然に新キーを持つ）。
  AoiroChobo では摘要の相手科目・税率・事業割合を変えると**その摘要で登録済みの当年度の仕訳が
  まとめて書き換わる**（意図的な一括修正機能）。これは**仕様**で確認も出さない——科目の改名と
  同じく「変えたらその年の分はすべてそう変わる」で統一。別の意味に使いたいときはユーザーが
  新しい行を作る（摘要はスロット制ではないので無制限に追加できる）。
- 摘要名（`name`）は**参照キーにしない**。摘要登録画面の新規行は既定名が「新規摘要」、コピー行は
  「◯◯（コピー）」で、DB にも同名を禁じる制約が無いため、同名の行が普通に存在し得る。
- PC 側の裏付け：`MemoTemplate.MemoKey` カラム（Phase 4 で追加・`(FiscalYearId, MemoKey)` に UNIQUE）。
  詳細は [vocabulary-snapshot.md](vocabulary-snapshot.md) §4.8。

### `vocabulary.json`（PC → スマホ）

- 中身・スキーマ・具体例：[vocabulary-snapshot.md](vocabulary-snapshot.md)
- サンプル：[examples/vocabulary.sample.json](examples/vocabulary.sample.json)
- 要点：
  - `accountKey` / `memoKey`（年度非依存の一意キー・上記）で科目・摘要を参照。`searchKey`（検索用
    文字列）・科目名・摘要名は載せるが参照キーにはしない。Id は載せない。
  - `IsActive = 1` の科目・摘要だけを載せる。**無効化された行は翌年度に複製されない**ので、
    無効化＝その年度でそのキーの系譜が終わる（復活させたい場合は新しいキーが採番される）。
  - `schemaVersion` 必須。任意で `contentHash`（スマホ側の鮮度チェック用）。
  - enum（`accountType` / `ledgerType` / `direction` / `taxRate` / `defaultTaxCategory`）は
    AoiroChobo の C# enum・シードから機械生成して同梱する（手維持しない）。

### スナップショットが古くなったら

- スマホの `vocabulary.json` が古くても壊れない。PC の**取込時検証**が
  「その `accountKey` は今年度に存在しない」を検出し、その行を「要確認」にまわす。
- スマホ側は `vocabulary.json` の `generatedAt` / `fiscalYear.year` をユーザーに見せて、
  「マスタが N 日前のものです。AoiroChobo で書き出し直してください」程度の注意喚起ができるとよい。

---

## 3. 取引データはスマホ→PC も JSON（③の確定）

- CSV ではなく **JSON**。理由：
  - 二重仕訳（借方／貸方のネスト）・`matchStatus`・`meta` が素直に表現できる。
  - スマホは既に JSON を扱い、PC は `System.Text.Json` で自明。
  - 「転送方式が変わっても契約は不変」という CSV 採用理由は JSON でも成立する。
- スキーマ・フィールド定義・`ExternalId` 生成規則・具体例：
  [transaction-import.md](transaction-import.md)
- サンプル：[examples/transactions.sample.json](examples/transactions.sample.json)
- スマホ側一次仕様書 §10 の JSON 案をベースに、AoiroChobo のスキーマ（`JournalEntry`）へ寄せてある。

### 借方／貸方の決め方（スマホ側一次仕様書 §6・§7 と同じ）

科目は `accountKey` で指す（下表の `kaikake` 等はシステム科目の `accountKey` 値の例）。

| source | 借方 (`debit.accountKey`) | 貸方 (`credit.accountKey`) | 金額 | 摘要 | 落ちる帳簿 (`ledgerType`) |
|---|---|---|---|---|---|
| 購買（JA伝票） | 商品の経費科目 | `kaikake`（買掛金・固定） | `amount` | 買掛摘要の `memoKey`（無ければ null・商品名は `note`） | `AP` |
| 通帳 入金 | 預金口座科目（スロット） | ルールの相手科目 | `abs(amount)` | 預金摘要の `memoKey`（無ければ null・原文は `note`） | `Bank` |
| 通帳 出金 | ルールの相手科目 | 預金口座科目（スロット） | `abs(amount)` | 同上 | `Bank` |
| レシート（貸方＝`ledgerAffinity` が `Cash` の科目。現金） | 品目の経費科目 | 支払方法の科目（`genkin` 等） | `price` | 現金出金の摘要の `memoKey`（無ければ null・品目名は `note`） | `Cash` |
| レシート（貸方＝`ledgerAffinity` が `Unpaid` の科目。未払金） | 品目の経費科目 | 支払方法の科目（`mibarai` 等） | `price` | 未払発生の摘要の `memoKey`（無ければ null・品目名は `note`） | `Unpaid` |
| レシート（貸方＝それ以外。事業主借など） | 品目の経費科目 | 支払方法の科目（`zigyounusikari` 等） | `price` | 振替の摘要の `memoKey`（無ければ null・品目名は `note`） | `Transfer` |

レシートの `ledgerType` は**貸方（支払方法）の科目の `ledgerAffinity`** で決める。クレカ・電子マネーで
払っても、貸方が事業主借なら `Transfer`（振替伝票）になる（2026-09-30 minor（9））。
キーの綴りで分けない。返品・値引きで借方／貸方を入れ替えるときは、**入れ替える前の貸方**で決める。

`ledgerType` は「その仕訳を所有する帳簿」のヒント。省略された場合は PC が借方／貸方科目の
`ledgerAffinity` から推定する。

---

## 4. 税率コード変換表

AoiroChobo 側のコード（`debit.taxRate` / `credit.taxRate` に入れる値）：

| AoiroChobo コード | 意味 | 表示 | スマホ側（`RakurakuTekiyou.taxRate` 等）からの変換元（例） |
|---|---|---|---|
| `"10"` | 標準税率 10% | 10% | `10%` / `課対仕入10` / `課税売上`（10%想定） |
| `"8"` | 軽減税率 8% | 8%軽 | `8%` / `課対仕入8` |
| `"1"` | 軽減税率 1%（食料品・2027年から予定） | 1%軽 | `1%` / `課対仕入1`（スマホ側が対応したら） |
| `"8_old"` | 旧税率 8%（2019/9/30 以前） | 8%(旧) | （通常は発生しない。旧年度データのみ） |
| `"non"` | 非課税 | 非課税 | `非` / `非課税` |
| `"na"` | 不課税 | 不課税 | `不` / `対象外` / 空 |
| `"men"` | 免税 | 免税 | （免税事業者向け。該当時のみ） |
| `null`（キー省略可） | 税率設定なし | — | 相手科目が資産・負債・資本のとき（現金/預金/売掛/買掛の科目側） |

- **現金／預金／売掛／買掛／未払**の仕訳は、経費・収益側の科目にだけ税率が付く。
  もう一方（現金・預金・買掛金など）は `null`。`debit.taxRate` と `credit.taxRate` は
  実質どちらか一方だけが non-null になる。
- 簡易課税・税込入力なので**消費税額は分離しない**（スマホ側一次仕様書 §1 と一致）。

---

## 5. 契約の必須決定事項（[REVIEW-notes.md](REVIEW-notes.md) より・この契約での回答）

| # | 項目 | この契約での決定 | 詳細 |
|---|---|---|---|
| A | 行の一意キー `externalId` | グローバル一意・復元耐性のある文字列。スマホ側で採番 | [transaction-import.md](transaction-import.md) §「externalId」 |
| B | 日付形式 | 西暦 ISO `yyyy-MM-dd` 一本化。和暦変換はスマホ側 | §3-3 |
| C | 税率コード | AoiroChobo コードを正とし、上記変換表で変換 | §4 |
| D | 預金スロット識別子 | `bankSlotNo` は **`1`〜`5`**（普通預金の補助口座）。`0`＝親「普通預金」は**使わない**（見出し科目で出納帳が無い）。スマホは通帳ごとに口座を 1 つ選んでおく（最大 5 冊・2026-09-25 minor（8）） | [transaction-import.md](transaction-import.md) §「bankSlotNo」 |
| E | 相手科目 | 解決済み `accountKey` 必須。生の支払方法テキストは `meta.paymentMethodText` に | §1 責務 1 |
| I | 科目参照キー | `accountKey`（不透明・不変・年度非依存・年度内で一意）。`Account.Code` は使わない。PC 側は `Account.AccountKey` カラムを Phase 4 で追加 | §2「科目の参照キー」・[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.4 |
| K | 科目の作り替え（意味変更） | **2026-09-13 全面改訂。** `accountKey` は**スロット**に 1 対 1 で、作り替えても**据え置き**（採番し直さない。`keyRevision` も `AccountKeyRegistry` も廃止）。科目・摘要は年度ごとに別の行なので過去年度は物理的に無傷。スマホ側は **`name` が変わったキーの学習を外す**（学習と一緒に「そのとき見た `name`」を保持して比較）のが主機構。「ファイルに無いキーの学習は使わない」は科目の**無効化**用として残る。古いスナップショットからの取込は PC 側が `accountName` / `memoName` のエコーを現在名と突き合わせて「要確認」に回す | [vocabulary-snapshot.md](vocabulary-snapshot.md) §4.6 |
| K2 | 摘要参照キー | `memoKey`（不透明・不変・年度非依存・年度内で一意）。摘要名は同名が存在し得るので参照キーにしない。PC 側は `MemoTemplate.MemoKey` カラムを Phase 4 で追加 | §2「摘要の参照キー」・[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.8 |
| J | マッチング候補の絞り込み | source で候補を絞る。科目＝`vocabulary` の `ocrRoleExpenseDebit` / `ocrRoleDepositCounter` フラグ＋支払方法は `genkin`/`mibarai`/`zigyounusikari` 直指定。摘要＝`ledgerType`/`direction`/`showInCash`/`showInBank` でスコープ（Receipt の `Transfer` は振替の摘要・minor（9））。レシートの品目グループはレシート共通（`paymentCommon`・minor（10）） | [vocabulary-snapshot.md](vocabulary-snapshot.md) §4.5 |
| F | 個別上書き・除外・集計行 | スマホ側で適用済みにする（上書き反映・`isExcluded` 除外・小計/合計行を出さない） | §1 責務 5 |
| G | 返品・マイナス金額 | 金額は常に正。返品は借方／貸方を入れ替えて出す（例：Dr 買掛金 / Cr 経費科目）。`meta.isReturn = true` | §3 |
| H | 文字コード・バージョン | JSON・UTF-8 (BOM なし)・LF。`schemaVersion` 必須 | 全体 |
| L | 科目以外の項目の出所 | 商品名から予測できるのは科目だけ。税率＝科目の `defaultTaxCategory` フォールバック／事業割合＝スマホは触らず `100` 固定（PC は `memoKey` の摘要の値を採る）／インボイス＝レシート現物の `T\d{13}` 検出／摘要＝自由文字列。`memoTemplates` 逆引きはラベル候補出し専用に格下げ | [vocabulary-snapshot.md](vocabulary-snapshot.md) §4.7 |
| M | 通帳・JA伝票のマッチング | LLM でなくルールエンジン（決定論・オフライン・課金なし）。ルールは「確定例 → 再コンパイル」で自動生成。手書きは opt-in。LLM は未ヒット行の初回サジェスト専用の任意プラグイン | [matching-rules.md](matching-rules.md) |
| N | 摘要は閉じた語彙 | `memoKey` は `vocabulary.memoTemplates[].memoKey` か `null` の二択。スマホは文字列を作らない。逆引き 0 件は `null`＋`UnmatchedMemo` → PC で辞書から選択 or 新規登録。生テキスト（商品名・但し書き・通帳メモ）は `note`（メモ欄・自由文字）へ | [transaction-import.md](transaction-import.md) §「摘要は閉じた語彙」 |
| O | 未解決行の受け皿 | PC は取込ステージングテーブル `ImportedTransaction` に全行を保存し、確定した行だけ `JournalEntry` 化する（`JournalEntry` の科目列は非 NULL FK なので未解決行を直接保存できない）。「要確認」の状態はこのテーブルが保持 | [transaction-import.md](transaction-import.md) §10 |

---

## 6. このフォルダの構成

```
docs/integration/
├── HANDOVER.md                    ← **スマホ側へ渡すときの案内状**（読む順番・前回からの差分・回答待ち9項目）
├── README.md                      ← この文書（連携の全体像・役割分担・③の確定）
├── REVIEW-notes.md                スマホ側一次仕様書のレビュー所見
├── vocabulary-snapshot.md         PC → スマホ：マスタスナップショット仕様
├── transaction-import.md          スマホ → PC：取引データ JSON 仕様
├── matching-rules.md              スマホ側マッチングエンジンの設計（ルールエンジン＋LLM 境界）
├── CHANGELOG.md                   schemaVersion ごとの変更履歴
└── examples/
    ├── vocabulary.sample.json     vocabulary.json のゴールデン例
    └── transactions.sample.json   transactions.json のゴールデン例
```

**契約テストは両側に置く**：スキーマ検証＋ゴールデン例（`examples/`）との一致を CI で確認する。
スキーマが変わったら `schemaVersion` を上げ、`CHANGELOG.md` に記録する。
