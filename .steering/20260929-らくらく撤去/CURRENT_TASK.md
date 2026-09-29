# CURRENT_TASK.md

## 作業タイトル
らくらく青色申告農業版の撤去（③ コード・画面 → ④ DB v40）

## 目的・背景
らくらく青色申告農業版のサポート終了（2026-09-23 決定・`docs/integration/REPLY-phone-2026-09-23.md` §5）。
出力先は 弥生 CSV と あおいろ帳簿 `transactions.json` の 2 本にする。

撤去は 4 段階（ユーザー了承・2026-09-26）。①② は完了済みで、記録は
`.steering/20260926-あおいろtransactions.json（購買・預金・レシート）/CURRENT_TASK.md`：
- ① 既定値を弥生に・設定の選択肢から外す（**済**。保存値 `RAKURAKU` は読み出し時に `YAYOI` へ移す）
- ② 預金・レシートのあおいろ JSON（**済**。あおいろモードの全部門が JSON を出し、らくらく CSV の分岐はどこからも通らない）

## 今回のタスク
- [x] ③ らくらく CSV の出力（購買・預金・レシート）と、`AccountingSoftware.RAKURAKU` を見ている分岐を削除
- [x] ③ らくらくの画面（`RakurakuAccountSettingsScreen`・`RakurakuTekiyouScreen`・通帳摘要別リストのらくらく部分・商品編集の買掛摘要）と導線を削除
- [x] ③ `assets/rakurakutekiyou.csv` と摘要の差分取込（`importTekiyouFromCsv`・`TekiyouDictImporter`）を削除
- [x] ③ バックアップの書き出し・取込かららくらくの表を外す（古いバックアップのらくらく部分は読み飛ばす）
- [x] ③ 実機確認：弥生・あおいろの出力が撤去前と同じ／簿記ソフト連携メニューにらくらくが無い／古いバックアップの取込が通る
- [x] ④ **前に全データのバックアップを勧める**。DB v40 で `rakuraku_accounts`・`rakuraku_tekiyou` を落とし、
      学習ルール・明細・商品のらくらく列（`rakurakuTekiyouId`・`overrideTekiyouId`・`kaikakeTekiyouId` など）を外す。
      あわせて DAO のらくらく用クエリ（`DepositMeisaiDao` の `clearOverridesForRule`・`updateOverrideTekiyou`・
      JOIN の `rakuraku_tekiyou`、`TekiyouMatchingRuleDao` の結合列）と `SettingsScreen` の `withoutRakuraku()` を消す
- [x] ④ enum `BLUE_RETURN_PREP` の改名（保存値の移行つき）。`RAKURAKU` は ③ で削除済み
- [x] docs（`APP_SPECIFICATION.md`・`functional-design.md`・`architecture.md`・`MANUAL.md`・`PC_ACCOUNTING_INTEGRATION_SPEC.md` ほか）
- [x] `CLAUDE.md`（撤去済みの記載・DB バージョン v40。更新はユーザー確認）

## 完了条件
コードに `Rakuraku` / `RAKURAKU` / らくらく の参照が残らず（移行・読み飛ばしのための記述を除く）、DB v40 への移行が実機で通り、
弥生・あおいろの出力が撤去前と同じ結果になる。

## 進捗メモ
- 2026-09-26 時点の参照：約 25 ファイル・約 300 か所（多いのは `ReceiptDatabase.kt`・`RakurakuAccountDao.kt`・`SettingsScreen.kt`・
  `TekiyouMatchingScreen.kt`）。着手時に `grep -rniE "らくらく|rakuraku" app/src/main` で取り直すこと
- エンティティの列を外すときは、そのエンティティを列挙で組み直している箇所を必ず洗う（`CLAUDE.md`）
- ④ はスキーマ変更。マイグレーションを書き忘れると起動時クラッシュ。実機は DB 3 ファイル（本体・wal・shm）をセットでバックアップ
- 2026-09-29 ③ 完了（ビルド成功・実機未確認）。決定・変更点：
  - enum `RAKURAKU` は計画を前倒しして ③ で削除（残すと exhaustive な when にダミー分岐が要るため）。
    `AppPreferences.accountingSoftware` は知らない保存値を弥生に移して書き戻す
  - バックアップ取込は古いファイルのらくらく表を Gson が無視する。商品の `kaikakeTekiyouId`・ルールの `rakurakuTekiyouId` は
    外部キー違反になるので取込時に null にする（`withoutRakuraku()`、④ で列ごと消す）
  - `AccountHierarchyComponents.kt` → `YayoiCategoryOrder.kt`（らくらく科目画面専用だった階層表示を削除）
  - `assets/product_master.csv` の空の `rakuraku_account_id` 列を削除。旧コードは 5 列目（実は yayoi_account_id・全行空）を
    `kaikakeTekiyouId` に入れていたが、その読み込みも削除
- 2026-09-29 ③ 実機確認済み（ユーザー）。④ 完了・実機で移行確認済み：
  - 移行前バックアップ：`C:\Users\toshiro\GreenFrameOCR-backups\20260929-before-v40\`（DB 3 ファイル・v39・integrity ok）
  - 移行前に実データを数えた結果、らくらく摘要を指す学習は 0 件（商品・通帳ルール・明細の個別指定とも）。
    v36 のコメントにある `linkMemoKey`（らくらく→あおいろの引き継ぎ）は実装されていなかった
  - `MIGRATION_39_40`：`product_master`・`tekiyou_matching_rules`・`deposit_meisai` を残す列だけで作り直し、
    らくらくの 2 表を DROP。実機で v40・integrity ok・foreign_key_check 空・件数（商品 115・ルール 39・明細 157）一致
  - enum は `AOIRO` に改名。保存値 `BLUE_RETURN_PREP` は読み出し時に `AOIRO` に移す（実機で確認し、設定は弥生に戻した）
- 2026-09-29 docs 更新：今の仕様を書いている 12 ファイル（APP_SPECIFICATION・functional-design・architecture・MANUAL・
  PC_ACCOUNTING_INTEGRATION_SPEC・CSV_SPEC・DATABASE_SCHEMA・DICTIONARY・product-requirements・glossary・
  repository-structure・known-issues）。履歴（`CHANGELOG.md`・`integration/REPLY-*`・`TASK_*`）と PC から同期している契約
  （`integration/README.md` など）は書き換えていない
  - `CSV_SPEC.md` の購買・預金 CSV はらくらく形式だったので削除。商品マスタ／学習データの共有 CSV はコードに無いので「未実装」と明記
  - `DATABASE_SCHEMA.md` は v11 時代のまま全体が古いので、本文は触らず冒頭に注記だけ入れた
  - `PC_ACCOUNTING_INTEGRATION_SPEC.md` も v33 時点の文書。らくらく部分を外し、冒頭に「連携の一次情報は `docs/integration/`」と注記
  - MANUAL の「あおいろは購買のみ」「預金はあおいろでもらくらく CSV が出る」も古かったので直した

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- ③ コード・画面の撤去（2026-09-29・実機確認済み）
- ④ DB v40・enum 改名（2026-09-29・実機で移行確認済み）
- docs の更新（2026-09-29）
- CLAUDE.md の更新（ユーザー確認済み・2026-09-29）

### 未完了・中断した理由
- なし（完了。`.steering/20260929-らくらく撤去/` にアーカイブ）

### 次回セッションで最初にやること
新しい作業を決める（候補：`DATABASE_SCHEMA.md`・`PC_ACCOUNTING_INTEGRATION_SPEC.md` を書き直すか廃止するか）。

### 新たに発覚した問題・制約
- `DATABASE_SCHEMA.md`（v11 時代）と `PC_ACCOUNTING_INTEGRATION_SPEC.md`（v33 時点）は、らくらく以外の部分も今の実装と食い違う。
  書き直すか廃止するかは未決定
