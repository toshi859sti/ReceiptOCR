# 引き渡しメモ — GreenFrameOCR 側の開発者へ

> **この文書の読者**
> Android アプリ「JA仕訳変換」(`com.example.greenframeocr`) の開発者／Claude Code。
>
> `docs/integration/` の契約一式を渡すにあたっての**案内状**。仕様そのものは書いていない。
> 何をどの順に読めばよいか、どこが前回から変わったか、こちらが**何を待っているか**をまとめた。

発行日：2026-09-22 ／ 対象：`schemaVersion: 2`

> **2026-09-22 追記：回答を受け取った（第 3 ラウンドまで完了）。**
> [REPLY-phone-2026-09-22.md](REPLY-phone-2026-09-22.md) で §5 の 9 項目に回答があり、
> 指摘された契約側の 3 点は**修正済み**（`accountName` の追加・§7 の返品の主語・
> `_note` の旧規約）。続く §8・§9 の継続協議 3 件にも
> [REPLY-pc-2026-09-22.md](REPLY-pc-2026-09-22.md) で回答した——
> **Purchase の `externalId` は行の UUID に変更**（`ocr:purchase:{rowUuid}`）、
> Deposit は合成番号、収入科目の税率は摘要から。`CHANGELOG.md` の最上段 2 本を見ること。
> **§5 の 9 項目に未回答は無く、往復検証も手順 3 まで終わっている**
> （ゴールデン例は契約検証を通る）。残りはスマホ側の実装と手順 2・4・5。

---

## 1. 結論から

**契約は完成していて、AoiroChobo 側は書き出し・取込とも実装が終わっている。**
動くものと突き合わせながら実装できる状態なので、スマホ側の着手を待っている。

残っているのは**スマホ側にしか決められない 9 項目**（§5）だけ。
仕様の穴ではなく、実装方針の確認事項。

---

## 2. 渡すもの

`docs/integration/` フォルダ一式。AoiroChobo リポジトリが所有しているので、
**copy か submodule で取り込んで参照**してほしい（スマホ側リポジトリで編集しない）。

| ファイル | 中身 |
|---|---|
| `README.md` | 全体像・役割分担・**必須決定事項 A〜O の回答表** |
| `vocabulary-snapshot.md` | PC → スマホ：`vocabulary.json` 仕様 |
| `transaction-import.md` | スマホ → PC：`transactions.json` 仕様 |
| `matching-rules.md` | スマホ側マッチングエンジンの設計（ルールエンジンと LLM の境界） |
| `CHANGELOG.md` | `schemaVersion` ごとの変更履歴 |
| `examples/vocabulary.sample.json` | `vocabulary.json` のゴールデン例 |
| `examples/transactions.sample.json` | `transactions.json` のゴールデン例 |
| `REVIEW-notes.md` | スマホ側一次仕様書（`docs/PC_ACCOUNTING_INTEGRATION_SPEC.md`）へのレビュー所見 |
| `REPLY-phone-2026-09-10.md` / `REPLY-phone-2026-09-22.md` | スマホ側からの回答（第 1〜3 ラウンド） |
| `REPLY-pc-2026-09-22.md` | PC 側からの回答（§8・§9 の継続協議 3 件へ） |
| `HANDOVER.md` | この文書 |

`docs/PC_ACCOUNTING_INTEGRATION_SPEC.md`（スマホ側の一次仕様書）は AoiroChobo リポジトリにも
写しを置いてある。§3 の ①②③ はこれへの回答なので、突き合わせるときはそちらも参照のこと。

---

## 3. 読む順番

1. **`README.md` §0「まず結論」** — スマホ側一次仕様書との差分 3 点（①②③）。ここだけは必ず。
2. **`README.md` §5「契約の必須決定事項」** — A〜O の回答表。15 項目すべてに答えが入っている。
3. 作るものに応じて **`vocabulary-snapshot.md`**（読む側）か
   **`transaction-import.md`**（書く側）。
4. マッチングを実装するなら **`matching-rules.md`**。
5. 最後に **`examples/`** の 2 ファイルを実物として突き合わせる。

### いちばん大事な 3 点（`README.md` §0）

スマホ側一次仕様書は「PC が旧モデルで動く」前提で書かれている。実際は以下で確定している。

| | 一次仕様書の前提 | 確定方針 |
|---|---|---|
| ① 商品名・摘要 → 勘定科目のマッチング | PC がやる（`toCanonicalKey` 等を PC へ移植） | **スマホがやる** |
| ② 勘定科目・摘要辞書マスタの所有者 | スマホが持ち、PC へバックアップを渡す | **AoiroChobo が持つ**。PC → スマホへ `vocabulary.json` |
| ③ スマホ → PC の取引データ形式 | CSV 前提の記述が残る | **JSON で確定** |

①②は**スマホ側の弥生／らくらく出力には影響しない**（それらは今のままでよい）。
AoiroChobo 向け出力を作るときだけこの契約に従う＝**3 番目の出力形式を足す**話。

---

## 4. 前回のドラフトから変わったところ

**旧ドラフトを受け取っている場合は、ここだけ差し替えてほしい。**

### 4-1. `accountKey` の「作り替え」規則が逆になった（2026-09-13 全面改訂）

| | 内容 |
|---|---|
| 旧 | `accountKey` は**概念**に 1 対 1。科目を別用途に作り替えたら**新しいキーを採番**する。引退キーは `AccountKeyRegistry` が管理 |
| **現行** | `accountKey` は科目マスタの**行**に 1 対 1。作り替えても**キーは据え置き、`name` だけ変わる**。`AccountKeyRegistry` も `keyRevision` も作らない |

**スマホ側の対応が変わる。**

- 主機構は「**`name` が変わったキーの学習を外す**」。学習データと一緒に「そのとき見た `name`」を
  保持しておき、新しい `vocabulary.json` と比較する。
- 「ファイルに無くなったキーの学習は使わない」は**残る**。ただし用途は科目の**無効化**のみ
  （作り替えではキーが消えなくなったため）。
- 詳細：`vocabulary-snapshot.md` §4.6。

> `accountKey` は**表示位置ではない**点に注意。ユーザーは科目を並べ替えられるが、
> キーは行に付いたまま一緒に動く。位置に紐づけると並べ替えだけで学習が別科目に付け替わる。

### 4-2. 摘要の参照キーは `memoKey`（`schemaVersion` 2 で導入）

摘要名は同名が存在し得るので参照キーにしない。`memoName` は**表示用のエコー**に格下げ、
`memoSearchKey` は廃止。`vocabulary-snapshot.md` §4.8。

### 4-3. `bankSlotNo` の有効値は **1〜5**

契約初版にあった「`0` ＝親『普通預金』」は実装に存在しない
（親科目は見出しで出納帳が無い）。`transaction-import.md` §6。

### 4-4. `enums.taxRate` に `"1"` が増えた（前方互換・2026-09-14）

2027 年からの食料品 1% 軽減税率。`["10", "8", "1", "8_old", "non", "na", "men"]` になる。
**スマホが `"1"` を出す必要は当面ない**が、`vocabulary.json` の摘要が持ち始めるので
**読めること**は要る。未知の値として弾かないこと。

---

## 5. 回答がほしいこと（9 項目）

こちらでは決められない。**§5-1 の 4 件が先**で、これが決まれば往復検証まで進める。

### 5-1. 引き渡しに関わる（先に決めたい）

| # | 項目 | なぜ要るか | 参照 |
|---|---|---|---|
| 1 | `vocabulary.json` の**取込経路** — スマホ側の UI と保存先 | AoiroChobo 側の書き出し画面に「この後どうするか」を案内したい。受け渡しは手動（USB / クラウド / メール）の想定 | `vocabulary-snapshot.md` §1 |
| 2 | **預金スロットの割り当て方法** — スマホ設定で固定するか、取込ごとに選ぶか | Android は単一通帳だが AoiroChobo は普通預金の補助口座を 1〜5 持てる。どちらでも契約は成り立つので実装方針を知りたい | `transaction-import.md` §6 |
| 3 | **`contentHash` の正規化仕様**をそちらの実装と突き合わせたい | 手順は `vocabulary-snapshot.md` §5 に書いたが、ソート順・`null` 除去・空白なし JSON 化のどこかがずれると毎回不一致になる。**不一致でも取込は止めない**ので気づきにくい | `vocabulary-snapshot.md` §5 |
| 4 | **ゴールデン例の往復検証**をやりたい | §6 の手順 | `examples/` |

### 5-2. マッチングエンジンの詰め（後でよい）

| # | 項目 | 参照 |
|---|---|---|
| 5 | 正規化関数の仕様（AoiroChobo の `SearchKey` 正規化と揃えるか） | `matching-rules.md` §8 |
| 6 | 金額バンドの既定許容幅 | 同上 |
| 7 | 確信度の閾値（何回の確定で「暗黙適用」に昇格するか） | 同上 |
| 8 | `corrections.json`（PC → スマホのフィードバック）を作るか。作るならスキーマ | 同上 |
| 9 | ラベル選好キャッシュを実装するか | `vocabulary-snapshot.md` §4.7 |

---

## 6. 動作確認の進め方

AoiroChobo 側は**ホーム →「スマホ連携」**に両方向の導線がある。

```
科目・摘要を書き出す   → aoirochobo_vocabulary_{年度}_{日付}_{時刻}.json
取引ファイルを取り込む → ja_shiwake_{日付}_{時刻}.json を読む
```

推奨の順番：

1. **AoiroChobo で `vocabulary.json` を書き出し**、スマホ側で読めることを確認する。
   `examples/vocabulary.sample.json` と構造を突き合わせる。
2. スマホ側で `transactions.json` を 1 ファイル作り、**AoiroChobo で取り込む**。
   まず `examples/transactions.sample.json` をそのまま食わせて通ることを確かめるとよい。
3. `contentHash` が一致するか確認する（§5 の 3 番）。

### 取込側の挙動で知っておいてほしいこと

- **契約違反が 1 件でもあればファイルごと中止する。部分取込はしない。**
  画面に「どの行のどの項目か」を行番号つきで出すので、詰まったらその表示を送ってほしい。
  よくある違反：`entryDate` が空／実在しない日付、`amount` が 0、`externalId` の重複、
  `matchStatus=UnmatchedMemo` なのに `memoKey` が入っている。
- **`matchStatus != "Matched"` の行は自動で帳簿に入らない。**
  `UnmatchedMemo` / `Ambiguous` もユーザー確認を通す。
  **迷ったら素直に `Ambiguous` / `UnmatchedMemo` を返してよい**——PC 側が拾う。
- **`ledgerType` は省略してよい。** 借方・貸方の科目の `ledgerAffinity` から PC が推定する。
- **`contentHash` 不一致は警告だけで取込は止めない。**
- **`memoName` は照合にも表示にも使っていない。** 帳簿に入る摘要名は当年度マスタの現在名。

---

## 7. 版の扱い・連絡

- `schemaVersion` は `vocabulary.json` と `transactions.json` で**共通**。
  破壊的変更のときだけ整数を +1 する。フィールド追加（前方互換）では上げず、
  `CHANGELOG.md` に「minor」として記録する。
- 契約の所有者は AoiroChobo 側。**変更はこのフォルダを更新して再配布する**ので、
  スマホ側リポジトリのコピーを直接編集しないでほしい（次の配布で消える）。
- 契約テストは**両側に置く**方針。スキーマ検証＋`examples/` との一致を CI で確認する。

§5 の回答が返ってきたら `CHANGELOG.md` 末尾の未確定事項を消し込み、必要なら版を上げる。
