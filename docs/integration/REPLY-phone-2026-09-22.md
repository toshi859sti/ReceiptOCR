# 連携契約レビュー — スマホ側からの回答（schemaVersion 2 / 2026-09-22 受領分）

> **この文書の読者**：AoiroChobo（PC）側の開発者／Claude Code。
> **書き手**：Android アプリ「JA仕訳変換」(`com.example.greenframeocr`) 側。
> [HANDOVER.md](HANDOVER.md) で渡された契約一式（`schemaVersion: 2` / 2026-09-22 版）への回答。
>
> スマホ側の突き合わせ対象：`feature/gemini-ocr` @ `4f71a98` / Room DB v33 / 連携機能は未実装
> 前回の回答：[REPLY-phone-2026-09-10.md](REPLY-phone-2026-09-10.md)（`schemaVersion: 1` 向け・**§7 の通り一部撤回**）

---

## 0. 結論

| # | 論点 | 回答 |
|---|---|---|
| 1 | [HANDOVER.md](HANDOVER.md) §5-1 の 4 件（引き渡しに関わる分） | **すべて回答**（§2） |
| 2 | 9/10 回答 §A「自前科目 → PC 科目の接続キーが無い」 | **依頼を取り下げる**。スマホ側だけで閉じる。**契約変更は不要**（§1） |
| 3 | 2026-09-13 改訂（`accountKey` 据え置き／`name` の変化で学習を外す） | **受諾**。実装タスク化（§3） |
| 4 | 契約側に直してほしい点 | **3 件**。うち 1 件は往復検証のブロッカー（§4）→ **同日中に PC 側が 3 件とも修正済み**（§8-0） |
| 5 | §7 の継続協議 3 件（第 2 ラウンド） | **§8** で回答。Purchase の `externalId` は **UUID 方式への変更を提案**（§8-1）。Deposit の空欄フォールバックは**ハッシュ不要**（§8-2）。収入科目の税率は了解（§8-3） |
| 6 | スマホ側の実装状況（第 3 ラウンド） | **DB v34 まで完了**（§9）。回答待ちは Purchase の `externalId` を UUID 方式にする件だけ（§9-3） |

---

## 1. §A（接続キー問題）は取り下げる — 契約変更は不要

**9/10 に依頼した `vocabulary.json` の `Account` への `rakurakuAccountCode` / `yayoiAccountCode` /
`aliases` 追加は、すべて不要になった。** スマホ側だけで閉じられることが確認できたため。

### 解き方

スマホの自前科目マスタに **`accountKey: String?` 列を 1 本足すだけ**にする。

| テーブル | 変更 |
|---|---|
| `yayoi_accounts`（98 行） | `accountKey: String?` を追加 |
| `rakuraku_accounts`（61 行） | `accountKey: String?` を追加 |
| `product_master` / `tekiyou_matching_rules` / `general_item_master` / `receipt_payment_method_rules` | **無変更**（`yayoiAccountId: Long?` のまま） |

マッチング結果の解決時に account master を 1 ホップするだけで `accountKey` が取れる。
既存のマッチング資産（`product_master` の確定済み商品名 → 科目など）を作り直す必要がない。

### 列の初期投入は「自動提案 ＋ 1 回の確定」で足りる

`vocabulary.json` のシステム科目 `accountKey` が歴史的スラッグ（`genkin` / `hiryou` / `kaikake` …）である
こと（[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.4）を利用して、**らくらく側のサーチキー英字を
初期提案のヒントとして使う**。実測：

| 突き合わせ | 結果 |
|---|---|
| `examples/vocabulary.sample.json` の `accountKey`（27 件）↔ `rakuraku_accounts.searchKeyAlpha` | **22 件が完全一致** |
| 一致しなかった 5 件 | `bank3`（預金補助スロット）・`suitou` / `nougai`（田畑・任意スロット）・`zigyounusikari` / `zigyounusikas`（らくらく側は `zigyounusi` 1 本に統合されている） |
| `rakuraku_accounts` 内の重複サーチキー | 4 件（`hiryou` / `kasidaore` / `totikairyou` / `zigyounusi`） |
| `yayoi_accounts`（98 行）↔ らくらくのサーチキー | **16 件しか一致しない**（`GENKIN` / `TOUZAYO` … 表記体系が別。内部にも重複が 6 件） |

→ **らくらくモードはほぼ自動確定、弥生モードは手動確定が主**になる。弥生は
`yayoi_accounts → rakuraku_accounts` を挟まず、`yayoi_accounts.accountKey` を直接ユーザーに確定させる。

`searchKey` は**参照キーとしては使わない**。マッピング UI の初期提案を出すためだけに使い、
確定するのはユーザー。[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.1 の
「一意保証なし・スマホは使わなくてよい」に反しない使い方に留める。

### 帰結

- PC 側に追加フィールドの実装依頼は無し。
- 9/10 回答の「次善策（マッピング UI）」を**本線として採用**する。
- 未マッピングの科目に当たった仕訳は `matchStatus = "UnmatchedAccount"` で出す（契約通り落とさない）。

---

## 2. HANDOVER §5-1 の 4 件への回答

### 2-1. `vocabulary.json` の取込経路（スマホ側の UI と保存先）

既存の導線をそのまま使える。スマホには SAF ベースの JSON インポートが既にある
（`SettingsScreen.kt` の「💾 データ管理」セクション・`ActivityResultContracts.OpenDocument`）。

| 項目 | 回答 |
|---|---|
| UI の場所 | **設定 > データ管理** に「**AoiroChobo 科目・摘要を取り込む**」を追加 |
| 既存のバックアップ取込との関係 | **別項目にする**。既存の「インポート」は自前 DB のバックアップ復元で、外部マスタの取込とは性質が違うため同じ選択肢に混ぜない |
| ファイル選択 | SAF（`ACTION_OPEN_DOCUMENT` / `application/json`）でユーザーが選ぶ。**自動同期はしない**（契約通り） |
| 保存先 | 新規 Room テーブル 3 つ：`aoirochobo_accounts` / `aoirochobo_memo_templates` / `aoirochobo_vocab_meta`（1 行） |
| なぜ DB か | マッチング時に SQL で引きたい。ファイルのまま持って毎回パースはしない |
| DB バージョン | **v33 → v34**（`ReceiptDatabase.kt` にマイグレーション追加） |
| 保持年度 | **1 年度分のみ**。`fiscalYear.year` が変わったら旧データを破棄 |
| 未知の enum | **弾かない**。警告表示に留め、その値を使う行は `matchStatus` を立てて出す（`taxRate: "1"` もこれで通る） |
| 鮮度表示 | `generatedAt` / `fiscalYear.year` から「このマスタは N 日前のものです」を取込画面に表示 |

**取込時に走らせる処理**（9/10 回答には無かった項目。2026-09-13 改訂への対応）：

1. `schemaVersion` を検証（`2` 以外は警告して中止）。
2. `contentHash` が前回取込値と**文字列一致**するならスキップ（§2-3）。
3. **`name` が変わった `accountKey` / `memoKey` の学習を外す** ← 主機構。
4. ファイルから消えた `accountKey` / `memoKey` の学習を非アクティブ化（科目・摘要の**無効化**用に存続）。
5. `yayoi_accounts.accountKey` / `rakuraku_accounts.accountKey` の**差分だけ**ユーザーに再確認させる
   （消えたキー・増えたキー）。

### 2-2. 預金スロットの割り当て方法

**スマホ設定で固定する**（取込ごとに選ばせない）。

| 項目 | 回答 |
|---|---|
| 保持場所 | `AppPreferences` に `aoirochoboBankSlotNo: Int` を 1 つ |
| **既定値** | **`1`**。9/10 回答の「既定 `0` ＝親『普通預金』」は **撤回**（`schemaVersion 2` で `0` は不正値になったため） |
| 設定 UI | 番号の直打ちにしない。取込済みの `aoirochobo_accounts` から **`ledgerAffinity == "Bank" && bankSlotNo != null`** の科目だけを選択肢に出し、**科目名で選ばせて内部で番号を持つ**（[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.3 の通り） |
| `vocabulary.json` 未取込のとき | AoiroChobo 形式の**出力自体をブロック**する。スロット不明のまま出すと全 Deposit 行が解決不能になるため |
| 複数口座 | 当面スコープ外。`deposit_meisai` は銀行識別子を持たない単一通帳前提（実装確認済み）。要望が出たら「取込時に選択」へ拡張 |

### 2-3. `contentHash` の正規化仕様

**スマホ側は `contentHash` を再計算しない。** したがって**正規化仕様の突き合わせは不要**と考える
（HANDOVER §5-1 の 3 番は、この回答で消し込めるはず）。

| 用途 | 扱い |
|---|---|
| 取込スキップ判定 | 前回取り込んだ値と**文字列一致**するかだけ見る |
| `transactions.json` への転記 | 受け取った `vocabulary.json` に書かれていた値を**そのまま**入れる |

理由：

- Kotlin/Gson と C#/System.Text.Json で正規化のバイト一致（キー順序・数値表現・エスケープ）を
  保つのは事故りやすく、ズレても**取込は止まらない**（[transaction-import.md](transaction-import.md) §11 は
  警告のみ）。検知価値に対して実装コストと誤警告リスクが見合わない。
- スマホが値を転記するだけなら、PC 側のマスタずれ検知は**完全に成立する**（スマホがどの
  スナップショットに対して解決したかが一意に分かる）。

PC 側が両側実装による厳密検証を望む場合は、正規化を **RFC 8785 (JSON Canonicalization Scheme)** に
寄せることを提案する。独自正規化のまま両側実装するのは避けたい。

### 2-4. ゴールデン例の往復検証

HANDOVER §6 の順番で異存ない。ただし **§4-1（`accountName` が契約に未定義）を先に潰す必要がある**。
これが決まらないと `transactions.json` のゴールデン例の形が確定しない。

進め方：

1. §4 の 3 件を契約側で修正 → `examples/` を更新して再配布。
2. AoiroChobo で `vocabulary.json` を書き出し、スマホで取込（`examples/vocabulary.sample.json` と構造突き合わせ）。
3. `examples/transactions.sample.json` を**そのまま** AoiroChobo に食わせて通ることを確認（スマホ実装前でも可）。
4. スマホが `transactions.json` を 1 ファイル生成 → AoiroChobo で取込。
5. `contentHash` の転記が効いているか確認。

---

## 3. 2026-09-13 改訂（`accountKey` 据え置き）への対応

受諾する。[CHANGELOG.md](CHANGELOG.md) のスマホ側チェックリストに対する現状：

| 項目 | 状況 |
|---|---|
| 学習エントリに「そのとき見た `name`」を保存する | **未実装**。§6 のタスク 3 で対応 |
| スナップショット取込時に `name` の変化を検出して該当学習を外す | **未実装**。§2-1 の取込処理 3 で対応 |
| `accountName` / `memoName` を `transactions.json` に必ず載せる | **契約側が未定義**（§4-1）。定義され次第、必ず載せる |
| `keyRevision` を参照しているコードが残っていないか | **該当なし**。スマホ側は連携機能を未実装のため、`accountKey` / `keyRevision` を参照するコードは 1 行も存在しない |

「名前が変わったら学習を外す＝安全側に失敗する」という設計に異論はない。打ち間違いの修正で外れても、
次に同じレシートを撮って 1 回選び直せば再学習するだけで、ユーザーの不利益は小さい。

---

## 4. 契約側に直してほしい 3 点

### 4-1.【ブロッカー】`accountName` が契約に定義されていない

[CHANGELOG.md](CHANGELOG.md)（2026-09-13・変更 4）と
[vocabulary-snapshot.md](vocabulary-snapshot.md) §4.6「残る穴」は、

> 対処：`transactions.json` が**既に持っている** `accountName`（人間可読のエコー）を PC 側が使い、
> 取込時に現在の名前と突き合わせ、食い違っていたら「要確認」に回す。
>
> **スマホ側への要求**：`accountName` / `memoName` は任意フィールドのままだが、**送れるときは必ず送ること**。

と書いているが、**`transaction-import.md` §3 の `Entry` スキーマにも `debit` / `credit` オブジェクトにも
`accountName` は存在せず**、`examples/transactions.sample.json` にも入っていない
（`memoName` だけが定義済み）。

キーを引退させなくなった以上、これが**古いスナップショットからの取込を検知する唯一の機構**なので、
穴として残すのは危ない。以下の形での追加を提案する：

```jsonc
"debit":  { "accountKey": "hiryou",  "accountName": "肥料費", "taxRate": "10", "businessRatio": 100 },
"credit": { "accountKey": "kaikake", "accountName": "買掛金", "taxRate": null, "businessRatio": 100 },
```

- 任意フィールド。値は「そのマッチングを決めたときに見えていた名前」。`accountKey` が `null` なら `null`。
- 検証は `memoName` と同じ扱い：入っていれば現在名と突き合わせ、**不一致はエラーにせず**
  取込サマリーに「マスタが古い可能性」と警告（[transaction-import.md](transaction-import.md) §11）。
- あわせて `examples/transactions.sample.json` にも入れてほしい（ゴールデン例の形が確定するため）。

### 4-2. `examples/vocabulary.sample.json` の `_note` が旧規約のまま

現在の `_note` に次の一文が残っている：

> 科目を別用途に作り替えると新しい accountKey が採番され、旧キーはこのファイルから消える（§4.6）

これは 2026-09-12 版の規約で、**2026-09-13 改訂で撤回された内容**（現行は「キーは据え置き、`name` だけ
変わる」）。ゴールデン例を先に読んだ実装者が逆の実装をする危険がある。

### 4-3. `transaction-import.md` §7 の `isReturn` の文言（9/10 に指摘・未反映）

§7 は「元データが負（返品・値引き）の場合：借方／貸方を入れ替えて正数で出す」としているが、
**Deposit の出金（`amount < 0`）は返品ではなく通常の資金移動**で、`isReturn = false`。
借方／貸方の振り分けは §5 の表で既に正しく分岐しているので、§7 の主語を
**「Purchase / Receipt で元データが負の場合」**に限定してほしい。

---

## 5. HANDOVER §5-2（後でよい 5 件）への暫定見解

実装しながら詰めたいので**暫定**。契約フィールドには影響しない。

| # | 項目 | 暫定見解 |
|---|---|---|
| 5 | 正規化関数（PC の `SearchKey` 正規化と揃えるか） | **揃えない**。スマホ側は既存の `toCanonicalKey` / `normalizeTekiyou`（全角半角・カナ・数字接頭辞除去の実績あり）をそのまま使う。照合はスマホ内で完結し、契約に出るのは `accountKey` だけなので揃える必要がない |
| 6 | 金額バンドの既定許容幅 | **±20% かつ下限 ±1,000 円**を初期値に、確定例が増えたら狭める |
| 7 | 確信度の閾値 | 確定 1 回 → 暫定（適用するが「推定」表示）／一貫した確定 **3 回** → 暗黙適用。[matching-rules.md](matching-rules.md) §5 の「2〜3 回」のうち安全側を採る |
| 8 | `corrections.json`（PC → スマホ） | **当面作らない**。確定の主戦場はスマホのレビュー画面で、PC の「要確認」は安全網という整理に同意。PC 側での訂正が常態化したら作る |
| 9 | ラベル選好キャッシュ | **実装する**（記帳バンドルとは完全分離）。`memoKey` が改名・年度繰越で不変になったので、照合は「新しい `vocabulary.json` にそのキーが残っているか」だけで済み、コストが低い |

---

## 6. スマホ側の実装タスクと順序

1. `yayoi_accounts` / `rakuraku_accounts` に `accountKey: String?` 追加（§1）。
2. `aoirochobo_accounts` / `aoirochobo_memo_templates` / `aoirochobo_vocab_meta` 追加。**DB v33 → v34**。
3. 学習系テーブルに「そのとき見た `name`」列を追加（§3）。
4. 設定 > データ管理に `vocabulary.json` 取込 UI ＋ 取込処理 1〜5（§2-1）。
5. 科目マッピング UI（サーチキー英字で自動提案 → ユーザー確定 → 以後は差分のみ）（§1）。
6. `general_receipts` に `uuid: String` 追加＋既存行へのバックフィル（`externalId` の復元耐性・
   [transaction-import.md](transaction-import.md) §4。現状 PK は autoincrement `id` のみで復元に耐えない）。
7. 預金スロット設定（§2-2）。
8. 出力確認画面に「AoiroChobo 形式（JSON）」を追加（弥生・らくらく CSV に続く 3 番目）。
9. `transactions.json` ビルダー（借方／貸方組み立て・`externalId` 採番・`matchStatus` 判定・
   返品の Dr/Cr 入替・個別上書き／`isExcluded`／集計行の適用）。
10. 契約テスト：`examples/` をゴールデンにスキーマ検証。

1〜3・6 はマイグレーションなのでまとめて 1 回の DB バージョンアップにする。

---

## 7. 9/10 回答（`schemaVersion 1` 向け）からの撤回・更新

[REPLY-phone-2026-09-10.md](REPLY-phone-2026-09-10.md) は `schemaVersion 1` への回答で、以下が古い。

| 箇所 | 9/10 の内容 | 現在 |
|---|---|---|
| §A | `vocabulary.json` に `rakurakuAccountCode` / `yayoiAccountCode` / `aliases` を追加してほしい | **取り下げ**。スマホ側で閉じる（§1） |
| §D | 預金スロット既定値 `0`（親「普通預金」） | **撤回**。既定 `1`（`0` は `schemaVersion 2` で不正値）（§2-2） |
| §H・§K | 摘要がフリーテキストのままなら `UnmatchedMemo` | 摘要は**閉じた語彙**に変更済み。`memoKey` は辞書のキーか `null` の二択、生テキストは `note` へ |
| 全体 | 科目参照を `code` と表記 | `accountKey` に統一 |
| §K | 未確定事項 A / C / E / F / G | A は取り下げ（§1）、C は §2-3 で回答、E・F・G は下記の通り**継続協議** |

**継続協議として残るもの**（今回は回答しない）：

- Purchase で行の並べ替え／伝票移動が起きたときの旧 `externalId` の扱い（void 相当で残す想定でよいか）。
- Deposit の `transactionNumber` が空欄のときの `externalId` フォールバック規則。
- 収入科目の税率を「科目の `defaultTaxCategory` ではなく一致した摘要から引く」で良いか
  （`yayoi_accounts.defaultTaxCategory` は `課税売上` としか持たず 8% / 10% を区別できないため）。

---

---

## 8. 第 2 ラウンド（同日・PC 側の反映を受けて）

PC 側が §4 の 3 点を反映（[CHANGELOG.md](CHANGELOG.md) 最上段「2026-09-22 minor」）。
**3 件とも確認した。** そのうえで §7 の継続協議 3 件に回答する。

### 8-0. `accountName` の扱い（確認のみ・異存なし）

不一致を「警告どまり」でなく**その行を「要確認」に回す**に変えた件、**同意する**。
`matchStatus = "Matched"` のまま一括確定を素通りしては足した意味が無い、という理由に異論はない。

スマホ側の実装メモ（出力は同じなので契約には影響しない）：

- `accountName` / `memoName` は**出力時に、保持している `vocabulary.json` スナップショットから引く**。
  学習レコードに焼き込んだ値を送るのではなく、エクスポート時点のスナップショットの `name` を引く。
  取込時に「`name` が変わった学習は外す」（§2-1 の処理 3）を通しているので、両者は一致する。
- したがって**エコーが現在名とズレるのは「スマホが古いスナップショットのまま出力したとき」だけ**になり、
  この機構の意図どおりの発火条件になる。
- スマホ側は `vocabulary.json` 未取込なら出力自体をブロックし（§2-2）、取込画面に鮮度（N 日前）を出す。

### 8-1.【継続協議 1】Purchase の `externalId` — **UUID 方式に変えたい**

**そちらの提案（消えた `externalId` を void にせず、重複候補としてサマリーに出す）は、
orphan 側の対処としては妥当。ただしそれだけでは足りない。** 危ないのは消える側ではなく
**衝突する側**で、実コードを確認したところ現実に起きる。

**根拠（`ReceiptInputScreen.kt`）**

- 伝票グリッドの行アクションに **「挿入」「削除」があり、どちらも以降の行を全部シフトする**
  （`rows[i] = rows[i - 1]` / `rows[i] = rows[i + 1]`）。ワンタップ操作で、入力中は普通に使う。
- 保存時の `itemNumber` は `rowNumber = index + 1` で**リストの位置から振り直される**。
- つまり 5 行目に 1 行挿入すると、**6 行目以降の `externalId` がすべて 1 つずつずれる**。
  空いた `externalId` には隣の行の商品が入る。

**このとき PC 側で何が起きるか**（[transaction-import.md](transaction-import.md) §10 の再取込規則）

> 同じ `externalId` が未編集で存在 → 内容差分があれば**更新**

＝ `ocr:purchase:202601-3-6` 以降が**別の商品のデータで黙って上書きされ**、末尾に 1 件増える。
重複候補の検出はこれを拾えない（`externalId` は同じで、日付も伝票も同じだから）。

**提案：Purchase も Receipt と同じ UUID 方式にする**

```
ocr:purchase:{rowUuid}
```

- `receipt_items` に行単位の `uuid` 列を追加する（DB v34・`general_receipts.uuid` と同じ回の
  マイグレーションで済む＝追加コストがほぼ無い）。
- 挿入・削除は**行オブジェクトごとシフトする**ので、`uuid` を載せておけば**行の内容に付いて動く**。
  並べ替えても別伝票へ移しても `externalId` は変わらない。
- 月データ保存は「月単位で全 DELETE → 全 INSERT」だが、`uuid` は UI の行データが持ったまま
  書き戻されるので保存を繰り返しても不変。既存行には移行時に一度だけ採番する。

**トレードオフは正直に書く。** UUID は**編集に強いが再作成に弱い**（ユーザーがその月を消して
入力し直すと全行が新しい `externalId` になる）。位置ベースは逆で、再作成に強いが編集に弱い。
**挿入・削除がワンタップの日常操作であるのに対し、月をまるごと消して入力し直すのは稀**なので、
UUID を採る。バックアップ復元では `uuid` 列ごと復元されるので復元耐性の要件も満たす。

**それとは別に、PC 側に 1 つ入れてほしいガード**（`externalId` の方式に関係なく効く）：

> 同じ `externalId` が未編集で存在し、かつ**借方科目（`accountKey`）または `note` が変わっている**
> 場合は、黙って更新せず「要確認」に回す。

金額や日付の修正は素直な訂正だが、**科目や商品名まで変わっているのは「別の取引になった」合図**で、
`externalId` の採番がずれたときの最後の砦になる。

### 8-2.【継続協議 2】Deposit の `transactionNumber` 空欄 — **ハッシュは不要**

調べたところ、**ハッシュも連番サフィックスも要らない**ことが分かった。

**実コードの事実**

- `deposit_meisai` には `UNIQUE(transactionDate, transactionNumber)` がある。
- 通帳 CSV 取込は `insertAllIgnoreDuplicates`（`OnConflictStrategy.IGNORE`）を使う。
- → **同じ日に取引通番が空欄の行は 2 件目以降が無言でスキップされる**。つまり
  「同日・同額・同摘要が複数」という状況は**そもそも DB に入らない**ので、
  提案にあった `-2` / `-3` の連番は**発火しない**。

**同時に、これはスマホ側のバグでもある**（取込件数が CSV の行数と合わない・ユーザーは気づけない）。
`docs/known-issues.md` に登録した。直し方が、そのまま `externalId` の答えになる：

**提案：取込時に、空欄の `transactionNumber` へ日付内の連番を合成して入れる**（`#01` / `#02` …）

- 空欄が DB に入らなくなるので、`externalId` は既存の
  `ocr:deposit:{transactionDate}-{transactionNumber}` **1 本で全ケースを覆える**。
  フォールバック規則そのものが不要になる。
- ハッシュ関数・正規化の仕様をそちらから出してもらう必要も無くなる。
- 落とされていた行が救われる（本来の目的）。

**暫定案（合成番号を入れないなら）**：`ocr:deposit:{transactionDate}-noseq`。
上記 UNIQUE 制約により「空欄の行は 1 日 1 件」が保証されているので衝突せず、
ハッシュと違って**金額や摘要を後から直しても `externalId` が変わらない**。

どちらでも構わないが、**合成番号（前者）を推す**。バグ修正も兼ねるため。

### 8-3.【継続協議 3】収入科目の税率 — 了解

摘要（`memoTemplates[].taxRate`）から引く。`defaultTaxCategory` では 8% / 10% を区別できない、
という見立ての確認をありがとう。

スマホ側の実装は [matching-rules.md](matching-rules.md) §4 の順どおり：

1. 逆引きで摘要が 1 件に決まった → その `taxRate`。
2. 複数 → 第一候補の `taxRate`＋`matchStatus = "Ambiguous"`。
3. **0 件 → `memoKey = null`（`UnmatchedMemo`）。このとき税率も決まらないので
   `taxRate = null` で出す**（科目の `defaultTaxCategory` からは埋めない）。
   収入科目でこれが起きたら、PC 側の「要確認」で確定してもらう。

経費科目はこれまでどおり「摘要 → 科目の `defaultTaxCategory` → `null`」のフォールバック。

### 8-4. 往復検証について

§4 の 3 点が解消したので、こちらのブロッカーは無くなった。
残るのはスマホ側の実装だけで、順序は §6 のとおり。

先に **手順 3（`examples/transactions.sample.json` をそのまま AoiroChobo に食わせる）だけは
スマホ側の実装を待たずに実施できる**ので、そちらで走らせておいてもらえると助かる。
スマホ側は DB v34 のマイグレーション一式から着手する。

**8-1 の UUID 方式に同意がもらえれば、`receipt_items.uuid` も同じマイグレーションに入れる**ので、
そこだけ先に返事がほしい（[transaction-import.md](transaction-import.md) §4 の Purchase の行の
書き換えが必要になる）。

---

## 9. 第 3 ラウンド — スマホ側が実装に着手（DB v34）

契約側のブロッカーが全部消えたので、§6 の順で実装を始めた。
**タスク 1・2・3・6（マイグレーション一式）が完了**している。契約フィールドへの影響は無い。

| # | 入れたもの |
|---|---|
| 1 | `yayoi_accounts` / `rakuraku_accounts` に `accountKey` ＋ `accountKeyName` |
| 2 | `aoirochobo_accounts` / `aoirochobo_memo_templates` / `aoirochobo_vocab_meta` を新設 |
| 3 | `rakuraku_tekiyou`（摘要辞書）に `memoKey` ＋ `memoKeyName` |
| 6 | `general_receipts.uuid` を追加し既存行をバックフィル（UNIQUE） |

→ **Receipt の `externalId`（`ocr:receipt:{uuid}`）は材料が揃った。** 復元耐性の要件も満たす
（`uuid` 列はバックアップ JSON に含まれ、復元時にそのまま戻る）。

実機での起動確認だけ未了（手元に実機が無いため）。スキーマ自体は Room の期待スキーマと
突き合わせて一致を確認済み。

### 9-1.【訂正】`receipt_items.uuid` は v34 ではなく **v35** になる

§8-1 で「`general_receipts.uuid` と
同じ回のマイグレーションで済む」と書いたが、**返事を待たずに v34 を先に確定させた**ので、
Purchase 側は単独の **DB v35** になる。

ただし**追加コストが小さいことは変わらない**（列追加＋既存行への一度きりの採番＋保存時に
`uuid` を引き継ぐ実装）。方式の判断材料としては §8-1 のままで、
こちらの結論も変わっていない。**同意がもらえるまで Purchase の `externalId` は出せない**ので、
そこだけ先に返事がほしい。

### 9-2.「そのとき見た `name`」の置き場所 — 学習の葉ではなく**接続キーの行**にした

[CHANGELOG.md](CHANGELOG.md) 2026-09-13 改訂のスマホ側チェックリスト 1 番への回答。
**契約フィールドには影響しないが、そちらから見た挙動が変わるので伝えておく。**

スマホの学習エントリ（`product_master` などの「商品名 → 科目」）が指しているのは**スマホ自前の
科目**で、ユーザーはその紐付けを**自前の科目名**を見て決めている。そちらがスロットを作り替えても
「肥料 → 肥料費」という判断自体は無効にならないし、この学習は弥生 CSV・らくらく CSV の出力でも
使い回しているため、PC 都合で消すと**連携と無関係な機能が壊れる**。

そこで「そのとき見た `name`」は、学習 1 件ずつではなく `yayoi_accounts.accountKey` /
`rakuraku_tekiyou.memoKey` の**行**に持たせた（`accountKeyName` / `memoKeyName`）。
取込時に現在名と食い違ったら、**その接続キーだけを外す**。

| | 契約が想定していた挙動 | スマホの実装 |
|---|---|---|
| 名前が変わったとき外すもの | その `accountKey` を使う学習エントリ | その `accountKey` との**接続 1 件** |
| そちらに届く仕訳 | `matchStatus = "UnmatchedAccount"` | **同じ** |
| ユーザーの再確認 | 学習を N 件選び直す | **科目を 1 件マッピングし直す** |
| 学習資産（商品名 → 科目） | 消える | 残る（弥生・らくらく出力でも使うため） |

「名前が変わったら安全側に失敗する」という設計意図は満たしている — その科目を通る仕訳は
**1 件残らず `UnmatchedAccount` になってそちらに届く**（契約どおり落とさない）。変わるのは
ユーザー側の直し方の粒度だけ。

### 9-3. 現在の回答待ち・依頼（再掲）

| # | 内容 | 状態 |
|---|---|---|
| 1 | §8-1 Purchase の `externalId` を **UUID 方式**にすることへの同意<br>（`transaction-import.md` §4 の Purchase の行の書き換えが必要） | **唯一のブロッカー**。返事待ち |
| 2 | §8-1 PC 側ガード：同じ `externalId` で**借方科目か `note` が変わっている**なら黙って更新せず「要確認」へ | 返事待ち |
| 3 | §8-2 Deposit は**合成番号**でいく（`externalId` は常に番号を持つ・フォールバック規則は不要）ことの確認 | 返事待ち |
| 4 | §8-4 往復検証の**手順 3**（`examples/transactions.sample.json` をそのまま AoiroChobo に食わせる）を、スマホ実装を待たずに先に走らせてほしい | 依頼 |

§5（HANDOVER §5-2 の 5 件）の暫定見解は**変更なし**。

### 9-4. 次に着手するもの

§6 のタスク 4（設定 > データ管理に `vocabulary.json` 取込 UI ＋ 取込処理 1〜5）。
これが動けば、そちらの書き出しファイルを実際に食わせる**往復検証の手順 2** に進める。

---

_作成: 2026-09-22 / スマホ側対象: `feature/gemini-ocr` @ `b93e3ec` / Room DB v33 / 連携機能は未実装_
_第 3 ラウンド追記（§9）: 2026-09-22 / `feature/gemini-ocr` @ `860c037` / Room DB v34 / 連携機能は実装中_
