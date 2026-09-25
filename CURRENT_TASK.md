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

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- 前作業の `CURRENT_TASK.md` を `.steering/20260925-PC会計アプリ連携・あおいろ科目摘要取込/` にアーカイブ
- transactions.json ビルダー（JA購買）・契約テスト 12 件・出力確認画面の JSON 出力

### 未完了・中断した理由
- 実機確認：端末が未接続だった

### 次回セッションで最初にやること
実機であおいろモードにして購買の出力確認画面から JSON を書き出し、adb pull して中身（件数・matchStatus・externalId が receipt_items.uuid と一致）を確かめ、PC 側で取り込んでもらう。終わったら弥生モードに戻す。

### 新たに発覚した問題・制約
- `java.time` が minSdk 24 で使えない件（`docs/known-issues.md` 転記済み）
- あおいろモードの預金・レシート出力は今もらくらく CSV に落ちる（あおいろ対応が未着手のため。既知）
