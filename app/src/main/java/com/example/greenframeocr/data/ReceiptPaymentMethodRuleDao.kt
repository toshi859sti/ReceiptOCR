package com.example.greenframeocr.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReceiptPaymentMethodRuleDao {

    @Query("SELECT * FROM receipt_payment_method_rules ORDER BY sortOrder, id")
    suspend fun getAll(): List<ReceiptPaymentMethodRule>

    @Insert
    suspend fun insert(rule: ReceiptPaymentMethodRule): Long

    // バックアップ復元用（IDを保持したまま上書き）
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<ReceiptPaymentMethodRule>)

    @Update
    suspend fun update(rule: ReceiptPaymentMethodRule)

    @Delete
    suspend fun delete(rule: ReceiptPaymentMethodRule)

    @Query("DELETE FROM receipt_payment_method_rules")
    suspend fun deleteAll()
}
