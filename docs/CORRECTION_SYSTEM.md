# OCR Correction System V3 - Three-Layer Architecture

誤認識補正システム V3 の設計・実装ドキュメント。

---

## 設計思想

### 基本原則
- **誤変換ゼロ原則**: 「当たったときだけ強く補正」
- **Precisionに全振り**: Recallを捨てて精度を最優先
- **Conservative Approach**: 確信がないときは補正しない
- **時間減衰の廃止**: 低頻度利用（月1回〜年1回）を想定

### 三層構造

| Layer | 名称 | 役割 | 動作 |
|-------|------|------|------|
| **Layer 1** | 確定知識 | LOCKED / 手動CONFIRMED | 無条件で即座に補正適用 |
| **Layer 2** | 条件付き知識 | 自動CONFIRMED | スコア検証後に適用 |
| **Layer 3** | マスタ直接マッチ | 商品マスタ | スコア計算・閾値判定後に適用 |

---

## スコア定数（100点満点）

### 受理閾値 (ProductNameCorrectorV3.kt)

```kotlin
/** 最低採用スコア（マスタ直接マッチ） */
private const val MIN_ACCEPT = 78.0

/** 2位との最低差分（マスタ直接マッチ） */
private const val MIN_GAP = 12.0

/** CONFIRMED知識の最低採用スコア */
private const val MIN_ACCEPT_CONFIRMED = 75.0

/** CONFIRMED知識の最低差分 */
private const val MIN_GAP_CONFIRMED = 10.0

/** 学習登録の最低スコア */
private const val MIN_LEARNING_SCORE = 88.0

/** 学習登録の最低差分 */
private const val MIN_LEARNING_GAP = 12.0

/** 学習登録の最低文字数 */
private const val MIN_LEARNING_LENGTH = 3
```

### スコア構成（100点満点）

```kotlin
/** 文字類似度（最大60点） */
private const val MAX_TEXT_SIMILARITY = 60.0

/** 先頭欠落ボーナス（最大10点） */
private const val MAX_PREFIX_BONUS = 10.0

/** 濁点誤認識ボーナス（最大5点） */
private const val MAX_DAKUTEN_BONUS = 5.0

/** 既存知識ボーナス（最大15点） */
private const val MAX_VARIANT_BONUS = 15.0

/** 手動修正履歴ボーナス（最大10点） */
private const val MAX_HISTORY_BONUS = 10.0

/** リスクペナルティ（最大-30点） */
private const val MAX_RISK_PENALTY = -30.0
```

---

## Layer 1: 確定知識（無条件適用）

### 目的
LOCKED または 手動CONFIRMED（source=USER）のパターンはスコア計算なしで即座に補正。

### 検索クエリ
```kotlin
@Query("""
    SELECT * FROM ocr_variants
    WHERE normalizedText = :normalizedText
    AND isDisabled = 0
    AND (
        confidenceLevel = 'LOCKED'
        OR (confidenceLevel = 'CONFIRMED' AND source = 'USER')
    )
    ORDER BY
        CASE confidenceLevel WHEN 'LOCKED' THEN 0 ELSE 1 END,
        hitCount DESC
    LIMIT 1
""")
suspend fun findUnconditionalVariant(normalizedText: String): OcrVariant?
```

---

## Layer 2: 条件付き知識（スコア検証）

### 目的
自動昇格によるCONFIRMED（source != USER）は、スコア検証後に適用。

### 検証条件
```kotlin
if (top.breakdown.totalScore >= MIN_ACCEPT_CONFIRMED) {  // 75点以上
    val gap = top.breakdown.totalScore - (second?.breakdown?.totalScore ?: 0.0)
    if (second == null || gap >= MIN_GAP_CONFIRMED) {  // 2位との差10点以上
        // 補正適用
    }
}
```

---

## Layer 3: マスタ直接マッチング

### 目的
OcrVariantに登録がない場合、商品マスタから候補を検索してスコア計算。

### ハード制約（候補フィルタ）

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

    return true
}
```

### 判定条件
```kotlin
when {
    top.breakdown.totalScore < MIN_ACCEPT -> REJECT_SCORE_LOW          // 78点未満
    second != null && gap < MIN_GAP -> REJECT_GAP_INSUFFICIENT         // 差12点未満
    top.breakdown.riskPenalty <= MAX_RISK_PENALTY -> REJECT_RISK_PENALTY // -30点ペナルティ
    else -> MASTER_MATCH  // 補正成功
}
```

---

## スコア計算詳細

### 文字類似度（最大60点）
```kotlin
private fun calculateTextSimilarity(raw: String, product: String): Double {
    val maxLen = max(raw.length, product.length)
    if (maxLen == 0) return 0.0

    val distance = levenshteinDistance(raw, product)
    val normalizedSimilarity = 1.0 - (distance.toDouble() / maxLen)
    return (normalizedSimilarity * MAX_TEXT_SIMILARITY).coerceIn(0.0, MAX_TEXT_SIMILARITY)
}
```

### 先頭欠落ボーナス（最大10点）
```kotlin
private fun calculatePrefixBonus(raw: String, product: String): Double {
    // 先頭1文字欠落 → 10点
    if (isPrefixDroppedMatch(raw, product, 1)) {
        return MAX_PREFIX_BONUS
    }
    // 先頭2文字欠落 → 5点
    if (isPrefixDroppedMatch(raw, product, 2)) {
        return MAX_PREFIX_BONUS / 2
    }
    return 0.0
}
```

### 濁点誤認識ボーナス（最大5点）
```kotlin
private fun calculateDakutenBonus(raw: String, product: String): Double {
    if (raw == product) return 0.0
    if (removeDakuten(raw) == removeDakuten(product)) {
        return MAX_DAKUTEN_BONUS
    }
    return 0.0
}
```

### 既存知識ボーナス（最大15点）
```kotlin
private fun calculateVariantBonus(existingVariant: OcrVariant?): Double {
    return when (existingVariant?.confidenceLevel) {
        ConfidenceLevel.LOCKED.name -> 15.0     // LOCKED
        ConfidenceLevel.CONFIRMED.name -> 10.0  // CONFIRMED
        ConfidenceLevel.AUTO.name -> 0.0        // AUTOは補正に使わない
        else -> 0.0
    }
}
```

### 手動修正履歴ボーナス（最大10点）
```kotlin
private fun calculateHistoryBonus(existingVariant: OcrVariant?): Double {
    if (existingVariant == null) return 0.0
    return if (existingVariant.manualCorrectCount > 0) MAX_HISTORY_BONUS else 0.0
}
```

### リスクペナルティ（最大-30点）
```kotlin
private fun calculateRiskPenalty(...): Double {
    var penalty = 0.0

    // 容量違い → -30点
    if (ocrParts.capacity.isNotEmpty() && productParts.capacity.isNotEmpty() &&
        ocrParts.capacity != productParts.capacity) {
        penalty += -30.0
    }

    // 数字違い → -30点
    val rawNumbers = extractNumbers(normalizedRaw)
    val productNumbers = extractNumbers(normalizedProduct)
    if (rawNumbers.isNotEmpty() && productNumbers.isNotEmpty() && rawNumbers != productNumbers) {
        penalty += -30.0
    }

    return penalty.coerceAtLeast(MAX_RISK_PENALTY)
}
```

---

## 補正理由 (CorrectionReason)

```kotlin
enum class CorrectionReason {
    NO_INPUT,                  // 入力なし
    NO_CANDIDATES,             // 候補なし
    UNCONDITIONAL_VARIANT,     // Layer 1: LOCKED/手動CONFIRMED
    CONFIRMED_VARIANT,         // Layer 2: 自動CONFIRMED
    MASTER_MATCH,              // Layer 3: マスタ直接マッチ（補正成功）
    REJECT_SCORE_LOW,          // スコア不足（78点未満）
    REJECT_GAP_INSUFFICIENT,   // 2位との差不足（12点未満）
    REJECT_RISK_PENALTY        // リスクペナルティ適用
}
```

---

## OcrVariant 学習システム

### 信頼度レベル

```kotlin
enum class ConfidenceLevel {
    AUTO,       // 自動学習（初期状態）- 補正には使わない
    CONFIRMED,  // 確認済み - スコア検証後に使用可能
    LOCKED      // ロック - 無条件で使用
}
```

### 昇格条件（V3: 時間減衰廃止）

```kotlin
/**
 * AUTO → CONFIRMED 昇格条件
 *
 * 自動学習由来（source = AUTO）:
 * - hitCount >= 3
 * - avgFinalScore >= 0.90
 * - highScoreHits >= 2
 * - autoFailCount == 0
 *
 * 手動修正由来（source = USER）:
 * - manualCorrectCount >= 2（異なるバッチで2回以上）
 */
fun canPromoteToConfirmed(): Boolean {
    if (confidenceLevel != ConfidenceLevel.AUTO.name) return false
    if (autoFailCount > 0) return false

    return when (source) {
        VariantSource.USER.name -> manualCorrectCount >= 2
        else -> hitCount >= 3 && avgFinalScore >= 0.90 && highScoreHits >= 2
    }
}

/**
 * CONFIRMED → LOCKED 昇格条件
 */
fun canPromoteToLocked(): Boolean {
    if (confidenceLevel != ConfidenceLevel.CONFIRMED.name) return false
    return hitCount >= 10 && avgFinalScore >= 0.92 && autoFailCount == 0
}
```

### 降格・無効化ルール（失敗駆動）

```kotlin
/**
 * AUTO: autoFailCount >= 1 → 無効化
 * CONFIRMED: autoFailCount >= 1 → AUTO降格
 * LOCKED: 手動解除のみ
 */
fun shouldDemoteOrDisable(): DemotionAction {
    if (autoFailCount == 0) return DemotionAction.NONE

    return when (confidenceLevel) {
        ConfidenceLevel.AUTO.name -> DemotionAction.DISABLE
        ConfidenceLevel.CONFIRMED.name -> DemotionAction.DEMOTE_TO_AUTO
        ConfidenceLevel.LOCKED.name -> DemotionAction.NONE
        else -> DemotionAction.NONE
    }
}
```

### 学習登録

```kotlin
private fun shouldRegisterLearning(score: Double, gap: Double, normalizedText: String): Boolean {
    return score >= MIN_LEARNING_SCORE &&       // 88点以上
           gap >= MIN_LEARNING_GAP &&            // 差12点以上
           normalizedText.length >= MIN_LEARNING_LENGTH  // 3文字以上
}
```

---

## データベーススキーマ

### OcrVariant テーブル (V3)

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
    -- V3 追加カラム
    manualCorrectCount INTEGER NOT NULL DEFAULT 0,
    autoFailCount INTEGER NOT NULL DEFAULT 0,
    lastManualCommitBatchId TEXT,
    FOREIGN KEY (productId) REFERENCES product_master(id) ON DELETE CASCADE
);

CREATE INDEX index_ocr_variants_productId ON ocr_variants (productId);
CREATE INDEX index_ocr_variants_variantText ON ocr_variants (variantText);
CREATE INDEX index_ocr_variants_normalizedText ON ocr_variants (normalizedText);
CREATE INDEX index_ocr_variants_confidenceLevel ON ocr_variants (confidenceLevel);
```

### OcrScoreLog テーブル（V3新規）

```sql
CREATE TABLE ocr_score_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    rawOcrText TEXT NOT NULL,
    candidateProductId INTEGER,
    decision TEXT NOT NULL,          -- AUTO / NEED_CONFIRM / NO_MATCH
    totalScore REAL,
    textSimilarity REAL,
    prefixBonus REAL,
    dakutenBonus REAL,
    variantBonus REAL,
    historyBonus REAL,
    riskPenalty REAL,
    gapToSecond REAL,
    manualOverride INTEGER NOT NULL DEFAULT 0,
    manualCorrectedProductId INTEGER,
    commitBatchId TEXT,
    createdAt INTEGER NOT NULL
);

CREATE INDEX index_ocr_score_logs_createdAt ON ocr_score_logs (createdAt);
CREATE INDEX index_ocr_score_logs_decision ON ocr_score_logs (decision);
CREATE INDEX index_ocr_score_logs_commitBatchId ON ocr_score_logs (commitBatchId);
CREATE INDEX index_ocr_score_logs_rawOcrText ON ocr_score_logs (rawOcrText);
```

---

## 処理フロー

```
1. 入力チェック
   └─ 空白 → NO_INPUT

2. 前処理
   ├─ normalizeForCompare(ocrRawText)
   └─ parseProductName(ocrRawText)

3. Layer 1: 確定知識
   └─ findUnconditionalVariant() → UNCONDITIONAL_VARIANT

4. カテゴリフィルタ + ハード制約
   ├─ getByCategory()
   └─ passHardConstraints() → 候補なしなら NO_CANDIDATES

5. Layer 2: 条件付き知識
   ├─ findAutoConfirmedVariants()
   └─ スコア検証 → CONFIRMED_VARIANT

6. Layer 3: マスタ直接マッチ
   ├─ generateAndScoreCandidates()
   └─ 判定:
      ├─ score < 78 → REJECT_SCORE_LOW
      ├─ gap < 12 → REJECT_GAP_INSUFFICIENT
      ├─ penalty <= -30 → REJECT_RISK_PENALTY
      └─ 条件クリア → MASTER_MATCH

7. 学習登録（score >= 88, gap >= 12, length >= 3）
   └─ registerAutoLearning()

8. スコアログ保存
   └─ OcrScoreLog → ocr_score_logs テーブル
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
│   ├── OcrVariant.kt              # 学習パターン Entity (V3)
│   ├── OcrVariantDao.kt           # 学習パターン DAO (V3)
│   ├── OcrScoreLog.kt             # スコアログ Entity (V3新規)
│   ├── OcrScoreLogDao.kt          # スコアログ DAO (V3新規)
│   ├── CorrectionLog.kt           # 補正ログ Entity（旧）
│   ├── CorrectionLogDao.kt        # 補正ログ DAO（旧）
│   └── ReceiptDatabase.kt         # Room Database (v11)
```

---

## 更新履歴

- **2026-01-18**: V3実装完了、100点満点スコア、時間減衰廃止、3層補正構造
- **2026-01-17**: V3設計書作成
- **2026-01-16**: ProductNameCorrectorV3実装開始
- **2026-01-01**: ProductNameCorrectorV2実装（全角容量対応、容量重複バグ修正）
- **2025-12-29**: 初期実装（ProductNameCorrector）
