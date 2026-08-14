package com.example.greenframeocr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GeneralItemMasterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(master: GeneralItemMaster)

    @Query("SELECT * FROM general_item_master WHERE canonicalKey = :canonicalKey")
    suspend fun getByKey(canonicalKey: String): GeneralItemMaster?

    @Query("SELECT * FROM general_item_master")
    suspend fun getAll(): List<GeneralItemMaster>

    // 類似グループ統合で吸収された側のグループデフォルト設定を削除する
    @Query("DELETE FROM general_item_master WHERE canonicalKey = :canonicalKey")
    suspend fun deleteByKey(canonicalKey: String)
}
