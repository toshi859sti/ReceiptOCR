# CURRENT_TASK.md

## 作業タイトル
JA購買伝票OCRパイプライン Gemini Vision API移行（Phase 0・1・2完了、Phase4一部着手）

## 目的・背景
詳細計画は `docs/TASK_gemini_ocr_migration.md` を参照。ML Kit日本語モデルの精度が
頭打ちのため、JA購買伝票OCRをGemini Vision APIへ移行する。UI・DB基盤を作り込む前に、
まず実測でGeminiの読み取り精度を検証する（Phase0）。

2026-08-07のレビューで確定した主な方針：
- APIキーはユーザー個別キー方式を維持（既存 `AppPreferences.geminiApiKey` を使用）
- JA伝票OCRは課金有効化キーを推奨（無料枠は入出力がGoogle側の学習に利用され得るため）
- 新規ラッパークラスは作らず既存 `util/GeminiReceiptClient.kt` を拡張
- DBマイグレーションは v25→v26
- `ocrConfidence` は補助表示のみ。強制ブロックは検算バリデーション不一致のみ
- ML Kit経路はユーザー向けトグルとして併存させない。Phase6の削除は本番安定稼働後

## 今回のタスク

### Phase 0：スパイクテスト（完了）
- [x] 既存の実機確認済み `warpedBitmap`（3045×2220px）サンプルを数枚用意
- [x] JA伝票用プロンプト素案（列定義・JSON構造指定）を作成
- [x] 手動スクリプトまたはデバッグ経由でGemini Vision APIへ送信し、レスポンスを確認
- [x] 数量列・商品名列（複雑な漢字）の読み取り精度をML Kit時代の既知の誤読サンプルと比較
- [x] 後継モデル（Gemini 3 Flash / 3.1 Flash-Lite等、2.5 Flashは2026/10/16廃止予定）の
      速度・精度・コストを比較 → `gemini-3.5-flash-lite`採用確定
- [x] 送信画像リサイズ幅（2,000〜2,500px）を変えて精度への影響を確認し、最終値を決定
      → 有意差なし、2000px既定
- [x] 取引日列の不安定な誤読 → 列クロップTwo-Pass方式で完全安定を確認、採用確定

### Phase 1：透視変換後の確認フロー（実装・実機確認済み）
- [x] `GreenFrameDetector.isValidShape()` 新規実装（コーナー内角±1.5°以内）
- [x] `TransformPreviewScreen` 新設
- [x] `OcrCaptureViewModel` に `CaptureStep.Preview` / `onDetectionResult()` /
      `retryFromPreview()` を追加（`SheetEditorScreen`の「再OCR」導線用）
- [x] `OcrCaptureScreen` の撮影完了フローに組み込み
- [x] **本番の実際の撮影導線（`ReceiptInputScreen.kt`内の独自`CameraView`）にも同じ
      ロジックを追加**（実機確認で`OcrCaptureScreen`が主動線でないことが判明したため）
- [x] 送信時のカメラちらつき不具合を修正（`isProcessingOcr`最優先分岐に変更）
- [x] 実機での動作確認完了（スキップ／確認画面表示／撮り直す／送信するの全パターン確認済み）

### Phase 2：Gemini API連携基盤（実装・本番組み込み・実機確認済み）
- [x] `GeminiReceiptClient.kt`に`parseJaSheetFromImage()`実装（列クロップTwo-Pass・並列実行）
- [x] 429/500/503/通信エラーの指数バックオフリトライ（`callWithRetry()`）
- [x] 日付列アラインメント不一致の再アラインメント処理（NORMAL行数ベース）
- [x] `OcrCaptureViewModel`・`ReceiptInputScreen.kt`の`CameraView`をGemini呼び出しに置き換え
- [x] カテゴリ仮判定ロジック（`assignJaSheetCategories()`、ML Kit版と同じ簡易方式）
- [x] 実機の本番導線で複数枚撮影・動作確認（日付・商品名・金額・小計・合計すべて正常）
- [x] `SettingsScreen`への課金有効化キー推奨の注意文言追加
- [x] `docs/development-guidelines.md`へのGoogle Cloud Console設定手順追記
- [x] API失敗時の専用エラーUI・再試行ボタン

### Phase 4：検算バリデーション・confidence表示（一部着手）
- [x] `receipt_items` に `ocrConfidence` カラム追加（`MIGRATION_25_26`、v25→v26）
- [x] `OcrCaptureViewModel.ParsedRow`・`saveData()` でGeminiの`confidence`を
      `ReceiptItem.ocrConfidence`に保存
- [x] 既存の`ValidationUtils.validateSheet()`（小計整合性検算）がGemini結果にも
      コード変更なしで機能することを確認（`SheetEditorScreen`で不一致を赤表示）
- [x] `SheetEditorScreen`（再OCR専用の副次画面）の行リストに要確認バッジを追加
- [x] `ItemEditDialog`に要確認理由の一言表示を追加
- [x] **重大な発見**：`SheetEditorScreen`は主導線ではなく、実際の主導線
      `ReceiptInputScreen.kt`は独自の`ReceiptRowData`/`validateAllSheetsData()`/
      `saveMonthData()`という別系統の実装だった。バッジ表示・検算ブロックとも
      こちらにも実装が必要と判明し、追加実装した
- [x] `ReceiptInputScreen.kt`のデータグリッドにも要確認バッジを追加（`ocrConfidence`を
      `ReceiptRowData`に追加し、OCR直後・DB再読込時・保存時の3箇所で伝搬）
- [x] `ReceiptInputScreen.kt`の「決定」ボタンに検算不一致の強制ブロックを実装
      （`hasUnresolvedMismatch()`。入力値が0＝未検出の場合と、罫線誤読の既知パターンで
      説明が付く場合はブロック対象外）
- [x] **実機動作確認済み**（2026-08-09）：検算不一致ブロック・DBマイグレーション
      （既存データ入り端末での無停止起動）・バッジ列レイアウトを確認
- [ ] 伝票内の要確認行のみを抽出する一覧・一括確認モード（未実装）

## 完了条件
- Gemini Vision APIでJA伝票の実サンプルを読み取り、精度がML Kit＋補正ロジックと
  同等以上であることを確認できる（Phase0達成）
- 採用モデル・リサイズ幅が実測に基づいて決定されている（Phase0達成：`flash-lite`・2000px・
  列クロップTwo-Pass）
- 検証結果が `docs/TASK_gemini_ocr_migration.md` の進捗メモに記録されている（達成）
- TransformPreviewScreenが実機で正しく動作する（達成：本番導線で確認済み）

## 進捗メモ
（作業中に気づいたこと、決定事項、変更点などを随時記録）

- 2026-08-08：Phase0スパイクテスト実施。詳細結果は `docs/TASK_gemini_ocr_migration.md` の
  進捗メモ参照。要点は下記「今回完了したこと」に記載。
- 2026-08-08（続き）：実機4サンプル追加検証。数量列除外なら商品名・金額・小計・合計は
  4サンプルとも完全正解だったが、取引日列に低確率（約1/3）で不安定な誤読が見つかった。
  詳細は `docs/TASK_gemini_ocr_migration.md` 進捗メモ「sample01〜04追加検証」参照。
- 2026-08-08（続き2）：Geminiに原因・対策を相談し、「生の6桁数字のまま出力→クライアント側で
  MM/DDにパース」方式を実装して再検証。sample03は3回とも完全安定して正解に改善。sample01は
  改善したが3回中1回のみ完全正解でまだ不安定。詳細は `docs/TASK_gemini_ocr_migration.md`
  進捗メモ「Geminiへの技術相談・生数字出力方式で再検証」参照。
- 2026-08-08（続き3）：取引日列だけをクロップして単独送信するTwo-Pass方式を実装・検証
  （`gemini_datecolumn_crop_test.ps1`）。sample01・sample03とも**6回中6回完全に安定して
  正解**（従来の全体画像方式ではsample01が3回中1回のみ正解）。処理も1.3〜2.7秒と全体画像
  方式より高速・低トークン。**Two-Pass方式を正式採用**、昇順チェックは優先度を下げた。
  詳細は `docs/TASK_gemini_ocr_migration.md` 進捗メモ「列クロップTwo-Pass方式を検証」参照。
- 2026-08-08（続き4）：MLKit版を別プロジェクトとしてコピー開発する案を検討したが、
  共通コード（GreenFrameDetector・DB・CSV出力等）の二重管理コストが大きいため中止。
  従来通りGitブランチ運用（`master`=ML Kit版、`feature/gemini-ocr`=Gemini移行作業）を継続
- 2026-08-08（続き5）：Phase1（`TransformPreviewScreen`）を実装。`isValidShape()`は
  計画書に「既存」と記載されていたが実際には未実装だったため新規作成（コーナー内角±1.5°、
  `DebugCaptureScreen`のデバッグ表示閾値と同じ基準）。`OcrCaptureViewModel`に
  `CaptureStep.Preview`を追加し、形状NG時のみ確認画面を挟む設計。`Navigation.kt`への
  新規ルート登録は不要（`OcrCaptureScreen`内の状態遷移として実装）。
  `./gradlew compileDebugKotlin`でコンパイル確認済み、実機動作確認は未実施。
  詳細は `docs/TASK_gemini_ocr_migration.md` Phase1セクション参照。
- 2026-08-09：Phase1実機確認を実施。**重大発見**：実際の本番撮影導線（伝票データ→編集→
  伝票追加→撮影）は`OcrCaptureScreen`/`OcrCaptureViewModel`を経由せず、
  `ReceiptInputScreen.kt`内の独自`CameraView`実装を使っていた（`Screen.OcrCapture`ルートは
  `SheetEditorScreen`の「再OCR」からのみ到達、主動線ではなかった）。`CameraView`にも同じ
  `isValidShape()`＋`TransformPreviewScreen`ロジックを追加して修正。送信時にカメラが一瞬
  映る不具合も発見・修正（`isProcessingOcr`を最優先分岐に）。修正後、スキップ／確認画面
  表示／撮り直す／送信するの全パターンを実機で確認済み。既存ML Kitパイプラインの精度限界
  （取引日誤読・小計取得漏れ・罫線誤読・商品名誤り）も実機で再確認したが、これはPhase1と
  無関係でGemini移行（Phase2/3）で解消予定。詳細は `docs/TASK_gemini_ocr_migration.md`
  Phase1セクション「実機確認」参照。
- 2026-08-09（続き）：Phase2着手。`GeminiReceiptClient.kt`に`parseJaSheetFromImage()`を実装
  （列クロップTwo-Pass方式・並列実行・指数バックオフリトライ）。コンパイル確認済みだが
  **まだUIから呼ばれていない**（実際のOCR呼び出し箇所は2箇所とも引き続きML Kit）。
  実機でのAPI疎通確認も未実施。詳細は `docs/TASK_gemini_ocr_migration.md` Phase2参照。
- 2026-08-09（続き2）：`DebugCaptureScreen`に一時的な動作確認用ボタンを追加し、実機で
  `parseJaSheetFromImage()`をGemini APIキーで実行。1回目は`dateColumnAligned=false`
  （main16行・date14行）となり、原因はSUBTOTAL行2件を列クロップコールが省略していたこと
  と判明。NORMAL行数ベースの再アラインメント処理を追加し、再実行で正常にマージされることを
  実機確認済み。詳細は `docs/TASK_gemini_ocr_migration.md` Phase2「実機でAPI疎通確認」参照。
- 2026-08-09（続き3）：**本番組み込み完了**。`OcrCaptureViewModel.processDetectionResult()`・
  `ReceiptInputScreen.kt`の`CameraView.runOcr()`の両方をML KitからGeminiに置き換え
  （共通処理は`OcrCaptureViewModel.mapGeminiResultToParsedRows()`）。カテゴリ判定は
  ML Kit版と同じ「簡易仮判定＋保存後CategoryRecalculatorで確定」方式を踏襲。実機の本番導線
  （伝票データ→編集→伝票追加→撮影）で複数枚撮影し、日付・商品名・金額・小計は完璧に取得。
  合計(税込)が空欄になる問題を発見しプロンプト修正（MONTHLY_TOTAL行のcategorySum指示追加・
  ヘッダー省略可の注意書きが合計行と紛らわしかった点を修正）→再テストで解決。複数ページ
  伝票で2枚目以降の合計欄が空欄になる件は紙面自体の仕様（1ページ目にのみ合計を印字）で
  Gemini側の問題ではないとユーザー確認済み。ただし修正時のプロンプト文言に「合計行は
  必ず存在する」という誤った前提を書いてしまっており、ユーザー指摘で訂正（画像に実際に
  印字されている場合のみ出力する条件に修正）。再テストで2ページ目以降の合計を捏造しない
  ことを確認済み。**Phase0〜2が実機で完全に動作確認できた。**

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- Gemini API課金有効化キーの取得（新規プロジェクト`accounting-ocr`作成、請求先アカウント作成、
  従量課金制フルアカウントへのアップグレード、前払いクレジット¥5,000チャージ、予算アラート
  ¥1,000/月設定）。旧`testOcr01`プロジェクト（2020年からのFirebase既存プロジェクト、無関係）は削除
- Phase0スパイクテスト（1枚目）：実機確認済みサンプル1枚を`gemini-3.6-flash` /
  `gemini-3.5-flash-lite` × 送信画像幅2000px/2500pxの4パターンで実測
  - `gemini-3.6-flash`：全行正解（数量列の小数点推測も成功）、約20秒/回、約¥5〜6円/回
  - `gemini-3.5-flash-lite`：商品名・金額は正解だが数量列誤読、約4秒/回、約¥0.6円/回
  - 送信画像幅2000px/2500pxで精度・トークン数に有意差なし
- **方針転換**：数量列はCSV出力（弥生・らくらく青色申告）で未使用と判明。20秒/回は遅いとの
  指摘もあり、数量列を評価対象から除外して`flash-lite`中心に再検証する方針に変更
- 実機で追加4サンプル撮影（`DebugCaptureScreen`→画像保存→`adb pull`で取得。端末が
  「unauthorized」だったのでUSBデバッグ許可が必要だった）。返品行・複雑な商品名・
  ヘッダー空欄2ページ目・SUBTOTAL行なしの4パターンをカバー
- 4サンプルで`flash-lite`を再検証：**商品名・税込金額・分類計・合計は4サンプルとも完全正解**
- **重大な発見**：取引日列で、サンプル内の全行が同じ月に系統的に誤読される現象を2サンプルで
  発見（例：11月分が全行10月、9月分が全行7月）。目視で伝票印字を拡大確認し誤読でないことを確認
- 原因切り分け：①リサイズなし（3200px）に変更→トークン数不変・改善せず、②プロンプト修正
  （日付の桁を慎重に読む指示＋昇順一貫性チェック指示）→1サンプルは直ったが1サンプルは直らず、
  ③同一画像を3回連続実行→2回正解・1回誤り（`confidence`はいずれも"high"のまま）
  → **temperature=0でも実行ごとに結果がブレる不安定な誤りで、プロンプト側だけでは根絶できず、
  Geminiの自己申告confidenceでも検出できない**ことが判明
- `docs/TASK_gemini_ocr_migration.md` に一連の実測結果・原因切り分け・対策方針を記録
- Geminiに直接、実行ごとのブレの原因と対策を相談（temperature=0でも分散推論の浮動小数点誤差で
  タイブレークが起きる、列全体が引きずられるのは「コンテキストバイアス」という既知の失敗モード、
  等の回答）。提案の中から「生の6桁数字のまま出力させクライアント側でパース」を試したところ
  sample03は3回とも安定して正解するように改善。ただしsample01は3回中1回のみ正解で、
  Geminiが提案していた「取引日列だけのクロップ二段階OCR」が本命の対策として残る
- 検算用に実装した「昇順チェック」ロジックが、SUBTOTAL行での区分ごとの日付リセットを
  考慮しておらず誤検出することが判明。Phase4設計では区分境界でリセットする仕様が必要
- **2026-08-09：Phase1・Phase2を実装し、実機の本番導線で完全に動作確認できた。**
  - Phase1：`GreenFrameDetector.isValidShape()`（新規実装）＋`TransformPreviewScreen`で
    撮影後の確認フローを実装。実機確認の過程で「本番の撮影導線は`OcrCaptureScreen`ではなく
    `ReceiptInputScreen.kt`内の独自`CameraView`」という重大な事実を発見し、そちらにも
    同じロジックを追加。送信時のカメラちらつき不具合も発見・修正
  - Phase2：`GeminiReceiptClient.parseJaSheetFromImage()`（列クロップTwo-Pass方式・並列実行・
    指数バックオフリトライ）を実装。実機確認で「列クロップコールがSUBTOTAL行を省略する」
    問題を発見し、NORMAL行数ベースの再アラインメントで解決
  - `OcrCaptureViewModel`・`ReceiptInputScreen.kt`の両方でML KitからGeminiへの置き換えが完了。
    カテゴリ判定はML Kit版と同じ「簡易仮判定＋保存後CategoryRecalculatorで確定」方式を踏襲
  - 実機の本番導線で複数枚撮影し、日付・商品名・金額・小計・合計（該当ページのみ）すべて
    正常に取得できることを確認。合計(税込)行の欠落バグをプロンプト修正で解決する過程で、
    プロンプトに「合計行は必ず存在する」という誤った前提を書いてしまい、ユーザー指摘で
    訂正（複数ページ伝票では2ページ目以降に合計行自体が存在しないため）
  - 詳細は `docs/TASK_gemini_ocr_migration.md` Phase1・Phase2セクション参照
- 2026-08-09（続き4）：Phase2残タスクのうち2点を完了。`SettingsScreen`のGemini APIキー欄に
  課金有効化キー推奨の注意カード（`tertiaryContainer`・`Warning`アイコン、
  `TransformPreviewScreen`と同じ意匠）を追加。`docs/development-guidelines.md`に
  「Gemini API キー設定手順（課金有効化キー）」節を新設し、プロジェクト作成〜請求先アカウント
  作成〜課金アップグレード〜予算アラート設定〜APIキー発行の手順と、実測料金目安
  （`gemini-3.6-flash`約¥5〜6/枚・`gemini-3.5-flash-lite`約¥0.6/枚）を記載。
  `./gradlew compileDebugKotlin`でコンパイル確認済み。
- 2026-08-09（続き5）：Phase2最後の残タスク「API失敗時の専用エラーUI・再試行ボタン」を実装し、
  **Phase2完全完了**。`OcrCaptureViewModel.CaptureStep`に`Error(message, detectionResult)`を
  新設し、透視変換失敗・Gemini API例外（`GeminiRateLimitException`等の既存の日本語メッセージを
  そのまま活用）の両方をこのステップに集約。`OcrCaptureScreen.kt`に共有の`OcrErrorScreen`
  composable（errorContainerカード＋「再試行（同じ画像で送信）」「撮り直す」「キャンセル」の
  3ボタン）を新設し、`OcrCaptureScreen`本体と本番導線`ReceiptInputScreen.kt`の`CameraView`
  両方から利用する形に統一。従来`CameraView`はToastで一瞬表示して素通りするだけで、失敗時は
  常に撮り直しを強制していたが、「再試行」ボタンで撮影済み画像を破棄せず同じ画像のまま
  再送信できるようにした（ネットワーク瞬断など一過性エラーでの撮り直しストレスを軽減）。
  未使用になった`Toast`importと`context`変数を削除。`./gradlew compileDebugKotlin`で
  コンパイル確認済み（実機での失敗パターン再現確認は未実施）。
- 2026-08-09（続き6）：Phase4に一部着手（DBスキーマ・データフロー・基本UI表示のみ）。
  `ReceiptItem`に`ocrConfidence: String?`を追加し、`ReceiptDatabase.kt`に
  `MIGRATION_25_26`（`ALTER TABLE receipt_items ADD COLUMN ocrConfidence TEXT`、v25→v26）
  を実装。`OcrCaptureViewModel.ParsedRow`にconfidenceを追加し、`mapGeminiResultToParsedRows()`
  ・`saveData()`でGeminiの`JaSheetRow.confidence`を`ReceiptItem.ocrConfidence`まで
  伝搬させた。**重要な発見**：既存の`ValidationUtils.validateSheet()`（小計・合計整合性の
  検算バリデーション）は`SheetData`と`ReceiptItem`の`category`/`amount`のみを見る設計のため、
  `OcrCaptureViewModel.saveData()`がML Kit時代と同じ形で`SheetData`を組み立てている以上、
  **コード変更なしですでにGemini結果に対しても機能している**（`SheetEditorScreen`の
  小計セクションで不一致が赤表示される）ことを確認した。UI側は`SheetEditorScreen.kt`の
  `EditableRow`に要確認バッジ（`ocrConfidence=="low"`の行に黄色ドット）、`ItemEditDialog`に
  要確認理由の一言表示を追加。`./gradlew compileDebugKotlin`でコンパイル確認済み
  （実機でのマイグレーション動作確認・バッジ表示確認は未実施）。
  **今回は着手しなかったもの**：①検算不一致時に`OutputConfirmScreen`での確定操作を
  強制ブロックする仕組み（一般レシート含む共有画面への変更で影響範囲が広く、設計判断が
  必要なため見送った）、②伝票内の要確認行のみを抽出する一覧・一括確認モード。
  詳細は`docs/TASK_gemini_ocr_migration.md`Phase4セクション参照。
- 2026-08-09（続き7）：ユーザーから「不完全な伝票は保存すべきでない」という強い方針指摘を
  受け、強制ブロックの設計・実装を進める過程で**重大な発見**：`SheetEditorScreen`は
  Phase1で発見済みの`CameraView`の件と同根で、実は主導線ではなかった。実際にユーザーが
  使う編集・保存画面は`ReceiptInputScreen.kt`が独自に持つ月単位の`ReceiptRowData`グリッド
  ＋`validateAllSheetsData()`＋`saveMonthData()`で、`ValidationUtils`/`ReceiptItem`とは
  完全に別系統の実装だった。このため（続き6）で`SheetEditorScreen`に入れたバッジ表示は
  副次的な再OCR画面にしか効いておらず、`OcrCaptureViewModel.saveData()`に入れた
  `ocrConfidence`保存処理も主導線では一度も呼ばれていないことが判明。
  `ReceiptInputScreen.kt`側に同じ内容を実装し直した：
  - `ReceiptRowData`に`ocrConfidence`を追加し、`convertParsedRowsToRowData()`
    （OCR直後）・`convertReceiptItemsToRows()`（DB再読込時）・`saveMonthData()`
    （保存時）の3箇所で伝搬
  - `DataGrid`のヘッダー行・`DataRow`に16dp固定幅の要確認バッジ列を追加
    （左右で列がずれないよう、ヘッダー側にも同幅のスペーサーを追加）
  - 検算不一致の強制ブロックを「決定」ボタンに実装。`hasUnresolvedMismatch()`で、
    カテゴリ別小計・合計それぞれについて「入力値が0でない（＝検出/入力済み）」かつ
    「計算値と一致しない」かつ「罫線誤読の既知パターン（`tryStripRuleDigit`、罫線が
    数字'1'に誤読される既知の補正）でも説明が付かない」場合のみブロックする設計にした。
    入力値0（小計行未検出）を除外したのは、SUBTOTAL行が印字されない伝票パターンが
    実在すること（2026-08-08実機検証で確認済み）と、未検出を機械的にブロックすると
    「印字されていない数字を仕方なく捏造入力する」という悪い誘因を生むため
  - 既存の`hasUnclassified`（未分類行ブロック）と同じ`return@Button`パターンで実装し、
    ブロック時は専用の`AlertDialog`を表示
  - `./gradlew compileDebugKotlin`でコンパイル確認済み（実機でのブロック動作確認は未実施）

### 未完了・中断した理由
Phase0・1・2は実機確認まで完了し、本番導線がGemini化された。**Phase2は全タスク完了**。
Phase4はDBスキーマ・confidenceデータフロー・UI表示（バッジ）・検算不一致の強制ブロックまで
完了（主導線`ReceiptInputScreen.kt`に実装済み）。一括確認モードのみ未着手
（詳細は上記「今回完了したこと」参照）。

### 次回セッションで最初にやること
1. `OcrErrorScreen`（再試行・撮り直すボタン）をGemini API呼び出し失敗パターン
   （APIキー空・機内モード等）で実機動作確認する（今回はまだ未実施）
2. しばらく実機で複数枚・複数パターン（返品行・SUBTOTALなし伝票・複雑な商品名等）を撮影し、
   本番導線でのGemini結果の安定性を継続確認する（今回確認できたのは数枚のみ）
3. `dateColumnAligned=false`（再アラインメントでも救済できない場合）の伝票をどう扱うか
   （現状は全体画像コールの値をそのまま使うだけで、UI上の警告表示は未実装）
4. Phase4残タスク：伝票内の要確認行のみを抽出する一覧・一括確認モード

### 2026-08-09（続き8）：実機で検算不一致ブロックを動作確認（完了）
`ReceiptInputScreen.kt`（`令和8年1月`・テスト用に伝票追加→クリア後に破棄、実データへの影響なし）で
「あああ／500円」（NORMAL・一般購買）＋「小計（一般購買）／1,000円」（SUBTOTAL）という
意図的な不一致データを直接入力モードで作成し、「決定」ボタンをタップ。
**「小計・合計が一致していません」ダイアログが正しく表示されブロックされることを確認**。
同時に「合計」欄も入力値0（未入力）で不一致表示になっていたが、`hasUnresolvedMismatch()`の
設計通りこちらはブロック理由に含まれず、「一般購買」小計の不一致のみが理由でブロックされる
ことも確認できた（＝未検出値をブロック対象外とする線引きが意図通り機能）。
「キャンセル」でテストデータを保存せず破棄し、0/0伝票の状態に復元。
DBマイグレーション（v25→v26、既存データ入り端末で無停止起動）・バッジ列のレイアウト
（ヘッダー/データ行のズレなし）も合わせて実機確認済み。
adb操作の実務メモ：Git Bash環境では`adb pull`等のパス引数が`/sdcard/...`のまま
MSYS側のパス変換に巻き込まれるため`MSYS_NO_PATHCONV=1`を付けること。
Compose画面の要素タップ座標は`adb shell uiautomator dump`の`bounds`から機械的に
算出する方が、スクリーンショット画像上の目視換算より大幅に正確（今回何度か目視換算で
ミスタップした）。

### 新たに発覚した問題・制約
- Gemini API課金有効化の実務手順が複雑だった（請求先アカウント新規作成・プロジェクトの
  課金アップグレード・前払いチャージが別々の画面で分かりにくい）。次回同様の作業をする際は
  この`CURRENT_TASK.md`と`docs/TASK_gemini_ocr_migration.md`の記録を参照すること
- `gemini-3.6-flash`は約20秒/回とUXに影響する速度。採用する場合はローディングUI設計
  （Phase1〜2）で考慮が必要
- 数量列を仕訳データとして使わない方針が確定したため、検算バリデーション（税込金額列のみ対象）
  の限界（数量列の誤りを検出できない）は実害がなくなった
- **取引日列にtemperature=0でも約1/3の確率で不安定な誤読が発生し、Geminiの自己申告confidence
  では検出できない**（2026-08-08発見、詳細は上記）。Phase4設計に必ず反映すること
- Windows PowerShell 5.1（`powershell.exe`）はBOMなしUTF-8の`.ps1`を正しく解釈できず、
  日本語コメントを含むスクリプトで構文エラーになる。検証用スクリプトを書く際はBOM付きUTF-8で
  保存すること
- `adb`は初回接続時に端末側でUSBデバッグ許可のダイアログ確認が必要（「unauthorized」状態になる）
- **`OcrCaptureScreen`/`OcrCaptureViewModel`・`SheetEditorScreen`/`SheetEditorViewModel`は
  実質的に主動線ではない**（`SheetEditorScreen`の「再OCR」ボタンからのみ到達する副次画面の
  組）。実際にユーザーが撮影・編集・保存に使う画面は`ReceiptInputScreen.kt`単体で、撮影用の
  独自`CameraView`実装に加えて、編集・検証・保存もすべて独自の`ReceiptRowData`/
  `validateAllSheetsData()`/`saveMonthData()`で完結しており、`ValidationUtils`・
  `SheetEditorViewModel`とは別系統。2026-08-09、Phase4のバッジ表示・検算ブロックを
  `SheetEditorScreen`側に実装してから「主導線に効いていない」と気づき、
  `ReceiptInputScreen.kt`側に実装し直す手戻りが発生した。**今後この2画面のどちらかに
  手を入れる際は、必ずもう一方（`ReceiptInputScreen.kt`が主・`SheetEditorScreen`が副）
  にも同じ変更が要るか確認すること**（Phase1のカメラ確認フロー・Phase2のGemini呼び出し
  組み込み・Phase4のconfidence表示/検算ブロックと、これで3回連続で同じ見落としをしている）
- Compose環境で「Aの状態をnullにしてBの分岐に戻す」系の実装は、B側に副作用のある
  `LaunchedEffect`（今回は`cameraViewModel.resetToPreview()`）があると意図せず再実行されて
  ちらつき等の不具合を生みやすい。状態分岐の優先順位（今回は`isProcessingOcr`を最優先に）に
  注意すること
- **Geminiプロンプトに「〜は必ず存在する／空欄になることはない」という断定的な前提を
  書くと、伝票の多様なバリエーション（今回は複数ページ伝票の2ページ目以降）で事実と
  矛盾し、誤った出力を誘発するリスクがある**（2026-08-09、ユーザー指摘で発覚）。
  「実際に画像に見えている場合のみ出力する」という条件付きの表現にすること
