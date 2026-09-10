# 連携契約 CHANGELOG

`schemaVersion` は `vocabulary.json` と `transactions.json` で共通に上げる。
破壊的変更のときだけ整数を +1 する。フィールド追加（前方互換）は版を上げず、
本ファイルに「minor」として記録する。

---

## schemaVersion 1 — 2026-09-10（初版・ドラフト）

- `docs/integration/` 新設。契約一式を初出。
- 決定事項：
  - マッチングはスマホ担当（PC はクラウド AI を持たない）。
  - マスタ（勘定科目・摘要辞書）は AoiroChobo 所有。PC→スマホへ `vocabulary.json` を発行。
  - スマホ→PC の取引データは **JSON**（`transactions.json`）。CSV 案は取り下げ。
  - 科目参照は `Account.Code`（年度非依存）。日付は西暦 ISO。金額は正の整数・返品は Dr/Cr 入替。
  - `externalId` スキーム：`ocr:purchase:…` / `ocr:deposit:…` / `ocr:receipt:{uuid}:{index}`。
- **未確定 / スマホ側の残り仕様書待ち**：
  - PC→スマホの `vocabulary.json` 取込経路（スマホ側 UI・保存先）。
  - 預金スロットの割り当て方法（スマホ設定で固定 or 取込時に選択）の最終形。
  - `contentHash` の正規化仕様をスマホ側実装と突き合わせて確定。
  - ゴールデン例（`examples/`）を両側の実装で往復検証。
