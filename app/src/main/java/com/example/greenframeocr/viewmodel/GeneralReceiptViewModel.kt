package com.example.greenframeocr.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.GeneralItemGroup
import com.example.greenframeocr.data.GeneralItemMaster
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.InvoiceStore
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.data.ReceiptItemPreview
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.CsvUtils
import com.example.greenframeocr.util.GeminiApiException
import com.example.greenframeocr.util.GeminiApiKeyMissingException
import com.example.greenframeocr.util.GeminiQuotaExhaustedException
import com.example.greenframeocr.util.GeminiRateLimitException
import com.example.greenframeocr.util.GeminiReceiptClient
import com.example.greenframeocr.util.toCanonicalKey
import com.example.greenframeocr.util.withComputedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 登録番号・店舗一覧に表示する1件。登録番号ありはinvoice_stores由来、
// なしはレシートのstoreName実績のみから作る（registrationNumber=null）
data class IssuerEntry(
    val storeName: String,
    val registrationNumber: String?,
    val address: String = ""
)

data class GeneralReceiptOutputItem(
    val itemId: Long,
    val receiptId: Long,
    val date: String,
    val storeName: String,
    val itemName: String,
    val price: Int,
    val accountName: String,       // 主科目名（補助科目がある場合は親科目名）
    val accountCode: String,
    val debitSubAccountName: String = "",  // 補助科目名（なければ空）
    val defaultTaxCategory: String = "対象外",
    var isSelected: Boolean = true
)

class GeneralReceiptViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ReceiptDatabase.getDatabase(application)
    private val dao = db.generalReceiptDao()
    private val prefs = AppPreferences(application)

    val receipts: StateFlow<List<GeneralReceipt>> =
        dao.getAllReceipts().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 店舗名手入力時のオートコンプリート候補（過去のレシートのstoreName実績、新しい順）
    val storeNameSuggestions: StateFlow<List<String>> =
        receipts.map { list -> list.map { it.storeName }.filter { it.isNotBlank() }.distinct() }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val itemGroups: StateFlow<List<GeneralItemGroup>> =
        dao.getItemGroups().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 品目名（＝但し書き）手入力時のオートコンプリート候補。canonicalKeyで正規化グルーピング済みの
    // itemGroupsをそのまま使う（表記ゆれを吸収済み・件数の多い順）
    val itemNameSuggestions: StateFlow<List<String>> =
        itemGroups.map { groups -> groups.map { it.itemName }.filter { it.isNotBlank() } }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 一覧カードの商品名プレビュー用（receiptId → 商品名連結文字列・経費対象件数）
    val itemPreviews: StateFlow<Map<Long, ReceiptItemPreview>> =
        dao.getItemNamePreviews()
            .map { list -> list.associate { it.receiptId to it } }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val invoiceStores: StateFlow<List<InvoiceStore>> =
        db.invoiceStoreDao().getAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 登録番号・店舗一覧画面用：登録番号ありの法人（invoice_stores）＋
    // 登録番号が取れていないレシートの発行者名（storeNameのみ）をマージした一覧
    val issuerList: StateFlow<List<IssuerEntry>> =
        combine(invoiceStores, receipts) { stores, receiptList ->
            val registeredNames = stores.map { it.storeName }.toSet()
            val registered = stores.map {
                IssuerEntry(storeName = it.storeName, registrationNumber = it.registrationNumber, address = it.address)
            }
            val unregistered = receiptList
                .map { it.storeName }
                .filter { it.isNotBlank() && it !in registeredNames }
                .distinct()
                .map { IssuerEntry(storeName = it, registrationNumber = null) }
            (registered + unregistered).sortedBy { it.storeName }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // AI提案結果
    data class AiSuggestion(
        val canonicalKey: String,
        val itemName: String,
        val accountId: Long,
        val accountName: String,
        val reason: String
    )
    private val _aiSuggestions = MutableStateFlow<List<AiSuggestion>>(emptyList())
    val aiSuggestions: StateFlow<List<AiSuggestion>> = _aiSuggestions

    private val _aiUsageStats = MutableStateFlow<GeminiReceiptClient.AiUsageStats?>(null)
    val aiUsageStats: StateFlow<GeminiReceiptClient.AiUsageStats?> = _aiUsageStats

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

    private val _isAiMatching = MutableStateFlow(false)
    val isAiMatching: StateFlow<Boolean> = _isAiMatching

    private val _isLookingUpStore = MutableStateFlow(false)
    val isLookingUpStore: StateFlow<Boolean> = _isLookingUpStore

    private val _storeLookupError = MutableStateFlow<String?>(null)
    val storeLookupError: StateFlow<String?> = _storeLookupError

    private val _pendingReceipt = MutableStateFlow<GeneralReceipt?>(null)
    val pendingReceipt: StateFlow<GeneralReceipt?> = _pendingReceipt

    private val _pendingItems = MutableStateFlow<List<GeneralReceiptItem>>(emptyList())
    val pendingItems: StateFlow<List<GeneralReceiptItem>> = _pendingItems

    sealed class UiState {
        object Idle : UiState()
        object GeminiRunning : UiState()
        data class Done(val receiptId: Long) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    fun saveReceipt(receipt: GeneralReceipt, items: List<GeneralReceiptItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val receiptId = dao.insertReceipt(receipt)
            val itemsWithId = items.map { it.copy(receiptId = receiptId).withComputedKey() }
            dao.insertItems(itemsWithId)
            // 登録番号があれば invoice_stores に登録（未登録の場合のみ）
            if (receipt.registrationNumber.isNotBlank()) {
                val existing = db.invoiceStoreDao().findByNumber(receipt.registrationNumber)
                if (existing == null) {
                    db.invoiceStoreDao().upsert(
                        InvoiceStore(
                            registrationNumber = receipt.registrationNumber,
                            storeName = receipt.storeName
                        )
                    )
                }
            }
            _pendingReceipt.value = null
            _pendingItems.value = emptyList()
            _uiState.value = UiState.Done(receiptId)
        }
    }

    fun onImageCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            val apiKey = prefs.geminiApiKey
            if (apiKey.isBlank() || !isNetworkAvailable()) {
                _uiState.value = UiState.Error("Gemini APIキーが未設定またはオフラインです")
                return@launch
            }
            _uiState.value = UiState.GeminiRunning
            try {
                val result = GeminiReceiptClient.parseReceiptFromImage(bitmap, apiKey)
                if (result != null) {
                    _pendingReceipt.value = GeneralReceipt(
                        date = result.date,
                        storeName = result.storeName,
                        total = result.total,
                        rawOcrText = "",
                        geminiUsed = true
                    )
                    _pendingItems.value = result.items.map { item ->
                        GeneralReceiptItem(receiptId = 0, itemName = item.name, price = item.price)
                    }
                    _uiState.value = UiState.Idle
                    // 画像モードでも店舗名が空なら登録番号フィールドで後から補完可能
                } else {
                    _uiState.value = UiState.Error("Gemini画像解析に失敗しました")
                }
            } catch (e: GeminiRateLimitException) {
                _uiState.value = UiState.Error(e.message ?: "利用上限エラー")
            }
        }
    }

    /** グループのデフォルト科目を変更する。グループ内の個別上書きは全解除される
     *  （預金摘要集約リストと同じ「グループ保存時は全件リセット」挙動） */
    fun updateGroupDefaultAccount(canonicalKey: String, accountId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            db.generalItemMasterDao().upsert(GeneralItemMaster(canonicalKey, accountId))
            dao.clearOverridesForGroup(canonicalKey)
        }
    }

    /** グループ内の個別明細だけ科目を上書きする。accountId=nullでグループのデフォルトに戻す */
    fun updateItemOverride(itemId: Long, accountId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateAccountForItem(itemId, accountId)
        }
    }

    fun getItemsByCanonicalKey(canonicalKey: String) = dao.getItemsByCanonicalKey(canonicalKey)

    fun suggestAccountsForItems(
        unmatchedGroups: List<GeneralItemGroup>,
        accounts: List<YayoiAccount>
    ) {
        viewModelScope.launch {
            _isAiMatching.value = true
            _aiError.value = null
            try {
                val productPairs = unmatchedGroups.map { it.itemName to "一般購買" }
                val result = GeminiReceiptClient.matchProductsToAccounts(
                    productNames = productPairs,
                    accounts = accounts,
                    apiKey = prefs.geminiApiKey
                )
                val canonicalKeyByName = unmatchedGroups.associate { it.itemName to it.canonicalKey }
                _aiSuggestions.value = result.suggestions.map {
                    AiSuggestion(
                        canonicalKey = canonicalKeyByName[it.productName] ?: toCanonicalKey(it.productName),
                        itemName = it.productName,
                        accountId = it.suggestedAccountId,
                        accountName = it.suggestedAccountName,
                        reason = it.reason
                    )
                }
                _aiUsageStats.value = result.usageStats
            } catch (e: GeminiApiKeyMissingException) {
                _aiError.value = e.message
            } catch (e: GeminiQuotaExhaustedException) {
                _aiError.value = e.message
            } catch (e: GeminiRateLimitException) {
                _aiError.value = e.message
            } catch (e: GeminiApiException) {
                _aiError.value = e.message
            } catch (e: Exception) {
                _aiError.value = "エラー: ${e.message}"
            } finally {
                _isAiMatching.value = false
            }
        }
    }

    fun clearAiSuggestions() {
        _aiSuggestions.value = emptyList()
        _aiUsageStats.value = null
        _aiError.value = null
    }

    /** 登録番号から店舗名を照会し pendingReceipt.storeName を更新する（手動ボタン用） */
    fun lookupStoreByRegistrationNumber(registrationNumber: String) {
        viewModelScope.launch { autoLookupStore(registrationNumber) }
    }

    private suspend fun autoLookupStore(registrationNumber: String) {
        _isLookingUpStore.value = true
        _storeLookupError.value = null
        try {
            val storeName = resolveStoreName(registrationNumber)
            if (storeName != null) {
                _pendingReceipt.value = _pendingReceipt.value?.copy(storeName = storeName)
            } else {
                _storeLookupError.value = "登録番号 $registrationNumber の事業者情報が見つかりませんでした"
            }
        } finally {
            _isLookingUpStore.value = false
        }
    }

    /** ローカルキャッシュ（過去に保存・編集した登録番号）から店舗名を照会する */
    private suspend fun resolveStoreName(registrationNumber: String): String? =
        withContext(Dispatchers.IO) {
            db.invoiceStoreDao().findByNumber(registrationNumber)?.storeName
        }

    fun clearStoreLookupError() { _storeLookupError.value = null }

    /** 既存の保存済みレシートから登録番号を遡って invoice_stores に登録する */
    fun backfillInvoiceStoresFromReceipts() {
        viewModelScope.launch(Dispatchers.IO) {
            val allReceipts = dao.getAllReceiptsOnce()
            allReceipts
                .filter { it.registrationNumber.isNotBlank() }
                .forEach { receipt ->
                    val existing = db.invoiceStoreDao().findByNumber(receipt.registrationNumber)
                    if (existing == null) {
                        db.invoiceStoreDao().upsert(
                            InvoiceStore(
                                registrationNumber = receipt.registrationNumber,
                                storeName = receipt.storeName
                            )
                        )
                    }
                }
        }
    }

    fun deleteInvoiceStore(store: InvoiceStore) {
        viewModelScope.launch(Dispatchers.IO) { db.invoiceStoreDao().delete(store) }
    }

    fun deleteInvoiceStoreByRegistrationNumber(registrationNumber: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.invoiceStoreDao().findByNumber(registrationNumber)?.let { db.invoiceStoreDao().delete(it) }
        }
    }

    fun updateInvoiceStoreName(
        registrationNumber: String,
        newName: String,
        feedbackToReceipts: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            db.invoiceStoreDao().upsert(
                InvoiceStore(registrationNumber = registrationNumber, storeName = newName)
            )
            if (feedbackToReceipts) {
                dao.updateStoreNameByRegistrationNumber(registrationNumber, newName)
            }
        }
    }

    /** 登録番号未登録の発行者名をリネーム（該当storeNameの全レシートに反映） */
    fun renameUnregisteredIssuer(oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateStoreNameByOldName(oldName, newName)
        }
    }

    suspend fun loadYayoiAccounts(): List<YayoiAccount> =
        withContext(Dispatchers.IO) { db.yayoiAccountDao().getAll().filter { it.isEnabled } }

    fun clearPending() {
        _pendingReceipt.value = null
        _pendingItems.value = emptyList()
        _uiState.value = UiState.Idle
    }

    fun deleteReceipt(receipt: GeneralReceipt) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteReceipt(receipt) }
    }

    suspend fun getItemsForReceipt(receiptId: Long): List<GeneralReceiptItem> =
        withContext(Dispatchers.IO) { dao.getItemsByReceiptIdOnce(receiptId) }

    fun saveReceiptEdits(
        receipt: GeneralReceipt,
        updatedItems: List<GeneralReceiptItem>,
        originalItems: List<GeneralReceiptItem>
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReceipt(receipt)
            val updatedIds = updatedItems.filter { it.id != 0L }.map { it.id }.toSet()
            originalItems.filter { it.id !in updatedIds }.forEach { dao.deleteItem(it) }
            updatedItems.forEach { item ->
                val keyed = item.copy(receiptId = receipt.id).withComputedKey()
                if (item.id == 0L) dao.insertItem(keyed)
                else dao.updateItem(keyed)
            }
        }
    }

    /** 個別上書き（item.yayoiAccountId）があればそちら優先、なければグループのデフォルトを使う */
    private suspend fun resolveEffectiveAccountId(item: GeneralReceiptItem): Long? {
        item.yayoiAccountId?.let { return it }
        return db.generalItemMasterDao().getByKey(item.canonicalKey)?.yayoiAccountId
    }

    suspend fun loadOutputItems(): List<GeneralReceiptOutputItem> =
        withContext(Dispatchers.IO) {
            val allItems = dao.getItemsForExport(null, null)
            val allAccounts = db.yayoiAccountDao().getAll().associateBy { it.id }
            allItems.map { item ->
                val receipt = dao.getReceiptById(item.receiptId)
                val account = resolveEffectiveAccountId(item)?.let { allAccounts[it] }
                val parentAccount = account?.parentId?.let { allAccounts[it] }
                val debitAccountName = parentAccount?.accountName ?: account?.accountName ?: ""
                val debitSubAccountName = if (parentAccount != null) account?.accountName ?: "" else ""
                GeneralReceiptOutputItem(
                    itemId = item.id,
                    receiptId = item.receiptId,
                    date = receipt?.date ?: "",
                    storeName = receipt?.storeName ?: "",
                    itemName = item.itemName,
                    price = item.price,
                    accountName = debitAccountName,
                    accountCode = account?.accountCode ?: "",
                    debitSubAccountName = debitSubAccountName,
                    defaultTaxCategory = account?.defaultTaxCategory ?: "対象外"
                )
            }
        }

    suspend fun buildCsvForExport(from: String?, to: String?, accounts: List<YayoiAccount>): String =
        withContext(Dispatchers.IO) {
            val items = dao.getItemsForExport(from, to).filter { !it.isExcluded }
            val accountMap = accounts.associateBy { it.id }
            buildString {
                appendLine("日付,店舗名,商品名,金額,勘定科目,科目コード")
                items.forEach { item ->
                    val receipt = dao.getReceiptById(item.receiptId)
                    val date = receipt?.date?.replace("-", "/") ?: ""
                    val store = receipt?.storeName ?: ""
                    val account = resolveEffectiveAccountId(item)?.let { accountMap[it] }
                    val accountName = account?.accountName ?: ""
                    val accountCode = account?.accountCode ?: ""
                    appendLine(
                        listOf(
                            date,
                            store,
                            item.itemName,
                            item.price.toString(),
                            accountName,
                            accountCode
                        ).joinToString(",") { CsvUtils.escapeCsvField(it) }
                    )
                }
            }
        }

    private fun isNetworkAvailable(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }
}
