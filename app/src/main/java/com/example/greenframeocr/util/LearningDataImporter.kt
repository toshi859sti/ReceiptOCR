package com.example.greenframeocr.util

import android.content.Context
import android.net.Uri
import com.example.greenframeocr.data.OcrVariant
import com.example.greenframeocr.data.OcrVariantDao
import com.example.greenframeocr.data.ProductMaster
import com.example.greenframeocr.data.ProductMasterDao
import com.example.greenframeocr.data.VariantSource
import com.google.gson.Gson
import java.io.InputStreamReader

data class ImportResult(
    val addedProducts: Int,
    val skippedProducts: Int,
    val addedVariants: Int,
    val skippedVariants: Int
)

object LearningDataImporter {
    suspend fun import(
        context: Context,
        productMasterDao: ProductMasterDao,
        ocrVariantDao: OcrVariantDao,
        uri: Uri
    ): ImportResult {
        val data = context.contentResolver.openInputStream(uri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                Gson().fromJson(reader, LearningExportData::class.java)
            }
        } ?: return ImportResult(0, 0, 0, 0)

        var addedProducts = 0
        var skippedProducts = 0

        // canonicalName+category → DB上のID
        val canonicalToId = mutableMapOf<Pair<String, String>, Long>()

        for (ep in data.products) {
            val existing = productMasterDao.getByName(ep.canonicalName)
            if (existing != null) {
                canonicalToId[Pair(ep.canonicalName, ep.category)] = existing.id
                skippedProducts++
            } else {
                val newId = productMasterDao.insert(
                    ProductMaster(
                        canonicalName = ep.canonicalName,
                        category = ep.category,
                        isCertified = ep.isCertified
                    )
                )
                canonicalToId[Pair(ep.canonicalName, ep.category)] = newId
                addedProducts++
            }
        }

        var addedVariants = 0
        var skippedVariants = 0

        for (ev in data.variants) {
            val productId = canonicalToId[Pair(ev.canonicalName, ev.category)]
                ?: productMasterDao.getByName(ev.canonicalName)?.id
                ?: continue

            val existing = ocrVariantDao.findByNormalizedTextAndProduct(ev.normalizedText, productId)
            if (existing != null) {
                skippedVariants++
                continue
            }

            val now = System.currentTimeMillis()
            ocrVariantDao.insert(
                OcrVariant(
                    productId = productId,
                    variantText = ev.variantText,
                    normalizedText = ev.normalizedText,
                    confidenceLevel = ev.confidenceLevel,
                    hitCount = ev.hitCount,
                    manualCorrectCount = ev.manualCorrectCount,
                    source = VariantSource.IMPORT.name,
                    firstSeenAt = now,
                    lastSeenAt = now
                )
            )
            addedVariants++
        }

        return ImportResult(addedProducts, skippedProducts, addedVariants, skippedVariants)
    }
}
