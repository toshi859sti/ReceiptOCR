# Dictionary-Based Correction System

辞書ベース補正システムの設計ドキュメント。

---

## 概要

農協の購買伝票は商品が限定的なため、辞書ベースの補正が非常に効果的。

### 重要な要件
1. **商品コードなし** - 商品名のみで補正
2. **カテゴリは小計ベース** - "一般購買", "給油所", "農業機械" など
3. **容量違いの商品あり** - 正しい容量を誤って書き換えない仕組みが必須

---

## データベース設計

### ProductMaster (商品マスタ)
```kotlin
@Entity(tableName = "product_master")
data class ProductMaster(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productName: String,    // 正しい商品名 (例: "フェニックス顆粒水和剤250g")
    val category: String,       // カテゴリ: "一般購買", "給油所", "農業機械"
    val frequency: Int = 0,     // 使用頻度（よく買う商品を優先）
    val createdAt: Long = System.currentTimeMillis()
)
```

### OcrVariant (OCR誤認識パターン)
```kotlin
@Entity(
    tableName = "ocr_variants",
    foreignKeys = [ForeignKey(
        entity = ProductMaster::class,
        parentColumns = ["id"],
        childColumns = ["product_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class OcrVariant(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "product_id")
    val productId: Long,           // 対応する商品ID
    @ColumnInfo(name = "variant_text")
    val variantText: String,       // OCR誤認識パターン (例: "フェニックス類粒水和剤")
    @ColumnInfo(name = "occurrence_count")
    val occurrenceCount: Int = 1,  // 出現回数
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long = System.currentTimeMillis()
)
```

### YayoiAccount (弥生勘定科目)
```kotlin
@Entity(tableName = "yayoi_accounts")
data class YayoiAccount(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountCode: String,    // 勘定科目コード
    val accountName: String,    // 勘定科目名
    val category: String,       // 大分類
    val subcategory: String?,   // 小分類
    val description: String?    // 説明
)
```

### RakurakuAccount (らくらく青色申告勘定科目)
```kotlin
@Entity(tableName = "rakuraku_accounts")
data class RakurakuAccount(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountCode: String,
    val accountName: String,
    val category: String,
    val subcategory: String?,
    val description: String?
)
```

### Database Migration
```kotlin
// v2 → v3
Room.databaseBuilder(context, ReceiptDatabase::class.java, "receipt_db")
    .addMigrations(MIGRATION_2_3)
    .build()
```

---

## CSV初期データ

### product_master.csv
- **総数**: 113商品
- **カテゴリ別**:
  - 一般購買: 農薬、肥料、資材など
  - 給油所: ガソリン、軽油、灯油、オイルなど
  - 農業機械: (現在は少数)

**頻出商品例:**
| 商品名 | カテゴリ | 頻度 |
|--------|---------|------|
| レギュラーガソリン | 給油所 | 56 |
| 軽油 | 給油所 | 32 |
| トミー顆粒水和剤250g | 一般購買 | 18 |
| フェニックス顆粒水和剤250g | 一般購買 | 15 |

### ocr_variants.csv
- **総数**: 13パターン
- **例**:
  - "フェニックス類粒水和剤" → "フェニックス顆粒水和剤"
  - "レギュラーガリン" → "レギュラーガソリン"
  - "トミー類粒水和剤" → "トミー顆粒水和剤"

### yayoi_accounts.csv
- **総数**: 15勘定科目
- **例**: 農薬費, 肥料費, 農具費, 車両費, 燃料費 など

### rakuraku_accounts.csv
- **総数**: 15勘定科目
- **例**: 種苗費, 肥料費, 農薬衛生費, 動力光熱費 など

---

## 容量保護型マッチング設計

### ProductNameParts (商品名分解)
```kotlin
data class ProductNameParts(
    val baseName: String,   // "フェニックス顆粒水和剤"
    val capacity: String?,  // "250g"
    val fullName: String    // "フェニックス顆粒水和剤250g"
)
```

### parseProductName (商品名パース)
```kotlin
fun parseProductName(name: String): ProductNameParts {
    // 容量パターン: 数字 + 単位
    // V1: 半角のみ
    val capacityPattern = Regex("""(\d+(?:\.\d+)?)(g|kg|ml|mℓ|L|ℓ|cc|個|本|枚|袋|缶)$""")

    // V2: 全角対応 (2026-01-01~)
    val capacityPattern = Regex(
        """(\d+(?:[.．]\d+)?)(g|ｇ|kg|ｋｇ|ml|ｍｌ|mℓ|L|ℓ|ｌ|Ｌ|cc|ｃｃ|個|本|枚|袋|缶)$"""
    )

    val match = capacityPattern.find(name)

    return if (match != null) {
        val capacity = match.value
        val baseName = name.removeSuffix(capacity).trim()
        ProductNameParts(baseName, capacity, name)
    } else {
        ProductNameParts(name, null, name)
    }
}
```

**対応容量単位:**
- **重量**: g, kg (半角/全角)
- **容量**: ml, mℓ, L, ℓ, cc (半角/全角)
- **個数**: 個, 本, 枚, 袋, 缶

---

## 補正ロジック

### correctProductName (容量保護型補正)
```kotlin
suspend fun correctProductName(
    ocrName: String,
    category: String,
    productDao: ProductMasterDao
): Pair<String, Float> {
    // 1. OCR結果を分解
    val ocrParts = parseProductName(ocrName)

    // 2. カテゴリ内の商品を取得
    val products = productDao.getProductsByCategory(category)

    var bestMatch: ProductMaster? = null
    var bestSimilarity = 0f

    // 3. マッチング
    for (product in products) {
        val masterParts = parseProductName(product.productName)

        // 3.1. ベース名のみで類似度を計算
        val baseSimilarity = calculateSimilarity(
            ocrParts.baseName,
            masterParts.baseName
        )

        // 3.2. 容量フィルタ
        if (ocrParts.capacity != null && masterParts.capacity != null) {
            // 容量が一致する場合のみマッチング対象
            if (ocrParts.capacity == masterParts.capacity) {
                if (baseSimilarity > bestSimilarity) {
                    bestSimilarity = baseSimilarity
                    bestMatch = product
                }
            }
        } else {
            // 容量なしの商品
            if (baseSimilarity > bestSimilarity) {
                bestSimilarity = baseSimilarity
                bestMatch = product
            }
        }
    }

    // 4. 補正結果を生成（ベース名は補正、容量はOCRを保持）
    return if (bestSimilarity >= SIMILARITY_THRESHOLD && bestMatch != null) {
        val masterParts = parseProductName(bestMatch.productName)

        // ベース名を補正、容量はOCRを保持
        val correctedName = if (ocrParts.capacity != null) {
            "${masterParts.baseName}${ocrParts.capacity}"
        } else {
            bestMatch.productName
        }

        Pair(correctedName, bestSimilarity)
    } else {
        Pair(ocrName, 0f)
    }
}
```

**重要ポイント:**
- **ベース名のみで類似度計算** - 容量は除外
- **容量一致フィルタ** - 容量が異なる商品は候補から除外
- **OCR容量を保持** - マスタの容量ではなく、OCRで読み取った容量を使用
- **閾値判定** - 類似度0.7以上で補正実行

---

## 類似度計算

### Levenshtein Distance (編集距離)
```kotlin
private fun levenshteinDistance(s1: String, s2: String): Int {
    val len1 = s1.length
    val len2 = s2.length
    val dp = Array(len1 + 1) { IntArray(len2 + 1) }

    // 初期化
    for (i in 0..len1) dp[i][0] = i
    for (j in 0..len2) dp[0][j] = j

    // DP計算
    for (i in 1..len1) {
        for (j in 1..len2) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,      // 削除
                dp[i][j - 1] + 1,      // 挿入
                dp[i - 1][j - 1] + cost // 置換
            )
        }
    }

    return dp[len1][len2]
}
```

### calculateSimilarity (類似度スコア)
```kotlin
private fun calculateSimilarity(s1: String, s2: String): Float {
    val distance = levenshteinDistance(s1, s2)
    val maxLength = maxOf(s1.length, s2.length)
    return if (maxLength == 0) 1f else 1f - (distance.toFloat() / maxLength)
}
```

**スコア範囲:**
- 1.0: 完全一致
- 0.9-0.99: 1-2文字の誤認識
- 0.7-0.89: 2-4文字の誤認識 (閾値)
- 0.0-0.69: 補正しない

---

## 補正例

### 例1: 容量が正しい場合
```
OCR:  "フェニックス類粒水和剤250g"
      ↓ 分解
Base: "フェニックス類粒水和剤"  Capacity: "250g"
      ↓ マスタとベース名で比較（容量一致のもののみ）
Master: "フェニックス顆粒水和剤" + "250g" ✓ (容量一致)
        "フェニックス顆粒水和剤" + "500g" ✗ (容量不一致 → 除外)
      ↓ 類似度計算
Similarity: 0.85 (≥ 0.7)
      ↓ 補正実行
Result: "フェニックス顆粒水和剤250g"
        (ベース名補正、容量は保持)
```

### 例2: 容量なしの商品
```
OCR:  "レギュラーガリン"
      ↓ 分解
Base: "レギュラーガリン"  Capacity: null
      ↓ マスタとベース名で比較
Master: "レギュラーガソリン" ✓
      ↓ 類似度計算
Similarity: 0.80 (≥ 0.7)
      ↓ 補正実行
Result: "レギュラーガソリン"
```

### 例3: 容量誤認識の保護
```
OCR:  "フェニックス類粒水和剤500g"
      ↓ 分解
Base: "フェニックス類粒水和剤"  Capacity: "500g"
      ↓ マスタとベース名で比較（容量一致のもののみ）
Master: "フェニックス顆粒水和剤" + "250g" ✗ (容量不一致 → 除外)
        "フェニックス顆粒水和剤" + "500g" ✓ (容量一致)
      ↓ 類似度計算
Similarity: 0.85 (≥ 0.7)
      ↓ 補正実行
Result: "フェニックス顆粒水和剤500g"
        (250gへの誤変換を防止！)
```

### 例4: 容量重複バグの修正 (2026-01-01~)
```
旧実装:
OCR:  "アグリードCフロアブル100cc"
      ↓
Result: "アグリードCフロアブル100cc100 co"
        (容量が重複！)

新実装 (V2):
OCR:  "アグリードCフロアブル100cc"
      ↓ マスタ検索 (全角容量パターンも対応)
Master: "アグリードCフロアブル100cc"
      ↓ 容量抽出・補正
Result: "アグリードCフロアブル100cc"
        (重複なし！)
```

---

## カテゴリ判定

### Lookahead方式 (小計行から逆算)
```kotlin
fun assignCategories(rows: List<ReceiptRow>): List<ReceiptRow> {
    var currentCategory = "一般購買"  // デフォルト

    // 逆順でスキャン
    for (i in rows.indices.reversed()) {
        val row = rows[i]

        // 小計行を検出 → カテゴリ更新
        if (row.rowType == RowType.CATEGORY_SUBTOTAL) {
            currentCategory = detectCategoryFromSubtotal(row)
        }

        // 通常行にカテゴリを割り当て
        if (row.rowType == RowType.ITEM) {
            row.category = currentCategory
        }
    }
}
```

### 小計キーワード検出
```kotlin
fun detectCategoryFromSubtotal(row: ReceiptRow): String {
    val text = row.rawText ?: return "一般購買"

    return when {
        text.contains("給値所") || text.contains("給油所") -> "給油所"
        text.contains("展業慢城") || text.contains("農業機械") -> "農業機械"
        else -> "一般購買"
    }
}
```

**OCR誤認識対応:**
- "給値所" → "給油所"
- "展業慢城" → "農業機械"

---

## 自動学習機能

### 学習トリガー
```kotlin
if (bestSimilarity >= SIMILARITY_THRESHOLD && bestMatch != null) {
    // 補正が成功した場合

    // OCR結果が既存variantと完全一致しない場合、新規記録
    val existingVariant = ocrVariantDao.findVariantByText(ocrName)

    if (existingVariant == null) {
        // 新しいOCR誤認識パターンを記録
        ocrVariantDao.insert(OcrVariant(
            productId = bestMatch.id,
            variantText = ocrName,
            occurrenceCount = 1,
            lastSeen = System.currentTimeMillis()
        ))
    } else {
        // 出現回数を更新
        ocrVariantDao.update(existingVariant.copy(
            occurrenceCount = existingVariant.occurrenceCount + 1,
            lastSeen = System.currentTimeMillis()
        ))
    }
}
```

**利点:**
- ユーザー固有のOCR誤認識パターンを学習
- 出現頻度の高いパターンを優先的に補正
- メンテナンス不要で精度が向上

---

## テスト結果

### 2025-12-29 実装完了
- 補正成功率: 70% (14/20)
- CSV初期データインポート完了

### 2025-12-30 統合テスト
- 検出行数: 20行
- 補正成功率: 55%
- 自動学習: 6件の新パターン記録

### 2025-12-31 大規模テスト
- 3枚の伝票、57行
- 補正成功率: 35% (20/57)
- 原因: OCR精度低 (商品名列拡大なし)

### 2026-01-01 DoubleOCR + V2補正
- 検出行数: 17行
- 補正成功率: **100% (9/9 補正可能項目)**
- 容量重複バグ: ✅ 解決
- 全角容量対応: ✅ 実装

---

## 実装予定機能

### 短期
- [x] ProductMaster.kt, ProductMasterDao.kt 作成 ✅
- [x] ReceiptDatabase.kt に product_master テーブル追加 ✅
- [x] ProductNameCorrector.kt 作成 ✅
- [x] assets/product_master.csv 作成 ✅
- [x] UnderlyingBaseProcessor.kt に統合 ✅
- [x] カテゴリ判定ロジック（小計行から逆算) ✅
- [x] ProductNameCorrectorV2.kt (全角容量対応) ✅ 2026-01-01

### 中期
- [ ] OcrVariantからのパターンマッチング優先 (学習データ活用)
- [ ] 頻度ベースのランキング補正
- [ ] ユーザーによる手動補正 → 自動学習
- [ ] 商品マスタのUI編集機能

### 長期
- [ ] 複数農協対応（地域別商品マスタ）
- [ ] クラウド同期（共通商品マスタ）
- [ ] カテゴリ自動学習（教師なし学習）

---

## 期待効果

- "類" → "顆粒" などの誤字を自動補正
- i/o/O の誤認識を吸収
- 容量誤変換を防止（250g → 500g への誤変換なし）
- カテゴリ別フィルタで精度向上
- 使用頻度が高い商品を優先補正
- ユーザー固有の誤認識パターンを学習

---

## 更新履歴

- **2026-01-01**: ProductNameCorrectorV2実装 (全角容量対応、容量重複バグ修正)
- **2025-12-30**: 統合テスト、自動学習機能動作確認
- **2025-12-29**: 初期実装完了、CSV初期データインポート
- **2025-12-25**: 設計完了、仕様書作成
