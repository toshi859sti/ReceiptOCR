# CURRENT_TASK.md

## 作業タイトル
あおいろ帳簿向け transactions.json ビルダー（JA購買）

## 目的・背景
JA購買の商品には あおいろ帳簿の科目・摘要（`product_master.accountKey` / `memoKey`）を付けられるようになった
（2026-09-25）。これを契約 `docs/integration/transaction-import.md`（schemaVersion 2）どおりの
`transactions.json` にして PC に渡し、スマホ → PC の流れを初めて一周させる。

前の作業（PC会計アプリ連携仕様書〜あおいろ科目・摘要の取込・用途の絞り込み）の記録は
`.steering/20260925-PC会計アプリ連携・あおいろ科目摘要取込/CURRENT_TASK.md` にアーカイブ済み。

## 今回のタスク
- [x] ビルダー本体（純粋関数・Room に依存しない）：JA購買の行 → Entry（`util/AoiroChoboTransactionsBuilder.kt`）
- [x] 契約テスト：本番 vocabulary.json を使い、出力を契約 §11 の検証項目で突き合わせる（12件）
- [x] 出力確認画面（購買）に あおいろ形式（JSON）の出力を追加
- [ ] 実機で書き出したファイルを確認（端末未接続のため未了）

## 完了条件
あおいろモードで購買の出力確認画面から `transactions.json` を書き出せ、その中身が契約 §11 を満たす。
契約テストがそれを担保している。

## 進捗メモ

### 設計（JA購買の Entry の決め方）
- 対象行：既存の購買出力と同じく「小計」「合計」を含む行を除く。金額 0 の行は契約（amount ≥ 1）で出せないので除外して数を報告
- `externalId` = `ocr:purchase:{receipt_items.uuid 小文字}`
- `entryDate` = 令和年（`2018 + receiptYear`）-月-日。実在しない日付は出さずに報告
- 借方 = 商品の `accountKey`（当年度の科目に無ければ `null`）、`accountName` は**確定時に見えていた名前**（`accountKeyName`）
- 貸方 = `ledgerAffinity == "AP"` の科目（本番は `kaikake` 1 件）。0 件・複数なら `null`
- 摘要 = 商品の `memoKey`。当年度の辞書に在り、買掛/仕入（AP×In）で相手科目が借方と一致するときだけ載せる
- 税率 = 摘要が解決できたら摘要の `taxRate`、できなければ `null`（PC が要確認で決める）
- `matchStatus`：科目なし → `UnmatchedAccount`（摘要も null）／摘要なし → `UnmatchedMemo`／両方 → `Matched`
- 返品（金額が負）：借方/貸方を入れ替え正数にし `meta.isReturn = true`
- `note` = 商品名（生テキスト）

### 実装（2026-09-25）
- `util/AoiroChoboTransactionsBuilder.kt`：`buildPurchase(rows, accounts, memos, vocabMeta, appVersion)` →
  `Result(file, skipped, warnings)`。JSON は Gson（null も書く・HTML エスケープなし）。
  `ledgerType` は常に `AP`、`hasInvoice` は送らない（PC 既定）、`confidence` は行の OCR 確度、
  `meta.phoneExportedAt` は `exportedAt` を ISO 8601（+09:00）に直したもの
- `generatedAt` は `yyyy-MM-dd'T'HH:mm:ssXXX`（日本時間）。**`java.time` は minSdk 24 で使えない**ので
  `SimpleDateFormat` / `GregorianCalendar` で書いた（`docs/known-issues.md` に転記）
- 年度警告：vocabulary の年度以外の取引は出すが警告する（PC は当年度のマスタで解決するため）
- 出力確認画面（購買）：あおいろモードでは一覧に「科目 ／ 摘要」（今の vocabulary の名前）、ボタンは「JSON出力」、
  ファイル名 `ja_shiwake_yyyyMMdd_HHmmss.json`。未確定の行でも止めない（PC が要確認で受ける）。
  書いたら出した行だけ出力済みにし、結果ダイアログで 確定／摘要なし／科目なし の件数・出せなかった行・警告を見せる
- それまであおいろモードの購買出力は**黙ってらくらく CSV を出していた**（else 分岐に落ちていた）。これで解消
- 預金・レシートの出力は手を付けていない（あおいろモードでは今もらくらく CSV に落ちる）
- 全ユニットテスト 69 件パス・`assembleDebug` 成功

### 2026-09-25 追記：複数通帳（最大5冊・DB v39）

ユーザー判断：預金口座は複数ある（あおいろの 営農口座＝スロット1・直売口座＝スロット2）ので、最大 5 冊に対応する。

- **DB v38 → v39**：`passbooks`（名前・並び・弥生の補助科目・あおいろ口座の accountKey＋控えた名前）を新設し 1 冊目「通帳1」を作成。
  `deposit_meisai.passbookId`（NOT NULL DEFAULT 1）を追加し、既存明細は全部 1 冊目。
  UNIQUE を (日付, 通番) → (**通帳**, 日付, 通番) に張り替え（張り替えないと 2 冊目の CSV の重なる行が IGNORE で黙って落ちる）
- 弥生とあおいろの口座は別々に持つ：弥生は「普通預金」＋補助科目（文字で持つ。補助科目は弥生側で作るもので
  スマホの科目マスタに無いことがある）、あおいろは預金スロット科目から選ぶ（同じ口座を 2 冊に割り当てられない）
- あおいろ口座の名前が PC で変わったら外す：`AoiroChoboLinkDao` の科目キー横断（取得・外す・名前の補完）に passbooks を追加
- 通帳データ画面：右上「通帳の管理」（追加・名前・補助科目・あおいろ口座・この通帳の明細を削除・明細 0 件の通帳の削除）。
  2 冊以上で通帳の切替チップ（＋すべて）。CSV 取込は 2 冊以上なら取込先を選ばせる（前回の通帳を選んだ状態）。
  CSV に口座番号が無いので自動判別はできない
- 合成番号（`DepositNumberAssigner`）は通帳ごとに振り、別の通帳の行は再利用しない（テスト 1 件追加・8 件）
- 預金の出力確認：2 冊以上で通帳の絞り込み。弥生 CSV は預金側の補助科目に通帳の補助科目を入れる。
  補助科目が空の通帳が 2 冊以上あると警告（弥生で同じ普通預金に混ざる）
- バックアップ：全データ・通帳データのエクスポートに passbooks を追加。v39 より前のバックアップの明細は 1 冊目へ。
  明細が指す通帳が無ければ「通帳N」を作る。全データ削除では通帳も消す（次に開いた画面が 1 冊目を作り直す）
- 学習（通帳パターン→科目）は通帳をまたいで共通のまま
- **PC への依頼** `docs/integration/REPLY-phone-2026-09-25c.md`：Deposit の externalId を
  `ocr:deposit:p{通帳ID}-{日付}-{通番}` に（通帳 ID はスロット番号ではない。口座を選び直しても ID が変わらないように）。
  質問：口座間の振替が両通帳に載って二重に届く件を「重複の可能性」で受けられるか
- 検証：`assembleDebug` 成功・ユニットテスト 70 件パス。KSP 生成の期待スキーマとマイグレーションの SQL が一致。
  v37 実機バックアップのコピーにマイグレーション SQL を流し、157 件が通帳1・旧インデックス削除・
  別通帳なら同じ日付/通番が入り同じ通帳では弾かれる・integrity ok を確認。**実機未確認（端末未接続）**
- 見つけた既存バグ：「通帳再読込」ボタンが確認なしで全明細を消す → **ユーザー了承のうえ削除**（ボタン・明細 0 件時の asset 読み込み・`importMeisaiFromCsv`）。
  asset `meisai.csv` はもう無かったので、どちらも「消すだけ」の処理になっていた
- `CLAUDE.md` の DB バージョン表記を v39 に更新（ユーザー確認済み）
- PC への返信 25c：ユーザー了承済み。PC 側は repo の `docs/integration/` を読む運用なのでファイルはコミット済み（PC の Claude セッションはこの端末で起動していなかったので直接は送れていない）

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- 前作業の `CURRENT_TASK.md` を `.steering/20260925-PC会計アプリ連携・あおいろ科目摘要取込/` にアーカイブ
- transactions.json ビルダー（JA購買）・契約テスト 12 件・出力確認画面の JSON 出力
- 複数通帳（最大 5 冊・DB v39）と PC への契約変更依頼（`REPLY-phone-2026-09-25c.md`）

### 未完了・中断した理由
- 実機確認（JSON 出力・v38 → v39 マイグレーション・通帳の管理）：端末が未接続だった
- PC への返信 25c の回答待ち（回答を受けてから Deposit の transactions.json に進む）

### 次回セッションで最初にやること
実機で v38 → v39 を通す（事前に receipt_database + -wal + -shm をセットでバックアップ）：user_version 39・通帳1 に 157 件・通帳を追加して別 CSV を取込先指定で入れる。続けてあおいろモードで購買 JSON を書き出して adb pull で中身を確認し、終わったら弥生モードに戻す。

### 新たに発覚した問題・制約
- `java.time` が minSdk 24 で使えない件（`docs/known-issues.md` 転記済み）
- あおいろモードの預金・レシート出力は今もらくらく CSV に落ちる（あおいろ対応が未着手のため。既知）
- 「通帳再読込」ボタンが確認なしで全通帳の明細を消していた（同日修正・`docs/known-issues.md` に記録）
