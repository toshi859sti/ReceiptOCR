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
- [ ] 既存の実機確認済み `warpedBitmap`（3045×2220px）サンプルを数枚用意
- [ ] Phase3で検討中のプロンプト（列定義・JSON構造指定）の素案を使い、手動スクリプトまたは
      デバッグ経由で実際にGemini Vision APIへ送信し、レスポンスを確認
- [ ] 特に数量列・商品名列（複雑な漢字）の読み取り精度をML Kit時代の既知の誤読サンプルと
      比較する
- [ ] 後継モデル（Gemini 3 Flash / 3.1 Flash-Lite等）候補で速度・精度・コストを比較
- [ ] 送信画像リサイズ幅（2,000〜2,500px）を変えて精度への影響を確認し、最終値を決定
- [ ] 検証結果を本ドキュメントの「進捗メモ」に記録し、Phase1以降に進むかを判断

---

## Phase 1：透視変換後の確認フロー

### 背景
Gemini移行によりOCR失敗のコストが変化する（API呼び出し1回＝コスト発生）。歪んだ画像を
そのまま送信すると、Geminiは意味的推論で「それらしい」誤読を自信満々に返す可能性があるため、
送信前のゲートを強化する。

### タスク
- [ ] `GreenFrameDetector.DetectionResult` に含まれる `dewarpedBitmap` を表示する
      `TransformPreviewScreen` を新設
- [ ] 自動判定ロジック（既存の `isValidShape()` の凸形状・面積・内角チェック）を用いて、
      基準を満たす場合は確認画面を自動スキップし、そのまま送信フローへ進む
- [ ] 基準を満たさない場合のみ確認画面を表示し、「送信する」「撮り直す」の2択を提示
- [ ] 「撮り直す」選択時は `OcrCaptureScreen` に戻る。連続撮影中の場合は伝票番号
      （`sheetNumber`）を保持したまま再撮影できるようにする
- [ ] 初期実装は「OK / 撮り直し」の2択のみ（四隅の手動調整UIは今回のスコープ外、
      将来拡張として `CURRENT_TASK.md` に記録）

### Navigation
```
OcrCaptureScreen → (GreenFrameDetector.process)
  → [isValidShape() OK] → 直接送信フローへ
  → [isValidShape() NG] → TransformPreviewScreen → 送信 or 撮り直し
```

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
- [ ] `GeminiReceiptClient.kt` にJA伝票用のメソッド（例：`parseJaSheetFromImage()`）を追加。
      既存の `parseReceiptFromImage()` と同様の画像送信・JSON強制パターンを踏襲しつつ、
      JA伝票専用のプロンプト・レスポンス構造（Phase3）に対応させる
- [ ] ネットワークエラー・タイムアウト・レート制限時のリトライ処理（指数バックオフ、
      最大3回程度）。既存のエラー分類クラスをそのまま再利用する
- [ ] API呼び出し失敗時のユーザー向けエラー表示（「通信状態を確認してください」等）と
      再試行ボタン

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
- [ ] JSONパース失敗時のフォールバック処理（構造が壊れている場合は全体を要確認扱いにし、
      手動入力を促す）

---

## Phase 4：検算バリデーション・confidence表示

### DBスキーマ変更（Room DB v25→v26、2026-08-07 時点の最新バージョンで確定）
- [ ] `receipt_items` テーブルに `ocrConfidence` カラム追加（String、nullable、
      値: "high"/"medium"/"low"）
- [ ] マイグレーションスクリプトを `ReceiptDatabase.kt` に追加（`MIGRATION_25_26`）
- [ ] `fallbackToDestructiveMigration()` はすでに削除済み（2026-07-12）。マイグレーション
      書き忘れは起動時クラッシュになるため、追加時は必ずテストすること

### 判定ロジック（2026-08-07 方針確定：confidenceは主指標にしない）
- [ ] 既存の小計・合計整合性チェック（検算バリデーション）をGemini結果に対しても実行し、
      **これを唯一の強制ブロック条件とする**
- [ ] `ocrConfidence`（Geminiの自己申告）は補助的な参考情報にとどめる。LLMの自己評価
      confidenceはキャリブレーションが悪いことが知られており、確定操作を強制ブロックする
      根拠には使わない
- [ ] 表示ルール：
      - 検算不一致（金額） → 赤バッジ・`OutputConfirmScreen` での確定操作を
        強制ブロック（青色申告データの整合性を優先）
      - `ocrConfidence == "low"` → 黄バッジ・確認は推奨だがスキップ可能（あくまで参考表示）
      - それ以外 → バッジなし

### UI変更
- [ ] `SheetEditorScreen` の行リストにバッジ表示を追加
- [ ] `ItemEditDialog` を開いた際、要確認理由（「小計と¥120差異」「読み取り不確実」等）を
      一言添えて表示
- [ ] 伝票内の要確認行のみを抽出する一覧・一括確認モードを追加（要確認0件なら
      ワンタップで全体確定できるようにする）

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

## 未確定・要相談事項（実装前に確認推奨）

- Gemini 2.5 Flashの後継モデル（3 Flash / 3.1 Flash-Lite等）のうち、実際にどれを採用するか
  実測で比較してから確定すること（速度・精度・コストのバランス）→ Phase0で検証
- 送信画像の最終リサイズ幅（2,000〜2,500pxの範囲内でどこに決めるか）は実機テストで確定
  → Phase0で検証
- `warpedBitmap` の保存期間（伝票確定直後に削除か、一定期間保持して再OCRの猶予を持たせるか）
- 再OCR回数の上限値（行あたり3回は暫定値、実運用を見て調整）
