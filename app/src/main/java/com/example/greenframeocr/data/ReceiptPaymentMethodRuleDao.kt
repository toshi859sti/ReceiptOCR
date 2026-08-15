package com.example.greenframeocr.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReceiptPaymentMethodRuleDao {

    @Query("SELECT * FROM receipt_payment_method_rules ORDER BY sortOrder, id")
    suspend fun getAll(): List<ReceiptPaymentMethodRule>

    @Insert
    suspend fun insert(rule: ReceiptPaymentMethodRule): Long

    @Update
    suspend fun update(rule: ReceiptPaymentMethodRule)

    @Delete
    suspend fun delete(rule: ReceiptPaymentMethodRule)
}
