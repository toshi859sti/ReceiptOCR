# TASK_gemini_ocr_migration.md

## 作業タイトル
JA購買伝票OCRパイプラインのML Kit → Gemini Vision API移行

## 目的・背景

現行の購買伝票OCRパイプライン（GreenFrameDetector → ML Kit → ProductNameCorrectorV3）は、
ML Kit日本語モデルの精度が天井に達している（CLAHE/UnsharpMaskで悪化する事例あり、複雑な漢字は
sharpness≥1000でも認識不可）。Gemini Vision APIへの移行により、意味的推論を用いた読み取り精度の
向上と、補正ロジック（ProductNameCorrectorV3・ExplicitJoinMatcher等）の大幅な簡素化を図る。

アプリはJA組合員限定の閉じた配布であり、オフライン動作は不要。API呼び出しはAndroidアプリから
直接行う設計とする（バックエンドプロキシは現段階では不要、将来の配布範囲拡大時に再検討）。

**重要な前提**：Gemini 2.5 Flashは2026年10月16日に廃止予定。実装時点で利用可能な後継モデル
（Gemini 3 Flash または 3.1 Flash-Lite 等）を確認の上、採用すること。

**データ利用に関する前提（2026-08-07 決定）**：Gemini APIは無料枠（無課金キー）だと入出力が
Googleのモデル改善に利用され、人間レビュアーが閲覧し得る。有料枠（課金有効化キー）は学習に
利用されず、ログも安全性・不正利用検知・法令対応目的で一定期間のみ保持される。JA伝票は
組合員の仕入・取引先・金額という経営情報そのものであり一般レシートより機微度が高いため、
JA伝票OCRについては**課金有効化キーの利用を前提とし、注意文言で組合員に周知する**方針とする
（アプリ側で課金有無を検知するのは非現実的なため強制はしない。詳細はPhase2参照）。

---

## 全体方針

### 維持する資産
- `GreenFrameDetector`（緑枠検出・透視変換）：**そのまま維持**。出力解像度 3045×2220px
  （WARP_PX_PER_MM=15.0）は変更不要。この解像度は結果的にGemini向けにも十分な品質。
- 撮影品質ゲート（フォーカス評価・鮮鋭度リアルタイム表示・輝度チェック）：そのまま維持
- 小計/合計との検算バリデーション：そのまま維持・強化（金額誤りの機械的検出は必須の安全網）
- `SheetEditorScreen` / `ItemEditDialog` の手動編集フロー：そのまま維持
- 商品マスタ（`product_master`）・買掛摘要辞書（`rakuraku_tekiyou`）：維持し、Geminiの
  プロンプトコンテキストとして活用する

### 廃止する資産
- `OCRProcessor.kt` のML Kit呼び出し部分（全体OCR・列特化OCR）
- `ProductNameCorrectorV3.kt`（3層スコアリング補正）
- `ExplicitJoinMatcher.kt`（分離テキスト結合学習）
- Step7（行切り抜き）・Step8（二値化・グレーチャンネル前処理）
- 列ROI個別切り出し（数量列・商品名列の個別OCR）→ 表全体を1枚の画像としてGeminiに渡す
- `OcrLearningStatusScreen`・`DebugCaptureScreen`（画面ごと削除）
- `ocr_variants`・`ocr_score_logs`・`correction_logs`・`ocr_explicit_joins` テーブル
  （学習パラダイム自体が不要になるため。マイグレーションでDROP）

  **削除タイミングの方針（2026-08-07 決定）**：上記コードの物理削除はPhase6にまとめて
  実施するが、Gemini移行を本番投入した直後には行わない。本番で一定期間（安定稼働の確認が
  取れるまで）運用し、問題が出ないことを確認してから削除する。ML Kit経路をユーザー向け
  トグルとして併存させることはしない（確認画面・confidence表示・再OCR UIまで二重に
  作ることになり、本移行の目的である簡素化と矛盾するため）。ロールバックが必要な場合は
  Git履歴から復元する。

### 新規追加する資産
- 透視変換後の画像確認画面（`TransformPreviewScreen`）
- Gemini API呼び出し：新規クラスは作らず、**既存 `util/GeminiReceiptClient.kt` を拡張**する
  （2026-08-07 決定・詳細はPhase2参照）
- API呼び出し中のローディング/エラー/リトライUI（既存の`GeminiRateLimitException`等の
  エラー分類・`AiUsageStats`を再利用）
- 行ごとの confidence（自信度）表示・要確認バッジ
- 部分クロップ再送信による再OCR機能
- warpedBitmapの一時永続化（内部ストレージ、確定後削除）

---

## Phase 0：スパイクテスト（実装前の精度検証）（2026-08-07 追加）

### 背景
ML Kitは文字高さ100px以下で精度が急落する制約があった。Gemini Visionでも密な表組み・
小さいフォントの数量列で同様の限界がないか、UI・DB基盤を作り込む前に検証する。ここで
精度が不十分と分かれば、Phase1以降の設計（列ROIを個別送信する形に戻す等）に影響するため
最優先で行う。

### タスク
- [x] 既存の実機確認済み `warpedBitmap`（3045×2220px）サンプルを数枚用意
      （2026-08-08：1枚で実施。追加サンプルは次回以降の余力があれば）
- [x] Phase3で検討中のプロンプト（列定義・JSON構造指定）の素案を使い、手動スクリプトまたは
      デバッグ経由で実際にGemini Vision APIへ送信し、レスポンスを確認（2026-08-08実施）
- [x] 商品名列（複雑な漢字）の読み取り精度をML Kit時代の既知の誤読サンプルと比較する
      （2026-08-08：全モデルで商品名・取引日・税込金額・分類計は正解。詳細は進捗メモ参照）
- [x] 後継モデル（gemini-3.6-flash / gemini-3.5-flash-lite）候補で速度・精度・コストを比較
      （2026-08-08実施。数量列を除いた再比較が次回タスク）
- [x] 送信画像リサイズ幅（2,000〜2,500px）を変えて精度への影響を確認
      （2026-08-08：有意差なし。2000pxを既定候補とする）
- [x] 検証結果を本ドキュメントの「進捗メモ」に記録（2026-08-08）
- [x] 数量列を評価対象から除外した上で `gemini-3.5-flash-lite` を再検証（2026-08-08：
      商品名・金額・小計・合計は4サンプル完全正解。取引日列のみ不安定 → 列クロップTwo-Pass
      方式で対策し完全安定を確認。詳細は上記「続き3」参照）。速度優先で`flash-lite`採用が
      確定

---

## Phase 1：透視変換後の確認フロー

### 背景
Gemini移行によりOCR失敗のコストが変化する（API呼び出し1回＝コスト発生）。歪んだ画像を
そのまま送信すると、Geminiは意味的推論で「それらしい」誤読を自信満々に返す可能性があるため、
送信前のゲートを強化する。

### タスク
- [x] `GreenFrameDetector.DetectionResult` に含まれる `dewarpedBitmap` を表示する
      `TransformPreviewScreen` を新設（2026-08-08実装）
- [x] 自動判定ロジック `GreenFrameDetector.isValidShape()` を新規実装（当初「既存」と
      想定していたが未実装だったため今回新設）。コーナー内角4点が90°から±1.5°以内かで
      判定（`DebugCaptureScreen`のデバッグ表示閾値と同じ基準を採用）。基準を満たす場合は
      確認画面を自動スキップし、そのまま送信フローへ進む
- [x] 基準を満たさない場合のみ確認画面を表示し、「送信する」「撮り直す」の2択を提示
- [x] 「撮り直す」選択時は`OcrCaptureViewModel.retryFromPreview()`でカメラへ戻る。
      `sheetNumber`はViewModelのプロパティのまま保持されるため連続撮影でも維持される
- [x] 初期実装は「OK / 撮り直し」の2択のみ（四隅の手動調整UIは今回のスコープ外、
      将来拡張として `CURRENT_TASK.md` に記録）

### Navigation（実装済み）
```
OcrCaptureScreen（CaptureStep.Capturing）
  → CameraScreenForOcr → viewModel.onDetectionResult(result)
    → [isValidShape() OK] → processDetectionResult() → Processing → Complete
    → [isValidShape() NG] → CaptureStep.Preview → TransformPreviewScreen
        → 送信する → processDetectionResult() → Processing → Complete
        → 撮り直す → retryFromPreview() → Capturing
```
`TransformPreviewScreen`は独立したNavigationルートではなく、`OcrCaptureScreen`内の
`CaptureStep`状態遷移として実装（`Navigation.kt`への新規ルート登録は不要だった）。

### 実装ファイル（2026-08-08）
- `util/GreenFrameDetector.kt`：`isValidShape()`・`MAX_CORNER_ANGLE_DEVIATION`定数を追加
- `viewmodel/OcrCaptureViewModel.kt`：`CaptureStep.Preview`・`onDetectionResult()`・
  `retryFromPreview()`を追加
- `ui/TransformPreviewScreen.kt`（新規）：確認画面のUI
- `ui/OcrCaptureScreen.kt`：`onOcrComplete`の呼び先を`onDetectionResult`に変更、
  `Preview`状態のUI分岐を追加

`./gradlew compileDebugKotlin`でコンパイル確認済み（BUILD SUCCESSFUL、新規警告なし）。

### 2026-08-09：実機確認 → 本番の撮影導線が想定と違うことが判明・修正

実機確認したところ、`isValidShape()`の判定ログ（`OcrCaptureViewModel`）が撮影のたびに
一度も出力されないという問題が発生。調査の結果、**実際にユーザーが使う撮影導線
（JA購買伝票→伝票データ→編集→伝票追加→撮影）は`OcrCaptureScreen`/`OcrCaptureViewModel`
を一切経由しない**ことが判明した。`ReceiptInputScreen.kt`内に完全に別実装の非公開
`CameraView`コンポーザブルが存在し、`CameraScreenForOcr`を直接呼び出して独自に
`OCRProcessor.processUnderlayingBase()`を呼んでいた（`Screen.OcrCapture`ルートは
`SheetEditorScreen`の「再OCR」ボタンからのみ到達可能で、実質的に主動線ではなかった）。
ビルド警告「Parameter 'onCapture' is never used」がこれを裏付けた。

**対応**：`ReceiptInputScreen.kt`の`CameraView`にも同じ`isValidShape()`チェック＋
`TransformPreviewScreen`表示ロジックを追加。`OcrCaptureViewModel`側の実装はそのまま
（`SheetEditorScreen`の再OCR導線用に維持）。

追加で、送信時に一瞬ライブカメラ映像が見えてしまう不具合を発見・修正（`previewDetectionResult`
をnullにすると一瞬`CameraScreenForOcr`側に戻り、内部の`cameraViewModel.resetToPreview()`が
走ってしまうため）。`isProcessingOcr`を最優先の分岐にして、OCR処理中は常にスピナーを
表示するよう修正。

#### 実機確認結果（修正後）
- 通常撮影（コーナー角度ほぼ90°）→ 確認画面は自動スキップ ✅
- 意図的に傾けた撮影（コーナー角度ズレ2.1°、閾値1.5°超）→ 確認画面が表示 ✅
- 「撮り直す」→ カメラに戻る ✅
- 「送信する」→ カメラのちらつきなくOCR処理へ進む ✅（修正後）
- 通常撮影（自動スキップ）でのOCR結果：取引日誤読・小計（一般購買）取得漏れ・
  合計金額の罫線誤読（「1」と誤認）・商品名の誤りあり。**これは既存のML Kitパイプラインの
  精度限界であり、Phase1の変更とは無関係**（Gemini移行の動機そのもの。Phase2/3で解消見込み）

Phase1は実機で完全に動作確認済み。次はPhase2（Gemini API連携基盤）へ進む。

---

## Phase 2：Gemini API連携基盤

### 方針（2026-08-07 決定）
APIキーは**ユーザー個別キー方式を維持**する（既存の `AppPreferences.geminiApiKey` /
`SettingsScreen` の入力欄をそのまま使う。アプリ埋め込みの単一キー方式は採用しない）。
JA伝票OCRは組合員の経営情報を扱うため、設定画面の該当箇所に**課金有効化キーを推奨する
注意文言**を追加する（無料枠だと入出力がGoogle側のモデル改善に利用され得るため）。

新規クラスは作らず、既存 `util/GeminiReceiptClient.kt` を拡張する。同ファイルには
すでに `parseReceiptFromImage()`（画像→Base64→`inlineData`送信→
`responseMimeType: application/json`でのJSON強制）と、エラー分類
（`GeminiRateLimitException`/`GeminiQuotaExhaustedException`/`GeminiApiKeyMissingException`/
`GeminiApiException`）、`AiUsageStats`（トークン使用量）が実装済みのため、これらを流用する。

### タスク
- [ ] `SettingsScreen` のGemini APIキー入力欄付近に、JA伝票OCR利用時の注意文言
      （課金有効化キー推奨・理由）を追加
- [ ] `docs/development-guidelines.md` にGoogle Cloud Console側の設定手順を追記：
      - 課金の有効化手順（AI Studio / Cloud Console）
      - APIキーに「Android アプリ」制限をかける（パッケージ名 `com.example.greenframeocr` +
        署名証明書SHA-1フィンガープリント）
      - 使用量アラートを設定（想定利用量から大きく外れた場合に通知）
- [x] `GeminiReceiptClient.kt` にJA伝票用のメソッド `parseJaSheetFromImage()` を追加
      （2026-08-09実装）。列クロップTwo-Pass方式（全体画像コール＋取引日列クロップコールを
      `async`で並列実行）を実装。詳細は下記「実装内容」参照
- [x] ネットワークエラー・タイムアウト・レート制限時のリトライ処理（指数バックオフ、
      最大3回）を実装（`callWithRetry()`）。既存のエラー分類クラスをそのまま再利用
- [ ] API呼び出し失敗時のユーザー向けエラー表示（「通信状態を確認してください」等）と
      再試行ボタン（UI未着手。呼び出し側の実装時に対応）

### 実装内容（2026-08-09）
`util/GeminiReceiptClient.kt` に以下を追加：
- `JaSheetRow`（rowType/dateRaw/itemName/quantity/amount/categorySum/remarks/confidence）・
  `JaSheetParseResult`（rows/usageStats/dateColumnAligned）
- `parseJaSheetFromImage(dewarpedBitmap, apiKey): JaSheetParseResult`
  - 全体画像コール（`requestJaSheetMain`）：2000pxにリサイズ、Phase0検証済みプロンプトで
    生の6桁日付(`dateRaw`)・商品名・数量・税込金額・分類計・備考・confidenceを取得
  - 取引日列クロップコール（`requestJaSheetDateColumn`）：`docs/OCR_SPEC.md`の列定義
    （取引日 5.5〜20.0mm、通常行/小計行 56.5〜120.5mm＋月合計行121.0〜135.0mmを結合）に
    マージンを加えてクロップ・2倍拡大し、取引日だけの配列を取得
  - 2つの呼び出しは`coroutineScope`内で`async`により並列実行（逐次実行より高速）
  - 行数が一致すれば`dateRaw`をクロップ側の値で上書き。不一致なら`dateColumnAligned=false`
    を返し、全体画像コール側の`dateRaw`をそのまま使う（フォールバックは呼び出し側の責務）
  - `callWithRetry()`：429/500/503/通信エラーのみ指数バックオフ（1s/2s/4s）で最大3回リトライ。
    403・クォータ超過・APIキー未設定は即座にエラーを投げる（リトライしても無駄なため）
  - モデルは`gemini-3.5-flash-lite`固定（Phase0実測で採用確定）、リサイズ幅2000px固定

`./gradlew compileDebugKotlin`でコンパイル確認済み（BUILD SUCCESSFUL、新規警告なし）。

### 2026-08-09（続き）：実機でAPI疎通確認 → 日付列アラインメント不一致を発見・再アラインメントで解決

動作確認用に`DebugCaptureScreen`（削除予定だが実機テスト用に一時活用）に「⑦ Gemini OCR
（テスト・列クロップTwo-Pass）」セクションを追加し、`parseJaSheetFromImage()`を実機のGemini
APIキーで実行できるようにした（`GeminiReceiptClient.JaSheetParseResult`をそのまま行ごとに
表示するデバッグ用UI。本番UIへの組み込みではない）。

#### 発見：列クロップコールがSUBTOTAL行を省略する

実機の伝票（16行、うちSUBTOTAL行が2行）で実行したところ`dateColumnAligned=false`
（main=16行 date=14行）。ログで両方の中身を比較した結果、**列クロップコール（取引日列のみ
送信する呼び出し）が、プロンプトで明示的に指示していたにもかかわらず、日付欄が空欄の行
（SUBTOTAL行）を配列から省略していた**ことが判明（16行から空欄2件を除いた14件と、
列クロップの返り値14件が完全一致）。これはsample01/03で見られた「実行ごとに結果がブレる」
不安定な誤りとは別の、**決定論的な省略パターン**。

#### 対策：NORMAL行数ベースの再アラインメント（実装・確認済み）

SUBTOTAL/MONTHLY_TOTAL行は伝票の仕様上必ず日付欄を持たない、という構造的な前提を使い、
`alignDateColumn()`に以下のフォールバックを追加：
1. 件数が完全一致 → そのまま順番に割り当て（従来通り）
2. 不一致でも、列クロップの件数が「NORMAL行の数」と一致する場合 → NORMAL行にだけ順番に
   日付を割り当て直し、SUBTOTAL/MONTHLY_TOTAL行のdateRawは空文字列に確定させる
   （`aligned=true`として扱う）
3. それでも一致しない場合のみ`aligned=false`（要フォールバック）

同じ伝票で再実行したところ、警告ログなしで正常にマージされ、実機で「日付列アラインメント：
OK」表示・正しい日付での結果を確認済み。

**残課題**：今回確認できたのは「NORMAL行のみ抜き出す」パターン1件のみ。他の省略パターン
（例：一部のNORMAL行も混じって省略される等）が今後見つかった場合は、この再アラインメント
ロジックでは救済できず`aligned=false`のままになる。

**2026-08-09追記**：`aligned=false`時のUI警告表示を実装済み。`OcrCaptureViewModel.
CaptureStep.Complete`に`dateColumnAligned`を追加し、`OcrCaptureScreen`（副次画面）は
警告バナー、主導線`ReceiptInputScreen.kt`は「取引日をご確認ください」ダイアログを表示する。
検算不一致とは異なり強制ブロックはしない（疑わしいが誤りと確定していないシグナルのため、
`ocrConfidence == "low"`と同じ「警告するが止めない」方針）。2026-08-09、実機の本番導線で
`aligned=false`が自然発生し、ダイアログが正しく表示されることを確認済み（詳細は
`CURRENT_TASK.md`参照。なお表示された取引日のずれを誤読の証拠として記録したのは誤りで、
「年月固定」設定による上書きが原因だったとユーザー指摘で訂正済み）。

### 2026-08-09（続き2）：本番UIへの組み込み・実機確認完了

`OcrCaptureViewModel.processDetectionResult()`と`ReceiptInputScreen.kt`の`CameraView.runOcr()`
の両方を、`OCRProcessor.processUnderlayingBase()`（ML Kit）から`GeminiReceiptClient
.parseJaSheetFromImage()`（Gemini）に置き換えた。

#### カテゴリ判定の設計判断
ML Kit版は保存時点では簡易的な仮カテゴリ（SUBTOTAL行の区分名パターンマッチ／小計前後で
「未分類」「未定」）しか付けておらず、実際のカテゴリ確定は保存後に`CategoryRecalculator`
が月全体を見て「次の小計」を辿って行う設計だった。Gemini版もこれに合わせ、
`OcrCaptureViewModel`companion object に`assignJaSheetCategories()`（
`UnderlyingBaseProcessor.assignCategories()`と同じロジック）を追加し、同じ仮カテゴリだけを
付けて`CategoryRecalculator`に委ねる方針にした（新規のカテゴリ判定ロジックは不要だった）。

#### 実装した共通処理
`OcrCaptureViewModel`companion objectに`mapGeminiResultToParsedRows()`を追加し、
`ReceiptInputScreen.kt`の`CameraView`からも共通利用（`GeminiReceiptClient.JaSheetRow` →
`ParsedRow`変換＋簡易カテゴリ付与）。APIキーは両呼び出し元とも`AppPreferences.geminiApiKey`
から取得するよう配線（`OcrCaptureViewModelFactory`・`CameraView`にパラメータ追加）。
エラーハンドリングは`OcrCaptureViewModel`側は既存の`_errorMessage`、`ReceiptInputScreen`
側は`Toast`表示（呼び出し元にエラー専用UIがまだ無いための簡易対応、Phase4で改善予定）。

#### 実機確認：本番導線（伝票データ→編集→伝票追加→撮影）で複数枚撮影

- 日付・商品名・金額・小計：**完璧**（ユーザー確認）
- 合計(税込)：1回目のプロンプトでは2回とも空欄 → プロンプトの2箇所を修正して解決
  - `categorySum`の説明が「SUBTOTAL行のみ」で、MONTHLY_TOTAL行（合計(税込)）での使い方が
    指示されていなかった
  - 末尾の「省略可」の注意書きが「前月請求・前月入金・**合計欄**などのヘッダー部」という
    表現で、明細末尾の必須の合計(税込)行と紛らわしく、省略されやすい書き方だった
  - 修正後、複数ページの伝票で再テストしたところ「1枚目のみ合計欄に数値、2枚目以降は空欄」
    という結果になったが、**これは紙面自体の仕様通り（1ページ目にしか合計が印字されない）
    でありGemini側の問題ではないとユーザーが確認済み**
  - **プロンプトの訂正（同日）**：上記修正時に「合計(税込)行は空欄であることはないので
    必ず1行出力してください」という誤った前提の文言を入れてしまっていた（複数ページ伝票の
    2ページ目以降は紙面に合計行自体が存在しないため、この前提は誤り）。ユーザーの指摘で
    発覚し、「画像に実際に印字されている場合のみ出力し、印字されていないページでは無理に
    出力しない」という正しい条件に修正した。修正後に実機で再テストし、2ページ目以降で
    合計を捏造しないことを確認済み（2026-08-09）

**Phase2は本番導線での実機確認まで完了**。ML Kitパイプライン（`OCRProcessor`・
`ProductNameCorrectorV3`等）はまだ物理削除していない（Phase6で本番安定稼働確認後に削除する
方針は維持）が、実際の呼び出し経路からは外れた。

---

## Phase 3：OCRプロンプト設計・JSON構造化

### タスク
- [ ] 送信前の画像リサイズ処理を追加：長辺2,000〜2,500px程度・JPEG品質80%以上
      （小さい文字が多い伝票のため、過度な縮小は避ける。実測の上で最終値を確定すること）
- [ ] プロンプトに以下を含める：
      - 伝票の構造説明（列定義：取引日／商品名／数量／税込金額／分類計。既存の
        `OCR_SPEC.md` の列定義をベースに記述）
      - `product_master` から取得した既知商品名候補リスト（完全一致を強制せず、
        近い候補があれば優先させる指示。存在しない場合は自由記述を許可）
      - 出力を厳密なJSON形式に限定する指示（前置き・Markdown装飾なしで純粋なJSONのみ）
- [ ] レスポンスJSON構造（案）：
      ```json
      {
        "rows": [
          {
            "rowType": "NORMAL | SUBTOTAL | MONTHLY_TOTAL",
            "date": "MM/DD",
            "itemName": "商品名",
            "quantity": 1,
            "amount": 1980,
            "categorySum": null,
            "confidence": "high | medium | low"
          }
        ]
      }
      ```
      **2026-08-08 追記**：`quantity` はCSV出力（弥生・らくらく青色申告）で未使用と確定
      （`docs/CSV_SPEC.md`・`docs/yayoi-csv-export-spec.md` に数量列なし）。プロンプトから
      数量列の読み取り指示自体を削除するか、参考情報として残すが精度要求を下げるかは
      次回のPhase0再検証時に決定する。
- [ ] JSONパース失敗時のフォールバック処理（構造が壊れている場合は全体を要確認扱いにし、
      手動入力を促す）

---

## Phase 4：検算バリデーション・confidence表示

### DBスキーマ変更（Room DB v25→v26、2026-08-07 時点の最新バージョンで確定）
- [x] `receipt_items` テーブルに `ocrConfidence` カラム追加（String、nullable、
      値: "high"/"medium"/"low"）2026-08-09実装
- [x] マイグレーションスクリプトを `ReceiptDatabase.kt` に追加（`MIGRATION_25_26`）
- [x] `fallbackToDestructiveMigration()` はすでに削除済み（2026-07-12）。マイグレーション
      書き忘れは起動時クラッシュになるため、追加時は必ずテストすること
      （2026-08-09、既存データ入りの実機でv25→v26マイグレーションを実機確認済み。
      クラッシュなく起動し既存データも保持）

### 判定ロジック（2026-08-07 方針確定：confidenceは主指標にしない）
- [x] 既存の小計・合計整合性チェック（検算バリデーション、`ValidationUtils.validateSheet()`）
      は、`OcrCaptureViewModel.saveData()` がML Kit時代と同じ`SheetData`
      （subtotalGeneral/Gas/Agri・totalFromInput）を組み立てているため、**コード変更なしで
      Gemini結果に対してもすでに機能している**（`SheetEditorScreen`の小計セクションで
      不一致が赤表示される）。2026-08-09確認
- [x] **重大な発見（2026-08-09）**：`SheetEditorScreen`/`SheetEditorViewModel`は実質的に
      主導線ではなく、`Screen.OcrCapture`→`OcrCaptureScreen`経由の再OCR専用画面だった
      （Phase1で発見済みの`CameraView`の件と同根の問題）。実際の主導線
      （伝票データ→編集→月単位で保存）は`ReceiptInputScreen.kt`が独自に持つ
      `ReceiptRowData`・`validateAllSheetsData()`・`saveMonthData()`という、
      `ValidationUtils`/`ReceiptItem`とは別系統の実装だった。このため強制ブロックは
      `SheetEditorScreen`ではなく`ReceiptInputScreen.kt`の「決定」ボタン
      （`saveMonthData()`呼び出し前）に実装する必要があった
- [x] `ReceiptInputScreen.kt`の「決定」ボタンに強制ブロックを実装。ブロック条件は
      `hasUnresolvedMismatch()`：カテゴリ別小計または合計について、
      **入力値（OCR/手入力）が0でない かつ 計算値と一致しない かつ 罫線補正
      （`tryStripRuleDigit`、既存の「罫線が数字'1'に誤読される」既知パターンの補正）でも
      説明が付かない**場合のみブロックする。入力値が0（＝小計行がそもそも検出/入力されて
      いない状態）はブロック対象外とした。理由：SUBTOTAL行が印字されない伝票パターンが
      実在すること（2026-08-08の実機検証で確認済み）と、未検出をブロックすると
      「印字されていない数字を仕方なく捏造入力する」という悪い誘因を生むため。
      既存の`hasUnclassified`（未分類行ブロック）チェックと同じ`return@Button`パターンで
      実装し、ブロック時は`AlertDialog`で理由を表示する
- [x] 上記を検算不一致の強制ブロック条件として実装完了（`OutputConfirmScreen`ではなく
      `ReceiptInputScreen.kt`の月次保存操作＝実質的な唯一の確定操作をブロックする形で実現）
- [x] `ocrConfidence`（Geminiの自己申告）は補助的な参考情報にとどめる。LLMの自己評価
      confidenceはキャリブレーションが悪いことが知られており、確定操作を強制ブロックする
      根拠には使わない（`ocrConfidence == "low"`は黄バッジ表示のみで確定はブロックしない
      実装にした）
- [x] 表示ルール：`ocrConfidence == "low"` → 黄バッジ・確認は推奨だがスキップ可能
      （あくまで参考表示）を実装。「検算不一致→強制ブロック」は上記の通り実装・実機確認済み
      （2026-08-09、意図的に小計不一致を作って「決定」ボタンがブロックされることを確認）

### UI変更
- [x] `SheetEditorScreen`（再OCR専用の副次画面）の行リストにバッジ表示を追加（`EditableRow`
      左端の黄色ドット、`ocrConfidence == "low"`の行のみ）2026-08-09実装
- [x] `ItemEditDialog` を開いた際、要確認理由を一言添えて表示（「⚠ 読み取り不確実（AIの
      自己申告確信度: low）。内容をご確認ください。」）。「小計と¥120差異」のような
      小計差異ベースの理由文言は、小計不一致がSheetData単位（伝票全体）であり
      個別ReceiptItemに紐づく情報ではないため対象外とした
- [x] **主導線`ReceiptInputScreen.kt`のデータグリッドにも同じバッジを追加**（上記の
      「重大な発見」参照。こちらが実際にユーザーが使う画面）。`ReceiptRowData`に
      `ocrConfidence`を追加し、`convertParsedRowsToRowData()`（OCR直後）・
      `convertReceiptItemsToRows()`（DB再読込時）・`saveMonthData()`（保存時）の
      3箇所で伝搬。`DataGrid`のヘッダー行・`DataRow`に16dp固定幅のバッジ列を追加
      （左右で列がずれないよう、ヘッダー側にも同幅のスペーサーを追加）
- [x] 伝票内の要確認行のみを抽出する一覧・一括確認モードを実装（2026-08-11、実機確認済み。
      詳細は`CURRENT_TASK.md`「要確認行の一覧・一括確認モードを実装」参照）

---

## Phase 5：再OCR（部分クロップ再送信）

### 背景
Gemini再OCRは同一画像・同一プロンプトの単純リトライでは効果が薄い（ML Kitのようなランダム性がない）。
該当行・該当セルを元の高解像度画像から切り出して再送信する方式を採用する。

### タスク
- [ ] `warpedBitmap`（透視変換後の元画像、3045×2220px）を撮影確定まで内部ストレージに
      一時保存する処理を追加（DBに画像を直接持たせない。ファイルパスのみ保持）
      - 保存先：アプリ内部ストレージ（外部公開しない、既存のセキュリティ方針に準拠）
      - 削除タイミング：伝票確定（`SheetEditorScreen` での保存確定）後、または
        一定期間経過後
- [ ] `isOcrOverwriteTarget` の概念を流用し、行単位で再OCR対象をマークするUIをそのまま維持
- [ ] 既存の `OCR_SPEC.md` のROI定義（列のmm/px範囲）を使い、該当行のY範囲×該当列のX範囲で
      `warpedBitmap` から `cropBitmap()`
- [ ] クロップ画像を2倍程度に拡大（既存の商品名列2倍拡大ロジックを流用）
- [ ] 軽量プロンプト（「これは伝票の一部分［商品名欄］です。以下の候補と照合しつつ
      正確に読み取ってください」＋商品マスタ候補リスト）でGemini APIへ単独送信
- [ ] 結果を `receipt_items` の該当フィールドのみ上書き
- [ ] 再OCR呼び出し回数の上限を設定（1行あたり例えば3回まで等、コスト暴走防止）

---

## Phase 6：不要ファイル・画面の削除

### 削除対象ファイル
- [ ] `util/ProductNameCorrectorV3.kt`
- [ ] `util/ProductNameCorrector.kt` / `ProductNameCorrectorV2.kt`（旧バージョン、
      `known-issues.md` に記載の技術的負債）
- [ ] `util/ExplicitJoinMatcher.kt`
- [ ] `ui/OcrLearningStatusScreen.kt`
- [ ] `ui/DebugCaptureScreen.kt`
- [ ] `viewmodel/` 内の上記画面に対応するViewModel

### Navigation変更
- [ ] `Navigation.kt` から `OcrLearningStatusScreen`・`DebugCaptureScreen` のルートを削除
- [ ] `SettingsScreen` からのOCR学習状況への導線を削除

### DB変更
- [ ] 以下のテーブルを削除するマイグレーションを追加：
      - `ocr_variants`
      - `ocr_score_logs`
      - `correction_logs`
      - `ocr_explicit_joins`
- [ ] `product_master.kaikakeTekiyouId` 等、削除対象テーブルに依存しないFK関係は
      影響がないことを確認する

### OCRProcessor.kt の扱い
- [ ] ML Kitのラッパーとしての `OCRProcessor.kt` は、一般レシート（一般購買）パイプライン側で
      引き続き使用中の可能性があるため、**安易に全削除しない**。JA伝票専用ロジック
      （列特化OCR・数量Latinモデル呼び出し等）のみ削除し、一般レシート側で使っている
      メソッドは残すこと。着手前に一般レシートパイプラインの依存箇所を確認すること。

---

## ドキュメント更新

- [ ] `docs/architecture.md`：システムフロー図をGemini版に更新
- [ ] `docs/OCR_SPEC.md`：ML Kit固有の記述（Step2・Step8・Step8.5等）を、Geminiプロンプト
      仕様に置き換え。列のmm/px範囲定義は再OCRクロップ処理で引き続き使うため残す
- [ ] `docs/functional-design.md`：ER図・システムフローをGemini版に更新
- [ ] `docs/known-issues.md`：ML Kit時代の既知バグのうち解消されるものを整理、
      Gemini移行後の新たな注意点（APIタイムアウト、幻覚リスク等）を追記
- [ ] `docs/glossary.md`：`OcrVariant`・`confidenceLevel`（LOCKED/CONFIRMED/AUTO）等の
      ML Kit学習用語を削除し、`ocrConfidence`（high/medium/low）等の新用語を追加
- [ ] `CLAUDE.md`：Room DBバージョン・重要な技術ルール（RGBA→BGR変換等は透視変換部分に
      引き続き必要なため維持）を更新

---

## 完了条件

- 購買伝票をGemini Vision APIでOCRし、商品名・数量・税込金額・カテゴリが取得できる
- 検算バリデーション（小計整合性）が機能し、不一致行は確定前に強制的にユーザー確認を求める
- confidence表示により、要確認行が一覧・バッジで識別できる
- 再OCRが部分クロップ方式で機能し、対象行のみ再送信される
- OcrLearningStatusScreen・DebugCaptureScreenが削除され、Navigation・DBともに整合性が保たれている
- 一般レシート（一般購買）OCRパイプラインが今回の変更で壊れていないことを確認済み
- `fallbackToDestructiveMigration()` が開発中のみ有効であることを維持（本番前削除は別タスク）

## 進捗メモ

### 2026-08-07：計画レビューと方針決定
Claude Codeとのレビューで以下を決定：
1. `GeminiOcrProcessor.kt` は新設せず、既存 `util/GeminiReceiptClient.kt`（一般レシートの
   `parseReceiptFromImage()` 等が実装済み）を拡張する方針に変更
2. APIキーはユーザー個別キー方式を維持（`AppPreferences.geminiApiKey`をそのまま使用、
   BuildConfig埋め込み方式は不採用）
3. Gemini APIのデータ利用ポリシーを調査：無料枠は入出力がGoogle側のモデル改善に利用され得る、
   有料枠は学習に利用されない。JA伝票は機微度が高いため課金有効化キーを推奨する注意文言を
   設定画面に追加する方針とした
4. Phase4のDBマイグレーション番号を v15→v16 から **v25→v26**（現在の実バージョン）に修正
5. `ocrConfidence`（Geminiの自己申告）は強制ブロックの根拠にせず、検算バリデーションのみを
   強制ブロック条件とする方針を確定
6. Phase1〜3のUI・DB基盤を作り込む前に、Phase0としてスパイクテスト（サンプル画像での
   実測精度検証）を追加し、最優先で実施することにした
7. ML Kit経路をユーザー向けトグルとして併存させることはしない。Phase6での物理削除は
   本番安定稼働の確認後に行う（ロールバックはGit履歴から）

次回セッションで着手すること：Phase0のスパイクテスト。

---

### 2026-08-08：Phase0スパイクテスト実施・結果記録

実機確認済みサンプル1枚（`OCRTest/debug_1773491633214_3_dewarped.png`、島原雲仙農業協同組合
購買代金請求明細書、小計53,551円・15,810円で検算済みの正解データ）を使い、
`gemini-3.6-flash` / `gemini-3.5-flash-lite` の2モデル × 送信画像幅2000px/2500pxの
4パターンでGemini Vision APIへ実送信し、精度・速度・コストを比較した。

#### 結果

| モデル×解像度 | 精度 | 処理時間 | 入力/総トークン | 概算コスト/回 |
|---|---|---|---|---|
| gemini-3.6-flash w2000 | 全行正解（数量列の小数点推測も成功） | 約21秒 | 1,434 / 6,284 | 約$0.039（≈¥6） |
| gemini-3.6-flash w2500 | 全行正解 | 約19秒 | 1,434 / 5,718 | 約$0.034（≈¥5） |
| gemini-3.5-flash-lite w2000 | 数量列誤り（`2920`のまま、小数点推測失敗）、JSON型逸脱あり | 約3.6秒 | 1,434 / 2,829 | 約$0.004（≈¥0.6） |
| gemini-3.5-flash-lite w2500 | 数量列誤り（`29`に切り捨てさらに悪化） | 約3.8秒 | 1,434 / 2,820 | 約$0.004（≈¥0.6） |

- 商品名・取引日・税込金額・分類計（小計）は4パターンとも完全正解
- ガソリン給油量（伝票上`2920`→実際は`29.20`）の小数点推測は、`gemini-3.6-flash`のみ
  単価×金額の検算から正しく復元できた。この推論はML Kit時代には不可能だった
- 送信画像幅2000px/2500pxで入力トークン数・精度に有意差なし → コスト面のメリットなし

#### 2026-08-08 方針転換：数量列は不使用と確定

弥生・らくらく青色申告向けCSV出力（`docs/CSV_SPEC.md`・`docs/yayoi-csv-export-spec.md`）に
数量列は含まれておらず、実運用で数量値を使わないことが確認された。これにより：

- `gemini-3.5-flash-lite`の数量列誤読（小数点推測失敗）は**採用可否の決定的な欠点ではなくなった**
- `gemini-3.6-flash`（約20秒/回）はUX上長すぎるとの指摘があり、`flash-lite`（約4秒/回）中心で
  再検証する方針に変更
- **次回タスク**：数量列を評価対象から除外したプロンプト・比較基準で同一サンプルを再テストし、
  商品名・取引日・税込金額・分類計の精度のみで`flash-lite`が実用に足るか再判定する。
  実用に足ると分かれば速度優先で`flash-lite`を採用、不足があれば`gemini-3.6-flash`を維持する

#### 検算バリデーションに関する注意点（記録）

小計整合性チェック（検算バリデーション）は税込金額列の合計のみを見ており、**数量列の誤りは
検出できない**。数量列を仕訳データとして使わない方針が確定したため、この制約は実害がなくなった。

---

### 2026-08-08（続き）：sample01〜04追加検証・日付列の不安定な誤読を発見

実機で新たに4枚撮影（`DebugCaptureScreen`→画像保存→`adb pull`で取得）し、数量列を除外した
基準（商品名・取引日・税込金額・分類計・合計）で`gemini-3.5-flash-lite`を再検証した。

- sample01：返品行あり・令和7年11月分
- sample02：商品名が複雑（英数字・カタカナ混在）・令和7年7月分・行数多め
- sample03：ヘッダー空欄の2ページ目（2/2）・令和7年9月分
- sample04：SUBTOTAL行が存在しない伝票・専門資材名（記号多数）・令和7年9月分

#### 結果：商品名・金額・小計・合計は4サンプルとも完全正解

数量列を除けば、商品名・税込金額・分類計（小計）・合計（税込）は4サンプルすべてで完全一致。
返品行の符号処理、SUBTOTAL行が存在しない伝票での適切な省略、空欄ヘッダーの扱いも問題なし。

#### 問題：取引日列に低確率で不安定な誤読がある（重要）

sample01とsample03で、**そのサンプル内の全行が同じ月に系統的に誤読される**現象を発見した
（例：sample01は実際「11月」なのに全18行が「10月」、sample03は実際「9月」なのに全12行が
「7月」）。目視で伝票の印字を拡大確認し、誤読ではなく実際に正しい数字（11・9）であることを
確認済み。

原因切り分けのため以下を実施：

1. **リサイズなし（3200px＝実質等倍）で再送信** → 入力トークン数がw2000と完全に同一
   （Gemini側で画像を内部的に固定サイズへ再エンコードしている可能性）。sample01は改善せず、
   sample03はたまたま正解に変化 → リサイズは有効な対策ではないと判断
2. **プロンプト修正**（`gemini_batch_test.ps1`）：
   - 6桁の日付が「年2桁+月2桁+日2桁」形式であること、月の桁は誤読しやすいので慎重に読むこと
   - 表の行は取引日の昇順に並んでいること、前月分の行が先頭に混ざるのは正常だが、
     周囲と連続性のない孤立した月の飛びは誤読の可能性が高いこと
   - この修正でsample01は全行「11月」に修正（正解）。sample03は直らず
3. **sample03のみ同一画像・同一プロンプトで3回連続実行**（`gemini_repeat_test.ps1`）：
   - 1回目 09/19（正解）、2回目 09/19（正解）、3回目 07/19（誤り）
   - **temperature=0でも実行ごとに結果がブレる**ことを確認。安定した誤読ではなく、
     約1/3の確率で発生する不安定な誤り
   - 誤った回も`confidence: "high"`のまま → **Geminiの自己申告confidenceはこの種の
     誤りを検出できない**

#### 結論・今後の対策方針

- プロンプト側の工夫（月の慎重な読み取り指示、昇順の一貫性チェック指示）だけでは根絶できない
- confidence表示に頼れないため、**アプリ側のコードで機械的に検算する仕組みが必須**：
  行の取引日が直前の行より大きく後退していないか（昇順が崩れていないか）を受信後にチェックし、
  崩れていたらconfidenceに関わらず強制的に要確認フラグを立てる案が有力
  （Phase4の検算バリデーションに追加する形で実装する想定）
- 検証に使ったスクリプト・サンプル画像・結果JSONはセッションのスクラッチパッド
  （`...\facff607-cbd0-4e21-a1ea-012cf4fcd28a\scratchpad\gemini_samples\`,
  `gemini_spike_results\`）に保存されているが、セッション終了後に消える可能性があるため
  再現性が必要な場合はプロンプト内容をこのファイルの記録から復元すること

---

### 2026-08-08（続き2）：Geminiへの技術相談・生数字出力方式で再検証

上記の不安定な日付誤読について、Gemini自身に原因と対策を相談した。回答の要旨：

- `temperature: 0` でも実行ごとにブレるのは既知の挙動（分散推論の浮動小数点誤差によるタイブレーク。
  画像入力はビジョンエンコーダのパッチ展開を経るためテキストより影響を受けやすい）
- 列全体が同じ方向にズレる現象は「コンテキストバイアス（自己回帰的な引きずり）」と呼ばれる
  VLM特有の失敗モード。区切りのない6桁連続数字が周辺の伝票番号・単価と混同されやすい
- クライアント側でリサイズしてもトークン数が変わらなかった件：Gemini側が画像を固定タイル
  グリッドに内部再エンコードしているため。**取引日列だけを別途クロップして送れば実効解像度が
  数倍に上がり、周辺の紛らわしい数字も物理的に排除できる**ため最も有効な対策
- 推奨プラクティス：①OCR（生数字の書き写し）と論理変換（MM/DD整形）を同時にやらせない
  （生の6桁文字列のまま出力させ、年/月/日への変換はクライアント側で行う）、②取引日列限定の
  クロップと全体抽出の二段階OCR（Two-Pass）、③複数回実行の多数決、④上位モデルはこの種の
  バイアスに強い傾向

上記①（生数字出力＋クライアント側パース）を`gemini_rawdate_test.ps1`で実装し、sample01・
sample03それぞれ3回ずつ再検証した。

#### 結果

| サンプル | run1 | run2 | run3 |
|---|---|---|---|
| sample03（正解:9月） | 全行正解 | 全行正解 | 全行正解 |
| sample01（正解:11月） | 先頭5行のみ誤り(10月) | 16/18行誤り(10月) | 全行正解 |

- **sample03は3回とも完全に安定して正解**するようになった（従来のMM/DD直接変換方式では
  3回中2回正解だったので明確な改善）
- **sample01は改善したが依然不安定**（3回中1回のみ完全正解）。ただし誤り方が「全行一律にズレる」
  から「一部の行だけ誤る」に変化しており、コンテキストバイアスがある程度緩和されている兆候
- Geminiが「区切り記号なしの6桁数字のみ」という指示に従わず、`07/10/04`のように自らスラッシュを
  挿入するケースがあった。実運用でのパース処理は区切り文字の有無に依存しない実装にする必要がある
- 検証用に実装した「取引日昇順チェック」ロジックが、SUBTOTAL行（区分の境目）で取引日が
  区分ごとにリセットされる（一般購買9/19〜25→給油所9/6〜29）伝票の構造を考慮しておらず、
  正常なケースを誤検出（false positive）した。**昇順チェックはSUBTOTAL/MONTHLY_TOTAL行で
  基準をリセットする実装にする必要がある**（Phase4設計に反映すること）

#### 結論・最終的な対策方針

1. プロンプトは「生の6桁数字のまま出力」に変更し、年/月/日への変換はKotlin側で行う（採用確定）
2. 取引日の昇順チェックはSUBTOTAL/MONTHLY_TOTAL行でリセットする仕様にする（Phase4設計に反映）
3. 上記1・2だけではsample01のような不安定性を完全には解消できないため、**取引日列だけを
   クロップして別送信する二段階OCR（Two-Pass、Phase5で計画済みの部分クロップ再送信の仕組みを
   流用）が本命の対策**。ただし実装コストがあるため、まず1・2だけで実運用に足るか
   （昇順チェックで異常検出→ユーザー確認フローに落とせるか）を優先的に見極める
4. `flash-lite`の採用自体は継続方針。ここまでの検証で商品名・金額・小計・合計の精度に
   問題はなく、取引日のみが対策必要な領域と切り分けられている

---

### 2026-08-08（続き3）：取引日列クロップTwo-Pass方式を検証 → 完全安定を確認

不安定性が残っていたsample01・安定していたsample03の両方を対象に、取引日列だけを
クロップして単独送信するTwo-Pass方式を実装・検証した。

#### 実装内容
- クロップ範囲：`docs/OCR_SPEC.md`の列定義（取引日 X:83〜300px, 5.5〜20.0mm）に
  左右10px・上下5pxのマージンを加えた X:73〜310px, Y:843〜2030px
  （通常行/小計行 Y:848〜1808px と月合計行 Y:1815〜2025px を1回のクロップでカバー）
- 2倍拡大（既存の商品名列2倍拡大ロジックと同じ`PRODUCT_OCR_SCALE`慣例に合わせた）
- プロンプトは列全体のみを対象にした軽量版（生の6桁数字のまま出力方式は維持）
- モデルは`gemini-3.5-flash-lite`、`gemini_datecolumn_crop_test.ps1`で検証

#### 結果：6回中6回とも完全に安定して正解

| サンプル | run1 | run2 | run3 | 処理時間 | 入力トークン |
|---|---|---|---|---|---|
| sample01（正解:11月・18行） | 11月:18件 ✅ | 11月:18件 ✅ | 11月:18件 ✅ | 1.4〜2.7秒 | 1,179 |
| sample03（正解:9月・12行） | 09月:12件 ✅ | 09月:12件 ✅ | 09月:12件 ✅ | 1.3〜1.7秒 | 1,179 |

- sample01は全体画像＋生数字出力方式では3回中1回しか完全正解しなかったが、列クロップ方式では
  3回とも安定して全行正解に改善。これまで確認した対策の中で唯一「完全に」安定した方式
- 行数（件数）も期待通り（sample01=18行、sample03=12行）で、欠落・重複なし
- 処理時間も全体画像方式（flash-liteで約4〜20秒）より大幅に速い（1.3〜2.7秒）。列クロップのみの
  軽量画像・軽量プロンプトのため入力トークンも少ない（1,179、全体画像は1,434前後）

#### 結論・採用方針

**取引日列クロップのTwo-Pass方式を正式に採用する。** Phase4で先に検討していた「昇順チェックで
異常検出→ユーザー確認フローに落とす」案は、この完全安定という結果を受けて優先度を下げ、
Two-Pass方式をメインの対策として設計する。昇順チェックはTwo-Pass導入後も安全網として併用は
検討可（コスト0のためリスクなし）だが、必須の一次対策ではなくなった。

#### 残課題（Phase4/5設計に反映すること）

- **行アラインメントの担保**：全体画像コール（商品名・金額等）と列クロップコール（取引日のみ）は
  別々のGemini呼び出しのため、返ってくる行数が一致しない場合のマージ戦略が必要。今回の検証では
  件数は両者で一致したが、伝票によっては空白行の扱い等でズレる可能性があるため、行数不一致時は
  該当伝票全体を要確認扱いにするフォールバックを設計に含めること
- Two-Pass化により1伝票あたりのAPI呼び出しが2回になる（コスト・レイテンシ増）。ただし列クロップ
  コール自体は軽量（1,179トークン・1.3〜2.7秒）なため、全体への影響は許容範囲と判断
- 他の列（商品名・税込金額等）は現時点で全サンプル完全正解のため、Two-Pass化は取引日列のみに
  限定する。全列を分割するのはコスト・レイテンシに見合わないと判断
- 検証に使ったスクリプト（`gemini_datecolumn_crop_test.ps1`）・クロップ画像・結果JSONは
  セッションのスクラッチパッド（`...\c766040d-10f2-4928-b6e6-c439f6746efa\scratchpad\`）に
  保存されているが、セッション終了後に消える可能性があるため、クロップ範囲・プロンプト内容は
  このファイルの記録から復元すること

---

## 未確定・要相談事項（実装前に確認推奨）

- Gemini 2.5 Flashの後継モデル（3 Flash / 3.1 Flash-Lite等）のうち、実際にどれを採用するか
  実測で比較してから確定すること（速度・精度・コストのバランス）→ Phase0で検証
- 送信画像の最終リサイズ幅（2,000〜2,500pxの範囲内でどこに決めるか）は実機テストで確定
  → Phase0で検証
- `warpedBitmap` の保存期間（伝票確定直後に削除か、一定期間保持して再OCRの猶予を持たせるか）
- 再OCR回数の上限値（行あたり3回は暫定値、実運用を見て調整）
- 取引日の昇順チェックロジックは、列クロップTwo-Pass方式の採用決定（2026-08-08）により
  必須の一次対策ではなくなった。安全網として併用するかは実装時に判断（優先度は下がった）
- ~~取引日列クロップの二段階OCR（Two-Pass）をPhase5と統合するか先行導入するか~~
  → **2026-08-08決定**：先行導入する。全体画像コール＋取引日列クロップコールの2回セットを
  Phase3〜4の標準フローに組み込む（Phase5の「行単位再OCR」とは別軸の、常時実行される
  Two-Pass構成として設計）。行アラインメント（件数不一致時のフォールバック）の設計が
  Phase4着手前の必須タスクとして残る
