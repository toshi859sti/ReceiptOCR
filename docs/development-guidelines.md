# 開発ガイドライン

## コーディング規約

### 基本方針
- Kotlin 公式スタイルガイドに従う
- Jetpack Compose のベストプラクティスに従う
- UI・ドメイン・データ層の依存方向を守る（UI → ViewModel → Util → Data）
- `util/` のクラスは UI や ViewModel に依存させない

### 命名規則

| 対象 | 規則 | 例 |
|---|---|---|
| クラス名 | PascalCase | `GreenFrameDetector`, `OcrCaptureViewModel` |
| 関数名 | camelCase | `processUnderlayingBase`, `correctProductName` |
| 変数名 | camelCase | `warpedBitmap`, `isAlphaFullWidth` |
| 定数 | SCREAMING_SNAKE_CASE | `WARP_PX_PER_MM`, `MIN_STABLE_FOCUS_FRAMES` |
| DB テーブル名 | snake_case | `receipt_items`, `product_master` |
| DB カラム名 | camelCase（Room） | `productName`, `issueYear` |
| Route 文字列 | snake_case | `receipt_input`, `passbook_data` |
| Composable 関数 | PascalCase | `ReceiptInputScreen`, `ItemEditDialog` |

### Compose ガイドライン
- 1画面 = 1ファイル（`*Screen.kt`）
- ダイアログ・サブコンポーネントは同一ファイルに `private fun` で定義
- `@OptIn(ExperimentalMaterial3Api::class)` が必要な API（`FilterChip` など）は関数単位で付与
- `remember` / `collectAsState` は Composable の先頭でまとめて宣言

### 商品名・OCRテキストの文字種ルール

#### 商品名エディタの文字種

| 文字種 | 規則 |
|---|---|
| 通常文字 | 全角で入力・保存する |
| 英字 | トグルボタンで半角入力に切り替え可能。デフォルトは全角 |
| 数字 | 常時全角。半角入力は受け付けない |
| スペース | 常時全角スペース（`　`）に変換 |
| 単位（kg・mm・cm・ml 等） | 例外的に半角許容 |

- 文字数制限：**全角 30 文字**（半角 2 文字 = 全角 1 文字換算）。伝票印字の実際の最大文字数（全角20文字）に
  余裕を持たせた値で、伝票の記載と完全に一致させる必要はない
- 半角文字数が奇数（ペアになっていない）でも入力・保存を妨げない（2026-08-10、ユーザー判断で撤廃）
- 「一括全角」ボタン：現在入力済みのテキスト全体を強制全角化する
- 差分検出方式：カーソル位置以降の**新規入力部分のみ**トグル状態（半角/全角）を適用する。既存テキストは変更しない

#### ocr_variants の全角化ルール（新規書き込みは非稼働、既存データの参照時のみ関係）

`ocr_variants`への書き込み（`registerLearning()`）はPhase6（2026-08-11）以降呼び出し元がなく
非稼働。既存データはCSV出力時の商品名照合フォールバック（読み取り専用）で参照され続けるため、
過去に保存された`variantText`を扱う際は以下の前提が残る。

- `ocr_variants.variantText` は **全角化済みテキストで保存されている**（OCR 生テキストのまま
  ではない）
- マッチング時の入力テキストも全角化済みであることを前提とする（マッチング関数内で二重変換しない）
- DB に保存済みの `variantText` を検索・比較する際は、検索キーも事前に全角化してから渡す

### Room / DB ガイドライン
- バージョンアップ時は必ずマイグレーションを `ReceiptDatabase.kt` に追加する
- `fallbackToDestructiveMigration()` は開発中のみ。本番リリース前に必ず削除する
- エンティティの変更は DAO・マイグレーション・`@Database(entities=[...])` をセットで更新する

### OpenCV ガイドライン

**変換フロー（必ず守ること）:**

```
Bitmap（RGBA）
  ↓ Utils.bitmapToMat
Mat（RGBA 4ch）
  ↓ Imgproc.cvtColor(rgbaMat, bgrMat, COLOR_RGBA2BGR)
Mat（BGR 3ch）← OpenCV 処理はここで行う
  ↓ Imgproc.cvtColor(bgrMat, rgbaMat, COLOR_BGR2RGBA)  ← Gemini に渡す場合のみ必要
Mat（RGBA 4ch）
  ↓ Utils.matToBitmap(rgbaMat, bitmap)
Bitmap（RGBA）→ JPEG圧縮・Base64化して GeminiReceiptClient 経由で Gemini Vision API へ
```

- `Utils.bitmapToMat` は RGBA 4ch を返す → **必ず `COLOR_RGBA2BGR` で変換してから OpenCV 処理する**
- OpenCV で加工した Mat を Gemini（`GeminiReceiptClient`）に渡す場合は **`COLOR_BGR2RGBA` で戻してから `matToBitmap` を呼ぶ**。逆順のまま渡すと色チャンネルが反転し OCR 精度に悪影響が出る
- `toBitmap()` 拡張関数は BGR→RGBA 変換を内包しているため、src は常に BGR 3ch のまま扱う
- `Mat` と `Bitmap` は try-finally でリリースする（`mat.release()` / `bitmap.recycle()`）

### 非同期処理
- ViewModel の処理は `viewModelScope.launch` で実行
- DB 操作は `Dispatchers.IO` で実行
- UI 更新は `StateFlow` / `MutableStateFlow` を通じて行う
- `withContext(Dispatchers.Main)` は UI 更新時のみ使用

---

## Git 規約

### ブランチ戦略
- `master`: 安定版・リリース済みコード
- `feature/xxx`: 機能追加・改善
- `fix/xxx`: バグ修正
- 現在作業ブランチ: `feature/major-refactor`

### コミットメッセージ
英語で記述。形式は以下の通り：

```
<type>: <summary>

[optional body]
```

| type | 用途 |
|---|---|
| `Add` | 新機能追加 |
| `Fix` | バグ修正 |
| `Refactor` | 機能変更を伴わないリファクタリング |
| `Update` | 既存機能の改善・設定変更 |
| `Remove` | コード・ファイル削除 |

例:
```
Fix deposit meisai grouping bug and improve product name editor
Add adaptive icon: white icon on dark green background (#0E6C48)
```

---

## テスト規約

- 現在ユニットテスト・UI テストはほぼ未実装
- 動作確認は実機テストで行う。`DebugCaptureScreen`はPhase6（2026-08-11）で削除済みのため、
  現在は本番導線（`ReceiptInputScreen.kt`等）を直接使って確認する
- 新機能追加時はパイプライン全体を実機で確認する

---

## セキュリティ考慮事項

- ユーザー入力はすべて `ValidationUtils` でバリデーションする
- CSV エクスポート先はシステムの `SAF（Storage Access Framework）` 経由で取得する
- DB ファイルはアプリ内部ストレージに保存（外部公開しない）
- `WRITE_EXTERNAL_STORAGE` は API 32 以下にのみ要求する（API 33+ は `READ_MEDIA_IMAGES`）

---

## Gemini API キー設定手順（課金有効化キー）

JA購買伝票OCR（`GeminiReceiptClient`）はユーザー個別の Gemini APIキー方式（設定画面で入力・
`AppPreferences.geminiApiKey` に保存）。無料枠キーは入力画像・出力内容が Google 側のモデル
改善に利用され得るため、伝票の取引先情報を扱う本アプリでは **課金有効化キー（従量課金制）を
推奨する**（設定画面にも注意文言を表示済み）。

以下は Google Cloud Console での課金有効化キー取得手順（2026-08-09 実施時の実務メモ）。

1. [Google Cloud Console](https://console.cloud.google.com/) で新規プロジェクトを作成する
   （既存の無関係な Firebase プロジェクト等を流用しない。混在すると請求管理が煩雑になる）
2. 「お支払い」→「請求先アカウント」で新規の請求先アカウントを作成する
   （プロジェクト作成とは別画面。ここが分かりにくいので注意）
3. 請求先アカウントを「従量課金制フルアカウント」にアップグレードする
   （無料トライアルのままだと API が利用できない場合がある）
4. 前払いクレジットをチャージする（少額で開始し、利用量を見ながら追加する）
5. 「予算とアラート」で月間の予算アラートを設定する（想定外の高額請求を防ぐため）
6. プロジェクトを対象の請求先アカウントに紐付ける
7. [Google AI Studio](https://aistudio.google.com/) または Cloud Console の
   「APIとサービス」→「認証情報」から、手順1で作成したプロジェクト配下で APIキーを発行する
8. アプリの設定画面（`SettingsScreen` の「Gemini APIキー」欄）に発行したキーを入力・保存する

**料金の目安**（2026-08-08 実測、Phase0 検証時点）:
- `gemini-3.6-flash`：JA伝票1枚あたり約 ¥5〜6（全行正解・約20秒/回）
- `gemini-3.5-flash-lite`：JA伝票1枚あたり約 ¥0.6（数量列以外は完全正解・約4秒/回）
- 本番採用は `gemini-3.5-flash-lite`（数量列はCSV出力で未使用のため精度要件から除外）

---

## パフォーマンスガイドライン

- `GreenFrameDetector.process()` は UI スレッドで呼ばない（`Dispatchers.Default` で実行）
- `WARP_PX_PER_MM = 15.0` は変更禁止（20px/mm は Step7 が 2.6 倍遅くなる）
- 本番モードでは `debugMode=false`（Step7 スキップ・約 700ms 削減）
- Gemini Vision API 呼び出しはネットワーク往復（`gemini-3.5-flash-lite`で約4秒/回）のため、
  タスク完了まで待機する設計にする（429/500/503 は `callWithRetry()` で指数バックオフ）
