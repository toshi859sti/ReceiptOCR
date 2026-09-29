# ReceiptOCR データベース構造

> **この文書は DB v11 時代のまま更新されていない（2026-09-29 時点の実装は v40・19 テーブル）。**
> 今のテーブル一覧は `docs/architecture.md`、ER 図は `docs/functional-design.md`、一次情報源は `ReceiptDatabase.kt` の
> `version` / `entities`。下に出てくる `rakuraku_tekiyou`・`rakuraku_accounts` と、それを指す列
> （`kaikakeTekiyouId`・`rakurakuTekiyouId`・`overrideTekiyouId`）は、らくらく青色申告農業版の撤去で v40 に削除した。
> `correction_logs`・`ocr_score_logs` も v28 で削除済み。

## 概要

Room Database Version: 11
データベース名: `receipt_database`

---

## テーブル一覧

| テーブル名 | 説明 | 部門 |
|-----------|------|------|
| receipt_items | 購買伝票明細 | 購買 |
| sheet_data | 伝票シートデータ | 購買 |
| monthly_data | 月次集計データ | 購買 |
| product_master | 商品マスタ | 購買 |
| ocr_variants | OCR学習データ | 購買 |
| correction_logs | 補正ログ | 購買 |
| ocr_score_logs | OCRスコアログ | 購買 |
| deposit_meisai | 預金明細（通帳データ） | 預金 |
| tekiyou_matching_rules | 摘要マッチングルール | 預金 |
| rakuraku_tekiyou | らくらく摘要辞書 | 共通 |
| yayoi_accounts | 弥生会計 勘定科目 | 共通 |
| rakuraku_accounts | らくらく 勘定科目 | 共通 |

---

## ER図（リレーション）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           【購買部門】                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  receipt_items ─────┬───> sheet_data                                   │
│  (購買明細)          │     (伝票シート)                                   │
│                      │          │                                        │
│                      └──────────┴───> monthly_data                      │
│                                       (月次集計)                          │
│                                                                         │
│  product_master <──────────── ocr_variants                              │
│  (商品マスタ)    1:N          (OCR学習)                                   │
│       │                            │                                     │
│       │ FK                         └──> correction_logs                 │
│       ▼                                  (補正ログ)                       │
│  rakuraku_tekiyou                        │                               │
│  (買掛摘要)                               └──> ocr_score_logs            │
│                                               (スコアログ)                │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                           【預金部門】                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  deposit_meisai ─────> tekiyou_matching_rules ─────> rakuraku_tekiyou  │
│  (通帳データ)    参照   (摘要パターン)          FK     (摘要辞書)          │
│                                                                         │
│  ※ deposit_meisai と tekiyou_matching_rules は                         │
│    正規化文字列でグルーピング（FK連携ではない）                            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                           【共通マスタ】                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  rakuraku_tekiyou              yayoi_accounts                           │
│  (らくらく摘要辞書)              (弥生勘定科目)                            │
│       │                              │                                   │
│       │ 自己参照なし                  │ parentId (自己参照)               │
│       ▼                              ▼                                   │
│  - mainCategory: 現金/預金/売掛/買掛    rakuraku_accounts                 │
│  - subCategory: 入金/出金/販売/購入     (らくらく勘定科目)                  │
│                                              │                           │
│                                              │ parentId (自己参照)       │
│                                              ▼                           │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 各テーブル詳細

### 1. receipt_items（購買伝票明細）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Long (PK) | 自動採番 |
| issueYear | Int | 発行年 |
| issueMonth | Int | 発行月 |
| sheetNumber | Int | 伝票番号（何枚目） |
| itemNumber | Int | 伝票内行番号 |
| receiptYear | Int | 領収日：年 |
| receiptMonth | Int | 領収日：月 |
| receiptDay | Int | 領収日：日 |
| productName | String | 商品名 |
| amount | Int | 税込金額（マイナス可：返品） |
| category | String | 分類（未分類/一般購買/給油所/農業機械） |
| isOcrOverwriteTarget | Boolean | 再OCR上書き対象フラグ |

---

### 2. sheet_data（伝票シートデータ）

複合主キー: (issueYear, issueMonth, sheetNumber)

| カラム | 型 | 説明 |
|--------|-----|------|
| issueYear | Int (PK) | 発行年 |
| issueMonth | Int (PK) | 発行月 |
| sheetNumber | Int (PK) | 伝票番号 |
| totalFromInput | Int? | 合計金額 |
| subtotalGeneral | Int? | 一般購買小計 |
| subtotalGas | Int? | 給油所小計 |
| subtotalAgri | Int? | 農業機械小計 |
| isTotalOcrTarget | Boolean | 再OCR対象フラグ |
| isSubtotalGeneralOcrTarget | Boolean | 〃 |
| isSubtotalGasOcrTarget | Boolean | 〃 |
| isSubtotalAgriOcrTarget | Boolean | 〃 |

---

### 3. monthly_data（月次集計データ）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | String (PK) | "YYYY_MM" 形式 |
| issueYear | Int | 発行年 |
| issueMonth | Int | 発行月 |
| totalSheets | Int | 合計枚数 |
| generalPurchaseTotal | Int | 一般購買合計 |
| agriculturalTotal | Int | 農業機械合計 |
| gasStationTotal | Int | 給油所合計 |
| monthlyTotal | Int | 月合計 |

---

### 4. product_master（商品マスタ）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Long (PK) | 自動採番 |
| canonicalName | String | 正規化された商品名 |
| category | String | カテゴリ（一般購買/給油所/農業機械） |
| frequencyCount | Int | 使用頻度 |
| kaikakeTekiyouId | Int? (FK) | 買掛摘要辞書ID → rakuraku_tekiyou |

**外部キー:**
- kaikakeTekiyouId → rakuraku_tekiyou(id) ON DELETE SET NULL

---

### 5. ocr_variants（OCR学習データ）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Long (PK) | 自動採番 |
| productId | Long (FK) | 商品マスタID |
| variantText | String | 誤認識された文字列（生テキスト） |
| normalizedText | String | 正規化後の文字列 |
| confidenceLevel | String | 信頼度: AUTO/CONFIRMED/LOCKED |
| hitCount | Int | 累積ヒット数 |
| highScoreHits | Int | 高スコア（>=0.90）ヒット回数 |
| avgFinalScore | Double | 平均最終スコア |
| totalScore | Double | スコア合計 |
| firstSeenAt | Long | 最初に見た日（Unix timestamp） |
| lastSeenAt | Long | 最終確認日時 |
| uniqueDays | Int | ユニーク日数 |
| lastSeenDate | Int | 最後に見た日（YYYYMMDD形式） |
| source | String | 登録ソース: AUTO/USER/IMPORT |
| isDisabled | Boolean | 無効化フラグ |
| disabledReason | String? | 無効化理由 |
| manualCorrectCount | Int | 手動修正回数 |
| autoFailCount | Int | AUTO誤爆回数 |
| lastManualCommitBatchId | String? | 最後の手動修正バッチID |

**外部キー:**
- productId → product_master(id) ON DELETE CASCADE

**インデックス:**
- productId, variantText, normalizedText, confidenceLevel

---

### 6. correction_logs（補正ログ）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Long (PK) | 自動採番 |
| sessionId | String | セッションID |
| timestamp | Long | タイムスタンプ |
| rawText | String | OCR生テキスト |
| normalizedRaw | String | 正規化後テキスト |
| category | String | カテゴリ |
| hardConstraintsPassed | Int | Layer 0通過数 |
| topProduct | String? | 1位商品名 |
| topBaseScore | Double | 1位ベーススコア |
| topBonusTotal | Double | 1位ボーナス合計 |
| topFinalScore | Double | 1位最終スコア |
| secondProduct | String? | 2位商品名 |
| secondFinalScore | Double | 2位最終スコア |
| decision | String | 補正判定結果 |
| correctedName | String? | 補正後商品名 |
| matched | Boolean | 補正成功フラグ |
| bonusBreakdown | String? | ボーナス内訳（JSON） |

**インデックス:** timestamp, decision, sessionId

---

### 7. ocr_score_logs（OCRスコアログ）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Long (PK) | 自動採番 |
| rawOcrText | String | OCR生テキスト |
| candidateProductId | Long? | 候補商品ID |
| decision | String | 判定結果: AUTO/NEED_CONFIRM/NO_MATCH |
| totalScore | Double? | 総合スコア（100点満点） |
| textSimilarity | Double? | 文字類似度（最大60） |
| prefixBonus | Double? | 先頭欠落ボーナス（最大10） |
| dakutenBonus | Double? | 濁点誤認識ボーナス（最大5） |
| variantBonus | Double? | 既存知識ボーナス（最大15） |
| historyBonus | Double? | 手動修正履歴ボーナス（最大10） |
| riskPenalty | Double? | リスクペナルティ（最大-30） |
| gapToSecond | Double? | 2位との差分 |
| manualOverride | Boolean | 後で手動修正されたか |
| manualCorrectedProductId | Long? | 手動修正後の商品ID |
| commitBatchId | String? | コミットバッチID |
| createdAt | Long | 作成日時 |

**インデックス:** createdAt, decision, commitBatchId, rawOcrText

---

### 8. deposit_meisai（預金明細）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Int (PK) | 自動採番 |
| transactionDate | String | 取引日（yyyy-MM-dd） |
| transactionNumber | String | 取引通番 |
| tekiyou | String | 摘要（原文） |
| amount | Int | 金額（正:入金、負:出金） |
| memo | String | メモ |
| matchingRuleId | Int? | マッチングルールID（参照用） |

**インデックス:** transactionDate, tekiyou

---

### 9. tekiyou_matching_rules（摘要マッチングルール）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Int (PK) | 自動採番 |
| pattern | String (UNIQUE) | マッチング用パターン |
| normalizedTekiyou | String | 正規化された摘要名 |
| isRegex | Boolean | 正規表現として扱うか |
| rakurakuTekiyouId | Int? (FK) | らくらく摘要辞書ID |
| sampleText | String | サンプル（元の摘要テキスト例） |
| matchCount | Int | 該当件数 |
| isDeposit | Boolean | 入金(true) or 出金(false) |

**外部キー:**
- rakurakuTekiyouId → rakuraku_tekiyou(id) ON DELETE SET NULL

**インデックス:** pattern (UNIQUE), rakurakuTekiyouId

---

### 10. rakuraku_tekiyou（らくらく摘要辞書）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Int (PK) | 自動採番 |
| mainCategory | String | メインカテゴリ: 現金/預金/売掛/買掛 |
| subCategory | String | サブカテゴリ: 入金/出金/販売/購入 |
| tekiyouName | String | 摘要名 |
| searchKey | String | 検索文字（ローマ字） |
| kamoku | String | 勘定科目 |
| taxRate | String | 税率: 8%/10%/非/不/空 |
| businessRatio | Int? | 事業割合（%） |
| isShared | Boolean? | 共有フラグ（現金/預金のみ） |
| isEnabled | Boolean | 使用するフラグ |

**インデックス:** (mainCategory, subCategory)

---

### 11. yayoi_accounts（弥生会計 勘定科目）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Long (PK) | 自動採番 |
| accountName | String | 勘定科目名 |
| searchKeyAlpha | String | サーチキー英字 |
| accountCode | String (UNIQUE) | 勘定科目コード |
| debitCredit | String | 借貸（借/貸） |
| categoryC | String | 区分C - 小分類 |
| categoryB | String | 区分B - 中分類 |
| categoryA | String | 区分A - 大分類 |
| usedForPurchase | Boolean | 購買取引で使用するか |
| usedForDeposit | Boolean | 預金取引で使用するか |
| parentId | Long? | 親科目ID（自己参照） |

**インデックス:** accountCode (UNIQUE)

---

### 12. rakuraku_accounts（らくらく 勘定科目）

| カラム | 型 | 説明 |
|--------|-----|------|
| id | Long (PK) | 自動採番 |
| accountCode | String (UNIQUE) | 勘定科目コード |
| accountName | String | 勘定科目名 |
| searchKeyAlpha | String | サーチキー英字 |
| debitCredit | String | 借貸（借/貸） |
| categoryC | String | 区分C - 小分類 |
| categoryB | String | 区分B - 中分類 |
| categoryA | String | 区分A - 大分類 |
| usedForPurchase | Boolean | 購買取引で使用するか |
| usedForDeposit | Boolean | 預金取引で使用するか |
| parentId | Long? | 親科目ID（自己参照） |

**インデックス:** accountCode (UNIQUE)

---

## リレーション一覧

| 親テーブル | 子テーブル | カラム | ON DELETE |
|-----------|-----------|--------|-----------|
| product_master | ocr_variants | productId | CASCADE |
| rakuraku_tekiyou | product_master | kaikakeTekiyouId | SET NULL |
| rakuraku_tekiyou | tekiyou_matching_rules | rakurakuTekiyouId | SET NULL |

---

## 部門別データ分離

### 購買部門（独立してエクスポート可能）

1. **伝票データ**: receipt_items, sheet_data, monthly_data
2. **商品マスタ**: product_master
3. **OCR学習データ**: ocr_variants, correction_logs, ocr_score_logs

### 預金部門（依存関係あり）

1. **通帳データ**: deposit_meisai
2. **摘要パターン**: tekiyou_matching_rules
   - rakuraku_tekiyou と FK 連携
   - 通帳データとは正規化文字列でグルーピング（FK なし）

### 共通マスタ

1. **摘要辞書**: rakuraku_tekiyou
2. **勘定科目**: yayoi_accounts, rakuraku_accounts

---

## インポート/エクスポート推奨形式

| データ | 形式 | 備考 |
|--------|------|------|
| 通帳データ | CSV | 取引日,通番,摘要,金額,メモ |
| 摘要マッチング | CSV | 正規化摘要,入出金,らくらく摘要名,勘定科目 |
| 商品マスタ | CSV | 商品名,カテゴリ,買掛摘要名 |
| OCR学習 | CSV | 商品名,バリアント,信頼度,ソース |

**文字コード**: UTF-8 BOM付き（Excel互換）
**区切り文字**: カンマ（,）
