# Receipt OCR Project - Change Log

過去の改善履歴を時系列で記録。

---

## 2026-02-01

### Remoniへのリブランディング & UI改善

**変更内容:**

#### 1. アプリ名変更
- **ReceiptOCR → Remoni**（receipt + money の造語）
- strings.xml の app_name を変更
- MenuScreen.kt のタイトルを変更
- 起動画面から年号表示を削除

#### 2. アプリアイコン作成
- レモンをモチーフにしたアダプティブアイコン
- 前景: レモン形状（黄色）+ 葉（緑）のVector Drawable
- 背景: 黄色グラデーション
- ファイル: `ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `ic_launcher.xml`, `ic_launcher_round.xml`

#### 3. 伝票データ画面の年表示変更
- 「R７年」→「令和７年」の漢字表記に変更

#### 4. 購買出力確認画面の改善
- **小計・合計行を除外**: productNameに「小計」「合計」を含む行をフィルタリング
- **日付を西暦変換**: 令和年 → 西暦（2018 + 令和年）

#### 5. 通帳データのソート順変更
- 取引日降順 → 取引日昇順に変更（DepositMeisaiDao.kt）

#### 6. 設定画面のデータ管理UI変更
- エクスポート/インポート/データクリアの各操作に対象選択ラジオボタンを追加
- 選択肢: 全データ、購買伝票、通帳データ、マスタデータ
- DataType enum を追加
- 2行レイアウト（1行目: 全データ, マスタデータ / 2行目: 購買伝票, 通帳データ）

#### 7. フラッシュ消灯問題の修正
- OCR処理開始時に確実にフラッシュ消灯するよう強化（CameraScreen.kt）

#### 8. 出力確認画面のグリッドレイアウト改善
- 固定幅 + 横スクロール → weight-basedフレキシブルレイアウトに変更
- 画面幅に合わせて自動調整、横スクロール不要に
- 列比率: 出力チェック(40dp固定) / 日付(weight 1.2) / 摘要メモ(weight 2) / 金額(weight 1)
- 長いテキストは省略記号(...)で表示

**変更ファイル:**
- `app/src/main/res/values/strings.xml` - app_name変更
- `app/src/main/java/com/example/receiptorc/ui/MenuScreen.kt` - タイトル変更、年号削除
- `app/src/main/java/com/example/receiptorc/ui/ReceiptInputScreen.kt` - 年表示変更
- `app/src/main/java/com/example/receiptorc/data/DepositMeisaiDao.kt` - ソート順変更
- `app/src/main/java/com/example/receiptorc/ui/OutputConfirmScreen.kt` - 小計除外、西暦変換、グリッドレイアウト変更
- `app/src/main/java/com/example/receiptorc/ui/SettingsScreen.kt` - データ管理UI変更
- `app/src/main/java/com/example/receiptorc/ui/CameraScreen.kt` - フラッシュ消灯強化
- `app/src/main/java/com/example/receiptorc/data/ReceiptDao.kt` - deleteAll系メソッド追加
- `app/src/main/res/drawable/ic_launcher_foreground.xml` - 新規（レモンアイコン）
- `app/src/main/res/drawable/ic_launcher_background.xml` - 新規（背景グラデーション）
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` - アダプティブアイコン定義
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` - 丸形アイコン定義

**注意事項:**
- applicationIdは`com.example.receiptorc`のまま維持（既存データ保護のため）
- パッケージ名も変更なし

---

## 2026-01-31 (2)

### 設定画面の大幅改修とデータ管理機能の整理

**変更内容:**

#### 1. 設定画面の構成変更
- **購買部門**セクションに統合:
  - 入力年設定
  - カメラ設定（フラッシュ、カメラ情報）
  - OCR学習状況
- **預金部門**セクション:
  - 金額を非表示（マスク機能）

#### 2. データ管理のインポート/エクスポート整理
- **全データ**: 購買伝票 + 通帳 + マスタデータを一括管理
- **購買伝票**: ReceiptItem, SheetData, MonthlyData
- **通帳データ**: DepositMeisai
- **マスタデータ**:
  - 商品マスタ (product_master)
  - OCR学習データ (ocr_variants)
  - 摘要辞書 (rakuraku_tekiyou)
  - 摘要マッチングルール (tekiyou_matching_rules)

#### 3. 預金部門の金額非表示機能
- 設定で有効化すると金額が `***` で表示される
- 対象画面: 通帳データ画面、出力確認画面（預金）

#### 4. 通帳摘要別リストの改善
- 「通帳データありのみ」フィルタチェックボックス追加
- 通帳データ再読込時にマッチングルールを保持（らくらく摘要との紐付けを維持）

#### 5. メイン画面の修正
- 「令和○年度」→「令和○年」に変更

**変更ファイル:**
- `MenuScreen.kt` - 年度表示修正
- `SettingsScreen.kt` - 大幅改修、データ管理機能整理
- `AppPreferences.kt` - depositHideAmount設定追加
- `PassbookDataScreen.kt` - 金額マスク機能追加
- `OutputConfirmScreen.kt` - 金額マスク機能追加
- `TekiyouMatchingScreen.kt` - フィルタ機能追加、ルール保持機能
- `Navigation.kt` - AppPreferences引数追加
- `ProductMasterDao.kt` - insertIgnore追加
- `RakurakuTekiyouDao.kt` - insertIgnore追加
- `OcrVariantDao.kt` - insertIgnore追加

**新規作成ファイル:**
- `docs/DATABASE_SCHEMA.md` - データベース構造ドキュメント

---

## 2026-01-31

### 出力確認画面の大幅改善

**変更内容:**

#### 1. 期間選択機能の追加
- 画面上部に開始日・終了日を選択するDatePickerを配置
- 「解除」ボタンで期間フィルタをクリア可能
- 選択した期間内のデータのみ表示

#### 2. ソート順の変更
- **預金部門**: 日付 → 取引通番（昇順）に変更
- **購買部門**: 日付 → 伝票番号 → 行番号（昇順）に変更

#### 3. ID列の非表示化
- 両部門のグリッドからID列を削除

#### 4. 摘要未設定行のハイライト
- 摘要が空白の行は薄い赤（`#FFEBEE`）で背景色表示

#### 5. スクロール連動
- ヘッダーとデータ行で共通のScrollStateを使用
- 横スクロールが連動するように修正

#### 6. 列構成の変更
- **預金部門**: 入金・出金列を「金額」1列に統合（出金はマイナス表示）
- **購買部門**: 「購入」列のキャプションを「金額」に変更
- **両部門**: 摘要・メモ列を1列に統合（上段:摘要、下段:メモの2行表示）

### 設定画面 - 購買品使用回数の再カウント機能

**変更内容:**

- マスタデータ管理セクションに「購買品の使用回数を再カウント」機能を追加
- 登録済み購買伝票データ（ReceiptItem）から各商品名の出現回数を集計
- ProductMasterの`frequencyCount`を更新
- 処理結果（更新件数、伝票件数）を表示

**変更ファイル:**
- `OutputConfirmScreen.kt` - 期間選択、ソート順、列構成変更
- `SettingsScreen.kt` - 購買品使用回数再カウント機能追加

---

## 2026-01-30

### 出力確認画面の実装 & 外部アプリ共有対応

**変更内容:**

#### 1. キャプション変更
- 「預金摘要マッチング」→「通帳摘要別リスト」(`TekiyouMatchingScreen.kt`)
- 「購買伝票」→「伝票データ」(`ReceiptInputScreen.kt`)

#### 2. 購買部門の出力確認画面 (`OutputConfirmScreen.kt`)
- グリッド表示: 出力チェック、ID、日付、摘要、メモ、購入
- ソート: 取引日降順 → 伝票番号降順 → 行番号降順
- 摘要取得: 商品名 → ProductMaster → 買掛摘要 (kaikakeTekiyouId → RakurakuTekiyou.tekiyouName)
- メモ: 商品名 (productName)
- CSV出力: ヘッダー付き「購買_日時.csv」(ID,日付,摘要,メモ,購入)

#### 3. 預金部門の出力確認画面 (`OutputConfirmScreen.kt`)
- グリッド表示: 出力チェック、ID、日付、摘要、メモ、入金、出金
- ソート: 取引日降順 → 連番降順
- 摘要取得: 正規化摘要 → TekiyouMatchingRule → RakurakuTekiyou.tekiyouName
- メモ: 通帳摘要原文 (DepositMeisai.tekiyou)
- 金額表示: 正→入金列、負→出金列
- CSV出力: ヘッダー付き「預金_日時.csv」(ID,日付,摘要,メモ,入金,出金)

#### 4. 共通UI機能
- 全選択/全解除ボタン
- 行タップまたはチェックボックスで個別切り替え
- 下部に出力数とCSV出力ボタン

#### 5. 外部アプリからの共有対応 (`AndroidManifest.xml`, `MainActivity.kt`)
- ACTION_SEND: text/csv, text/comma-separated-values, text/plain, application/octet-stream
- ACTION_VIEW: CSVファイルを直接開く
- 共有されたCSVは通帳データ画面で自動取り込み

**変更ファイル:**
- `TekiyouMatchingScreen.kt` - キャプション変更
- `ReceiptInputScreen.kt` - キャプション変更
- `OutputConfirmScreen.kt` - 完全書き換え
- `Navigation.kt` - databaseパラメータ追加
- `AndroidManifest.xml` - intent-filter追加
- `MainActivity.kt` - 共有インテント処理追加

---

### メニュー構造の大幅リファクタリング

**背景:**
- メインメニューが多すぎて分かりにくい
- 購買部門と預金部門の機能を明確に分離したい

**変更内容:**

#### 1. メインメニューを3項目に集約
- 購買部門
- 預金部門
- 設定

#### 2. 購買部門サブメニュー (`PurchaseMenuScreen.kt`)
- 伝票データ (ReceiptInputScreen)
- 購買品目別リスト (ProductListScreen)
- 買掛摘要辞書 (KaikakeTekiyouScreen)
- 出力確認画面 (OutputConfirmScreen)

#### 3. 預金部門サブメニュー (`DepositMenuScreen.kt`)
- 通帳データ (PassbookDataScreen)
- 通帳摘要別リスト (TekiyouMatchingScreen)
- 預金摘要辞書 (YokinTekiyouScreen)
- 出力確認画面 (OutputConfirmScreen)

#### 4. 新規画面作成
- `KaikakeTekiyouScreen.kt` - 買掛摘要辞書（使用/摘要名/Key/科目/事業 グリッド）
- `YokinTekiyouScreen.kt` - 預金摘要辞書（入金/出金トグル付き）
- `PassbookDataScreen.kt` - 通帳データ（CSV取込、重複マージ対応）

---

### 摘要辞書のチェックボックス機能

**変更内容:**

1. **RakurakuTekiyouエンティティ拡張**
   - `isEnabled: Boolean` フィールド追加（使用する/しない）

2. **ProductMasterの紐付け変更**
   - `yayoiAccountId` / `rakurakuAccountId` → `kaikakeTekiyouId` に統合
   - 買掛摘要と直接紐付け

3. **UI更新**
   - 買掛摘要辞書・預金摘要辞書にチェックボックス列追加
   - 選択ダイアログで有効な摘要のみ表示

4. **データベースマイグレーション (v10 → v11)**

---

### 設定画面の改善

**変更内容:**

1. **年号設定の変更**
   - 「令和何年」→「入力年」
   - サブタイトル: 「令和X年 / 西暦YYYY年」両方表示
   - 月設定を削除

2. **OCR学習状況画面の日本語化**
   - AUTO → 自動
   - CONFIRMED → 確定
   - LOCKED → 固定

---

### 通帳データの機能強化

**変更内容:**

1. **日付表示の完全化**
   - MM-DD → YYYY-MM-DD 形式

2. **降順ソート**
   - DAOで `ORDER BY transactionDate DESC` 実装済み

3. **CSV取込の重複マージ**
   - 取引日 + 取引通番 で既存チェック
   - 重複はスキップ、新規のみ追加
   - 結果表示: 「X件追加（Y件は既存のためスキップ）」

---

### ProductMaster削除時のOCR学習データ保護

**背景:**
- ProductMaster削除時、関連OcrVariantが孤立データになる問題

**変更内容:**

1. **削除前警告ダイアログ**
   - 関連OCR学習データ数を表示
   - 「⚠️ この商品には X件 のOCR学習データがあります」

2. **CASCADE削除**
   - ProductMaster削除時、関連OcrVariantも一緒に削除

**実装:**
- `OcrVariantDao.countByProductId()` 追加
- `ProductListScreen` の削除ダイアログ改修

---

### OCR学習データの手動削除機能

**変更内容:**

1. **OcrLearningStatusScreen改修**
   - 各パターンカードに削除ボタン（ゴミ箱アイコン）追加
   - 削除確認ダイアログ表示

2. **削除フロー**
   - パターン一覧 → 削除アイコン → 確認ダイアログ → 削除 → リスト更新

---

### マスタデータのエクスポート/インポート機能

**背景:**
- 購買品リストとOCR学習データを別端末に移行したい
- バックアップ機能が必要

**変更内容:**

1. **設定画面に新セクション追加**
   - 「📦 マスタデータ管理」

2. **エクスポート機能**
   - ProductMaster + OcrVariant を JSON形式で出力
   - ファイル名: `master_backup_日時.json`

3. **インポート機能（マージ方式）**
   - 購買品: 商品名で重複判定
   - 学習データ: normalizedText + productId で重複判定
   - 旧ID → 新ID のマッピングを自動処理
   - 結果表示: 「購買品 +X件 (既存Y件) / 学習 +X件 (既存Y件)」

**エクスポートファイル形式:**
```json
{
  "exportDate": "2026-01-30 01:23:45",
  "version": 1,
  "productMasters": [...],
  "ocrVariants": [...]
}
```

**新規ファイル/変更ファイル:**
- `MenuScreen.kt` - 3項目に変更
- `PurchaseMenuScreen.kt` - 新規
- `DepositMenuScreen.kt` - 新規
- `KaikakeTekiyouScreen.kt` - 新規
- `YokinTekiyouScreen.kt` - 新規
- `PassbookDataScreen.kt` - 新規/改修
- `ProductListScreen.kt` - 削除警告追加
- `OcrLearningStatusScreen.kt` - 日本語化、削除機能追加
- `SettingsScreen.kt` - 年設定変更、マスタデータ管理追加
- `RakurakuTekiyou.kt` - isEnabled追加
- `RakurakuTekiyouDao.kt` - getEnabledByCategory追加
- `ProductMaster.kt` - kaikakeTekiyouId変更
- `OcrVariantDao.kt` - getAll, countByProductId追加
- `DepositMeisaiDao.kt` - findByDateAndNumber追加
- `ReceiptDatabase.kt` - Migration 10→11
- `Navigation.kt` - 新規ルート追加

---

## 2026-01-24

### らくらく青色申告 摘要辞書・マッチング機能

**背景:**
- らくらく青色申告農業版の摘要辞書をアプリで管理したい
- 預金明細CSVの摘要と摘要辞書を紐付けて自動仕訳を実現したい

**新規画面:**

#### 1. 摘要辞書画面 (`RakurakuTekiyouScreen.kt`)

**機能:**
- らくらく青色申告農業版の摘要辞書を表示・編集
- カテゴリ切り替え: 現金 / 預金 / 売掛 / 買掛
- サブカテゴリ切り替え:
  - 現金・預金: 入金 / 出金
  - 売掛: 販売 / 入金
  - 買掛: 購入 / 出金
- グリッド表示（カテゴリにより列数が異なる）:
  - 現金・預金: 摘要名, 検索文字, 科目, 税率, 事業割合, 共有 (6列)
  - 売掛・買掛: 摘要名, 検索文字, 科目, 税率, 事業割合 (5列)
- 追加・編集・削除機能

**データ:**
- 初期データ: `assets/rakurakutekiyou.csv` から自動インポート
- 保存先: Room DB (`rakuraku_tekiyou` テーブル)

#### 2. 預金摘要マッチング画面 (`TekiyouMatchingScreen.kt`)

**機能:**
- 預金明細CSVの摘要パターンを自動抽出
- 摘要辞書（預金カテゴリのみ）とのマッチング設定
- 入金/出金を金額の符号から自動判定

**パターン正規化（オフラインAIなし）:**
```
例: "いんげん インゲン   1029" → "いんげん インゲン"
例: "電気料 デンリヨク 07-09" → "電気料 デンリヨク"
```
末尾の日付・数字を正規表現で除去するルールベース処理。

**UI構成:**
- 統計カード: 合計 / マッチ済 / 未マッチ 件数
- フィルタチップ: 全て / 入金(緑) / 出金(赤)
- マッチングルールリスト:
  - 入金/出金ラベル（色分け）
  - マッチング状態アイコン（✓ or ⚠）
  - 正規化された摘要パターン
  - マッチング先の摘要名・科目
- 編集ダイアログ:
  - 預金カテゴリのみ表示（入金/出金は自動選択）
  - 検索機能付き摘要リスト

**データ:**
- 預金明細: `assets/meisai.csv` から自動インポート（デモ用）
- 保存先: Room DB (`deposit_meisai`, `tekiyou_matching_rules` テーブル)

**新規ファイル:**
- `RakurakuTekiyou.kt` - 摘要辞書エンティティ
- `RakurakuTekiyouDao.kt` - 摘要辞書DAO
- `RakurakuTekiyouScreen.kt` - 摘要辞書画面
- `DepositMeisai.kt` - 預金明細エンティティ
- `DepositMeisaiDao.kt` - 預金明細DAO
- `TekiyouMatchingRule.kt` - マッチングルールエンティティ
- `TekiyouMatchingRuleDao.kt` - マッチングルールDAO
- `TekiyouMatchingScreen.kt` - マッチング画面

**データベース更新:**
- v9: `rakuraku_tekiyou` テーブル追加
- v10: `deposit_meisai`, `tekiyou_matching_rules` テーブル追加

**メニュー追加:**
- 「摘要辞書」ボタン
- 「摘要マッチング」ボタン

---

## 2026-01-23

### 勘定科目設定画面のUI改善

**背景:**
- 区分Aがツリー展開形式で一覧性が悪い
- 区分A/B/Cの並び順がCSV読み込み順で固定されていない
- 勘定科目の表示項目が不足・2行表示で見づらい

**変更内容:**

1. **区分Aセレクター導入**
   - ツリー展開形式から、横スクロール可能なFilterChipセレクターに変更
   - 「資産」「負債」「資本」「経常損益」「引当金等」をタップで切り替え
   - 選択した区分A内の項目のみを表示

2. **区分A/B/Cの並び順を固定**
   - 区分A: 資産 → 負債 → 資本 → 経常損益 → 引当金等
   - 区分B（資産）: 流動資産 → 固定資産 → 繰延資産 → 事業主貸
   - 区分B（負債）: 流動負債 → 事業主借
   - 区分B（経常損益）: 収入金額 → 経費
   - 区分B（引当金等）: 繰戻額等 → 繰入額等
   - 区分C（流動資産）: 現金・預金 → 売上債権 → 有価証券 → 棚卸資産 → 他流動資産
   - 区分C（固定資産）: 有形固定資産 → 無形固定資産 → 投資等
   - 区分C（流動負債）: 仕入債務 → 他流動負債
   - 区分C（収入金額）: 収入金額 → 農産物棚卸高
   - 区分C（経費）: 経費 → 農産外棚卸高

3. **勘定科目の表示を横1行・文字拡大**
   - 変更前: 2行表示（科目名 + コード/貸借）
   - 変更後: 横1行表示（科目名 | コード | 英字 | 貸借 | 購買 | 預金 | 削除）
   - 科目名: 16sp太字、その他: 13sp
   - 「預金」バッジを追加（将来の預金口座CSV連携機能用）

**UIイメージ:**
```
┌─────────────────────────────────────────────────┐
│ [資産] [負債] [資本] [経常損益] [引当金等]        │
├─────────────────────────────────────────────────┤
│ ▼ 流動資産                              (12件)  │
│   ├ 現金・預金                                  │
│   │  現金        100  genkin   借  [預金]  🗑   │
│   │  普通預金    101  hutuu    借  [預金]  🗑   │
│   ├ 売上債権                                    │
│   │  売掛金      104  urikake  借  [預金]  🗑   │
└─────────────────────────────────────────────────┘
```

---

## 2026-01-21

### 勘定科目設定画面の階層化 & データ構造改善

**背景:**
- 勘定科目の表示がフラットリストで見づらい
- 区分A/B/Cによる分類がデータに反映されていない
- 購買取引で使用する科目のフィルタリングが必要

**データモデル変更:**

```kotlin
// RakurakuAccount / YayoiAccount 共通拡張
data class Account(
    val id: Long,
    val accountCode: String,
    val accountName: String,
    val searchKeyAlpha: String,      // サーチキー英字
    val debitCredit: String,         // 借/貸
    val categoryC: String,           // 小分類 (例: 【経費】)
    val categoryB: String,           // 中分類 (例: 【経費】)
    val categoryA: String,           // 大分類 (例: 【経常損益】)
    val usedForPurchase: Boolean,    // 購買取引で使用
    val usedForDeposit: Boolean,     // 預金取引で使用
    val parentId: Long?              // 親科目への参照
)
```

**区分構造:**
| 区分A (大分類) | 区分B (中分類) | 区分C (小分類) |
|---------------|---------------|---------------|
| 【資産】 | 【流動資産】【固定資産】【繰延資産】【事業主貸】 | 【現金・預金】【売上債権】【棚卸資産】等 |
| 【負債】 | 【流動負債】【事業主借】 | 【仕入債務】【他流動負債】等 |
| 【資本】 | 【資本】 | 【資本】 |
| 【経常損益】 | 【収入金額】【経費】 | 【収入金額】【経費】【農産物棚卸高】等 |
| 【引当金等】 | 【繰戻額等】【繰入額等】 | 【繰戻額等】【繰入額等】 |

**初期データ:**
- 弥生会計: 99件 (kamoku.csv)
- らくらく青色申告農業版: 61件 (kamoku2.csv)

**UI改善:**
1. **階層表示**: 区分A → 区分B → 区分C → 勘定科目の折りたたみ可能なツリー
2. **全展開/折りたたみボタン**: 右上アクションボタン
3. **購買バッジ**: 購買取引で使用する科目に「購買」タグを表示
4. **親子関係表示**: 親科目の下に子科目をインデント表示
5. **科目追加ダイアログ**: 区分A/B/Cをドロップダウンから選択

**DAOクエリ追加:**
```kotlin
// 区分別取得
suspend fun getByCategoryA(categoryA: String): List<Account>
suspend fun getByCategoryB(categoryB: String): List<Account>
suspend fun getByCategoryC(categoryC: String): List<Account>

// 購買取引用フィルタ
suspend fun getForPurchase(): List<Account>

// 区分一覧取得
suspend fun getDistinctCategoryA(): List<String>
suspend fun getDistinctCategoryB(): List<String>
suspend fun getDistinctCategoryC(): List<String>
```

**実装ファイル:**
- `RakurakuAccount.kt` (エンティティ拡張)
- `YayoiAccount.kt` (エンティティ拡張)
- `RakurakuAccountDao.kt` (クエリ追加)
- `YayoiAccountDao.kt` (クエリ追加)
- `DatabaseInitializer.kt` (CSV読み込み更新)
- `AccountSettingsScreen.kt` (階層表示UI)
- `ReceiptDatabase.kt` (v7→v8マイグレーション)
- `assets/yayoi_accounts.csv` (新データ)
- `assets/rakuraku_accounts.csv` (新データ)

---

## 2026-01-20

### 購買伝票画面 UI改善

**変更内容:**

1. **TopAppBar変更**
   - タイトル: 「伝票入力」→「購買伝票」
   - 右側に「閲覧」/「編集」を18sp太字で表示

2. **年表示強化**
   - R7年をSurfaceで囲み、18sp太字・プライマリカラーで目立つように

3. **レイアウト再構成**
   - 1行目: R7年（目立つ）、月選択、伝票ナビゲーション
   - 2行目: 編集/伝票追加・クリア・削除ボタン
   - 3行目: OCR/直接、撮影
   - 4行目: 年月固定チェック、フォントサイズ、再計算ボタン

**実装ファイル:**
- `ReceiptInputScreen.kt`

---

### 勘定科目マスタ機能実装

**背景:**
- らくらく青色申告農業版との連携
- 弥生会計とのマッチング対応
- サーチキー方式による高速入力

**データベース設計:**

```kotlin
@Entity(tableName = "account_codes")
data class AccountCode(
    val id: Int,
    val code: String,           // 弥生会計等との連携用
    val name: String,           // 勘定科目名
    val searchKey: String,      // サーチキー（ローマ字）
    val accountType: AccountType,  // 資産/負債/資本/収入/経費
    val taxType: TaxType,       // 課税/非課税/不課税/軽減税率
    val sortOrder: Int,
    val isActive: Boolean,
    val note: String
)
```

**初期データ（36科目）:**

| 分類 | 科目数 | 主な科目 |
|------|--------|----------|
| 資産 | 6 | 現金, 営農口座, 直売口座, 売掛金, 未収金, 事業主貸 |
| 負債 | 4 | 買掛金, 借入金, 未払金, 事業主借 |
| 資本 | 2 | 元入金, 青申特別控除前の所得金額 |
| 収入 | 4 | 水稲, インゲン, キュウリ類, 雑収入 |
| 経費 | 20 | 租税公課, 種苗費, 肥料費, 動力光熱費, ... |

**サーチキー入力UI:**

| コンポーネント | 用途 |
|---------------|------|
| `AccountCodeSearchField` | インライン検索フィールド（リアルタイム候補表示） |
| `AccountCodeSearchFieldCompact` | グリッド内用コンパクト版 |
| `AccountCodeSelectDialog` | 全画面選択ダイアログ |

**検索動作:**
```
入力: "hi"  → 肥料費
入力: "dou" → 動力光熱費
入力: "nou" → 農具費, 農薬衛生費, 農業共済掛金
入力: "zi"  → 事業主貸, 事業主借
```

**実装ファイル:**
- `AccountCode.kt` (エンティティ + AccountType, TaxType enum)
- `AccountCodeDao.kt` (検索・CRUD)
- `ReceiptDatabase.kt` (v5 → v6マイグレーション)
- `DatabaseInitializer.kt` (初期データ登録)
- `AccountCodeScreen.kt` (勘定科目設定画面)
- `AccountCodeSearchField.kt` (サーチキー入力コンポーネント)
- `AccountCodeSelectDialog.kt` (選択ダイアログ)
- `Navigation.kt` (画面追加)
- `SettingsScreen.kt` (勘定科目設定へのリンク追加)

**アクセス方法:**
設定 → 勘定科目設定 → 設定

---

## 2026-01-19

### OCR学習システム V3 完全実装

**背景:**
- V2は日数・ヒット数重視で昇格に時間がかかりすぎる
- 時間減衰により使用頻度の低いパターンが消える
- 自動判定だけでは誤学習リスクがある

**設計思想: 「人間の確認が唯一の真実」**
- 手動修正を最も信頼性の高い情報源として扱う
- 失敗したパターンは即座に信頼度を下げる
- 時間減衰を廃止し、実績ベースの昇格に変更

**主な変更点:**

| 項目 | V2 | V3 |
|------|-----|-----|
| 昇格基準 | 日数・ヒット数重視 | 手動確認重視 |
| 時間減衰 | あり（30日で0.37） | なし |
| 日数条件 | 必須（3日以上） | なし |
| 失敗時の処理 | なし | 即座に降格/無効化 |
| スコア記録 | なし | 全判定を記録 |

**昇格条件:**

| 遷移 | 条件 |
|------|------|
| AUTO → CONFIRMED（手動） | 異なるバッチで手動修正2回 |
| AUTO → CONFIRMED（自動） | hitCount≥3, avgScore≥90, highScoreHits≥2, autoFailCount=0 |
| CONFIRMED → LOCKED | 手動修正5回 |

**降格・無効化:**

| 現在レベル | 失敗時の動作 |
|------------|--------------|
| AUTO | 即座に無効化（isDisabled=true） |
| CONFIRMED | AUTOに降格 + autoFailCount++ |
| LOCKED | 変更なし |

**データベース更新 (v4 → v5):**
- OcrVariantテーブル拡張（manualCorrectCount, autoFailCount, lastManualCommitBatchId）
- OcrScoreLogテーブル新規作成（スコア計算詳細を記録）

**実装ファイル:**
- `OcrVariant.kt` (V3昇格・降格ロジック追加)
- `OcrVariantDao.kt` (registerManualCorrection, onAutoFailure等)
- `OcrScoreLog.kt` (新規)
- `OcrScoreLogDao.kt` (新規)
- `ProductNameCorrectorV3.kt` (100点満点スコアシステム)
- `ReceiptDatabase.kt` (v5マイグレーション)
- `ReceiptInputScreen.kt` (手動修正記録、originalOcrName/productMasterId設定)

---

### 伝票入力画面 UI改善

**変更内容:**

1. **閲覧時ボタン削除**
   - 「再計算」ボタンを削除
   - 「月別サマリー」ボタンを削除

2. **編集状態表示**
   - TopAppBarのタイトル横に「閲覧中」/「編集中」を表示
   - 編集中は赤色で強調

3. **年月表示改善**
   - 月の左に年を表示（例: R7年 1月）
   - ViewModeLabelコンポーネントを削除

4. **年月固定機能**
   - 月の右側に「年月固定」チェックボックスを追加
   - 永続保存（AppPreferences）
   - チェック時:
     - OCR: 日データのみ採用し、選択中の年月と組み合わせ
     - 直接入力: 日のみ入力可能（1〜31/30/29/28、月により制限）

5. **税込金額の等幅表示**
   - fontFeatureSettings = "tnum" で等幅数字を強制
   - 桁の把握を容易に

**実装ファイル:**
- `ReceiptInputScreen.kt` (UI構造変更、年月固定ロジック)
- `AppPreferences.kt` (fixYearMonth設定追加)

---

### ドキュメント更新

- `OCR_LEARNING_SYSTEM.md` をV3仕様に全面書き換え

---

## 2026-01-16

### OCR補正システム V3 - 三層構造設計

**背景:**
- 従来の編集距離ベース補正は誤変換リスクが高い
- 容量違い商品（250g vs 500g）への誤変換防止が不十分
- 類似商品の競合判定がなかった

**設計思想:**
- 誤変換ゼロ原則: 「当たったときだけ強く補正」
- Precisionに全振り: Recallを捨てて精度を最優先
- Conservative Approach: 確信がないときは補正しない

**三層構造:**

| Layer | 名称 | 役割 |
|-------|------|------|
| Layer 0 | 制約バリア | 容量・カテゴリ不一致 → 即除外 |
| Layer 1 | 全文マッチング | Levenshtein距離でスコア計算 |
| Layer 2 | ボーナス計算 | 先頭欠落、濁点、N-gram |

**スコア定数:**
```kotlin
MIN_ACCEPT_SCORE = 0.78       // 最低受理スコア
MIN_SCORE_GAP = 0.12          // 1位-2位の最小スコア差
HEAD_MISSING_BONUS = 0.08     // 先頭欠落ボーナス
TAIL_MISSING_BONUS = 0.06     // 末尾欠落ボーナス
DAKUTEN_BONUS_SINGLE = 0.02   // 濁点ボーナス（1文字）
MAX_TOTAL_BONUS = 0.10        // ボーナス合計最大値
CONFLICT_THRESHOLD = 0.85     // 競合判定閾値
```

**補正理由 (CorrectionReason):**
- `NO_INPUT`: 入力なし
- `NO_CANDIDATES`: 候補なし
- `OCR_VARIANT_CONFIRMED`: 確認済みパターンヒット
- `SIMILARITY_MATCH`: 類似度マッチ（補正成功）
- `REJECT_SCORE_LOW`: スコア不足（< 0.78）
- `REJECT_GAP_INSUFFICIENT`: 2位との差不足（< 0.12）
- `REJECT_SIMILAR_PRODUCTS_CONFLICT`: 類似商品競合

**OcrVariant学習システム:**
- 信頼度レベル: AUTO → CONFIRMED → LOCKED
- 昇格条件: hitCount>=5, uniqueDays>=3, avgFinalScore>=0.88, highScoreHits>=3
- 減衰計算: exp(-days/30)

**データベース更新 (v3 → v4):**
- OcrVariantテーブル拡張（confidenceLevel, hitCount, highScoreHits等）
- CorrectionLogテーブル新規作成

**実装ファイル:**
- `ProductNameCorrectorV3.kt` (新規 ~850行)
- `OcrVariant.kt` (拡張)
- `OcrVariantDao.kt` (拡張)
- `CorrectionLog.kt` (新規)
- `CorrectionLogDao.kt` (新規)
- `ReceiptDatabase.kt` (v4マイグレーション)

詳細は [CORRECTION_SYSTEM.md](./CORRECTION_SYSTEM.md) を参照。

---

## 2026-01-12

### 15:00: 二段階Binary OCR評価システム実装

**背景:**
- Binary OCRは「補助火力」であり、主力ではない
- Gray OCRは常に主系、Binary OCRは「明確に勝った場合のみ」採用
- 線幅分散は「禁止条件」ではなく「ペナルティ要素」とすべき

**設計思想: 二段階評価**

**段階A: 実行判定 (Should Run Binary OCR?)**
- 目的: 計算コスト節約、明らかに不適な画像は実行しない
- ハード条件（最低要件）:
  ```kotlin
  val canTryBinary = (
      charPx >= 18f &&          // 文字高さ不足
      blackRatio <= 0.45 &&     // 黒画素過多（二値化失敗）
      edgeDensity >= 0.02       // エッジ不足（文字なし）
  )
  ```
- 候補スコア計算（binaryCandidateScore）:
  ```kotlin
  val charHeightNorm = ((charPx - 18f) / (40f - 18f)).coerceIn(0f, 1f)
  val edgeDensityNorm = ((edgeDensity - 0.02) / (0.10 - 0.02)).coerceIn(0.0, 1.0)

  // 線幅分散を段階的ペナルティに変更（日本語テキストは自然に分散が高い）
  val strokePenalty = when {
      strokeWidthVar <= 0.3 -> 0.0  // 理想的
      strokeWidthVar <= 0.6 -> 0.1  // 許容範囲
      strokeWidthVar <= 0.9 -> 0.2  // 高いが試す価値あり
      else -> 0.3                   // 非常に高い
  }

  score = 0.40 * charHeightNorm + 0.40 * edgeDensityNorm - 0.20 * strokePenalty
  ```
- 実行判定: `canTryBinary && binaryCandidateScore >= 0.5`

**段階B: 採用判定 (Should Adopt Binary Result?)**
- 目的: Gray vs Binary の最終決定、辞書の正規名で評価
- Gray/Binaryスコア計算:
  ```kotlin
  // Grayスコア（主系）
  grayScore = 0.35 * confidence + 0.20 * scriptScore + 0.25 * dictionaryScore
            + 0.10 * bboxConsistency + 0.10 * lengthScore

  // Binaryスコア（辞書重視）
  binaryScore = 0.30 * confidence + 0.15 * scriptScore + 0.35 * dictionaryScore
              + 0.10 * bboxConsistency + 0.10 * lengthScore
  ```
- Binary採用条件（すべて満たす必要あり）:
  ```kotlin
  val meetsCondition1 = binaryCandidateScore >= 0.6
  val meetsCondition2 = (binaryResult.confidence ?: 0f) >= 0.55f
  val meetsCondition3 = binaryScoreDetails.dictMatchScore >= 0.5
  val meetsCondition4 = binaryScore >= grayScore + 0.15

  return if (all conditions met) binaryResult else grayResult
  ```

**実装内容:**

1. **ImagePreprocessor.kt: 線幅分散を段階的ペナルティに変更**
   ```kotlin
   fun calcBinaryCandidateScore(...): Double {
       val strokePenalty = when {
           strokeWidthVar <= 0.3 -> 0.0
           strokeWidthVar <= 0.6 -> 0.1
           strokeWidthVar <= 0.9 -> 0.2
           else -> 0.3
       }
       score = 0.40 * charHeightNorm + 0.40 * edgeDensityNorm - 0.20 * strokePenalty
   }
   ```

2. **OCRProcessor.kt: 段階A実行判定**
   ```kotlin
   val canTryBinary = (
       charPx >= 18f &&
       blackRatio <= 0.45 &&
       edgeDensity >= 0.02
   )
   // strokeWidthVarをハード条件から削除

   val shouldUseBinary = canTryBinary && binaryCandidateScore >= 0.5
   ```

3. **OcrResultEvaluator.kt: 段階B最終決定**
   - `calculateGrayScore()`: Grayスコア計算
   - `calculateBinaryScore()`: Binaryスコア計算（辞書重視）
   - `chooseBestResult()`: 4条件チェックで最終決定

4. **CameraViewModel.kt: 辞書マッチング後に段階B評価**
   ```kotlin
   if (correctionResult.matched) {
       val doubleOcrResult = result.productNameDoubleOcrMap[index]
       if (doubleOcrResult != null) {
           val bestResult = OcrResultEvaluator.chooseBestResult(
               grayOcrResult,
               binaryOcrResult,
               correctionResult.correctedName  // 辞書の正規名で評価
           )
       }
       // 最終的には辞書の正規名を使用
       row.copy(itemName = correctionResult.correctedName)
   }
   ```

**テスト結果:**
```
段階A:
- charPx=19.0-23.0, blackRatio=0.29-0.35, edgeDensity=0.04-0.06
- strokeWidthVar=0.84-0.85 → strokePenalty=0.3
- binaryCandidateScore=0.586-0.671 → Binary OCR実行 ✅

段階B:
- Binary採用条件を満たさず → Gray OCR採用 ✅
- 理由: dictMatchScore不足、binaryScore < grayScore + 0.15
```

**効果:**
- Binary OCR実行率: ~30%（段階Aを通過）
- Binary OCR採用率: 0%（段階Bで正しくフィルタリング）
- 線幅分散が高い日本語テキストでもBinary OCR実行可能に

---

### 12:00: 数量列OCR完全再設計

**背景:**
- 固定4倍拡大は不適切（文字高さが異なる伝票で過剰/不足）
- 罫線が「1」として誤認識される問題（OCR前に物理除去すべき）
- 商品名列と同じく文字高さ正規化が必要

**設計思想:**
1. **固定倍率廃止**: 4倍 → 文字高さベース適応的スケーリング (1.0-3.0倍)
2. **罫線物理除去**: OCR前にモルフォロジー処理で除去
3. **行ごと処理**: 列全体ではなく行ごとに処理
4. **シェイプフィルタ**: OCR後にBboxアスペクト比・高さでフィルタリング

**実装内容:**

1. **ImagePreprocessor.kt: 罫線除去関数追加**
   ```kotlin
   fun removeLines(
       grayMat: Mat,
       removeVertical: Boolean = true,
       removeHorizontal: Boolean = false
   ): Mat {
       val result = grayMat.clone()

       if (removeVertical) {
           // 縦線除去（最優先）
           val verticalKernel = getStructuringElement(
               MORPH_RECT,
               Size(1.0, grayMat.rows() * 0.6)  // 高さ60%
           )
           val verticalMask = Mat()
           morphologyEx(result, verticalMask, MORPH_OPEN, verticalKernel)
           subtract(result, verticalMask, result)
       }

       if (removeHorizontal) {
           // 横線除去
           val horizontalKernel = getStructuringElement(
               MORPH_RECT,
               Size(grayMat.cols() * 0.6, 1.0)  // 幅60%
           )
           val horizontalMask = Mat()
           morphologyEx(result, horizontalMask, MORPH_OPEN, horizontalKernel)
           subtract(result, horizontalMask, result)
       }

       return result
   }
   ```

2. **ImagePreprocessor.kt: 軽量文字高さ推定**
   ```kotlin
   fun estimateCharHeightSimple(grayMat: Mat): Float {
       // 1. 軽量二値化（閾値150）
       val binaryMat = Mat()
       threshold(grayMat, binaryMat, 150.0, 255.0, THRESH_BINARY_INV)

       // 2. 輪郭検出
       val contours = ArrayList<MatOfPoint>()
       findContours(binaryMat, contours, ...)

       // 3. 高さ分布取得
       val heights = contours.map { boundingRect(it).height }

       // 4. 中央値返却
       return heights.sorted()[heights.size / 2].toFloat()
   }
   ```

3. **OCRProcessor.kt: extractQuantitiesFromColumn() 完全書き換え**
   ```kotlin
   private suspend fun extractQuantitiesFromColumn(...): Map<Int, String> {
       rows.forEachIndexed { rowIndex, row ->
           // 1. 行ROI抽出
           val rowRoiBitmap = Bitmap.createBitmap(...)

           // 2. グレースケール変換
           val grayMatGray = Mat()
           cvtColor(rowMat, grayMatGray, COLOR_RGBA2GRAY)

           // 3. 罫線除去（OCR前処理、最重要）
           val cleanedMat = ImagePreprocessor.removeLines(
               grayMatGray,
               removeVertical = true,
               removeHorizontal = false
           )

           // 4. 文字高さ推定
           val charPx = ImagePreprocessor.estimateCharHeightSimple(cleanedMat)

           // 5. 適応的スケーリング（固定4倍廃止）
           val targetHeight = 30.0
           val scale = (targetHeight / charPx).coerceIn(1.0, 3.0)

           // 6. Latin OCR実行
           val ocrText = recognizeTextLatin(scaledBitmap)

           // 7. Bboxシェイプフィルタ（OCR後フィルタ）
           val elements = recognizer.process(inputImage).await()
           for (textBlock in elements.textBlocks) {
               for (line in textBlock.lines) {
                   for (element in line.elements) {
                       val bounds = element.boundingBox ?: continue
                       val width = bounds.width()
                       val height = bounds.height()
                       val aspectRatio = width.toDouble() / height.toDouble()

                       // アスペクト比チェック（横線除外）
                       if (aspectRatio > 5.0) {
                           continue  // 横線として除外
                       }

                       // 高さチェック（細い線除外）
                       if (height < 12) {
                           continue  // 細い線として除外
                       }

                       // 8. 正規表現チェック（^[0-9]{1,3}$）
                       val text = element.text
                       if (text.matches(Regex("^[0-9]{1,3}$"))) {
                           quantityMap[rowIndex] = text
                       }
                   }
               }
           }
       }
   }
   ```

**テスト結果:**
```
適応的スケーリング:
- Row 0: charPx=16.5 → scale=1.82 (30.0/16.5)
- Row 1: charPx=11.0 → scale=2.73 (30.0/11.0)
- Row 2: charPx=17.0 → scale=1.76 (30.0/17.0)

罫線除去:
- 縦線が物理的に除去され、「1」誤認識が大幅減少

Bboxシェイプフィルタ:
- アスペクト比 > 5.0 → 横線除外 ✅
- 高さ < 12px → 細い線除外 ✅
```

**効果:**
- 文字高さに応じた最適なスケーリング（過剰拡大/不足を防止）
- 罫線誤認識の大幅削減
- 行ごと処理でより精密な制御

---

### 09:00: Y座標フィルタリング修正

**問題:**
- 合計行がY座標フィルタリングで「見る前に捨てられる」
- 実測: 合計行 Y=2138px（152.71mm）
- 設定: MONTHLY_TOTAL_Y_START_MM = 153.0mm（2142px）
- 結果: 合計行が範囲外（2142-2198px）で除外

**修正内容:**

1. **UnderlyingBaseProcessor.kt: 合計行開始位置を修正**
   ```kotlin
   // 修正前
   private const val MONTHLY_TOTAL_Y_START_MM = RECEIPT_TOP_MM + 122.0  // 153.0mm

   // 修正後
   private const val MONTHLY_TOTAL_Y_START_MM = RECEIPT_TOP_MM + 121.0  // 152.0mm
   ```

2. **OCRProcessor.kt: フィルタリングを行タイプ判定後に移動**
   ```kotlin
   // 修正前のフロー
   OCR → 行クラスタリング → Y座標フィルタ → 行タイプ判定

   // 修正後のフロー
   OCR → 行クラスタリング → 行タイプ判定 → 行タイプ別Y座標フィルタ

   // 実装
   val filteredRows = rows.filter { row ->
       val yMm = row.bounds.centerY() / mmToPixelRatio

       when (row.rowType) {
           RowType.NORMAL, RowType.SUBTOTAL -> {
               yMm >= UnderlyingBaseProcessor.NORMAL_ROW_Y_START_MM &&
               yMm <= UnderlyingBaseProcessor.SUBTOTAL_Y_END_MM
           }
           RowType.MONTHLY_TOTAL -> {
               yMm >= UnderlyingBaseProcessor.MONTHLY_TOTAL_Y_START_MM &&
               yMm <= UnderlyingBaseProcessor.MONTHLY_TOTAL_Y_END_MM
           }
           else -> false  // HEADER, FOOTER は除外
       }
   }
   ```

**テスト結果:**
```
修正前:
- 33行検出 → Y座標フィルタ → 20行残存
- 合計行 Y=2138px（152.71mm）→ 範囲外除外 ❌

修正後:
- 33行検出 → 行タイプ判定 → Y座標フィルタ → 20行残存
- 合計行 Y=2138px（152.71mm）→ 範囲内保持 ✅
- ヘッダー/フッター行を正しく除外 ✅
```

**効果:**
- 合計行が「見る前に捨てられる」問題を解消
- ヘッダー/フッター行を正しく除外
- 行タイプに応じた柔軟なY座標フィルタリング

---

## 2026-01-01

### 09:45: Double OCR + OcrResultEvaluator実装 & 全角容量対応

**実装内容:**
1. **OcrResultEvaluator.kt** (新規作成)
   - 辞書ベースのインテリジェント結果選択
   - スコアリング: 辞書マッチ40%, 編集距離25%, 数値20%, 信頼度10%, 長さ5%

2. **DoubleOCR処理**
   - Gray版 + Binary版の2つのOCR結果を評価比較
   - 前処理パイプライン: グレースケール → コントラスト → モルフォロジーOpen → エッジ密度チェック → 条件付き二値化

3. **ProductNameCorrectorV2.kt**
   - 全角容量パターン対応 (ｃｃ, ｍｌ, ｋｇ など)
   - 容量重複バグ修正 (例: "100cc100 co" → "100cc")

**テスト結果:**
- 検出行数: 17行
- 補正成功率: **100% (9/9 補正可能項目)**
- 容量重複問題: ✅ 解決

---

## 2025-12-31

### 23:30: 適応的解像度OCRシステムの実装

**背景:**
- 4K ImageAnalysis化成功 (3264×2448)
- 固定2400×1700透視変換により文字高さ不足
- 実測: ~35px期待に対し、商品名列OCRで文字化け多発

**実装内容:**

1. **動的透視変換** (ImageProcessor.kt:398-445)
   ```kotlin
   - ArUcoマーカー間距離からpx/mm比率を実測
   - 目標px/mm (10-14) 設定 → 文字30px以上確保
   - 出力サイズ = A4サイズ(mm) × 目標px/mm
   ```

2. **文字高さ測定** (ImagePreprocessor.kt:264-329)
   ```kotlin
   - Cannyエッジ検出 → 膨張 → 輪郭検出
   - バウンディングボックス高さの中央値 = 文字高さ
   ```

3. **適応的スケーリング** (ImagePreprocessor.kt:340-382)
   ```kotlin
   - 現在の文字高さ測定 → 目標32pxに対する倍率計算
   - scale = 32px / 現在高さ (1.0-3.0に制限)
   - 単一OCRパス (多スケール試行を廃止)
   ```

4. **商品名列OCRへの統合** (OCRProcessor.kt:700-741)
   - 固定3倍拡大 → 適応的スケーリング
   - 文字高さベースの動的倍率計算

**実測結果:**
```
透視変換:
- Measured px/mm: 9.47-9.78
- Target px/mm: 14.00 (自動ブースト)
- Output size: 4158×2940 px (旧2400×1700比 73%向上)

商品名列OCR:
- Character height: 測定値ベース
- Scale factor: 動的計算 (1.0-3.0)
```

**技術的意義:**
- 入力解像度に適応 (3264×2448 → 4158×2940出力)
- ML Kit推奨30-40pxに自動調整
- 処理効率化 (多スケール試行廃止)

---

### 20:50: ImageCapture高解像度問題 → ImageAnalysis 4K解決策

**問題:**
- ImageCapture実装で座標変換失敗
- Preview: 1600×1200 (横長 4:3)
- High-res: 1836×2448 (縦長 3:4) - カメラ自動90度回転
- ArUco検出失敗、座標スケーリング複雑化

**試行した失敗アプローチ:**
1. 単純座標スケーリング (1.1475, 2.04) → ヘッダー/フッター誤検出 ❌
2. ビットマップ+90度回転 + 座標+90度回転 → 誤領域検出 ❌
3. ビットマップ-90度回転 + 座標-90度回転 → 誤領域検出 ❌

**最終解決策:** ✅
```kotlin
// ImageCaptureを廃止、ImageAnalysisの解像度向上
val imageAnalyzer = ImageAnalysis.Builder()
    .setTargetResolution(android.util.Size(3840, 2160))  // 4K
    .build()
```

**実測結果:**
- 実解像度: 3264×2448 (デバイス最大)
- 検出行数: 31行 → 17行 (Y範囲フィルタ後)
- 商品名補正: 86% (6/7)

**技術的意義:**
- シンプル性: 複雑な座標変換不要
- 信頼性: ArUco検出とOCR処理が同一画像
- 性能: 解像度2倍向上 (1600×1200 → 3264×2448)
- 保守性: コード量削減 (500行以上)

---

### 19:40: 文字高さ評価のOCRスケール対応 (問題①)

**問題分析:**
```
現状のフロー:
1. 品質評価: 1280pxスケールで実施
   → 文字高さ 6-7px = 「良好」(score 0.70-0.85)

2. 実際のOCR: 2400pxスケールで実施
   → 6-7px × (2400/1280) = 18-21px

問題:
- ML Kit推奨: 30-40px/文字
- 18-21pxは不足 → 文字化け多発
- ❌「読めない状態を高品質と誤判定」
```

**修正内容:**

1. **文字高さスコアリング更新**
   ```kotlin
   // 修正前（1280pxスケール）:
   height < 6 -> 0.0-0.7
   height < 8 -> 0.7-0.85

   // 修正後（2400pxスケール = OCR実行時）:
   height < 20 -> 0.0-0.4   // 認識困難
   height < 25 -> 0.4-0.6   // 不安定
   height < 30 -> 0.6-0.75  // 最低限
   height <= 40 -> 0.75-1.0 // 良好
   ```

2. **評価フロー分離**
   ```kotlin
   // ステップ1: フォーカス・コントラスト (1280px - 高速)
   val previewBitmap = scale to 1280px

   // ステップ2: 文字高さ (2400px - OCRスケール)
   val ocrBitmap = scale to 2400px
   ```

---

### 15:30: 品質閾値調整とパフォーマンス最適化

**問題:**
- ユーザー: 「70超えません。68,69止まり」
- 品質スコア: 0.690 (69%)
- 内訳: focus=1.0, charHeight=0.4, contrast=0.97
- ボトルネック: 文字高さスコアが0.4固定

**根本原因:**
- 品質評価: 1280pxダウンスケール
- スケール比: 1280 / 3264 ≈ 0.39 (39%)
- 実測文字高さ: 6-7px (1280pxスケール)
- 元画像換算: 6÷0.39 ≈ 15-18px (理想的!)
- charHeightScore()が元解像度用閾値使用 → 誤判定

**修正内容:**
```kotlin
// 修正前（元解像度用）:
height < 6 -> 0.0
height < 7 -> 0.4

// 修正後（1280pxスケール用）:
height < 3 -> 0.0
height < 4 -> 0.4
height < 6 -> 0.4-0.7
height < 8 -> 0.7-0.85  ← 6-7px該当
```

**効果:**
- 6px: 0.40 → **0.70** (75%向上)
- 7px: 0.40 → **0.775** (93%向上)
- 総合スコア: 68.9% → **88.8%** ✅

**実機テスト:**
```
OcrQuality(score=0.888, focus=713.7 (1.00),
           charHeight=7 (0.77), contrast=1.00, isGood=true)
```

**パフォーマンス改善:**
- 品質評価速度: 10-20倍高速化 (4K → 1280px)

---

### 07:00: OCR品質評価システムの実装

**背景:**
- ユーザー要求: 「撮影されません」
- 初期問題: フォーカス値0、文字高さ0

**実装:**
1. **OcrQualityEvaluator.kt** (新規)
   - 3指標: フォーカス(20%), 文字高さ(50%), コントラスト(30%)
   - Laplacian分散によるシャープネス計算
   - 垂直エッジ検出による文字高さ推定
   - 標準偏差によるコントラスト測定

2. **ImagePreprocessor.kt** (新規)
   - エッジ検出 (Sobel-like vertical)
   - 文字高さ推定 (connected components)

**調整経緯:**
- a) 計算エラー解消 (Int/Long型、エッジ検出方向)
- b) 実測値ベーススコアリング調整
  - フォーカス: 5-40範囲 → 0.0-1.0
  - 文字高さ: 6-15px範囲 → 0.4-0.85
- c) 閾値段階的引き下げ
  - QUALITY_THRESHOLD: 0.70 → 0.45
  - MIN_STABLE_FOCUS_FRAMES: 3 → 1

**ArUcoマーカー検出問題:**
- 暫定対応: DEBUG_SKIP_MARKER_CHECK = true

---

### 01:00: 辞書補正精度大規模テスト

**テスト:**
- 3枚の伝票、57行分析

**結果:**
- 成功率: 35% (20/57) - 目標70%未達

**根本原因特定:**
- OCR精度低: 商品名列が拡大なし (482px)
- 比較: 数量列(4倍拡大)は良好、商品名列(拡大なし)は不良

**次の修正:**
- 商品名列に2-3倍拡大追加

---

## 2025-12-30

### 日付パターン最適化 & システム統合

**実装内容:**
1. **日付パターン改善**
   - 4つの正規表現パターン
   - E/e→8 OCR誤認識対応
   - 繰り返しマッチング (最大3回)

2. **統合テスト**
   - 検出行数: 20行
   - 辞書補正成功: 55%
   - 自動学習: 6件の新しいOCR誤認識パターン記録

---

## 2025-12-29

### 辞書ベース補正システム実装

**データベース設計:**
- ProductMaster, OcrVariant, YayoiAccount, RakurakuAccount
- 4つのDAO + Room Database migration (v2→v3)

**CSVインポート:**
- 113商品
- 15弥生勘定科目
- 15らくらく勘定科目
- 13 OCR誤認識パターン

**容量保護型マッチング:**
- レーベンシュタイン距離
- 類似度閾値0.7
- カテゴリ別フィルタリング (小計行から逆算)
- 自動学習機能

**テスト結果:**
- 補正成功率: 70% (14/20)

---

## 2025-12-25

### 00:00: OCR画像前処理 & 辞書ベース補正設計

**背景:**
- 誤字多発: "乳素"→"乳剤", "ガッリン"→"ガソリン"
- i/o/O 誤認識多数

**実装内容:**

1. **OCR画像前処理** (ImageProcessor.kt)
   ```kotlin
   fun enhanceImageForOCR(bitmap: Bitmap): Bitmap {
       // 1. グレースケール変換
       // 2. シャープニング (Unsharp Mask, weight=1.5, blur=-0.5)
       // 3. CLAHE (clipLimit=2.0, tileSize=8x8)
   }
   ```

2. **フォーカス閾値引き上げ**
   - 200.0 → 250.0

**テスト結果:**
```
改善された誤字:
- "乳素" → "乳剤" ✅
- "ガッリン" → "ガソリン" ✅

依然残る誤字:
- i/l/o/O 誤認識
- 一部の文字: "類"→"頼"

小計精度: 100% (53551, 15810) ✅
```

**改善効果:**
- 文字認識向上
- ML Kit OCRの限界認識
- 次の施策: 辞書ベース補正必要

---

## 2025-12-24

### 22:36: 商品名クリーニング & 総合品質管理システム

**背景:**
- 商品名に日付混入: "p71008米用紙袋"
- 金額正規化不足: "158 1o" → 15810失敗
- 撮影品質不安定: シャープネス 38-197

**実装内容:**

1. **商品名クリーニング** (UnderlyingBaseProcessor.kt:690-710)
   ```kotlin
   private fun cleanItemName(itemName: String): String {
       // パターン1: OCR誤認識を含む日付 (p71xxx, めE1021)
       val pattern1 = Regex("^.{0,3}[0-9oOlI.:/ ]{4,7}[|]?")

       // パターン2: 正確な6桁日付 (071011)
       val pattern2 = Regex("^\\d{6}[|]?")
   }
   ```

2. **金額・小計正規化強化**
   ```kotlin
   fun normalizeToDigits(text: String): String {
       return text
           .replace("o", "0").replace("O", "0")
           .replace("l", "1").replace("I", "1")
           .replace("S", "5").replace("s", "5")
   }
   ```

3. **総合品質管理システム** (CameraScreen.kt:380-550)
   - a) 輝度計算関数 (640x480スケール、グレースケール平均)
   - b) 品質閾値最適化
     ```kotlin
     FOCUS_THRESHOLD = 200.0      // 150.0 → 200.0
     BRIGHTNESS_MIN = 40.0
     BRIGHTNESS_MAX = 220.0
     ```
   - c) 3段階品質チェック + 自動撮影
   - d) リアルタイム品質表示UI

**テスト結果:**
```
実測値比較:
一般購買 小計: 53,551円 ✅
給油所 小計:   15,810円 ✅

画質メトリクス:
- シャープネス: 1558.6 (閾値200.0を大幅上回る)
- 輝度: 155.5 (40-220適正範囲)

商品名クリーニング:
- "p71008米用紙袋" → "米用紙袋" ✅
- すべての商品名から日付除去 ✅
```

**改善効果:**
- 小計精度: 0% → **100%** (2/2完全一致)
- 商品名品質: 日付混入解決
- 撮影品質: 不安定(38-197) → 安定(1500+)
- ユーザー体験: リアルタイム品質確認

---

### 08:30: フォーカス品質最適化 & Y軸範囲修正

**実施内容:**
- フォーカス閾値: 150.0に引き上げ (品質優先)
- Y軸有効範囲: 120.5mmに修正 (実測値ベース)

**効果:**
- 数量検出精度: 50% → 57% → 64%
- 小さい1桁数字(1, 2, 9)新規検出成功

---

### 07:15: 数量列特化OCR処理の実装

**課題:**
- ML Kit日本語モデルは小さい1桁数字を「ノイズ」として落とす
- 優先度: 日本語 > 数字、大きい文字 > 小さい文字

**解決策: 2段階OCR処理**
1. **全体OCR（日本語）** - 行検出、商品名、金額、行タイプ
2. **数量列特化OCR（Latin + 4倍拡大）** - 数量のみ再処理

**実装内容:**
1. **数量列ROI切り出し** (OCRProcessor.kt:522-638)
   ```kotlin
   // 1. 数量列切り出し (X: 1134-1296px)
   // 2. 4倍アップスケール (162px → 648px)
   // 3. Latin OCR（数字に強い）
   // 4. Y座標で行にマッピング → 上書き
   ```

2. **正規化関数**
   ```kotlin
   fun normalizeQuantity(raw: String): Int? {
       return raw
           .replace("o", "0").replace("O", "0")
           .replace("l", "1").replace("I", "1")
           .toIntOrNull()
   }
   ```

3. **返品処理**
   ```kotlin
   if (row.itemName?.contains("返品") == true && qty > 0) {
       quantity = -qty
   }
   ```

**効果:**
- 初期実装: 50% → 57%
- 「50」「2920」「2850」検出・正規化成功

---

## 2025-12-23

### 23:35: 行クラスタリング最適化

**実施内容:**

1. **行クラスタリング閾値最適化**
   - 旧: 25px (広すぎて異なる行が混在)
   - 新: 15px (実測値ベース: 64.5mm/20行≈3.2mm/行)
   - 結果: 28行 → 32行分離

2. **列範囲の再定義**
   ```
   旧: IGNORE_RANGE: 992-1437px (数量も無視)

   新:
   - DATE_RANGE:      392-510px
   - ITEM_RANGE:      510-992px
   - STORE_RANGE:     992-1133px  (無視)
   - QUANTITY_RANGE:  1133-1295px (取得！)
   - UNITPRICE_RANGE: 1295-1437px (無視)
   - AMOUNT_RANGE:    1437-1611px
   - CATEGORY_RANGE:  1611-1786px
   ```

3. **ReceiptRowデータ構造拡張**
   ```kotlin
   data class ReceiptRow(
       val rowType: RowType,
       val date: String?,
       val itemName: String?,
       val quantity: String?,  // ← 新規追加
       val amount: Int?,
       val categorySum: Int?,
       val rawText: String?
   )
   ```

---

## 記録終了

最新の状況は `.clinerules` のトップセクションを参照してください。
