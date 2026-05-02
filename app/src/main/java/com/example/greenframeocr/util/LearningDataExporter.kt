package com.example.greenframeocr.util

import android.content.Context
import android.net.Uri
import com.example.greenframeocr.data.OcrVariantDao
import com.example.greenframeocr.data.ProductMasterDao
import com.google.gson.GsonBuilder
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportProduct(
    val canonicalName: String,
    val category: String,
    val isCertified: Boolean
)

data class ExportVariant(
    val canonicalName: String,
    val category: String,
    val variantText: String,
    val normalizedText: String,
    val confidenceLevel: String,
    val hitCount: Int,
    val manualCorrectCount: Int,
    val source: String
)

data class LearningExportData(
    val version: Int = 1,
    val exportedAt: String,
    val products: List<ExportProduct>,
    val variants: List<ExportVariant>
)

object LearningDataExporter {
    suspend fun export(
        context: Context,
        productMasterDao: ProductMasterDao,
        ocrVariantDao: OcrVariantDao,
        uri: Uri
    ) {
        val products = productMasterDao.getAll()
        val variants = ocrVariantDao.getAll()

        val productMap = products.associateBy { it.id }

        val exportProducts = products.map { p ->
            ExportProduct(
                canonicalName = p.canonicalName,
                category = p.category,
                isCertified = p.isCertified
            )
        }

        val exportVariants = variants.mapNotNull { v ->
            val product = productMap[v.productId] ?: return@mapNotNull null
            ExportVariant(
                canonicalName = product.canonicalName,
                category = product.category,
                variantText = v.variantText,
                normalizedText = v.normalizedText,
                confidenceLevel = v.confidenceLevel,
                hitCount = v.hitCount,
                manualCorrectCount = v.manualCorrectCount,
                source = v.source
            )
        }

        val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            .format(Date())

        val data = LearningExportData(
            exportedAt = exportedAt,
            products = exportProducts,
            variants = exportVariants
        )

        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(data)

        context.contentResolver.openOutputStream(uri)?.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                writer.write(json)
            }
        }
    }
}
