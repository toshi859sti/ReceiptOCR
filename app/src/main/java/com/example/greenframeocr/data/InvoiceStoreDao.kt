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

    @Delete
    suspend fun delete(store: InvoiceStore)
}
