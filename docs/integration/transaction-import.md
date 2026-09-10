# transactions.json — 取引データ仕様（スマホ → AoiroChobo）

> スマホ（JA仕訳変換）が出力し、AoiroChobo が取り込む取引データ。**JSON**（③の確定・[README.md](README.md) §3）。
> スマホ側一次仕様書 §10 の JSON 案をベースに、AoiroChobo の `JournalEntry` スキーマへ寄せてある。
>
> 対象：`schemaVersion: 1`

---

## 1. 発行と受け渡し

- スマホ側「出力確認画面」に「AoiroChobo 形式（JSON）」を追加（弥生・らくらく CSV に続く 3 番目）。
- ファイル名例：`ja_shiwake_20260910_150000.json`
- 文字コード：**UTF-8 (BOM なし)**、改行 LF。日本語はそのまま格納（`\uXXXX` エスケープ不要）。
- 1 ファイル = 1 回の取込バッチ。AoiroChobo 側が取込ごとに `ImportBatchId`（例 `ocr-20260910-001`）を採番する
  ので、スマホ側は採番しない。

---

## 2. ルートオブジェクト

```jsonc
{
  "schemaVersion": 1,
  "kind": "aoirochobo.transactions",
  "generatedAt": "2026-09-10T15:00:00+09:00",
  "generatedBy": {
    "app": "JA仕訳変換",
    "appVersion": "1.4.0",
    "sourceCommit": "5889f13"        // 任意
  },
  "vocabulary": {                    // どの vocabulary.json に対して解決したか
    "schemaVersion": 1,
    "fiscalYear": 2026,
    "contentHash": "sha256:2f6c…"    // 任意だが強く推奨（PC 側がマスタずれを検知できる）
  },
  "accountingSoftwareOnPhone": "RAKURAKU",  // 任意・参考情報（RAKURAKU | YAYOI）
  "entries": [ Entry, … ]
}
```

---

## 3. `Entry`（1 仕訳）

AoiroChobo の `JournalEntry` は **1 行 = 借方 1 科目・貸方 1 科目・金額 1 つ（税込）**。
スマホ側でこの形（単一仕訳）まで組み立ててから出す。

```jsonc
{
  "externalId": "ocr:purchase:202601-3-5",   // ★ 必須・グローバル一意・復元耐性（§4）
  "source": "Purchase",                       // Purchase | Deposit | Receipt
  "ledgerType": "AP",                         // 省略可（PC が科目から推定）。§「ledgerType」
  "bankSlotNo": null,                         // source=Deposit のとき必須。§「bankSlotNo」
  "entryDate": "2026-01-20",                  // 西暦 ISO yyyy-MM-dd（和暦変換はスマホ側）
  "amount": 11000,                            // 税込・正の整数（円）。§「金額と返品」

  "debit":  { "accountCode": "hiryou",  "taxRate": "10",  "businessRatio": 100 },
  "credit": { "accountCode": "kaikake", "taxRate": null,  "businessRatio": 100 },

  "memoName": "肥料購入",                     // 摘要（vocabulary の memoTemplates[].name か、フリーテキスト）
  "memoSearchKey": "hiryou",                  // 任意。摘要辞書に一致したときその searchKey
  "note": "ダイアジノン粒剤3",                // メモ欄（商品名など）。任意
  "hasInvoice": true,                         // 任意（省略時 PC 側既定）。§「hasInvoice」

  "matchStatus": "Matched",                   // Matched | UnmatchedAccount | UnmatchedMemo | Ambiguous
  "confidence": "high",                       // 任意。high | medium | low

  "meta": {                                   // 参考情報。PC は記帳に使わないが「要確認」画面に出す
    "sourceTable": "receipt_items",
    "sourceRowId": 123,
    "productName": "ダイアジノン粒剤3",
    "category": "一般購買",
    "storeName": null,
    "registrationNumber": null,               // インボイス登録番号 T+13桁。任意
    "paymentMethodText": null,                // レシートの支払方法印字（"クレジット" 等）。任意
    "isReturn": false,                        // amount が元データで負だった（返品・値引き）
    "phoneExportedAt": null                   // スマホ側で過去に出力済みなら日時
  }
}
```

### 各フィールド

| フィールド | 必須 | 説明 |
|---|---|---|
| `externalId` | ✔ | §4。重複取込防止・再取込マッチングのキー |
| `source` | ✔ | 元データ種別。`Purchase`（JA伝票）/ `Deposit`（通帳）/ `Receipt`（店舗レシート） |
| `ledgerType` | — | この仕訳を所有する帳簿。省略時は PC が `debit`/`credit` の科目の `ledgerAffinity` から推定 |
| `bankSlotNo` | △ | `source=Deposit` のとき必須。§「bankSlotNo」 |
| `entryDate` | ✔ | 西暦 ISO `yyyy-MM-dd`。空文字・和暦・分割整数は不可 |
| `amount` | ✔ | 税込・正の整数（円）。0 以下は不可。§「金額と返品」 |
| `debit.accountCode` | ✔※ | `vocabulary.accounts[].code`。解決不能でも推定値を入れ、`matchStatus` を立てる。本当に不明なら `null` |
| `debit.taxRate` | — | [README.md](README.md) §4 のコード。相手科目側にだけ付く。省略/`null` 可 |
| `debit.businessRatio` | — | 事業割合(%)。省略時 100。PC 側で家事按分を別途扱うので通常は 100 のままでよい |
| `credit.*` | ✔※ | 借方と同様 |
| `memoName` | ✔ | 帳簿の「摘要」列に入る文字列 |
| `memoSearchKey` | — | 摘要辞書に一致したときの `searchKey`。PC 側で摘要辞書に紐付ける手がかり |
| `note` | — | 帳簿の「メモ」列 |
| `hasInvoice` | — | 省略時 PC 側既定（現状 true 相当）。§「hasInvoice」 |
| `matchStatus` | ✔ | §「matchStatus」 |
| `confidence` | — | OCR / マッチングの自己申告確度 |
| `meta` | — | 参考情報一式。オブジェクトごと省略可 |

※ `debit`/`credit` オブジェクト自体は必須。中の `accountCode` は「不明なら `null`」を許容。

---

## 4. `externalId` — 一意キーの生成規則

**目的**：同じ取引を 2 回取り込まない／再取込で内容が変わったら更新する／
ユーザーが AoiroChobo で消した取引を再取込で黙って復活させない。

**要件**

1. **グローバル一意**（テーブルをまたいでも衝突しない）。
2. **復元耐性**：スマホの再インストール・DB 復元・行の再採番があっても同じ取引なら同じ値になる。
   → Room の autoincrement `id` を**そのまま使わない**（`receipt_items.id` 等は復元で変わる）。
3. **決定的**：同じ元伝票からは毎回同じ `externalId`。
4. ASCII の範囲（`[a-z0-9:_-]`）に収める。全体で 128 文字以内。

**スキーム（`ocr:` プレフィックス＋種別＋伝票内の安定した位置）**

| source | 形式 | 例 | 構成要素 |
|---|---|---|---|
| Purchase | `ocr:purchase:{issueYearAD}{issueMonth:02}-{sheetNumber}-{itemNumber}` | `ocr:purchase:202601-3-5` | 伝票発行年月（西暦4桁+月2桁）＋伝票番号＋行番号。伝票内の位置は安定 |
| Deposit | `ocr:deposit:{transactionDate}-{transactionNumber}` | `ocr:deposit:2026-02-05-0012` | `(transactionDate, transactionNumber)` は Android 側で UNIQUE 制約あり＝◎ |
| Receipt | `ocr:receipt:{receiptUuid}:{itemIndex}` | `ocr:receipt:9f1c8b0e-4a2d-4f1a-9b3e-7c6d5e4f3a21:2` | レシート単位の UUID をスマホ側が採番して Room に永続化。`itemIndex` は 0 始まりの明細順 |

- Receipt の `receiptUuid` は**レシート行を作った時点で採番して保存**する
  （品目 `id` は復元で変わるので使わない）。既存レシートには移行時に一度だけ採番。
- 1 枚のレシートを複数仕訳に分ける場合（品目ごとに科目が違う等）、`itemIndex` で分ける。
- レシートを 1 仕訳にまとめる場合は `itemIndex` を `sum` などの固定語にする（`ocr:receipt:{uuid}:sum`）。

---

## 5. `ledgerType` と借方／貸方の組み立て

[README.md](README.md) §3 の表に従う。要点だけ再掲：

| `source` / 区分 | `ledgerType` | `debit.accountCode` | `credit.accountCode` |
|---|---|---|---|
| Purchase | `AP` | 商品の経費科目 | `kaikake`（買掛金） |
| Deposit 入金（元 amount ≥ 0） | `Bank` | 預金口座科目（スロット） | ルールの相手科目 |
| Deposit 出金（元 amount < 0） | `Bank` | ルールの相手科目 | 預金口座科目（スロット） |
| Receipt 現金払い | `Cash` | 品目の経費科目 | `genkin`（現金） |
| Receipt クレカ・電子マネー | `Unpaid` | 品目の経費科目 | `mibarai` / `zigyounusikari` 等 |

- **預金口座科目**は `bankSlotNo` に対応する `vocabulary.accounts[]` の科目。
  スマホは `credit`/`debit` の該当側に、その科目の `code`（`einou` 等）を入れる。
- `ledgerType` を省略した場合、PC は「借方・貸方のうち `ledgerAffinity` が
  `Cash`/`Bank`/`AR`/`AP`/`Unpaid` の科目」からその帳簿を決める。両方該当・両方非該当なら
  `Transfer` 扱い＋「要確認」。

---

## 6. `bankSlotNo`

- `source = Deposit` のとき**必須**（どの預金口座に取り込むか）。
- 値：`0`（親「普通預金」）または `1,2,3,…`（補助口座）。`vocabulary.accounts[].bankSlotNo` と対応。
- Android は単一通帳前提なので、スマホの設定で「この通帳 → スロット 1」のように固定してよい。
  ユーザーが AoiroChobo でしか口座を増やしていない場合は取込 UI 側で選ばせる。
- `source = Purchase` / `Receipt` では `null`。

---

## 7. 金額と返品

- `amount` は**常に正の整数**（税込・円）。
- 元データが負（返品・値引き）の場合：**借方／貸方を入れ替えて**正数で出す。
  - 例：肥料の返品 1,100 円 → 通常仕訳 `Dr hiryou / Cr kaikake` の逆で
    `debit.accountCode = "kaikake"`, `credit.accountCode = "hiryou"`, `amount = 1100`。
  - `meta.isReturn = true` を必ず立てる。
- 端数処理はしない（OCR で読んだ税込額をそのまま）。

---

## 8. `hasInvoice`

- スマホは `meta.registrationNumber`（インボイス登録番号 T+13桁）を渡すだけでよい。
- `hasInvoice` を判断できるなら `true`/`false` を入れる。省略時は PC 側の既定（現状 true 相当）。
- PC 側で取込後に編集可能。

---

## 9. `matchStatus`

| 値 | 意味 | `accountCode` |
|---|---|---|
| `Matched` | 借方・貸方の科目が確定。摘要も辞書 or 妥当 | 両側とも有効な `code` |
| `UnmatchedAccount` | 科目を確定できなかった（推定値はあるかも） | 推定 `code` または `null` |
| `UnmatchedMemo` | 科目は確定、摘要が未確定（フリーテキストのまま） | 有効な `code` |
| `Ambiguous` | 候補が複数あって選べなかった | 第一候補 `code` |

- `Matched` 以外の行は、PC 側で「要確認 → 未確認取込」タブに入れてユーザーに確定させる。
- **どの状態でも行は必ず出す**（落とさない）。

---

## 10. 取込後の扱い（PC 側・スマホは意識しなくてよいが参考）

- 取込仕訳は `SourceType = "OcrImport"`、`ExternalId` に上記の値、`ImportBatchId` に取込バッチ ID。
- 取込後は AoiroChobo が所有し**編集可能**（ロックしない）。ユーザーが金額等を直すと
  `EditedAfterImport = 1` になり、行ヘッダーに橙のバーが付く。
- **物理削除はしない**（`IsVoided = 1` の論理削除のみ）。`ExternalId` は void 行にも残す。
- 再取込：
  - 同じ `externalId` が未編集で存在 → 内容差分があれば更新、なければスキップ。
  - 同じ `externalId` が**編集済み**で内容差分あり → ダイアログで
    「取込値で上書き／このまま維持／別行として追加」をユーザーに選ばせる。
  - 同じ `externalId` が void 済み → 「削除済み N 件」として取込サマリーに表示し、黙って復活させない。

---

## 11. スキーマ検証（契約テスト用の要点）

- `schemaVersion` は整数 `1`。
- `entries[].externalId` は非空・ファイル内で一意・`[a-z0-9:_-]{1,128}`。
- `entryDate` は `^\d{4}-\d{2}-\d{2}$` かつ実在日。
- `amount` は 1 以上の整数。
- `source` は `Purchase` / `Deposit` / `Receipt`。
- `source = Deposit` なら `bankSlotNo` が整数（0 以上）。
- `debit` / `credit` は必須オブジェクト。`accountCode` は `null` か非空文字列。
- `taxRate` / `credit.taxRate` は `null` か `["10","8","8_old","non","na","men"]` のいずれか。
- `matchStatus` は 4 値のいずれか。
- `vocabulary.contentHash`（あれば）が PC 側の当年度スナップショットと一致しないときは
  取込を止めず、サマリーに「マスタが一致しません」と警告表示（PC 側の挙動）。

サンプル：[examples/transactions.sample.json](examples/transactions.sample.json)
