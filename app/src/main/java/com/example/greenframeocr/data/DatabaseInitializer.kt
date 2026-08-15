package com.example.greenframeocr.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.greenframeocr.util.withComputedKey
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
                // 勘定科目データが空なら初期化
                val yayoiCount = database.yayoiAccountDao().count()
                val rakurakuCount = database.rakurakuAccountDao().count()

                if (yayoiCount == 0) {
                    Log.d(TAG, "Initializing Yayoi accounts...")
                    importYayoiAccounts(context, database)
                }

                if (rakurakuCount == 0) {
                    Log.d(TAG, "Initializing Rakuraku accounts...")
                    importRakurakuAccounts(context, database)
                }

                seedReceiptPaymentMethodRulesIfNeeded(database)

                // すでにデータがある場合はスキップ
                val productCount = database.productMasterDao().getCount()
                if (productCount > 0) {
                    Log.d(TAG, "Database already initialized (products: $productCount)")
                    return@withContext
                }

                Log.d(TAG, "Starting database initialization...")

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
     * 弥生会計 勘定科目マスタ初期化
     * 初期データはマイグレーション（MIGRATION_19_20）で投入済みのため、
     * reinitialize() 時のみ再投入が必要。
     * CSV形式: 勘定科目,サーチキー英字,サーチキー数字,借貸,区分B,区分A,税区分,購買取引使用,預金取引使用,レシート取引使用
     */
    private suspend fun importYayoiAccounts(context: Context, database: ReceiptDatabase) {
        val dao = database.yayoiAccountDao()
        val accounts = mutableListOf<YayoiAccount>()

        context.assets.open("yayoi_accounts.csv").use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readLine() // ヘッダー行をスキップ

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(",")
                    if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                        accounts.add(
                            YayoiAccount(
                                id = 0,
                                accountName = parts[0].trim(),
                                searchKeyAlpha = parts.getOrNull(1)?.trim() ?: "",
                                accountCode = parts.getOrNull(2)?.trim()?.ifEmpty { null },
                                debitCredit = parts.getOrNull(3)?.trim() ?: "",
                                categoryB = parts.getOrNull(4)?.trim() ?: "",
                                categoryA = parts.getOrNull(5)?.trim() ?: "",
                                defaultTaxCategory = parts.getOrNull(6)?.trim() ?: "対象外",
                                usedForPurchase = parts.getOrNull(7)?.trim()?.uppercase() == "TRUE",
                                usedForDeposit = parts.getOrNull(8)?.trim()?.uppercase() == "TRUE",
                                usedForReceipt = parts.getOrNull(9)?.trim()?.uppercase() == "TRUE",
                                parentId = null
                            )
                        )
                    }
                }
            }
        }

        dao.deleteAll()
        dao.insertAll(accounts)
        Log.d(TAG, "Imported ${accounts.size} Yayoi accounts")
    }

    /**
     * レシート領収書の支払方法→相手科目ルールのデフォルトを投入する（ルールが1件も無い場合のみ）。
     * 現金→現金、クレジット→事業主借、PayPay→事業主借（ユーザー方針、2026-08-15）
     */
    private suspend fun seedReceiptPaymentMethodRulesIfNeeded(database: ReceiptDatabase) {
        val ruleDao = database.receiptPaymentMethodRuleDao()
        if (ruleDao.getAll().isNotEmpty()) return

        val accounts = database.yayoiAccountDao().getAll()
        val genkin = accounts.firstOrNull { it.accountName == "現金" }
        val jigyoushuKari = accounts.firstOrNull { it.accountName == "事業主借" }
        if (genkin == null || jigyoushuKari == null) return

        ruleDao.insert(ReceiptPaymentMethodRule(keyword = "現金", yayoiAccountId = genkin.id, sortOrder = 0))
        ruleDao.insert(ReceiptPaymentMethodRule(keyword = "クレジット", yayoiAccountId = jigyoushuKari.id, sortOrder = 1))
        ruleDao.insert(ReceiptPaymentMethodRule(keyword = "PayPay", yayoiAccountId = jigyoushuKari.id, sortOrder = 2))
        Log.d(TAG, "Seeded default receipt payment method rules")
    }

    /**
     * らくらく青色申告 勘定科目マスタをインポート
     * CSV形式: 勘定科目,サーチキー英字,サーチキー数字,借貸,区分C,区分B,区分A,購買取引使用,預金取引使用,親科目
     */
    private suspend fun importRakurakuAccounts(context: Context, database: ReceiptDatabase) {
        val dao = database.rakurakuAccountDao()
        val accounts = mutableListOf<RakurakuAccount>()
        val parentRefs = mutableListOf<Int?>() // CSV行番号の親参照

        context.assets.open("rakuraku_accounts.csv").use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                // ヘッダー行をスキップ
                reader.readLine()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(",")
                    if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                        val parentRef = parts.getOrNull(9)?.trim()?.toIntOrNull()
                        parentRefs.add(parentRef)
                        accounts.add(
                            RakurakuAccount(
                                id = 0, // AutoGenerate
                                accountName = parts[0].trim(),
                                searchKeyAlpha = parts.getOrNull(1)?.trim() ?: "",
                                accountCode = parts.getOrNull(2)?.trim() ?: "",
                                debitCredit = parts.getOrNull(3)?.trim() ?: "",
                                categoryC = parts.getOrNull(4)?.trim() ?: "",
                                categoryB = parts.getOrNull(5)?.trim() ?: "",
                                categoryA = parts.getOrNull(6)?.trim() ?: "",
                                usedForPurchase = parts.getOrNull(7)?.trim()?.uppercase() == "TRUE",
                                usedForDeposit = parts.getOrNull(8)?.trim()?.uppercase() != "FALSE",
                                parentId = null // 後で設定
                            )
                        )
                    }
                }
            }
        }

        dao.deleteAll()
        dao.insertAll(accounts)

        // 親科目の参照を設定（CSVの行番号ベース）
        // ID順で取得（getAll()はカテゴリ順なので順番がずれる）
        val allAccounts = dao.getAllById()
        for (i in accounts.indices) {
            val parentRef = parentRefs.getOrNull(i)
            if (parentRef != null && parentRef > 0 && parentRef <= allAccounts.size) {
                val account = allAccounts[i]
                val parentAccount = allAccounts[parentRef - 1] // 1-indexed
                dao.update(account.copy(parentId = parentAccount.id))
            }
        }

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
                                id = 0,
                                canonicalName = parts[1].trim(),
                                category = parts[2].trim(),
                                frequencyCount = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: 0,
                                kaikakeTekiyouId = parts.getOrNull(4)?.trim()?.toIntOrNull()
                            ).withComputedKey()
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
                                source = VariantSource.SYSTEM.name
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
