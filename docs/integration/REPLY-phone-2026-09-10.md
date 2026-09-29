# 連携契約レビュー — スマホ側からの回答（schemaVersion 1）

> **この文書の読者**：AoiroChobo（PC）側の開発者／Claude Code。
> **書き手**：Android アプリ「JA仕訳変換」(`com.example.greenframeocr`) 側。
> AoiroChobo が発行した契約一式（[README.md](README.md) / [vocabulary-snapshot.md](vocabulary-snapshot.md) /
> [transaction-import.md](transaction-import.md) / [REVIEW-notes.md](REVIEW-notes.md)）への回答。
>
> 対象：契約 `schemaVersion: 1`（2026-09-10 初版ドラフト）
> スマホ側の突き合わせ対象：`feature/gemini-ocr` @ `f4a1c68` / Room DB v33 /
> `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md`

---

## 0. 結論

契約の3大前提変更を **すべて受け入れる**。

| # | 論点 | 回答 |
|---|---|---|
| ① | マッチングはスマホ担当 | 合意。`toCanonicalKey` / `normalizeTekiyou` / 各ルールテーブルはスマホ側に残す |
| ② | マスタは AoiroChobo 所有（`vocabulary.json` を発行） | 合意。スマホは読み取り専用で取り込む |
| ③ | スマホ→PC の取引データは JSON | 合意。CSV は弥生／らくらく向けのまま、AoiroChobo 向けは 3 番目の出力として JSON |

そのうえで、**実装に入る前に詰めたい点が下記 A〜I**。特に **A（科目コードの接続キー）が最大の未解決事項**で、
ここが決まらないとスマホ側は AoiroChobo の `Account.code` を出力できない。

---

## A.【最重要・PC 側へ依頼】自前科目 → AoiroChobo `code` の接続キーがない

### 問題

契約は「科目は `Account.code`（年度非依存の安定キー）で参照。`name` はマッチングのキーにするな」
（[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.1）としている。

しかしスマホ側が蓄積しているマッチング知識は、**すべて自前 DB の内部 ID** で表現されている：

| スマホ側テーブル | 科目参照カラム | 参照先 |
|---|---|---|
| `product_master` | `yayoiAccountId: Long?` / `kaikakeTekiyouId: Int?` | `yayoi_accounts.id` / `rakuraku_tekiyou.id` |
| `tekiyou_matching_rules` | `yayoiAccountId: Long?` | `yayoi_accounts.id` |
| `general_item_master` | `yayoiAccountId: Long?` | `yayoi_accounts.id` |
| `receipt_payment_method_rules` | `yayoiAccountId: Long` | `yayoi_accounts.id` |

これらを AoiroChobo の `code`（`hiryou` 等）に変換する**決定的な接続キーが存在しない**。
スマホの `yayoi_accounts` / `rakuraku_accounts` が持つのは:

- `yayoi_accounts.accountCode: String?` — **nullable**（補助科目・一部科目は null）。弥生の科目コード体系
- `rakuraku_accounts.accountCode: String` — NOT NULL・UNIQUE。**らくらく青色申告（農業版）の科目コード体系**
- `accountName`（科目名）— 契約が「マッチングに使うな」と明記している

→ 科目名でしか突き合わせられないが、それは契約の禁止事項と衝突する。

### 依頼

**`vocabulary.json` の各 `Account` に、任意フィールドとして以下を追加してほしい：**

```jsonc
{
  "code": "hiryou",
  "name": "肥料費",
  // ↓ 追加依頼
  "rakurakuAccountCode": "512",   // らくらく青色申告（農業版）の科目コード。分かる範囲で
  "yayoiAccountCode": null,       // 弥生の科目コード。分かる範囲で（null 可）
  "aliases": ["肥料費", "肥料"]   // 別名（任意）。名前照合のフォールバック用
}
```

- AoiroChobo の科目セットは **らくらく農業簿記ベース**のはず（`docs/yayoi-csv-export-spec.md` も
  AoiroChobo 由来）。であれば少なくとも `rakurakuAccountCode` は機械的に対応が付けられるはず。
- スマホは `rakuraku_accounts.accountCode` ⇔ `vocabulary.accounts[].rakurakuAccountCode` で
  **決定的にマッピング表を作れる**。弥生モードのユーザーも、`yayoi_accounts` → `rakuraku_accounts` の
  対応は科目コード近傍で機械化できる（スマホ側の宿題）。

### `aliases` も無理な場合の次善策（スマホ側で完結）

1. スマホに「**AoiroChobo 科目マッピング**」画面を追加（設定 > データ管理）。
2. `vocabulary.json` 取込時に、自前科目と `vocabulary` 科目を**科目名の一致・類似で自動提案**し、
   ユーザーが 1 回だけ確定する（`product_master` を作る時と同じ「確定」操作）。
3. マッピング表は端末保持。`vocabulary.json` 差し替え時は、消えた `code` / 増えた `code` の
   差分だけ再確認させる。
4. 未マッピングの科目に当たった仕訳は `matchStatus = "UnmatchedAccount"` で出す。

→ **PC 側の回答が必要**：`rakurakuAccountCode`（＋可能なら `yayoiAccountCode` / `aliases`）を
`vocabulary.json` に載せられるか。載せられないなら次善策で進める。

---

## B. `vocabulary.json` の取込経路 —（契約の未確定事項への回答）

| 論点 | スマホ側の回答 |
|---|---|
| UI の場所 | 設定 > データ管理 に「**AoiroChobo 科目・摘要を取り込む**」を追加（既存の JSON バックアップ取込と同じ画面） |
| ファイル選択 | SAF（`ACTION_OPEN_DOCUMENT`）でユーザーが `.json` を選ぶ。自動同期はしない（契約通り） |
| 保存先 | **新規 Room テーブル**：`aoirochobo_accounts` / `aoirochobo_memo_templates` / `aoirochobo_vocab_meta`（1 行） |
| なぜ DB か | マッチング時に SQL で引きたい。ファイルのまま持って毎回パースはしない |
| メタ保持 | `schemaVersion` / `fiscalYear`（year/start/end）/ `generatedAt` / `contentHash` を保存し、取込画面に「**このマスタは N 日前のものです**」と表示 |
| 年度 | **1 年度分のみ保持**（取込で置き換え）。`fiscalYear.year` が変わったら旧データを破棄 |
| enum | 取込時に未知の enum 値があっても**弾かない**。警告表示にとどめ、その値を使う行は `matchStatus` を立てて出す |

DB バージョンは v33 → v34 以降でマイグレーション追加（`ReceiptDatabase.kt`）。

---

## C. `contentHash` は「不透明な文字列」として扱いたい

[vocabulary-snapshot.md](vocabulary-snapshot.md) §5 の正規化仕様（配列ソート順・キー昇順・`null` 除去・
空白なし JSON 化・UTF-8 SHA-256）を、**PC(C#/System.Text.Json) とスマホ(Kotlin/Gson) で
バイト一致させるのは事故りやすい**（キー順序・数値表現・エスケープの差）。

### 提案

- **スマホは `contentHash` を再計算しない。** PC が計算した値を**そのまま保存**し、
  「前回取り込んだ値と文字列一致するか」だけに使う（＝再取込スキップ判定）。
- `transactions.json` の `vocabulary.contentHash` には、**スマホが受け取った `vocabulary.json` に
  書かれていた値をそのまま転記**する。PC 側のマスタずれ検知（[transaction-import.md](transaction-import.md) §11）は
  これで成立する。
- → §5「両側の実装で往復検証」「スマホ側実装と突き合わせて確定」の作業が不要になる。

**この方針で良いか PC 側の確認が必要。**（`contentHash` を厳密に両側実装したいなら、
正規化を JSON Canonicalization Scheme (RFC 8785) に寄せることを提案する。）

---

## D. 預金スロット `bankSlotNo` —（契約の決定事項 D への回答）

- スマホは単一通帳前提（`deposit_meisai` に銀行識別子カラムなし）。
- **スマホ設定に「この通帳のスロット番号」を 1 つ持つ**（既定 `0` = 親「普通預金」）。
- すべての `source = "Deposit"` entry にその固定値を入れる。
- 複数口座運用は当面スコープ外。ユーザー要望が出たら「取込時に選択」へ拡張する。

---

## E. `externalId` の安定性 — 実コード確認結果と残課題

### Purchase：`ocr:purchase:{issueYearAD}{issueMonth:02}-{sheetNumber}-{itemNumber}`

実コード確認（`ReceiptInputScreen.kt` 保存処理・`ReceiptItem.kt`）：

- `receipt_items` の PK は autoincrement `id` のみ。**(issueYear, issueMonth, sheetNumber, itemNumber) に
  UNIQUE 制約なし**。
- 月データ保存時は「その月の行を全 DELETE → 全 INSERT」。**`id` は毎回変わる**（→ 契約通り `id` は使わない、で正しい）。
- `itemNumber = row.rowNumber`（固定 20 行グリッドの位置 1〜20、合計行は 21）。**行位置ベースなので比較的安定**。
- 注意点（契約に caveat として明記したい）：
  - `issueYear` は**令和年**（Int）。`externalId` では**西暦 4 桁に変換**して入れる（令和8年1月 → `202601`）。
  - ユーザーが行を並べ替える／商品を別の伝票（`sheetNumber`）に移すと `externalId` が変わる。
    これは「別の取引になった」と解釈してよいか？（＝ PC 側で旧 `externalId` は void 相当で残る）
  - 同一 (issueYear, issueMonth, sheetNumber, itemNumber) に対して、返品行と通常行が別 `id` で
    2 行入ることは基本ない（1 グリッドセル 1 行）。

### Deposit：`ocr:deposit:{transactionDate}-{transactionNumber}`

- `deposit_meisai` は **UNIQUE(transactionDate, transactionNumber)** あり＝安定。◎
- ただし `transactionNumber: String` は OCR / 手入力の**生文字列**。ゼロ埋めされていない・
  空文字の可能性がある。
  - **決めたいルール**：生文字列をそのまま使う（`[a-z0-9:_-]` に反する文字は除去 or `_` 置換）。
  - `transactionNumber` が**空**のときの `externalId` をどうするか？
    案：`ocr:deposit:{transactionDate}-{amount}-{tekiyou の canonicalKey 先頭8}` にフォールバック。

### Receipt：`ocr:receipt:{receiptUuid}:{itemIndex}`

- `general_receipts` に **UUID カラムは無い**（PK は autoincrement `id`）。
- 対応：`general_receipts` に `uuid: String`（NOT NULL）を追加＋マイグレーションで既存行に一度だけ採番。
  **実装タスクとして合意**。
- `itemIndex` は `general_receipt_items` の明細順（0 始まり）。1 レシート 1 仕訳にまとめる場合は `:sum`。

---

## F. 税率変換の穴 —（契約 §4 変換表への補足）

スマホ側の税率ソースは 2 系統：

| モード | ソース | 値 |
|---|---|---|
| 弥生 | `yayoi_accounts.defaultTaxCategory: String` | `対象外` / `課対仕入10` / `課対仕入8` / `課税売上` / `非課税`（実データ 5 種） |
| らくらく | `rakuraku_tekiyou.taxRate: String`（自由文字列） | `8%` / `10%` / `非` / `不` / 空 |

変換案：

| スマホ側の値 | AoiroChobo コード |
|---|---|
| `課対仕入10` / `10%` | `"10"` |
| `課対仕入8` / `8%` | `"8"` |
| `非課税` / `非` | `"non"` |
| `対象外` / `不` / 空 | `null`（相手科目側）または `"na"` |
| `課税売上` | **8 か 10 か決まらない** ← 下記 |

### `課税売上` 問題

軽減税率対象（米・野菜等の販売）は 8%、それ以外の売上は 10%。
スマホの `yayoi_accounts.defaultTaxCategory` は `課税売上` としか持たず、**8/10 を区別できない**。

- 提案：**収入科目（`accountType = "Income"`）が絡む仕訳の税率は、科目からではなく
  一致した摘要から引く**。
  - らくらく：自前 `rakuraku_tekiyou.taxRate`
  - AoiroChobo：`vocabulary.memoTemplates[].taxRate`（`米販売代金` は `"8"`、サンプル通り）
- どちらでも決まらなければ `debit/credit.taxRate` は入れず（`null`）、`matchStatus = "UnmatchedMemo"` にして
  PC 側で確定させる。

---

## G. `meta.isReturn` の定義 —（[transaction-import.md](transaction-import.md) §7 の文言修正提案）

- `meta.isReturn = true` は **Purchase / Receipt の元金額が負**（返品・値引き）のときだけ。
  このとき借方／貸方を入れ替えて `amount` を正数化する（契約通り）。
- **Deposit の出金（`amount < 0`）は返品ではなく通常の資金移動**。
  → `isReturn = false`。借方／貸方どちらに預金口座科目を置くかで表現する
  （[README.md](README.md) §3・[transaction-import.md](transaction-import.md) §5 の表で既に正しく分岐している）。
- [transaction-import.md](transaction-import.md) §7 の「元データが負（返品・値引き）の場合」という記述は
  Deposit には当てはまらない。「Purchase / Receipt で」と限定する文言修正を提案。

---

## H. `matchStatus` / `confidence` の埋め方

- スマホの現状挙動：弥生モードは「科目未設定行は出力ブロック」、らくらくモードは「摘要空でも出力可」。
  **AoiroChobo 出力ではこの制限を外し、全行出す**（契約通り・落とさない）。
- `confidence`：
  - Purchase / Receipt → `receipt_items.ocrConfidence` / Gemini の自己申告をそのまま（`high`/`medium`/`low`、手入力は省略）。
  - Deposit → 手入力主体なので基本 `high` か省略。
- `matchStatus` の対応：

| 状態 | matchStatus |
|---|---|
| 借方・貸方の `code` 解決済み＋摘要も妥当 | `Matched` |
| 科目 `code` に解決できない（A のマッピング欠落含む） | `UnmatchedAccount` |
| 科目は解決、摘要がフリーテキストのまま／税率未確定 | `UnmatchedMemo` |
| 候補複数（AI が絞れない） | `Ambiguous` |

---

## I. スマホの会計ソフト設定（`RAKURAKU` / `YAYOI`）との関係

- スマホは現在 `AccountingSoftware` 設定で弥生／らくらくを切り替え、マッチングロジックも分岐している。
- **AoiroChobo 出力はこの設定から独立させる**。どちらのモードでも `vocabulary.json` ベースで動く
  3 番目の経路とする。
- `transactions.json` の `accountingSoftwareOnPhone` には、参考情報として現在のモード値を入れる
  （契約通り任意）。

---

## J. スマホ側の「適用済みにする」責務（[README.md](README.md) §1 責務 5・6 の確認）

AoiroChobo 出力を作る時点で、スマホ側が以下をすべて適用済みにする：

| 項目 | スマホ側の対応 | 現状 |
|---|---|---|
| 個別上書き（`overrideYayoiAccountId` / `overrideTekiyouId` / `item.yayoiAccountId` / `paymentAccountOverride`） | 最優先で適用 | 預金 CSV は未反映（`known-issues` #3）。**AoiroChobo 経路では最初から反映して実装** |
| `isExcluded == true`（レシート品目） | 出力しない | `loadOutputItems()` は未フィルタ（#4）。**AoiroChobo 経路では除外** |
| 小計行 `[小計] xxx` / 合計行 `合計` | 出力しない | 判定 `productName.contains("小計"\|"合計")` を流用 |
| 令和 → 西暦 | スマホ側で変換して `entryDate` に入れる | `receipt_items` のみ令和年。`CsvUtils` 相当のロジックを流用 |

弥生 CSV 側の既知不整合（#3 列並び・#4 税区分文字列）は AoiroChobo 経路には影響しない
（別フォーマット・別コード体系）。

---

## K. 未確定事項サマリー（PC 側の回答待ち）

| # | 内容 | 誰が決める |
|---|---|---|
| A | `vocabulary.json` の `Account` に `rakurakuAccountCode` / `yayoiAccountCode` / `aliases` を載せられるか | **PC** |
| C | `contentHash` を「スマホは再計算せず不透明に扱う」で良いか（or RFC 8785 に寄せるか） | **PC** |
| E | Purchase で行の並べ替え／伝票移動時、旧 `externalId` は void 扱いで良いか | 両者 |
| E | Deposit の `transactionNumber` 空欄時の `externalId` フォールバック規則 | 両者 |
| F | 収入科目の税率を「科目でなく摘要から引く」で良いか | 両者 |
| G | [transaction-import.md](transaction-import.md) §7 の `isReturn` 文言修正 | PC（ドキュメント） |

## L. スマホ側の実装タスク（合意済み前提で着手できるもの）

1. `general_receipts` に `uuid` カラム追加＋マイグレーション＋既存行バックフィル（DB v34）。
2. `aoirochobo_accounts` / `aoirochobo_memo_templates` / `aoirochobo_vocab_meta` テーブル追加。
3. 設定 > データ管理に「`vocabulary.json` 取込」UI。
4. 自前科目 ⇔ AoiroChobo `code` マッピング解決（A の結論次第で「機械マッピング」か「マッピング UI」）。
5. 出力確認画面に「AoiroChobo 形式（JSON）」を追加（弥生・らくらく CSV に続く 3 番目）。
6. `transactions.json` ビルダー（借方／貸方組み立て・`externalId` 採番・`matchStatus` 判定・返品の Dr/Cr 入替）。
7. 契約テスト：`examples/transactions.sample.json` をゴールデンにスキーマ検証。

---

_作成: 2026-09-10 / スマホ側対象: `feature/gemini-ocr` @ `f4a1c68` / Room DB v33_
