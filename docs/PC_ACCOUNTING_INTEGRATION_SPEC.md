# PC会計アプリ連携仕様書（Androidアプリ「JA仕訳変換」データ仕様）

> **この文書の目的**
> Androidアプリ「JA仕訳変換」（`com.example.greenframeocr` / このリポジトリ）が
> 出力するデータを、別途製作中の **PC会計アプリ** 側の Claude Code / 開発者が
> 正しく解釈・変換できるようにするための一次情報。
>
> PCアプリの計画：
> 1. Androidアプリから **摘要辞書・勘定科目・取引データ** を JSON で取り込む
> 2. PC側で商品名／摘要 → 勘定科目のマッチングを行う
> 3. 仕訳（取引データ）を JSON で出力する
>
> 本書は Android 側の実コード（Kotlin / Room DB v33、2026-09時点）に基づく。
> 迷ったら本書ではなく `app/src/main/java/com/example/greenframeocr/` の実装が正。
>
> **2026-09-29 追記**：PC 会計アプリ（AoiroChobo）との連携の一次情報は `docs/integration/` の契約に移った
> （取引は `transactions.json`・科目は PC 所有の `accountKey`）。本書の §10 の提案などはその前の段階の記述。
> らくらく青色申告農業版は撤去済み（DB v40 で `rakuraku_tekiyou`・`rakuraku_accounts` とそれを指す列を削除、
> らくらく CSV も削除）なので、本書からもらくらくの記述を外した。バックアップ JSON に残るらくらくのキーは取込時に無視される。

---

## 0. 用語と全体像

| 用語 | 意味 |
|---|---|
| JA伝票 / 購買伝票 | 島原雲仙農業協同組合の「購買代金請求明細書」。買掛（掛け仕入）。`receipt_items` |
| 預金 / 通帳 | 銀行通帳の入出金明細。`deposit_meisai` |
| 一般レシート / 領収書 | JA以外の店舗のレシート・領収書。`general_receipts` + `general_receipt_items` |
| 勘定科目 | 会計ソフトの勘定科目マスタ。弥生用 `yayoi_accounts`（あおいろは PC から取り込んだ `aoirochobo_accounts`） |
| canonicalKey | 商品名・品目名の表記ゆれを吸収した正規化文字列（§4.3） |
| 作業年（eraYear） | 令和 X 年。アプリ全体の「いま処理している年度」。西暦 = eraYear + 2018 |

**データフロー（現状）**

```
[JA伝票撮影] ─Gemini OCR→ receipt_items ┐
[通帳手入力/CSV]            deposit_meisai ├→ [出力確認画面] ─→ 弥生 CSV or あおいろ transactions.json
[レシート撮影] ─Gemini OCR→ general_receipt_items ┘

[設定 > データ管理] ─→ JSON バックアップ（全テーブルの生ダンプ）
```

現状、**「仕訳（取引データ）JSON」という出力は存在しない**。
出力は CSV のみ（§7）。PCアプリはこの CSV が担っている「勘定科目引き当て＋
借方／貸方の組み立て」を JSON ベースで置き換える想定と理解している。

PCアプリが取り込む入力は **設定＞データ管理の JSON バックアップ**（§5）。

---

## 1. アプリの役割と前提

- 対象ユーザー：農業経営者（簿記の専門知識は薄い）。会計ソフトは
  **弥生の青色申告** または **あおいろ帳簿**（自作 PC アプリ AoiroChobo）のどちらか（設定で切替、§9）。
- 課税方式：簡易課税・税込入力・農業（第二種）を想定。
- Androidアプリは「証憑 → 明細データ化 + 勘定科目/摘要の割り当て」までを担当。
  最終的な仕訳帳登録は会計ソフト（CSVインポート）が担当。
- 金額はすべて **税込・整数（円）**。消費税額は分離しない。

---

## 2. 連携方式

### 2.1 ファイル形式

| 種別 | 形式 | 文字コード | 備考 |
|---|---|---|---|
| データバックアップ（PCアプリの入力想定） | JSON | UTF-8 (BOMなし) | Gson `setPrettyPrinting()` 整形済み |
| 弥生 CSV | CSV | **windows-31j (MS932)**・改行CRLF | 全フィールドダブルクォート囲み |

### 2.2 JSON のシリアライズ規約（Gson）

- Kotlin data class のプロパティ名 = JSON のキー名（キャメルケース）。
- `Int` / `Long` → JSON 数値、`Boolean` → `true`/`false`、`String` → 文字列。
- Nullable プロパティが null のとき：**キー自体が出力されない**ことがある
  （Gson デフォルト動作）。PC側は「キー欠落 = null」として扱うこと。
- 日時プロパティはすべて **文字列**。ISO 8601 ではなく後述の独自形式。

---

## 3. 共通データ規約

### 3.1 日付

| フィールド | 形式 | 例 | 備考 |
|---|---|---|---|
| `receipt_items.receiptYear/Month/Day` | 分割された整数。**年は令和年** | 7, 5, 20 | 西暦 = `receiptYear + 2018` |
| `receipt_items.issueYear/issueMonth` | 整数。**令和年**（伝票発行年月） | 7, 5 | |
| `deposit_meisai.transactionDate` | `yyyy-MM-dd`（西暦） | `2026-02-05` | |
| `general_receipts.date` | `yyyy-MM-dd`（西暦） | `2026-02-05` | Gemini が読んだ日付。空文字あり得る |
| `*.exportedAt` | `yyyy/MM/dd HH:mm`（西暦・ローカル時刻） | `2026/09/09 14:30` | CSV出力済みマーク。null = 未出力 |
| `*.createdAt` / `cachedAt` | Unix epoch ミリ秒（Long） | `1725866400000` | |

**和暦変換（会計ソフト向け）**：`CsvUtils.toYayoiDate()`

```
西暦(year,month,day):
  令和 = year > 2019 or (year == 2019 and month >= 5)
  令和なら "R.{year-2018:02}/{month:02}/{day:02}"   例: 2026-01-20 → R.08/01/20
  それ以外 "H.{year-1988:02}/{month:02}/{day:02}"
```

### 3.2 金額の符号

| データ | 正の値 | 負の値 |
|---|---|---|
| `receipt_items.amount` | 通常の購入 | 返品・値引き |
| `deposit_meisai.amount` | 入金 | 出金 |
| `general_receipt_items.price` | 通常（負は想定していない） | — |

### 3.3 「小計行」「合計行」（JA伝票のみ）

`receipt_items` には明細行だけでなく集計行も混在する：

- 小計行：`productName` が `"[小計] 一般購買"` のように `[小計] ` プレフィックス付き。
- 合計行：`productName` が `"合計"`。
- **仕訳出力時はこれらを除外する**。判定は
  `productName.contains("小計") || productName.contains("合計")`
  （Android実装 `OutputConfirmScreen.loadPurchaseOutputItems`）。
- 小計・合計行の `productMasterId` は null。

### 3.4 文字コード注意（弥生 CSV）

`①`・`㈱` 等の機種依存文字は `Shift_JIS` だと無警告で `?` 化するため
Android 側は `windows-31j` を優先使用。PCアプリで弥生 CSV を作る場合も同様に。

---

## 4. マッチングの土台

### 4.1 全体像

| 対象 | キー | 参照先マスタ | 得られるもの |
|---|---|---|---|
| JA購買 明細 | `productMasterId`（FK） / なければ `productName` の canonicalKey | `product_master` | `yayoiAccountId`（弥生科目）, `accountKey`・`memoKey`（あおいろ） |
| 預金 明細 | `normalizeTekiyou(tekiyou) + "_" + (D|W)` | `tekiyou_matching_rules` | `yayoiAccountId`, `accountKey`・`memoKey` |
| レシート 品目 | `canonicalKey`（`itemName` 由来） | `general_item_master`（グループデフォルト） + 明細の個別上書き | `yayoiAccountId` |
| レシート 支払方法 | `general_receipts.paymentMethodText` の部分一致 | `receipt_payment_method_rules` | 相手科目（貸方）`yayoiAccountId` |

### 4.2 補助科目（弥生）の解決

`yayoi_accounts` は自己参照（`parentId`）で親子2階層。

```
account = yayoi_accounts[targetId]
if account.parentId != null:
    親科目名  = yayoi_accounts[account.parentId].accountName   ← CSVの「借方勘定科目」
    補助科目名 = account.accountName                            ← CSVの「借方補助科目」
else:
    勘定科目名 = account.accountName
    補助科目名 = ""
```

### 4.3 `toCanonicalKey(name)` — 商品名・品目名の正規化キー

`util/ProductNameInputUtils.kt`。**マッチングの要。PC側で完全に同じ実装が必要**。

処理（先頭から1文字ずつ）：

1. 半角スペース `' '`・全角スペース `'　'` は **除去**。
2. 半角カタカナ + `ﾞ`（濁点） → 対応する全角濁音（`ｶﾞ`→`ガ` 等、2文字消費）。
3. 半角カタカナ + `ﾟ`（半濁点） → 対応する全角半濁音（`ﾊﾟ`→`パ` 等）。
4. 単独の半角カタカナ → 全角カタカナ（`ｱ`→`ア`、`ｰ`→`ー` 等）。
5. コードポイントが `U+FF01`〜`U+FF5E`（全角ASCII記号・数字・英字） → `code - 0xFEE0`（半角化）。
   例：`２５０ｇ` → `250g`、`（` → `(`、`－` → `-`。
6. それ以外はそのまま。

> 全角カナ→半角化・ひらがな⇔カタカナ・大文字小文字の統一は **しない**。
> 記号のダッシュ類は「幅（全角/半角）のみ」を揃える。字体（`—`/`ー`/`-`）は揃わない。

対応表（半角カナ→全角）は実コード参照。濁点結合の対象：
`ｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾊﾋﾌﾍﾎｳ`、半濁点：`ﾊﾋﾌﾍﾎ`。

### 4.4 `normalizeTekiyou(tekiyou)` — 通帳摘要の正規化

`OutputConfirmScreen.kt` / `TekiyouMatchingScreen.kt`（同一実装が2箇所にある）。

```
tekiyou
  .replace(Regex("\\s+\\d{2}-\\d{2}$"), "")   // 末尾 " 05-20" 等（日付らしき数字）を除去
  .replace(Regex("\\s+\\d{4}$"), "")           // 末尾 " 1234" 等を除去
  .replace(Regex("\\s+$"), "")
  .trim()
```

### 4.5 預金のパターンキー

```
isDeposit = (amount >= 0)          // 入金
suffix    = isDeposit ? "_D" : "_W"
patternKey = normalizeTekiyou(tekiyou) + suffix
rule = tekiyou_matching_rules[pattern == patternKey]   // pattern 列は UNIQUE
```

`tekiyou_matching_rules.pattern` は上記そのままの値が入っている（例：`フリコミアグリサービス_D`）。
`isRegex` フラグはあるが現状の照合はキー完全一致のみ（正規表現マッチは未使用）。

---

## 5. データバックアップ JSON スキーマ（PCアプリの入力）

Android：**設定 → データ管理 → エクスポート**。5種類。

| 種別 | ルートクラス | ファイル名例 | 内容 |
|---|---|---|---|
| すべて | `AllExportData` | `all_backup_YYYYMMDD_HHmmss.json` | 全テーブル |
| 購買伝票 | `PurchaseExportData` | `purchase_*.json` | `receiptItems` / `sheetData` / `monthlyData` |
| 通帳 | `DepositExportData` | `deposit_*.json` | `depositMeisai` |
| マスタ | `MasterExportData` | `master_*.json` | 商品・ルール・弥生の勘定科目 |
| レシート領収書 | `ReceiptExportData` | `receipt_*.json` | `generalReceipts` ほか |

**PCアプリは「マスタ」で摘要辞書・勘定科目を、「すべて」または各種別で取引データを取得できる。**

### 5.1 ルートオブジェクト

```jsonc
// AllExportData
{
  "exportDate": "2026-09-09 14:30:00",   // yyyy-MM-dd HH:mm:ss
  "dataType": "all",
  "version": 2,
  "receiptItems":        [ ReceiptItem ],
  "sheetData":           [ SheetData ],
  "monthlyData":         [ MonthlyData ],
  "depositMeisai":       [ DepositMeisai ],
  "productMasters":      [ ProductMaster ],
  "ocrVariants":         [ OcrVariant ],
  "tekiyouMatchingRules":[ TekiyouMatchingRule ],
  "yayoiAccounts":       [ YayoiAccount ],    // v2以降。旧ファイルは欠落し得る
  "generalReceipts":     [ GeneralReceipt ],
  "generalReceiptItems": [ GeneralReceiptItem ],
  "invoiceStores":       [ InvoiceStore ],
  "generalItemMasters":  [ GeneralItemMaster ],
  "receiptPaymentMethodRules": [ ReceiptPaymentMethodRule ]
}
```

```jsonc
// MasterExportData
{
  "exportDate": "...", "dataType": "master", "version": 3,
  "productMasters":      [ ProductMaster ],
  "ocrVariants":         [ OcrVariant ],
  "tekiyouMatchingRules":[ TekiyouMatchingRule ], // v3以降。null あり得る
  "yayoiAccounts":       [ YayoiAccount ]         // v3以降。null あり得る
}
// 2026-09-29 より前のファイルには "rakurakuTekiyou" / "rakurakuAccounts" がある（取込時は無視）
// PurchaseExportData : exportDate, dataType="purchase", receiptItems, sheetData, monthlyData
// DepositExportData  : exportDate, dataType="deposit",  depositMeisai
// ReceiptExportData  : exportDate, dataType="receipt",  generalReceipts, generalReceiptItems,
//                      invoiceStores, generalItemMasters, receiptPaymentMethodRules
```

### 5.2 エンティティ定義

#### ReceiptItem（`receipt_items`）JA購買 明細行

| キー | 型 | 説明 |
|---|---|---|
| `id` | Long | PK |
| `issueYear` | Int | 伝票発行年（**令和年**） |
| `issueMonth` | Int | 伝票発行月 |
| `sheetNumber` | Int | 何枚目の伝票か（月内連番） |
| `itemNumber` | Int | 伝票内の行番号 |
| `receiptYear` | Int | 領収日 年（**令和年**）。西暦 = +2018 |
| `receiptMonth` | Int | 領収日 月 |
| `receiptDay` | Int | 領収日 日 |
| `productName` | String | 商品名。`[小計] xxx` / `合計` は集計行（§3.3） |
| `amount` | Int | 税込金額。負 = 返品・値引き |
| `category` | String | §9 参照。`一般購買`/`給油所`/`農業機械`/`未分類`。まれに一時値 `未定`・`月合計` |
| `isOcrOverwriteTarget` | Boolean | 再OCR対象マーク（連携では無視可） |
| `ocrConfidence` | String? | Gemini自己申告 `high`/`medium`/`low`。手入力・旧データは null |
| `productMasterId` | Long? | `product_master.id`。未マッチ・集計行は null |
| `exportedAt` | String? | CSV出力日時 |

#### SheetData（`sheet_data`）伝票単位サマリー

複合PK `(issueYear, issueMonth, sheetNumber)`。OCR/手入力した伝票の小計・合計欄。
検算用途。仕訳生成には通常不要。

| キー | 型 | 説明 |
|---|---|---|
| `issueYear` / `issueMonth` / `sheetNumber` | Int | PK（令和年） |
| `totalFromInput` | Int? | 伝票の合計欄 |
| `subtotalGeneral` / `subtotalGas` / `subtotalAgri` | Int? | 一般購買 / 給油所 / 農業機械 の小計欄 |
| `isTotalOcrTarget` ほか `is*OcrTarget` | Boolean | 再OCRマーク |

#### MonthlyData（`monthly_data`）月次サマリー

| キー | 型 | 説明 |
|---|---|---|
| `id` | String | `"YYYY_MM"`（令和年） |
| `issueYear` / `issueMonth` | Int | 令和年 |
| `totalSheets` | Int | 枚数 |
| `generalPurchaseTotal` / `agriculturalTotal` / `gasStationTotal` | Int | カテゴリ別合計 |
| `monthlyTotal` | Int | 月合計 |

#### DepositMeisai（`deposit_meisai`）通帳明細

`(transactionDate, transactionNumber)` に UNIQUE 制約。

| キー | 型 | 説明 |
|---|---|---|
| `id` | Int | PK |
| `transactionDate` | String | `yyyy-MM-dd`（西暦） |
| `transactionNumber` | String | 取引通番（通帳の連番） |
| `tekiyou` | String | 摘要 原文（例：`フリコミ アグリサービス`） |
| `amount` | Int | 正 = 入金 / 負 = 出金 |
| `memo` | String | ユーザーメモ（通常空） |
| `matchingRuleId` | Int? | `tekiyou_matching_rules.id`（参照用キャッシュ。§6.2 では pattern で引き直す） |
| `overrideYayoiAccountId` | Long? | 個別上書き `yayoi_accounts.id`（弥生用）。null = ルールに従う |
| `exportedAt` | String? | CSV出力日時 |

> 個別上書きはルールより優先する（2026-09-09 から Android の出力もそうしている。レシート側 §6.3 と同じ思想）。

#### ProductMaster（`product_master`）購買品リスト

`(canonicalKey, category)` に UNIQUE 制約。

| キー | 型 | 説明 |
|---|---|---|
| `id` | Long | PK |
| `canonicalName` | String | 表示用の商品名（スペース・表記を保持） |
| `canonicalKey` | String | `toCanonicalKey(canonicalName)`（§4.3） |
| `category` | String | `一般購買` / `給油所` / `農業機械` |
| `frequencyCount` | Int | 使用回数（マッチング優先度の参考） |
| `isCertified` | Boolean | 手動確定済みフラグ |
| `yayoiAccountId` | Long? | `yayoi_accounts.id`（弥生モードの借方科目） |

#### TekiyouMatchingRule（`tekiyou_matching_rules`）預金摘要マッチングルール

`pattern` に UNIQUE 制約。

| キー | 型 | 説明 |
|---|---|---|
| `id` | Int | PK |
| `pattern` | String | `normalizeTekiyou(tekiyou) + "_D"|"_W"`（§4.5） |
| `normalizedTekiyou` | String | 表示用の正規化摘要名 |
| `isRegex` | Boolean | 複数サンプルから作られたら true（現状照合には未使用） |
| `sampleText` | String | 元の摘要テキスト例 |
| `matchCount` | Int | 該当明細数 |
| `isDeposit` | Boolean | true = 入金ルール / false = 出金ルール |
| `yayoiAccountId` | Long? | `yayoi_accounts.id`（弥生モード）。null = 未割当 |

#### YayoiAccount（`yayoi_accounts`）弥生 勘定科目

`accountCode` にインデックス（NULL 許容）。

| キー | 型 | 説明 |
|---|---|---|
| `id` | Long | PK |
| `accountName` | String | 科目名 or 補助科目名 |
| `searchKeyAlpha` | String | ローマ字サーチキー |
| `accountCode` | String? | 科目コード。補助科目・一部科目は null |
| `debitCredit` | String | `借` / `貸` |
| `categoryA` | String | 大分類：`資産`/`負債`/`資本`/`収入`/`経費`/`引当金等` |
| `categoryB` | String | 中分類（例：`農業生産費`、`現金・預金`） |
| `defaultTaxCategory` | String | 税区分。実データ値：`対象外` / `課対仕入10` / `課対仕入8` / `課税売上` / `非課税`（そのまま弥生CSVの税区分列に出力される） |
| `usedForPurchase` | Boolean | 購買取引の科目候補に出す |
| `usedForDeposit` | Boolean | 預金取引の科目候補に出す |
| `usedForReceipt` | Boolean | レシート領収書の科目候補に出す |
| `isEnabled` | Boolean | 有効フラグ |
| `parentId` | Long? | 親科目 `yayoi_accounts.id`（補助科目のとき非null） |

#### GeneralReceipt（`general_receipts`）レシート・領収書 ヘッダ

| キー | 型 | 説明 |
|---|---|---|
| `id` | Long | PK |
| `date` | String | `yyyy-MM-dd`（西暦）。Gemini が読めなければ空文字 |
| `storeName` | String | 店舗・発行者名 |
| `total` | Int | レシート合計（税込） |
| `rawOcrText` | String | 生 OCR テキスト（現状ほぼ空） |
| `geminiUsed` | Boolean | Gemini で解析したか |
| `registrationNumber` | String | インボイス登録番号 `T`+13桁。無ければ空文字 |
| `createdAt` | Long | epoch millis |
| `paymentMethodText` | String? | 合計欄付近の支払方法印字（例：`クレジット`、`PayPay`）。無ければ null |
| `paymentAccountOverride` | Long? | 相手科目（貸方）の個別上書き `yayoi_accounts.id`。null = ルール自動判定 |

#### GeneralReceiptItem（`general_receipt_items`）レシート 明細（品目）

`receiptId` → `general_receipts.id`（CASCADE削除）。

| キー | 型 | 説明 |
|---|---|---|
| `id` | Long | PK |
| `receiptId` | Long | 親レシート |
| `itemName` | String | 品目名（=但し書き） |
| `price` | Int | 税込金額 |
| `category` | String | `未分類` ほか（レシート側は分類ロジック弱め） |
| `tekiyouId` | Int? | 旧・未使用寄り。基本 null |
| `yayoiAccountId` | Long? | **この明細だけの個別上書き**科目。null = グループデフォルト（§6.3） |
| `isExcluded` | Boolean | true = 経費対象外／集計・出力から除外すべき |
| `canonicalKey` | String | `toCanonicalKey(itemName)`。品目グルーピングのキー |
| `exportedAt` | String? | CSV出力日時 |

#### GeneralItemMaster（`general_item_master`）品目グループのデフォルト科目

| キー | 型 | 説明 |
|---|---|---|
| `canonicalKey` | String | PK（`general_receipt_items.canonicalKey` と対応） |
| `yayoiAccountId` | Long? | このグループのデフォルト借方科目。null 可 |

#### ReceiptPaymentMethodRule（`receipt_payment_method_rules`）支払方法 → 相手科目ルール

| キー | 型 | 説明 |
|---|---|---|
| `id` | Long | PK |
| `keyword` | String | `paymentMethodText` に部分一致させるキーワード（例：`クレジット`、`カード`、`PayPay`） |
| `yayoiAccountId` | Long | 一致時に採用する相手科目（貸方）`yayoi_accounts.id` |
| `sortOrder` | Int | 評価順（昇順、次いで id 昇順）。**先に一致したルール**を採用 |

#### InvoiceStore（`invoice_stores`）インボイス登録番号キャッシュ

| キー | 型 | 説明 |
|---|---|---|
| `registrationNumber` | String | PK。`T`+13桁 |
| `storeName` | String | 事業者名 |
| `address` | String | 住所（現状空が多い） |
| `cachedAt` | Long | epoch millis |

#### OcrVariant（`ocr_variants`）OCR 誤認識辞書（**連携ではほぼ無視してよい**）

商品名 OCR 誤読の学習テーブル。学習の書き込み経路は 2026-08 に削除済みで
**実質非稼働**。CSV出力時の商品名照合フォールバックとして読み取り専用で残存。
`productId` → `product_master.id`。詳細フィールドは `data/OcrVariant.kt` 参照。
PCアプリのマッチングでは `product_master.canonicalKey` 完全一致を使えば十分。

---

## 6. マッチング仕様（勘定科目・摘要の引き当て）

会計ソフトの選択（`AccountingSoftware` = `YAYOI` / `AOIRO`）で分岐する。以下は弥生モードの引き当て。
あおいろモードは `accountKey` / `memoKey` を使い、組み立ては `util/AoiroChoboTransactionsBuilder.kt`
（契約は `docs/integration/transaction-import.md`）。

### 6.1 JA購買 明細 → 科目/摘要

対象：`receipt_items` のうち集計行（§3.3）を除いた行。

**Step 1. 商品マスタの特定**（優先順）
1. `item.productMasterId` があれば `product_master[productMasterId]`。
2. なければ `product_master` を `canonicalKey == toCanonicalKey(item.productName)` で検索。
3. （Android は更に `ocr_variants` フォールバックするが、PC では 1–2 で十分）
4. 見つからなければ **未マッチ**（弥生モードでは出力ブロック対象、§7.3）。

**Step 2. 弥生モード**
```
account = yayoi_accounts[ product.yayoiAccountId ]      // 無ければ未設定
借方勘定科目/補助科目 = §4.2 の解決
借方税区分 = account.defaultTaxCategory（無ければ "対象外"）
```

### 6.2 預金 明細 → 科目/摘要

対象：`deposit_meisai` 全件。

```
patternKey = normalizeTekiyou(meisai.tekiyou) + (meisai.amount >= 0 ? "_D" : "_W")
rule = tekiyou_matching_rules[ pattern == patternKey ]
```

**弥生モード**
```
（まず meisai.overrideYayoiAccountId を見る。非nullならそれを採用）
account = yayoi_accounts[ rule.yayoiAccountId ]
勘定科目/補助科目 = §4.2
税区分 = account.defaultTaxCategory
```

> ルールが見つからない／科目未割当 = 未マッチ。

### 6.3 レシート品目 → 借方科目

対象：`general_receipt_items`（`isExcluded == true` は除外すべき）。

**借方科目の解決（優先順）**
```
1. item.yayoiAccountId （個別上書き）が非null → それ
2. general_item_master[ item.canonicalKey ].yayoiAccountId （グループデフォルト）
3. どちらも無ければ未設定（弥生モードは出力ブロック対象）
account = yayoi_accounts[ 上記 id ]
借方勘定科目/補助科目 = §4.2
借方税区分 = account.defaultTaxCategory（無ければ "対象外"）
```

**相手科目（貸方）の解決（優先順）** — レシートヘッダ単位

```
1. receipt.paymentAccountOverride が非null → yayoi_accounts[それ].accountName
2. receipt.paymentMethodText が非空:
     rules = receipt_payment_method_rules を (sortOrder, id) 昇順
     rule  = 最初に paymentMethodText.contains(rule.keyword, ignoreCase=true) となるもの
     → yayoi_accounts[ rule.yayoiAccountId ].accountName
3. yayoi_accounts の中で accountName == "現金" のもの
4. どれも無ければ固定文字列 "現金"
貸方税区分は常に "対象外"
```

---

## 7. 既存 CSV 出力仕様（仕訳ロジックの参照実装）

PCアプリの「取引データ JSON」はこの CSV が担っている借方／貸方の組み立てを
踏襲すればよい。以下は Android の実装そのまま。

（§7.1・7.2・7.5 にあったらくらくのシンプル CSV は 2026-09-29 に出力ごと削除した。節番号は参照を保つため詰めていない）

### 7.3 弥生：購買・預金 CSV（25列・windows-31j・CRLF・全項目クォート・ヘッダなし）

> ⚠ **既知の不整合**：購買/預金の弥生行ビルダー（`OutputConfirmScreen.buildPurchaseYayoiRow` /
> `buildDepositYayoiRow`）は **先頭の識別フラグ `"2000"` を出していない**。
> 一方レシート側（§7.4）は先頭に `"2000"` を付ける「正しい 25 列」形式。
> PCアプリでは弥生の正式仕様（先頭 `"2000"`、§7.4 の並び）に統一することを推奨。
> 以下は現状の購買/預金ビルダーの実際の列（0始まりindex）：

**購買（`buildPurchaseYayoiRow`）** — 仕訳：借方=商品科目 / 貸方=買掛金
| idx | 値 |
|---|---|
| 0 | 取引日付（和暦 `R.yy/MM/dd`） |
| 1 | 伝票番号（空） |
| 2 | 伝票摘要 = `productName`（40文字まで） |
| 3 | 借方部門（空） |
| 4 | 借方勘定科目 = 商品の弥生科目（親科目名） |
| 5 | 借方補助科目 |
| 6 | 借方税区分 = `defaultTaxCategory` |
| 7 | 借方金額 = `amount` |
| 8 | 借方消費税額（空） |
| 9 | 貸方部門（空） |
| 10 | 貸方勘定科目 = `"買掛金"`（固定） |
| 11 | 貸方補助科目（空） |
| 12 | 貸方税区分 = `"対象外"` |
| 13 | 貸方金額 = `amount` |
| 14 | 貸方消費税額（空） |
| 15–24 | 空 |

**預金（`buildDepositYayoiRow`）** — 金額 = `abs(amount)`
- 入金（`amount>=0`）：借方 `普通預金`/`対象外` ／ 貸方 = ルールの科目(+補助)/`defaultTaxCategory`
- 出金（`amount<0`）：借方 = ルールの科目(+補助)/`defaultTaxCategory` ／ 貸方 `普通預金`/`対象外`
- idx 0=和暦日付, 2=摘要(`tekiyou` 40字), 4/5/6/7=借方科目/補助/税区分/金額, 10/11/12/13=貸方…

### 7.4 弥生：レシート領収書 CSV（25列・正式並び・先頭 `"2000"`）

`GeneralReceiptOutputScreen.buildYayoiRow`。仕訳：借方=品目科目 / 貸方=相手科目（§6.3）。

| # | 項目 | 値 |
|---|---|---|
| 1 | 識別フラグ | `2000` |
| 2 | 伝票No | 空 |
| 3 | 決算 | 空 |
| 4 | 取引日付 | 和暦 `R.yy/MM/dd` |
| 5 | 借方勘定科目 | 品目の弥生科目（親科目名） |
| 6 | 借方補助科目 | `debitSubAccountName` |
| 7 | 借方部門 | 空 |
| 8 | 借方税区分 | `defaultTaxCategory` |
| 9 | 借方金額 | `price` |
| 10 | 借方税金額 | `0` |
| 11 | 貸方勘定科目 | 相手科目名（`現金`/`クレジット`… §6.3） |
| 12 | 貸方補助科目 | 空 |
| 13 | 貸方部門 | 空 |
| 14 | 貸方税区分 | `対象外` |
| 15 | 貸方金額 | `price` |
| 16 | 貸方税金額 | `0` |
| 17 | 摘要 | `itemName`（40文字まで） |
| 18–24 | 番号/期日/タイプ/生成元/仕訳メモ/付箋1/付箋2 | `""`,`""`,`"0"`,`""`,`""`,`"0"`,`"0"` |
| 25 | 調整 | `no` |

---

## 8. 未マッチ時の扱い（重要）

- **弥生モード**：借方科目（購買）／科目（預金）／品目科目（レシート）が未設定の
  行がチェックされたまま出力しようとすると **ブロックダイアログ** が出て CSV を出せない。
  デフォルトでも未設定行はチェックOFFで読み込まれる。
- **あおいろモード**：未設定でも止めずに出す（`matchStatus` で PC 側に伝える。契約 `docs/integration/transaction-import.md`）。
- **出力済み（`exportedAt != null`）** の行は二重計上防止でデフォルト・チェックOFF。
  PCアプリでも「出力済みフラグ」を尊重するか、独自に出力履歴を管理すること。

PCアプリの取引 JSON では、各明細に **「マッチ状態」** を持たせ、未マッチを
呼び出し側が判別できるようにすることを推奨（§10 の `matchStatus`）。

---

## 9. 列挙値カタログ

| 種別 | 取り得る値 |
|---|---|
| `AccountingSoftware`（アプリ設定・JSONには非含有） | `YAYOI`（弥生の青色申告） / `AOIRO`（あおいろ帳簿）。旧値 `RAKURAKU`・`BLUE_RETURN_PREP` は読み出し時に移す |
| `ReceiptItem.category` | `一般購買` / `給油所` / `農業機械` / `未分類`（＋一時値 `未定`・`月合計`） |
| `ProductMaster.category` | `一般購買` / `給油所` / `農業機械` |
| `ReceiptItem.ocrConfidence` | `high` / `medium` / `low` / null |
| `YayoiAccount.debitCredit` | `借` / `貸` |
| `YayoiAccount.categoryA` | `資産` / `負債` / `資本` / `収入` / `経費` / `引当金等` |
| `YayoiAccount.defaultTaxCategory`（実データ） | `対象外` / `課対仕入10` / `課対仕入8` / `課税売上` / `非課税` |
| 弥生CSV税区分列（現状は上記文字列をそのまま出力） | — |
| `TekiyouMatchingRule.pattern` サフィックス | `_D`（入金） / `_W`（出金） |
| インボイス登録番号 | `T` + 数字13桁 |

### 初期マスタ（`app/src/main/assets/*.csv`）

アプリ初回起動時にこれらから DB を初期化する。PCアプリが空 DB のユーザーを想定するなら
同等の初期科目・摘要を用意する必要がある。

| ファイル | 内容 | ヘッダ |
|---|---|---|
| `yayoi_accounts.csv` | 弥生 勘定科目 初期データ | `勘定科目,サーチキー英字,サーチキー数字,借貸,区分C,区分B,区分A,購買取引使用,預金取引使用,親科目` |
| `product_master.csv` | 商品マスタ実績 | `id,canonical_name,category,frequency_count,yayoi_account_id` |
| `ocr_variants.csv` | OCR誤読辞書（非稼働・参考） | `product_id,variant_text,occurrence_count,last_seen` |

> `yayoi_accounts` の実エンティティは `区分C` を持たず `categoryA/categoryB/defaultTaxCategory`。
> CSV の「区分C/B/A」列はインポータ側でマッピングされる。DB（＝JSON）側の定義（§5.2）が正。

---

## 10. PCアプリ向け：取引データ JSON 出力スキーマ（提案 / 要すり合わせ）

現状 Android にこの形式は無い。以下は「CSV が担っている情報を PC で JSON 化するなら」
という **たたき台**。確定仕様は両アプリ開発で合意すること。

```jsonc
{
  "schemaVersion": 1,
  "generatedAt": "2026-09-09T14:30:00+09:00",
  "accountingSoftware": "YAYOI",
  "sourceApp": "JA仕訳変換",
  "entries": [
    {
      "source": "PURCHASE",               // PURCHASE | DEPOSIT | RECEIPT
      "sourceId": 123,                     // 元テーブルの id（receipt_items.id 等）
      "date": "2026-01-20",               // 西暦 ISO。和暦変換は取り込み側 or ここで両方
      "wareki": "R.08/01/20",
      "debit":  { "account": "肥料費", "subAccount": "",        "taxCategory": "課対仕入10", "amount": 11000 },
      "credit": { "account": "買掛金", "subAccount": "",        "taxCategory": "対象外",     "amount": 11000 },
      "summary": "ダイアジノン粒剤3",       // 摘要（メモ/商品名/品目名）
      "note": "",
      "matchStatus": "MATCHED",           // MATCHED | UNMATCHED_ACCOUNT | UNMATCHED_TEKIYOU
      "meta": {
        "productMasterId": 42,
        "category": "一般購買",
        "storeName": null,
        "registrationNumber": null,
        "ocrConfidence": "high",
        "isReturn": false,                 // amount < 0 由来
        "exportedAt": null
      }
    }
  ]
}
```

**仕訳の借方／貸方の決め方**（§6・§7 の要約）：

| source | 借方 | 貸方 | 金額 | 摘要 |
|---|---|---|---|---|
| PURCHASE | 商品の科目（`product.yayoiAccountId`） | `買掛金`（固定） | `amount` | `productName` |
| DEPOSIT 入金 | `普通預金` | ルールの科目（`rule.yayoiAccountId`） | `abs(amount)` | `tekiyou` |
| DEPOSIT 出金 | ルールの科目 | `普通預金` | `abs(amount)` | 同上 |
| RECEIPT | 品目の科目（個別上書き→グループデフォルト） | 相手科目（個別上書き→支払方法ルール→`現金`） | `price` | `itemName` |

- 返品・値引き（`amount < 0`）：Android は符号付きのまま 1 行で出す。
  PCで貸借を反転させるか、マイナス金額のまま出すかは会計ソフト仕様に合わせて要検討。

---

## 11. 既知の注意点・落とし穴

1. **令和年 / 西暦の混在**：`receipt_items` は令和年（+2018で西暦）、
   `deposit_meisai`・`general_receipts` は西暦 `yyyy-MM-dd`。
2. **`normalizeTekiyou` / `toCanonicalKey` は 2 箇所以上に重複実装**があり、
   将来ズレる可能性。PC 側は本書 §4.3 / §4.4 を単一の真実として実装し、
   Android 実装の変更を監視すること。
3. ~~預金の個別上書きが Android CSV 出力に反映されていない~~ → 2026-09-09 に修正済み（上書きを最優先）。
4. **`isExcluded`（レシート品目）** は集計・出力から除外すべきだが、
   Android の `loadOutputItems()` はフィルタしていない（`buildCsvForExport()` はする）。
   PC は必ず除外。
5. **弥生 CSV の列並びが購買/預金 と レシート で違う**（§7.3 の `"2000"` 欠落）。
   PC は正式 25 列（§7.4）に統一推奨。
6. **弥生税区分文字列**：DB 値（`課対仕入10` 等）がそのまま CSV に出るが、
   やよいの青色申告が実際に受け付ける文字列（`課対仕入込10%` 等）とは
   表記が異なる可能性。実際のインポート検証が必要（`docs/yayoi-csv-export-spec.md`
   は別プロジェクト "AoiroChobo" 由来の参考資料で、表記が食い違う）。
7. **勘定科目マスタが空のユーザー**：初期 CSV（§9）投入前だと科目 0 件。
8. ~~`rakuraku_accounts` はマスタとして存在するが仕訳生成にほぼ未使用~~ → らくらく撤去で表ごと削除（v40）。
9. **OCR 学習系（`ocr_variants` ほか）は非稼働**。マッチングは
   `product_master.canonicalKey` 完全一致で組んでよい。
10. JSON バックアップは **生のテーブルダンプ**で、ID をそのまま含む。
    PC 側で複数ユーザー/複数回取り込みをするなら ID 衝突に注意。

---

## 12. 参照（このリポジトリ内）

| 目的 | ファイル |
|---|---|
| DB定義・全マイグレーション | `app/src/main/java/com/example/greenframeocr/data/ReceiptDatabase.kt` |
| エンティティ | `app/src/main/java/com/example/greenframeocr/data/*.kt` |
| JSON バックアップ実装 | `app/src/main/java/com/example/greenframeocr/ui/SettingsScreen.kt`（`AllExportData` ほか / 1080行付近〜） |
| 購買・預金 CSV / 仕訳ロジック | `app/src/main/java/com/example/greenframeocr/ui/OutputConfirmScreen.kt` |
| レシート CSV / 仕訳ロジック | `app/src/main/java/com/example/greenframeocr/ui/GeneralReceiptOutputScreen.kt`, `viewmodel/GeneralReceiptViewModel.kt` |
| canonicalKey / 正規化 | `app/src/main/java/com/example/greenframeocr/util/ProductNameInputUtils.kt` |
| 預金摘要マッチング | `app/src/main/java/com/example/greenframeocr/ui/TekiyouMatchingScreen.kt` |
| 商品マスタ照合 | `app/src/main/java/com/example/greenframeocr/util/JaSheetOcrMapper.kt` |
| CSV共通ユーティリティ・和暦変換 | `app/src/main/java/com/example/greenframeocr/util/CsvUtils.kt` |
| 摘要辞書 CSV 取り込み | `app/src/main/java/com/example/greenframeocr/util/TekiyouDictImporter.kt` |
| 旧・弥生CSV仕様（別プロジェクト由来。参考のみ） | `docs/yayoi-csv-export-spec.md` |
| 旧・DB構造ドキュメント（v11時点・古い） | `docs/DATABASE_SCHEMA.md` |

---

_最終更新: 2026-09-09 / 対象コミット: `feature/gemini-ocr` @ `5889f13` / Room DB v33_
