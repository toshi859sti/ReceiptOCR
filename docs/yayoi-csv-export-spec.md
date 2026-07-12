# 弥生青色申告 CSVエクスポート仕様書
## AoiroChobo — 弥生インポート形式 出力仕様

作成日：2026年6月13日  
対象：やよいの青色申告（デスクトップ版）  
前提：簡易課税・税込入力・農業（第二種）

---

## 1. 概要

AoiroChoboで入力した仕訳データを弥生の青色申告にインポートするためのCSVファイル出力機能の仕様。

**位置づけ：**
- AoiroChoboをメインで使用し、弥生に仕訳データを受け渡す用途
- Phase 4 のスコープとして実装予定
- 出力対象：`JournalEntries` テーブルの全仕訳（`IsVoided = 0` のもの）

---

## 2. ファイル仕様

| 項目 | 値 |
|---|---|
| 文字コード | **Shift-JIS（CP932）** |
| 改行コード | **CRLF**（`\r\n`） |
| 区切り文字 | カンマ（`,`） |
| フィールドの囲み | **すべてダブルクォート**（`"`）で囲む |
| 列数 | **25列**（固定） |
| ヘッダー行 | **なし**（1行目からデータ） |
| 日付形式 | 和暦（`R.yy/MM/dd`） |
| ファイル拡張子 | `.csv` |

> ⚠️ 文字コードが UTF-8 だとインポート時にエラーになる。必ず Shift-JIS で出力すること。

---

## 3. 列定義（25列）

| 列 | 項目名 | 必須 | 取得元 | 出力値 |
|---|---|---|---|---|
| 1 | 識別フラグ | ○ | 固定 | 常に `"2000"` |
| 2 | 伝票No | | `JournalEntry.SlipNo` | 空欄でも可 |
| 3 | 決算 | | 変換ロジック | `EntryType=Closing` → `"*"`、それ以外 → `""` |
| 4 | 取引日付 | ○ | `JournalEntry.EntryDate` | 西暦→和暦変換（例：`R.08/01/20`） |
| 5 | 借方勘定科目 | ○ | `Accounts.Name`（借方） | 弥生の科目名と**完全一致**が必要 |
| 6 | 借方補助科目 | | `Accounts.Name`（借方・子科目） | `ParentId != NULL` の場合のみ出力 |
| 7 | 借方部門 | | 固定 | 常に `""` |
| 8 | 借方税区分 | ○ | 変換ロジック | 税区分変換ルールで生成（§5参照） |
| 9 | 借方金額 | ○ | `JournalEntry.Amount` | 数値のみ（カンマなし） |
| 10 | 借方税金額 | | 固定 | 常に `"0"`（税込経理のため） |
| 11 | 貸方勘定科目 | ○ | `Accounts.Name`（貸方） | 弥生の科目名と**完全一致**が必要 |
| 12 | 貸方補助科目 | | `Accounts.Name`（貸方・子科目） | `ParentId != NULL` の場合のみ出力 |
| 13 | 貸方部門 | | 固定 | 常に `""` |
| 14 | 貸方税区分 | ○ | 変換ロジック | 税区分変換ルールで生成（§5参照） |
| 15 | 貸方金額 | ○ | `JournalEntry.Amount` | 借方金額と同額 |
| 16 | 貸方税金額 | | 固定 | 常に `"0"` |
| 17 | 摘要 | | `MemoName` + `Note` | 最大40文字（超過時トリミング） |
| 18 | 番号 | | 固定 | 常に `""` |
| 19 | 期日 | | 固定 | 常に `""` |
| 20 | タイプ | | 固定 | 常に `"0"` |
| 21 | 生成元 | | 固定 | 常に `""` |
| 22 | 仕訳メモ | | 固定 | 常に `""` |
| 23 | 付箋1 | | 固定 | 常に `"0"` |
| 24 | 付箋2 | | 固定 | 常に `"0"` |
| 25 | 調整 | | 固定 | 常に `"no"` |

---

## 4. 日付変換ルール（西暦 → 和暦）

```csharp
// JournalEntry.EntryDate（yyyy-MM-dd）を弥生の R.yy/MM/dd 形式に変換
public static string ToYayoiDate(string entryDate)
{
    var date = DateOnly.ParseExact(entryDate, "yyyy-MM-dd");

    // 令和：2019年5月1日以降
    if (date >= new DateOnly(2019, 5, 1))
    {
        int reiwaYear = date.Year - 2018;
        return $"R.{reiwaYear:D2}/{date.Month:D2}/{date.Day:D2}";
    }

    // 平成：2019年4月30日以前
    if (date >= new DateOnly(1989, 1, 8))
    {
        int heiseiYear = date.Year - 1988;
        return $"H.{heiseiYear:D2}/{date.Month:D2}/{date.Day:D2}";
    }

    // 昭和以前（実用上ほぼ不要）
    return $"{date.Year}/{date.Month:D2}/{date.Day:D2}";
}
```

| 入力（EntryDate） | 出力 |
|---|---|
| `2026-01-20` | `R.08/01/20` |
| `2025-12-31` | `R.07/12/31` |
| `2019-05-01` | `R.01/05/01` |
| `2019-04-30` | `H.31/04/30` |

---

## 5. 税区分変換ルール

借方・貸方それぞれについて、科目の `DefaultTaxCategory`（Accounts テーブル）と
仕訳の `TaxRate`（JournalEntries テーブル）を組み合わせて弥生の税区分文字列を生成する。

### 判定ロジック

```
Step 1: DefaultTaxCategory が NA または NotApplicable
        → 借方・貸方ともに「対象外」

Step 2: DefaultTaxCategory が NonTaxable
        → 借方側：「非課仕入」
        → 貸方側：「非課売上」

Step 3: DefaultTaxCategory が Taxable
        → TaxRate と借方/貸方の組み合わせで下表から決定
```

### 変換表（Taxable の場合）

| TaxRate | 借方側（仕入） | 貸方側（売上） | 該当科目例 |
|---|---|---|---|
| `10` | `課対仕入込10%` | `課税売上込二10%` | 肥料費・農薬・売上高（野菜）など |
| `8` | `課対仕入込 軽減8%` | `課税売上込二 軽減8%` | 飼料費・売上高（米・牛）など |
| `8_old` | `課対仕入込8%` | `課税売上込二8%` | 2019年9月以前の取引 |
| `non` | `非課仕入` | `非課売上` | — |
| `na` | `対象外` | `対象外` | — |
| `NULL` | `対象外` | `対象外` | — |

### 科目別の変換結果一覧

| AoiroChobo科目 | DefaultTaxCategory | 典型的なTaxRate | 借方税区分 | 貸方税区分 |
|---|---|---|---|---|
| 現金・預金・売掛金 | `NA` | — | 対象外 | 対象外 |
| 買掛金・借入金・未払金 | `NA` | — | 対象外 | 対象外 |
| 元入金・事業主貸・事業主借 | `NA` | — | 対象外 | 対象外 |
| 売上高（野菜・花）| `Taxable` | `10` | 課対仕入込10% | 課税売上込二10% |
| 売上高（米・牛）| `Taxable` | `8` | 課対仕入込 軽減8% | 課税売上込二 軽減8% |
| 肥料費・農薬衛生費 | `Taxable` | `10` | 課対仕入込10% | 課税売上込二10% |
| 飼料費 | `Taxable` | `8` | 課対仕入込 軽減8% | 課税売上込二 軽減8% |
| 農業共済掛金 | `NonTaxable` | `non` | 非課仕入 | 非課売上 |
| 利子割引料 | `NonTaxable` | `non` | 非課仕入 | 非課売上 |
| 租税公課・雇人費 | `NotApplicable` | `na` | 対象外 | 対象外 |
| 減価償却費・土地改良費 | `NotApplicable` | `na` | 対象外 | 対象外 |

> ⚠️ 税区分文字列はやよいの青色申告（簡易課税・税込入力）で検証済み。
> バージョンや消費税設定によって異なる場合がある。
> **税区分は必須項目（空欄だとインポートエラーになる）。**

---

## 6. 摘要の生成ルール

```csharp
// MemoName と Note を結合して40文字以内に収める
public static string BuildMemo(string memoName, string? note)
{
    var memo = string.IsNullOrEmpty(note)
        ? memoName
        : $"{memoName}　{note}";  // 全角スペースで結合

    // 弥生の摘要欄は最大40文字
    return memo.Length > 40 ? memo[..40] : memo;
}
```

---

## 7. 補助科目の出力ルール

```csharp
// 科目が補助科目（ParentId != null）かどうかで出力先を分ける
public static (string accountName, string subAccountName) GetYayoiAccountNames(
    Account account, Account? parentAccount)
{
    if (parentAccount is not null)
    {
        // 補助科目あり → 親科目名 + 補助科目名
        return (parentAccount.Name, account.Name);
    }
    else
    {
        // 補助科目なし → 科目名のみ、補助科目列は空欄
        return (account.Name, "");
    }
}
```

**例：**

| AoiroChoboの科目 | 弥生の勘定科目欄（列5） | 弥生の補助科目欄（列6） |
|---|---|---|
| 現金（補助なし） | 現金 | （空欄） |
| 普通預金 > 営農口座 | 普通預金 | JA島原雲仙 |
| 普通預金 > 直売口座 | 普通預金 | 直売口座 |

---

## 8. 科目名の対応

AoiroChoboの科目名と弥生の科目名は**完全一致**が必要。  
インポートエラーの最大の原因が科目名の不一致のため、事前に弥生側へ農業用科目を登録しておく。

**推奨：弥生側をAoiroChoboに合わせて登録する**

| AoiroChoboの科目名 | 弥生への登録名 | 備考 |
|---|---|---|
| 動力光熱費 | 動力光熱費 | そのまま |
| 農薬衛生費 | 農薬衛生費 | 弥生標準にない → 追加登録が必要 |
| 荷造運賃手数料 | 荷造運賃手数料 | 同上 |
| 農業共済掛金 | 農業共済掛金 | 同上 |
| 作業用衣料費 | 作業用衣料費 | 同上 |
| 農機具等（資産） | 農機具等 | 「（資産）」を除いた名称で登録 |
| 建物・構築物（資産） | 建物・構築物 | 同上 |
| 種苗費 | 種苗費 | 同上 |
| 素畜費 | 素畜費 | 同上 |
| 肥料費 | 肥料費 | 同上 |
| 飼料費 | 飼料費 | 同上 |
| 農具費 | 農具費 | 同上 |
| 諸材料費 | 諸材料費 | 同上 |
| 雇人費 | 雇人費 | 同上 |
| 地代・賃借料 | 地代・賃借料 | 同上 |
| 土地改良費 | 土地改良費 | 同上 |

---

## 9. サンプルCSV

### 9-1. 仕訳パターン別サンプル

```csv
"2000","1","","R.08/01/20","現金","","","対象外","10000","0","売上高","直売所","","課税売上込二10%","10000","0","売上入金（直売所）","","","0","","","0","0","no"
"2000","2","","R.08/01/20","肥料費","","","課対仕入込10%","11000","0","現金","","","対象外","11000","0","肥料購入","","","0","","","0","0","no"
"2000","3","","R.08/01/20","動力光熱費","","","課対仕入込10%","3300","0","現金","","","対象外","3300","0","電気料金（1月分）","","","0","","","0","0","no"
"2000","4","","R.08/01/20","農業共済掛金","","","非課仕入","8000","0","現金","","","対象外","8000","0","水稲共済掛金","","","0","","","0","0","no"
"2000","5","","R.08/01/31","現金","","","対象外","120000","0","普通預金","JA島原雲仙","","対象外","120000","0","現金引出","","","0","","","0","0","no"
"2000","6","","R.08/12/31","事業主貸","","","対象外","60000","0","動力光熱費","","","対象外","60000","0","家事按分（電気70%）","","","0","","","0","0","no"
"2000","7","*","R.08/12/31","減価償却費","","","対象外","85000","0","農機具等","","","対象外","85000","0","トラクター減価償却","","","0","","","0","0","no"
```

### 9-2. パターン解説

| 行 | 取引の種類 | ポイント |
|---|---|---|
| 1行目 | 売上入金（直売所・現金） | 貸方（売上高）に補助科目「直売所」、課税売上込二10% |
| 2行目 | 肥料購入（現金払い） | 借方（肥料費）が課税仕入、貸方（現金）が対象外 |
| 3行目 | 電気料金 | 動力光熱費は課税仕入10% |
| 4行目 | 農業共済掛金 | NonTaxable → 非課仕入 |
| 5行目 | 普通預金引出 | 補助科目「JA島原雲仙」を列12に出力 |
| 6行目 | 家事按分（決算整理） | 借方・貸方ともに対象外 |
| 7行目 | 減価償却（決算整理） | 列3に `"*"` → 決算仕訳フラグ |

---

## 10. C# 実装サンプル

```csharp
// YayoiCsvExporter.cs（AoiroChobo.Core/Services/）

public class YayoiCsvExporter
{
    private readonly IAccountRepository _accountRepository;

    public YayoiCsvExporter(IAccountRepository accountRepository)
        => _accountRepository = accountRepository;

    public async Task ExportAsync(
        IEnumerable<JournalEntry> entries,
        string outputPath)
    {
        var accounts = await _accountRepository.GetAllAsync();
        var accountMap = accounts.ToDictionary(a => a.Id);

        // Shift-JIS で出力
        var encoding = Encoding.GetEncoding("shift_jis");

        await using var writer = new StreamWriter(outputPath, false, encoding);
        await using var csv = new CsvWriter(writer, new CsvConfiguration(CultureInfo.InvariantCulture)
        {
            HasHeaderRecord = false,
            ShouldQuote = _ => true,           // 全フィールドをクォート
            NewLine = "\r\n",                   // CRLF
        });

        foreach (var entry in entries.Where(e => !e.IsVoided))
        {
            var debitAccount  = accountMap[entry.DebitAccountId];
            var creditAccount = accountMap[entry.CreditAccountId];

            var (debitName, debitSub)   = GetYayoiAccountNames(debitAccount, accountMap);
            var (creditName, creditSub) = GetYayoiAccountNames(creditAccount, accountMap);

            var debitTax  = ConvertTaxCategory(debitAccount.DefaultTaxCategory, entry.TaxRate, isDebit: true);
            var creditTax = ConvertTaxCategory(creditAccount.DefaultTaxCategory, entry.TaxRate, isDebit: false);

            var row = new object[]
            {
                "2000",
                entry.SlipNo?.ToString() ?? "",
                entry.EntryType == "Closing" ? "*" : "",
                ToYayoiDate(entry.EntryDate),
                debitName,
                debitSub,
                "",                             // 部門（空欄）
                debitTax,
                entry.Amount.ToString(),
                "0",
                creditName,
                creditSub,
                "",                             // 部門（空欄）
                creditTax,
                entry.Amount.ToString(),
                "0",
                BuildMemo(entry.MemoName, entry.Note),
                "", "", "0", "", "", "0", "0", "no"
            };

            await csv.WriteRecordAsync(row);
            await csv.NextRecordAsync();
        }
    }

    // 西暦 → 和暦変換
    private static string ToYayoiDate(string entryDate)
    {
        var date = DateOnly.ParseExact(entryDate, "yyyy-MM-dd");
        if (date >= new DateOnly(2019, 5, 1))
            return $"R.{date.Year - 2018:D2}/{date.Month:D2}/{date.Day:D2}";
        if (date >= new DateOnly(1989, 1, 8))
            return $"H.{date.Year - 1988:D2}/{date.Month:D2}/{date.Day:D2}";
        return $"{date.Year}/{date.Month:D2}/{date.Day:D2}";
    }

    // 税区分変換
    private static string ConvertTaxCategory(
        string defaultTaxCategory, string? taxRate, bool isDebit)
    {
        return defaultTaxCategory switch
        {
            "NA" or "NotApplicable" => "対象外",
            "NonTaxable" => isDebit ? "非課仕入" : "非課売上",
            "Taxable" => (taxRate, isDebit) switch
            {
                ("10",    true)  => "課対仕入込10%",
                ("10",    false) => "課税売上込二10%",
                ("8",     true)  => "課対仕入込 軽減8%",
                ("8",     false) => "課税売上込二 軽減8%",
                ("8_old", true)  => "課対仕入込8%",
                ("8_old", false) => "課税売上込二8%",
                ("non",   _)     => isDebit ? "非課仕入" : "非課売上",
                _                => "対象外",
            },
            _ => "対象外",
        };
    }

    // 補助科目の分解
    private static (string name, string sub) GetYayoiAccountNames(
        Account account, Dictionary<int, Account> accountMap)
    {
        if (account.ParentId is not null && accountMap.TryGetValue(account.ParentId.Value, out var parent))
            return (parent.Name, account.Name);
        return (account.Name, "");
    }

    // 摘要生成（40文字制限）
    private static string BuildMemo(string memoName, string? note)
    {
        var memo = string.IsNullOrEmpty(note)
            ? memoName
            : $"{memoName}　{note}";
        return memo.Length > 40 ? memo[..40] : memo;
    }
}
```

---

## 11. インポート手順（弥生側の操作）

1. やよいの青色申告を起動
2. メニュー「ファイル」→「インポート」→「弥生インポート形式」を選択
3. AoiroChoboで出力したCSVファイルを指定
4. エラーがある場合はエラーログを確認
   - **科目名不一致**が最多 → §8の対応表で弥生側に科目を追加登録
   - **税区分エラー** → 税区分文字列の表記を確認（§5参照）
5. 正常取込後、仕訳帳で内容を確認

---

## 12. エラー対応表

| エラーメッセージ | 原因 | 対処方法 |
|---|---|---|
| `借方税区分：必須項目が指定されていません` | 税区分列が空欄 | §5の変換ロジックで必ず値を設定する |
| `勘定科目が見つかりません` | 科目名が弥生に未登録または名称不一致 | §8を参照して弥生側に科目を追加登録 |
| `1行目が形式に合っていません` | 文字コードまたは列数の誤り | Shift-JIS・25列であることを確認 |
| `日付の形式が正しくありません` | 和暦変換の誤り | `R.yy/MM/dd` 形式になっているか確認 |

---

## 13. 注意事項

- 弥生の税区分文字列はバージョンや消費税設定によって異なる場合がある。本仕様は**簡易課税・税込入力**での検証結果。
- 弥生への取込は**年度単位**で行うことを推奨。複数年度のデータを一度に取り込むとエラーになる場合がある。
- AoiroChoboで取消済み（`IsVoided = 1`）の仕訳はCSVに出力しない。
- 取込後は弥生側で**合計残高試算表の貸借一致**を確認すること。
