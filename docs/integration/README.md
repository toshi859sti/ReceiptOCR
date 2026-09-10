# AoiroChobo ⇔ GreenFrameOCR 連携契約（AoiroChobo 側発行）

> **この文書の読者**
> Android アプリ「JA仕訳変換」(`com.example.greenframeocr`) の開発者／Claude Code。
> AoiroChobo（農業用青色申告帳簿アプリ・Windows デスクトップ）と連携するための
> **AoiroChobo 側から見た仕様**。スマホ側の一次仕様書
> `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md` への回答・すり合わせ資料でもある。
>
> AoiroChobo がマスタ（勘定科目・摘要辞書）の所有者であり、この契約の所有者。
> スマホ側はこのフォルダを copy / submodule で取り込んで参照する。

対象バージョン：`schemaVersion: 1`（2026-09-10 初版）
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
│ transactions.json を出力（スマホ→PC）               │──→│ 取込：Code を当年度科目に解決        │
│                                                    │   │ 解決できなければ「要確認」タブへ     │
│                                                    │   │ 取込後の仕訳は AoiroChobo が所有・編集 │
└────────────────────────────────────────────────────┘   └────────────────────────────────────┘
```

### スマホ側の責務（この契約で守ってほしいこと）

1. **勘定科目は解決済みの `Account.Code`（文字列）で出す。** Id・科目名では出さない。
   相手科目（貸方）も同様に Code で出す（「現金」「クレジット」等の生テキストは `meta` に補助情報として）。
2. **税率は AoiroChobo のコード体系に変換して出す**（§4 の変換表）。
3. **日付は西暦 ISO `yyyy-MM-dd`**。令和→西暦変換はスマホ側で済ませる。
4. **金額は税込・正の整数（円）。** 返品・値引きは借方／貸方を入れ替えて正数化する（§3）。
5. **個別上書き・`isExcluded`・小計/合計行**は、AoiroChobo 向け出力を作る時点で
   すべて適用済み（上書き反映・除外行は出さない・集計行は出さない）にする。
6. マッチできなかった行も**落とさず** `matchStatus` を付けて出す（PC 側で「要確認」にまわす）。
7. `AoiroChobo.Code` に解決できない支払方法（「PayPay」「クレジット」等）は、
   `vocabulary.json` の中の相当科目（未払金 `mibarai` / 事業主借 `zigyounusikari` 等）に寄せる。

---

## 2. マスタは AoiroChobo 所有

### 何が変わるか

- スマホ側一次仕様書 §5 は「PC が Android の JSON バックアップ（`yayoi_accounts` /
  `rakuraku_accounts` / `rakuraku_tekiyou`）を食う」前提。**これは AoiroChobo 連携では使わない。**
- 代わりに **AoiroChobo が `vocabulary.json` を発行**し、スマホがそれを取り込む。
  スマホの AI はこの `vocabulary.json` を語彙テーブルとして使い、マッチング結果を
  `vocabulary.json` 内の `Account.Code` で表現する。

### なぜ AoiroChobo 所有か

- 勘定科目・摘要辞書はユーザーが **AoiroChobo の画面で追加・改名・無効化**する。
  正本が 2 か所にあると必ずズレる。所有者を 1 つに固定する。
- AoiroChobo の科目・摘要は**年度スコープ**（`FiscalYearId` を持つ）。スナップショットは
  「どの年度のマスタか」を明示して発行する。

### `vocabulary.json`（PC → スマホ）

- 中身・スキーマ・具体例：[vocabulary-snapshot.md](vocabulary-snapshot.md)
- サンプル：[examples/vocabulary.sample.json](examples/vocabulary.sample.json)
- 要点：
  - `Account.Code`（年度非依存の安定キー）で科目を参照。Id は載せない。
  - `IsActive = 1` の科目・摘要だけを載せる。
  - `schemaVersion` 必須。任意で `contentHash`（スマホ側の鮮度チェック用）。
  - enum（`accountType` / `ledgerType` / `direction` / `taxRate` / `defaultTaxCategory`）は
    AoiroChobo の C# enum・シードから機械生成して同梱する（手維持しない）。

### スナップショットが古くなったら

- スマホの `vocabulary.json` が古くても壊れない。PC の**取込時検証**が
  「その Code は今年度に存在しない」を検出し、その行を「要確認」にまわす。
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

| source | 借方 (`debit.accountCode`) | 貸方 (`credit.accountCode`) | 金額 | 摘要 | 落ちる帳簿 (`ledgerType`) |
|---|---|---|---|---|---|
| 購買（JA伝票） | 商品の経費科目 | `kaikake`（買掛金・固定） | `amount` | 買掛摘要名 or 商品名 | `AP` |
| 通帳 入金 | 預金口座科目（スロット） | ルールの相手科目 | `abs(amount)` | 預金摘要名 or 摘要原文 | `Bank` |
| 通帳 出金 | ルールの相手科目 | 預金口座科目（スロット） | `abs(amount)` | 同上 | `Bank` |
| レシート（現金払い） | 品目の経費科目 | `genkin`（現金） | `price` | 品目名 | `Cash` |
| レシート（クレカ/電子マネー） | 品目の経費科目 | `mibarai` 等（未払金・事業主借） | `price` | 品目名 | `Unpaid` |

`ledgerType` は「その仕訳を所有する帳簿」のヒント。省略された場合は PC が借方／貸方科目の
`ledgerAffinity` から推定する。

---

## 4. 税率コード変換表

AoiroChobo 側のコード（`debit.taxRate` / `credit.taxRate` に入れる値）：

| AoiroChobo コード | 意味 | 表示 | スマホ側（`RakurakuTekiyou.taxRate` 等）からの変換元（例） |
|---|---|---|---|
| `"10"` | 標準税率 10% | 10% | `10%` / `課対仕入10` / `課税売上`（10%想定） |
| `"8"` | 軽減税率 8% | 8%軽 | `8%` / `課対仕入8` |
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
| D | 預金スロット識別子 | `bankSlotNo`（0=親「普通預金」/ 1,2,…=補助口座）。Android は単一通帳なので取込 UI で選ぶかスマホ設定で固定 | [transaction-import.md](transaction-import.md) §「bankSlotNo」 |
| E | 相手科目 | 解決済み `Account.Code` 必須。生の支払方法テキストは `meta.paymentMethodText` に | §1 責務 1 |
| F | 個別上書き・除外・集計行 | スマホ側で適用済みにする（上書き反映・`isExcluded` 除外・小計/合計行を出さない） | §1 責務 5 |
| G | 返品・マイナス金額 | 金額は常に正。返品は借方／貸方を入れ替えて出す（例：Dr 買掛金 / Cr 経費科目）。`meta.isReturn = true` | §3 |
| H | 文字コード・バージョン | JSON・UTF-8 (BOM なし)・LF。`schemaVersion` 必須 | 全体 |

---

## 6. このフォルダの構成

```
docs/integration/
├── README.md                      ← この文書（連携の全体像・役割分担・③の確定）
├── REVIEW-notes.md                スマホ側一次仕様書のレビュー所見
├── vocabulary-snapshot.md         PC → スマホ：マスタスナップショット仕様
├── transaction-import.md          スマホ → PC：取引データ JSON 仕様
├── CHANGELOG.md                   schemaVersion ごとの変更履歴
└── examples/
    ├── vocabulary.sample.json     vocabulary.json のゴールデン例
    └── transactions.sample.json   transactions.json のゴールデン例
```

**契約テストは両側に置く**：スキーマ検証＋ゴールデン例（`examples/`）との一致を CI で確認する。
スキーマが変わったら `schemaVersion` を上げ、`CHANGELOG.md` に記録する。
