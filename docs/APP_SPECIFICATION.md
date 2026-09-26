# JA仕訳変換 アプリケーション仕様書

アプリ全体を1枚で見渡すための概要。細部は次の文書が一次情報源で、食い違ったらそちらと実装を優先する。

| 知りたいこと | 見る場所 |
|---|---|
| 処理フロー・コンポーネント・DB テーブル | `docs/architecture.md` |
| 画面ごとの機能・ER 図 | `docs/functional-design.md` |
| ファイル配置 | `docs/repository-structure.md` |
| PC 会計アプリ（AoiroChobo）との契約 | `docs/integration/` |
| 既知の不具合・制約 | `docs/known-issues.md` |
| DB バージョン・テーブル | `ReceiptDatabase.kt` の `version` / `entities` |

（2026-02-21 までの版は ArUco 台紙・ML Kit 時代の記述で、現行コードと大きく食い違っていたため 2026-09-26 に全面的に書き直した。
旧版は git 履歴で見られる）

---

## 1. 概要

- **アプリ名**: JA仕訳変換
- **パッケージ**: `com.example.greenframeocr`（versionName 1.0）
- **minSdk** 24 / **targetSdk** 34 / **compileSdk** 34 / Kotlin JVM 17
- **利用者**: 農業経営者本人（1 端末・1 事業者）

農業経営の証憑を読み取り、会計ソフトに取り込める仕訳データを作る。扱う証憑は 3 種類。

| 部門 | 入力 | 読み取り方 |
|---|---|---|
| JA 購買 | 島原雲仙農業協同組合の購買代金請求明細書（A5 横・3 辺だけ緑枠） | カメラ → 緑枠検出・透視変換 → Gemini |
| JA 預金 | 通帳明細の CSV（最大 5 冊） | CSV 取込（共有・ファイル選択） |
| レシート・領収書 | 一般の店のレシート・手書き領収書 | カメラ → Gemini（枠検出なし） |

### 出力先（2026-09-23 決定）

| 会計ソフト | 形式 | 状態 |
|---|---|---|
| 弥生の青色申告 | 仕訳 CSV（Shift-JIS・25 列） | 購買・預金・レシートとも対応 |
| あおいろ帳簿（自作 PC アプリ AoiroChobo） | `transactions.json`（UTF-8） | **購買のみ対応**。預金・レシートはあおいろモードでも今はらくらく CSV が出る |
| らくらく青色申告農業版 | シンプル CSV（UTF-8） | **サポート終了が決定済み**。コードは撤去前。設定画面の選択肢からは外し、保存値が `RAKURAKU` なら読み出し時に `YAYOI` へ移す（2026-09-26） |

出力先は設定の「連携会計ソフト」（`AccountingSoftware`）で切り替える。弥生とあおいろは科目体系が別物なので、
商品や摘要の学習は両方の科目を別々の列で持つ（1 対 1 に変換しない）。

---

## 2. 技術スタック

| 分類 | 技術 |
|---|---|
| UI | Jetpack Compose + Material3（compose-bom 2023.10.01）・Navigation Compose 2.7.5 |
| カメラ | CameraX 1.3.0（ImageAnalysis 4K 3840×2160） |
| 画像処理 | OpenCV 4.9.0（緑枠検出・透視変換） |
| OCR | Gemini Vision API `gemini-3.5-flash-lite`（OkHttp 4.12.0 で REST 直接呼び出し。API キーは利用者が設定画面で入力） |
| DB | Room 2.6.0（KSP）。DB 名 `receipt_database` |
| 非同期 | Kotlin Coroutines 1.7.3 + Flow |
| JSON | Gson 2.10.1 |

**ML Kit は 2026-08-11 に依存ごと全廃**。OCR は常に通信が必要で、オフラインや API キー未設定のときはエラー表示のみ
（手入力への自動切り替えはしない。利用者の判断）。

---

## 3. 画面構成

画面遷移は `navigation/Navigation.kt` の NavHost。

```
メニュー (MenuScreen)
├─ JA購買伝票 (PurchaseMenuScreen)
│  ├─ 伝票データ (ReceiptInputScreen) ── 撮影・OCR・編集・保存をこの画面だけで行う
│  │  └─ 月次サマリー (MonthlySummaryScreen)
│  ├─ 購買データ確認 (YearSummaryScreen)
│  │  └─ 月次サマリー (MonthlySummaryScreen)
│  ├─ 購買品目別リスト (ProductListScreen)
│  └─ 出力確認画面 (OutputConfirmScreen・購買)
│
├─ JA預金 (DepositMenuScreen)
│  ├─ 通帳データ (PassbookDataScreen) ── CSV 取込・通帳の管理 (PassbookManageDialog)
│  ├─ 通帳摘要別リスト (TekiyouMatchingScreen)
│  └─ 出力確認画面 (OutputConfirmScreen・預金)
│
├─ レシート・領収書 (GeneralPurchaseMenuScreen)
│  ├─ レシート領収書撮影・OCR (GeneralReceiptCaptureScreen)
│  │  └─ 読み取り結果の確認 (GeneralReceiptConfirmScreen)
│  ├─ レシート領収書一覧 (GeneralReceiptListScreen)
│  ├─ 商品名・但し書きリスト (GeneralItemMatchingScreen)
│  ├─ 出力確認画面 (GeneralReceiptOutputScreen)
│  ├─ 登録番号・店舗・発行者一覧 (InvoiceStoreListScreen)
│  └─ 支払方法の科目設定 (ReceiptPaymentMethodRuleScreen)
│
├─ 簿記ソフト連携 (BookkeepingMenuScreen)
│  ├─ 弥生：勘定科目 (YayoiAccountSettingsScreen)
│  │  └─ 科目の編集 (YayoiAccountEditScreen)
│  ├─ あおいろ帳簿：勘定科目・摘要辞書 (AoiroChoboVocabularyScreen)
│  ├─ らくらく：勘定科目 (RakurakuAccountSettingsScreen)
│  └─ らくらく：摘要辞書 (RakurakuTekiyouScreen)
│
└─ 設定 (SettingsScreen)
```

- `CameraScreen` / `CameraScreenForOcr` / `TransformPreviewScreen` は他の画面に埋め込むコンポーザブルなので、ルートを持たない（不具合ではない）
- 摘要辞書（買掛・預金）の専用画面は、どこからも開けなかったので 2026-09-26 に削除した。摘要は「らくらく：摘要辞書」の各タブで扱う

### 一覧画面の共通 UI

- 文字サイズ（A- / A+、10〜20sp、全一覧で共通の `listFontSize`）はタイトルバー右上に置く
- 年の絞り込みと「作業年で固定」（`lockYearToWorking`）
- 検索・並び替え・絞り込みは折りたたみパネル（`ListFilterComponents.kt`）
- メニュー画面は横向きで収まらないときスクロールする（`MenuColumn.kt`）

---

## 4. 部門ごとの流れ

### 4.1 JA 購買

```
CameraX 4K → YuvToRgbConverter → GreenFrameDetector.process()
  緑枠検出 → 4 コーナー → 透視変換 3045×2220px（WARP_PX_PER_MM = 15.0・変更禁止）
→ GeminiReceiptClient.parseJaSheetFromImage()
  全体画像＋取引日列だけのクロップを並列に送る（Two-Pass）。429/500/503 は指数バックオフで再試行
→ JaSheetOcrMapper
  区分（一般購買・給油所・農業機械）の仮判定、商品マスタとの canonicalKey 照合
→ ReceiptInputScreen で確認・編集（セルを選んで部分再 OCR もできる）→ 保存
  検算（小計・合計）・要確認バッジ。保存後に CategoryRecalculator が月全体の区分を確定
→ 出力確認画面（購買）→ 弥生 CSV ／ らくらく CSV ／ あおいろ transactions.json
```

- 商品マスタ（`product_master`）に弥生の科目・あおいろの科目（`accountKey`）と摘要（`memoKey`）を別々に紐付ける
- あおいろの JSON は `util/AoiroChoboTransactionsBuilder.kt` が組み立てる（契約は `docs/integration/transaction-import.md`、schemaVersion 2）
- 出力した行には出力日時を記録し、「未出力のみ表示」で絞り込める

### 4.2 JA 預金

- 通帳（`passbooks`）は最大 5 冊。通帳ごとに名前・弥生の補助科目・あおいろの預金口座を持つ（DB v39〜）
- 通帳データ画面で CSV を取り込む。2 冊以上あるときは取込先を選ぶ（CSV に口座番号が無く自動判別できないため）
- 取引通番が空の行には合成番号を振る（`DepositNumberAssigner`・通帳ごと）。同じ通帳の同じ日付・通番は重複として飛ばす
- 摘要のパターン（`tekiyou_matching_rules`）ごとに科目を割り当て、通帳摘要別リストで学習・一括設定する。学習は通帳をまたいで共通
- 出力確認画面（預金）は通帳で絞り込める。弥生 CSV の預金側には通帳の補助科目が入る

### 4.3 レシート・領収書

- 撮影した画像をそのまま Gemini に送る（`parseReceiptFromImage`）。枠検出はしない
- 手入力での新規追加もできる（一覧画面の ＋）
- 発行者と適格請求書の登録番号は `invoice_stores` にまとめる
- 商品名・但し書きは canonicalKey でグループにまとめ、グループ単位の科目と個別の上書きを持つ（`general_item_master`）
- 支払方法（現金・カードなど）はキーワードから科目を決める（`receipt_payment_method_rules`）

---

## 5. データ

Room DB **v39**（2026-09-26 時点）。21 テーブル。

| 系統 | テーブル |
|---|---|
| JA 購買 | `receipt_items` / `sheet_data` / `monthly_data` / `product_master` |
| JA 預金 | `passbooks` / `deposit_meisai` / `tekiyou_matching_rules` |
| レシート | `general_receipts` / `general_receipt_items` / `invoice_stores` / `general_item_master` / `receipt_payment_method_rules` |
| 科目・摘要 | `yayoi_accounts` / `rakuraku_accounts` / `rakuraku_tekiyou` |
| あおいろ帳簿 | `aoirochobo_accounts` / `aoirochobo_memo_templates` / `aoirochobo_vocab_meta` / `aoirochobo_account_usage` |
| ML Kit 時代の名残 | `ocr_variants`（読み取りのみ現役・学習は非稼働） / `ocr_fallback_logs` |

- `fallbackToDestructiveMigration()` は使っていない。スキーマを変えたらマイグレーションを書かないと**起動時にクラッシュ**する
- あおいろの科目・摘要は PC が書き出した `vocabulary.json` を取り込んだもの（`AoiroChoboVocabImporter`）。科目は PC が持つ不透明な文字列キー `accountKey` で指す

### 初期データ（`assets/`）

`DatabaseInitializer` がテーブルが空のときに取り込む：`yayoi_accounts.csv` / `rakuraku_accounts.csv` /
`product_master.csv` / `ocr_variants.csv`。支払方法ルールはコード内の既定値から作る。
摘要辞書（`rakuraku_tekiyou`）は `rakurakutekiyou.csv` から、「らくらく：摘要辞書」と「通帳摘要別リスト」を
開くたびに差分で取り込む（`importTekiyouFromCsv`。DB に無い「大分類｜小分類｜摘要名」だけを足す）。
そのため画面で消した既定の摘要は、次に開いたときに戻ってくる。

### バックアップ

設定画面の「エクスポート」「インポート」で、全データ・マスタデータ・購買伝票・通帳データ・レシート・領収書の
どれかを JSON ファイルにして端末外へ出し入れする。「データクリア」も同じ種類別に削除できる（確認あり）。

---

## 6. 設定（`AppPreferences`・SharedPreferences）

| 項目 | キー | 既定値 |
|---|---|---|
| 作業年（令和） | `era_year` | 7 |
| 作業年で固定 | `lock_year_to_working` | false |
| 撮影時の月 / 年月固定 | `current_issue_month` / `fix_year_month` | 1 / false |
| 連携会計ソフト | `accounting_software` | `YAYOI`（`RAKURAKU` は読み出し時に `YAYOI` へ移す） |
| Gemini API キー | `gemini_api_key` | なし |
| 累計トークン使用量 | `cumulative_*_tokens` | 0（リセット可） |
| 一覧の文字サイズ | `list_font_size` | 14 |
| 預金の金額を隠す | `deposit_hide_amount` | false |
| 前回選んだ通帳 | `last_passbook_id` | 1 |
| カメラ解像度 / プレビュー / フラッシュ | `camera_resolution` / `camera_preview` / `camera_flash` | 3840x2160 / false / false |
| 最低シャープネス | `min_sharpness` | 1000 |
| テーマ / ダークモード | `theme_preset` / `dark_mode` | GREEN / SYSTEM |

---

## 7. 権限・外部連携

**権限**（`AndroidManifest.xml`）：`INTERNET`・`ACCESS_NETWORK_STATE`（Gemini）、`CAMERA`、
`READ_EXTERNAL_STORAGE`・`WRITE_EXTERNAL_STORAGE`（maxSdk 32）、`READ_MEDIA_IMAGES`

**受け取るインテント**：CSV（`text/csv`・`text/comma-separated-values`・`text/plain`・`application/octet-stream`）の
SEND と、`.csv` の VIEW。通帳 CSV を他アプリから共有してそのまま取り込むため。

---

## 8. 守るべき技術ルール

`CLAUDE.md` の「重要な技術ルール」が正。要点だけ再掲する。

- `Utils.bitmapToMat` は RGBA を返す。OpenCV の前に BGR へ、Gemini に渡す前に RGBA へ戻す
- 透視変換の解像度 `WARP_PX_PER_MM = 15.0` は上げても下げてもいけない（下げると Gemini の読み取り精度に直接効く）
- エンティティに列を足したら、そのエンティティをフィールド列挙で作り直している箇所を全部直す（黙って null に戻る）
- `java.time` は minSdk 24 で使えない（`SimpleDateFormat` / `GregorianCalendar` を使う）

---

**最終更新**: 2026-09-26
