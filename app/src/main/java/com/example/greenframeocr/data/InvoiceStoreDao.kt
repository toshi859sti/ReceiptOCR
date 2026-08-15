package com.example.greenframeocr.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceStoreDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(store: InvoiceStore)

    @Query("SELECT * FROM invoice_stores WHERE registrationNumber = :number")
    suspend fun findByNumber(number: String): InvoiceStore?

    @Query("SELECT * FROM invoice_stores ORDER BY storeName ASC")
    fun getAll(): Flow<List<InvoiceStore>>

    // バックアップ書き出し用（データ管理画面のエクスポート機能）
    @Query("SELECT * FROM invoice_stores ORDER BY storeName ASC")
    suspend fun getAllOnce(): List<InvoiceStore>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stores: List<InvoiceStore>)

    @Delete
    suspend fun delete(store: InvoiceStore)

    @Query("DELETE FROM invoice_stores")
    suspend fun deleteAll()
}
