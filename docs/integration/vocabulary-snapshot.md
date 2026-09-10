# vocabulary.json — マスタスナップショット仕様（AoiroChobo → スマホ）

> AoiroChobo が発行し、スマホ（JA仕訳変換）が取り込む**一方向・読み取り専用**のマスタ。
> スマホの AI はこれを語彙テーブルとして使い、マッチング結果を `accounts[].code` で表現する。
>
> 対象：`schemaVersion: 1`
> 全体像は [README.md](README.md) を参照。

---

## 1. 発行と受け渡し

- AoiroChobo の「GreenFrameOCR 連携 → 科目・摘要を書き出し」（実装予定）で 1 ファイル生成。
- ファイル名例：`aoirochobo_vocabulary_2026_20260910_143000.json`
  （`aoirochobo_vocabulary_{fiscalYear}_{yyyyMMdd}_{HHmmss}.json`）
- 受け渡しは手動（USB / クラウドストレージ / メール）。自動同期はしない。
- 文字コード：**UTF-8 (BOM なし)**、改行 LF、UTF-8 のまま日本語を格納（`\uXXXX` エスケープ不要）。

---

## 2. ルートオブジェクト

```jsonc
{
  "schemaVersion": 1,
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
  "taxRate":      ["10", "8", "8_old", "non", "na", "men"],
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
  "code": "hiryou",              // ★ 年度非依存の安定キー。スマホはこれで科目を指す
  "name": "肥料費",              // 表示名（改名され得る。マッチングのキーにしない）
  "accountType": "Expense",      // enums.accountType
  "groupName": null,             // 決算書内訳のグループ名（"田畑" 等）。null 可
  "parentCode": null,            // 補助科目の親の code。親科目なら null
  "ledgerAffinity": null,        // この科目が属する帳簿（下表）。null 可
  "bankSlotNo": null,            // 預金口座スロット番号。預金科目のみ。§4.3
  "allowsTaxable": true,         // 課税区分「課税」を選べるか（損益科目のみ意味を持つ）
  "allowsNonTaxable": true,      // 課税区分「課税以外」を選べるか
  "defaultTaxCategory": "Taxable", // enums.defaultTaxCategory。既定の課税区分。null 可
  "displayOrder": 42             // AoiroChobo 内の並び順
}
```

**`ledgerAffinity` の値**

| 値 | 意味 |
|---|---|
| `Cash` | 現金（`genkin`） |
| `Bank` | 預金口座（`hutuu` とその補助口座） |
| `AR` | 売掛金（`urikake`） |
| `AP` | 買掛金（`kaikake`） |
| `Unpaid` | 未払金（`mibarai`） |
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
  "ledgerType": "AP",            // enums.ledgerType。この摘要が使える帳簿
  "direction": "In",             // enums.direction。振替(Transfer)では "" （空）
  "name": "肥料購入",            // 摘要名（帳簿の「摘要」列に入る文字列）
  "searchKey": "hiryou",         // ローマ字検索キー（スマホ側の照合にも使える）
  "counterAccountCode": "hiryou",// 相手科目の code。Cash/Bank/AR/AP/Unpaid で使う。null 可
  "debitAccountCode": null,      // 振替(Transfer)専用。借方科目の code
  "creditAccountCode": null,     // 振替(Transfer)専用。貸方科目の code
  "taxRate": "10",               // enums.taxRate。Cash/Bank/AR/AP/Unpaid では相手科目側の税率、
                                 //   Transfer では借方(debitAccountCode)側の税率。null 可
  "creditTaxRate": null,         // Transfer で貸方(creditAccountCode)側にも税率が要るとき（現物払い等）。null 可
  "businessRatio": 100,          // 事業割合(%)。既定 100
  "hasInvoiceDefault": true,     // インボイス既定（適格請求書ありか）
  "showInCash": false,           // 現金出納帳の摘要ドロップダウンに出すか
  "showInBank": false,           // 預金出納帳の摘要ドロップダウンに出すか
  "bankSlotNo": null             // 特定の預金スロット専用の摘要なら番号。通常 null
}
```

- `ledgerType` が `Cash` / `Bank` の摘要は 1 レコードを両帳簿で共有し得る
  （`showInCash` / `showInBank` で出し分け）。
- `ledgerType` が `AR` / `AP` / `Unpaid` の摘要では `showInCash` / `showInBank` は常に `false`。
- `ledgerType` が `Transfer` の摘要は `direction = ""`、`counterAccountCode = null`、
  代わりに `debitAccountCode` / `creditAccountCode` を持つ。

**スマホ側での使い方（想定）**

1. OCR 明細の商品名／摘要文字列を、`memoTemplates[].name` / `.searchKey` と突き合わせて
   最も近い摘要を選ぶ（AI マッチング）。
2. 選んだ摘要の `counterAccountCode`（または `debitAccountCode`/`creditAccountCode`）を
   その仕訳の相手科目として採用。`taxRate` もそこから引く。
3. 摘要に一致しなければ、`accounts[]` から科目を直接推定し、`matchStatus` に応じた値を付ける。

---

### 4.3 預金スロット（`bankSlotNo`）

AoiroChobo は複数の預金口座を「スロット」で管理する。

| `bankSlotNo` | 科目 | 例 |
|---|---|---|
| `null` | 親「普通預金」(`hutuu`) 直下、またはスロットなし | |
| `1`, `2`, `3`, … | 「普通預金」の補助口座 | `1`=営農口座(`einou`) / `2`=直売口座(`tyokubai`) |

- `accounts[]` の預金科目には `bankSlotNo` が入る。スマホ側はどのスロットに取り込むかを
  ユーザーに選ばせる（Android は単一通帳前提なので、通帳ごとにスロット番号を設定で固定してもよい）。
- 取引データ側では `entries[].bankSlotNo` で指定する（[transaction-import.md](transaction-import.md)）。

---

## 5. `contentHash`（任意・推奨）

- `accounts` と `memoTemplates` を以下の手順で正規化した文字列の SHA-256、先頭に `sha256:`。
  1. 各配列を `code`（accounts）/ `ledgerType,direction,searchKey`（memoTemplates）で昇順ソート。
  2. 各オブジェクトのキーを昇順に並べ、`null` のキーは除去。
  3. 2 配列を `{"accounts":[…],"memoTemplates":[…]}` の形で**空白なし** JSON 化。
  4. UTF-8 バイト列の SHA-256 を小文字 16 進で。
- スマホ側は「前回取り込んだ `contentHash` と同じならスキップ」に使える。
- `generatedAt` / `appVersion` はハッシュ対象外（同じ内容なら同じハッシュにする）。

---

## 6. スキーマ検証（契約テスト用の要点）

- `schemaVersion` は整数 `1`。
- `accounts[].code` は非空・全体で一意。
- `accounts[].parentCode` は、非 null なら同じ `accounts` 内の別の `code` を指す。
- `accounts[].accountType` は `enums.accountType` のいずれか。
- `memoTemplates[].ledgerType` は `enums.ledgerType` のいずれか。
- `memoTemplates[].counterAccountCode` / `debitAccountCode` / `creditAccountCode` は、
  非 null なら `accounts` 内に存在する `code`。
- `taxRate` / `creditTaxRate` は、非 null なら `enums.taxRate` のいずれか。
- `direction` は `enums.direction` のいずれか、または `""`（Transfer）。

サンプル：[examples/vocabulary.sample.json](examples/vocabulary.sample.json)
