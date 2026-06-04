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
class GeminiApiKeyMissingException : Exception("Gemini APIキーが設定されていません。設定画面で入力してください。")

object GeminiReceiptClient {

    data class AccountMatchSuggestion(
        val productName: String,
        val suggestedAccountId: Long,
        val suggestedAccountName: String,
        val reason: String
    )

    suspend fun matchProductsToAccounts(
        productNames: List<Pair<String, String>>,  // (productName, category)
        accounts: List<com.example.greenframeocr.data.YayoiAccount>,
        apiKey: String
    ): List<AccountMatchSuggestion> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiApiKeyMissingException()

        val productsText = productNames.take(60).joinToString("\n") { (name, cat) ->
            "- $name（カテゴリ: $cat）"
        }
        val accountsText = accounts.take(100).joinToString("\n") { acc ->
            val code = acc.accountCode?.let { "[$it]" } ?: ""
            "ID:${acc.id}  ${acc.accountName}$code  ${acc.categoryA}/${acc.categoryB}"
        }

        val prompt = """
あなたは農業経営の青色申告（弥生の青色申告）に詳しい会計専門家です。
以下の農業関連購買品目に対して、提示された弥生勘定科目の中から最も適切なものを1つ割り当ててください。

# 購買品目（未割当・${productNames.size}件）
$productsText

# 弥生勘定科目リスト（${accounts.size}件）
$accountsText

# 回答形式（JSON）
- accountId は必ず上記リストの ID を使用してください
- 適切な科目が見つからない場合はそのエントリを省略してください
- reason は30文字以内の日本語で記述してください

{
  "matches": [
    {"productName": "品目名", "accountId": 科目ID, "reason": "割り当て理由"}
  ]
}
""".trimIndent()

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0)
            })
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("$API_URL?key=$apiKey")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 429) throw GeminiRateLimitException()
                    Log.e("GeminiReceiptClient", "HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                parseMatchResponse(body, accounts)
            }
        } catch (e: GeminiRateLimitException) { throw e }
          catch (e: GeminiApiKeyMissingException) { throw e }
          catch (e: Exception) {
            Log.e("GeminiReceiptClient", "matchProducts error: ${e.message}")
            emptyList()
        }
    }

    private fun parseMatchResponse(
        responseBody: String,
        accounts: List<com.example.greenframeocr.data.YayoiAccount>
    ): List<AccountMatchSuggestion> {
        return try {
            val root = JSONObject(responseBody)
            val text = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val json = JSONObject(text)
            val arr = json.getJSONArray("matches")
            val accountMap = accounts.associateBy { it.id }

            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val productName = obj.optString("productName")
                val accountId = obj.optLong("accountId", -1L)
                val reason = obj.optString("reason", "")
                val account = accountMap[accountId] ?: return@mapNotNull null
                AccountMatchSuggestion(
                    productName = productName,
                    suggestedAccountId = accountId,
                    suggestedAccountName = account.accountName,
                    reason = reason
                )
            }
        } catch (e: Exception) {
            Log.e("GeminiReceiptClient", "parseMatchResponse error: ${e.message}")
            emptyList()
        }
    }

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
