package com.example.greenframeocr.util

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiRateLimitException : Exception("APIの利用上限に達しました。しばらく待ってから再試行してください")

object GeminiReceiptClient {

    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    data class ReceiptParseResult(
        val storeName: String,
        val date: String,
        val items: List<ParsedItem>,
        val total: Int
    )

    data class ParsedItem(
        val name: String,
        val price: Int
    )

    suspend fun parseReceiptFromImage(bitmap: Bitmap, apiKey: String): ReceiptParseResult? =
        withContext(Dispatchers.IO) {
            val scaled = scaleBitmap(bitmap, 1600)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                            put(JSONObject().apply { put("text", buildImagePrompt()) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0)
                })
            }
            executeRequest(requestBody, apiKey)
        }

    suspend fun parseReceipt(ocrText: String, apiKey: String): ReceiptParseResult? =
        withContext(Dispatchers.IO) {
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", buildPrompt(ocrText)) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0)
                })
            }
            executeRequest(requestBody, apiKey)
        }

    private suspend fun executeRequest(requestBody: JSONObject, apiKey: String): ReceiptParseResult? {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("$API_URL?key=$apiKey")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                Log.d("GeminiReceiptClient", "HTTP ${response.code}")
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: "(no body)"
                    Log.e("GeminiReceiptClient", "HTTP error ${response.code}: $errBody")
                    if (response.code == 429) throw GeminiRateLimitException()
                    return null
                }
                val body = response.body?.string() ?: run {
                    Log.e("GeminiReceiptClient", "body is null")
                    return null
                }
                parseGeminiResponse(body)
            }
        } catch (e: Exception) {
            Log.e("GeminiReceiptClient", "API error: ${e.message}")
            null
        }
    }

    private fun buildPrompt(ocrText: String): String = """
以下はレシートをOCRで読み取ったテキストです。
このテキストから以下のJSON形式で情報を抽出してください。日付が不明な場合は空文字列にしてください。
価格は税込の整数（円）で返してください。
小計・合計・ポイント・お釣り等の行は items に含めないでください。

{
  "storeName": "店舗名",
  "date": "yyyy-MM-dd",
  "items": [
    { "name": "商品名", "price": 金額 }
  ],
  "total": 合計金額
}

OCRテキスト:
$ocrText
""".trimIndent()

    private fun buildImagePrompt(): String = """
このレシート画像から以下のJSON形式で情報を抽出してください。
日付が不明な場合は空文字列にしてください。
価格は税込の整数（円）で返してください。
小計・合計・ポイント・お釣り等の行は items に含めないでください。

{
  "storeName": "店舗名",
  "date": "yyyy-MM-dd",
  "items": [
    { "name": "商品名", "price": 金額 }
  ],
  "total": 合計金額
}
""".trimIndent()

    private fun scaleBitmap(bitmap: Bitmap, maxPx: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxPx && h <= maxPx) return bitmap
        val scale = maxPx.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun parseGeminiResponse(responseBody: String): ReceiptParseResult? {
        return try {
            val root = JSONObject(responseBody)
            val text = root
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val json = JSONObject(text)
            val itemsArray = json.getJSONArray("items")
            val items = (0 until itemsArray.length()).map { i ->
                val obj = itemsArray.getJSONObject(i)
                ParsedItem(
                    name = obj.optString("name", ""),
                    price = obj.optInt("price", 0)
                )
            }

            ReceiptParseResult(
                storeName = json.optString("storeName", ""),
                date = json.optString("date", ""),
                items = items,
                total = json.optInt("total", 0)
            )
        } catch (e: Exception) {
            Log.e("GeminiReceiptClient", "Parse error: ${e.message}")
            null
        }
    }
}
