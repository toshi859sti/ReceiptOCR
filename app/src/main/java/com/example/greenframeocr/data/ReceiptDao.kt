package com.example.greenframeocr.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    // ReceiptItem operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceiptItem(item: ReceiptItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceiptItems(items: List<ReceiptItem>)

    @Query("SELECT * FROM receipt_items ORDER BY issueYear, issueMonth, sheetNumber, itemNumber")
    suspend fun getAllReceiptItems(): List<ReceiptItem>

    @Query("SELECT * FROM receipt_items WHERE issueYear = :year AND issueMonth = :month ORDER BY sheetNumber, itemNumber")
    fun getReceiptItemsByMonth(year: Int, month: Int): Flow<List<ReceiptItem>>

    @Query("SELECT * FROM receipt_items WHERE issueYear = :year AND issueMonth = :month AND sheetNumber = :sheetNumber ORDER BY itemNumber")
    suspend fun getReceiptItemsBySheet(year: Int, month: Int, sheetNumber: Int): List<ReceiptItem>

    @Query("SELECT COALESCE(MAX(sheetNumber), 0) FROM receipt_items WHERE issueYear = :year AND issueMonth = :month")
    suspend fun getMaxSheetNumberForMonth(year: Int, month: Int): Int

    @Query("DELETE FROM receipt_items WHERE issueYear = :year AND issueMonth = :month AND sheetNumber = :sheetNumber")
    suspend fun deleteReceiptItemsBySheet(year: Int, month: Int, sheetNumber: Int)

    @Query("DELETE FROM receipt_items WHERE issueYear = :year AND issueMonth = :month")
    suspend fun deleteReceiptItemsByMonth(year: Int, month: Int)

    @Update
    suspend fun updateReceiptItem(item: ReceiptItem)

    @Update
    suspend fun updateReceiptItems(items: List<ReceiptItem>)

    // MonthlyData operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMonthlyData(data: MonthlyData)

    @Query("SELECT * FROM monthly_data ORDER BY issueYear, issueMonth")
    suspend fun getAllMonthlyData(): List<MonthlyData>

    @Query("SELECT * FROM monthly_data WHERE id = :id")
    suspend fun getMonthlyData(id: String): MonthlyData?

    @Query("SELECT * FROM monthly_data WHERE issueYear = :year ORDER BY issueMonth")
    fun getMonthlyDataByYear(year: Int): Flow<List<MonthlyData>>

    @Query("DELETE FROM monthly_data WHERE id = :id")
    suspend fun deleteMonthlyData(id: String)

    @Update
    suspend fun updateMonthlyData(data: MonthlyData)

    // SheetData operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSheetData(data: SheetData)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSheetDataList(dataList: List<SheetData>)

    @Query("SELECT * FROM sheet_data ORDER BY issueYear, issueMonth, sheetNumber")
    suspend fun getAllSheetData(): List<SheetData>

    @Query("SELECT * FROM sheet_data WHERE issueYear = :year AND issueMonth = :month ORDER BY sheetNumber")
    fun getSheetDataByMonth(year: Int, month: Int): Flow<List<SheetData>>

    @Query("SELECT * FROM sheet_data WHERE issueYear = :year AND issueMonth = :month AND sheetNumber = :sheetNumber")
    suspend fun getSheetData(year: Int, month: Int, sheetNumber: Int): SheetData?

    @Query("DELETE FROM sheet_data WHERE issueYear = :year AND issueMonth = :month AND sheetNumber = :sheetNumber")
    suspend fun deleteSheetData(year: Int, month: Int, sheetNumber: Int)

    @Update
    suspend fun updateSheetData(data: SheetData)

    @Update
    suspend fun updateSheetDataList(dataList: List<SheetData>)

    // 全伝票のユニークな商品名を取得（小計・合計行を除く）
    @Query("SELECT DISTINCT productName FROM receipt_items WHERE productName != '' AND productName NOT LIKE '%小計%' AND productName NOT LIKE '%合計%' ORDER BY productName")
    suspend fun getAllDistinctProductNames(): List<String>

    // 商品名の一括書き換え（統合・改名時）
    @Query("UPDATE receipt_items SET productName = :newName WHERE productName = :oldName")
    suspend fun updateProductNameInReceiptItems(oldName: String, newName: String)

    // 年別集計用
    @Query("SELECT DISTINCT issueYear FROM sheet_data ORDER BY issueYear DESC")
    suspend fun getAvailableYears(): List<Int>

    @Query("SELECT DISTINCT issueMonth FROM sheet_data WHERE issueYear = :year ORDER BY issueMonth")
    suspend fun getAvailableMonthsForYear(year: Int): List<Int>

    // 全データ削除
    @Query("DELETE FROM receipt_items")
    suspend fun deleteAllReceiptItems()

    @Query("DELETE FROM monthly_data")
    suspend fun deleteAllMonthlyData()

    @Query("DELETE FROM sheet_data")
    suspend fun deleteAllSheetData()
}
