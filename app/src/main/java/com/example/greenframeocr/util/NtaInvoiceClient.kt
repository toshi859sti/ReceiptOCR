package com.example.greenframeocr.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object NtaInvoiceClient {

    // 国税庁 適格請求書発行事業者公表システム Web-API
    // 公式: https://www.invoice-kohyo.nta.go.jp/web-api/index.html
    // id=アプリケーションID（要申請）、未設定でも動作するケースあり
    private const val API_BASE_URL =
        "https://web-api.invoice-kohyo.nta.go.jp/1/num"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val registrationNumberRegex = Regex("T\\d{13}")

    data class InvoiceInfo(
        val registrationNumber: String,
        val storeName: String,
        val address: String
    )

    /** OCRテキストから登録番号（T + 13桁）を抽出する */
    fun extractRegistrationNumber(text: String): String? =
        registrationNumberRegex.find(text)?.value

    /**
     * 登録番号で国税庁APIを照会する
     * ローカルキャッシュは ViewModel 側で管理する
     * @param registrationNumber "T" + 13桁数字
     * @param applicationId アプリケーションID（未設定時は空文字）
     */
    suspend fun lookup(registrationNumber: String, applicationId: String = ""): InvoiceInfo? = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("$API_BASE_URL?number=$registrationNumber&type=21")
                if (applicationId.isNotBlank()) append("&id=$applicationId")
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("NtaInvoiceClient", "HTTP ${response.code} for $registrationNumber")
                return@withContext null
            }
            val body = response.body?.string() ?: return@withContext null
            Log.d("NtaInvoiceClient", "Response: ${body.take(300)}")
            parseResponse(registrationNumber, body)
        } catch (e: Exception) {
            Log.e("NtaInvoiceClient", "Lookup failed for $registrationNumber: ${e.message}")
            null
        }
    }

    private fun parseResponse(registrationNumber: String, json: String): InvoiceInfo? {
        return try {
            val root = JSONObject(json)
            val code = root.optInt("code", -1)
            if (code != 0) {
                Log.w("NtaInvoiceClient", "API error code=$code for $registrationNumber")
                return null
            }
            val announcements = root.optJSONArray("announcement") ?: return null
            if (announcements.length() == 0) return null
            val first = announcements.getJSONObject(0)
            val name = first.optString("name", "").ifBlank { return null }
            val address = first.optString("address", "")
            InvoiceInfo(registrationNumber, name, address)
        } catch (e: Exception) {
            Log.e("NtaInvoiceClient", "Parse failed: ${e.message}\nJSON: ${json.take(300)}")
            null
        }
    }
}
