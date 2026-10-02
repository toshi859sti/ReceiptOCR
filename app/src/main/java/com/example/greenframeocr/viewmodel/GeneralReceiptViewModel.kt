package com.example.greenframeocr.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboAccountUsage
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.GeneralItemGroup
import com.example.greenframeocr.data.GeneralItemGroupYearCount
import com.example.greenframeocr.data.GeneralItemMaster
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.InvoiceStore
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.data.ReceiptItemPreview
import com.example.greenframeocr.data.ReceiptPaymentMethodRule
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.AoiroChoboReceiptRules
import com.example.greenframeocr.util.AoiroChoboTransactionsBuilder
import com.example.greenframeocr.util.AoiroChoboUsageRules
import com.example.greenframeocr.util.CsvUtils
import com.example.greenframeocr.util.GeminiApiException
import com.example.greenframeocr.util.GeminiApiKeyMissingException
import com.example.greenframeocr.util.GeminiQuotaExhaustedException
import com.example.greenframeocr.util.GeminiRateLimitException
import com.example.greenframeocr.util.GeminiReceiptClient
import com.example.greenframeocr.util.NumericPrefixCandidate
import com.example.greenframeocr.util.SimilarGroupPair
import com.example.greenframeocr.util.findNumericPrefixCandidates
import com.example.greenframeocr.util.findSimilarGroupPairs
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
    val counterAccountName: String = "現金",  // 相手科目（貸方勘定科目）名。弥生CSV出力でのみ使用
    val exportedAt: String? = null,  // 直近のCSV出力日時。未出力ならnull
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

    /** グループ×年の明細数。商品名・但し書きリストを年で絞るのに使う（グループの設定そのものは全年で 1 つ） */
    val itemGroupYearCounts: StateFlow<List<GeneralItemGroupYearCount>> =
        dao.getItemGroupYearCounts().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    /** あおいろの AI 提案 1 件。AI が決めるのは科目だけで、摘要は空欄にして農家が選ぶ（空欄のままでもよい） */
    data class AoiroAiSuggestion(
        val canonicalKey: String,
        val itemName: String,
        val accountKey: String,
        val accountName: String,
        val reason: String
    )
    /** null = 提案ダイアログを出さない。空リスト = 提案なし */
    private val _aoiroAiSuggestions = MutableStateFlow<List<AoiroAiSuggestion>?>(null)
    val aoiroAiSuggestions: StateFlow<List<AoiroAiSuggestion>?> = _aoiroAiSuggestions

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
                result?.usageStats?.let { prefs.addTokenUsage(it.promptTokens, it.candidatesTokens, it.totalTokens) }
                if (result != null) {
                    _pendingReceipt.value = GeneralReceipt(
                        date = result.date,
                        storeName = result.storeName,
                        total = result.total,
                        rawOcrText = "",
                        geminiUsed = true,
                        paymentMethodText = result.paymentMethodText
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
            // 弥生の科目を変えても AoiroChobo 側の紐付けは別物なので消さない
            // （同じ商品でも弥生で A、あおいろで B を選ぶことがある）
            val existing = db.generalItemMasterDao().getByKey(canonicalKey)
            db.generalItemMasterDao().upsert(
                existing?.copy(yayoiAccountId = accountId)
                    ?: GeneralItemMaster(canonicalKey, accountId)
            )
            dao.clearOverridesForGroup(canonicalKey)
        }
    }

    /**
     * グループのあおいろの科目・摘要を変更する。弥生の科目とは独立なので弥生側（科目・個別上書き）には触らない。
     * グループ内のあおいろの個別上書きは全解除する（弥生の [updateGroupDefaultAccount] と同じ）
     */
    fun updateGroupAoiro(
        canonicalKey: String,
        accountKey: String?,
        accountKeyName: String?,
        memoKey: String?,
        memoKeyName: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.generalItemMasterDao().getByKey(canonicalKey) ?: GeneralItemMaster(canonicalKey)
            db.generalItemMasterDao().upsert(
                existing.copy(
                    accountKey = accountKey,
                    accountKeyName = accountKeyName,
                    memoKey = memoKey,
                    memoKeyName = memoKeyName
                )
            )
            dao.clearAoiroOverridesForGroup(canonicalKey)
        }
    }

    /** 明細 1 件だけあおいろの科目・摘要を上書きする。accountKey=null でグループの設定に戻す */
    fun updateItemAoiroOverride(
        itemId: Long,
        accountKey: String?,
        accountKeyName: String?,
        memoKey: String?,
        memoKeyName: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateAoiroOverrideForItem(
                itemId, accountKey, accountKeyName?.takeIf { accountKey != null },
                memoKey?.takeIf { accountKey != null }, memoKeyName?.takeIf { accountKey != null && memoKey != null }
            )
        }
    }

    /** あおいろの科目・摘要・用途の絞り込み（あおいろモードの画面用） */
    data class AoiroVocab(
        val accounts: List<AoiroChoboAccount>,
        val memos: List<AoiroChoboMemoTemplate>,
        val usage: List<AoiroChoboAccountUsage>
    )

    suspend fun loadAoiroVocab(): AoiroVocab = withContext(Dispatchers.IO) {
        AoiroVocab(
            accounts = db.aoiroChoboVocabDao().getAllAccounts(),
            memos = db.aoiroChoboVocabDao().getAllMemoTemplates(),
            usage = db.aoiroChoboAccountUsageDao().getAll()
        )
    }

    /**
     * 選んだ品目からあおいろの transactions.json を組み立てる。vocabulary 未取込なら例外。
     * itemIndex はレシートの全品目（経費対象外も含む）を id 順に並べた位置（契約 §4）
     */
    suspend fun buildAoiroReceiptJson(itemIds: List<Long>, appVersion: String): AoiroChoboTransactionsBuilder.Result =
        withContext(Dispatchers.IO) {
            val vocabDao = db.aoiroChoboVocabDao()
            val meta = vocabDao.getMeta() ?: error("あおいろ帳簿の科目・摘要がまだ取り込まれていません")
            val wanted = itemIds.toSet()
            val allItems = dao.getAllItemsOnce()
            val itemsByReceipt = allItems.groupBy { it.receiptId }
            val receiptsById = dao.getAllReceiptsOnce().associateBy { it.id }
            val groups = db.generalItemMasterDao().getAll().associateBy { it.canonicalKey }
            // 出力確認画面と同じ並び（日付 → 品目 id）
            val rows = allItems.filter { it.id in wanted }
                .mapNotNull { item ->
                    val receipt = receiptsById[item.receiptId] ?: return@mapNotNull null
                    val index = itemsByReceipt[item.receiptId].orEmpty().sortedBy { it.id }.indexOfFirst { it.id == item.id }
                    AoiroChoboTransactionsBuilder.ReceiptRow(receipt, item, index, groups[item.canonicalKey])
                }
                .sortedWith(compareBy({ it.receipt.date }, { it.item.id }))
            AoiroChoboTransactionsBuilder.buildReceipt(
                rows = rows,
                paymentRules = db.receiptPaymentMethodRuleDao().getAll(),
                accounts = vocabDao.getAllAccounts(),
                memos = vocabDao.getAllMemoTemplates(),
                vocabMeta = meta,
                appVersion = appVersion,
                defaultPaymentKey = prefs.aoiroReceiptDefaultPaymentKey
            )
        }

    /** あおいろモードの出力確認に出す「科目 ／ 摘要」（品目 id → 表示。今の辞書の名前） */
    suspend fun loadAoiroReceiptLabels(): Map<Long, String> = withContext(Dispatchers.IO) {
        val accountNames = db.aoiroChoboVocabDao().getAllAccounts().associate { it.accountKey to it.name }
        val memoNames = db.aoiroChoboVocabDao().getAllMemoTemplates().associate { it.memoKey to it.name }
        val groups = db.generalItemMasterDao().getAll().associateBy { it.canonicalKey }
        dao.getAllItemsOnce().associate { item ->
            val link = AoiroChoboTransactionsBuilder.linkForReceiptItem(item, groups[item.canonicalKey])
            item.id to listOfNotNull(
                link.accountKey?.let { accountNames[it] },
                link.memoKey?.let { memoNames[it] }
            ).joinToString(" ／ ")
        }
    }

    /**
     * このレシートのあおいろの支払方法の科目名（出力と同じ決め方）。決まらなければ null（PC には「科目なし」で送る）。
     * 辞書から消えた上書き・ルールの科目も null
     */
    suspend fun resolveAoiroPaymentNameForReceipt(receipt: GeneralReceipt): String? =
        withContext(Dispatchers.IO) { resolveAoiroPayment(receipt).second }

    /** このレシートのあおいろの支払方法の科目（出力と同じ決め方）。明細の個別変更で摘要の帳簿を決めるのに使う */
    suspend fun aoiroPaymentAccountForReceipt(receiptId: Long): AoiroChoboAccount? =
        withContext(Dispatchers.IO) { dao.getReceiptById(receiptId)?.let { resolveAoiroPayment(it).first } }

    private suspend fun resolveAoiroPayment(receipt: GeneralReceipt): Pair<AoiroChoboAccount?, String?> {
        val accounts = db.aoiroChoboVocabDao().getAllAccounts()
        return AoiroChoboTransactionsBuilder.resolveReceiptPayment(
            receipt,
            db.receiptPaymentMethodRuleDao().getAll(),
            accounts.associateBy { it.accountKey },
            AoiroChoboTransactionsBuilder.defaultPayment(accounts, prefs.aoiroReceiptDefaultPaymentKey)
        )
    }

    /** あおいろ：支払方法のルールに当たらないときの科目（`accountKey`）。null = 現金 */
    var aoiroReceiptDefaultPaymentKey: String?
        get() = prefs.aoiroReceiptDefaultPaymentKey
        set(value) { prefs.aoiroReceiptDefaultPaymentKey = value }

    /** 既定の支払方法の科目（選んでいなければ現金）。今の辞書に無ければ null */
    fun aoiroDefaultPayment(accounts: List<AoiroChoboAccount>): AoiroChoboAccount? =
        AoiroChoboTransactionsBuilder.defaultPayment(accounts, prefs.aoiroReceiptDefaultPaymentKey)

    /**
     * 一覧用：レシート id → 今効いている支払方法の科目名（出力と同じ決め方）。
     * あおいろで決まらないものは null（PC には「科目なし」で送る）
     */
    suspend fun resolvePaymentAccountNames(
        receipts: List<GeneralReceipt>,
        isAoiro: Boolean
    ): Map<Long, String?> = withContext(Dispatchers.IO) {
        val rules = db.receiptPaymentMethodRuleDao().getAll()
        if (isAoiro) {
            val accounts = db.aoiroChoboVocabDao().getAllAccounts()
            val byKey = accounts.associateBy { it.accountKey }
            val fallback = aoiroDefaultPayment(accounts)
            receipts.associate { r ->
                r.id to AoiroChoboTransactionsBuilder.resolveReceiptPayment(r, rules, byKey, fallback).second
            }
        } else {
            val accountsById = db.yayoiAccountDao().getAll().associateBy { it.id }
            receipts.associate { r -> r.id to resolveCounterAccountName(r, rules, accountsById) }
        }
    }

    /** レシート単位のあおいろの支払方法の科目の上書き。accountKey=null でルール判定に戻す */
    fun updateReceiptPaymentAccountKeyOverride(receiptId: Long, accountKey: String?, accountKeyName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updatePaymentAccountKeyOverride(receiptId, accountKey, accountKeyName?.takeIf { accountKey != null })
        }
    }

    /** グループ内の個別明細だけ科目を上書きする。accountId=nullでグループのデフォルトに戻す */
    fun updateItemOverride(itemId: Long, accountId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateAccountForItem(itemId, accountId)
        }
    }

    fun getItemsByCanonicalKey(canonicalKey: String) = dao.getItemsByCanonicalKey(canonicalKey)

    private val _similarGroupPairs = MutableStateFlow<List<SimilarGroupPair>>(emptyList())
    val similarGroupPairs: StateFlow<List<SimilarGroupPair>> = _similarGroupPairs

    private val _isFindingSimilarGroups = MutableStateFlow(false)
    val isFindingSimilarGroups: StateFlow<Boolean> = _isFindingSimilarGroups

    /** OCR誤読でcanonicalKeyが完全一致しなかった別グループを編集距離で検出する（候補提示のみ） */
    fun findSimilarGroups(groups: List<GeneralItemGroup>) {
        viewModelScope.launch {
            _isFindingSimilarGroups.value = true
            _similarGroupPairs.value = withContext(Dispatchers.Default) { findSimilarGroupPairs(groups) }
            _isFindingSimilarGroups.value = false
        }
    }

    fun clearSimilarGroupPairs() {
        _similarGroupPairs.value = emptyList()
    }

    /** 承認された統合候補を実行する。merge側の明細をkeep側のcanonicalKeyへ付け替え、
     *  merge側のグループデフォルト設定は破棄する（個別上書きの値はそのまま持ち越す） */
    fun mergeGroups(pairs: List<SimilarGroupPair>) {
        viewModelScope.launch(Dispatchers.IO) {
            pairs.forEach { pair ->
                dao.reassignCanonicalKey(pair.merge.canonicalKey, pair.keep.canonicalKey)
                db.generalItemMasterDao().deleteByKey(pair.merge.canonicalKey)
            }
        }
        _similarGroupPairs.value = emptyList()
    }

    /** グループ内の全明細の品目名を一括リネームする（レジ番号等のノイズ除去用）。
     *  canonicalKeyも新品目名から再計算し直すため、リネーム後に既存の別グループへ吸収される
     *  こともある（その場合、統合先に既存のデフォルト科目がなければ元のデフォルトを引き継ぐ） */
    fun renameGroup(oldCanonicalKey: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { performRenameGroup(oldCanonicalKey, trimmed) }
    }

    private suspend fun performRenameGroup(oldCanonicalKey: String, newName: String) {
        val newKey = toCanonicalKey(newName)
        dao.renameGroupItems(oldCanonicalKey, newKey, newName)
        if (newKey != oldCanonicalKey) {
            val oldMaster = db.generalItemMasterDao().getByKey(oldCanonicalKey)
            val existingMaster = db.generalItemMasterDao().getByKey(newKey)
            if (existingMaster == null && oldMaster != null) {
                // 品目名を直しただけなので、弥生・AoiroChobo どちらの紐付けも引き継ぐ
                db.generalItemMasterDao().upsert(oldMaster.copy(canonicalKey = newKey))
            }
            db.generalItemMasterDao().deleteByKey(oldCanonicalKey)
        }
    }

    private val _numericPrefixCandidates = MutableStateFlow<List<NumericPrefixCandidate>>(emptyList())
    val numericPrefixCandidates: StateFlow<List<NumericPrefixCandidate>> = _numericPrefixCandidates

    private val _isFindingNumericPrefixes = MutableStateFlow(false)
    val isFindingNumericPrefixes: StateFlow<Boolean> = _isFindingNumericPrefixes

    /** 品目名先頭の数字接頭辞（伝票行番号らしきノイズ）を正規表現で検出する（候補提示のみ） */
    fun findNumericPrefixes(groups: List<GeneralItemGroup>) {
        viewModelScope.launch {
            _isFindingNumericPrefixes.value = true
            _numericPrefixCandidates.value = withContext(Dispatchers.Default) { findNumericPrefixCandidates(groups) }
            _isFindingNumericPrefixes.value = false
        }
    }

    fun clearNumericPrefixCandidates() {
        _numericPrefixCandidates.value = emptyList()
    }

    /** 承認された数字接頭辞除去候補を順番に適用する（同じ除去結果に集約されるケースの
     *  競合を避けるため、performRenameGroupを1つのコルーチン内で逐次実行する） */
    fun applyNumericPrefixCleanup(candidates: List<NumericPrefixCandidate>) {
        viewModelScope.launch(Dispatchers.IO) {
            candidates.forEach { performRenameGroup(it.group.canonicalKey, it.cleanedName) }
        }
        _numericPrefixCandidates.value = emptyList()
    }

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
                result.usageStats?.let { prefs.addTokenUsage(it.promptTokens, it.candidatesTokens, it.totalTokens) }
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

    /**
     * 未マッチの品目グループに、あおいろの科目を AI に提案させる（弥生版 [suggestAccountsForItems] のあおいろ版）。
     * 弥生版と違って既に科目のあるグループは送らない（JA 購買・預金のあおいろ版と同じ。承認すると個別変更が消えるため）
     */
    fun suggestAoiroAccountsForItems(groups: List<GeneralItemGroup>, vocab: AoiroVocab) {
        val unmatched = groups.filter { it.accountKey == null }.take(60)
        if (unmatched.isEmpty()) {
            _aiError.value = "未マッチの品目がありません"
            return
        }
        val accounts = AoiroChoboUsageRules.candidates(AoiroChoboUsageRules.Usage.RECEIPT, vocab.accounts, vocab.usage)
        if (accounts.isEmpty()) {
            _aiError.value = "あおいろ帳簿の科目がまだ取り込まれていません。設定画面から取り込んでください"
            return
        }
        // 摘要名のヒントは、品目グループの摘要と同じくレシート共通のもの
        val memoNames = accounts.associate { a ->
            a.accountKey to AoiroChoboReceiptRules.groupMemoCandidates(a.accountKey, vocab.memos).map { it.name }
        }
        viewModelScope.launch {
            _isAiMatching.value = true
            _aiError.value = null
            try {
                val result = GeminiReceiptClient.matchReceiptItemsToAoiroAccounts(
                    itemNames = unmatched.map { it.itemName },
                    accounts = accounts,
                    memoNamesByAccount = memoNames,
                    apiKey = prefs.geminiApiKey
                )
                val byKey = vocab.accounts.associateBy { it.accountKey }
                _aoiroAiSuggestions.value = result.matches.mapNotNull { m ->
                    val group = unmatched.getOrNull(m.itemIndex) ?: return@mapNotNull null
                    val account = byKey[m.accountKey] ?: return@mapNotNull null
                    AoiroAiSuggestion(
                        canonicalKey = group.canonicalKey,
                        itemName = group.itemName,
                        accountKey = account.accountKey,
                        accountName = account.name,
                        reason = m.reason
                    )
                }
                _aiUsageStats.value = result.usageStats
                result.usageStats?.let { prefs.addTokenUsage(it.promptTokens, it.candidatesTokens, it.totalTokens) }
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

    /** 承認したあおいろの提案を品目グループに保存する（手で設定したときと同じく、グループ内の個別変更は外れる） */
    fun applyAoiroSuggestions(accepted: List<AoiroAiSuggestion>) {
        accepted.forEach { s ->
            updateGroupAoiro(s.canonicalKey, s.accountKey, s.accountName, null, null)
        }
        clearAiSuggestions()
    }

    fun clearAiSuggestions() {
        _aoiroAiSuggestions.value = null
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

    /** レシートの明細（変更を追う）。詳細画面で科目・摘要を変えたあと、表示をすぐ合わせるのに使う */
    fun itemsForReceiptFlow(receiptId: Long) = dao.getItemsByReceiptId(receiptId)

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
                else {
                    // 既存の明細の item.canonicalKey は改名前のまま。改名でグループが変わったら設定を引き継ぐ
                    if (keyed.canonicalKey != item.canonicalKey) carryOverGroupSettings(item.canonicalKey, keyed.canonicalKey)
                    dao.updateItem(keyed)
                }
            }
        }
    }

    /**
     * 品目名を直して別のグループ（canonicalKey）に移った明細の科目設定を引き継ぐ。
     * 移り先にまだ設定（弥生の科目・あおいろの科目）が無ければ、移る前のグループの設定を写す。
     * 移り先に設定があればそちらを使う（既にある品目に合流したのと同じ）。明細の個別変更は明細側に残る
     */
    private suspend fun carryOverGroupSettings(fromKey: String, toKey: String) {
        if (toKey.isBlank()) return
        val masterDao = db.generalItemMasterDao()
        val from = masterDao.getByKey(fromKey) ?: return
        if (from.yayoiAccountId == null && from.accountKey == null) return
        val to = masterDao.getByKey(toKey)
        if (to != null && (to.yayoiAccountId != null || to.accountKey != null)) return
        masterDao.upsert(from.copy(canonicalKey = toKey))
    }

    /** 個別上書き（item.yayoiAccountId）があればそちら優先、なければグループのデフォルトを使う */
    private suspend fun resolveEffectiveAccountId(item: GeneralReceiptItem): Long? {
        item.yayoiAccountId?.let { return it }
        return db.generalItemMasterDao().getByKey(item.canonicalKey)?.yayoiAccountId
    }

    /** 支払方法テキスト・個別上書きから相手科目（貸方勘定科目）名を決定する。
     *  優先順：個別上書き > ReceiptPaymentMethodRuleの部分一致（sortOrder順） > 「現金」科目 > 固定文字列"現金"。
     *  弥生の科目を持たないルール（あおいろモードで足したもの）は飛ばす。当たっても現金にせず次のルールを見る */
    private fun resolveCounterAccountName(
        receipt: GeneralReceipt?,
        rules: List<ReceiptPaymentMethodRule>,
        accountsById: Map<Long, YayoiAccount>
    ): String {
        receipt?.paymentAccountOverride?.let { id -> accountsById[id]?.accountName?.let { return it } }
        val text = receipt?.paymentMethodText
        if (!text.isNullOrBlank()) {
            val rule = rules.firstOrNull { it.yayoiAccountId != null && text.contains(it.keyword, ignoreCase = true) }
            rule?.yayoiAccountId?.let { id -> accountsById[id]?.accountName?.let { name -> return name } }
        }
        return accountsById.values.firstOrNull { it.accountName == "現金" }?.accountName ?: "現金"
    }

    /** レシート詳細画面用：このレシートの支払方法の科目名を解決する（ルール一覧・科目一覧を都度読み込む） */
    suspend fun resolveCounterAccountNameForReceipt(receipt: GeneralReceipt): String =
        withContext(Dispatchers.IO) {
            val rules = db.receiptPaymentMethodRuleDao().getAll()
            val accountsById = db.yayoiAccountDao().getAll().associateBy { it.id }
            resolveCounterAccountName(receipt, rules, accountsById)
        }

    suspend fun loadOutputItems(): List<GeneralReceiptOutputItem> =
        withContext(Dispatchers.IO) {
            // 経費対象外にマークした品目（isExcluded）は出力候補から除外する
            // （buildCsvForExport と挙動を揃える）
            val allItems = dao.getItemsForExport(null, null).filter { !it.isExcluded }
            val allAccounts = db.yayoiAccountDao().getAll().associateBy { it.id }
            val rules = db.receiptPaymentMethodRuleDao().getAll()
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
                    defaultTaxCategory = account?.defaultTaxCategory ?: "対象外",
                    counterAccountName = resolveCounterAccountName(receipt, rules, allAccounts),
                    exportedAt = item.exportedAt
                )
            }
        }

    /** CSV出力後、実際に出力された明細に出力日時を記録する */
    suspend fun markItemsExported(itemIds: List<Long>, exportedAt: String) {
        withContext(Dispatchers.IO) { dao.markExported(itemIds, exportedAt) }
    }

    // ─── 支払方法→相手科目ルール ────────────────────────────────────────

    suspend fun loadPaymentMethodRules(): List<ReceiptPaymentMethodRule> =
        withContext(Dispatchers.IO) { db.receiptPaymentMethodRuleDao().getAll() }

    // 書き終わってから一覧を読み直せるよう suspend にしている（launch だと読み直しが先に走り、古い行が表示されることがあった）
    suspend fun savePaymentMethodRule(rule: ReceiptPaymentMethodRule) {
        withContext(Dispatchers.IO) {
            if (rule.id == 0L) db.receiptPaymentMethodRuleDao().insert(rule)
            else db.receiptPaymentMethodRuleDao().update(rule)
        }
    }

    suspend fun deletePaymentMethodRule(rule: ReceiptPaymentMethodRule) {
        withContext(Dispatchers.IO) { db.receiptPaymentMethodRuleDao().delete(rule) }
    }

    /** レシート単位の相手科目個別上書き。accountId=nullでルール判定に戻す */
    fun updateReceiptPaymentAccountOverride(receiptId: Long, accountId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updatePaymentAccountOverride(receiptId, accountId)
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
