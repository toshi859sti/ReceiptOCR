# transactions.json — 取引データ仕様（スマホ → AoiroChobo）

> スマホ（JA仕訳変換）が出力し、AoiroChobo が取り込む取引データ。**JSON**（③の確定・[README.md](README.md) §3）。
> スマホ側一次仕様書 §10 の JSON 案をベースに、AoiroChobo の `JournalEntry` スキーマへ寄せてある。
>
> 対象：`schemaVersion: 2`

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
  "schemaVersion": 2,
  "kind": "aoirochobo.transactions",
  "generatedAt": "2026-09-10T15:00:00+09:00",
  "generatedBy": {
    "app": "JA仕訳変換",
    "appVersion": "1.4.0",
    "sourceCommit": "5889f13"        // 任意
  },
  "vocabulary": {                    // どの vocabulary.json に対して解決したか
    "schemaVersion": 2,
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
科目の参照は必ず **`accountKey`**、摘要の参照は必ず **`memoKey`**
（[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.4 / §4.8）で行う。
PC 取込は、そのキーを当年度のマスタへ解決するだけ。解決できなければ「要確認」にまわす。

```jsonc
{
  "externalId": "ocr:purchase:3f2b9c14-77a1-4a6e-9c02-1d5e8b4a0c71", // ★ 必須・グローバル一意・復元耐性（§4）
  "source": "Purchase",                       // Purchase | Deposit | Receipt
  "ledgerType": "AP",                         // 省略可（PC が科目から推定）。§「ledgerType」
  "bankSlotNo": null,                         // source=Deposit のとき必須。§「bankSlotNo」
  "entryDate": "2026-01-20",                  // 西暦 ISO yyyy-MM-dd（和暦変換はスマホ側）
  "amount": 11000,                            // 税込・正の整数（円）。§「金額と返品」

  // accountName は任意・人間可読のエコー。PC は照合に使わず、古いマスタの検知だけに使う
  "debit":  { "accountKey": "hiryou",  "accountName": "肥料費", "taxRate": "10", "businessRatio": 100 },
  "credit": { "accountKey": "kaikake", "accountName": "買掛金", "taxRate": null, "businessRatio": 100 },

  "memoKey": "memo-0042",                     // 摘要。★ vocabulary の memoTemplates[].memoKey のみ（閉じた語彙）。
                                             //   逆引き0件なら null。§「摘要は閉じた語彙」
  "memoName": "肥料購入",                     // 任意・人間可読のエコー。PC は照合に使わない（検証・ログ用）
  "note": "ダイアジノン粒剤3",                // メモ欄。★ 生テキスト（商品名・但し書き・通帳メモ）はここ。自由文字
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
| `debit.accountKey` | ✔※ | `vocabulary.accounts[].accountKey`。source ごとの候補フィルタは [vocabulary-snapshot.md](vocabulary-snapshot.md) §4.5。解決不能でも推定値を入れ、`matchStatus` を立てる。本当に不明なら `null` |
| `debit.accountName` | — | `accountKey` が指す科目の `name` のエコー。値は「そのマッチングを決めたときに見えていた名前」。PC は照合に使わず、**現在名と食い違ったらその行を「要確認」に回す**（古いスナップショットの検知。[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.6）。`accountKey` が `null` なら `null`。**送れるときは必ず送ること** |
| `debit.taxRate` | — | [README.md](README.md) §4 のコード。相手科目側にだけ付く。省略/`null` 可 |
| `debit.businessRatio` | — | 事業割合(%)。省略時 100。**100 のままでよい**。`memoKey` が当年度の摘要に解決できた行では PC は**この値を使わず摘要の `businessRatio` を仕訳に入れる**（手入力で摘要を選んだときと同じ）。`memoKey = null` の行だけこの値（相手科目側）が使われる |
| `credit.*` | ✔※ | 借方と同様 |
| `memoKey` | ✔※ | 帳簿の「摘要」列。**`vocabulary.memoTemplates[].memoKey` のいずれかのみ**（閉じた語彙）。キー自体は必須だが、逆引き 0 件のときは `null`（`UnmatchedMemo`）。§「摘要は閉じた語彙」 |
| `memoName` | — | `memoKey` に対応する `name` のエコー。PC は照合に使わない（取込サマリーの表示・契約テストの突き合わせ用）。`memoKey` が `null` なら `null` |
| `note` | — | 帳簿の「メモ」列。**生テキスト（商品名・但し書き・通帳メモ）はここに入れる**。自由文字・長さ制限ゆるめ |
| `hasInvoice` | — | `Receipt` は `true`/`false`、`Purchase`/`Deposit` は省略。省略時 PC 側既定（現状 true 相当）。§「hasInvoice」 |
| `matchStatus` | ✔ | §「matchStatus」 |
| `confidence` | — | OCR / マッチングの自己申告確度 |
| `meta` | — | 参考情報一式。オブジェクトごと省略可 |

※ `debit`/`credit` オブジェクト自体は必須。中の `accountKey` は「不明なら `null`」を許容。

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
| Purchase | `ocr:purchase:{rowUuid}` | `ocr:purchase:3f2b9c14-77a1-4a6e-9c02-1d5e8b4a0c71` | 伝票の**行**ごとに採番して Room に永続化した UUID（`receipt_items.uuid`）。行を挿入・削除・並べ替えても値に付いて動く |
| Deposit | `ocr:deposit:p{passbookId}-{transactionDate}-{transactionNumber}` | `ocr:deposit:p1-2026-02-05-0012` | `passbookId` はスマホ内の通帳の ID（正の整数・バックアップ／復元で保持）。**`bankSlotNo` ではない**。`(passbookId, transactionDate, transactionNumber)` は Android 側で UNIQUE 制約あり＝◎ |
| Receipt | `ocr:receipt:{receiptUuid}:{itemIndex}` | `ocr:receipt:9f1c8b0e-4a2d-4f1a-9b3e-7c6d5e4f3a21:2` | レシート単位の UUID をスマホ側が採番して Room に永続化。`itemIndex` は 0 始まりの明細順 |

- Receipt の `receiptUuid` は**レシート行を作った時点で採番して保存**する
  （品目 `id` は復元で変わるので使わない）。既存レシートには移行時に一度だけ採番。
- 1 枚のレシートを複数仕訳に分ける場合（品目ごとに科目が違う等）、`itemIndex` で分ける。
- レシートを 1 仕訳にまとめる場合は `itemIndex` を `sum` などの固定語にする（`ocr:receipt:{uuid}:sum`）。

**Purchase が伝票内の位置（`{年月}-{伝票番号}-{行番号}`）から UUID に変わった理由**（2026-09-22）

伝票グリッドの「挿入」「削除」は以降の行を 1 つずつシフトし、保存時の `itemNumber` は
リストの位置から振り直される。位置ベースだと **5 行目に 1 行挿入しただけで 6 行目以降の
`externalId` がすべてずれ、空いた番号に隣の行の商品が入る**。PC はそれを「同じ取引の訂正」と
読んで黙って上書きしてしまう（§10）。挿入・削除はワンタップの日常操作なので、
**編集に強い UUID** を採る。

- **UUID は再作成に弱い**（その月を消して入力し直すと全行が新しい `externalId` になる）。
  PC 側はこれを「重複の可能性」として検知して「要確認」に回す（§10）。
- 月データの保存が「全 DELETE → 全 INSERT」でも、UI の行データが `uuid` を持ったまま
  書き戻されるなら値は不変。既存行には移行時に一度だけ採番する。

**`transactionNumber` は必ず値があること**（Deposit・2026-09-22）

通帳 CSV の取引通番が空欄の行は、スマホ側の取込時に**合成番号**を入れて `deposit_meisai` に
保存する。PC 側にフォールバック規則は置かない（`ocr:deposit:p{通帳}-{日付}-{番号}` の 1 本で覆う）。

- 合成番号も `externalId` の文字種（`[a-z0-9:_-]`）に収めること。**`#` は使えない**
  ——PC が「別の取引として追加」の連番サフィックス（`…#2`）に予約している。
- 合成番号は**同じ CSV を取り込み直しても同じ値になる**こと。位置だけで振ると、
  範囲の違う CSV を取り込んだときに番号がずれて同じ取引が二重に届く。

---

## 5. `ledgerType` と借方／貸方の組み立て

[README.md](README.md) §3 の表に従う。要点だけ再掲（科目は `accountKey` で指す）：

| `source` / 区分 | `ledgerType` | `debit.accountKey` | `credit.accountKey` |
|---|---|---|---|
| Purchase | `AP` | 商品の経費科目 | 買掛金 |
| Deposit 入金（元 amount ≥ 0） | `Bank` | 預金口座科目（スロット） | ルールの相手科目 |
| Deposit 出金（元 amount < 0） | `Bank` | ルールの相手科目 | 預金口座科目（スロット） |
| Receipt 貸方が `ledgerAffinity == Cash`（現金） | `Cash` | 品目の経費科目 | 支払方法の科目 |
| Receipt 貸方が `ledgerAffinity == Unpaid`（未払金） | `Unpaid` | 品目の経費科目 | 支払方法の科目 |
| Receipt 貸方がそれ以外（事業主借など） | `Transfer` | 品目の経費科目 | 支払方法の科目 |

- **Receipt の `ledgerType` は貸方（支払方法）の科目の `ledgerAffinity` で決める**（2026-09-30 minor（9））。
  支払手段（クレカ・電子マネー）ではない。返品・値引き（§7）は借方／貸方を入れ替える**前**の貸方で決める。
- Receipt の貸方が預金（`Bank`）・買掛金（`AP`）になる支払方法は扱わない。Receipt は `bankSlotNo` を持てない（§6）。
- **`ledgerType` と科目が合わない行は「要確認」**（2026-09-30 PC 実装）。`Cash`/`Bank`/`AR`/`AP`/`Unpaid` を
  指定したのに、その帳簿の科目（`ledgerAffinity` が同じ科目）が借方にも貸方にも無い行のこと。
  例：「経費 / 事業主借」を `Unpaid`。帳簿ページは科目で仕訳を拾うので、そのまま入れるとどの帳簿にも出ない。
  確定するときは、そのときの科目から下の推定で帳簿を決め直す（例の行は振替伝票に入る）。
  `Transfer` の指定は科目を問わずそのまま使う。
- **`memoKey` の摘要がその帳簿で使えない行も「要確認」**（2026-10-01 minor（10） PC 実装）。
  帳簿は上の「科目と合わなければ推定し直した帳簿」で判定する。使える摘要：

  | 帳簿 | 使える摘要 |
  |---|---|
  | `Cash` | `showInCash`（レシート共通の摘要もこれに当たる） |
  | `Bank` | `showInBank` |
  | `Unpaid` | `ledgerType == "Unpaid"`、または `paymentCommon` |
  | `Transfer` | `ledgerType == "Transfer"`、または `paymentCommon` |
  | `AR` / `AP` | `ledgerType` が同じ |

  例：レシート共通でない現金の摘要を `Transfer` で送った行。そのまま入れると帳簿の摘要の候補に無く、農家が選び直せない。
  要確認画面では状態に「この帳簿で使えない摘要」と出て、摘要を選び直すまで登録できない。

- **預金口座科目**は `bankSlotNo` に対応する `vocabulary.accounts[]` の科目。
  スマホは `credit`/`debit` の該当側に、その科目の `accountKey` を入れる。
- `ledgerType` を省略した場合、PC は「借方・貸方のうち `ledgerAffinity` が
  `Cash`/`Bank`/`AR`/`AP`/`Unpaid` の科目」からその帳簿を決める。両方該当・両方非該当なら
  `Transfer`。推定した行は、推定だけを理由に「要確認」にはしない（以前は「要確認」と書いていたが、
  PC は実装していなかった。2026-09-30 に文書を実装に合わせた）。

---

## 6. `bankSlotNo`

- `source = Deposit` のとき**必須**（どの預金口座に取り込むか）。
- 値：**`1` 〜 `5`**（「普通預金」の補助口座）。`vocabulary.accounts[].bankSlotNo` と対応。
  **`0` は使わない**：親「普通預金」は見出し科目で、それ自体の預金出納帳が存在しないため
  取込先に指定できない（[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.3）。
- スマホは通帳を複数（最大 5 冊）持ち、通帳ごとに口座（`bankSlotNo` を持つ科目）を 1 つ選んでおく
  （2026-09-25 minor（8））。1 つの口座を 2 冊に割り当てない（1 つの出納帳に混ざるため）。
  口座が未設定の通帳の明細は出力しない。
- 通帳に割り当てる口座を選び直しても `externalId` は変わらない（§4 の `passbookId` は `bankSlotNo` と別）。
  PC は同じ取引として受ける（§10）。まだ確定していなければ黙って更新し、確定済みなら預金側の
  `accountKey` が変わるので、上書き／維持／別の取引として追加の 3 択をユーザーに出す。
- `source = Purchase` / `Receipt` では `null`。

---

## 7. 金額と返品

- `amount` は**常に正の整数**（税込・円）。
- **`Purchase` / `Receipt` で**元データが負（返品・値引き）の場合：**借方／貸方を入れ替えて**正数で出す。
  - 例：肥料の返品 1,100 円 → 通常仕訳 `Dr hiryou / Cr kaikake` の逆で
    `debit.accountKey = "kaikake"`, `credit.accountKey = "hiryou"`, `amount = 1100`。
  - `meta.isReturn = true` を必ず立てる。
- **`Deposit` の出金（元 amount < 0）は返品ではない**。通常の資金移動なので `meta.isReturn = false`。
  借方／貸方は §5 の表で入金／出金として既に分岐しており、ここでの入れ替えは起きない。
- 端数処理はしない（OCR で読んだ税込額をそのまま）。

---

## 8. `hasInvoice`

`source` ごとに決める（2026-10-02 minor（12））。

| `source` | `hasInvoice` | 理由 |
|---|---|---|
| `Receipt` | 登録番号（`T\d{13}`）が読めたら `true`、読めなければ **`false`** | レシートに書いてあるかどうかの事実。`hasInvoiceDefault`（推測）では埋めない |
| `Purchase` / `Deposit` | **省略する** | JA の請求書・通帳では登録番号を読んでいない。PC は省略を `true` として記帳する（JA は登録事業者、引き落としの請求書にも番号が載る） |

- 読めた番号は `meta.registrationNumber` にも入れる。
- OCR が番号を読み落とすと、実際はありでも `false` になる。簡易課税なので仕入れ側のインボイスの有無は税額に効かず、
  PC で取込後に直せるので受け入れる。
- 省略時の PC の既定は `true` 相当（摘要の `hasInvoiceDefault` は見ない）。

---

## 9. `matchStatus`

| 値 | 意味 | `accountKey` |
|---|---|---|
| `Matched` | 借方・貸方の科目が確定。摘要も辞書 or 妥当 | 両側とも有効な `accountKey` |
| `UnmatchedAccount` | 科目を確定できなかった（推定値はあるかも） | 推定 `accountKey` または `null` |
| `UnmatchedMemo` | 科目は確定、摘要辞書に一致なし。`memoKey` は `null`、生テキストは `note` に | 有効な `accountKey` |
| `Ambiguous` | 摘要候補が複数あって選べなかった（`memoKey` は第一候補のキー） | 有効な `accountKey` |

- `Matched` 以外の行は、PC 側で「要確認 → 未確認取込」タブに入れてユーザーに確定させる。
- **どの状態でも行は必ず出す**（落とさない）。

### 摘要は閉じた語彙

- `memoKey` に入れてよいのは **`vocabulary.json` の `memoTemplates[].memoKey` のいずれか**だけ。
  スマホが生成した文字列・OCR の生テキストを摘要にしてはいけない。
- 逆引き（[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.2）の結果：
  - 1 件 → その `memoKey`。`matchStatus = "Matched"`、`memoName` にその `name` をエコー。
  - 複数 → 元資料テキストに最も近い候補。決められなければ第一候補。`matchStatus = "Ambiguous"`。
  - **0 件 → `memoKey = null`・`memoName = null`、`matchStatus = "UnmatchedMemo"`**。
- 生テキスト（JA伝票の商品名、レシートの品名、通帳のメモ、領収書の但し書き）は必ず **`note`** に入れる。
  摘要と `note` は別列（摘要＝閉じた語彙、メモ＝自由文字）。
- **PC 側の扱い（実装済 2026-09-14）**：`memoKey = null`（`UnmatchedMemo`）の行は「要確認」で、ユーザーが
  (a) 当年度の摘要辞書から選ぶ、または (b) その場で摘要辞書に新規登録する（科目・税率もセットされ以後の
  手入力でも使える）。**フリーテキストのまま確定させない**。スマホは辞書を作れない（`vocabulary.json` は
  一方向）ので新規登録は PC 側のみ。
- 辞書のカバレッジ前提：主要な経費科目に最低 1 つ汎用プリセットがあること（らくらくのシード摘要辞書は
  この作り）。0 件になるのは AI が珍しい科目を選んだときで、元々ユーザー確認したいケース。

---

## 10. 取込後の扱い（PC 側・スマホは意識しなくてよいが参考）

**2 段階で取り込む（実装済 2026-09-14）**。ファイルの行はまず **`ImportedTransaction`（取込ステージングテーブル）** に
そのまま保存され、ユーザーが確定した行だけが `JournalEntry` になる。

- 理由：`JournalEntry.DebitAccountId` / `CreditAccountId` は**非 NULL の外部キー**なので、
  `accountKey` が解決できない行（`UnmatchedAccount`・`accountKey = null`）は仕訳として保存できない。
  また `matchStatus` / `confidence` / 解決できなかったキー文字列を持つ列も `JournalEntry` には無く、
  そのまま入れるとアプリを再起動した時点で「要確認」の状態が失われる。
- `ImportedTransaction` は受け取った JSON の各フィールドを（解決前の生の `accountKey` / `memoKey`
  文字列のまま）保持し、`ImportBatchId`・`matchStatus`・`confidence`・解決結果・確定済みフラグ・
  生成された `JournalEntryId` を持つ。「要確認」タブはこのテーブルを見る。
- 確定して `JournalEntry` になった行だけが帳簿に現れる。以降の編集・取り消しは AoiroChobo の所有。

**確定後の仕訳**

- 取込仕訳は `SourceType = "OcrImport"`、`ExternalId` に上記の値、`ImportBatchId` に取込バッチ ID。
- 取込後は AoiroChobo が所有し**編集可能**（ロックしない）。ユーザーが金額等を直すと
  `EditedAfterImport = 1` になり、行ヘッダーに橙のバーが付く。
- **物理削除はしない**（`IsVoided = 1` の論理削除のみ）。`ExternalId` は void 行にも残す。
- 再取込：
  - 同じ `externalId` が未編集で存在 → 内容差分があれば更新、なければスキップ。
    ただし**借方／貸方の `accountKey` か `note` が変わっている**ときは黙って更新せず、
    編集済みのときと同じ 3 択（上書き／維持／別の取引として追加）をユーザーに出す
    （2026-09-22 追加）。日付や金額だけの違いは素直な訂正だが、科目や商品名まで変わって
    いるのは「別の取引になった」合図で、`externalId` の採番がずれたときの最後の砦になる。
  - 同じ `externalId` が**編集済み**で内容差分あり → ダイアログで
    「取込値で上書き／このまま維持／別行として追加」をユーザーに選ばせる。
    **「別行として追加」を選んだ行の `JournalEntry.ExternalId` には `#2` `#3` … の連番サフィックスを付ける**
    （`JournalEntry.ExternalId` は UNIQUE 制約付きのため。元の値は `ImportedTransaction` 側に残る）。
  - 同じ `externalId` が void 済み → 「削除済み N 件」として取込サマリーに表示し、黙って復活させない。
- **重複の可能性**（2026-09-22 追加）：`externalId` が未知の行でも、**日付・金額・借方貸方の
  `accountKey` が既存の取込行と同じ**なら、その行を「要確認」に回して取込サマリーに出す
  （`ImportedTransaction.DuplicateOfExternalId` に相手の `externalId` を入れる）。
  スマホが `externalId` を採り直す状況（月をまるごと入力し直した・行の UUID を振り直した）で
  二重計上を防ぐ唯一の手掛かり。取り消し済み（`IsVoided = 1`）の取引とは突き合わせない
  ——消したものを入れ直したのなら、それは重複ではない。
  - **同じファイルの中の行どうしも突き合わせる**（2026-09-25 minor（8））。口座間の振替
    （営農口座 → 直売口座）は両方の通帳に載るので、2 冊を 1 ファイルで出すと同じ取引が
    別の `externalId` で 2 行届く。相手科目にもう一方の口座を選んでいれば、2 行は日付・金額・
    借方貸方がそろうので、**後の行**が「重複の可能性」になる（先の行は普通に入る）。
    スマホ側は振替を検出・除外しなくてよい。

---

## 11. スキーマ検証（契約テスト用の要点）

- `schemaVersion` は整数 `2`。
- `entries[].externalId` は非空・ファイル内で一意・`[a-z0-9:_-]{1,128}`。
- `entryDate` は `^\d{4}-\d{2}-\d{2}$` かつ実在日。
- `amount` は 1 以上の整数。
- `source` は `Purchase` / `Deposit` / `Receipt`。
- `source = Deposit` なら `bankSlotNo` が `1`〜`5` の整数（`0`・`null` は不正）。
- `debit` / `credit` は必須オブジェクト。`accountKey` は `null` か非空文字列。
- `debit.accountName` / `credit.accountName` は省略可。入っている場合は `accountKey` が指す科目の
  `name` と一致すること。`accountKey` が `null` なら `null`。`memoName` と同じ扱い。
  **不一致はエラーにしない**（ファイルは通る）が、PC はその行を**「要確認」に回す**——
  `matchStatus` が `Matched` でも自動では帳簿に入らない（[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.6）。
  あわせて取込サマリーに「マスタが古い可能性」と警告を出す。
- `taxRate` / `credit.taxRate` は `null` か `["10","8","1","8_old","non","na","men"]` のいずれか。
  `"1"` は食料品の軽減税率 1%（2027年から予定。2026-09-14 に追加）。
- `matchStatus` は 4 値のいずれか。
- `memoKey` は `null` か、`vocabulary.memoTemplates[].memoKey` に実在するキー（フリーテキスト不可）。
  `matchStatus = "UnmatchedMemo"` のとき `memoKey` は必ず `null`。`UnmatchedAccount`（科目が `null`）も
  `memoKey` は `null`。`Matched` / `Ambiguous` のときは実在するキー。
- `memoName` は省略可。入っている場合は `memoKey` が指す行の `name` と一致すること。
  `memoKey` が `null` なら `null`。不一致の扱いは `accountName` と同じ（エラーにせず「要確認」へ＋警告）。
- `vocabulary.contentHash`（あれば）が PC 側の当年度スナップショットと一致しないときは
  取込を止めず、サマリーに「マスタが一致しません」と警告表示（PC 側の挙動）。

サンプル：[examples/transactions.sample.json](examples/transactions.sample.json)
