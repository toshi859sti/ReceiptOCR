package com.example.greenframeocr.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GeneralReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: GeneralReceipt): Long

    @Update
    suspend fun updateReceipt(receipt: GeneralReceipt)

    @Delete
    suspend fun deleteReceipt(receipt: GeneralReceipt)

    @Query("SELECT * FROM general_receipts ORDER BY date DESC, createdAt DESC")
    fun getAllReceipts(): Flow<List<GeneralReceipt>>

    @Query("SELECT * FROM general_receipts WHERE id = :id")
    suspend fun getReceiptById(id: Long): GeneralReceipt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<GeneralReceiptItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: GeneralReceiptItem): Long

    @Update
    suspend fun updateItem(item: GeneralReceiptItem)

    @Delete
    suspend fun deleteItem(item: GeneralReceiptItem)

    @Query("SELECT * FROM general_receipt_items WHERE receiptId = :receiptId ORDER BY id ASC")
    fun getItemsByReceiptId(receiptId: Long): Flow<List<GeneralReceiptItem>>

    @Query("SELECT * FROM general_receipt_items WHERE receiptId = :receiptId ORDER BY id ASC")
    suspend fun getItemsByReceiptIdOnce(receiptId: Long): List<GeneralReceiptItem>

    @Query("DELETE FROM general_receipt_items WHERE receiptId = :receiptId")
    suspend fun deleteItemsByReceiptId(receiptId: Long)

    @Query("""
        SELECT * FROM general_receipt_items
        WHERE (:from IS NULL OR (SELECT date FROM general_receipts WHERE id = receiptId) >= :from)
        AND   (:to   IS NULL OR (SELECT date FROM general_receipts WHERE id = receiptId) <= :to)
        ORDER BY (SELECT date FROM general_receipts WHERE id = receiptId) ASC, id ASC
    """)
    suspend fun getItemsForExport(from: String?, to: String?): List<GeneralReceiptItem>
}
