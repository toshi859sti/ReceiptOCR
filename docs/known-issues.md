# 既知バグ・制約・技術的負債

## 既知のバグ

- ~~**「通帳摘要別リスト」右上の「通帳再読込」ボタンが、確認なしで全通帳の預金明細を消す**~~（2026-09-25 発見・**同日修正**：ボタンと asset 読み込み処理を削除）。
  `TekiyouMatchingScreen` が `deleteAll()` のあと `assets/meisai.csv`（デモ用）を読み直す作りだが、その asset は
  もう存在しないので、消えたまま何も入らない（例外は握りつぶされる）。DB v39 で通帳が複数になったので全冊が消える。
  画面を開いたとき明細 0 件なら同じ asset を読みに行く処理も同じく空振りしている

- [x] 摘要集約リスト「データなし」バグ（修正済み 2026-04-05）
  - `updateRulesFromMeisai` でルール生成後に `deposit_meisai.matchingRuleId` を書き戻していなかった
- [x] 小計カテゴリ認識精度（実機確認済み 2026-08-11）
  - Gemini移行後、一般購買・給油所・農業機械の3カテゴリすべてが同一伝票内に混在する
    実伝票で確認。誤分類（`未定`/`未分類`混入）なし、`ocrConfidence`はすべて`high`
- [x] 表示ルール（小計後空行・合計行の順序）確認済み（実機確認済み 2026-08-11）
  - 小計行→空行→次カテゴリ開始という並びを、一般購買→農業機械・農業機械→給油所の
    両方の境界で確認。合計行は1ページ目のみに正しく出力されることも複数回の実撮影で確認済み
- [x] レシート領収書CSV出力の貸方勘定科目（支払方法の科目）が全件「現金」ハードコード
      （修正済み 2026-08-15）
  - `ReceiptPaymentMethodRule`（キーワード→科目のユーザー設定ルール）とGeminiによる支払方法
    印字テキスト抽出（`GeneralReceipt.paymentMethodText`）を追加し、個別上書き→ルール一致→
    現金の優先順で自動判定するよう修正。詳細は`.steering/`（該当セッションのアーカイブ）参照

- [x] 預金CSV出力が個別オーバーライドを無視する（発覚 2026-09-09 / 修正済み 2026-09-09）
  - `TekiyouMatchingScreen` の個別変更ダイアログ（`IndividualOverrideDialog` / らくらく =
    `deposit_meisai.overrideTekiyouId`、`IndividualYayoiOverrideDialog` / 弥生 =
    `overrideYayoiAccountId`）でユーザーが行単位に科目/摘要を上書きできるが、
    `OutputConfirmScreen.loadDepositOutputItems` は `tekiyou_matching_rules` のパターン一致
    （`rule.yayoiAccountId` / `rule.rakurakuTekiyouName`）しか見ておらず、個別上書きが
    CSV出力に反映されなかった
  - 修正：`loadDepositOutputItems` で `meisai.overrideYayoiAccountId`（弥生）/
    `meisai.overrideTekiyouId`（らくらく・`rakuraku_tekiyou` から摘要名を引く）を最優先で解決し、
    なければ従来どおりルール一致にフォールバックするようにした

- [ ] 弥生CSVの列構成が購買/預金とレシートで不一致（発覚 2026-09-09）
  - `GeneralReceiptOutputScreen.buildYayoiRow` は先頭に識別フラグ `"2000"` を持つ正式な25列。
    一方 `OutputConfirmScreen.buildPurchaseYayoiRow` / `buildDepositYayoiRow` は先頭が
    取引日付で `"2000"` がなく、列の並びも独自（借方部門・貸方部門の位置等が異なる）
  - 既定の会計ソフトが「らくらく」のため弥生の購買/預金CSVは実運用での検証が薄いとみられる
  - 修正方針：`buildYayoiRow`（レシート）と同じ25列レイアウトに購買/預金も揃える。
    共通化して `CsvUtils` か専用 Exporter に寄せる

- [ ] 弥生の税区分文字列がやよいの青色申告の実インポート仕様と一致しない可能性（発覚 2026-09-09）
  - `YayoiAccount.defaultTaxCategory` のDB値（`課対仕入10` / `課対仕入8` / `課税売上` /
    `非課税` / `対象外`）がそのまま弥生CSVの税区分列に出力される。やよいの青色申告が
    実際に受け付ける表記（`課対仕入込10%` 等）とは異なる可能性が高い
  - `docs/yayoi-csv-export-spec.md` は別プロジェクト "AoiroChobo"(C#) 由来の参考資料で、
    そこでは `課対仕入込10%` 等の表記。実機での弥生インポート検証が必要
  - 修正方針：検証後、出力時に `defaultTaxCategory` → 弥生税区分文字列へのマッピング表を挟む

- [x] レシート出力確認画面が `isExcluded` の品目を除外していない
      （発覚 2026-09-09 / 修正済み 2026-09-09）
  - `GeneralReceiptViewModel.buildCsvForExport` は `.filter { !it.isExcluded }` するが、
    出力確認画面が使う `loadOutputItems()` はフィルタしていないため、経費対象外に
    マークした品目も出力候補に並んでいた
  - 修正：`loadOutputItems()` でも `dao.getItemsForExport(...).filter { !it.isExcluded }` するようにした

- [x] 通帳CSV取込で「同一日・取引通番が空欄」の行は2件目以降が黙って捨てられる（発覚 2026-09-22 / 修正済み 2026-09-22）
  - `deposit_meisai` は `UNIQUE(transactionDate, transactionNumber)`、CSV取込は
    `insertAllIgnoreDuplicates`（`OnConflictStrategy.IGNORE`）を使う（`PassbookDataScreen.kt`）。
    銀行CSVの取引通番列が空の行が同じ日に複数あると、2件目以降が**無言でスキップ**されていた
    （エラーも件数表示も出ない）
  - 影響：取込件数がCSVの行数と合わない。ユーザーは気づけない
  - 修正：`DepositNumberAssigner` で取込時に空欄へ合成番号（`x01`/`x02` …）を入れる。
    既存の空欄行は DB v35 のマイグレーションで `x01` に埋めた（UNIQUE 制約により空欄は1日1件しか
    存在しないため衝突しない）。`TekiyouMatchingScreen` の assets CSV 取込にも同じ処理を入れた
  - 番号の形には AoiroChobo 側の条件が2つある（`docs/integration/REPLY-pc-2026-09-22.md` §3）：
    **`#` は使えない**（`externalId` の文字種 `[a-z0-9:_-]` 違反＋PC側が `…#2` を
    「別の取引として追加」に予約済み）／**同じCSVを取り込み直したら同じ番号になること**。
    後者は「日付・摘要・金額・メモが同じ既存の合成番号行を先に再利用する」ことで満たしている
    （`DepositNumberAssignerTest` で担保）

---

## 制約・注意事項

- **契約JSONの取込はフィールド名を間違えても落ちない**（2026-09-22 に実際に踏んだ）
  - Gson は JSON に無いフィールドを黙って null のままにするため、`memos` と `memoTemplates` の
    取り違えで**摘要0件のまま「取り込みました」と表示された**。例外も警告も出ない
  - 対策：`AoiroChoboVocabImporter` は配列そのものが欠けている場合と、キー・名前が無くて行を
    読み飛ばした場合に警告を出す。**取込後は必ず件数を確認すること**
  - Gson がコンストラクタのデフォルト値を使わないのも同根。非null宣言のフィールドに null が
    入り得るので、NOT NULL 列へ入れる前に採番し直す（`withRestoredUuid()`）

- `WARP_PX_PER_MM = 15.0` は変更禁止
  - 20px/mm に変更すると Step7 行切り抜きが 2.6 倍遅くなる（実測済み）
  - 変更する場合は全パイプラインの再計測が必要
- CameraX ImageAnalysis の 4K 解像度はデバイスによってサポート外の場合あり
  - 非対応デバイスでは自動フォールバックするが精度低下の可能性あり
- `Utils.bitmapToMat` は RGBA 4ch を返す
  - OpenCV 処理前に `COLOR_RGBA2BGR` 変換が必須。忘れると色チャンネル不一致でマスク精度が劣化する
- `fallbackToDestructiveMigration()` は削除済み（2026-07-12）
  - 以後、DBスキーマ変更時はマイグレーション追加が必須
  - マイグレーションを書き忘れるとデータ消失ではなく**起動時クラッシュ**になる点に注意
- `OcrCaptureScreen`/`OcrCaptureViewModel`・`SheetEditorScreen`/`SheetEditorViewModel`は
  本番UIから到達不能と判明したため、Phase6（2026-08-11）で削除済み
  - 実際にユーザーが使う撮影導線（伝票データ→編集→伝票追加→撮影）は
    `ReceiptInputScreen.kt`内の独自`CameraView`実装（`showCamera`状態＋非公開`CameraView`
    コンポーザブル）。実際のカメラプレビューUI（`CameraScreen.kt`・`CameraViewModel.kt`）と
    OCR失敗画面（`OcrErrorScreen`、`ui/CameraScreenForOcr.kt`）は本番導線でも共有利用しており
    削除していない
  - 撮影・OCR処理まわりに手を入れる際は、`ReceiptInputScreen.kt`が主導線であることを
    前提にすること
- **ML Kitは2026-08-11に依存ごと完全削除**（`build.gradle.kts`・`proguard-rules.pro`から除去）。
  一般レシート（`GeneralReceiptCaptureScreen.kt`）もJA伝票と同じくGemini Vision APIへの
  画像直接送信（`parseReceiptFromImage`）に一本化した。オフライン・APIキー未設定時の
  手動入力フォールバックは廃止済み（精度優先のユーザー判断、`onImageCaptured()`は
  単純にエラー表示するのみ）
- JA伝票グリッドの「挿入」「削除」は**以降の行を全部シフトする**（`ReceiptInputScreen.kt` の
  行アクション。`rows[i] = rows[i - 1]` / `rows[i] = rows[i + 1]`）。保存時の `itemNumber` は
  `rowNumber = index + 1` でリストの位置から振り直されるため、**行を1つ挿入するとそれ以降の
  行番号がすべてずれる**
  - 表示・印字上は問題ないが、AoiroChobo 連携の `externalId` を「伝票内の位置」で作ると、
    挿入・削除のたびに**同じ externalId が別の商品を指す**ことになる（PC 側は「内容が変わった」と
    解釈して黙って上書きする）
  - 対策済み（2026-09-22・DB v35）：`receipt_items.uuid` と `ReceiptRowData.uuid` を追加し、
    `externalId` は `ocr:purchase:{rowUuid}` にした。挿入・削除は行オブジェクトごとシフトするので
    `uuid` は行の内容に付いて動き、保存の全DELETE→全INSERTも跨ぐ
  - 残るトレードオフ：UUID は**再作成に弱い**（その月を消して入力し直すと全行が新しい
    `externalId` になる）。PC 側が「重複の可能性」検知で受け止める合意ができている
    （`docs/integration/transaction-import.md` §10）ため、スマホ側の追加対応は不要

- エンティティを `.copy()` せず**フィールドを列挙して組み直している保存処理**が複数ある。
  新しい列を足すと、その画面で保存しただけで**黙って null に戻る**。2026-09-23 に 3 か所で踏みかけた
  （`ProductListScreen` の商品編集ダイアログ、`GeneralReceiptViewModel.updateGroupDefaultAccount` と
  品目名リネーム）。いずれも AoiroChobo 用の `accountKey` / `memoKey` が、弥生の科目を選び直しただけで
  消える挙動だった。**エンティティに列を足したら、そのエンティティを `new` している箇所を全部見る**
  （`grep -rn "ProductMaster(" app/src/main`）
- `AoiroChoboVocabDao.getMemoTemplatesFor(ledgerType, direction)` は ledgerType で絞るため、
  **預金の摘要には使えない（常に0件）**。AoiroChobo は預金出納帳の摘要も `ledgerType = "Cash"` で持ち、
  `showInBank` で出し分けている（`"Bank"` の摘要は実在しない）。帳簿ごとの摘要候補は
  `util/AoiroChoboMemoRules.MemoTab` を使うこと。2026-09-24 時点で呼び出し元なし
- **Gson で読み書きするクラスはフィールド名がそのまま JSON のキー**（`AoiroChoboTransactionsBuilder` の出力・
  `AoiroChoboVocabularyFile`・バックアップ）。今は release でも `isMinifyEnabled = false` なので無事だが、
  難読化を有効にするとキーが黙って `a` `b` に変わる。有効にするなら keep ルールか `@SerializedName` が要る。
  また Android の実行時はフィールドを名前順で返すため、**端末で書いた JSON はキーがアルファベット順**になる
  （JVM のユニットテストでは宣言順）。JSON としては同じなので契約上は問題ない（2026-09-25 実機で確認）
- ~~横向きだとメニュー画面の下側のボタンが画面外に出てスクロールもできない~~（2026-09-25 発見・**同日修正**：
  トップ・JA購買伝票・JA預金・レシート領収書の 4 画面を `MenuColumn`（収まれば中央、収まらなければスクロール）に。
  簿記ソフト連携は元からスクロールできた）
- **`java.time` は使えない**（minSdk 24・coreLibraryDesugaring なし。API 26 未満の端末で実行時に落ちる）。
  日付は `java.util.Calendar` / `SimpleDateFormat` で扱う。`util/ValidationUtils.kt` が `LocalDate` を
  使っているが、2026-09-25 時点で呼び出し元が無いので実害はない（2026-09-25 発見）

---

## 未実装・将来対応

- [ ] `NtaInvoiceClient`（国税庁インボイス照会）の API 仕様が未検証（2026-07-12）
  - `API_BASE_URL` およびレスポンスJSONのフィールド名（`code`/`announcement`/`name`/`address`）は公式ドキュメントとの突合が未実施
  - アプリケーションID未設定時に実際に動作するかも未確認
  - 実機でのネットワーク照会テストが必要
- [ ] `RakurakuTekiyouScreen` が `SettingsScreen` から遷移できるか未確認
- [ ] 弥生会計・らくらく青色申告との直接連携
  - 現状は CSV ファイル出力のみ。API 連携は未実装
- [ ] 複数伝票の一括処理・一括確定機能
- [ ] ユニットテスト・UI テストの整備（現在ほぼ未実装）
- [ ] Windows マスタ管理アプリ（検討中）
  - adb pull → PC で商品マスタ・ocr_variants を編集 → adb push
  - 技術候補: Python + PySide6

- [ ] あおいろ帳簿（AoiroChobo）対応が JA 購買だけ（2026-09-25 時点）
  - レシート・預金は科目・摘要の選択も AI 提案もまだ弥生の科目だけ。用途の絞り込み（`aoirochobo_account_usage`）の
    レシート列・預金列は保存されるがどこにも効かない
  - 通帳の摘要一覧（`TekiyouMatchingScreen`）はあおいろモードで「Windows側で管理」と出たまま
  - `transactions.json` の組み立ては未着手
- [ ] らくらく摘要の検索（`tekiyou.searchKey` の部分一致 3 か所）はかな入力に対応していない。らくらく撤去で消える予定のため未対応

---

## 技術的負債

（`ProductNameCorrectorV3`の自動補正学習システム・`ocr_score_logs`/`correction_logs`
テーブルはPhase6（2026-08-11）で削除済み。関連する技術的負債はあわせて解消）
