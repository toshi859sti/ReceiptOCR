package com.example.greenframeocr.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.ReceiptDatabase
import com.example.greenframeocr.util.GeminiRateLimitException
import com.example.greenframeocr.util.GeminiReceiptClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GeneralReceiptViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ReceiptDatabase.getDatabase(application)
    private val dao = db.generalReceiptDao()
    private val prefs = AppPreferences(application)

    val receipts: StateFlow<List<GeneralReceipt>> =
        dao.getAllReceipts().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _pendingReceipt = MutableStateFlow<GeneralReceipt?>(null)
    val pendingReceipt: StateFlow<GeneralReceipt?> = _pendingReceipt

    private val _pendingItems = MutableStateFlow<List<GeneralReceiptItem>>(emptyList())
    val pendingItems: StateFlow<List<GeneralReceiptItem>> = _pendingItems

    sealed class UiState {
        object Idle : UiState()
        object OcrRunning : UiState()
        object GeminiRunning : UiState()
        object GeminiUnavailable : UiState()
        data class Done(val receiptId: Long) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    fun onOcrCompleted(ocrText: String) {
        viewModelScope.launch {
            _uiState.value = UiState.OcrRunning
            val apiKey = prefs.geminiApiKey
            val isOnline = isNetworkAvailable()

            if (apiKey.isNotBlank() && isOnline) {
                _uiState.value = UiState.GeminiRunning
                try {
                    val result = GeminiReceiptClient.parseReceipt(ocrText, apiKey)
                    if (result != null) {
                        _pendingReceipt.value = GeneralReceipt(
                            date = result.date,
                            storeName = result.storeName,
                            total = result.total,
                            rawOcrText = ocrText,
                            geminiUsed = true
                        )
                        _pendingItems.value = result.items.map { item ->
                            GeneralReceiptItem(receiptId = 0, itemName = item.name, price = item.price)
                        }
                        _uiState.value = UiState.Idle
                    } else {
                        fallbackToRawOcr(ocrText)
                    }
                } catch (e: GeminiRateLimitException) {
                    fallbackToRawOcr(ocrText)
                    _uiState.value = UiState.Error(e.message ?: "利用上限エラー")
                }
            } else {
                _uiState.value = UiState.GeminiUnavailable
                fallbackToRawOcr(ocrText)
            }
        }
    }

    private fun fallbackToRawOcr(ocrText: String) {
        _pendingReceipt.value = GeneralReceipt(
            date = "",
            storeName = "",
            total = 0,
            rawOcrText = ocrText,
            geminiUsed = false
        )
        _pendingItems.value = emptyList()
        _uiState.value = UiState.Idle
    }

    fun saveReceipt(receipt: GeneralReceipt, items: List<GeneralReceiptItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val receiptId = dao.insertReceipt(receipt)
            val itemsWithId = items.map { it.copy(receiptId = receiptId) }
            dao.insertItems(itemsWithId)
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
                } else {
                    _uiState.value = UiState.Error("Gemini画像解析に失敗しました")
                }
            } catch (e: GeminiRateLimitException) {
                _uiState.value = UiState.Error(e.message ?: "利用上限エラー")
            }
        }
    }

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

    suspend fun buildCsvForExport(from: String?, to: String?): String =
        withContext(Dispatchers.IO) {
            val items = dao.getItemsForExport(from, to)
            buildString {
                appendLine("ID,日付,摘要,メモ,金額")
                items.forEach { item ->
                    val receipt = dao.getReceiptById(item.receiptId)
                    val date = receipt?.date?.replace("-", "/") ?: ""
                    appendLine("${item.id},$date,,${item.itemName},${item.price}")
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
