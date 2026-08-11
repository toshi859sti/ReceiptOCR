# CURRENT_TASK.md

## 作業タイトル
JA購買伝票OCRパイプライン Gemini Vision API移行（Phase 0〜6すべて完了）

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
- [x] API失敗時の専用エラーUI・再試行ボタン（2026-08-09実機確認済み：通信エラー時に
      正しくブロック・再試行・撮り直し・キャンセルすべて動作）

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
- [x] 伝票内の要確認行のみを抽出する一覧・一括確認モード（2026-08-11実装・実機確認済み、詳細下記）

### Phase 5：再OCRの部分クロップ再送信化（完了・実機確認済み）
- [x] `GeminiReceiptClient.kt`に行Y座標モデル（20行均等分割＋合計行の別ブロック）と
      `parseJaSheetPartial()`を新設。選択セルの行範囲だけをクロップして送信し、
      選択セル種別に応じて不要な呼び出し（取引日列クロップ等）を省略する
- [x] 返却行数が期待値と一致しない場合は自動的に伝票全体再送信（既存Fullパス）へフォールバック
- [x] `ReceiptInputScreen.kt`（主導線のみ。副次画面`SheetEditorScreen`はセル選択の概念が
      なく対象外）に`OcrRunResult`（Full/Partial）を新設し、`CameraView.runOcr()`・
      `onOcrComplete`をPartial対応に変更
- [x] 実機で5パターン確認済み（2026-08-11）：セル未選択の回帰確認／金額のみ選択／
      取引日のみ選択／離れた複数行選択／合計行との混在選択時の自動フォールバック
- [x] **検証中に発見した実際の事故を受けて追加実装**：同じ物理伝票を誤って2枚目として
      再OCRしてしまい、小計が異常な形で合算される事故が発生。月次請求明細書は同じ小計
      カテゴリ（一般購買/給油所/農業機械）が月に1回しか出現しない仕様のため、他の伝票と
      小計カテゴリが重複した場合は保存前にブロックする機能を追加（既存の同一伝票内重複
      チェックと同じダイアログ・UIパターンを再利用）。実機で実際に再現・ブロックを確認済み

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
Phase0〜6すべて完了・実機確認済み。Phase6（ML Kit時代のJA伝票専用コード削除）を実施し、
実機のv27→v28マイグレーション・UI表示・撮影/保存フロー・一般レシート（ML Kit経路）を
確認済み（下記「続き7」参照）。中断した作業はなし。

### 次回セッションで最初にやること
1. 引き続き実機で複数パターン（返品行・複数ページ・小計なし等）の伝票を撮影し、
   Gemini結果と保存時チェック群の安定性を継続確認する
2. Phase6で計画外だったドキュメント10件（`glossary.md`・`OCR_SPEC.md`・
   `CORRECTION_SYSTEM.md`・`DICTIONARY.md`・`OCR_LEARNING_REDESIGN.md`等）に残る
   削除済みクラスへの言及の整理（優先度は低い、アーカイブ的な内容のため急ぎではない）

### 2026-08-11（続き6）：実機継続確認中に発見したトーチ（懐中電灯）消灯漏れバグを修正（完了）
本番導線で実際のJA伝票2枚（令和7年8月、一般購買20行＋給油所小計・給油所1行）を撮影し、
保存時チェック群（要確認バッジ・検算・小計カテゴリ重複ガード等）が正常に機能することを確認した
（`category="未定"`0件、全行`ocrConfidence=high`、クラッシュ・エラーログなし）。取引日列の
再アライメントも自動成功。

その確認の中でユーザーから、Gemini移行とは無関係の実バグを2件報告された：
1. **緑枠の向きが回転していても正しくOCRされる**（`GreenFrameDetector`が緑枠が3辺のみ・
   右辺なしという非対称形状から上下を自動判定する設計が意図通り機能。バグではなく仕様確認）
2. **フラッシュ設定オフの状態で撮影画面の「ライトボタン」を手動でONにすると、自動撮影後も
   消灯しない**（実バグ、修正済み・実機確認済み）

**原因**：`CameraScreen.kt`には`enableTorch(false)`の呼び出しが4箇所
（自動撮影トリガー時・手動撮影ボタン・`uiState`変化時・disposal時）あり一見冗長なほど
安全策があるが、**disposal時（`onDispose`）が`enableTorch(false)`は呼んでいても
`cameraProvider.unbindAll()`を呼んでいなかった**。`bindToLifecycle`はActivityの
ライフサイクルに紐づくため、`CameraScreen`（Compose側）が破棄されてもカメラデバイス自体は
バインドされたまま生き続ける。一部端末のカメラHAL実装ではトーチがカメラデバイスの
オープン状態に紐づいており、`enableTorch(false)`だけでは物理LEDが消えず、デバイスを
クローズ（`unbindAll()`）して初めて消灯する。実機（Motorola moto_g66j_5G）でこの挙動と
一致する症状が再現した。

**修正**：`cameraProvider`（`ProcessCameraProvider`）への参照を`remember`で外側スコープに
保持するよう変更し（従来は`AndroidView`の`factory`ラムダ内のローカル`val`のみで、
`onDispose`から参照できなかった）、`onDispose`で`enableTorch(false)`に続けて
`cameraProvider?.unbindAll()`を呼ぶよう修正。ローカル変数名は`boundProvider`に変更して
外側の状態変数`cameraProvider`との混同を回避した。

`./gradlew compileDebugKotlin`・`assembleDebug`・`adb install -r`で確認、実機で
「フラッシュ設定オフ→ライトボタンで手動ON→自動撮影→消灯確認」の手順を再現し、
修正後は正しく消灯することを確認済み（ユーザー確認済み：「消えるようになりました」）。

### 2026-08-11（続き7）：Phase6（ML Kit時代のJA伝票専用コード削除）を完了

Plan modeで調査・計画・ユーザー承認を経て実施。事前調査で当初の想定より大きな事実が判明した：
`ReceiptInputScreen.kt`の`onCapture`パラメータ（`Screen.OcrCapture`への遷移）はコード中
どこからも呼び出されておらず、`SheetEditorScreen`への唯一の入口（`OcrCaptureScreen`の
`onComplete`）もこの経路の先にしか存在しなかった。つまり**`OcrCaptureScreen`→
`SheetEditorScreen`の一群は本番UIから完全に到達不能**だった（過去セッションの
「再OCRボタンからのみ到達する副次画面」という認識も、実際にはさらに一歩踏み込んで
「その再OCRボタン自体への入口も無かった」という事実だった）。ユーザー判断で「再OCR」機能は
機能ごと削除、`DebugCaptureScreen`（`MenuScreen`から到達可能な現役画面）も削除に。

**削除したファイル**（22ファイル）：`ui/DebugCaptureScreen.kt`、`ui/OcrCaptureScreen.kt`、
`viewmodel/OcrCaptureViewModel.kt`、`ui/SheetEditorScreen.kt`、
`viewmodel/SheetEditorViewModel.kt`、`ui/OcrLearningStatusScreen.kt`、
`util/LearningDataExporter.kt`/`LearningDataImporter.kt`、`util/OCRProcessor.kt`、
`util/MultiScaleOcrProcessor.kt`、`util/ProductNameCorrectorV3/V2/(無印).kt`、
`util/ExplicitJoinMatcher.kt`、`data/CorrectionLog(Dao).kt`、`data/OcrScoreLog(Dao).kt`、
`data/OcrExplicitJoin(Dao).kt`。

**実装時の重要な発見・修正**：`OcrCaptureScreen.kt`を丸ごと削除しようとしたところ
`ReceiptInputScreen.kt`のコンパイルが破損した。同ファイル内の`CameraScreenForOcr`・
`OcrErrorScreen`の2つのcomposableは実は`ReceiptInputScreen.kt`のCameraViewが直接呼んでいる
**現役の共有UI**だったため、`ui/CameraScreenForOcr.kt`として新規ファイルに退避してから
残りを削除した。同様の理由で`CameraScreen.kt`・`CameraViewModel.kt`（今回のトーチバグを
修正した本体）も削除しなかった。`OcrCaptureViewModel`の`ParsedRow`・
`applyProductMasterCorrection()`・`mapGeminiResultToParsedRows()`等も
`ReceiptInputScreen.kt`が直接依存する共通処理だったため、`util/JaSheetOcrMapper.kt`
（新規object）に退避してから元ファイルを削除した。

**DB変更**：v27→v28（`MIGRATION_27_28`）で`correction_logs`・`ocr_score_logs`・
`ocr_explicit_joins`の3テーブルをDROP。**`ocr_variants`は削除しなかった**
（`docs/TASK_gemini_ocr_migration.md`の旧Phase6計画は誤ってこのテーブルも削除対象に
含めていたが、実際は`ReceiptInputScreen.kt`の手動補正学習・`OutputConfirmScreen.kt`の
FKフォールバック・`ProductListScreen.kt`/`SettingsScreen.kt`のツール群で現役使用中と判明し、
計画修正の上で除外した）。

**ドキュメント更新**：`docs/TASK_gemini_ocr_migration.md`（Phase6セクションを完了マーク・
`ocr_variants`誤記を訂正）、`docs/known-issues.md`、`docs/functional-design.md`
（Mermaid画面遷移図を更新）、`docs/repository-structure.md`、`docs/architecture.md`
（システムフロー図・DB設計・logcatコマンドをGemini版に更新）、`CLAUDE.md`
（DBバージョン表記・debugMode注記・ProductNameCorrectorV3節削除、いずれもプラン承認時に
ユーザー確認済み）。`docs/APP_SPECIFICATION.md`はArUcoマーカー時代の記述が大半で
Phase6と無関係に全面的に古かったため、個別の記述修正ではなく先頭に非推奨バナーを追加する
方針にとどめた。他10件（`glossary.md`・`OCR_SPEC.md`・`CORRECTION_SYSTEM.md`・
`DICTIONARY.md`・`OCR_LEARNING_REDESIGN.md`等）にも死んだクラスへの言及が残っているが、
計画のスコープ外のため今回は着手していない（次回以降の課題）。

**実機確認**：`./gradlew assembleDebug`→`adb install -r`で実データ（令和7年8月分等）入りの
端末にv27→v28マイグレーションを適用し無停止起動を確認。メニュー画面から「デバッグ撮影」
ボタンが消えたこと、設定画面から「OCR学習状況」項目が消えたこと、`ReceiptInputScreen`が
既存伝票データを正常に読み込み表示すること（`JaSheetOcrMapper`移設の影響なし）、一般レシート
撮影画面（ML Kit経路）が引き続き正常に開くことをスクリーンショット付きで確認。
クラッシュ・FATAL例外なし。

### 2026-08-11（続き5）：Phase5実装・実機確認、小計カテゴリの伝票間重複ブロックを追加（完了）
Phase4完了時点の残タスク（伝票削除の番号詰め・合計欄0円ブロック）の実機確認（下記「続き4」）に
続けて、Phase5に着手した。

**Phase5実装**：`GeminiReceiptClient.kt`に、伝票の行のY座標モデル（`docs/OCR_SPEC.md`の
Y座標定義を再利用。通常行・小計行56.5〜120.5mmを20行で均等分割、合計行は121.0〜135.0mmの
別ブロック）と`parseJaSheetPartial()`を新設。選択セルの行範囲だけをクロップした画像を送信し、
選択セル種別に応じて不要な呼び出し（取引日が未選択なら取引日列クロップ呼び出し自体）を省略する。
新設プロンプトは「空白行も省略せず行範囲と1:1で返す」ことを明示要求するが、既存の取引日列
クロップでも同種の指示が完全には守られなかった実績（2026-08-09発見のSUBTOTAL行省略）を踏まえ、
返却行数が期待値と不一致の場合は自動的に伝票全体再送信（既存Fullパス）へフォールバックする
設計とした。`ReceiptInputScreen.kt`（主導線のみ。副次画面`SheetEditorScreen`はセル選択の
概念がなく対象外と判断）に`OcrRunResult`（Full/Partial）を新設し、`CameraView.runOcr()`・
`onOcrComplete`をPartial対応に変更。設計はPlanモードでユーザー確認済み（画像を行範囲で
クロップする本格版を選択。不要呼び出しのみ省略する軽量版は不採用）。

**実機確認（5パターン）**：
1. セル未選択での撮影→従来通りFullパスで全体更新（回帰確認）
2. 1行の税込金額セルのみ選択→メイン部分呼び出しのみ発生（`needsDate=false`）、その金額だけ
   更新
3. 1行の取引日セルのみ選択→取引日列部分呼び出しのみ発生（`needsMain=false`）。直接モードで
   意図的に誤った日付に変更してから再OCRし、正しい日付に戻ることも確認
4. 離れた2行（例：3行目と9行目）を選択→間の行が変化しないことを確認（商品名を意図的に
   変えてから2箇所同時に再OCRし、両方正しく修正・間の行は無変化）
5. データ行＋合計行を同時選択→合計行はY座標が別ブロックのため混在ケースとして自動的に
   Fullパスにフォールバックすることを確認（ログで`partial re-OCR`ではなく通常の
   `date column re-aligned`ログのみが出ることで裏付け）

**検証中に発見した実際の事故と追加対応**：テスト4完了後、ユーザーが誤って同じ物理伝票を
「伝票追加」でもう1枚（2枚目）として再OCRしてしまい、小計が異常な形で合算される事故が
実際に発生した（合計欄は仕様通り2枚目では無視されるが、小計行はカテゴリごとに全伝票を
横断集計する設計のため、同じ小計が二重計上され破損した）。ユーザーの指摘で「この伝票は
月次請求明細書の1ページであり、同じ小計カテゴリ（一般購買/給油所/農業機械）は月に1回しか
出現しない仕様」と判明し、他の伝票（同じ月内）で既に検出済みの小計カテゴリと重複した場合、
既存の同一伝票内重複チェック（`ocrDuplicateSubtotalCategories`ダイアログ）と同じUIパターンで
保存前にブロックする機能を追加した。正当な複数枚パターン（同じ月に複数回買い物をした場合等）
との誤検知はない（月次明細の仕様上、小計行は月に1回しか印字されないため）。実機で実際に
同じ事故を再現し、正しくブロックされること・伝票データが破損しないことを確認済み。

`./gradlew compileDebugKotlin`・`assembleDebug`・`adb install -r`で確認、実機の本番導線で
上記すべて確認済み。

### 2026-08-11（続き4）：実機未確認だった安全性チェック2件を確認（完了）
令和7年12月（空月）でテストデータを作成し、Claude Code（Agent）とユーザーの協働で確認した
（Agentが手順を指示、ユーザーが実機操作とダイアログ内容の報告を担当）。

1. **伝票削除を最後の伝票のみに制限した変更の実削除動作**：直接入力モードで3枚の伝票を
   作成し、各1行目の税込金額に目印（111／222／333）を入力。3枚目（最後）で「伝票削除」→
   確認ダイアログ「3枚目の伝票を削除しますか？」→「削除」を実行し、カウンターが
   「3/3」→「2/2」に正しく更新されることを確認。1枚目（111）・2枚目（222）のデータは
   破損せず保持されていた。「伝票の削除は最後（N枚目）のみ可能です。」の説明文もNの値に
   追従して更新されることも確認。
2. **合計欄が0（未検出）でもブロックする変更**：1行目に取引日・商品名・税込金額（1000円）
   を入力し、2行目を小計行（一般購買、1000円＝1行目と一致）にして、21行目の「■　合計」欄
   だけを空欄（0円）のまま残して「決定」を押下。「小計・合計が一致していません」ダイアログ
   が表示され保存がブロックされることを確認（小計自体は一致しているため、合計欄0円のみが
   ブロック理由であることを分離して確認できた）。

いずれもテストデータは「クリア」→伝票削除で後始末し、令和7年12月を0/0の空状態に復元済み。
実データへの影響なし。これでPhase4完了時点の残タスクはすべて実機確認が取れた。

### 2026-08-11：productMasterId FK照合を実機のGemini API実行込みで検証（完了）
実際のJA伝票を本番導線（伝票データ→伝票追加→撮影）で撮影・保存し、`receipt_items`に
17件保存、うち12件が`productMasterId`で購買品マスタに正しく紐づいたことを確認した。
小計・合計行3件は仕様通り紐づけ対象外、未登録商品2件（「メリット(青)1kg」
「ベビーポンプ」）は未紐づけ（想定通り）。**特に`id=1`「レギュラーガソリン」が
`productMasterId=1`に正しく紐づいたことを確認**：2026-08-10に修復した`canonicalKey`
破損が実際のGemini OCR結果からの表記統一・FK紐づけで正しく機能することも合わせて実証できた。

**ハマった点（次回のadb DB確認で同じ手戻りをしないための記録）**：Room はデフォルトで
WAL（Write-Ahead Logging）モードのため、直近の書き込みは`receipt_database`本体ではなく
`receipt_database-wal`ファイルに残る。`adb exec-out run-as ... cat databases/receipt_database`
だけをpullすると**直近の保存内容が反映されず「0件」に見えてしまう**（今回実際に誤検出した）。
正しくは`receipt_database`・`-wal`・`-shm`の3ファイルを同じベース名で揃えてpullすること
（sqlite3が自動的にWALを適用する）。

**この検証と同じセッションで別件対応**：商品名エディタの文字数制限を全角20→30文字に緩和、
半角奇数チェックを撤廃（ユーザー判断、詳細は下記「2026-08-10（続き）」参照）。実装直後に
実機で「決定」を押すと旧チェックによる半角文字エラーが発生したが、これは**コード修正後に
実機へ再ビルド・再インストールしていなかったこと**が原因だった（`compileDebugKotlin`のみで
`assembleDebug`＋`adb install`をしていなかった）。再ビルド・再インストール後は正常に保存できた。

### 2026-08-09（続き13）：ReceiptItemにproductMasterId FK列を追加、CSV生成をFK経由に
前回（続き12）のcanonicalKey照合実装について、ユーザーから3点の鋭い指摘を受けて発展させた：
①事前計算列を使わず毎回再計算していた、②購買品リストで商品名を変えたら過去データも
自動的に追随すべきでは、③商品名は文字列かFKか。調査の結果、**JA購買伝票（ReceiptItem）
だけがこのアプリの中で例外的にFK化されていなかった**ことが判明した（預金`DepositMeisai`は
`matchingRuleId`/`overrideTekiyouId`/`overrideYayoiAccountId`、一般レシート
`GeneralReceiptItem`は`tekiyouId`/`yayoiAccountId`で、どちらも既にFK直結）。さらに
`OutputConfirmScreen.kt`のCSV生成処理が`productMasterDao.getByName(item.productName)`という
**完全一致文字列マッチング**で勘定科目/摘要を引いており、表記ゆれで一致しないと**黙って
空欄になる**という実害のあるバグを発見した。

これを受けて縮小版FK化を実装（`productName`はそのまま維持、`productMasterId: Long?`を
追加するのみ）：
- `ReceiptItem`に`productMasterId: Long?`追加、`MIGRATION_26_27`（v26→v27）で列追加＋
  `MIGRATION_16_17`と同じカーソル走査パターンで過去データを`canonicalKey`一致でバックフィル
- `OcrCaptureViewModel.ParsedRow`に`productMasterId`追加、`applyProductMasterCorrection()`が
  一致時に`productMasterId`もセットするよう拡張
- `ReceiptInputScreen.kt`：`convertParsedRowsToRowData()`・`convertReceiptItemsToRows()`・
  `saveMonthData()`・部分再OCR（セル選択→再撮影）のマージ処理、すべてに`productMasterId`を
  伝搬
- `OutputConfirmScreen.kt`の`loadPurchaseOutputItems()`を`item.productMasterId`優先の
  ルックアップに変更（未紐づけの過去データのみ従来の文字列一致にフォールバック）

実機で v26→v27 マイグレーション（列追加＋カーソル走査）がクラッシュしないことを確認。
DBを直接pull（`adb exec-out run-as ... cat databases/receipt_database`）してsqlite3で検証：
`product_master`は103件中102件`canonicalKey`が正常だが、**`id=1`「レギュラーガソリン」の
`canonicalKey`が空文字列**であることを発見。今回修正した「編集保存でcanonicalKeyがリセット
される」バグの実害の実例（このバグ自体より前に発生した破損）。次回、購買品リストで開いて
保存し直すことで修復可能（`.withComputedKey()`が正しく呼ばれるようになったため）。
`receipt_items`は毎回テストデータを破棄していたため0件で、バックフィルの実データ検証は
できなかった。

`./gradlew compileDebugKotlin`・`assembleDebug`でビルド確認、実機インストール・起動確認済み。

### 2026-08-09（続き12）：Gemini OCR結果の商品名を購買品リストと照合して表記統一
ユーザーから「Geminiの読み取り結果は全角/半角スペースを区別できないが、商品名の同一判定を
どう扱うか」という質問を受け、調査の上で実装した。

**背景**：OCRはスペース幅を視覚的に判別できない（ML Kit・Gemini問わずOCR全般の構造的限界）。
ML Kit時代は`ProductNameCorrectorV3.normalizeForCompare()`が比較前に全角/半角スペースを
問答無用で除去していたが、この学習システム（`ocr_variants`等）はPhase6で削除予定であり、
Gemini経路には現状この種の正規化が一切なかった（`OcrCaptureViewModel`・
`GeminiReceiptClient`のどこも`productMasterDao`を呼んでいなかった）。

**実装**：`OcrCaptureViewModel.applyProductMasterCorrection()`を新設。GeminiのNORMAL行の
商品名を`toCanonicalKey()`で正規化し、`product_master.canonicalKey`（既存の事前計算列）と
照合、一致すれば登録済みの`canonicalName`にその場で差し替える（SUBTOTAL/MONTHLY_TOTAL行は
対象外）。主導線`ReceiptInputScreen.kt`のCameraViewと副次画面`OcrCaptureScreen`の両方から
呼ばれるよう、`OcrCaptureViewModel`・`OcrCaptureViewModelFactory`・`Navigation.kt`に
`productMasterDao`を配線。処理コストは無視できるレベル（product_master現在103件、DB読込＋
103件のマップ構築＋行数分のハッシュ参照で数ミリ秒。Gemini API呼び出し自体が4〜20秒かかる
のに対して誤差）。

**ユーザー指摘で発見した既存バグ2件（今回の実装とは独立、修正済み）**：
1. `product_master.canonicalKey`は元々事前計算列として存在していた（MIGRATION_16_17）のに、
   最初の実装ではそれを使わず毎回`toCanonicalKey()`を再計算していた。既存列を直接読む方式に修正
2. `ProductListScreen.kt`の`ProductEditDialog`保存処理（商品名編集時）が`.withComputedKey()`を
   呼んでおらず、**商品名を編集するたびに`canonicalKey`が空文字列にリセットされていた**
   （複数の編集済み商品が`""`キーで衝突し、OCR照合が誤爆しうる状態だった）。`.withComputedKey()`
   呼び出しを追加して修正

**確認できていた既存機能**：`ProductListScreen`で商品名をリネームすると
`receiptDao().updateProductNameInReceiptItems(oldName, newName)`で過去の伝票データにも
遡って反映される。ただし完全一致文字列でのUPDATEのため、表記ゆれのある過去データ
（今回の照合機能導入前に登録された分）までは追随しない。

**設計判断（ユーザー確認済み）**：`ReceiptItem.productName`は現在プレーン文字列で、
`product_master.id`へのFK列は存在しない（`ReceiptRowData.productMasterId`はUI内にあるが
`saveMonthData()`で捨てられている）。FK化で表記ゆれ問題を構造的に解消する案も検討したが、
過去データ移行・全参照箇所の書き換え・SUBTOTAL/TOTAL行の特別扱いが必要な大規模リファクタと
判断し、**今回は見送り、文字列＋canonicalKey照合の継続を採用**。Gemini移行が一段落してから
改めて検討する。

`./gradlew compileDebugKotlin`・`assembleDebug`でビルド確認、実機にインストールして
正常起動を確認済み。実際にGemini APIを呼んでの照合動作確認（表記統一が実際に働くか）は
コスト面から今回は未実施。

### 2026-08-09（続き11）：dateColumnAligned=false警告を実機確認（完了、実際に発生）
ユーザーが機内モードを解除しネットワーク復旧後、同じ紙のJA伝票（令和7年11月分）を
本番導線で再撮影。**1回目の実撮影で偶然`dateColumnAligned=false`が発生し、新設した
「取引日をご確認ください」ダイアログが実機で正しく表示された**（意図的な誘発ではなく
自然発生）。商品名・金額はすべて正確に読み取れていた（グレーシア乳剤250ml→11,770円 等、
紙面と一致）。「確認しました」ボタンでダイアログを閉じ、グリッドは正常に編集可能な状態を
維持。テストデータは「キャンセル」で破棄し、0/0の状態に復元（実データへの影響なし）。
これでPhase4の残タスクのうち`dateColumnAligned=false`対応も実機で発火することを確認できた。

**訂正（ユーザー指摘）**：確認直後、表示された取引日が「08/01/xx」（令和8年1月）で
伝票の実際の印字「令和7年11月」とずれていたことを「既知の月ずれ誤読を実際に検出できた
証拠」と記録したが、これは誤りだった。撮影時に「年月固定」チェックボックスがONだった
ため、`ReceiptInputScreen.kt`の`convertParsedRowsToRowData()`内`fixYearMonth`処理により
Geminiが返した年月を無視して日にちだけを取り出し、年月は常に画面選択中の「令和8年1月」に
強制上書きする仕様が正しく動作していただけ（該当コード：`fixYearMonth && digits.length >= 2
-> "%02d/%02d/%02d".format(defaultYear % 100, defaultMonth, day)`）。`dateColumnAligned=false`
ダイアログ自体が発火した事実（Geminiの2回のAPI呼び出し間で行数が不一致だったという別の
内部シグナル）は変わらないが、「表示日付のずれ」をその根拠として結び付けたのは誤り。
Geminiが実際に返した生の取引日（年月）はこのテストでは確認できていない。

### 2026-08-09（続き10）：dateColumnAligned=false時のUI警告表示を実装
これまで`dateColumnAligned=false`（取引日列クロップと全体画像の行数不一致で再アラインメント
できなかった場合）はログ出力のみで、`OcrCaptureViewModel`・`ReceiptInputScreen.kt`
どちらの経路でもUIに一切表示されていなかった。以下を実装：
- `OcrCaptureViewModel.CaptureStep.Complete`に`dateColumnAligned: Boolean = true`を追加し、
  `OcrCaptureScreen.kt`の`CompleteScreen`に警告バナー（`TransformPreviewScreen`と同じ
  tertiaryContainer・Warningアイコンの意匠）を追加
- 主導線`ReceiptInputScreen.kt`の`CameraView`の`onOcrComplete`コールバックに
  `Boolean`（dateColumnAligned）パラメータを追加し、falseの場合は伝票データ画面に
  「取引日をご確認ください」ダイアログを表示（強制ブロックはしない、確認促しのみ）
- 検算不一致ブロックと違い、これは「疑わしいが誤りと確定していない」シグナルのため、
  `ocrConfidence == "low"`バッジと同じ「警告するが止めない」方針を踏襲

### 2026-08-09（続き9）：実機でOcrErrorScreenの失敗パターンを動作確認（完了）
ユーザーが機内モードを意図的にONにした状態で、本番の撮影導線（伝票データ→編集→撮影）から
実際の紙のJA伝票にカメラを向けて撮影。ネットワーク接続不可により
`Unable to resolve host "generativelanguage.googleapis.com"`のIOExceptionが発生し、
`OcrErrorScreen`が正しく表示されることを確認：
- エラーメッセージ（実際の例外内容）がカードに表示される
- 「再試行（同じ画像で送信）」→ 撮り直しなしで同じ画像のまま再送信 → 同じエラーで
  `OcrErrorScreen`に戻ることを確認（撮影済み画像を破棄しない設計が意図通り機能）
- 「撮り直す」→ ライブカメラ画面に戻ることを確認（その後自動撮影が走り再度失敗→
  `OcrErrorScreen`に戻る一連の流れも確認）
- 「キャンセル」→ 編集画面に戻ることを確認
テスト用に追加した伝票は「クリア」→「キャンセル」で破棄し、0/0の状態に復元。実データへの
影響なし。これでPhase2「API失敗時の専用エラーUI・再試行ボタン」が実機で完全に動作確認できた。

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

### 2026-08-10：id=1「レギュラーガソリン」のcanonicalKey破損を修復（完了）
コード調査の結果、`.withComputedKey()`未呼び出しの根本バグ自体は既存コードで修正済みと確認
（`ProductListScreen.kt`の`ProductEditDialog`保存処理1132行目、`DatabaseInitializer.kt`、
`LearningDataImporter.kt`いずれも呼び出し済み）。`MIGRATION_26_27`のバックフィルも
`canonicalKey != ''`条件で空キー行を除外する設計のため、他データへの波及もなし。
実機の購買品リストで「レギュラーガソリン」を開いて保存し直す手順で修復し、`adb exec-out
run-as`でDBを読み取り専用pullしてsqlite3で検証：`id=1`の`canonicalKey`が
`"レギュラーガソリン"`に正しく再計算されたこと、`product_master`全103件中`canonicalKey`が
空/NULLの行が0件（他に同様の破損なし）であることを確認済み。

### 2026-08-10（続き）：商品名文字数制限を20→30文字に緩和、半角奇数チェックを撤廃（Gemini移行とは別件）
ユーザー判断：全角20文字制限は伝票印字の実際の最大文字数に由来するものだったが、伝票と完全に
同じ記述にする必要はないため30文字に緩和。半角文字数の奇数チェック（kg/cm等の単位が2文字
ペアになっていることを強制する仕組み）は不要と判断し撤廃した。
- `ProductNameInputUtils.kt`：`truncateToFullWidthLimit`のデフォルト上限を20.0→30.0
- `ProductListScreen.kt`：`ProductEditDialog`の`fwMax`を20.0→30.0、ラベル文言更新
- `ReceiptInputScreen.kt`（主導線）：`halfWidthOddRows`state・警告ダイアログ・「決定」ボタンでの
  ブロックチェック・セル編集ダイアログの`productHalfWidthOdd`関連ロジックをすべて削除。
  文字数カウンター表示を`/20`→`/30`に変更
- `SheetEditorScreen.kt`（副次画面）：同様に`hasHalfWidthOdd`関連ロジックを削除、`/20`→`/30`に変更
  （主導線・副次画面の両方に同じ変更が必要という既知のパターンを踏襲）
- `docs/development-guidelines.md`の商品名文字種ルールを更新
`./gradlew compileDebugKotlin`でコンパイル確認済み。実機での動作確認は未実施。

### 2026-08-11（続き）：要確認行の一覧・一括確認モードを実装（Phase4残タスク完了）
`ReceiptInputScreen.kt`（主導線）に実装。試行錯誤があったため経緯も含めて記録する。

**実装内容**：
- `reviewRows`：全伝票から`ocrConfidence=="low"`の行を`(sheetNum, index, row)`で抽出する
  `remember(allSheetsData)`計算値
- 「要確認一覧を見る（N件）」ボタン（EDIT モードかつ`reviewRows`が1件以上の時だけ表示）→
  タップで一覧ダイアログを表示。各行をタップすると`currentSheetNumber`と`selectedRowIndex`を
  セットし、該当伝票・該当行にジャンプ＋選択状態にする
- セル編集ダイアログ（取引日・商品名・税込金額）で「決定」を押すと、その行の`ocrConfidence`を
  `null`にクリアする（3箇所の`onConfirm(currentRow.copy(...))`に`ocrConfidence = null`を追加）。
  `SheetEditorScreen.kt`（副次画面）の`ItemEditDialog`にも同様に追加
- **ユーザー指摘で設計変更**：「セルを開かないと解除されないのは、確信度が低くても実際は
  合っていた場合に面倒」との指摘を受け、「決定」ボタン押下時に要確認行が残っていれば
  ブロックせず「確信度の低い行があります」ダイアログを表示し、「確認して保存」を押すと
  残っている要確認行をまとめて確認済み（`ocrConfidence=null`）にしてから保存する方式に変更。
  保存処理を`performSave`ローカル関数に切り出し、通常の決定パスとこのダイアログの両方から
  呼べるようにした

**実機確認で見つかった不具合と修正**：
1. 要確認一覧の項目をタップしても何も変わらないように見えた → 原因はテストデータが
   1伝票のみでsheetNumber変更が視覚的に分からなかっただけだったが、ついでに
   `selectedRowIndex`も同時にセットして該当行をハイライトするよう改善した
2. 「確認して保存」後も行の背景色（選択ハイライト）が消えない → `performSave`が
   `selectedRowIndex`をリセットしていなかったバグ。`viewMode = ViewMode.VIEW`と同時に
   `selectedRowIndex = -1`を追加して修正
3. 選択中の行の背景色が小計行の薄緑（`0xFFE8F5E9`）と紛らわしい → 選択色に
   `MaterialTheme.colorScheme.primaryContainer`（テーマ依存）を使っていたため、現在の
   緑系テーマだと小計行とほぼ同じ色になっていた。テーマに依存しない固定の水色
   `Color(0xFFBBDEFB)`に変更

**動作確認方法（メモ）**：実データに`ocrConfidence=="low"`の行がなかったため、
`adb shell am force-stop`→DB+WAL+SHMをpull→ローカルsqlite3で`PRAGMA journal_mode=DELETE`で
チェックポイント後に対象行を`UPDATE`→単一ファイルをpush→端末側の`-wal`/`-shm`を削除→
アプリ再起動、という手順でテスト用に`low`を注入して確認した（DBファイルを直接pushする際は
`MSYS_NO_PATHCONV=1`を絶対パスを含む**すべての**adbコマンドに付けないと、Git Bashが
`/data/...`を`C:/Program Files/Git/data/...`のようなWindowsパスに誤変換してしまう点に注意）。
最終的に実機で「確認して保存」を実行したところ、実際に`ocrConfidence`がnullにクリアされる
ことをDBで確認できた。テストで使った行は実際のOCR結果（商品名・金額は本物）のため、
DBの復元は不要だった。

### 2026-08-11（続き2）：複数枚パターンの安定性確認で発見した「未定」カテゴリの未ブロック問題を修正
Phase4の残タスク「複数枚・複数パターンでの安定性継続確認」の一環で、返品行・複数ページ・
小計なしパターンを含む実伝票を撮影してDBを確認したところ、`category`が`"未定"`のまま
保存されている行を発見した。

**原因**：`ReceiptRowData.category`の割り当ては「この行より後ろで最初に見つかる小計行の
カテゴリを継承する」方式（`ReceiptInputScreen.kt`の`recalculateCategoriesInMemory()`）。
月内のどの伝票（何枚目でも可）を探しても該当する小計行が1つも見つからない場合、
`"未定"`という暫定値になる。ところが「決定」ボタンの未分類ブロックチェックは
`category == "未分類"`という別の文字列だけを見ており、`"未定"`はすり抜けて保存できてしまう
バグがあった。

**実例**：令和7年7月分の伝票で、給油所の4行（灯油・レギュラーガソリン×3）が撮り忘れていた
2枚目（給油所の小計のみが印字されたページ）のせいで`"未定"`のまま保存されていた。CSV出力は
`productMasterId`経由のFKルックアップを使うため直接は壊れないことをコードで確認したが
（`OutputConfirmScreen.kt`の`loadPurchaseOutputItems()`）、そもそも2枚目を撮り忘れている
という実害の大きいミスだったため、ユーザー判断で「小計は必ず伝票に印字されているはずなので、
決定時にエラー・警告を出すべき」という方針になった。

**修正**：`showUndeterminedCategoryDialog`を新設し、「決定」ボタン押下時に
`category == "未定"`の行が残っていれば、既存の未分類ブロックと同様に保存をブロックする
専用ダイアログ（「小計行が見つからない行があります」、2枚目以降の撮り忘れの可能性を明示）を
表示するようにした。

**実機確認**：修正後、実際に撮り忘れていた2枚目（給油所の小計のみのページ）を撮影・追加して
再度「決定」を押したところ、該当4行の`category`が`"未定"`→`"給油所"`に正しく解決され、
2枚目の小計（22,887円）・合計（86,621円＝一般購買小計63,734＋給油所小計22,887）とも
実際の行合計と完全一致することをDBで確認した。壊れていた実データも合わせて修復できた。

**副次的な発見（対応不要と判断）**：同じ検証中に、返品行（購入17,160円→返品-17,160円の
ペア）・複雑な商品名（長い漢字・記号混じり）・複数ページ伝票（1枚目に印字される「合計」が
実は全ページ分の最終合計になっているJAフォーマット）はいずれも正しく読み取り・保存できて
いることを確認した。また「複数ページのうち1枚だけ撮って決定を押す」ケースは、1枚目の
「合計」欄が非ゼロで読み取れている限り、既存の`hasUnresolvedMismatch()`（合計不一致検算）が
既に検出・ブロックする設計になっていることをコードで確認した（1枚目の合計欄自体が
読み取れなかった＝`enteredTotal==0`の場合のみ、既存の意図的な設計判断でブロック対象外）。

### 2026-08-11（続き3）：保存時の安全性チェックを4件追加・強化（ユーザーフィードバック起点）
「未定」カテゴリのブロック実装後、ユーザーとの対話で立て続けに関連する安全性ギャップが
見つかり、その場で対応した。

1. **合計欄が0（未検出）のケースもブロック対象に**：`hasUnresolvedMismatch()`の合計チェックから
   `total.enteredTotal != 0`という除外条件を削除。ユーザーが「1枚目には必ず合計が印字されている」
   と明言したため、合計欄が読み取れていない（0のまま）こと自体が読み取り漏れ・撮り忘れの
   サインとみなせると判断。カテゴリ別小計側の`enteredSubtotal != 0`除外は維持（こちらは
   小計行自体が印字されないカテゴリが実在するため）。
2. **欠損行チェックを新設**：税込金額が入力されているのに取引日または商品名が空欄の行を
   `incompleteRows`として抽出し、「決定」ボタンの最初のチェックとしてブロック（該当行を
   伝票番号・行番号・欠けている項目付きで一覧表示する専用ダイアログ`showIncompleteRowDialog`）。
   実機で直接入力モードにより意図的に空欄行を作成しブロックされることを確認済み。
3. **「未定」行を編集中にも可視化**：「小計・合計の検証」カードに、`category=="未定"`の行が
   あれば「⚠ カテゴリ未定（N行、小計行が見つからない）」という警告行と金額を表示するように
   した（既存の「未分類」警告と同じ意匠）。従来は`validateAllSheetsData()`が`"未定"`を
   どのカテゴリ集計にも一切カウントしない設計（ユーザー確認済み：一般購買等への誤集計は
   ない）だったため、決定を押す前は何も見えず原因が分かりにくかった問題を解消。カード全体の
   背景色も未定行がある場合はエラー色になるよう条件を追加。
4. **伝票削除を最後の伝票のみに制限**：複数枚の月で途中の伝票（例：3枚中1枚目）を削除すると
   後続が自動繰り上げされ、物理ページと伝票番号の対応が分からなくなる問題をユーザーが指摘。
   插入機能の追加 vs 削除を最後のみに制限、の2案を提示し、後者（よりシンプルで根本的に
   ズレが起きない）を採用。「伝票削除」ボタンの`enabled`条件に
   `currentSheetNumber == totalSheets`を追加し、最後以外の伝票を表示中は無効化＋
   「伝票の削除は最後（N枚目）のみ可能です。」という説明文を表示。
   - **実装ミスと修正**：説明文の`Text`を、伝票追加・クリア・伝票削除の3ボタンを横並びする
     `Row`の**内側**（3ボタンと同じ階層の4つ目の子）に置いてしまい、weight(1f)の3ボタンの
     横並びレイアウトが崩れる不具合を実機フィードバックで発見。`Row`の外（親の`Column`側）に
     移動して修正・実機確認済み。

いずれも`./gradlew compileDebugKotlin`・`assembleDebug`でビルド確認、実機インストール・
動作確認済み（1・3・4は実機確認済み、2は空欄行ブロックのみ確認、合計0ブロックの実機再現は
未実施）。

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
