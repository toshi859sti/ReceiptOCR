package com.example.greenframeocr.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ReceiptItem::class,
        MonthlyData::class,
        SheetData::class,
        ProductMaster::class,
        OcrVariant::class,
        YayoiAccount::class,
        RakurakuAccount::class,
        CorrectionLog::class,
        OcrScoreLog::class,
        RakurakuTekiyou::class,
        DepositMeisai::class,
        TekiyouMatchingRule::class,
        OcrFallbackLog::class,
        OcrExplicitJoin::class
    ],
    version = 16,
    exportSchema = false
)
abstract class ReceiptDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao
    abstract fun productMasterDao(): ProductMasterDao
    abstract fun ocrVariantDao(): OcrVariantDao
    abstract fun yayoiAccountDao(): YayoiAccountDao
    abstract fun rakurakuAccountDao(): RakurakuAccountDao
    abstract fun correctionLogDao(): CorrectionLogDao
    abstract fun ocrScoreLogDao(): OcrScoreLogDao
    abstract fun rakurakuTekiyouDao(): RakurakuTekiyouDao
    abstract fun depositMeisaiDao(): DepositMeisaiDao
    abstract fun tekiyouMatchingRuleDao(): TekiyouMatchingRuleDao
    abstract fun ocrFallbackLogDao(): OcrFallbackLogDao
    abstract fun ocrExplicitJoinDao(): OcrExplicitJoinDao

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

        fun getDatabase(context: Context): ReceiptDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ReceiptDatabase::class.java,
                    "receipt_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                    .fallbackToDestructiveMigration()  // 開発中はデータ破棄を許可
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
