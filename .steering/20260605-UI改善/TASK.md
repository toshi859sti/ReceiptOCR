# CURRENT_TASK.md

## 作業タイトル
購買品リスト・通帳摘要別リスト UI改善

## 目的・背景
購買品リストの削除ボタン誤操作対策、弥生モードの絞り込みラベル修正、AIボタンのテキスト化、
トークン使用量の可視化、通帳摘要別リストへのAI提案追加、および展開時の渦巻きバグ修正。

## 今回のタスク
- [x] 購買品リスト：削除ボタンをアイテム行から除去し、編集ダイアログに移動
- [x] 購買品リスト：弥生モードの「絞込」チップを「摘要未設定」→「科目未設定」に
- [x] 購買品リスト：TopAppBarのAIボタンをアイコン→テキスト「AI科目提案」に
- [x] GeminiReceiptClient：トークン使用量データクラス（AiUsageStats）追加・レスポンスから取得
- [x] AiMatchingDialog：トークン使用量をフッターに表示
- [x] 通帳摘要別リスト：弥生モードでAI科目提案ボタン追加・AiTekiyouMatchingDialog実装
- [x] 通帳摘要別リスト：展開時の渦巻きが消えないバグ修正（meisaiItemsのRAKURAKU条件を除去）
- [x] 通帳摘要別リスト：弥生モードで個別編集ダイアログ（IndividualYayoiOverrideDialog）追加（DB v22）

## 完了条件
- [x] BUILD SUCCESSFUL（2026-06-05）

## 進捗メモ
### 渦巻きバグの原因
`meisaiItems = if (accountingSoftware == AccountingSoftware.RAKURAKU) meisaiByRuleId[rule.id] else null`
弥生モードでは常に `null` → CircularProgressIndicator が表示され続けた。
→ `meisaiItems = meisaiByRuleId[rule.id]` に変更（全モードで一覧表示・個別編集はRAKURAKUのみ）

### 削除ボタン移動
- 行の右端にあった赤いゴミ箱アイコンを削除
- 編集ダイアログの dismissButton 左端に「削除」TextButton（赤）を追加（新規追加時は非表示）

### AIボタン変更
- `IconButton` + `Icons.Default.AutoAwesome` → `TextButton` + "AI科目提案" テキスト
- 処理中は "提案中..." と CircularProgressIndicator を表示

### トークン使用量
- `AiUsageStats(promptTokens, candidatesTokens, totalTokens)` を Gemini レスポンスの `usageMetadata` から取得
- AiMatchingDialog / AiTekiyouMatchingDialog の confirmButton 上部に表示
  （例：「入力: 1234 / 出力: 56 / 合計: 1290トークン」）

---

## 作業終了時の記録（2026-06-05）

### 今回完了したこと
上記タスク全件

### 未完了・中断した理由
なし

### 次回セッションで最初にやること
実機で動作確認：
1. 購買品リスト：アイテム行に削除ボタンがないこと
2. 購買品リスト：編集ダイアログに赤い「削除」ボタンがあること
3. 弥生モードで絞り込みチップが「科目未設定」になること
4. TopAppBarのAIボタンが「AI科目提案」テキストになること
5. AI提案後にトークン数が表示されること
6. 通帳摘要別リストでカードタップ→渦巻きが消えて明細一覧が表示されること
7. 通帳摘要別リスト（弥生モード）：明細行タップで IndividualYayoiOverrideDialog が開くこと
8. 通帳摘要別リスト（弥生モード）に「AI科目提案」ボタンが表示されること

### 新たに発覚した問題・制約
なし
