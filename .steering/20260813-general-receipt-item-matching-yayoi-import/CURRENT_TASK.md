# CURRENT_TASK.md

## 作業タイトル
一般レシートの連続撮影・品目別マッチング正規化グルーピング・弥生勘定科目インポート機能

## 目的・背景
一般レシートOCR運用開始後の実機継続確認セッション。¥/7誤認識対策、連続撮影のUX改善、
一覧表への商品名プレビュー追加に続き、「品目別マッチング」をJA伝票・預金摘要集約リストと
同じ「正規化グルーピング＋グループのデフォルト＋個別上書き」方式に揃える大きめの改修を実施。
その過程で発覚した弥生勘定科目一覧の表示バグも修正し、CSVインポート機能も追加した。

## 今回のタスク
- [x] Geminiプロンプトに「手書き金額は¥から始まる」ルールを追加（¥/7誤認識対策、完全解消はしない）
- [x] 一般レシートの連続撮影フロー（保存後に撮影画面へループバック、本日件数表示）
- [x] 撮影日時（createdAt）で本日件数を判定するよう修正（購入日で判定していたバグ）
- [x] 一般レシート撮影画面にJA伝票と同じ「フラッシュ」自動点灯設定を適用
- [x] 国税庁インボイス照会機能（API連携部分）を完全削除、ローカルキャッシュは維持
- [x] レシート一覧カードに商品名プレビュー（最大2行）・経費対象件数を追加
- [x] 品目別マッチングを一覧画面のタブから独立メニュー画面に分離（JA伝票の購買品目別リストと同型）
- [x] 品目別マッチングを正規化キー（canonicalKey）グルーピングに変更、DBマイグレーション v29→v30
- [x] グループのデフォルト科目＋個別上書き方式に変更（預金摘要集約リストと同じ操作方式）
- [x] AI一括提案ボタンを常時表示に変更（既マッチ済みグループも再提案対象に含める）
- [x] 弥生勘定科目一覧が常に0件表示になるバグを修正（categoryAの【】有無のミスマッチ）
- [x] 弥生勘定科目のCSVインポート機能を追加（勘定科目設定画面、accountCode一致でマージ更新）
- [x] 品目別マッチングの表示調整：金額表示削除、マッチ済み=緑系/個別上書き=赤系に固定色

## 完了条件
- 上記すべて実機確認済み（本項目は全てユーザー自身が実機テストして確認済み）

## 進捗メモ
- **DBマイグレーション v29→v30**：`general_receipt_items`に`canonicalKey`列追加＋バックフィル、
  新規`general_item_master`テーブル（canonicalKey→デフォルト科目）を追加し、既存の個別科目設定
  から多数決でグループデフォルトを自動登録。実機の既存データに対して動作確認済み（クラッシュなし、
  `adb exec-out run-as ... cat`でDB直接pullして中身を検証）
- `GeneralReceiptItem.yayoiAccountId`の意味を「個別上書き」に変更（null=グループのデフォルトに従う）。
  `updateAccountForItemName`（完全一致文字列の一括更新）を廃止し、
  `updateGroupDefaultAccount`（グループデフォルト変更・保存時に個別上書き全解除）と
  `updateItemOverride`（1件だけ上書き）に分離
- 弥生勘定科目バグ：`YayoiAccountSettingsScreen.kt`の`TAISYAKU_CATS`/`SONEKI_CATS`が
  括弧なし文字列（"資産"等）で定義されていたが、実データの`categoryA`は"【流動資産】"のように
  必ず【】付きだったため一件も一致せず、常に0件表示になっていた。実機DBを直接クエリして原因特定。
  括弧付きの実データ値そのものを列挙する形に修正
- 弥生勘定科目CSVインポート：`PassbookDataScreen.kt`の`GetContent`ファイルピッカーパターンを流用。
  CSV書式は`DatabaseInitializer.importYayoiAccounts()`の初期投入と同じにして、
  accountCode一致なら更新・なければ新規追加のマージ方式（全削除＋作り直しにはしていない）
- 品目別マッチングの色分けは3回イテレーションした：primary(緑)→青系→最終的に
  「グループマッチ=緑系(#2E7D32)固定・個別上書き=赤系(#C62828)固定」に決着。
  テーマプリセット（グリーン/パープル/ブルー/テラ/グレー）でprimary/tertiaryの色味が変わるため、
  区別のため固定色にした（TekiyouMatchingScreenの入金/出金色分けと同じ考え方）

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- 上記タスクすべて完了・実機確認済み。ユーザーから「OKです」と明示的な完了確認あり

### 未完了・中断した理由
なし。

### 次回セッションで最初にやること
未コミットの変更（GeneralItemMaster.kt/GeneralItemMasterDao.kt/GeneralItemMatchingScreen.kt新規、
GeneralReceiptDao.kt/GeneralReceiptItem.kt/ReceiptDatabase.kt/Navigation.kt/
GeneralPurchaseMenuScreen.kt/GeneralReceiptListScreen.kt/YayoiAccountSettingsScreen.kt/
ProductNameInputUtils.kt/GeneralReceiptViewModel.kt変更）をコミットする

### 新たに発覚した問題・制約
- 手書き金額の¥/7誤認識はプロンプト調整だけでは完全には解消しない（Gemini側のvision精度の限界、
  確認画面での目視チェックでカバーする方針）
- 弥生勘定科目一覧のcategoryAフィルタバグは今回修正済みだが、同種の「実データの表記ゆれと
  ハードコードされたフィルタ文字列の不一致」が他画面にも潜んでいる可能性がある
  （今回は横断的には調査していない）
