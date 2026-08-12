package com.example.greenframeocr.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.greenframeocr.util.toCanonicalKey

@Database(
    entities = [
        ReceiptItem::class,
        MonthlyData::class,
        SheetData::class,
        ProductMaster::class,
        OcrVariant::class,
        YayoiAccount::class,
        RakurakuAccount::class,
        RakurakuTekiyou::class,
        DepositMeisai::class,
        TekiyouMatchingRule::class,
        OcrFallbackLog::class,
        GeneralReceipt::class,
        GeneralReceiptItem::class,
        InvoiceStore::class,
        GeneralItemMaster::class
    ],
    version = 30,
    exportSchema = false
)
abstract class ReceiptDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao
    abstract fun productMasterDao(): ProductMasterDao
    abstract fun ocrVariantDao(): OcrVariantDao
    abstract fun yayoiAccountDao(): YayoiAccountDao
    abstract fun rakurakuAccountDao(): RakurakuAccountDao
    abstract fun rakurakuTekiyouDao(): RakurakuTekiyouDao
    abstract fun depositMeisaiDao(): DepositMeisaiDao
    abstract fun tekiyouMatchingRuleDao(): TekiyouMatchingRuleDao
    abstract fun ocrFallbackLogDao(): OcrFallbackLogDao
    abstract fun generalReceiptDao(): GeneralReceiptDao
    abstract fun invoiceStoreDao(): InvoiceStoreDao
    abstract fun generalItemMasterDao(): GeneralItemMasterDao

    companion object {
        @Volatile
        private var INSTANCE: ReceiptDatabase? = null

        // マイグレーション: version 1 → 2
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. SheetDataテーブルを作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sheet_data (
                        issueYear INTEGER NOT NULL,
                        issueMonth INTEGER NOT NULL,
                        sheetNumber INTEGER NOT NULL,
                        totalFromInput INTEGER,
                        subtotalGeneral INTEGER,
                        subtotalGas INTEGER,
                        subtotalAgri INTEGER,
                        isTotalOcrTarget INTEGER NOT NULL DEFAULT 0,
                        isSubtotalGeneralOcrTarget INTEGER NOT NULL DEFAULT 0,
                        isSubtotalGasOcrTarget INTEGER NOT NULL DEFAULT 0,
                        isSubtotalAgriOcrTarget INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(issueYear, issueMonth, sheetNumber)
                    )
                """.trimIndent())

                // 2. MonthlyDataテーブルのカラム名を変更
                database.execSQL("""
                    CREATE TABLE monthly_data_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        issueYear INTEGER NOT NULL,
                        issueMonth INTEGER NOT NULL,
                        totalSheets INTEGER NOT NULL,
                        generalPurchaseTotal INTEGER NOT NULL,
                        agriculturalTotal INTEGER NOT NULL,
                        gasStationTotal INTEGER NOT NULL,
                        monthlyTotal INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO monthly_data_new
                    SELECT id, year, month, totalSheets, generalPurchaseTotal,
                           agriculturalTotal, gasStationTotal, monthlyTotal
                    FROM monthly_data
                """.trimIndent())
                database.execSQL("DROP TABLE monthly_data")
                database.execSQL("ALTER TABLE monthly_data_new RENAME TO monthly_data")

                // 3. ReceiptItemテーブルのカラムを変更
                // 既存データは破棄（date列を個別フィールドに分割するため）
                database.execSQL("DROP TABLE IF EXISTS receipt_items")
                database.execSQL("""
                    CREATE TABLE receipt_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        issueYear INTEGER NOT NULL,
                        issueMonth INTEGER NOT NULL,
                        sheetNumber INTEGER NOT NULL,
                        itemNumber INTEGER NOT NULL,
                        receiptYear INTEGER NOT NULL,
                        receiptMonth INTEGER NOT NULL,
                        receiptDay INTEGER NOT NULL,
                        productName TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        isOcrOverwriteTarget INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // マイグレーション: version 3 → 4（OcrVariant V2 - 段階的学習システム）
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // OcrVariantテーブルの再作成（新カラム追加）
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ocr_variants_new (
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
                    )
                """.trimIndent())

                // 旧データを移行（既存のoccurrenceCountをhitCountに）
                database.execSQL("""
                    INSERT INTO ocr_variants_new (
                        id, productId, variantText, normalizedText, confidenceLevel,
                        hitCount, highScoreHits, avgFinalScore, totalScore,
                        firstSeenAt, lastSeenAt, uniqueDays, lastSeenDate,
                        source, isDisabled, disabledReason
                    )
                    SELECT
                        id, productId, variantText, variantText, 'AUTO',
                        occurrenceCount, 0, 0.75, occurrenceCount * 0.75,
                        lastSeen, lastSeen, 1, 0,
                        'IMPORT', 0, NULL
                    FROM ocr_variants
                """.trimIndent())

                // 旧テーブル削除
                database.execSQL("DROP TABLE ocr_variants")

                // 新テーブルをリネーム
                database.execSQL("ALTER TABLE ocr_variants_new RENAME TO ocr_variants")

                // インデックス作成
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_variants_productId
                    ON ocr_variants (productId)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_variants_variantText
                    ON ocr_variants (variantText)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_variants_normalizedText
                    ON ocr_variants (normalizedText)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_variants_confidenceLevel
                    ON ocr_variants (confidenceLevel)
                """.trimIndent())

                // CorrectionLogテーブル作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS correction_logs (
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
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_correction_logs_timestamp
                    ON correction_logs (timestamp)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_correction_logs_decision
                    ON correction_logs (decision)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_correction_logs_sessionId
                    ON correction_logs (sessionId)
                """.trimIndent())
            }
        }

        // マイグレーション: version 4 → 5（V3: 低頻度利用向けOCR学習システム再設計）
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. OcrVariantに新カラム追加
                database.execSQL("""
                    ALTER TABLE ocr_variants
                    ADD COLUMN manualCorrectCount INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
                database.execSQL("""
                    ALTER TABLE ocr_variants
                    ADD COLUMN autoFailCount INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
                database.execSQL("""
                    ALTER TABLE ocr_variants
                    ADD COLUMN lastManualCommitBatchId TEXT
                """.trimIndent())

                // 2. OcrScoreLogテーブル作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ocr_score_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        rawOcrText TEXT NOT NULL,
                        candidateProductId INTEGER,
                        decision TEXT NOT NULL,
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
                    )
                """.trimIndent())

                // 3. OcrScoreLogのインデックス作成
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_score_logs_createdAt
                    ON ocr_score_logs (createdAt)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_score_logs_decision
                    ON ocr_score_logs (decision)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_score_logs_commitBatchId
                    ON ocr_score_logs (commitBatchId)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_score_logs_rawOcrText
                    ON ocr_score_logs (rawOcrText)
                """.trimIndent())

                // 4. 既存のUSERソースデータのmanualCorrectCountを1に設定
                database.execSQL("""
                    UPDATE ocr_variants
                    SET manualCorrectCount = 1
                    WHERE source = 'USER'
                """.trimIndent())
            }
        }

        // マイグレーション: version 5 → 6（AccountCode削除のため空マイグレーション）
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // AccountCodeテーブルは削除されたためno-op
                // 既存のaccount_codesテーブルがあれば削除
                database.execSQL("DROP TABLE IF EXISTS account_codes")
            }
        }

        // マイグレーション: version 6 → 7（YayoiAccount再構築: サーチキー、借貸、税区分追加）
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // YayoiAccountテーブルを再作成（カラム構成変更のため）
                database.execSQL("DROP TABLE IF EXISTS yayoi_accounts")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS yayoi_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        accountName TEXT NOT NULL,
                        searchKeyAlpha TEXT NOT NULL DEFAULT '',
                        accountCode TEXT NOT NULL,
                        debitCredit TEXT NOT NULL DEFAULT '',
                        taxCategory TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_yayoi_accounts_accountCode
                    ON yayoi_accounts (accountCode)
                """.trimIndent())
            }
        }

        // マイグレーション: version 7 → 8（勘定科目の区分A/B/C追加）
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // YayoiAccountテーブルを再作成（区分A/B/C、購買取引使用、親科目追加）
                database.execSQL("DROP TABLE IF EXISTS yayoi_accounts")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS yayoi_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        accountName TEXT NOT NULL,
                        searchKeyAlpha TEXT NOT NULL DEFAULT '',
                        accountCode TEXT NOT NULL,
                        debitCredit TEXT NOT NULL DEFAULT '',
                        categoryC TEXT NOT NULL DEFAULT '',
                        categoryB TEXT NOT NULL DEFAULT '',
                        categoryA TEXT NOT NULL DEFAULT '',
                        usedForPurchase INTEGER NOT NULL DEFAULT 0,
                        usedForDeposit INTEGER NOT NULL DEFAULT 1,
                        parentId INTEGER
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_yayoi_accounts_accountCode
                    ON yayoi_accounts (accountCode)
                """.trimIndent())

                // RakurakuAccountテーブルを再作成（区分A/B/C、購買取引使用、親科目追加）
                database.execSQL("DROP TABLE IF EXISTS rakuraku_accounts")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS rakuraku_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        accountCode TEXT NOT NULL,
                        accountName TEXT NOT NULL,
                        searchKeyAlpha TEXT NOT NULL DEFAULT '',
                        debitCredit TEXT NOT NULL DEFAULT '',
                        categoryC TEXT NOT NULL DEFAULT '',
                        categoryB TEXT NOT NULL DEFAULT '',
                        categoryA TEXT NOT NULL DEFAULT '',
                        usedForPurchase INTEGER NOT NULL DEFAULT 0,
                        usedForDeposit INTEGER NOT NULL DEFAULT 1,
                        parentId INTEGER
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_rakuraku_accounts_accountCode
                    ON rakuraku_accounts (accountCode)
                """.trimIndent())
            }
        }

        // マイグレーション: version 9 → 10（預金明細・マッチングルール追加）
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 預金明細テーブル
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS deposit_meisai (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        transactionDate TEXT NOT NULL,
                        transactionNumber TEXT NOT NULL,
                        tekiyou TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        memo TEXT NOT NULL DEFAULT '',
                        matchingRuleId INTEGER
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_deposit_meisai_transactionDate
                    ON deposit_meisai (transactionDate)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_deposit_meisai_tekiyou
                    ON deposit_meisai (tekiyou)
                """.trimIndent())

                // マッチングルールテーブル
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS tekiyou_matching_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pattern TEXT NOT NULL,
                        normalizedTekiyou TEXT NOT NULL,
                        isRegex INTEGER NOT NULL DEFAULT 0,
                        rakurakuTekiyouId INTEGER,
                        sampleText TEXT NOT NULL DEFAULT '',
                        matchCount INTEGER NOT NULL DEFAULT 0,
                        isDeposit INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY (rakurakuTekiyouId) REFERENCES rakuraku_tekiyou(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_tekiyou_matching_rules_pattern
                    ON tekiyou_matching_rules (pattern)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_tekiyou_matching_rules_rakurakuTekiyouId
                    ON tekiyou_matching_rules (rakurakuTekiyouId)
                """.trimIndent())
            }
        }

        // マイグレーション: version 14 → 15（商品マスタ 確定フラグ追加）
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    ALTER TABLE product_master
                    ADD COLUMN isCertified INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
            }
        }

        // マイグレーション: version 13 → 14（預金明細 個別オーバーライド列追加）
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    ALTER TABLE deposit_meisai
                    ADD COLUMN overrideTekiyouId INTEGER
                """.trimIndent())
            }
        }

        // マイグレーション: version 12 → 13（OCR明示的結合パターン追加）
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // OcrExplicitJoinテーブル作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ocr_explicit_joins (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        productId INTEGER NOT NULL,
                        normalizedPattern TEXT NOT NULL,
                        joinedText TEXT NOT NULL,
                        originalTexts TEXT NOT NULL,
                        confidenceLevel TEXT NOT NULL DEFAULT 'AUTO',
                        hitCount INTEGER NOT NULL DEFAULT 0,
                        manualConfirmCount INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'AUTO',
                        isDisabled INTEGER NOT NULL DEFAULT 0,
                        firstSeenAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        FOREIGN KEY (productId) REFERENCES product_master(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // インデックス作成
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_explicit_joins_productId
                    ON ocr_explicit_joins (productId)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_explicit_joins_normalizedPattern
                    ON ocr_explicit_joins (normalizedPattern)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_explicit_joins_confidenceLevel
                    ON ocr_explicit_joins (confidenceLevel)
                """.trimIndent())
            }
        }

        // マイグレーション: version 11 → 12（OCRフォールバックログ追加）
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // OcrFallbackLogテーブル作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ocr_fallback_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId TEXT NOT NULL,
                        rowIndex INTEGER NOT NULL,
                        rowY INTEGER NOT NULL,
                        rawText TEXT NOT NULL,
                        cleanedText TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        textHeight REAL NOT NULL,
                        boxCount INTEGER NOT NULL,
                        separatedTexts TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // インデックス作成
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_fallback_logs_createdAt
                    ON ocr_fallback_logs (createdAt)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_fallback_logs_textHeight
                    ON ocr_fallback_logs (textHeight)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_fallback_logs_sessionId
                    ON ocr_fallback_logs (sessionId)
                """.trimIndent())
            }
        }

        // マイグレーション: version 10 → 11（摘要辞書isEnabled追加、ProductMaster変更）
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. RakurakuTekiyouにisEnabledカラムを追加
                database.execSQL("""
                    ALTER TABLE rakuraku_tekiyou
                    ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1
                """.trimIndent())

                // 2. ProductMasterテーブルを再作成（yayoiAccountId, rakurakuAccountId削除、kaikakeTekiyouId追加）
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS product_master_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        canonicalName TEXT NOT NULL,
                        category TEXT NOT NULL,
                        frequencyCount INTEGER NOT NULL DEFAULT 0,
                        kaikakeTekiyouId INTEGER,
                        FOREIGN KEY (kaikakeTekiyouId) REFERENCES rakuraku_tekiyou(id) ON DELETE SET NULL
                    )
                """.trimIndent())

                // 旧データを移行（勘定科目マッピングは破棄）
                database.execSQL("""
                    INSERT INTO product_master_new (id, canonicalName, category, frequencyCount, kaikakeTekiyouId)
                    SELECT id, canonicalName, category, frequencyCount, NULL
                    FROM product_master
                """.trimIndent())

                // 旧テーブル削除
                database.execSQL("DROP TABLE product_master")

                // 新テーブルをリネーム
                database.execSQL("ALTER TABLE product_master_new RENAME TO product_master")

                // インデックス作成
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_product_master_kaikakeTekiyouId
                    ON product_master (kaikakeTekiyouId)
                """.trimIndent())
            }
        }

        // マイグレーション: version 8 → 9（RakurakuTekiyou追加）
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS rakuraku_tekiyou (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mainCategory TEXT NOT NULL,
                        subCategory TEXT NOT NULL,
                        tekiyouName TEXT NOT NULL,
                        searchKey TEXT NOT NULL,
                        kamoku TEXT NOT NULL,
                        taxRate TEXT NOT NULL DEFAULT '',
                        businessRatio INTEGER,
                        isShared INTEGER
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_rakuraku_tekiyou_mainCategory_subCategory
                    ON rakuraku_tekiyou (mainCategory, subCategory)
                """.trimIndent())
            }
        }

        // マイグレーション: version 2 → 3（辞書ベース補正システム）
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. YayoiAccountテーブル作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS yayoi_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        accountCode TEXT NOT NULL,
                        accountName TEXT NOT NULL,
                        category TEXT,
                        subcategory TEXT,
                        description TEXT
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_yayoi_accounts_accountCode
                    ON yayoi_accounts (accountCode)
                """.trimIndent())

                // 2. RakurakuAccountテーブル作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS rakuraku_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        accountCode TEXT NOT NULL,
                        accountName TEXT NOT NULL,
                        category TEXT,
                        subcategory TEXT,
                        description TEXT
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_rakuraku_accounts_accountCode
                    ON rakuraku_accounts (accountCode)
                """.trimIndent())

                // 3. ProductMasterテーブル作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS product_master (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        canonicalName TEXT NOT NULL,
                        category TEXT NOT NULL,
                        frequencyCount INTEGER NOT NULL DEFAULT 0,
                        yayoiAccountId INTEGER,
                        rakurakuAccountId INTEGER,
                        FOREIGN KEY (yayoiAccountId) REFERENCES yayoi_accounts(id) ON DELETE SET NULL,
                        FOREIGN KEY (rakurakuAccountId) REFERENCES rakuraku_accounts(id) ON DELETE SET NULL
                    )
                """.trimIndent())

                // 4. OcrVariantテーブル作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ocr_variants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        productId INTEGER NOT NULL,
                        variantText TEXT NOT NULL,
                        occurrenceCount INTEGER NOT NULL DEFAULT 0,
                        lastSeen INTEGER NOT NULL,
                        FOREIGN KEY (productId) REFERENCES product_master(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_variants_productId
                    ON ocr_variants (productId)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_variants_variantText
                    ON ocr_variants (variantText)
                """.trimIndent())
            }
        }

        // マイグレーション: version 16 → 17（product_master に canonicalKey 列追加 + UNIQUE 制約）
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. canonicalKey 列を追加
                database.execSQL(
                    "ALTER TABLE product_master ADD COLUMN canonicalKey TEXT NOT NULL DEFAULT ''"
                )

                // 2. 既存行の canonicalKey を Kotlin 側で計算して更新
                val cursor = database.query("SELECT id, canonicalName FROM product_master")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    val key = toCanonicalKey(name)
                    database.execSQL(
                        "UPDATE product_master SET canonicalKey = ? WHERE id = ?",
                        arrayOf(key, id)
                    )
                }
                cursor.close()

                // 3. 同じ (canonicalKey, category) の重複を解消（id の大きい方 = 新しい方を残す）
                database.execSQL("""
                    DELETE FROM product_master
                    WHERE id NOT IN (
                        SELECT MAX(id) FROM product_master GROUP BY canonicalKey, category
                    )
                """.trimIndent())

                // 4. UNIQUE 制約付き新テーブルを作成
                database.execSQL("""
                    CREATE TABLE product_master_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        canonicalName TEXT NOT NULL,
                        canonicalKey TEXT NOT NULL DEFAULT '',
                        category TEXT NOT NULL,
                        frequencyCount INTEGER NOT NULL DEFAULT 0,
                        kaikakeTekiyouId INTEGER,
                        isCertified INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (kaikakeTekiyouId) REFERENCES rakuraku_tekiyou(id) ON DELETE SET NULL
                    )
                """.trimIndent())

                // 5. データをコピー
                database.execSQL("""
                    INSERT INTO product_master_new
                        (id, canonicalName, canonicalKey, category, frequencyCount, kaikakeTekiyouId, isCertified)
                    SELECT id, canonicalName, canonicalKey, category, frequencyCount, kaikakeTekiyouId, isCertified
                    FROM product_master
                """.trimIndent())

                // 6. 旧テーブル削除・リネーム
                database.execSQL("DROP TABLE product_master")
                database.execSQL("ALTER TABLE product_master_new RENAME TO product_master")

                // 7. インデックス再作成
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_product_master_kaikakeTekiyouId
                    ON product_master (kaikakeTekiyouId)
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_product_master_canonicalKey_category
                    ON product_master (canonicalKey, category)
                """.trimIndent())
            }
        }

        // マイグレーション: version 15 → 16（deposit_meisai に UNIQUE 制約追加）
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // SQLite は ALTER TABLE ADD CONSTRAINT 非対応のためテーブル再作成
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS deposit_meisai_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        transactionDate TEXT NOT NULL,
                        transactionNumber TEXT NOT NULL,
                        tekiyou TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        memo TEXT NOT NULL DEFAULT '',
                        matchingRuleId INTEGER,
                        overrideTekiyouId INTEGER
                    )
                """.trimIndent())
                // 既存データをコピー（重複がある場合は最小IDの行のみ保持）
                database.execSQL("""
                    INSERT INTO deposit_meisai_new
                    SELECT * FROM deposit_meisai
                    WHERE id IN (
                        SELECT MIN(id) FROM deposit_meisai
                        GROUP BY transactionDate, transactionNumber
                    )
                """.trimIndent())
                database.execSQL("DROP TABLE deposit_meisai")
                database.execSQL("ALTER TABLE deposit_meisai_new RENAME TO deposit_meisai")
                // インデックス再作成
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_deposit_meisai_transactionDate
                    ON deposit_meisai (transactionDate)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_deposit_meisai_tekiyou
                    ON deposit_meisai (tekiyou)
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_deposit_meisai_transactionDate_transactionNumber
                    ON deposit_meisai (transactionDate, transactionNumber)
                """.trimIndent())
            }
        }

        // マイグレーション: version 29 → 30（一般レシート品目別マッチングの正規化グルーピング対応。
        // general_receipt_items に canonicalKey 追加、general_item_master でグループのデフォルト
        // 科目を管理。個別明細の yayoiAccountId は「グループのデフォルトからの個別上書き」に意味変更）
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE general_receipt_items ADD COLUMN canonicalKey TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS general_item_master (
                        canonicalKey TEXT NOT NULL PRIMARY KEY,
                        yayoiAccountId INTEGER
                    )
                    """.trimIndent()
                )

                // 既存行の canonicalKey をバックフィル
                val itemCursor = database.query("SELECT id, itemName FROM general_receipt_items")
                while (itemCursor.moveToNext()) {
                    val id = itemCursor.getLong(0)
                    val itemName = itemCursor.getString(1)
                    database.execSQL(
                        "UPDATE general_receipt_items SET canonicalKey = ? WHERE id = ?",
                        arrayOf(toCanonicalKey(itemName), id)
                    )
                }
                itemCursor.close()

                // 既存の個別科目設定から、canonicalKeyごとの最頻値をグループのデフォルト科目として登録
                // （新規追加される明細は、これまで手動で確定していた科目を自動で引き継げるようにする）
                val voteCursor = database.query(
                    """
                    SELECT canonicalKey, yayoiAccountId
                    FROM general_receipt_items
                    WHERE itemName != '' AND isExcluded = 0 AND yayoiAccountId IS NOT NULL
                    """.trimIndent()
                )
                val votes = mutableMapOf<String, MutableMap<Long, Int>>()
                while (voteCursor.moveToNext()) {
                    val key = voteCursor.getString(0)
                    val accountId = voteCursor.getLong(1)
                    val perKey = votes.getOrPut(key) { mutableMapOf() }
                    perKey[accountId] = (perKey[accountId] ?: 0) + 1
                }
                voteCursor.close()
                votes.forEach { (key, accountCounts) ->
                    val bestAccountId = accountCounts.maxByOrNull { it.value }?.key ?: return@forEach
                    database.execSQL(
                        "INSERT OR REPLACE INTO general_item_master (canonicalKey, yayoiAccountId) VALUES (?, ?)",
                        arrayOf(key, bestAccountId)
                    )
                }
            }
        }

        // マイグレーション: version 26 → 27（receipt_items に productMasterId 追加、
        // canonicalKey での過去データバックフィル。MIGRATION_16_17 と同じカーソル走査パターン）
        // マイグレーション: version 28 → 29（toCanonicalKey()の記号幅正規化漏れ修正に伴う再計算）
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // ダッシュ等の全角記号を正規化するよう toCanonicalKey() を修正したため、
                // 既存 product_master の canonicalKey を新ロジックで再計算する
                // （MIGRATION_16_17・MIGRATION_26_27と同じカーソル走査パターン）
                val cursor = database.query("SELECT id, canonicalName FROM product_master")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val canonicalName = cursor.getString(1)
                    database.execSQL(
                        "UPDATE product_master SET canonicalKey = ? WHERE id = ?",
                        arrayOf(toCanonicalKey(canonicalName), id)
                    )
                }
                cursor.close()
            }
        }

        // マイグレーション: version 27 → 28（Phase6: 使われなくなったML Kit学習ログ3テーブルを削除）
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS correction_logs")
                database.execSQL("DROP TABLE IF EXISTS ocr_score_logs")
                database.execSQL("DROP TABLE IF EXISTS ocr_explicit_joins")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE receipt_items ADD COLUMN productMasterId INTEGER"
                )

                // canonicalKey → id のマップを構築
                val keyToId = mutableMapOf<String, Long>()
                val pmCursor = database.query("SELECT id, canonicalKey FROM product_master WHERE canonicalKey != ''")
                while (pmCursor.moveToNext()) {
                    keyToId[pmCursor.getString(1)] = pmCursor.getLong(0)
                }
                pmCursor.close()

                // receipt_items を走査し、小計・合計行以外を toCanonicalKey() でマッチングして
                // productMasterId をバックフィル
                val riCursor = database.query("SELECT id, productName FROM receipt_items")
                while (riCursor.moveToNext()) {
                    val id = riCursor.getLong(0)
                    val name = riCursor.getString(1)
                    if (name.startsWith("[小計]") || name == "合計") continue
                    val matchedId = keyToId[toCanonicalKey(name)]
                    if (matchedId != null) {
                        database.execSQL(
                            "UPDATE receipt_items SET productMasterId = ? WHERE id = ?",
                            arrayOf(matchedId, id)
                        )
                    }
                }
                riCursor.close()
            }
        }

        // マイグレーション: version 25 → 26（Gemini OCR確信度カラム追加）
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE receipt_items ADD COLUMN ocrConfidence TEXT"
                )
            }
        }

        // マイグレーション: version 24 → 25（品目 除外フラグ追加）
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE general_receipt_items ADD COLUMN isExcluded INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // マイグレーション: version 23 → 24（登録番号キャッシュ追加）
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE general_receipts ADD COLUMN registrationNumber TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS invoice_stores (
                        registrationNumber TEXT NOT NULL PRIMARY KEY,
                        storeName TEXT NOT NULL,
                        address TEXT NOT NULL DEFAULT '',
                        cachedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // マイグレーション: version 22 → 23（general_receipt_items に yayoiAccountId 追加）
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE general_receipt_items ADD COLUMN yayoiAccountId INTEGER DEFAULT NULL"
                )
            }
        }

        // マイグレーション: version 21 → 22（弥生個別オーバーライド列追加）
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE deposit_meisai ADD COLUMN overrideYayoiAccountId INTEGER DEFAULT NULL"
                )
            }
        }

        // マイグレーション: version 20 → 21（連携会計ソフト対応：product_master/tekiyou_matching_rules に yayoiAccountId 追加）
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE product_master ADD COLUMN yayoiAccountId INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE tekiyou_matching_rules ADD COLUMN yayoiAccountId INTEGER DEFAULT NULL")
            }
        }

        // マイグレーション: version 19 → 20（yayoi_accounts 刷新：isEnabled追加・accountCode NULL許容・categoryC削除・defaultTaxCategory追加）
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // yayoi_accounts を完全再作成
                database.execSQL("""
                    CREATE TABLE yayoi_accounts_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        accountName TEXT NOT NULL,
                        searchKeyAlpha TEXT NOT NULL DEFAULT '',
                        accountCode TEXT,
                        debitCredit TEXT NOT NULL DEFAULT '',
                        categoryA TEXT NOT NULL DEFAULT '',
                        categoryB TEXT NOT NULL DEFAULT '',
                        defaultTaxCategory TEXT NOT NULL DEFAULT '対象外',
                        usedForPurchase INTEGER NOT NULL DEFAULT 0,
                        usedForDeposit INTEGER NOT NULL DEFAULT 0,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        parentId INTEGER
                    )
                """.trimIndent())

                database.execSQL("DROP TABLE yayoi_accounts")
                database.execSQL("ALTER TABLE yayoi_accounts_new RENAME TO yayoi_accounts")
                database.execSQL("CREATE INDEX index_yayoi_accounts_accountCode ON yayoi_accounts (accountCode)")

                // 農業用初期データを投入
                fun ins(
                    accountName: String, searchKeyAlpha: String, accountCode: String?,
                    debitCredit: String, categoryA: String, categoryB: String,
                    defaultTaxCategory: String,
                    usedForPurchase: Boolean, usedForDeposit: Boolean,
                    parentId: Long? = null
                ) {
                    database.execSQL("""
                        INSERT INTO yayoi_accounts
                        (accountName, searchKeyAlpha, accountCode, debitCredit,
                         categoryA, categoryB, defaultTaxCategory,
                         usedForPurchase, usedForDeposit, isEnabled, parentId)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """.trimIndent(), arrayOf(
                        accountName, searchKeyAlpha, accountCode,
                        debitCredit, categoryA, categoryB, defaultTaxCategory,
                        if (usedForPurchase) 1 else 0,
                        if (usedForDeposit) 1 else 0,
                        parentId
                    ))
                }

                // ========== 資産 ==========
                ins("現金",               "GENKIN",    "100", "借", "資産", "現金・預金",      "対象外",     false, true)
                ins("普通預金",           "FUTSUUYO",  "111", "借", "資産", "現金・預金",      "対象外",     false, true)
                ins("当座預金",           "TOUZAYO",   "110", "借", "資産", "現金・預金",      "対象外",     false, true)
                ins("定期預金",           "TEIKIYO",   "113", "借", "資産", "現金・預金",      "対象外",     false, true)
                ins("売掛金",             "URIKAKE",   "130", "借", "資産", "売上債権",        "対象外",     false, true)
                ins("農産物等",           "",          null,  "借", "資産", "農業棚卸資産",    "対象外",     false, false)
                ins("未収穫農産物等",     "",          null,  "借", "資産", "農業棚卸資産",    "対象外",     false, false)
                ins("肥料その他の貯蔵品", "",          null,  "借", "資産", "農業棚卸資産",    "対象外",     false, false)
                ins("前払金",             "MAEBARAI",  "160", "借", "資産", "その他流動資産",  "対象外",     false, false)
                ins("未収金",             "MISHUUKI",  "164", "借", "資産", "その他流動資産",  "対象外",     false, false)
                ins("建物・構築物",       "TATEMONO",  "200", "借", "資産", "固定資産",        "課対仕入10", false, false)
                ins("農機具等",           "KIKAISO",   "203", "借", "資産", "固定資産",        "課対仕入10", false, false)
                ins("果樹・牛馬等",       "",          null,  "借", "資産", "固定資産",        "対象外",     false, false)
                ins("土地",               "TOCHI",     "210", "借", "資産", "固定資産",        "対象外",     false, false)
                ins("事業主貸",           "JIGYOU",    "291", "借", "資産", "事業主貸",        "対象外",     false, false)

                // ========== 負債 ==========
                ins("買掛金",   "KAIKAKE",  "301", "貸", "負債", "仕入債務",   "対象外", false, true)
                ins("借入金",   "KARIIREK", "320", "貸", "負債", "その他負債", "対象外", false, true)
                ins("未払金",   "MIHARAIK", "322", "貸", "負債", "その他負債", "対象外", false, true)
                ins("前受金",   "MAEUKEKI", "324", "貸", "負債", "その他負債", "対象外", false, false)
                ins("預り金",   "AZUKARIK", "325", "貸", "負債", "その他負債", "対象外", false, false)
                ins("事業主借", "JIGYOU",   "390", "貸", "負債", "事業主借",   "対象外", false, false)

                // ========== 資本 ==========
                ins("元入金",     "MOTOIRE", "400", "貸", "資本", "資本", "対象外", false, false)
                ins("専従者給与", "SENJUU",  "810", "借", "資本", "資本", "対象外", false, false)

                // ========== 収入 ==========
                ins("売上高",   "URIAGE",   "500", "貸", "収入", "農産物売上", "課税売上", false, true)
                ins("家事消費等", "KAJISHOU","583", "貸", "収入", "農産物売上", "課税売上", false, false)
                ins("雑収入",   "ZATSUSHU", "590", "貸", "収入", "その他収入", "課税売上", false, false)

                // ========== 経費：農業生産費 ==========
                ins("租税公課",     "SOZEI",    "700", "借", "経費", "農業生産費", "対象外",     true, false)
                ins("種苗費",       "",         null,  "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("素畜費",       "",         null,  "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("肥料費",       "",         null,  "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("飼料費",       "",         null,  "借", "経費", "農業生産費", "課対仕入8",  true, false)
                ins("農具費",       "",         null,  "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("農薬衛生費",   "",         null,  "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("諸材料費",     "",         null,  "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("修繕費",       "SHUUZEN",  "709", "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("動力光熱費",   "",         null,  "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("作業用衣料費", "",         null,  "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("農業共済掛金", "",         null,  "借", "経費", "農業生産費", "非課税",     true, false)
                ins("減価償却費",   "GENKASHO", "712", "借", "経費", "農業生産費", "対象外",     true, false)
                ins("荷造運賃手数料","NIZUKURI","701", "借", "経費", "農業生産費", "課対仕入10", true, false)
                ins("雇人費",       "KYUURYOU", "715", "借", "経費", "農業生産費", "対象外",     true, false)

                // ========== 経費：一般経費 ==========
                ins("地代・賃借料", "CHIDAI",   "723", "借", "経費", "一般経費", "課対仕入10", true,  false)
                ins("利子割引料",   "RISHIWAR", "722", "借", "経費", "一般経費", "非課税",     true,  false)
                ins("外注工賃",     "GAICHUU",  "720", "借", "経費", "一般経費", "課対仕入10", true,  false)
                ins("損害保険料",   "SONGAIHO", "708", "借", "経費", "一般経費", "非課税",     true,  false)
                ins("車両費",       "SHARYOU",  "726", "借", "経費", "一般経費", "課対仕入10", true,  false)
                ins("消耗品費",     "SHOUMOU",  "710", "借", "経費", "一般経費", "課対仕入10", true,  false)
                ins("支払手数料",   "SHIHARAI", "725", "借", "経費", "一般経費", "課対仕入10", true,  false)
                ins("水道光熱費",   "SUIDOU",   "703", "借", "経費", "一般経費", "課対仕入10", false, false)
                ins("通信費",       "TSUUSHIN", "705", "借", "経費", "一般経費", "課対仕入10", false, false)
                ins("雑費",         "ZAPPI",    "760", "借", "経費", "一般経費", "課対仕入10", true,  false)

                // ========== 引当金等 ==========
                ins("貸倒引当金戻入", "KASHIDAO", "800", "貸", "引当金等", "引当金等", "対象外", false, false)
                ins("貸倒引当金繰入", "KASHIDAO", "811", "借", "引当金等", "引当金等", "対象外", false, false)

                // ========== 補助科目（SELECTでparentIdを解決） ==========
                database.execSQL("""
                    INSERT INTO yayoi_accounts
                    (accountName, searchKeyAlpha, accountCode, debitCredit,
                     categoryA, categoryB, defaultTaxCategory,
                     usedForPurchase, usedForDeposit, isEnabled, parentId)
                    SELECT 'JA島原雲仙', '', NULL, '借',
                        '資産', '現金・預金', '対象外', 0, 1, 1, id
                    FROM yayoi_accounts WHERE accountName = '普通預金' AND parentId IS NULL LIMIT 1
                """.trimIndent())

                database.execSQL("""
                    INSERT INTO yayoi_accounts
                    (accountName, searchKeyAlpha, accountCode, debitCredit,
                     categoryA, categoryB, defaultTaxCategory,
                     usedForPurchase, usedForDeposit, isEnabled, parentId)
                    SELECT '直売所', '', NULL, '借',
                        '資産', '売上債権', '対象外', 0, 1, 1, id
                    FROM yayoi_accounts WHERE accountName = '売掛金' AND parentId IS NULL LIMIT 1
                """.trimIndent())

                database.execSQL("""
                    INSERT INTO yayoi_accounts
                    (accountName, searchKeyAlpha, accountCode, debitCredit,
                     categoryA, categoryB, defaultTaxCategory,
                     usedForPurchase, usedForDeposit, isEnabled, parentId)
                    SELECT '直売所', '', NULL, '貸',
                        '収入', '農産物売上', '課税売上', 0, 1, 1, id
                    FROM yayoi_accounts WHERE accountName = '売上高' AND parentId IS NULL LIMIT 1
                """.trimIndent())

                database.execSQL("""
                    INSERT INTO yayoi_accounts
                    (accountName, searchKeyAlpha, accountCode, debitCredit,
                     categoryA, categoryB, defaultTaxCategory,
                     usedForPurchase, usedForDeposit, isEnabled, parentId)
                    SELECT '農協', '', NULL, '貸',
                        '収入', '農産物売上', '課税売上', 0, 1, 1, id
                    FROM yayoi_accounts WHERE accountName = '売上高' AND parentId IS NULL LIMIT 1
                """.trimIndent())
            }
        }

        // マイグレーション: version 18 → 19（一般購買レシートテーブル追加）
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS general_receipts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        storeName TEXT NOT NULL DEFAULT '',
                        total INTEGER NOT NULL DEFAULT 0,
                        rawOcrText TEXT NOT NULL DEFAULT '',
                        geminiUsed INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS general_receipt_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        receiptId INTEGER NOT NULL,
                        itemName TEXT NOT NULL DEFAULT '',
                        price INTEGER NOT NULL DEFAULT 0,
                        category TEXT NOT NULL DEFAULT '未分類',
                        tekiyouId INTEGER,
                        FOREIGN KEY (receiptId) REFERENCES general_receipts(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_general_receipt_items_receiptId ON general_receipt_items(receiptId)"
                )
            }
        }

        // マイグレーション: version 17 → 18（OcrVariant enum リネーム）
        // confidenceLevel: AUTO → TENTATIVE
        // source: AUTO → SYSTEM, USER → CAPTURE, IMPORT → SYSTEM
        // source: PRESET 追加（コード側のみ、既存データなし）
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE ocr_variants SET confidenceLevel = 'TENTATIVE' WHERE confidenceLevel = 'AUTO'")
                database.execSQL("UPDATE ocr_variants SET source = 'SYSTEM'  WHERE source = 'AUTO'")
                database.execSQL("UPDATE ocr_variants SET source = 'CAPTURE' WHERE source = 'USER'")
                database.execSQL("UPDATE ocr_variants SET source = 'SYSTEM'  WHERE source = 'IMPORT'")
            }
        }

        fun getDatabase(context: Context): ReceiptDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ReceiptDatabase::class.java,
                    "receipt_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
