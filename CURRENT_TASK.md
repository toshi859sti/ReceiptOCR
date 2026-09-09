# CURRENT_TASK.md

## 作業タイトル
PC会計アプリ連携仕様書の作成

## 目的・背景
Androidアプリ「JA仕訳変換」の出力データを取り込み、マッチング・仕訳JSON出力を行う
PC会計アプリを同時進行で開発中。PC側のClaude Code / 開発者がAndroid側のデータ仕様
（摘要辞書・勘定科目・取引データのJSON、マッチングロジック、仕訳の借方/貸方の決め方）
を正確に理解できる一次情報が必要。

## 今回のタスク
- [x] Room DB（v33）全エンティティのスキーマ棚卸し
- [x] JSONバックアップ（AllExportData等）の形式調査
- [x] CSV出力（らくらく/弥生・購買/預金/レシート）の仕訳ロジック調査
- [x] マッチングロジック調査（canonicalKey / normalizeTekiyou / 支払方法ルール）
- [x] `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md` として文書化
- [ ] （必要なら）PC側と取引JSONスキーマ §10 のすり合わせ

## 完了条件
PC側のClaude Codeがこの1ファイルを読めば、Android出力の全データ構造・
マッチング仕様・仕訳生成規則を再現できる状態。

## 進捗メモ
- 新規ファイル: `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md`（12章構成）
- 調査中に判明した既存実装の不整合（仕様書 §11 に記載）:
  - 弥生CSVの列並びが購買/預金とレシートで違う（購買/預金は先頭 "2000" 欠落）
  - 預金の個別上書き overrideTekiyouId / overrideYayoiAccountId がCSV出力に未反映
  - レシート loadOutputItems() が isExcluded をフィルタしていない
  - YayoiAccount.defaultTaxCategory のDB値と弥生が受け付ける税区分文字列が不一致の可能性
- `docs/DATABASE_SCHEMA.md` はv11時点で大幅に古い（実際v33）
- `docs/yayoi-csv-export-spec.md` は別プロジェクト "AoiroChobo"(C#) 由来の参考資料

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- `docs/PC_ACCOUNTING_INTEGRATION_SPEC.md` を新規作成（PC会計アプリ向け連携仕様）

### 未完了・中断した理由
- PC側との取引JSONスキーマ（仕様書 §10）のすり合わせは未実施（PC側の要件待ち）

### 次回セッションで最初にやること
PC側の要望を聞き、仕様書 §10 の取引データJSONスキーマを確定させる

### 新たに発覚した問題・制約
上記「進捗メモ」の既存実装の不整合4点。docs/known-issues.md への転記を検討（未転記）。
