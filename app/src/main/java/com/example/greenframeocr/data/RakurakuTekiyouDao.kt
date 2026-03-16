package com.example.greenframeocr.data

import androidx.room.*

@Dao
interface RakurakuTekiyouDao {

    @Query("SELECT * FROM rakuraku_tekiyou ORDER BY mainCategory, subCategory, id")
    suspend fun getAll(): List<RakurakuTekiyou>

    @Query("SELECT * FROM rakuraku_tekiyou WHERE mainCategory = :mainCategory AND subCategory = :subCategory ORDER BY id")
    suspend fun getByCategory(mainCategory: String, subCategory: String): List<RakurakuTekiyou>

    @Query("SELECT * FROM rakuraku_tekiyou WHERE mainCategory = :mainCategory AND subCategory = :subCategory AND isEnabled = 1 ORDER BY id")
    suspend fun getEnabledByCategory(mainCategory: String, subCategory: String): List<RakurakuTekiyou>

    @Query("UPDATE rakuraku_tekiyou SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateEnabled(id: Int, isEnabled: Boolean)

    @Query("SELECT * FROM rakuraku_tekiyou WHERE id = :id")
    suspend fun getById(id: Int): RakurakuTekiyou?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tekiyou: RakurakuTekiyou): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tekiyou: RakurakuTekiyou): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tekiyouList: List<RakurakuTekiyou>)

    @Update
    suspend fun update(tekiyou: RakurakuTekiyou)

    @Delete
    suspend fun delete(tekiyou: RakurakuTekiyou)

    @Query("DELETE FROM rakuraku_tekiyou")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rakuraku_tekiyou")
    suspend fun getCount(): Int

    @Query("SELECT mainCategory || '|' || subCategory || '|' || tekiyouName FROM rakuraku_tekiyou")
    suspend fun getAllKeys(): List<String>
}
