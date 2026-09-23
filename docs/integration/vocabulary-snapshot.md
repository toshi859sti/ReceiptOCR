# vocabulary.json — マスタスナップショット仕様（AoiroChobo → スマホ）

> AoiroChobo が発行し、スマホ（JA仕訳変換）が取り込む**一方向・読み取り専用**のマスタ。
> スマホの AI はこれを語彙テーブルとして使い、マッチング結果を `accounts[].accountKey` で表現する。
>
> 対象：`schemaVersion: 2`
> 全体像は [README.md](README.md) を参照。変更点は [CHANGELOG.md](CHANGELOG.md)。

---

## 1. 発行と受け渡し

- AoiroChobo の **ホーム →「スマホ連携」→「科目・摘要を書き出す」**で 1 ファイル生成。
  （**実装済 2026-09-14**：`ExportVocabularyUseCase` / `GreenFrameLinkPage`）
- ファイル名例：`aoirochobo_vocabulary_2026_20260910_143000.json`
  （`aoirochobo_vocabulary_{fiscalYear}_{yyyyMMdd}_{HHmmss}.json`）
- 受け渡しは手動（USB / クラウドストレージ / メール）。自動同期はしない。
- 文字コード：**UTF-8 (BOM なし)**、改行 LF、UTF-8 のまま日本語を格納（`\uXXXX` エスケープ不要）。
- **1 ファイル = 1 会計年度分**（`fiscalYear.year` で明示）。

---

## 2. ルートオブジェクト

```jsonc
{
  "schemaVersion": 2,
  "kind": "aoirochobo.vocabulary",
  "generatedAt": "2026-09-10T14:30:00+09:00",   // ISO 8601（タイムゾーン付き）
  "generatedBy": {
    "app": "AoiroChobo",
    "appVersion": "0.9.0"
  },
  "fiscalYear": {
    "year": 2026,               // 会計年度（西暦）
    "startDate": "2026-01-01",
    "endDate": "2026-12-31"
  },
  "contentHash": "sha256:2f6c…", // accounts + memoTemplates を正規化した SHA-256（任意・下記§5）
  "enums": { … },               // §3
  "accounts": [ Account, … ],   // §4.1（IsActive=1 のみ）
  "memoTemplates": [ MemoTemplate, … ]  // §4.2（IsActive=1 のみ）
}
```

- **キー欠落 = null** として扱ってよい（スマホ側 Gson の慣習に合わせる）。
- スマホ側は未知のキーを**無視**する（前方互換）。未知の enum 値に当たったら「要確認」に落とす。

---

## 3. `enums`（AoiroChobo の C# enum・シードから機械生成）

```jsonc
"enums": {
  "accountType":  ["Asset", "Liability", "Income", "Expense", "Capital"],
  "ledgerType":   ["Cash", "Bank", "AR", "AP", "Unpaid", "Transfer"],
  "direction":    ["In", "Out"],
  "taxRate":      ["10", "8", "1", "8_old", "non", "na", "men"],
  "defaultTaxCategory": ["Taxable", "NonTaxable", "NotApplicable", "TaxExempt", "NA"]
}
```

| enum | 値 | 意味 |
|---|---|---|
| `accountType` | `Asset` | 資産 |
| | `Liability` | 負債 |
| | `Income` | 収入（農業所得） |
| | `Expense` | 経費 |
| | `Capital` | 資本（事業主貸借・元入金など） |
| `ledgerType` | `Cash` / `Bank` / `AR` / `AP` / `Unpaid` / `Transfer` | 現金出納帳 / 預金出納帳 / 売掛帳 / 買掛帳 / 未払帳 / 振替伝票 |
| `direction` | `In` | 収入・販売・仕入発生（相手＝収益・経費科目）側の摘要 |
| | `Out` | 支出・入金・支払（相手＝現金・預金科目）側の摘要 |
| `taxRate` | （[README.md](README.md) §4 の変換表を参照） | |
| `defaultTaxCategory` | `Taxable` | 課税 |
| | `NonTaxable` | 非課税 |
| | `NotApplicable` | 不課税 |
| | `TaxExempt` | 免税 |
| | `NA` | 課税区分の対象外（資産・負債・資本、および区分を持たない損益科目） |

---

## 4. エンティティ

### 4.1 `Account`（勘定科目）

AoiroChobo の `Account` テーブルのうち **`IsActive = 1` の行**を、年度スコープを解決して出力。

```jsonc
{
  "accountKey": "genkin",        // ★ 科目の一意キー（§4.4）。スマホはこれで科目を指す。不透明・不変
                                 //   ＝科目マスタ上の**スロット**の識別子。別用途に作り替えても
                                 //   キーは据え置き、name だけ変わる（§4.6）
  "searchKey": "genkin",         // ローマ字検索キー（AoiroChobo `Account.SearchKey`。一意保証なし・
                                 //   スマホは使わなくてよい）。memoTemplates[].searchKey と同じ性格
  "name": "現金",                // 表示名（改名され得る。マッチング/参照のキーにしない）
  "accountType": "Asset",        // enums.accountType
  "groupName": null,             // 決算書内訳のグループ名（"田畑" 等）。null 可
  "parentAccountKey": null,      // 補助科目の親の accountKey。親科目なら null
  "ledgerAffinity": "Cash",      // この科目が属する帳簿（下表）。null 可
  "bankSlotNo": null,            // 預金口座スロット番号。預金科目のみ。§4.3
  "allowsTaxable": false,        // 課税区分「課税」を選べるか（損益科目のみ意味を持つ）
  "allowsNonTaxable": false,     // 課税区分「課税以外」を選べるか
  "defaultTaxCategory": null,    // enums.defaultTaxCategory。既定の課税区分。null 可
  "displayOrder": 1,             // AoiroChobo 内の並び順
  "isSystem": true,              // らくらく標準科目か（ユーザー追加科目は false）
  "ocrRoleExpenseDebit": false,  // JA購買・レシートの「借方」候補にしてよい科目か（§4.5）
  "ocrRoleDepositCounter": true  // 通帳の「相手科目」候補にしてよい科目か（§4.5）
}
```

**AoiroChobo `Account` テーブルの列との対応**（`IsActive=1` の行のみ出力）

| `Account` 列 | vocabulary のキー | 備考 |
|---|---|---|
| `Id` | （出さない） | 年度をまたぐと変わるため使わない |
| `AccountKey`（Phase 4） | `accountKey` | §4.4。作り替えても**据え置き**（`name` だけ変わる。§4.6） |
| `Code`（→ `SearchKey` に改称・Phase 4） | `searchKey` | ローマ字検索キー。参照キーにしない。`memoTemplates[].searchKey` と同じ性格 |
| `FiscalYearId` | （ファイル単位の `fiscalYear`） | 行ごとには持たない |
| `ParentId` | `parentAccountKey` | Account.Id → その科目の `accountKey` に解決 |
| `Name` | `name` | |
| `GroupName` | `groupName` | |
| `AccountType` | `accountType` | |
| `LedgerAffinity` | `ledgerAffinity` | |
| `BankSlotNo` | `bankSlotNo` | |
| `AllowsTaxable` | `allowsTaxable` | 0/1 → bool |
| `AllowsNonTaxable` | `allowsNonTaxable` | 0/1 → bool |
| `DefaultTaxCategory` | `defaultTaxCategory` | |
| `DisplayOrder` | `displayOrder` | |
| `IsSystem` | `isSystem` | 0/1 → bool |
| `OcrRoleExpenseDebit`（Phase 4） | `ocrRoleExpenseDebit` | 0/1 → bool。§4.5 |
| `OcrRoleDepositCounter`（Phase 4） | `ocrRoleDepositCounter` | 0/1 → bool。§4.5 |
| `IsActive` | （出さない） | 常に 1（無効行は出力しない） |
| `CreatedAt` / `UpdatedAt` | （出さない） | 内部管理用 |

**`ledgerAffinity` の値**

| 値 | 意味 |
|---|---|
| `Cash` | 現金 |
| `Bank` | 預金口座（親「普通預金」とその補助口座） |
| `AR` | 売掛金 |
| `AP` | 買掛金 |
| `Unpaid` | 未払金 |
| `Any` | どの帳簿からでも使える資産・負債（前払金・借入金など） |
| `償却資産` | 減価償却対象の資産（建物・農機具など） |
| `null` | 帳簿と結びつかない（多くの収益・経費科目、棚卸資産など） |

> `allowsTaxable` / `allowsNonTaxable` は「らくらく農業簿記の科目一覧の 課税／課税以外 列」に相当。
> `allowsTaxable=1 かつ allowsNonTaxable=1` → 課税区分「すべて」選択可。
> どちらも `0` → 課税区分そのものが非対象（資産・負債・資本、および `減価償却費` 等）。

### 4.2 `MemoTemplate`（摘要辞書）

AoiroChobo の `MemoTemplate` テーブルのうち **`IsActive = 1` の行**。

```jsonc
{
  "memoKey": "memo-0042",          // ★ 摘要の一意キー（§4.8）。スマホはこれで摘要を指す。不透明・不変
  "ledgerType": "AP",              // enums.ledgerType。この摘要が使える帳簿
  "direction": "In",               // enums.direction。振替(Transfer)では "" （空）
  "name": "肥料購入",              // 摘要名（帳簿の「摘要」列に入る文字列）。改名され得る・重複し得る
                                   //   ので参照キーにしない（§4.8）
  "searchKey": "hiryou",           // ローマ字検索キー（スマホ側の照合にも使える。一意ではない）
  "counterAccountKey": "hiryou",   // 相手科目の accountKey。Cash/Bank/AR/AP/Unpaid で使う。null 可
  "debitAccountKey": null,         // 振替(Transfer)専用。借方科目の accountKey
  "creditAccountKey": null,        // 振替(Transfer)専用。貸方科目の accountKey
  "taxRate": "10",                 // enums.taxRate。Cash/Bank/AR/AP/Unpaid では相手科目側の税率、
                                   //   Transfer では借方(debitAccountKey)側の税率。null 可
  "creditTaxRate": null,           // Transfer で貸方(creditAccountKey)側にも税率が要るとき（現物払い等）。null 可
  "businessRatio": 100,            // 事業割合(%)。既定 100。Transfer では借方側
  "creditBusinessRatio": null,     // Transfer 専用。貸方側の事業割合(%)。null = 未設定（プリセットは空が多い）
  "hasInvoiceDefault": true,       // インボイス既定（適格請求書ありか）
  "showInCash": false,             // 現金出納帳の摘要ドロップダウンに出すか
  "showInBank": false,             // 預金出納帳の摘要ドロップダウンに出すか
  "bankSlotNo": null,              // 特定の預金スロット専用の摘要なら番号。通常 null
  "displayOrder": 1,               // AoiroChobo 内の並び順（同一 ledgerType 内。逆引きの同点処理にも使える）
  "isPreset": true                 // AoiroChobo のシード摘要か（ユーザー追加は false）
}
```

**AoiroChobo `MemoTemplate` テーブルの列との対応**（`IsActive=1` の行のみ出力）

| `MemoTemplate` 列 | vocabulary のキー | 備考 |
|---|---|---|
| `Id` | （出さない） | 年度をまたぐと変わるため使わない |
| `MemoKey`（Phase 4） | `memoKey` | §4.8。摘要の参照キー |
| `FiscalYearId` | （ファイル単位の `fiscalYear`） | 行ごとには持たない |
| `LedgerType` | `ledgerType` | |
| `BankSlotNo` | `bankSlotNo` | |
| `Direction` | `direction` | |
| `Name` | `name` | |
| `SearchKey` | `searchKey` | |
| `AccountId` | `counterAccountKey` | Account.Id → その科目の `accountKey` に解決 |
| `DebitAccountId` | `debitAccountKey` | 同上（Transfer 専用） |
| `CreditAccountId` | `creditAccountKey` | 同上（Transfer 専用） |
| `TaxRate` | `taxRate` | |
| `CreditTaxRate` | `creditTaxRate` | |
| `BusinessRatio` | `businessRatio` | |
| `CreditBusinessRatio` | `creditBusinessRatio` | |
| `DefaultHasInvoice` | `hasInvoiceDefault` | |
| `ShowInCash` | `showInCash` | |
| `ShowInBank` | `showInBank` | |
| `DisplayOrder` | `displayOrder` | |
| `IsPreset` | `isPreset` | |
| `IsActive` | （出さない） | 常に 1（無効行は出力しない） |
| `IsSharedAcrossBank` | （出さない） | 廃止済みの死にカラム |
| `CreatedAt` / `UpdatedAt` | （出さない） | 内部管理用 |

- 摘要の参照キーは **`memoKey`**（§4.8）。スマホは摘要辞書を語彙として使い、結果は
  `memoKey`（**辞書のキーそのもの、または `null`**）で表現する。`memoName` は人間可読の
  エコー（PC 側の検証・ログ用）で、参照キーではない（[transaction-import.md](transaction-import.md)）。
  **摘要は閉じた語彙**：スマホが文字列を生成しない。逆引き 0 件なら `memoKey`/`memoName` とも
  `null`（生テキストは `note` へ）。
- **摘要辞書の実体＝「記帳バンドルに名前を付けたもの」**。1 行を選ぶと
  相手科目（`counterAccountKey` or `debit/creditAccountKey`）・税率（`taxRate`/`creditTaxRate`）・
  事業割合（`businessRatio`/`creditBusinessRatio`）・インボイス既定（`hasInvoiceDefault`）が
  **決定論的に丸ごと適用**される。`name`/`searchKey` は検索ラベルで記帳結果には効かない。
- **科目＋税率＋事業割合＋インボイスが同一でテキストだけ違う摘要**は普通にある
  （米販売代金／農協売上入金／直売店売上入金＝全部 水稲・8%・100%）。つまり記帳セマンティクスの
  タプルは一意でない。テキストの選択は §4.7 の「ラベル層」として扱う。
- `ledgerType` が `Cash` / `Bank` の摘要は 1 レコードを両帳簿で共有し得る
  （`showInCash` / `showInBank` で出し分け）。
- `ledgerType` が `AR` / `AP` / `Unpaid` の摘要では `showInCash` / `showInBank` は常に `false`。
- `ledgerType` が `Transfer` の摘要は `direction = ""`、`counterAccountKey = null`、
  代わりに `debitAccountKey` / `creditAccountKey` を持つ。

**スマホ側での使い方（推奨：科目を先に決めて摘要を逆引き）**

商品名と摘要辞書の項目は抽象度が違う（商品名＝`ダイアジノン粒剤3` / 摘要＝`農薬購入`）。
摘要は「農家が繰り返す取引フロー」に合わせた小さな固定リストで、任意の店舗レシートの品目は
どの摘要にも当てはまらないことが多い。**商品名 → 摘要 の直接 AI マッチングは避け、以下の順で解決する。**

1. **商品名 → 科目**を AI マッチングで解決する（`accounts[]` が候補集合。これはスマホ側で既に実績あり）。
   → `debit` / `credit` の `accountKey` が決まる。
2. **摘要を逆引きする**。手順1で決めた相手科目の `accountKey` と、その仕訳の `ledgerType`・`direction`
   （In/Out）で `memoTemplates[]` を絞り込む：
   - 非振替：`counterAccountKey == 相手科目 && ledgerType == 帳簿 && direction == 方向`
   - 振替：`debitAccountKey` / `creditAccountKey` の組で照合
3. 逆引き結果で分岐：

   | 逆引き結果 | 出力（[transaction-import.md](transaction-import.md)） |
   |---|---|
   | 摘要がちょうど 1 件 | `memoKey` = その `memoKey`、`memoName` = その `name`（エコー）、`matchStatus = "Matched"` |
   | 摘要が複数（例：現金で `水稲` → 米販売代金／農協売上入金／直売店売上入金） | 元資料テキストとの近さで 1 件選ぶ or 第一候補（いずれも辞書の行）の `memoKey`＋`matchStatus = "Ambiguous"` |
   | 摘要が 0 件（一般レシートの雑多な品目など） | **`memoKey` = `null`・`memoName` = `null`**、`matchStatus = "UnmatchedMemo"`。生テキスト（商品名・但し書き）は `note` へ |

   → **`memoKey` は辞書のキーか `null` の二択。スマホが文字列を作らない**（摘要＝閉じた語彙）。
   0 件は正常（PC 側の「要確認」で、ユーザーが辞書から選ぶ or 辞書に新規登録して確定）。
   生テキストは常に `note`（メモ欄・自由文字）に入るので情報は失われない。
4. `taxRate` は、逆引きで摘要が 1 件に決まったらそこから、決まらなければ相手科目の
   `defaultTaxCategory` から [README.md](README.md) §4 の変換表で引く。

**帳簿別の相性**

- **JA購買（買掛帳・`AP`）**：買掛摘要（肥料購入・農薬購入・農具購入・諸材料購入・種苗購入・飼料購入）は
  実質「商品カテゴリ一覧」＝逆引きがよく効く。らくらくの `product_master.kaikakeTekiyouId` と同じ発想。
- **一般レシート（現金・未払）**：摘要マッチはたいてい失敗する（＝正常）。科目＋自由文字列メモで十分。
- **通帳（`Bank`）**：商品名ではなく通帳の摘要文字列（`フリコミ ノウキョウ` 等）が照合対象。
  スマホ側の `tekiyou_matching_rules` の仕事で、商品名の AI マッチングとは別経路。

---

### 4.3 預金スロット（`bankSlotNo`）

AoiroChobo は複数の預金口座を「スロット」で管理する。

| `bankSlotNo` | 科目 | 例 |
|---|---|---|
| `1` 〜 `5` | 「普通預金」の補助口座＝**預金出納帳として使える口座** | `1`=営農口座 / `2`=直売口座 |
| `null` | 親「普通預金」（見出し科目）、またはスロットを持たない科目 | |

- **有効なスロット番号は `1`〜`5` のみ**（AoiroChobo の口座上限は 5）。`0` は**使わない**。
  親「普通預金」は見出し科目で `bankSlotNo = null`、**それ自体の出納帳は存在しない**ので
  取込先に指定できない（`entries[].bankSlotNo` に `0` や `null` を入れると解決不能行になる）。
- `accounts[]` の預金科目には `bankSlotNo` が入る。スマホ側はどのスロットに取り込むかを
  ユーザーに選ばせる（Android は単一通帳前提なので、通帳ごとにスロット番号を設定で固定してもよい）。
  選択肢として見せてよいのは `ledgerAffinity == "Bank" && bankSlotNo != null` の科目だけ。
- 取引データ側では `entries[].bankSlotNo` で指定する（[transaction-import.md](transaction-import.md)）。

---

### 4.4 `accountKey` — 科目の一意キー

**性質**

- **一意**：1 つの `vocabulary.json`（＝ 1 会計年度）の中で `accountKey` は重複しない。
- **年度非依存で安定**：同じ論理科目（例「肥料費」）は、どの年度のスナップショットでも**同じ `accountKey`**。
  AoiroChobo は年度締めで科目を丸ごと複製するが、`accountKey` はそのまま引き継がれる。
- **不変**：一度発行した `accountKey` は変わらない。科目を**改名しても不変**（`name` だけ変わる）。
- **科目マスタの 1 行に 1 対 1**（2026-09-13 改訂／2026-09-14 表現を明確化）：`accountKey` が指すのは
  **その行そのもの**であって、そこに今入っている概念ではない。作り替えても**キーは据え置かれ、
  `name` だけが変わる**（§4.6）。旧版は「概念に 1 対 1・作り替えで新規採番」としていたが撤回した。
  - **表示位置ではない**。`DisplayOrder` はユーザーが並べ替えられる（`AccountMasterViewModel.MoveUp`/
    `MoveDown` が 2 行の `DisplayOrder` を交換する）が、`accountKey` は行に付いたまま一緒に動く。
    位置に紐づけると並べ替えだけで学習と残高が別科目に付け替わるので、そう解釈してはいけない。
  - **名前でもない**。改名しても作り替えても `accountKey` は変わらない。
  - 「スロット」という語は任意科目にしか当てはまらない。システム科目（現金・肥料費など）は枠では
    なく固定の科目で、キーは歴史的スラッグ（`genkin` `hiryou`）。どちらも「行の識別子」で統一される。
- **引退しない**：キーを引退させないので `AccountKeyRegistry`（引退年度・`supersededBy`）も作らない。
  科目を無効化した場合はその年度のファイルからキーが消えるが、同じスロットが再び有効化されれば
  同じキーで戻る。
- **不透明**：スマホ側は中身を解釈しない（パースしない・意味を読まない）。ただの識別子として扱う。
  参考までに、既存のシステム科目では歴史的なスラッグ（`genkin` `hiryou` `kaikake` …）と一致し、
  ユーザーが後から追加・作り替えた科目では `acct-<英数字>` 形式になる。

**スマホ側の使い方**

- マッチング結果（借方・貸方の科目、相手科目）は必ず `accountKey` で表現する。
- スマホが学習・キャッシュする「商品名 → 科目」の対応表も `accountKey` をキーにする
  （`name` や `searchKey` をキーにしない。改名・重複で壊れる）。
- **`name` が変わった `accountKey` の学習は外す**（2026-09-13・主機構）。学習と一緒に「そのとき見た
  `name`」を保持し、新しいスナップショットで変わっていたら外して未設定に戻す。作り替えでキーが
  変わらなくなったため、改名・作り替えを拾う合図はこれになる（§4.6）。
- **`vocabulary.json` に無くなった `accountKey` の学習は自動マッチに使わない**（非アクティブ化）。
  作り替えではキーが消えなくなったが、**科目の無効化**では消えるのでこのルールは残る（§4.6）。
- `vocabulary.json` に無い `accountKey`（古いスナップショット由来）を出しても構わない。
  PC 側の取込時検証が「その年度に存在しない」と判定して「要確認」にまわす。

> **PC 側の裏付け**：`accountKey` は `Account.AccountKey` カラム（Phase 4 のマイグレーションで追加予定・
> `(FiscalYearId, AccountKey)` に UNIQUE 制約）。同じマイグレーションで既存の `Account.Code` は
> `Account.SearchKey` に改称される（実態は検索キーのため）。**契約上のキーは `accountKey`**
> （[README.md](README.md) §2）。
>
> ~~キーの採番は年度の外にある `AccountKeyRegistry` が持つ。~~ **`AccountKeyRegistry` は作らない**
> （2026-09-13 撤回）。キーを引退させないので「引退したキーを別概念へ再発行してしまう」問題が
> 起きず、レジストリの存在理由が消えた。新規科目作成時の採番は年度内の最大値からの単純な連番でよい。

---

### 4.5 AI マッチングの候補フィルタ

スマホ側は **AI マッチングを走らせる前に、source（`Purchase` / `Deposit` / `Receipt`）で
候補集合を絞る**。精度が上がり、AI に渡す語彙も減る。

> これは**ハードな制約ではなく事前分布**。候補外の科目・摘要を低 confidence で返すのは可。
> PC 側の「要確認」がミスを拾う（[transaction-import.md](transaction-import.md) §9）。

#### 科目の候補

| source / 位置 | 候補集合 |
|---|---|
| `Purchase` 借方（`debit`） | `ocrRoleExpenseDebit == true` |
| `Purchase` 貸方（`credit`） | `kaikake`（買掛金）固定 |
| `Receipt` 借方（`debit`） | `ocrRoleExpenseDebit == true` |
| `Receipt` 貸方（`credit` ＝支払方法） | `genkin` / `mibarai` / `zigyounusikari` の 3 つ（`accountKey` 直指定）。`meta.paymentMethodText` が「クレジット」「PayPay」等 → `mibarai`、「現金」→ `genkin`、不明・私費立替 → `zigyounusikari` |
| `Deposit` 相手科目（入金なら `credit`、出金なら `debit`） | `ocrRoleDepositCounter == true` |
| `Deposit` 預金口座側 | `bankSlotNo` に対応する `Bank` 科目（`entries[].bankSlotNo`） |

- `ocrRoleExpenseDebit` / `ocrRoleDepositCounter` は `Account` の 2 フラグ（PC がシードで科目ごとに
  明示セット。`vocabulary-snapshot` §4.1）。**型では一律に決まらない**（例：`事業主貸`/`専従者給与`/
  `家計費` は資本だが `ocrRoleDepositCounter=true`、`減価償却費` は経費だが両方 `false`）。
- フラグが両方 `false` の科目（`元入金`・`減価償却費`・棚卸資産・`貸倒引当金繰入`・`家事消費` 等）は
  OCR 取引の相手科目にならない。スマホは候補から外す。

#### 摘要（`memoTemplates`）の候補

**新しいフラグは無い**。既存フィールドで絞る：

| 条件 | フィルタ |
|---|---|
| 帳簿 | `Purchase`→`ledgerType == "AP"` / `Receipt` クレカ→`"Unpaid"` / **`Receipt` 現金→`"Cash"` かつ `showInCash`** / **`Deposit`→`"Cash"` かつ `showInBank`** |
| 方向 | `direction` == `In` / `Out`。**お金の向きではなく帳簿上の発生／解消**：現金・預金は `In`＝入金・`Out`＝出金、売掛・買掛・未払は `In`＝債権債務の発生（売上・購入）・`Out`＝解消（入金・支払）。`Deposit` は元金額の符号で判定 |

⚠ **2026-09-23 訂正：`ledgerType == "Bank"` の摘要は実在しない。** 預金出納帳の摘要も `ledgerType = "Cash"` で
持ち、`showInBank` で出し分けている（PC の摘要登録画面の「預金」タブも `showInBank && direction` だけで絞る）。
以前この表は「`Deposit`→`Bank`」としていたが、そのとおり絞ると預金の候補は**常に 0 件**になる。
`"Bank"` は列挙値としては残っている（将来使う余地）ので、`Deposit` の候補は
「`ledgerType ∈ {Cash, Bank}` かつ `showInBank`」と書いておけば両方に耐える。
実データ（2026 本番・有効 107 件）：`Cash` 77 件のうち `showInBank` 75・`showInCash` 72（両方 70）。
| 常に除外 | `ledgerType == "Transfer"`（OCR は振替伝票を生成しない） |

推奨フロー（§4.2）＝「商品名→科目を先に解決 → その相手科目 `accountKey` で
`memoTemplates` を逆引き」だと、候補は最初から `counterAccountKey == 一致科目` の 0〜3 件に絞られる。
0 件なら `memoKey` は `null`（`matchStatus = "UnmatchedMemo"`）、生テキストは `note` へ。

---

### 4.6 科目の「作り替え」— `accountKey` は据え置き、**スマホ側は名前の変化で学習を外す**

> **2026-09-13 全面改訂。** 旧版は「作り替えのとき新しい `accountKey` を採番し、古いキーは
> スナップショットから消える」という設計だった。**これを撤回する。** 経緯は
> `docs/functional-design.md`「科目の作り替え・改名」。

**前提：科目は年度ごとに別の実体**

AoiroChobo は年度締めで `Account` / `MemoTemplate` を**まるごと別の行として複製**する
（`DuplicateAccountsAndMemoTemplatesUseCase`）。2026 年の帳簿が参照するのは 2026 年の科目行であり、
**2026 年でどう名前を変えても 2025 年のデータは物理的に変わらない**。

したがって「作り替えると過去の記録が壊れる」という心配は**年度をまたいでは存在しない**。
壊れうるのは当年度の中の表示と、年度をまたぐ**参照**だけである。

**何が起きるか**

科目マスタは**有限のスロット**でできている（任意資産 5／任意負債 8／田畑 6／果樹 3／特殊施設 3／
畜産物 3／任意経費 4／繰入額 4／預金補助 5）。枠が少ないので、使わなくなった科目を**別用途に
作り替える運用は普通に起こる**（作付けを変えて田畑スロットの「キャベツ」を「ねぎ」に、など）。

このとき `accountKey` は**据え置かれる**。キーはスロットに固定された識別子であり、そのスロットが
何を表しているかは `name` が語る。

```
2025 年度の vocabulary.json :  { "accountKey": "acct-0007", "name": "研修費" }
2026 年度の vocabulary.json :  { "accountKey": "acct-0007", "name": "水利費" }   ← 同じキー・名前だけ変化
```

**なぜキーを振り直さなくなったのか**

旧版がキーを振り直していたのは、年度をまたいでキーを引く処理が**黙って 2 つの概念を融合させる**のを
防ぐためだった。その経路は 5 本あり、2026-09-13 にすべて個別の対処が決まったので、キーの振り直しで
まとめて守る必要が無くなった。

| # | 年度をまたぐ参照 | 被害 | 対処 |
|---|---|---|---|
| 1 | 期首残高の引き継ぎ | 金額が狂う | PC 側で**変更・削除を禁止** |
| 2 | 期首棚卸の引き継ぎ | 金額が狂う | PC 側で**変更・削除を禁止** |
| 3 | 年度繰越の科目複製 | 金額が狂う | 1・2 の禁止で塞がる |
| 4 | 定型取引ホルダー `TransactionClip`（年度非依存） | 候補が的外れ | PC 側で**クリップを削除** |
| 5 | スマホ側の学習 | 候補が的外れ | **名前の変化で学習を外す**（下記） |

お金が動く 1〜3 は禁止で塞ぎ、金額を持たない 4・5 は捨てる／外す。結果として
「これは改名か作り替えか」を区別したがる相手がいなくなった。

あわせて `AccountKeyRegistry`（引退年度・`supersededBy` を持つ年度外テーブル）も**作らない**。
キーを引退させないので、引退キーの再発行を防ぐという存在理由が消えたため。

**PC 側のルール**

- **名称変更に確認ダイアログを出さない**。禁止条件に当たらない科目は、そのまま変更できる。
  - 旧版の A（使用中なので結果を提示）・B（未使用なので二択）は**どちらも廃止**。
  - 当年度に仕訳があっても変更してよい。入力済み取引の表示名が変わるのは**望ましい挙動**
    （2026-09-13 ユーザー判断。「打ち間違えたまま入力したのを直したい」が実際のケースだから）。
- **変更・削除を禁止する条件**（1 つでも満たしたら不可。旧「作り替え可能条件」から①を削除したもの）：
  1. ~~当年度にその科目を参照する `IsVoided = 0` の仕訳が 1 件も無いこと~~ → **条件から外した**
  2. 期首残高・前年度からの繰越残高が 0 であること
  3. 前年度から引き継いだ期末棚卸が紐づいていないこと

  禁止したときは行き止まりにせず、**先に期首残高／期首棚卸を 0 にする**よう案内する。
  UI 文言は `docs/functional-design.md`「科目の作り替え・改名」。
- 科目・摘要を変更（改名・無効化・相手科目の差し替え）したら、**それを参照する `TransactionClip` を
  全年度ぶん削除する**。
- 年度締めの科目複製では `accountKey` を**そのまま引き継ぐ**。
- システム科目（`isSystem = true`）は名称固定なので変更そのものが起きない。

**スマホ側の使い方**

- 学習・キャッシュ（「商品名 → 科目」対応表）のキーは **`accountKey`**。
- **学習を外す合図は「`name` が変わったこと」**（2026-09-13 改訂・旧版の「保険」を主機構に昇格）。
  学習を保存するとき、そのとき見えていた `name` も一緒に保持する。新しい `vocabulary.json` で
  同じ `accountKey` の `name` が（正規化しても）変わっていたら、**その学習を外す**。

  ```
  スマホの学習：  電話代 → acct-x7（そのとき見た名前「通信費」）
  新しい vocabulary.json：  acct-x7 の name が「研修費」
                    ↓
  名前が変わった → 学習を外す → 「電話代」は未設定に戻る
  ```

  打ち間違いの修正でも外れるが、外れて困ることは無い。次に同じレシートを撮ったときユーザーが
  一度選び直せばまた覚える＝**安全側に失敗する**。PC 側に新しい仕掛けは要らない。
- **ファイルに存在しない `accountKey` の学習も引き続き非アクティブにする**。作り替えでキーが消える
  ことは無くなったが、**科目の無効化**ではいまも消えるため、このルールは残る。
- 摘要（`memoKey` / `memoName`）も同じ扱い（§4.8）。

**残る穴：古いスナップショットを持ったスマホからの取込**

キーを引退させないので「キーが消える」という検知の合図が無くなる。しばらく同期していないスマホは
`acct-x7 = 通信費` のつもりで仕訳を送るが、PC 側ではもう `acct-x7` は研修費で、**キーは有効なので
そのまま通ってしまう**。

対処：`transactions.json` が既に持っている `accountName`（人間可読のエコー）を PC 側が使い、
**取込時に現在の名前と突き合わせ、食い違っていたら「要確認」に回す**。受け皿は取込ステージング
`ImportedTransaction`（[transaction-import.md](transaction-import.md) §9）。摘要は `memoName` で同じ判定。

```
スマホから：  accountKey = acct-x7 / accountName = "通信費"
PC の現在：   acct-x7 = "研修費"
           → 要確認へ
```

---

### 4.7 科目以外の項目（税率・事業割合・インボイス・摘要）の決め方

商品名・通帳摘要から確実に予測できるのは**科目だけ**。他は「予測」でなく別ソースから決まる。

| 項目 | 本当の出所 | スマホがやること |
|---|---|---|
| **科目**（`accountKey`） | 商品名・通帳摘要（ルール＋学習、必要なら LLM 補助） | 解決する。学習のキーは `accountKey` |
| **インボイス**（`hasInvoice`） | **レシート現物**（登録番号 `T\d{13}` の有無） | OCR で判定（予測でなく事実）。`hasInvoiceDefault` は最後の手段 |
| **税率**（`taxRate`） | 商品の性質（食品=8%）＞ その `accountKey` の `defaultTaxCategory` | AI/ルールで試行 → 科目既定にフォールバック → 不明なら `null`＋`matchStatus` |
| **事業割合**（`businessRatio`） | **その農家の家事按分方針**＝**摘要辞書の `businessRatio`**（商品と無関係） | **触らない。`100` 固定で出す**。**PC は `memoKey` が解決できたら摘要の `businessRatio` を採り、送られた値は使わない**（下記） |
| **摘要**（`memoKey`） | 摘要辞書（逆引き）。**閉じた語彙** | 辞書の `memoKey` か `null`。生テキストは `note` へ。任意でラベル選好キャッシュ（下記） |

**帰結：`memoTemplates` の逆引きは「記帳バンドルを取る」ためではなく、摘要ラベルの候補出し専用に
格下げ。** 科目＋税率は `accountKey`（＋その `defaultTaxCategory`）から直接引ける。1 科目で税率が
分かれる稀なケースだけ逆引きが効く。

**事業割合は摘要が決める（2026-09-23 改訂・スマホ側と合意）。** 以前は「スマホは 100 で出し、
按分は PC の年末『家事按分自動生成』がやる」としていたが、**その自動生成は PC に存在しない**。
PC の按分は**仕訳 1 件ごとの `BusinessRatio`** を決算書が掛ける方式で（らくらく青色申告農業版と同じ・
令和6年分の申告書と 1 円単位で一致済み）、手入力でも摘要を選ぶとその事業割合が仕訳に入る。
取込もこれに揃える：

- `memoKey` が当年度の摘要に解決できた → **仕訳の事業割合＝その摘要の `businessRatio`**。
  `debit/credit.businessRatio` は見ない
- `memoKey = null`（科目だけ確定）→ 送られた値（契約上 `100`）。按分が要るなら PC で摘要を選ぶか手で直す
- 要確認画面で PC が摘要を選び直した場合も、確定時にその摘要の値が入る

したがって**事業割合だけ違う摘要**（例：電気料金 40 と 電気料金（事業専用）100、どちらも動力光熱費・10%）の
取り違えは**金額配分に直結する**。スマホはこの組み合わせを自動で選ばず、ユーザーに確定させてから
ラベル選好キャッシュに学習すること（[REPLY-pc-2026-09-23b.md](REPLY-pc-2026-09-23b.md)）。

**2 つの層**

| 層 | 内容 | 誤ると | 扱い |
|---|---|---|---|
| 記帳バンドル | 科目＋税率＋インボイス | 帳簿・税・決算が崩れる | `accountKey` で厳密に。消えたキーは非アクティブ化 |
| ラベル | 摘要（`memoKey`）。**事業割合はここに乗る** | 見た目・集計の粒度。**事業割合だけ違う摘要を取り違えると経費額** | **閉じた語彙**（辞書の `memoKey` か `null`）。0 件は PC で辞書選択／新規登録。生テキストは `note` |

**ラベル選好キャッシュ（任意実装）**：農家がラベルの一貫性を気にするなら、スマホは別建てで
`商品名（or 通帳摘要パターン） → memoKey` の軽い学習を持てる。記帳バンドルと完全分離。
年度またぎは `memoKey` の照合だけ：取込した `vocabulary.json` にその `memoKey` が残っていればそのまま
使い、消えていれば選好を捨てて `memoKey = null`（`UnmatchedMemo`）に降格する。`memoKey` は改名でも
年度繰越でも変わらないので、schemaVersion 1 にあった「`searchKey` → `name` → 第一候補」の
フォールバック照合は不要になった。

**通帳の定期引き落とし・JA伝票**は AI でなく**ルールエンジン**の仕事。設計は
[matching-rules.md](matching-rules.md)。

---

### 4.8 `memoKey` — 摘要の一意キー

**性質**（`accountKey` と同じ設計。§4.4 と対称）

- **一意**：1 つの `vocabulary.json`（＝ 1 会計年度）の中で `memoKey` は重複しない。
- **年度非依存で安定**：年度締めの摘要複製で `memoKey` はそのまま引き継がれる。
- **不変**：摘要を改名しても `memoKey` は変わらない（`name` だけ変わる）。
- **概念に 1 対 1**：指すのは「その摘要が表す記帳バンドル」。摘要はスロット制ではないので、別の
  意味にしたいときは既存行を書き換えず**新しい行を作る**よう誘導する。新しい行なのだから自然に
  新しい `memoKey` を持つ（既存キーを振り直すわけではない。下記「摘要の変更と `memoKey`」）。
  `accountKey` がスロットに固定されるのと同じく、`memoKey` は行に固定される（どちらも変更で
  振り直されない）。違いは「新しく作れる枠があるか」だけで、規約としては対称である。
- **不透明**：スマホ側は中身を解釈しない。形式は `memo-<英数字>`。
  実機は `memo-<連番>`（`memo-102` のようにゼロ埋め無し）を振る。`examples/` のキーは
  説明用の作り物なので、**桁数や形を当てにしないこと**（文字列として持つ）。
- **再利用しない**：削除（`IsActive = 0`）した `memoKey` が別の摘要に再発行されることはない。

**なぜ `name` ではなくキーで指すのか**

schemaVersion 1 では「摘要は一意キーを持たない・`memoName`（＝辞書の `name`）で指す」としていたが、
`name` はキーとして成立しない：

- 摘要登録画面の新規行は既定名が「**新規摘要**」で、2 行足せば同名 2 行ができる。
- 行コピーは「◯◯（コピー）」で作られ、ユーザーが括弧を消せば元と同名になる。
- DB に `(LedgerType, Direction, Name)` の一意制約は無い（**同名を禁止していない**）。
- ユーザーが摘要を改名すると、スマホ側スナップショットの `memoName` が一斉に引けなくなる。
  同じレコードなのに全行が `UnmatchedMemo` に落ちる。

**摘要の変更と `memoKey`（PC 側の運用ルール）**

摘要辞書は**スロット制ではない**（いくらでも追加できる）ので、科目のような「枠が足りないから転用する」
必要が無い。加えて AoiroChobo では、**摘要の相手科目を変えると、その摘要で登録済みの仕訳の科目が
まとめて書き換わる**（`MemoMasterViewModel` の一括連動。「この摘要で切った仕訳を全部直す」ための
意図的な機能で、画面にも「この N 件の科目もまとめて変わります」と出る）。

つまり摘要は「使った瞬間にコピーされるスタンプ」ではなく**生きたリンク**で、作り替えは科目より
危険（科目の改名で動くのは当年度の**表示名だけ**で金額は 1 円も動かないが、摘要の相手科目を変えると
**当年度の金額が科目間を移動する**＝決算書の集計行と消費税額が実際に変わる）。なお科目・摘要とも
年度ごとに別の行なので、**前年度以前の帳簿が書き換わることは無い**（「過去」はすべて当年度の中の話）。
そのため PC 側は次のルールで運用する：

| 操作 | 扱い | `memoKey` |
|---|---|---|
| 名称の修正 | 可。**確認ダイアログは出さない** | 据え置き |
| 相手科目・税率の修正（過去も含めて直したい） | 可。現行の一括連動のまま | 据え置き |
| **別の意味に使いたい** | **ユーザーが自分で新しい行を作る**（摘要は無制限に追加できる）。アプリは誘導も判定もしない | 新しい行が自然に新しい `memoKey` を持つ |

**確認ダイアログは出さない**（2026-09-13 決定。同日中に 3 択案から変更）。摘要の変更も**当年度の仕訳を
すべて書き換える**——科目とまったく同じ扱いにする。3 択案（「今後の入力だけ変える」／「過去の分も
まとめて直す」）を撤回した理由は、**書き換えないなら旧行を残さざるを得ない**から：既存仕訳は旧行を
指したままなので `IsActive = 0` にしても削除できず（摘要表示が `MemoTemplateId` 経由のため）、
辞書に死んだ行が溜まり、帳簿には辞書に無い摘要が並ぶ。加えて科目と摘要で説明が分かれるのを避ける。
別の意味に使いたいユーザーは自分で行を足す（誘導もしない）。あわせて、帳簿の摘要
表示を `JournalEntry.MemoName`（値コピー）から `MemoTemplateId` 経由の `MemoTemplate.Name` に切り替え、
科目と同じ「改名は過去にも遡及する」挙動に揃える（`MemoName` は `MemoTemplateId = null` の行の
フォールバックに降格）。詳細は `docs/functional-design.md`「摘要の変更」。

摘要は無制限に追加できるので、この誘導にコストは無い。

**確認ダイアログは科目・摘要とも一切出さない**（2026-09-13 最終）。摘要の変更は当年度の金額を
科目間で動かすが、それは確認ではなく**保存後の通知**で伝える（「今年の仕訳 31 件を更新しました」）。

**摘要を変更したときも、それを参照する `TransactionClip` を全年度ぶん削除する**（§4.6 の 4 と同じ）。
スマホ側の学習も `name` の変化で外す（§4.6 と同じ扱い）。

**スマホ側の使い方**

- 逆引きの結果は `memoKey` で表現する（`memoName` は人間可読のエコー。PC は照合に使わない）。
- ラベル選好キャッシュを持つなら、そのキーも `memoKey`。
- `vocabulary.json` に存在しない `memoKey` は出さない（古いスナップショット由来で存在しないなら
  `null`＋`UnmatchedMemo`）。

> **PC 側の裏付け**：`MemoTemplate.MemoKey` カラム（Phase 4 のマイグレーションで追加・
> `(FiscalYearId, MemoKey)` に UNIQUE）。採番は年度内の最大値からの単純な連番でよい
> （`AccountKeyRegistry` は 2026-09-13 に撤回。§4.4）。
> 併せて、同一 `(LedgerType, Direction, Name)` かつ `IsActive = 1` の摘要を登録しようとしたときは
> 摘要登録画面で警告する（ハード制約にはしない。既存データと「同じ名前で別の相手科目」の
> 正当なケースを塞がないため）。

---

## 5. `contentHash`（任意・推奨）

- `accounts` と `memoTemplates` を以下の手順で正規化した文字列の SHA-256、先頭に `sha256:`。
  1. 各配列をソート：accounts は `accountKey` 昇順、memoTemplates は `memoKey` 昇順
     （どちらもファイル内で一意なので、同点処理は不要）。
  2. 各オブジェクトのキーを昇順に並べ、`null` のキーは除去。
  3. 2 配列を `{"accounts":[…],"memoTemplates":[…]}` の形で**空白なし** JSON 化。
  4. UTF-8 バイト列の SHA-256 を小文字 16 進で。
- スマホ側は「前回取り込んだ `contentHash` と同じならスキップ」に使える。
- `generatedAt` / `appVersion` はハッシュ対象外（同じ内容なら同じハッシュにする）。

---

## 6. スキーマ検証（契約テスト用の要点）

- `schemaVersion` は整数 `2`。
- `accounts[].accountKey` は非空・ファイル内で一意。
- `accounts[].parentAccountKey` は、非 null なら同じ `accounts` 内の別の `accountKey` を指す。
- `accounts[].accountType` は `enums.accountType` のいずれか。
- `accounts[].bankSlotNo` は null か `1`〜`5` の整数（`0` は不正）。§4.3。
- `memoTemplates[].memoKey` は非空・ファイル内で一意。§4.8。
- `memoTemplates[].ledgerType` は `enums.ledgerType` のいずれか。
- `memoTemplates[].counterAccountKey` / `debitAccountKey` / `creditAccountKey` は、
  非 null なら `accounts` 内に存在する `accountKey`。
- `taxRate` / `creditTaxRate` は、非 null なら `enums.taxRate` のいずれか。
- `direction` は `enums.direction` のいずれか、または `""`（Transfer）。
- `businessRatio` は 0〜100 の整数。`creditBusinessRatio` は null か 0〜100。
- `accounts[].ocrRoleExpenseDebit` / `ocrRoleDepositCounter` は bool（欠落＝false 扱い）。
  `ocrRoleExpenseDebit == true` の科目は `accountType in ("Expense","Asset")`。
- `ledgerType != "Transfer"` の行は `debitAccountKey` / `creditAccountKey` / `creditTaxRate` /
  `creditBusinessRatio` が null、`ledgerType == "Transfer"` の行は `direction == ""` かつ
  `counterAccountKey == null`。
- `memoTemplates[].name` の**重複は検証エラーにしない**（同一 `ledgerType`/`direction` 内に同名が
  存在し得る）。一意性は `memoKey` だけが保証する。§4.8。

サンプル：[examples/vocabulary.sample.json](examples/vocabulary.sample.json)
