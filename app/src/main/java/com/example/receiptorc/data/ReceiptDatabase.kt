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
        RakurakuAccount::class
    ],
    version = 3,
    exportSchema = false
)
abstract class ReceiptDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao
    abstract fun productMasterDao(): ProductMasterDao
    abstract fun ocrVariantDao(): OcrVariantDao
    abstract fun yayoiAccountDao(): YayoiAccountDao
    abstract fun rakurakuAccountDao(): RakurakuAccountDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()  // 開発中はデータ破棄を許可
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
