# CURRENT_TASK.md

## 作業タイトル
公式docs（product-requirements/functional-design/architecture/repository-structure/development-guidelines/glossary）の削除済みクラス・ML Kit言及の整理

## 目的・背景
Phase6（2026-08-11、ML Kit全廃止・OcrCaptureScreen等削除）以降、計画外だった残ドキュメントの
削除済みクラス言及整理が未着手のままだった（MEMORY.md「次のタスク」参照）。放置すると
将来のセッションが古い記述を現状と誤認するリスクがあるため着手した。

## 今回のタスク
- [x] docs/配下でML Kit・削除済み画面（OcrCaptureScreen/SheetEditorScreen/DebugCaptureScreen）・
      削除済みクラス（OCRProcessor/ProductNameCorrectorV3/ExplicitJoinMatcher/OcrExplicitJoin）を
      grepし、CLAUDE.mdが定義する公式7ドキュメントのうち該当箇所を洗い出す
- [x] architecture.md：技術スタック表のML Kit行をGemini Vision APIに統一、DBバージョン表記を
      28→33に修正（テーブル一覧にgeneral_item_master・receipt_payment_method_rulesを追加）、
      一般レシートのML Kitハイブリッド記述をGemini一本化後の状態に修正、技術的制約からML Kit
      バンドル・最小文字高さの記述を削除
- [x] product-requirements.md：OCR行・OCR補正行をGemini/canonicalKey方式に更新、OCR学習状況・
      デバッグ撮影の状態列を「画面削除済み」に修正
- [x] repository-structure.md：assetsフォルダのコメントをML Kitモデルから実際の内容（CSV初期
      データ等）に修正
- [x] functional-design.md：ER図からPhase6でDROP済みの3テーブル（ocr_explicit_joins/
      correction_logs/ocr_score_logs）を削除、OCRパイプライン節を全面書き換え（architecture.md
      への参照に統一し重複管理を解消）、OCR学習システム（V3）節に非稼働である旨の注記を追加
- [x] glossary.md：OCR学習システム用語節に非稼働の注記を追加、削除済みクラスの用語
      （OcrExplicitJoin・CorrectionResult）を除去、enum名変更（AUTO→TENTATIVE）を反映
- [x] development-guidelines.md：命名規則の例を現存するroute/Composableに差し替え、OpenCV変換
      フロー図のML Kit宛先をGemini宛先に修正、テスト規約からDebugCaptureScreen言及を削除、
      ocr_variants全角化ルールに非稼働の注記を追加、パフォーマンスガイドラインのML Kit行を
      Gemini（callWithRetry・約4秒/回）に更新

## 完了条件
CLAUDE.mdが定義する公式7ドキュメント（known-issues.mdは元々正しく注記済みのため対象外）に
おいて、削除済みクラス・ML Kitへの言及が「現状の説明」として残っていない状態
（履歴的注記としての言及は許容）。

## 進捗メモ
- 調査の過程で、当初のスコープ（ML Kit言及削除）を超える乖離を発見：
  - DBバージョンが実際は33（entities一覧確認済み）なのにdocsは28のままだった
  - OCR学習システム（V3、ocr_variantsのLOCKED/CONFIRMED/AUTO昇格ロジック）は、
    `OcrVariantDao.registerLearning()`の呼び出し元が存在せず実質的に非稼働と判明
    （`OcrVariant.kt`自体・DAOメソッド自体はコード上に現存するが、呼ばれていない）。
    現行のocr_variants利用はCSV出力時の`ocrVariantDao.getByText()`によるフォールバック
    照合のみ（読み取り専用）
  - 商品名補正は`ProductNameCorrectorV3`（3層スコアリング）ではなく、`JaSheetOcrMapper.
    applyProductMasterCorrection()`によるcanonicalKey完全一致照合に変わっていた
  - `ConfidenceLevel.AUTO`は2026-05-07に`TENTATIVE`へenumリネーム済みだったが、docsは
    旧名`AUTO`のまま記載されていた
- CHANGELOG.md・TASK_*.md・APP_SPECIFICATION.md・OCR_SPEC.md・GreenFrameOCR_SPEC.md等は
  CLAUDE.mdの公式7ドキュメントに含まれない歴史的記録と判断し、今回の修正対象外とした
  （当時の状態を記録する文書として残す方針）
- CLAUDE.md本体にも同様のML Kit言及（RGBA/BGR変換ルール・文字高さ制約）が残っているが、
  CLAUDE.md更新はユーザーの明示的確認が必要なため今回は対象外。次回ユーザーから更新指示が
  あれば対応する

---

## 作業終了時の記録（セッション終了前に必ず埋めること）

### 今回完了したこと
- 公式docs 6ファイルの削除済みクラス・ML Kit言及を整理し、実コード（ReceiptDatabase.kt・
  OcrVariant.kt・OcrVariantDao.kt・JaSheetOcrMapper.kt・GeminiReceiptClient.kt）と突き合わせて
  正確な現状（DBバージョン33・OCR学習システム非稼働・canonicalKey補正方式）に更新した

### 未完了・中断した理由
なし

### 次回セッションで最初にやること
MEMORY.mdの「次のタスク／未着手」から本項目を除去し、完了済みセクションに移す
（本セッション終了時にすでに実施済みの場合は不要）

### 新たに発覚した問題・制約
- CLAUDE.md本体（プロジェクトルート）にもML Kit言及（RGBA/BGR変換ルール・文字高さ制約）が
  残存している。docs/development-guidelines.mdは今回修正したが、CLAUDE.md本体は
  ユーザー確認なしに更新しない方針のため未修正のまま。ユーザーから更新指示があれば対応する
