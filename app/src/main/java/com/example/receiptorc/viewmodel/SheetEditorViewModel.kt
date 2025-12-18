package com.example.receiptorc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.receiptorc.data.ReceiptDao
import com.example.receiptorc.data.ReceiptItem
import com.example.receiptorc.data.SheetData
import com.example.receiptorc.util.Category
import com.example.receiptorc.util.ValidationUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 伝票編集画面のViewModel
 */
class SheetEditorViewModel(
    private val dao: ReceiptDao,
    private val issueYear: Int,
    private val issueMonth: Int,
    private val sheetNumber: Int
) : ViewModel() {

    // 編集モード
    private val _isOcrOverwriteMode = MutableStateFlow(false)
    val isOcrOverwriteMode: StateFlow<Boolean> = _isOcrOverwriteMode.asStateFlow()

    // 伝票アイテム
    private val _receiptItems = MutableStateFlow<List<ReceiptItem>>(emptyList())
    val receiptItems: StateFlow<List<ReceiptItem>> = _receiptItems.asStateFlow()

    // 伝票データ
    private val _sheetData = MutableStateFlow<SheetData?>(null)
    val sheetData: StateFlow<SheetData?> = _sheetData.asStateFlow()

    init {
        loadData()
    }

    /**
     * データを読み込む
     */
    private fun loadData() {
        viewModelScope.launch {
            // 伝票アイテムを読み込む
            _receiptItems.value = dao.getReceiptItemsBySheet(issueYear, issueMonth, sheetNumber)

            // 伝票データを読み込む
            val loadedSheetData = dao.getSheetData(issueYear, issueMonth, sheetNumber)
            _sheetData.value = loadedSheetData ?: SheetData(
                issueYear = issueYear,
                issueMonth = issueMonth,
                sheetNumber = sheetNumber,
                totalFromInput = null,
                subtotalGeneral = null,
                subtotalGas = null,
                subtotalAgri = null
            )
        }
    }

    /**
     * 編集モードを切り替える
     */
    fun toggleEditMode() {
        _isOcrOverwriteMode.value = !_isOcrOverwriteMode.value
    }

    /**
     * アイテムを更新
     */
    fun updateItem(item: ReceiptItem) {
        val index = _receiptItems.value.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            val newList = _receiptItems.value.toMutableList()
            newList[index] = item
            _receiptItems.value = newList
        }
    }

    /**
     * アイテムの上書き対象フラグを切り替え
     */
    fun toggleItemOverwriteTarget(itemId: Long) {
        val index = _receiptItems.value.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val item = _receiptItems.value[index]
            val newList = _receiptItems.value.toMutableList()
            newList[index] = item.copy(isOcrOverwriteTarget = !item.isOcrOverwriteTarget)
            _receiptItems.value = newList
        }
    }

    /**
     * 新しい行を追加
     */
    fun addNewItem() {
        val newItemNumber = (_receiptItems.value.maxOfOrNull { it.itemNumber } ?: 0) + 1
        val newItem = ReceiptItem(
            issueYear = issueYear,
            issueMonth = issueMonth,
            sheetNumber = sheetNumber,
            itemNumber = newItemNumber,
            receiptYear = issueYear,
            receiptMonth = issueMonth,
            receiptDay = 1,
            productName = "",
            amount = 0,
            category = Category.UNCLASSIFIED
        )
        _receiptItems.value = _receiptItems.value + newItem
    }

    /**
     * 小計・合計を更新
     */
    fun updateSheetData(newSheetData: SheetData) {
        _sheetData.value = newSheetData
    }

    /**
     * 小計・合計の上書き対象フラグを切り替え
     */
    fun toggleSubtotalOverwriteTarget(field: String) {
        _sheetData.value?.let { current ->
            _sheetData.value = when (field) {
                "general" -> current.copy(isSubtotalGeneralOcrTarget = !current.isSubtotalGeneralOcrTarget)
                "gas" -> current.copy(isSubtotalGasOcrTarget = !current.isSubtotalGasOcrTarget)
                "agri" -> current.copy(isSubtotalAgriOcrTarget = !current.isSubtotalAgriOcrTarget)
                "total" -> current.copy(isTotalOcrTarget = !current.isTotalOcrTarget)
                else -> current
            }
        }
    }

    /**
     * 整合性チェック
     */
    fun validateData(): com.example.receiptorc.util.ValidationResult {
        return ValidationUtils.validateSheet(_receiptItems.value, _sheetData.value!!)
    }

    /**
     * データを保存
     */
    suspend fun saveData(): Boolean {
        return try {
            // アイテムを保存
            dao.updateReceiptItems(_receiptItems.value)

            // 伝票データを保存
            _sheetData.value?.let {
                dao.insertSheetData(it)
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 前の伝票へ移動できるか
     */
    fun canMoveToPreviousSheet(): Boolean = sheetNumber > 1

    /**
     * 次の伝票へ移動できるか
     */
    suspend fun canMoveToNextSheet(): Boolean {
        val nextSheetItems = dao.getReceiptItemsBySheet(issueYear, issueMonth, sheetNumber + 1)
        return nextSheetItems.isNotEmpty()
    }
}
