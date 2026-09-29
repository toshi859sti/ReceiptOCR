# GreenFrameOCR 連携仕様書レビューノート

対象：`docs/PC_ACCOUNTING_INTEGRATION_SPEC.md`
（Android アプリ「JA仕訳変換」= `com.example.greenframeocr` が作成した一次仕様書。
対象コミット `feature/gemini-ocr` @ `5889f13` / Room DB v33）

レビュー日：2026-09-09

> ⚠️ **2026-09-10 更新あり**：本文中で「スマホは解決済みの `Account.Code` を吐く」「科目参照は
> `Account.Code`」としている箇所は、**`accountKey` に変更済み**（`Account.Code` は DB の UNIQUE 制約が
> 無く一意性を保証できないため）。③（取引データ形式）も **JSON で確定**。詳細は末尾「追記（2026-09-10）」と
> [README.md](README.md) を参照。本文は当時のレビュー記録としてそのまま残す。

突き合わせ対象：`docs/functional-design.md`「仕訳の由来表示と取込データの扱い」、
`docs/known-issues.md` の連携アーキテクチャ確定（2026-09-09 / コミット `65555b2`）、
`CURRENT_TASK.md`「GreenFrameOCR 連携の設計相談」

---

## 総評

ドキュメント自体は良質。実コード基準・列単位の型定義・落とし穴の明示・参照ファイル一覧まであり、
契約書のたたき台として使える。

**ただし全体が「PC 側が旧モデルで動く」前提で書かれている**。2026-09-09 にこちらで確定した
アーキテクチャと食い違う。スマホ仕様書とあわせて、この前提差をスマホ側開発者へ戻す必要がある。

---

## 前提のズレ（重要・3点）

### ① マッチングの担当が逆

| | Android 仕様書 | AoiroChobo 側の確定方針（`65555b2`） |
|---|---|---|
| 商品名/摘要 → 勘定科目のマッチング | **PC 側でやる**（冒頭「PCアプリの計画 2.」、§4.3「PC側で完全に同じ実装が必要」） | **スマホ側でやる**。PC にクラウド AI は入れない（オフラインファースト維持）。PC→スマホへ科目・摘要スナップショットを渡し、スマホの AI がそれを語彙表として使う |

→ **`toCanonicalKey` / `normalizeTekiyou` / `tekiyou_matching_rules` を PC 側に移植する必要はない。**
スマホが解決済みの `Account.Code` を吐き、PC は Code を現年度科目に引き当てるだけ
（引けなければ「未確認」→ 要確認タブ項目3）。仕様書 §4 章まるごとが AoiroChobo 連携には不要
（弥生/らくらく出力向けにはスマホ側に残す）。

### ② マスタの所有者が逆

- Android 仕様書 §5：「PC アプリの入力 = Android の JSON バックアップ（全テーブル生ダンプ・ID 込み）」。
  摘要辞書・勘定科目は **Android が持つ**（`rakuraku_accounts` / `yayoi_accounts` / `rakuraku_tekiyou`、
  バンドル CSV からシード）。
- こちらの確定方針：**PC がマスタの所有者＝契約の所有者**。AoiroChobo ターゲットでは、スマホは
  PC 発行の `vocabulary.json`（科目=Code 参照・`IsActive=1` のみ・`schemaVersion` 必須）を取り込む。

→ Android の `yayoi_accounts` / `rakuraku_accounts` はそれぞれの会計ソフト出力用にはそのままでよいが、
**AoiroChobo 出力用には PC のスナップショットを食う経路が別途要る**。この仕様書にはその経路が書かれていない
（スマホ仕様書側にあるはず）。

### ③ 取引データの形式（要ユーザー判断）

- Android §10：取引データ JSON スキーマを「たたき台」として提案（`entries[]` に `debit`/`credit` ネスト、
  `matchStatus`、`meta`）。仕様書本文は「PC アプリはこの JSON を想定と理解」と書いている。
- こちらの確定：**取引データ（スマホ→PC）は CSV**。契約は `docs/integration/` の「取込 CSV 列定義」。

→ **再検討の余地あり。** §10 の JSON 案は二重仕訳（Dr/Cr ネスト・`matchStatus`・`meta`）と素直に噛み合う。
CSV を選んだ理由は known-issues 上「予定どおり CSV」程度で強い根拠がない。スマホは既に JSON を扱い、
PC は `System.Text.Json` で自明。「転送方式が変わっても契約は不変」という CSV 採用理由は JSON でも成立する。
→ **Claude の見解：§10 を叩き台に JSON へ寄せるのが自然。CSV を貫くか、ユーザー判断待ち。**

さらに：Android の現行 CSV 出力（§7）は **らくらく形式 / 弥生形式**であって「AoiroChobo 形式」ではない。
3 番目の AoiroChobo 専用フォーマットはこの仕様書の範囲外で、今から設計する対象。

---

## 契約に落とすときの必須決定事項

### A. 行の一意キー（`ExternalId`）— 最重要

こちらは `JournalEntry.ExternalId` を「重複取込防止・再取込の更新マッチング・voided 行にも残す」
キーとして使う設計。Android 側の候補キーには問題がある：

- `receipt_items.id` / `deposit_meisai.id` / `general_receipt_items.id` はいずれも
  **テーブル内 autoincrement の小さい整数**で、テーブル間・端末間・再インストール/復元で
  衝突・変動する（§11.10 も明記）。
- 契約では **グローバル一意で復元耐性のある文字列**を定義する。案：
  - 購買：`ocr:purchase:{issueYear}{issueMonth:02}-{sheetNumber}-{itemNumber}`（伝票内位置は安定）
  - 預金：`ocr:deposit:{transactionDate}-{transactionNumber}`
    （`(transactionDate, transactionNumber)` に UNIQUE 制約あり＝◎）
  - レシート：レシート単位の UUID をスマホ側で採番して保持（品目 id では復元で変わる）

→ スマホ仕様書レビューの最優先確認項目。

### B. 日付形式の統一

Android は令和年（`receipt_items`）と西暦 `yyyy-MM-dd`（`deposit_meisai` / `general_receipts`）が
混在（§11.1）。**AoiroChobo 形式では西暦 ISO `yyyy-MM-dd` に一本化**し、令和→西暦変換はスマホ側の責務にする。
こちらの `EntryDate` は TEXT の西暦。

### C. 税率コードのマッピング表

| AoiroChobo（`DebitTaxRate`/`CreditTaxRate`） | Android 側 |
|---|---|
| `10` / `8` / `8_old` / NULL | `RakurakuTekiyou.taxRate` = `10%`/`8%`/`非`/`不`/空（自由文字列）、`YayoiAccount.defaultTaxCategory` = `課対仕入10`/`課対仕入8`/… |

こちらは借方・貸方で別々に税率を持つ（現物払い等）。単純な現金/預金/レシート仕訳は片側 `NA` で済むので
実害は小さいが、**契約で AoiroChobo 側のコード体系を正とし、変換表を `docs/integration/` に置く**。
`DefaultTaxCategory`（`Taxable`/`NonTaxable`/`NotApplicable`/`TaxExempt`/`NA`）も同様。

### D. 預金スロット（`BankSlotNo`）

Android の `deposit_meisai` に銀行識別子が無い（単一通帳前提）。AoiroChobo は複数預金スロット対応。
**取込時にどのスロットへ入れるか**を、列 or 取込 UI の選択で解決する必要がある。
契約に「預金取引には口座識別子列を持たせる（無ければ取込時にユーザーが 1 スロット指定）」を明記。

### E. 貸方の相手科目（レシート）

Android §6.3 の相手科目は `現金`/`クレジット`/`PayPay` など文字列。「クレジット」「PayPay」は
らくらく標準科目に無い（→ 未払金 / 事業主借 相当）。**スマホ側が PC スナップショットの Code に
解決してから吐く**のが正。契約に「相手科目も解決済み Code 必須。生の支払方法テキストは `meta` に
補助情報として同梱可」。

### F. 個別上書き・除外・集計行の適用責任

Android は自分の CSV でこれらを反映していない（§11.3 個別上書き未反映、§11.4 `isExcluded` 未フィルタ、
§3.3 小計/合計行）。**AoiroChobo 出力を作る時点でスマホ側が全部適用済みにする**ことを契約で明文化：

- `overrideYayoiAccountId` / `overrideTekiyouId` を優先適用
- `isExcluded == true` は出力しない
- `productName` に「小計」「合計」を含む行は出力しない

### G. 返品・マイナス金額

Android は負の金額を 1 行で持つ（§3.2、§10 末尾）。こちらの `JournalEntry.Amount` は
正数＋Dr/Cr で表す前提（`BalanceCalculator` の挙動要確認）。
**契約は「金額は常に正、返品はスマホ側で Dr/Cr を入れ替えて出力（Dr 買掛金 / Cr 商品科目）」を推奨。**

### H. 文字コード・バージョン

- AoiroChobo 取込形式：**UTF-8 (BOM なし)・LF・RFC4180 クォート**（CSV の場合）。
  弥生の MS932/CRLF は継承しない。
- ファイル先頭 or 同梱マニフェストに `schemaVersion` 必須（Android の現行 CSV にはバージョン印が無い）。

---

## 細かい確認事項

1. **`enum` 生成**：`SourceType`/`LedgerType`/税率コード/`DefaultTaxCategory`/`Direction`/`AccountType` は
   C# enum・シードから機械生成して `vocabulary.json` に同梱（手維持しない、既決）。
   Android 仕様書 §9 の列挙値カタログはこの生成物と突き合わせて検証する契約テストを両側に置く。
2. **`SlipNo` / `SortOrder`**：AoiroChobo 側の伝票番号・枝番はスマホは知らない。取込時に PC 側で採番
   （その日の `max(SortOrder)+1`）。契約に「スマホは採番しない」。
3. **`MemoName` vs `MemoTemplateId`**：取込仕訳は摘要フリーテキスト（`MemoName`）で入れ、`MemoTemplateId`
   は張らない想定でよいか（＝科目ロックはかからない、`SourceType=OcrImport` は
   「取込後 PC が所有・編集可・ロックしない」設計と整合）。要明記。
4. **`HasInvoice` / `BusinessRatio`**：Android は `registrationNumber`（T+13桁）を持つがインボイス該否の
   判断まではしていない。`meta.registrationNumber` を渡してもらい、`HasInvoice` は PC 側判定 or 取込後編集。
5. **仕様書の対象が未マージブランチ**：`feature/gemini-ocr` @ `5889f13` / Room DB v33。
   マージ・スキーマ変動で仕様がズレるリスク。契約テストのゴールデンで検知する。
6. **`docs/yayoi-csv-export-spec.md`**：Android 仕様書 §11.6 が「別プロジェクト "AoiroChobo" 由来の
   参考資料で表記が食い違う」と指摘。こちらの弥生 CSV 仕様書が古い可能性。ただし AoiroChobo 連携は
   独自形式なので影響は限定的。

---

## 整合が取れている点（そのまま採用可）

- 税込・整数円・消費税額を分離しない（簡易課税・第二種）→ こちらの税率コードのみ保持と一致
- 二重仕訳の Dr/Cr マッピング（§10 の表）→ こちらの 5 帳簿モデルと一致
- `Account.Code` を年度跨ぎの安定参照に（→ `TransactionClip` と同方針）
- 決定的・オフライン取込
- 紙の伝票は紙が証憑・画像は扱わない → こちらの「伝票画像は扱わない」と一致
- `ocr_variants` 等 OCR 学習系は非稼働＝無視でよい

---

## こちら側の宿題

- `docs/integration/` フォルダは新規（この REVIEW-notes.md が最初のファイル）。契約一式
  （JSON Schema / 取込形式列定義 / `vocabulary.json` / ゴールデン例 / CHANGELOG / DECISIONS）は未着手。
- `Account` に `Code` カラムは存在する（`functional-design.md`）。`AllowsTaxable`/`AllowsNonTaxable` も設計済み。
  スナップショット書き出しに必要なフィールドは揃っている。
- `JournalEntry.ExternalId` は実装済み（`20260901062756_SplitTaxRateAndAddExternalId`、現在未使用）。
  `EditedAfterImport`/`ImportBatchId` は Phase 4 着手時にマイグレーション。

---

## 推奨アクション

1. **上の「前提のズレ ①②③」をスマホ側開発者（＝ユーザーが作成中のスマホ仕様書）にフィードバック。**
   特に「マッチングはスマホ担当」「マスタは PC が所有」「AoiroChobo 専用の 3 番目の出力フォーマットが
   必要」の 3 点。
2. **§10 の JSON 案を叩き台にするか CSV を貫くか**を決める（Claude の見解：JSON 寄りが自然）。
3. スマホ仕様書が揃ったら `docs/integration/` に ①`vocabulary.json` スキーマ ②取込データ形式列定義
   ③`ExternalId` 生成規則 をドラフト。

---

## 追記（2026-09-10・決定と契約ドラフト作成）

- **①②③ すべてこちらの方針で確定**。契約ドラフトを `docs/integration/` に作成：
  `README.md` / `vocabulary-snapshot.md` / `transaction-import.md` / `examples/*.sample.json` / `CHANGELOG.md`。
- **③ ＝ JSON で確定**（CSV 案は取り下げ。`transactions.json`）。
- **科目参照キーは `accountKey` に変更**（本ノート §「整合が取れている点」および §A・§E で
  `Account.Code` を安定参照としていたのを撤回）。理由：`Account.Code` は DB の UNIQUE 制約が無く、
  科目登録画面で検索用文字列として露出しているだけで、一意性を保証できない。
  → `Account.AccountKey`（不透明・不変・年度非依存・`(FiscalYearId, AccountKey)` に UNIQUE）を
  **Phase 4 で追加**し、それを契約キーにする。詳細は `docs/functional-design.md`
  「仕訳の由来表示と取込データの扱い」の追加スキーマ表と `vocabulary-snapshot.md` §4.4。
- スマホ側一次仕様書 §4 の `toCanonicalKey` / `normalizeTekiyou` 等の PC 移植は不要（①の帰結）。
