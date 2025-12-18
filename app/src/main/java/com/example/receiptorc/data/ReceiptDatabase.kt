package com.example.receiptorc.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReceiptItem::class, MonthlyData::class, SheetData::class],
    version = 2,
    exportSchema = false
)
abstract class ReceiptDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao

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

        fun getDatabase(context: Context): ReceiptDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ReceiptDatabase::class.java,
                    "receipt_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()  // 開発中はデータ破棄を許可
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
