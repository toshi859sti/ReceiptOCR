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
- [ ] ③ 実機確認：弥生・あおいろの出力が撤去前と同じ／簿記ソフト連携メニューにらくらくが無い／古いバックアップの取込が通る
- [ ] ④ **前に全データのバックアップを勧める**。DB v40 で `rakuraku_accounts`・`rakuraku_tekiyou` を落とし、
      学習ルール・明細・商品のらくらく列（`rakurakuTekiyouId`・`overrideTekiyouId`・`kaikakeTekiyouId` など）を外す。
      あわせて DAO のらくらく用クエリ（`DepositMeisaiDao` の `clearOverridesForRule`・`updateOverrideTekiyou`・
      JOIN の `rakuraku_tekiyou`、`TekiyouMatchingRuleDao` の結合列）と `SettingsScreen` の `withoutRakuraku()` を消す
- [ ] ④ enum `BLUE_RETURN_PREP` の改名（保存値の移行つき）。`RAKURAKU` は ③ で削除済み
- [ ] docs（`APP_SPECIFICATION.md`・`functional-design.md`・`architecture.md`・`MANUAL.md`・`PC_ACCOUNTING_INTEGRATION_SPEC.md`）と `CLAUDE.md`（撤去済みの記載・DB バージョン。更新はユーザー確認）

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

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- ③ コード・画面の撤去（2026-09-29・ビルド成功）

### 未完了・中断した理由
- ③ の実機確認と ④（DB v40）が未着手

### 次回セッションで最初にやること
③ を実機で確認（弥生・あおいろ出力、メニュー、古いバックアップ取込）してから、全データのバックアップを勧めて ④ に着手する。

### 新たに発覚した問題・制約
- なし
