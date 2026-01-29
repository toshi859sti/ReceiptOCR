# OCR学習システム再設計 - 実装方針書

作成日: 2026-01-17
ステータス: 実装待ち

---

## 1. 背景と問題点

### 1.1 現状の利用実態

- 利用頻度: **月1回〜年1回**（低頻度）
- 商品数: 約100点（閉じた世界）
- ユーザー: 業務用途（誤爆＝事故）

### 1.2 現在実装の致命的問題

| 問題 | 現状の実装 | 影響 |
|------|-----------|------|
| ユニーク日数条件 | `uniqueDays >= 3` | 年1回利用だと永久に昇格しない |
| 時間ベース減衰 | 30日で0.37、90日で無効化 | 半年後には学習が全消失 |
| AUTO登録閾値 | `score >= 0.80` | ノイズが混入しやすい |
| 手動修正の扱い | AUTOと同列 | 人間の判断が軽視されている |

### 1.3 根本原因

現在の設計は「毎日使う」前提の**オンライン学習型**。
実際の利用実態（年数回）と**完全にミスマッチ**している。

---

## 2. 新設計の核心思想

```
「頻度で学ぶAI」 → 「事実を確定させる辞書」
```

### 2.1 設計原則

1. **誤変換は絶対にしない** - 補正は確信が持てたときだけ
2. **学習は「回数」ではなく「確定度」で判断** - 日数条件は完全廃止
3. **失敗した知識は即座に降格/無効化** - 時間では忘れない
4. **手動修正は最強の教師データだが絶対視しない** - 人間も間違える
5. **同一バッチ内の重複は1回としてカウント** - 「機会」で測る

### 2.2 補正の3層構造

```
Layer 1: 確定知識（無条件適用）
  └─ LOCKED / 手動CONFIRMED

Layer 2: 条件付き知識（スコア検証後に適用）
  └─ CONFIRMED（自動昇格）

Layer 3: 観測データ（補正には使わない・学習素材のみ）
  └─ AUTO
```

---

## 3. データベース設計

### 3.1 OcrVariant テーブル変更

#### 廃止するカラム（削除せず無視）
```
- uniqueDays
- lastSeenDate
- 時間ベースの減衰計算に使う全てのロジック
```

#### 追加するカラム
```kotlin
data class OcrVariant(
    // 既存
    val id: Long = 0,
    val productId: Long,
    val variantText: String,
    val normalizedText: String,
    val confidenceLevel: String,  // AUTO / CONFIRMED / LOCKED
    val hitCount: Int = 0,
    val highScoreHits: Int = 0,
    val avgFinalScore: Double = 0.0,
    val totalScore: Double = 0.0,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val isDisabled: Boolean = false,
    val disabledReason: String? = null,

    // 新規追加
    val source: String = "AUTO",              // AUTO / USER / IMPORT
    val manualCorrectCount: Int = 0,          // 手動修正回数
    val autoFailCount: Int = 0,               // AUTO誤爆回数
    val lastManualCommitBatchId: String? = null  // 重複カウント防止用
)
```

### 3.2 OcrScoreLog テーブル（新規）

```kotlin
data class OcrScoreLog(
    val id: Long = 0,
    val rawOcrText: String,
    val candidateProductId: Long?,
    val decision: String,           // AUTO / NEED_CONFIRM / NO_MATCH
    val totalScore: Double?,
    val textSimilarity: Double?,
    val prefixBonus: Double?,
    val dakutenBonus: Double?,
    val variantBonus: Double?,
    val historyBonus: Double?,
    val riskPenalty: Double?,
    val gapToSecond: Double?,
    val manualOverride: Boolean = false,  // 後で手動修正されたか
    val commitBatchId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
```

### 3.3 ReceiptItem 変更

```kotlin
data class ReceiptItem(
    // 既存フィールド...

    // 追加
    val originalOcrName: String? = null,  // OCR取得時の原本（編集不可）
    val productMasterId: Long? = null,    // 商品マスタID（確定時）
    val commitBatchId: String? = null     // コミットバッチID
)
```

### 3.4 マイグレーション戦略

1. 新カラム追加（`source`, `manualCorrectCount`, `autoFailCount`, `lastManualCommitBatchId`）
2. 既存データの`source`を推定設定
   - CSVインポート由来 → `IMPORT`
   - それ以外 → `AUTO`
3. 旧カラム（`uniqueDays`, `lastSeenDate`）は削除せず無視
4. 新テーブル（`ocr_score_log`）作成

---

## 4. 昇格・降格条件

### 4.1 AUTO → CONFIRMED 昇格

#### 自動学習由来（source = AUTO）
```kotlin
hitCount >= 3 AND
avgFinalScore >= 0.90 AND
highScoreHits >= 2 AND
autoFailCount == 0
```

#### 手動修正由来（source = USER）
```kotlin
manualCorrectCount >= 2
// ※ 同一バッチ内の重複は+1のみ
// ※ 2回以上の「異なるバッチ」での確定が必要
```

### 4.2 CONFIRMED → LOCKED 昇格

```kotlin
hitCount >= 10 AND
avgFinalScore >= 0.92 AND
autoFailCount == 0
```

### 4.3 降格・無効化ルール（失敗駆動）

```kotlin
// sourceに関係なく適用（手動CONFIRMEDも例外なし）
when (confidenceLevel) {
    "AUTO" -> {
        if (autoFailCount >= 1) {
            isDisabled = true
            disabledReason = "AUTO_FAIL"
        }
    }
    "CONFIRMED" -> {
        if (autoFailCount >= 1) {
            confidenceLevel = "AUTO"  // 降格
            // isDisabledはfalseのまま（再学習の機会を与える）
        }
    }
    "LOCKED" -> {
        // 手動解除のみ（UIから管理者が操作）
    }
}
```

---

## 5. スコア計算式

### 5.1 スコア構成（100点満点）

```kotlin
totalScore =
    textSimilarity        // 文字類似度（最大60）
  + prefixBonus           // 先頭欠落一致（最大10）
  + dakutenBonus          // 濁点誤認識一致（最大5）
  + variantBonus          // 既存知識ボーナス（最大15）
  + manualHistoryBonus    // 手動修正履歴（最大10）
  - riskPenalty           // 危険減点（最大-30）
```

### 5.2 各スコアの計算

#### textSimilarity（最大60）
```kotlin
val normalizedSimilarity = 1.0 - (levenshteinDistance / maxLength)
val textSimilarity = (normalizedSimilarity * 60).coerceIn(0.0, 60.0)
```

#### prefixBonus（最大10）
```kotlin
val prefixBonus = when {
    isPrefixDroppedMatch(1) -> 10  // 先頭1文字欠落
    isPrefixDroppedMatch(2) -> 5   // 先頭2文字欠落
    else -> 0
}
```

#### dakutenBonus（最大5）
```kotlin
val dakutenBonus = if (isDakutenVariantMatch) 5 else 0
```

#### variantBonus（最大15）
```kotlin
val variantBonus = when (existingVariant?.confidenceLevel) {
    "LOCKED" -> 15
    "CONFIRMED" -> 10
    "AUTO" -> 0  // AUTOは補正に使わないが、存在確認はする
    else -> 0
}
```

#### manualHistoryBonus（最大10）
```kotlin
val manualHistoryBonus = if (existingVariant?.manualCorrectCount ?: 0 > 0) 10 else 0
```

#### riskPenalty（最大-30）
```kotlin
val riskPenalty = listOf(
    if (isCapacityMismatch) -30 else 0,      // 容量違い
    if (isNumberMismatch) -30 else 0,         // 数字違い
    if (isCategoryMismatch) -20 else 0,       // カテゴリ不一致
    if (hasMultipleSimilarProducts) -15 else 0 // 類似商品複数
).sum().coerceAtLeast(-30)
```

### 5.3 判定閾値

```kotlin
const val MIN_ACCEPT = 78           // 最低採用スコア
const val GAP = 12                  // 2位との最低差分
const val MIN_ACCEPT_CONFIRMED = 75 // CONFIRMED知識の最低採用スコア
const val GAP_CONFIRMED = 10        // CONFIRMED知識の最低差分
```

### 5.4 学習登録条件（引き締め）

```kotlin
// AUTOとして学習登録する条件
val shouldRegisterLearning =
    score >= 0.88 AND
    (bestScore - secondScore) >= 0.12 AND
    normalize(ocrText).length >= 3
```

---

## 6. 補正判定フロー

```kotlin
fun correctOcr(rawText: String): CorrectionResult {
    val norm = normalize(rawText)

    // 1️⃣ LOCKED / 手動CONFIRMED（無条件適用）
    findLockedOrManualConfirmedVariant(norm)?.let { variant ->
        logScore(rawText, variant, Decision.AUTO)
        return AutoCorrected(variant.productId)
    }

    // 2️⃣ CONFIRMED（スコア検証）
    val confirmedCandidates = findConfirmedVariants(norm)
        .map { scoreCandidate(norm, it) }
        .filter { it.totalScore >= MIN_ACCEPT_CONFIRMED }
        .sortedByDescending { it.totalScore }

    if (confirmedCandidates.hasSingleWinner(GAP_CONFIRMED)) {
        logScore(rawText, confirmedCandidates, Decision.AUTO)
        return AutoCorrected(confirmedCandidates.best.productId)
    }

    // 3️⃣ マスタ直接マッチ
    val masterCandidates = matchProductMaster(norm)
        .map { scoreCandidate(norm, it) }
        .sortedByDescending { it.totalScore }

    if (masterCandidates.isNotEmpty() &&
        masterCandidates.best.totalScore >= MIN_ACCEPT &&
        masterCandidates.hasSingleWinner(GAP)) {

        // 学習登録条件を満たす場合のみAUTO登録
        if (shouldRegisterLearning(masterCandidates)) {
            registerAutoVariant(rawText, masterCandidates.best)
        }

        logScore(rawText, masterCandidates, Decision.AUTO)
        return AutoCorrected(masterCandidates.best.productId)
    }

    // 4️⃣ 判断不能
    logScore(rawText, masterCandidates, Decision.NEED_CONFIRM)
    return NeedConfirm(masterCandidates.take(3))
}
```

---

## 7. 手動修正の安全な学習登録

### 7.1 基本原則

- 編集中の変更は学習しない
- 月全体の「決定」ボタンでコミット時に差分検出
- 原本OCR結果と最終確定値を比較
- 同一バッチ内の重複は+1のみ
- 商品マスタ一致はID確定のみ（文字列一致NG）

### 7.2 コミット時の学習登録フロー

```kotlin
suspend fun registerManualCorrectionsOnCommit(
    items: List<ReceiptItem>,
    commitBatchId: String,
    variantDao: OcrVariantDao
) {
    for (item in items) {
        // 条件チェック
        if (!isValidForLearning(item.originalOcrName)) continue
        if (item.productMasterId == null) continue
        if (item.originalOcrName == item.productName) continue  // 変更なし

        variantDao.registerManualCorrection(
            ocrText = item.originalOcrName!!,
            correctProductId = item.productMasterId!!,
            commitBatchId = commitBatchId
        )
    }
}

fun isValidForLearning(originalOcrName: String?): Boolean {
    if (originalOcrName == null) return false
    val normalized = normalize(originalOcrName)
    return normalized.length >= 3  // 3文字以上のみ
}
```

### 7.3 手動修正登録ロジック

```kotlin
suspend fun registerManualCorrection(
    ocrText: String,
    correctProductId: Long,
    commitBatchId: String
) {
    val normalizedText = normalize(ocrText)
    val existing = findByNormalizedTextAndProduct(normalizedText, correctProductId)

    if (existing != null) {
        // 同一バッチで既にカウント済みならスキップ
        if (existing.lastManualCommitBatchId == commitBatchId) {
            return
        }

        val newManualCount = existing.manualCorrectCount + 1
        val newConfidence = if (newManualCount >= 2 && existing.confidenceLevel == "AUTO") {
            "CONFIRMED"
        } else {
            existing.confidenceLevel
        }

        update(existing.copy(
            manualCorrectCount = newManualCount,
            confidenceLevel = newConfidence,
            source = "USER",
            lastManualCommitBatchId = commitBatchId,
            lastSeenAt = System.currentTimeMillis()
        ))
    } else {
        // 新規登録（まずAUTOとして、source=USER）
        insert(OcrVariant(
            productId = correctProductId,
            variantText = ocrText,
            normalizedText = normalizedText,
            confidenceLevel = "AUTO",
            source = "USER",
            manualCorrectCount = 1,
            hitCount = 1,
            lastManualCommitBatchId = commitBatchId,
            firstSeenAt = System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis()
        ))
    }
}
```

### 7.4 AUTO誤爆時の処理

```kotlin
suspend fun onAutoFailure(variantId: Long) {
    val variant = getById(variantId) ?: return
    val newFailCount = variant.autoFailCount + 1

    when (variant.confidenceLevel) {
        "AUTO" -> {
            update(variant.copy(
                autoFailCount = newFailCount,
                isDisabled = true,
                disabledReason = "AUTO_FAIL_COUNT_${newFailCount}"
            ))
        }
        "CONFIRMED" -> {
            // CONFIRMEDでも1回の失敗でAUTO降格
            update(variant.copy(
                autoFailCount = newFailCount,
                confidenceLevel = "AUTO"
            ))
        }
        "LOCKED" -> {
            // LOCKEDは失敗カウントのみ記録（降格しない）
            update(variant.copy(
                autoFailCount = newFailCount
            ))
        }
    }
}
```

---

## 8. 先頭欠落・濁点誤認識検出

### 8.1 正規化（比較専用）

```kotlin
fun normalizeForCompare(text: String): String {
    return text
        .replace(Regex("[\\s　]"), "")           // 空白除去
        .replace(Regex("[^一-龯ぁ-んァ-ンa-zA-Z0-9]"), "")  // 記号除去
        // 全角半角統一
        .map { normalizeChar(it) }
        .joinToString("")
}

// 濁点分離版（比較用のみ）
fun removeDakuten(text: String): String {
    return text.map { dakutenMap[it] ?: it }.joinToString("")
}

private val dakutenMap = mapOf(
    'ガ' to 'カ', 'ギ' to 'キ', 'グ' to 'ク', 'ゲ' to 'ケ', 'ゴ' to 'コ',
    'ザ' to 'サ', 'ジ' to 'シ', 'ズ' to 'ス', 'ゼ' to 'セ', 'ゾ' to 'ソ',
    'ダ' to 'タ', 'ヂ' to 'チ', 'ヅ' to 'ツ', 'デ' to 'テ', 'ド' to 'ト',
    'バ' to 'ハ', 'ビ' to 'ヒ', 'ブ' to 'フ', 'ベ' to 'ヘ', 'ボ' to 'ホ',
    'パ' to 'ハ', 'ピ' to 'ヒ', 'プ' to 'フ', 'ペ' to 'ヘ', 'ポ' to 'ホ',
    // ひらがなも同様...
)
```

### 8.2 先頭欠落検出

```kotlin
fun isPrefixDroppedMatch(ocrNorm: String, masterNorm: String, dropCount: Int): Boolean {
    if (dropCount !in 1..2) return false
    if (masterNorm.length <= ocrNorm.length) return false
    if (masterNorm.length - ocrNorm.length != dropCount) return false

    val masterWithoutPrefix = masterNorm.drop(dropCount)
    return ocrNorm == masterWithoutPrefix || ocrNorm.startsWith(masterWithoutPrefix.take(3))
}
```

### 8.3 濁点誤認識検出

```kotlin
fun isDakutenVariantMatch(ocrNorm: String, masterNorm: String): Boolean {
    if (ocrNorm == masterNorm) return false  // 完全一致は別扱い
    return removeDakuten(ocrNorm) == removeDakuten(masterNorm)
}
```

---

## 9. スコアログ

### 9.1 記録タイミング

- AUTO補正時: 必ず記録
- NEED_CONFIRM時: 必ず記録
- NO_MATCH時: 必ず記録
- 手動修正時: `manualOverride = true` に更新

### 9.2 ログ保存処理

```kotlin
suspend fun logScore(
    rawOcrText: String,
    candidates: List<ScoredCandidate>,
    decision: Decision,
    commitBatchId: String? = null
) {
    val best = candidates.firstOrNull()
    val second = candidates.getOrNull(1)

    insert(OcrScoreLog(
        rawOcrText = rawOcrText,
        candidateProductId = best?.productId,
        decision = decision.name,
        totalScore = best?.breakdown?.totalScore,
        textSimilarity = best?.breakdown?.textSimilarity,
        prefixBonus = best?.breakdown?.prefixBonus,
        dakutenBonus = best?.breakdown?.dakutenBonus,
        variantBonus = best?.breakdown?.variantBonus,
        historyBonus = best?.breakdown?.historyBonus,
        riskPenalty = best?.breakdown?.riskPenalty,
        gapToSecond = if (best != null && second != null) {
            best.breakdown.totalScore - second.breakdown.totalScore
        } else null,
        manualOverride = false,
        commitBatchId = commitBatchId,
        createdAt = System.currentTimeMillis()
    ))
}
```

---

## 10. 変更対象ファイル一覧

| ファイル | 変更内容 |
|---------|---------|
| `OcrVariant.kt` | カラム追加、昇格条件関数の全面書き換え |
| `OcrVariantDao.kt` | クエリ修正、新規クエリ追加 |
| `ProductNameCorrectorV2.kt` | スコア式・判定ロジック全面改修（またはV3新規作成） |
| `ReceiptItem.kt` | `originalOcrName`, `productMasterId`, `commitBatchId` 追加 |
| `ReceiptDao.kt` | 関連クエリ修正 |
| `ReceiptDatabase.kt` | マイグレーション追加、新テーブル追加 |
| `ReceiptInputScreen.kt` | コミット時の学習登録呼び出し追加 |
| `OcrLearningStatusScreen.kt` | 表示内容変更（日数→回数ベース） |
| `OCR_LEARNING_SYSTEM.md` | ドキュメント全面書き換え |
| **新規** `OcrScoreLog.kt` | スコアログエンティティ |
| **新規** `OcrScoreLogDao.kt` | スコアログDAO |

---

## 11. 設計意図コメント（コードに必ず記載）

```kotlin
/**
 * OCR学習システム設計思想
 *
 * ⚠ この学習は「頻度最適化」ではない
 * ⚠ 人間の最終確定のみを真実とする
 * ⚠ 時間減衰を入れたらこの設計は壊れる
 * ⚠ 手動修正も絶対視しない（autoFailCountは全レベルで有効）
 * ⚠ 同一バッチ内の重複は1回としてカウント
 *
 * 利用想定: 月1回〜年1回の低頻度利用
 *
 * 設計原則:
 * 1. 誤変換は絶対にしない（補正は確信時のみ）
 * 2. 学習は「回数」ではなく「確定度」で判断
 * 3. 失敗した知識は即座に降格/無効化
 * 4. 同一バッチ内の重複は1回としてカウント
 * 5. 原本OCRは必ず保持し、3文字以上のみ学習対象
 * 6. 商品マスタ一致はID確定のみ（文字列一致禁止）
 *
 * 変更履歴:
 * - 2026-01-17: 低頻度利用向けに全面再設計
 */
```

---

## 12. UI/ドキュメント修正

### 12.1 削除する説明

- 「毎日使うと育つ」
- 「日数条件」
- 「減衰メカニズム」
- 「30日で忘却」

### 12.2 追加する説明

```
このアプリは、同じ帳票・同じ商品を再度処理したときに
自動的に精度が安定する仕組みです。

毎日使う必要はありません。

- 同じ誤認識パターンが複数回確認されると、自動的に学習されます
- 手動で修正した内容は、より信頼性の高い知識として記録されます
- 間違った補正が発生した場合、その知識は自動的に無効化されます
```

---

## 13. 実装順序（推奨）

1. **DB変更**: `OcrVariant`カラム追加、`OcrScoreLog`テーブル作成、マイグレーション
2. **エンティティ**: `OcrScoreLog.kt`、`OcrScoreLogDao.kt` 作成
3. **DAO修正**: `OcrVariantDao.kt` のクエリ修正
4. **コア処理**: スコア計算・判定ロジック（`ProductNameCorrectorV3.kt` 新規作成推奨）
5. **学習登録**: 手動修正のコミット時学習登録
6. **UI更新**: `OcrLearningStatusScreen.kt` 表示内容変更
7. **ドキュメント**: `OCR_LEARNING_SYSTEM.md` 全面書き換え
8. **テスト**: 実データでスコアログ確認、閾値調整

---

## 14. 将来拡張（今回は実装しない）

- 地域・農協別スコープ（`scope_type: GLOBAL / REGION / JA`）
- 管理者向けCONFIRMED/LOCKED手動編集UI
- スコアログ分析ダッシュボード
- バッチ間の学習進捗レポート
