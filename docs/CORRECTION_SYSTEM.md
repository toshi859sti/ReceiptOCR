# OCR Correction System V3 - Three-Layer Architecture

誤認識補正システム V3 の設計・実装ドキュメント。

---

## 設計思想

### 基本原則
- **誤変換ゼロ原則**: 「当たったときだけ強く補正」
- **Precisionに全振り**: Recallを捨てて精度を最優先
- **Conservative Approach**: 確信がないときは補正しない

### 三層構造

| Layer | 名称 | 役割 | 動作 |
|-------|------|------|------|
| **Layer 0** | 制約バリア | ハード制約 | 容量・カテゴリ不一致 → 即除外 |
| **Layer 1** | 全文マッチング | 確定補正の唯一の源 | Levenshtein距離でスコア計算 |
| **Layer 2** | ボーナス計算 | スコア加点のみ | 先頭欠落、濁点、N-gram |

---

## スコア定数

### 受理閾値 (ProductNameCorrectorV3.kt)

```kotlin
/** 最低受理スコア */
private const val MIN_ACCEPT_SCORE = 0.78

/** 1位-2位の最小スコア差 */
private const val MIN_SCORE_GAP = 0.12

/** 高スコア閾値（学習登録用） */
private const val HIGH_SCORE_THRESHOLD = 0.90

/** 中スコア閾値（学習登録用） */
private const val MID_SCORE_THRESHOLD = 0.85

/** Levenshtein最大距離（長さ依存） */
private const val MAX_DISTANCE_RATIO = 0.25  // 長さの25%まで
```

### ボーナス定数

```kotlin
/** 先頭欠落ボーナス */
private const val HEAD_MISSING_BONUS = 0.08

/** 末尾欠落ボーナス */
private const val TAIL_MISSING_BONUS = 0.06

/** 濁点ミスマッチボーナス（1文字） */
private const val DAKUTEN_BONUS_SINGLE = 0.02

/** 濁点ミスマッチボーナス（2文字） */
private const val DAKUTEN_BONUS_DOUBLE = 0.04

/** N-gramボーナス（1ヒットあたり） */
private const val NGRAM_BONUS_PER_HIT = 0.015

/** N-gramボーナス最大値 */
private const val MAX_NGRAM_BONUS = 0.05

/** ボーナス合計の最大値 */
private const val MAX_TOTAL_BONUS = 0.10

/** 競合判定閾値 */
private const val CONFLICT_THRESHOLD = 0.85
```

---

## Layer 0: ハード制約

### 目的
誤変換の根本原因を排除。容量やカテゴリが違う商品への変換を絶対に防ぐ。

### 制約条件

```kotlin
private fun passHardConstraints(
    product: ProductMaster,
    ocrParts: ProductParts
): Boolean {
    val productParts = parseProductName(product.canonicalName)

    // 容量が両方あり、不一致ならNG
    if (ocrParts.capacity.isNotEmpty() && productParts.capacity.isNotEmpty()) {
        if (ocrParts.capacity != productParts.capacity) {
            return false
        }
    }

    // 単位が両方あり、不一致ならNG
    if (ocrParts.unit.isNotEmpty() && productParts.unit.isNotEmpty()) {
        if (!isSameUnit(ocrParts.unit, productParts.unit)) {
            return false
        }
    }

    return true
}
```

### 単位正規化

```kotlin
private fun normalizeUnit(unit: String): String {
    return unit.lowercase()
        .replace("ｇ", "g")
        .replace("ｋｇ", "kg")
        .replace("ｍｌ", "ml")
        .replace("ｌ", "l")
        .replace("ℓ", "l")
        .replace("Ｌ", "l")
        .replace("ｃｃ", "cc")
}
```

---

## Layer 1: 全文マッチング

### 目的
確定補正の唯一の源。Levenshtein距離でベーススコアを計算。

### スコア計算

```kotlin
// Levenshtein距離
val distance = levenshteinDistance(normalizedOcrBase, normalizedProductBase)
val maxLen = max(normalizedOcrBase.length, normalizedProductBase.length)

// 最大距離チェック（長さ依存）
val maxAllowedDistance = max(2, (maxLen * MAX_DISTANCE_RATIO).toInt())
if (distance > maxAllowedDistance) {
    continue  // 候補から除外
}

// ベーススコア
val baseScore = if (maxLen == 0) 0.0 else 1.0 - distance.toDouble() / maxLen
```

### 正規化処理

```kotlin
private fun normalize(text: String): String {
    return text
        // 記号・空白を除去
        .replace(Regex("[\\s　・、。]"), "")
        // 全角英数を半角に
        .replace(Regex("[Ａ-Ｚａ-ｚ０-９]")) { match ->
            (match.value[0].code - 0xFEE0).toChar().toString()
        }
}
```

---

## Layer 2: ボーナス計算

### 目的
スコア加点のみ。先頭欠落、末尾欠落、濁点、N-gramでボーナスを付与。

### ボーナス内訳

```kotlin
data class BonusBreakdown(
    val headMissing: Double = 0.0,   // 先頭欠落ボーナス
    val tailMissing: Double = 0.0,   // 末尾欠落ボーナス
    val dakuten: Double = 0.0,       // 濁点ボーナス
    val ngram: Double = 0.0          // N-gramボーナス
) {
    val total: Double get() = min(
        headMissing + tailMissing + dakuten + ngram,
        MAX_TOTAL_BONUS  // 0.10が上限
    )
}
```

### 先頭欠落検出

```kotlin
/**
 * 先頭欠落パターン検出
 *
 * 条件:
 * 1) length(P) = length(R) + 1
 * 2) P.substring(1) == R
 * 3) P[0] is NOT numeric or unit character
 */
private fun isHeadMissingPattern(raw: String, product: String): Boolean {
    if (product.length != raw.length + 1) return false
    if (product.substring(1) != raw) return false
    if (isNumericOrUnit(product[0])) return false
    return true
}
```

### 濁点ミスマッチ検出

```kotlin
/**
 * 濁点ミスマッチ数カウント
 *
 * 同じ基本文字で濁点の有無のみ異なる場合にカウント
 * 3文字以上は「別語」の可能性が高いため0を返す
 */
private fun dakutenMismatchCount(raw: String, product: String): Int {
    if (raw.length != product.length) return 0

    var count = 0
    for (i in raw.indices) {
        val rBase = baseChar(raw[i])
        val pBase = baseChar(product[i])

        if (rBase == pBase) {
            if (dakutenType(raw[i]) != dakutenType(product[i])) {
                count++
            }
        } else {
            return 0  // 他の差異があれば即NG
        }
    }

    return if (count in 1..2) count else 0
}
```

### N-gramボーナス

```kotlin
/**
 * N-gramボーナス計算（2-gram, 3-gram）
 */
private fun calculateNgramBonus(raw: String, product: String): Double {
    if (raw.length < 2 || product.length < 2) return 0.0

    var bonus = 0.0

    // 2-gram
    val raw2grams = raw.windowed(2).toSet()
    val product2grams = product.windowed(2).toSet()
    val match2 = raw2grams.intersect(product2grams).size
    bonus += match2 * NGRAM_BONUS_PER_HIT

    // 3-gram
    if (raw.length >= 3 && product.length >= 3) {
        val raw3grams = raw.windowed(3).toSet()
        val product3grams = product.windowed(3).toSet()
        val match3 = raw3grams.intersect(product3grams).size
        bonus += match3 * NGRAM_BONUS_PER_HIT
    }

    return min(bonus, MAX_NGRAM_BONUS)
}
```

---

## 補正確定判定

### 判定フロー

```kotlin
private fun decideCorrection(...): CorrectionResult {
    // 条件1: 最低スコア
    if (top.finalScore < MIN_ACCEPT_SCORE) {
        return REJECT_SCORE_LOW
    }

    // 条件2: 2位との差
    if (second != null) {
        val gap = top.finalScore - second.finalScore
        if (gap < MIN_SCORE_GAP) {
            // 競合チェック
            val conflict = calculateConflict(top.product, second.product)
            if (conflict >= CONFLICT_THRESHOLD) {
                return REJECT_SIMILAR_PRODUCTS_CONFLICT
            }
            return REJECT_GAP_INSUFFICIENT
        }
    }

    // 条件3: 自己一致防止
    if (ocrRawText == top.product.canonicalName) {
        return ALREADY_CORRECT
    }

    // 補正確定
    return SIMILARITY_MATCH
}
```

### 競合度計算

```kotlin
/**
 * 2商品の類似度を計算
 * 類似度が高い（CONFLICT_THRESHOLD以上）場合、競合として判定
 */
private fun calculateConflict(p1: ProductMaster, p2: ProductMaster): Double {
    val name1 = normalize(parseProductName(p1.canonicalName).baseName)
    val name2 = normalize(parseProductName(p2.canonicalName).baseName)
    val distance = levenshteinDistance(name1, name2)
    val maxLen = max(name1.length, name2.length)
    return if (maxLen == 0) 1.0 else 1.0 - distance.toDouble() / maxLen
}
```

---

## 補正理由 (CorrectionReason)

```kotlin
enum class CorrectionReason {
    NO_INPUT,                       // 入力なし
    NO_CANDIDATES,                  // 候補なし
    OCR_VARIANT_CONFIRMED,          // 確認済みパターンヒット
    SIMILARITY_MATCH,               // 類似度マッチ（補正成功）
    REJECT_SCORE_LOW,               // スコア不足
    REJECT_GAP_INSUFFICIENT,        // 2位との差不足
    REJECT_SIMILAR_PRODUCTS_CONFLICT // 類似商品競合
}
```

---

## OcrVariant 学習システム

### 信頼度レベル

```kotlin
enum class ConfidenceLevel {
    AUTO,       // 自動学習（初期状態）
    CONFIRMED,  // 確認済み（昇格条件を満たした）
    LOCKED      // ロック（ユーザー確定）
}
```

### 昇格条件

```kotlin
/**
 * CONFIRMED昇格条件（すべて満たす必要あり）
 */
fun canPromoteToConfirmed(): Boolean {
    return hitCount >= 5 &&           // 5回以上ヒット
           uniqueDays >= 3 &&          // 3日以上の使用
           avgFinalScore >= 0.88 &&    // 平均スコア0.88以上
           highScoreHits >= 3          // 高スコア(0.90+)ヒット3回以上
}
```

### 減衰計算

```kotlin
/**
 * 減衰スコア計算
 * exp(-days/30) で30日で約37%に減衰
 */
fun calculateDecayScore(): Double {
    val daysSinceLastSeen = (System.currentTimeMillis() - lastSeenAt) / (1000 * 60 * 60 * 24)
    return exp(-daysSinceLastSeen / 30.0)
}
```

### 学習登録

```kotlin
/**
 * 学習登録（スコアが十分高い場合のみ）
 */
private suspend fun registerLearningIfNeeded(
    variantDao: OcrVariantDao,
    ocrRawText: String,
    normalizedRaw: String,
    productId: Long,
    finalScore: Double
) {
    if (finalScore >= MID_SCORE_THRESHOLD) {  // 0.85以上
        variantDao.registerLearning(
            variantText = ocrRawText,
            normalizedText = normalizedRaw,
            productId = productId,
            finalScore = finalScore,
            source = VariantSource.AUTO
        )
    }
}
```

---

## データベーススキーマ

### OcrVariant テーブル (v4)

```sql
CREATE TABLE ocr_variants (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    productId INTEGER NOT NULL,
    variantText TEXT NOT NULL,
    normalizedText TEXT NOT NULL DEFAULT '',
    confidenceLevel TEXT NOT NULL DEFAULT 'AUTO',
    hitCount INTEGER NOT NULL DEFAULT 0,
    highScoreHits INTEGER NOT NULL DEFAULT 0,
    avgFinalScore REAL NOT NULL DEFAULT 0.0,
    totalScore REAL NOT NULL DEFAULT 0.0,
    firstSeenAt INTEGER NOT NULL,
    lastSeenAt INTEGER NOT NULL,
    uniqueDays INTEGER NOT NULL DEFAULT 1,
    lastSeenDate INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT 'AUTO',
    isDisabled INTEGER NOT NULL DEFAULT 0,
    disabledReason TEXT,
    FOREIGN KEY (productId) REFERENCES product_master(id) ON DELETE CASCADE
);

CREATE INDEX index_ocr_variants_productId ON ocr_variants (productId);
CREATE INDEX index_ocr_variants_variantText ON ocr_variants (variantText);
CREATE INDEX index_ocr_variants_normalizedText ON ocr_variants (normalizedText);
CREATE INDEX index_ocr_variants_confidenceLevel ON ocr_variants (confidenceLevel);
```

### CorrectionLog テーブル

```sql
CREATE TABLE correction_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    rawText TEXT NOT NULL,
    normalizedRaw TEXT NOT NULL,
    category TEXT NOT NULL,
    hardConstraintsPassed INTEGER NOT NULL,
    topProduct TEXT,
    topBaseScore REAL NOT NULL,
    topBonusTotal REAL NOT NULL,
    topFinalScore REAL NOT NULL,
    secondProduct TEXT,
    secondFinalScore REAL NOT NULL,
    decision TEXT NOT NULL,
    correctedName TEXT,
    matched INTEGER NOT NULL,
    bonusBreakdown TEXT
);

CREATE INDEX index_correction_logs_timestamp ON correction_logs (timestamp);
CREATE INDEX index_correction_logs_decision ON correction_logs (decision);
CREATE INDEX index_correction_logs_sessionId ON correction_logs (sessionId);
```

---

## 処理フロー

```
1. 入力チェック
   └─ 空白 → NO_INPUT

2. 前処理
   ├─ normalize(ocrRawText)
   └─ parseProductName(ocrRawText)

3. Layer 0: ハード制約
   ├─ カテゴリフィルタ (getByCategory)
   └─ 容量・単位チェック (passHardConstraints)

4. OcrVariant直撃補正
   └─ CONFIRMED以上 → OCR_VARIANT_CONFIRMED

5. Layer 1 + Layer 2: スコアリング
   ├─ generateAndScoreCandidates()
   ├─ baseScore = Levenshtein類似度
   └─ finalScore = baseScore + bonus.total

6. 補正確定判定
   ├─ finalScore < 0.78 → REJECT_SCORE_LOW
   ├─ gap < 0.12 → REJECT_GAP_INSUFFICIENT
   ├─ conflict >= 0.85 → REJECT_SIMILAR_PRODUCTS_CONFLICT
   └─ 条件クリア → SIMILARITY_MATCH

7. 学習登録
   └─ finalScore >= 0.85 → registerLearning()

8. ログ保存
   └─ CorrectionLog → correction_logs テーブル
```

---

## ファイル構成

```
app/src/main/java/com/example/receiptorc/
├── util/
│   ├── ProductNameCorrectorV3.kt  # 三層補正システム（メイン）
│   ├── ProductNameCorrectorV2.kt  # 旧システム（互換性）
│   └── ProductNameCorrector.kt    # 初期システム（互換性）
├── data/
│   ├── OcrVariant.kt              # 学習パターン Entity
│   ├── OcrVariantDao.kt           # 学習パターン DAO
│   ├── CorrectionLog.kt           # 補正ログ Entity
│   ├── CorrectionLogDao.kt        # 補正ログ DAO
│   └── ReceiptDatabase.kt         # Room Database (v4)
```

---

## 更新履歴

- **2026-01-16**: ProductNameCorrectorV3実装、三層構造設計、OcrVariant V2スキーマ
- **2026-01-01**: ProductNameCorrectorV2実装（全角容量対応、容量重複バグ修正）
- **2025-12-29**: 初期実装（ProductNameCorrector）
