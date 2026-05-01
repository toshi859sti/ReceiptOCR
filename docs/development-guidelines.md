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
| Route 文字列 | snake_case | `ocr_capture`, `sheet_editor` |
| Composable 関数 | PascalCase | `SheetEditorScreen`, `ItemEditDialog` |

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
| 単位（kg・mm・cm・ml 等） | 例外的に半角許容。常に偶数文字数でなければならない |

- 文字数制限：**全角 20 文字**（半角 2 文字 = 全角 1 文字換算）
- 「一括全角」ボタン：現在入力済みのテキスト全体を強制全角化する
- 差分検出方式：カーソル位置以降の**新規入力部分のみ**トグル状態（半角/全角）を適用する。既存テキストは変更しない

#### ocr_variants の全角化ルール

- `ocr_variants.variantText` は **全角化済みテキストで保存する**。OCR 生テキストをそのまま入れない
- OCRProcessor がエディタへ結果を渡す前に全文字全角化処理を行う
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
  ↓ Imgproc.cvtColor(bgrMat, rgbaMat, COLOR_BGR2RGBA)  ← ML Kit に渡す場合のみ必要
Mat（RGBA 4ch）
  ↓ Utils.matToBitmap(rgbaMat, bitmap)
Bitmap（RGBA）→ InputImage.fromBitmap で ML Kit へ
```

- `Utils.bitmapToMat` は RGBA 4ch を返す → **必ず `COLOR_RGBA2BGR` で変換してから OpenCV 処理する**
- OpenCV で加工した Mat を ML Kit に渡す場合は **`COLOR_BGR2RGBA` で戻してから `matToBitmap` を呼ぶ**。逆順のまま渡すと色チャンネルが反転し OCR 精度に悪影響が出る
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
- 動作確認は実機テスト（`DebugCaptureScreen` 利用）で行う
- 新機能追加時は `DebugCaptureScreen` でパイプライン全体を確認する

---

## セキュリティ考慮事項

- ユーザー入力はすべて `ValidationUtils` でバリデーションする
- CSV エクスポート先はシステムの `SAF（Storage Access Framework）` 経由で取得する
- DB ファイルはアプリ内部ストレージに保存（外部公開しない）
- `WRITE_EXTERNAL_STORAGE` は API 32 以下にのみ要求する（API 33+ は `READ_MEDIA_IMAGES`）

---

## パフォーマンスガイドライン

- `GreenFrameDetector.process()` は UI スレッドで呼ばない（`Dispatchers.Default` で実行）
- `WARP_PX_PER_MM = 15.0` は変更禁止（20px/mm は Step7 が 2.6 倍遅くなる）
- 本番モードでは `debugMode=false`（Step7 スキップ・約 700ms 削減）
- ML Kit OCR はキャンセル不可のため、タスク完了まで待機する設計にする
