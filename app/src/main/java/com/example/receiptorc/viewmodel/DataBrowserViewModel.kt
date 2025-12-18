package com.example.receiptorc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.receiptorc.data.MonthlyData
import com.example.receiptorc.data.ReceiptDao
import com.example.receiptorc.data.ReceiptItem
import com.example.receiptorc.data.SheetData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * データ閲覧画面のViewModel
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataBrowserViewModel(
    private val dao: ReceiptDao
) : ViewModel() {

    // 選択中の年・月
    private val _selectedYear = MutableStateFlow(2024)
    val selectedYear: StateFlow<Int> = _selectedYear.asStateFlow()

    private val _selectedMonth = MutableStateFlow(1)
    val selectedMonth: StateFlow<Int> = _selectedMonth.asStateFlow()

    // 月次データ
    val monthlyData: StateFlow<MonthlyData?> = combine(
        _selectedYear,
        _selectedMonth
    ) { year, month ->
        dao.getMonthlyData("${year}_$month")
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // 伝票アイテム
    val receiptItems: StateFlow<List<ReceiptItem>> = combine(
        _selectedYear,
        _selectedMonth
    ) { year, month ->
        dao.getReceiptItemsByMonth(year, month)
    }.flatMapLatest { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 伝票データ
    val sheetDataList: StateFlow<List<SheetData>> = combine(
        _selectedYear,
        _selectedMonth
    ) { year, month ->
        dao.getSheetDataByMonth(year, month)
    }.flatMapLatest { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 年を設定
     */
    fun setYear(year: Int) {
        _selectedYear.value = year
    }

    /**
     * 月を設定
     */
    fun setMonth(month: Int) {
        if (month in 1..12) {
            _selectedMonth.value = month
        }
    }

    /**
     * 前の月へ移動
     */
    fun previousMonth() {
        if (_selectedMonth.value > 1) {
            _selectedMonth.value--
        } else {
            _selectedMonth.value = 12
            _selectedYear.value--
        }
    }

    /**
     * 次の月へ移動
     */
    fun nextMonth() {
        if (_selectedMonth.value < 12) {
            _selectedMonth.value++
        } else {
            _selectedMonth.value = 1
            _selectedYear.value++
        }
    }
}

/**
 * 表示用のデータ行
 */
data class ReceiptDisplayRow(
    val date: String,
    val productName: String,
    val amount: Int,
    val category: String,
    val sheetNumber: Int,
    val subtotal: Int?,
    val isSubtotalRow: Boolean,
    val itemId: Long?
)
