package com.example.receiptorc.data

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
        OcrScoreLog::class
    ],
    version = 7,
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

        fun getDatabase(context: Context): ReceiptDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ReceiptDatabase::class.java,
                    "receipt_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()  // 開発中はデータ破棄を許可
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
