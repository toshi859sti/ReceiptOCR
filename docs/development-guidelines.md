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

### Room / DB ガイドライン
- バージョンアップ時は必ずマイグレーションを `ReceiptDatabase.kt` に追加する
- `fallbackToDestructiveMigration()` は開発中のみ。本番リリース前に必ず削除する
- エンティティの変更は DAO・マイグレーション・`@Database(entities=[...])` をセットで更新する

### OpenCV ガイドライン
- `Utils.bitmapToMat` は RGBA 4ch を返す → **必ず `Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)` でBGR変換してから処理する**
- `Mat` と `Bitmap` は try-finally でリリースする（`mat.release()` / `bitmap.recycle()`）
- `toBitmap()` は内部で BGR→RGBA 変換するため、src は常に BGR 3ch のまま扱う

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
