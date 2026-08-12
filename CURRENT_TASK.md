# CURRENT_TASK.md

## 作業タイトル
一般レシートの連続撮影対応・¥/7誤認識対策・国税庁インボイス照会機能の削除

## 目的・背景
一般レシートOCR（Gemini画像直接送信）運用開始後の実機継続確認で3点の改善要望が出た。
(1) 手書き金額で「¥」記号と数字「7」を誤認識するケースが多い、
(2) レシートを何枚も連続で撮影したいが毎回一覧画面に戻る導線が邪魔、
(3) 設定ページの項目を見直したい（特に国税庁インボイス照会は不要と判断）。

## 今回のタスク
- [x] Geminiプロンプトに「手書き金額は先頭に¥が付く」というルールを明記し¥/7誤認識を軽減
- [x] 連続撮影フロー：保存後に一覧画面ではなく撮影画面へループバックする導線に変更
- [x] 撮影画面に「本日◯件」カウンター表示・一覧画面への導線ボタンを追加
- [x] カウンターが0件のまま増えないバグを修正（購入日ではなく撮影日時で判定）
- [x] JA伝票と同じ「フラッシュ」設定（起動時自動点灯）を一般レシート撮影画面にも適用
- [x] 国税庁インボイス照会機能（API連携部分）を完全削除

## 完了条件
- 手書き領収書で¥/7誤認識が改善する（実機確認：完全解消はしないが試行してユーザーが妥当と判断）
- レシートを連続撮影した際、保存のたびに撮影画面に戻りカウンターが増える（実機確認済み）
- 設定でフラッシュONにすると一般レシート撮影画面でも自動点灯する（実機確認済み）
- 設定画面から国税庁関連の項目が消えている（実機確認済み）

## 進捗メモ
- `GeminiReceiptClient.kt`の`buildImagePrompt()`に「手書き金額は先頭に¥、7ではない」という
  決め打ちルールを追加。「慎重に判断してください」という曖昧な指示では改善せず、断定的な
  ルールに書き換えることで対応（それでも完全には解消しないとユーザー実機確認、限界として許容）
- 連続撮影：`GeneralReceiptConfirmScreen`の保存ボタンを「保存して次を撮影」に変更、保存完了時に
  `onSaved()`で撮影画面へ`popUpTo(GeneralPurchaseMenu.route)`しつつ遷移（backstackが積み上がらない
  よう既存のcapture→confirmの`popUpTo`パターンを踏襲）
- 撮影画面カウンターの初回実装は`receipt.date`（領収書の購入日＝OCR結果）で「今日」を判定しており、
  過去の日付の領収書を今日撮影するケースが大半のため常に0件になるバグがあった。実機のDBを
  `adb exec-out run-as ... cat`で直接pullして確認し原因特定（`adb shell run-as ... cat`だと
  バイナリが壊れる問題があり`exec-out`に切り替えて解決）。`createdAt`（保存時刻）で判定するよう修正
- フラッシュ自動点灯：JA伝票`CameraScreen.kt`と同じ`appPreferences.cameraFlash`を共有し、
  カメラ起動時に`ON`なら`enableTorch(true)`
- 国税庁インボイス照会機能：ユーザーに削除範囲を確認し「API連携のみ完全削除」を選択
  - `NtaInvoiceClient.kt`削除、`AppPreferences.ntaApplicationId`削除、設定画面の入力欄削除
  - `GeneralReceiptViewModel`の`resolveStoreName()`からNTA APIフォールバックを削除（ローカル
    キャッシュのみに変更）、`refreshStore()`・`storeRefreshState`削除
  - `InvoiceStoreListScreen.kt`の「更新（NTA再照会）」ボタンを削除
  - **登録番号→店舗名のローカルキャッシュ機能自体は維持**（過去に保存・編集した店舗名を
    次回以降自動で呼び出す仕組み。`InvoiceStoreListScreen`本体・`lookupStoreByRegistrationNumber`
    ボタンはローカルキャッシュ限定で残っている）
  - 旧バージョンで平文prefs/秘匿ファイルに残っていた`nta_application_id`の値は、次回起動時の
    `migrateSecretsToSecurePrefs()`内の追加クリーンアップ処理で自動削除される

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- 上記6項目すべて実装・実機確認済み（カウンター増加・フラッシュ自動点灯・国税庁項目削除の
  3点はユーザー本人が実機で確認済み）

### 未完了・中断した理由
なし。

### 次回セッションで最初にやること
未コミットの変更（AppPreferences.kt / Navigation.kt / GeneralReceiptCaptureScreen.kt /
GeneralReceiptConfirmScreen.kt / InvoiceStoreListScreen.kt / SettingsScreen.kt /
GeminiReceiptClient.kt / GeneralReceiptViewModel.kt 変更・NtaInvoiceClient.kt削除）をコミットする

### 新たに発覚した問題・制約
- 手書き金額の¥/7誤認識はプロンプト調整だけでは完全には解消しない（Gemini側のvision精度の限界）。
  今後も誤認識が目立つ場合は、確認画面での金額目視チェックを促す運用でカバーする方針
