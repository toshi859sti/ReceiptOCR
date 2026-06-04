# CURRENT_TASK.md

## 作業タイトル
連携会計ソフト設定 ＋ 弥生モード対応（購買・通帳マッチング）＋ AI科目提案

## 目的・背景
弥生の青色申告・らくらく青色申告農業版・BlueReturnPrep の3択を設定で切り替え、
購買品目別リストと通帳摘要別リストのマッチング対象科目をソフトに応じて切り替える。
さらに弥生モードで Gemini AI による未マッチング品目への科目提案機能を追加。

## 今回のタスク
- [x] AppPreferences に AccountingSoftware enum（RAKURAKU/YAYOI/BLUE_RETURN_PREP）追加
- [x] SettingsScreen に「連携会計ソフト」ラジオボタンセクション追加
- [x] DB v21 マイグレーション（product_master・tekiyou_matching_rules に yayoiAccountId 追加）
- [x] ProductListScreen — 弥生モード対応（科目ラベル・ピッカー・フィルター）
- [x] TekiyouMatchingScreen — 弥生モード対応（科目ピッカー・BRP非表示）
- [x] フラグ優先フォールバック実装（usedForPurchase/usedForDeposit フラグ → 全科目フォールバック）
- [x] GeminiReceiptClient に matchProductsToAccounts() 追加
- [x] AiMatchingDialog 実装（提案一覧・個別承認・一括保存）

## 完了条件
- [x] BUILD SUCCESSFUL
- [x] 実機インストール確認（2026-06-05）

## 進捗メモ
- DB v20 → v21：ALTER TABLE 2本（product_master・tekiyou_matching_rules に yayoiAccountId INTEGER）
- MatchingRuleWithTekiyou に yayoiAccountId・yayoiAccountName・yayoiAccountCode フィールド追加
- TekiyouMatchingRuleDao.getAllWithTekiyou() で yayoi_accounts を LEFT JOIN
- AI提案は品目60件・科目100件上限（APIトークン節約）
- フラグ未設定でもカテゴリフィルターにフォールバックするので即使える
- BRP モードでは編集ボタン・個別オーバーライドを非表示

---

## 作業終了時の記録（2026-06-05）

### 今回完了したこと
- 連携会計ソフト設定（3択ラジオ）
- 購買品目別リスト：弥生モード（科目ラベル・ピッカー・フラグ優先フォールバック・AI提案）
- 通帳摘要別リスト：弥生モード（科目ピッカー・フラグ優先フォールバック）
- BRP モード：両画面でマッチング列非表示
- らくらく勘定科目の行削除ボタン撤去・編集ダイアログへ移動

### 未完了・中断した理由
なし

### 次回セッションで最初にやること
実機で以下を動作確認する：
1. 設定 → 連携会計ソフト切り替えが保存されるか
2. 弥生モードで購買品目別リストの科目ピッカーが「購買フラグのみ」で絞れるか
3. 通帳摘要別リストで全科目が選べるか（13件問題の解消）
4. AI提案ボタン（✨）→ Gemini 提案 → 承認保存

### 新たに発覚した問題・制約
- AI提案は Gemini APIキー必須（設定画面で入力）
- CLAUDE.md の DB バージョン記述を v21 に更新済み
