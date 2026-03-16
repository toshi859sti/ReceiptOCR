package com.example.greenframeocr.util

import android.content.Context
import com.example.greenframeocr.data.RakurakuTekiyou
import com.example.greenframeocr.data.ReceiptDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * rakurakutekiyou.csv からDBへ差分インポート
 * 既存のDBにない項目のみ追加し、既存データは保持する
 */
suspend fun importTekiyouFromCsv(context: Context, database: ReceiptDatabase) {
    withContext(Dispatchers.IO) {
        try {
            val inputStream = context.assets.open("rakurakutekiyou.csv")
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            val tekiyouList = mutableListOf<RakurakuTekiyou>()

            var currentMainCategory = ""
            var currentSubCategory = ""
            var isDataSection = false

            reader.forEachLine { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) {
                    isDataSection = false
                    return@forEachLine
                }

                val cells = trimmedLine.split(",").map { it.trim() }

                when {
                    cells[0] == "現金-入金" -> { currentMainCategory = "現金"; currentSubCategory = "入金"; isDataSection = false }
                    cells[0] == "現金-出金" -> { currentMainCategory = "現金"; currentSubCategory = "出金"; isDataSection = false }
                    cells[0] == "預金-入金" -> { currentMainCategory = "預金"; currentSubCategory = "入金"; isDataSection = false }
                    cells[0] == "預金-出金" -> { currentMainCategory = "預金"; currentSubCategory = "出金"; isDataSection = false }
                    cells[0] == "売掛-販売" -> { currentMainCategory = "売掛"; currentSubCategory = "販売"; isDataSection = false }
                    cells[0] == "売掛-入金" -> { currentMainCategory = "売掛"; currentSubCategory = "入金"; isDataSection = false }
                    cells[0] == "買掛－購入" || cells[0] == "買掛-購入" -> { currentMainCategory = "買掛"; currentSubCategory = "購入"; isDataSection = false }
                    cells[0] == "買掛－出金" || cells[0] == "買掛-出金" -> { currentMainCategory = "買掛"; currentSubCategory = "出金"; isDataSection = false }
                    cells[0] == "index" -> { isDataSection = true }
                    isDataSection && (cells[0].toIntOrNull() != null || cells[0].isEmpty()) && cells.size >= 4 -> {
                        val tekiyouName = cells.getOrNull(1) ?: ""
                        val searchKey = cells.getOrNull(2) ?: ""
                        val kamoku = cells.getOrNull(3) ?: ""
                        val taxRate = cells.getOrNull(4) ?: ""
                        val businessRatioStr = cells.getOrNull(5) ?: ""
                        val sharedStr = cells.getOrNull(6) ?: ""

                        if (tekiyouName.isNotEmpty() && kamoku.isNotEmpty()) {
                            val isShared = if (currentMainCategory in listOf("現金", "預金")) {
                                sharedStr.equals("TRUE", ignoreCase = true)
                            } else null

                            tekiyouList.add(
                                RakurakuTekiyou(
                                    mainCategory = currentMainCategory,
                                    subCategory = currentSubCategory,
                                    tekiyouName = tekiyouName,
                                    searchKey = searchKey,
                                    kamoku = kamoku,
                                    taxRate = taxRate,
                                    businessRatio = businessRatioStr.toIntOrNull(),
                                    isShared = isShared
                                )
                            )
                        }
                    }
                }
            }
            reader.close()

            if (tekiyouList.isNotEmpty()) {
                val existingKeys = database.rakurakuTekiyouDao().getAllKeys().toSet()
                val newItems = tekiyouList.filter {
                    "${it.mainCategory}|${it.subCategory}|${it.tekiyouName}" !in existingKeys
                }
                if (newItems.isNotEmpty()) {
                    database.rakurakuTekiyouDao().insertAll(newItems)
                }
            }
            Unit
        } catch (e: Exception) {
            android.util.Log.e("TekiyouDictImporter", "Failed to import CSV", e)
        }
    }
}
