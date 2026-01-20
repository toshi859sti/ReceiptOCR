package com.example.receiptorc.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * データベース初期化クラス
 *
 * assets/配下のCSVファイルをインポートして辞書データを構築。
 */
object DatabaseInitializer {

    private const val TAG = "DatabaseInitializer"

    /**
     * 初回起動時またはデータが空の場合にCSVをインポート
     */
    suspend fun initializeIfNeeded(context: Context, database: ReceiptDatabase) {
        withContext(Dispatchers.IO) {
            try {
                // すでにデータがある場合はスキップ
                val productCount = database.productMasterDao().getCount()
                if (productCount > 0) {
                    Log.d(TAG, "Database already initialized (products: $productCount)")
                    return@withContext
                }

                Log.d(TAG, "Starting database initialization...")

                // 1. 勘定科目マスタのインポート（外部キー参照されるため先にインポート）
                importYayoiAccounts(context, database)
                importRakurakuAccounts(context, database)

                // 2. 商品マスタのインポート
                importProductMaster(context, database)

                // 4. OCR誤認識パターンのインポート
                importOcrVariants(context, database)

                Log.d(TAG, "Database initialization completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize database", e)
                throw e
            }
        }
    }

    /**
     * 弥生会計 勘定科目マスタをインポート
     * CSV形式: id,account_code,account_name,category,subcategory,description
     */
    private suspend fun importYayoiAccounts(context: Context, database: ReceiptDatabase) {
        val dao = database.yayoiAccountDao()
        val accounts = mutableListOf<YayoiAccount>()

        context.assets.open("yayoi_accounts.csv").use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                // ヘッダー行をスキップ
                reader.readLine()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(",")
                    if (parts.size >= 3) {
                        accounts.add(
                            YayoiAccount(
                                id = 0, // AutoGenerate
                                accountCode = parts[1].trim(),
                                accountName = parts[2].trim(),
                                category = parts.getOrNull(3)?.trim(),
                                subcategory = parts.getOrNull(4)?.trim(),
                                description = parts.getOrNull(5)?.trim()
                            )
                        )
                    }
                }
            }
        }

        dao.insertAll(accounts)
        Log.d(TAG, "Imported ${accounts.size} Yayoi accounts")
    }

    /**
     * らくらく青色申告 勘定科目マスタをインポート
     * CSV形式: id,account_code,account_name,category,subcategory,description
     */
    private suspend fun importRakurakuAccounts(context: Context, database: ReceiptDatabase) {
        val dao = database.rakurakuAccountDao()
        val accounts = mutableListOf<RakurakuAccount>()

        context.assets.open("rakuraku_accounts.csv").use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                // ヘッダー行をスキップ
                reader.readLine()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(",")
                    if (parts.size >= 3) {
                        accounts.add(
                            RakurakuAccount(
                                id = 0, // AutoGenerate
                                accountCode = parts[1].trim(),
                                accountName = parts[2].trim(),
                                category = parts.getOrNull(3)?.trim(),
                                subcategory = parts.getOrNull(4)?.trim(),
                                description = parts.getOrNull(5)?.trim()
                            )
                        )
                    }
                }
            }
        }

        dao.insertAll(accounts)
        Log.d(TAG, "Imported ${accounts.size} Rakuraku accounts")
    }

    /**
     * 商品マスタをインポート
     * CSV形式: id,canonical_name,category,frequency_count,yayoi_account_id,rakuraku_account_id
     */
    private suspend fun importProductMaster(context: Context, database: ReceiptDatabase) {
        val dao = database.productMasterDao()
        val products = mutableListOf<ProductMaster>()

        context.assets.open("product_master.csv").use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                // ヘッダー行をスキップ
                reader.readLine()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(",")
                    if (parts.size >= 3) {
                        products.add(
                            ProductMaster(
                                id = 0, // AutoGenerate
                                canonicalName = parts[1].trim(),
                                category = parts[2].trim(),
                                frequencyCount = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: 0,
                                yayoiAccountId = parts.getOrNull(4)?.trim()?.toLongOrNull(),
                                rakurakuAccountId = parts.getOrNull(5)?.trim()?.toLongOrNull()
                            )
                        )
                    }
                }
            }
        }

        dao.insertAll(products)
        Log.d(TAG, "Imported ${products.size} products")
    }

    /**
     * OCR誤認識パターンをインポート
     * CSV形式: product_id,variant_text,occurrence_count,last_seen
     */
    private suspend fun importOcrVariants(context: Context, database: ReceiptDatabase) {
        val dao = database.ocrVariantDao()
        val variants = mutableListOf<OcrVariant>()

        context.assets.open("ocr_variants.csv").use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                // ヘッダー行をスキップ
                reader.readLine()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(",")
                    if (parts.size >= 3) {
                        val text = parts[1].trim()
                        val count = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                        val timestamp = parts.getOrNull(3)?.trim()?.toLongOrNull()
                            ?: System.currentTimeMillis()
                        variants.add(
                            OcrVariant(
                                id = 0, // AutoGenerate
                                productId = parts[0].trim().toLong(),
                                variantText = text,
                                normalizedText = text, // 同じ値で初期化
                                hitCount = count,
                                firstSeenAt = timestamp,
                                lastSeenAt = timestamp,
                                source = VariantSource.IMPORT.name
                            )
                        )
                    }
                }
            }
        }

        dao.insertAll(variants)
        Log.d(TAG, "Imported ${variants.size} OCR variants")
    }

    /**
     * 全データを削除して再インポート（開発・テスト用）
     */
    suspend fun reinitialize(context: Context, database: ReceiptDatabase) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Clearing all dictionary data...")

                // 逆順で削除（外部キー制約に配慮）
                database.ocrVariantDao().deleteAll()
                database.productMasterDao().deleteAll()
                database.rakurakuAccountDao().deleteAll()
                database.yayoiAccountDao().deleteAll()

                Log.d(TAG, "All dictionary data cleared. Starting import...")

                // 再インポート
                importYayoiAccounts(context, database)
                importRakurakuAccounts(context, database)
                importProductMaster(context, database)
                importOcrVariants(context, database)

                Log.d(TAG, "Reinitialization completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reinitialize database", e)
                throw e
            }
        }
    }
}
