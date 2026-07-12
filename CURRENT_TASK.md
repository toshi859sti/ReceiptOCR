# CURRENT_TASK.md

## 作業タイトル
レシート一覧 年フィルター追加・詳細表示をシングルタップに変更

## 目的・背景
レシート一覧の UX 改善。年が増えたときの絞り込みと、詳細表示の発見性向上。

## 今回のタスク
- [x] レシート一覧：年フィルター（FilterChip）を一覧上部に追加（2年以上のデータがある場合のみ表示）
- [x] レシート一覧：店舗フィルター（FilterChip）を年フィルター下に追加（現在の年フィルター内に2店舗以上ある場合のみ表示）
- [x] レシート一覧：詳細表示をダブルタップ→シングルタップに変更
- [x] ロングタップは削除確認のまま維持
- [x] 品目別マッチング Tab2：勘定科目のロード処理が TODO のまま → 実装
- [x] 品目別マッチング Tab2：「未マッチX件をAIで一括割り当て」ボタン追加（enabled=false → 有効化）
- [x] AI提案結果ダイアログ追加（チェックボックスで選択的承認・トークン使用量表示）
- [x] BUILD SUCCESSFUL（2026-06-08）

## 完了条件
- [x] BUILD SUCCESSFUL

---

## 作業終了時の記録（2026-06-09）

### 今回完了したこと
- `GeneralReceiptListScreen.kt` を全面改修：
  - Tab1：年フィルター（2年以上のデータがある場合のみ表示）
  - Tab1：店舗フィルター（現在の年フィルター内に2店舗以上ある場合のみ表示、年切替時に自動リセット）
  - Tab1：詳細表示をシングルタップに変更（ダブルタップ廃止）、ロングタップは削除確認のまま
  - Tab2：勘定科目の LaunchedEffect ロード処理を実装（TODO → 実装済み）
  - Tab2：「未マッチX件をAIで一括割り当て」ボタン有効化（AutoAwesome アイコン付き）
  - Tab2：AI提案結果ダイアログ追加（チェックボックスで選択的承認、トークン使用量表示）
- 実機インストール済み（2026-06-09）

### 未完了・中断した理由
なし

### 次回セッションで最初にやること
実機動作確認：
1. レシート一覧のシングルタップで詳細ダイアログが開くか
2. 年フィルター・店舗フィルターが正しく機能するか
3. Tab2 の「AIで一括割り当て」ボタンが動作するか（Gemini API キー設定済みの場合）

### 新たに発覚した問題・制約
- NtaInvoiceClient の API_BASE_URL とレスポンスの JSON フィールド名は国税庁公式ドキュメントで要確認

---

## 追加作業（2026-06-10）：全画面の年フィルター統一

### 今回完了したこと
- `GeneralReceiptListScreen.kt`：年フィルターデフォルトを「全て（null）」→「当年 or データ最新年」に変更
  - `remember(availableYears)` で当年データがあれば当年を、なければ最新年を自動選択
- `PassbookDataScreen.kt`：年フィルターを新規追加
  - `availableYears`（transactionDate の年一覧）、`selectedYear`（デフォルト=当年 or 最新年）、`displayedMeisai`（フィルター後リスト）を追加
  - 年選択ドロップダウン（ExposedDropdownMenuBox）＋件数表示を一覧上部に追加
  - `${selectedYear}年のデータがありません` 空状態メッセージ追加
- JA伝票：YearSummaryScreen → 月選択の構造で既に年別管理済み → 変更不要と確認
- BUILD SUCCESSFUL・実機インストール済み（2026-06-10）

### 次回セッションで最初にやること
実機動作確認（通帳データ・一般レシートの年フィルター）

---

## 追加作業（2026-06-11）：簿記ソフト連携ルート整理・AccountSettingsScreen削除

### 今回完了したこと
- 一般レシート年フィルター：データが1年分でも常に表示に変更（`availableYears.isNotEmpty()` 条件）
- 設定画面から `AccountSettingsScreen`（タブ統合版）へのルートを削除
- `AccountSettingsScreen.kt` を完全削除
- 共有コンポーネント（`buildHierarchy`・`CategoryASelector`・`FilteredAccountList`・`CategoryHeader`・階層データクラス）を `AccountHierarchyComponents.kt` に切り出し
- `Screen.AccountSettings` オブジェクトを Navigation.kt から削除
- BUILD SUCCESSFUL・実機インストール済み（2026-06-11）

### 現在の勘定科目設定ルート（整理後）
- 簿記ソフト連携 → 弥生 勘定科目 → `YayoiAccountSettingsScreen`
- 簿記ソフト連携 → らくらく 勘定科目 → `RakurakuAccountSettingsScreen`
- 簿記ソフト連携 → らくらく 摘要辞書 → `RakurakuTekiyouScreen`

### 未完了・中断した理由
なし

### 次回セッションで最初にやること
`docs/known-issues.md` の「AccountSettingsScreen が Navigation 未登録」記載を削除（解決済み扱い）

---

## 追加作業（2026-06-11〜12）：メニュー名称変更・全画面フォントサイズ制御

### 今回完了したこと

**メニュー名称変更**
- 購買部門 → JA購買伝票
- 預金部門 → JA預金
- 一般購買部門 → レシート・領収書
- 変更対象: MenuScreen / PurchaseMenuScreen / DepositMenuScreen / GeneralPurchaseMenuScreen / SettingsScreen

**フォントサイズ制御（A-/A+ ボタン）**
- `AppPreferences.listFontSize`（デフォルト 14f、範囲 10〜20）を新規追加
- 共有コンポーネント `FontSizeControl` を `UiComponents.kt` に追加
- 以下9画面に実装済み：
  - PassbookDataScreen
  - GeneralReceiptListScreen
  - TekiyouMatchingScreen
  - KaikakeTekiyouScreen
  - InvoiceStoreListScreen
  - YearSummaryScreen
  - RakurakuTekiyouScreen
  - ProductListScreen
  - OcrLearningStatusScreen
- Navigation.kt の各呼び出しに `appPreferences = appPreferences` を追加
- BUILD SUCCESSFUL・実機インストール済み（2026-06-12）

### 未完了・中断した理由
なし

### 次回セッションで最初にやること
実機動作確認：フォントサイズ変更が各画面で正しく反映されるか確認

### 新たに発覚した問題・制約
なし

---

## 追加作業（2026-06-13）：会計ソフト別CSV出力対応

### 今回完了したこと

**FontSizeControl 移動（TopAppBar → リスト上部）**
- 9画面（PassbookData / GeneralReceiptList / TekiyouMatching / KaikakeTekiyou / InvoiceStoreList / YearSummary / RakurakuTekiyou / ProductList / OcrLearningStatus）の FontSizeControl を TopAppBar actions から各リスト最上部の右寄せ Row に移動

**docs/known-issues.md 更新**
- AccountSettingsScreen Navigation 未登録の記載を削除（解決済み）

**GeneralReceiptOutputScreen 刷新**
- 旧: テキスト入力 + ボタン1個 → 新: 年フィルター・期間フィルター・チェックボックス一覧・全選択/全解除（JA購買・JA預金の出力確認画面と同等の操作性）
- 店舗名列を削除
- 弥生モード時: 仕訳CSV（Shift-JIS・25列・CRLF・和暦）
- らくらくモード時: シンプルCSV（UTF-8・日付/商品名/金額/勘定科目/科目コード）
- 出力形式バッジ（青=弥生 / 緑=らくらく）表示
- Navigation.kt を更新して `appPreferences` を渡すよう修正

**OutputConfirmScreen（JA購買・JA預金）弥生CSV対応**
- `PurchaseOutputItem` / `DepositOutputItem` に `yayoiSubAccountName` / `defaultTaxCategory` フィールド追加
- `loadPurchaseOutputItems` / `loadDepositOutputItems` に `accountingSoftware` パラメータを追加し、弥生モード時は `YayoiAccount` から親科目/補助科目/税区分を解決
- `exportPurchaseYayoiCsvToUri`: 25列・Shift-JIS・CRLF（借方=費用科目/貸方=買掛金）
- `exportDepositYayoiCsvToUri`: 25列・Shift-JIS・CRLF（入金: 借方=普通預金/貸方=科目、出金: 借方=科目/貸方=普通預金）
- `OutputFormatBadge` composable 追加（青=弥生・緑=らくらく）
- `toYayoiDate()`: 西暦 → 令和/平成変換（2019/5/1 以降が令和）
- BUILD SUCCESSFUL・実機インストール済み（2026-06-13）

### 未完了・中断した理由
なし

### 次回セッションで最初にやること
実機動作確認：
1. 会計ソフト設定を「弥生」に変更し、JA購買/JA預金/レシート・領収書の各出力で「弥生の青色申告」バッジが表示されCSVが正しく出力されるか
2. 弥生モード時に列ヘッダーが「科目/メモ」と表示されるか
3. A-/A+ ボタンで行のフォントサイズが変わるか
4. 会計ソフト設定を「らくらく」に戻してらくらくCSVが出力されるか
5. 出力したCSVを弥生青色申告にインポートできるか確認

### 新たに発覚した問題・制約
なし

---

## 追加作業（2026-06-13 後半）：出力確認画面の細部修正

### 今回完了したこと
- `OutputFormatBadge` を GeneralReceiptOutputScreen と同じ2要素スタイルに統一（ソフト名バッジ + フォーマット注記テキスト）
  - 弥生: 青バッジ「弥生の青色申告」＋「仕訳CSV（Shift-JIS・25列）」
  - らくらく: 緑バッジ「らくらく青色申告」＋「シンプルCSV（UTF-8）」
- JA購買・JA預金の出力確認画面にフォントサイズ制御（A-/A+）追加
  - バッジ右隣に配置、`appPreferences.listFontSize` と連動
  - `PurchaseGridRow` / `DepositGridRow` に `fontSize` パラメータ追加
- 列ヘッダー「摘要/メモ」を弥生モード時は「科目/メモ」に切り替え
  - `PurchaseGridHeader` / `DepositGridHeader` に `accountingSoftware` パラメータ追加
- BUILD SUCCESSFUL・実機インストール済み（2026-06-13）

### 未完了・中断した理由
なし

### 次回セッションで最初にやること
（上記の「実機動作確認」を実施する）

### 新たに発覚した問題・制約
なし

---

## 追加作業（記録漏れの棚卸し・2026-07-12）

前回セッションまでの作業ツリーに、CURRENT_TASK.md 未記載のまま実装済みの変更が複数あったため、
diff を精査して以下に追記する。**いずれも未コミット**（実機確認・BUILD確認の記録なし）。

### 今回判明した未記録の実装

**① インボイス登録番号 自動照会機能（新規）**
- `InvoiceStore` エンティティ・`InvoiceStoreDao` 新規追加（登録番号をキーにしたローカルキャッシュ）
- `NtaInvoiceClient`（新規）：国税庁 適格請求書発行事業者公表システム Web-API を叩いて登録番号→事業者名・住所を取得
  - OCRテキストから `T\d{13}` 正規表現で登録番号を抽出
  - 照会順序：ローカルキャッシュ → NTA API
- `GeneralReceiptViewModel`：`onOcrCompleted()` で登録番号を抽出し、Geminiが店舗名を取れなかった場合に自動照会
- `GeneralReceiptConfirmScreen`：登録番号表示＋「店舗名を検索」手動ボタン追加
- `InvoiceStoreListScreen`（新規画面）：登録番号・法人名の一覧・編集・削除・NTA再照会・「レシートから取込」（既存レシートを遡って backfill）
  - `GeneralPurchaseMenuScreen` から遷移（`onNavigateToStoreList`）
- `SettingsScreen`：「国税庁インボイス照会 アプリケーションID」入力欄を追加（レシート・領収書セクション）
- DB migration 23→24：`general_receipts.registrationNumber` 列 / `invoice_stores` テーブル新設

**② 一般レシート品目：弥生科目の個別割当・除外フラグ（新規）**
- `GeneralReceiptItem` に `yayoiAccountId`（品目→弥生科目）・`isExcluded`（集計除外）追加
- `GeneralReceiptDao.getItemGroups()`：品目名でグルーピングして件数・合計・科目IDを集計
- `GeneralReceiptListScreen`：品目ごとに除外チェックボックス・科目選択UIを追加
- `GeneralReceiptOutputScreen` の CSV出力で `isExcluded` の品目を除外、科目名・科目コード列を出力
- DB migration 22→23（`yayoiAccountId`）・24→25（`isExcluded`）

**③ 通帳データ（DepositMeisai）：弥生科目の個別オーバーライド（新規）**
- `DepositMeisai.overrideYayoiAccountId` 追加（グループ単位の弥生科目をレコード単位で上書き）
- `DepositMeisaiDao.updateOverrideYayoiAccount()` / `clearYayoiOverridesForRule()` 追加
- `DepositMeisaiWithOverride` に弥生科目名・科目コードを追加（`yayoi_accounts` を LEFT JOIN）
- DB migration 21→22

**④ TekiyouMatchingScreen（通帳摘要マッチング）：AI一括マッチング機能（新規）**
- `GeminiReceiptClient.matchTekiyouToAccounts()` 追加（通帳摘要パターン→弥生科目のAI一括提案。品目版と同様の仕組み）
- `AiUsageStats`（トークン使用量表示）、`GeminiQuotaExhaustedException`（無料枠上限）、`GeminiApiException`（HTTPコード別メッセージ：403/500/503）を追加し、エラーハンドリングを強化
  - 旧 `GeminiRateLimitException` は429専用に整理

**⑤ その他UI変更**
- `GeneralReceiptCaptureScreen`：撮影画面に照明（トーチ）ON/OFFボタン追加、撮影後は自動でOFF
- `SettingsScreen`：「📱 表示設定」セクション新設（一覧文字サイズをA-/A+で調整、9画面共通の `appPreferences.listFontSize` と連動）
- `SettingsScreen`：セクション名変更「購買部門」→「🌾 JA購買伝票」／「預金部門」→「🏦 JA預金」／「一般購買部門」→「🛒 レシート・領収書」（MenuScreen等と表記統一）
- `SettingsScreen`：「勘定科目設定」への遷移ボタンを削除（`AccountSettingsScreen` 削除に伴う整理の一環、6/11時点の作業と整合）

### DB バージョン
v21 → **v25**（4マイグレーション追加：21→22, 22→23, 23→24, 24→25）
※ `CLAUDE.md` の「Room DB バージョン（現在 v22）」の記載が古いままなので要更新（ユーザー確認の上で修正予定）

### 未完了・中断した理由
実機確認・BUILD確認の記録が残っていない。上記①〜⑤はコード上は実装が揃って見えるが、動作未検証。

### 次回セッションで最初にやること
1. `./gradlew clean assembleDebug` で BUILD SUCCESSFUL を確認
2. 実機動作確認：
   - レシート撮影→OCR→登録番号抽出→店舗名自動照会（ネットワークON/OFF両方）
   - 登録番号・法人一覧画面（一覧表示・編集・削除・NTA再照会・レシートから取込）
   - 一般レシート品目の除外チェック・科目割当がCSV出力に反映されるか
   - 通帳データの個別弥生科目オーバーライドが保存・CSV反映されるか
   - TekiyouMatchingScreen のAI一括マッチングとトークン使用量表示
   - 撮影画面の照明ボタン
   - 設定画面の表示設定セクション・A-/A+
3. 動作確認後、`git add` の範囲を精査してコミット（未追跡の `InvoiceStore*.kt` / `NtaInvoiceClient.kt` / `UiComponents.kt` / `docs/yayoi-csv-export-spec.md` を含める）

### 新たに発覚した問題・制約
- `NtaInvoiceClient` の `API_BASE_URL` とレスポンスJSONフィールド名（`code`/`announcement`/`name`/`address`）は国税庁公式ドキュメントとの突合が未実施（推測実装）。`docs/known-issues.md` に転記済み
- アプリケーションID未設定時に動作するかどうかも未検証

---

## 追加作業（2026-07-12）：プロジェクト解析で発見したリスクの修正

### 目的・背景
コード全体の解析で「データが壊れる・漏れる」系のリスクを5件特定し、優先度順に修正。

### 今回完了したこと

**① CSV共有ユーティリティ `util/CsvUtils.kt` 新設・エスケープ漏れ修正**
- `escapeCsvField` / `quoteField` / `toYayoiDate` / `yayoiCharset` を集約
- `OutputConfirmScreen.kt`・`GeneralReceiptOutputScreen.kt` に重複していた同名関数を CsvUtils 委譲に置換
  （`toYayoiDate` は区切り文字 `-`/`/` 両対応の堅牢な方に統一）
- **バグ修正**: `GeneralReceiptViewModel.buildCsvForExport` が生の文字列連結でカンマ入り商品名・店舗名で列ずれしていた → エスケープ適用

**② 弥生CSVの文字コードを Shift_JIS → windows-31j に変更**
- `Charset.forName("Shift_JIS")` は ①・㈱ 等の機種依存文字を無警告で `?` に化けさせる
- `CsvUtils.yayoiCharset()`（windows-31j 優先、非対応環境のみ Shift_JIS フォールバック）に統一（3箇所）

**③ APIキーのバックアップ除外**
- `AppPreferences`: `geminiApiKey`・`ntaApplicationId` を秘匿専用ファイル `receipt_ocr_secrets` に分離
  - 旧ファイルからの一度きり自動移行（`migrateSecretsToSecurePrefs()`）付き
- `res/xml/backup_rules.xml`（API≤30）・`data_extraction_rules.xml`（API31+）で秘匿ファイルをバックアップ・端末間転送から除外
- `AndroidManifest.xml` に `fullBackupContent` / `dataExtractionRules` 属性を追加（テンプレートXMLはあったが未参照だった）

**④ `fallbackToDestructiveMigration()` 削除**
- 実データ蓄積段階に入ったため前倒しで削除。以後マイグレーション書き忘れは起動時クラッシュになる
- `docs/known-issues.md`・`CLAUDE.md` の記述も更新（CLAUDE.md はユーザー確認済み）

**⑤ ユニットテスト新設（プロジェクト初）**
- `app/src/test/java/com/example/greenframeocr/util/CsvUtilsTest.kt`
  - CSVエスケープ、和暦変換（令和境界 2019/4/30↔5/1）、機種依存文字エンコード
- `app/src/test/java/com/example/greenframeocr/util/NtaInvoiceClientTest.kt`
  - 登録番号抽出（正常系・OCRテキスト埋め込み・桁不足・小文字）

**⑥ `NtaInvoiceClient.lookup()` のレスポンス未クローズ修正**
- `execute().use { }` に変更（HTTPエラー時のコネクションリーク解消）

### 完了条件
- [x] `./gradlew testDebugUnitTest assembleDebug` 成功（BUILD SUCCESSFUL・テスト19件全成功、2026-07-12）

### 未完了・中断した理由
なし（実機動作確認は次回）

### 次回セッションで最初にやること
実機動作確認（前セッション分と合わせて）：
1. アプリ更新後に Gemini APIキー・NTAアプリケーションIDが消えていないか（secure_prefs 移行の確認）
2. 弥生CSV出力で ①・㈱ 等を含むデータが正しく出るか
3. カンマ入り商品名でレシートCSVの列がずれないか

### 新たに発覚した問題・制約
- destructiveMigration 削除により、今後のスキーマ変更はマイグレーション必須（漏れると起動時クラッシュ）
