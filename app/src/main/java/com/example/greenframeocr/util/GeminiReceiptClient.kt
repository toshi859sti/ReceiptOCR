package com.example.greenframeocr.util

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiRateLimitException : Exception("APIのレート制限に達しました。しばらく待ってから再試行してください。")
class GeminiQuotaExhaustedException : Exception("Gemini APIの無料枠の上限に達しました。Google AI Studio（aistudio.google.com）で利用状況をご確認ください。")
class GeminiApiKeyMissingException : Exception("Gemini APIキーが設定されていません。設定画面で入力してください。")
class GeminiApiException(val code: Int, detail: String = "") : Exception(
    when (code) {
        403 -> "APIキーが無効または権限がありません（HTTP 403）。設定画面でAPIキーをご確認ください。"
        500, 503 -> "Gemini APIサーバーエラーが発生しました（HTTP $code）。しばらく待ってから再試行してください。"
        else -> "Gemini APIエラーが発生しました（HTTP $code）。${detail.take(80)}"
    }
)

object GeminiReceiptClient {

    /** APIトークン使用量 */
    data class AiUsageStats(
        val promptTokens: Int,
        val candidatesTokens: Int,
        val totalTokens: Int
    ) {
        fun toDisplayString() = "入力: ${promptTokens} / 出力: ${candidatesTokens} / 合計: ${totalTokens}トークン"
    }

    data class AccountMatchSuggestion(
        val productName: String,
        val suggestedAccountId: Long,
        val suggestedAccountName: String,
        val reason: String
    )

    /** 購買品目→弥生科目マッチング結果 */
    data class MatchProductsResult(
        val suggestions: List<AccountMatchSuggestion>,
        val usageStats: AiUsageStats?
    )

    /** 通帳摘要→弥生科目マッチング結果の1件 */
    data class TekiyouMatchSuggestion(
        val ruleId: Int,
        val normalizedTekiyou: String,
        val suggestedAccountId: Long,
        val suggestedAccountName: String,
        val reason: String
    )

    /** 通帳摘要→弥生科目マッチング結果 */
    data class MatchTekiyouResult(
        val suggestions: List<TekiyouMatchSuggestion>,
        val usageStats: AiUsageStats?
    )

    suspend fun matchProductsToAccounts(
        productNames: List<Pair<String, String>>,  // (productName, category)
        accounts: List<com.example.greenframeocr.data.YayoiAccount>,
        apiKey: String
    ): MatchProductsResult = withContext(Dispatchers.IO) {
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
                    val body = response.body?.string() ?: ""
                    Log.e("GeminiReceiptClient", "HTTP ${response.code}: $body")
                    throwApiError(response.code, body)
                }
                val body = response.body?.string() ?: return@withContext MatchProductsResult(emptyList(), null)
                parseMatchResponse(body, accounts)
            }
        } catch (e: GeminiRateLimitException) { throw e }
          catch (e: GeminiQuotaExhaustedException) { throw e }
          catch (e: GeminiApiKeyMissingException) { throw e }
          catch (e: GeminiApiException) { throw e }
          catch (e: Exception) {
            Log.e("GeminiReceiptClient", "matchProducts error: ${e.message}")
            MatchProductsResult(emptyList(), null)
        }
    }

    suspend fun matchTekiyouToAccounts(
        rules: List<com.example.greenframeocr.data.MatchingRuleWithTekiyou>,
        accounts: List<com.example.greenframeocr.data.YayoiAccount>,
        apiKey: String
    ): MatchTekiyouResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiApiKeyMissingException()

        val rulesText = rules.take(60).joinToString("\n") { rule ->
            val type = if (rule.isDeposit) "入金" else "出金"
            "ID:${rule.id}  ${rule.normalizedTekiyou}（${type}・${rule.matchCount}件）"
        }
        val accountsText = accounts.take(100).joinToString("\n") { acc ->
            val code = acc.accountCode?.let { "[$it]" } ?: ""
            "ID:${acc.id}  ${acc.accountName}$code  ${acc.categoryA}/${acc.categoryB}"
        }

        val prompt = """
あなたは農業経営の青色申告（弥生の青色申告）に詳しい会計専門家です。
以下の通帳摘要パターンすべてに対して、提示された弥生勘定科目の中から最も適切なものを1つ必ず割り当ててください。

# 割り当てのヒント
- 入金：農産物売上→売上高、補助金・共済金→雑収入、利息→受取利息
- 出金：肥料・農薬・資材→農業経費、燃料費→動力光熱費、固定資産税・都市計画税→租税公課、
         農協・各種共済→共済掛金、借入返済→長期借入金／短期借入金、給与→給料賃金、
         電気・ガス・水道→動力光熱費、通信→通信費、保険料→損害保険料、
         修繕→修繕費、消耗品→農具費または消耗品費

# 通帳摘要パターン（未割当・${rules.size}件）
$rulesText

# 弥生勘定科目リスト（${accounts.size}件）
$accountsText

# 回答形式（JSON）
- ruleId は必ず上記パターンリストの ID を使用してください
- accountId は必ず上記勘定科目リストの ID を使用してください
- すべてのパターンに対して必ず1件ずつ割り当ててください（省略不可）
- reason は30文字以内の日本語で記述してください

{
  "matches": [
    {"ruleId": ルールID, "accountId": 科目ID, "reason": "割り当て理由"}
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
                    val body = response.body?.string() ?: ""
                    Log.e("GeminiReceiptClient", "matchTekiyou HTTP ${response.code}: $body")
                    throwApiError(response.code, body)
                }
                val body = response.body?.string() ?: return@withContext MatchTekiyouResult(emptyList(), null)
                parseTekiyouMatchResponse(body, rules, accounts)
            }
        } catch (e: GeminiRateLimitException) { throw e }
          catch (e: GeminiQuotaExhaustedException) { throw e }
          catch (e: GeminiApiKeyMissingException) { throw e }
          catch (e: GeminiApiException) { throw e }
          catch (e: Exception) {
            Log.e("GeminiReceiptClient", "matchTekiyou error: ${e.message}")
            MatchTekiyouResult(emptyList(), null)
        }
    }

    private fun parseUsageStats(root: JSONObject): AiUsageStats? {
        return try {
            val usage = root.optJSONObject("usageMetadata") ?: return null
            AiUsageStats(
                promptTokens = usage.optInt("promptTokenCount", 0),
                candidatesTokens = usage.optInt("candidatesTokenCount", 0),
                totalTokens = usage.optInt("totalTokenCount", 0)
            )
        } catch (e: Exception) { null }
    }

    private fun parseMatchResponse(
        responseBody: String,
        accounts: List<com.example.greenframeocr.data.YayoiAccount>
    ): MatchProductsResult {
        return try {
            val root = JSONObject(responseBody)
            val usageStats = parseUsageStats(root)
            val text = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val json = JSONObject(text)
            val arr = json.getJSONArray("matches")
            val accountMap = accounts.associateBy { it.id }

            val suggestions = (0 until arr.length()).mapNotNull { i ->
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
            MatchProductsResult(suggestions, usageStats)
        } catch (e: Exception) {
            Log.e("GeminiReceiptClient", "parseMatchResponse error: ${e.message}")
            MatchProductsResult(emptyList(), null)
        }
    }

    private fun parseTekiyouMatchResponse(
        responseBody: String,
        rules: List<com.example.greenframeocr.data.MatchingRuleWithTekiyou>,
        accounts: List<com.example.greenframeocr.data.YayoiAccount>
    ): MatchTekiyouResult {
        return try {
            val root = JSONObject(responseBody)
            val usageStats = parseUsageStats(root)
            val text = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val json = JSONObject(text)
            val arr = json.getJSONArray("matches")
            val ruleMap = rules.associateBy { it.id }
            val accountMap = accounts.associateBy { it.id }

            val suggestions = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val ruleId = obj.optInt("ruleId", -1)
                val accountId = obj.optLong("accountId", -1L)
                val reason = obj.optString("reason", "")
                val rule = ruleMap[ruleId] ?: return@mapNotNull null
                val account = accountMap[accountId] ?: return@mapNotNull null
                TekiyouMatchSuggestion(
                    ruleId = ruleId,
                    normalizedTekiyou = rule.normalizedTekiyou,
                    suggestedAccountId = accountId,
                    suggestedAccountName = account.accountName,
                    reason = reason
                )
            }
            MatchTekiyouResult(suggestions, usageStats)
        } catch (e: Exception) {
            Log.e("GeminiReceiptClient", "parseTekiyouMatchResponse error: ${e.message}")
            MatchTekiyouResult(emptyList(), null)
        }
    }

    private fun throwApiError(code: Int, body: String): Nothing {
        if (code == 429) {
            val message = try { JSONObject(body).optJSONObject("error")?.optString("message") ?: "" } catch (_: Exception) { "" }
            if (message.contains("quota", ignoreCase = true)) throw GeminiQuotaExhaustedException()
            throw GeminiRateLimitException()
        }
        throw GeminiApiException(code, body)
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

    // -----------------------------------------------------------------------
    // JA購買伝票OCR（Two-Pass方式）
    //
    // Phase0スパイクテストで確認した「取引日列だけを単独クロップして送信すると
    // 全体画像方式より安定する」という結果に基づき、以下2回のAPI呼び出しを並列実行し、
    // 行インデックスでマージする：
    //   1. 全体画像コール：商品名・数量・税込金額・分類計・取引日(生の6桁数字)を取得
    //   2. 取引日列クロップコール：取引日列だけを2倍拡大して送信し、生の6桁数字を取得
    // 2つの行数が一致しない場合は dateColumnAligned=false を返し、呼び出し側で
    // 伝票全体を要確認扱いにするなどのフォールバックを行うこと。
    // -----------------------------------------------------------------------

    private const val JA_SHEET_MODEL = "gemini-3.5-flash-lite"
    private const val JA_SHEET_MAIN_RESIZE_PX = 2000
    private const val JA_SHEET_MAX_RETRIES = 3

    /** JA伝票の1行分（Gemini生レスポンス。年/月/日への変換・カテゴリ判定は呼び出し側で行う） */
    data class JaSheetRow(
        val rowType: String,       // "NORMAL" | "SUBTOTAL" | "MONTHLY_TOTAL"
        val dateRaw: String,       // 6桁の生数字（例:"071008"）。空文字列=日付欄なし
        val itemName: String,
        val quantity: Double?,
        val amount: Int?,
        val categorySum: Int?,     // SUBTOTAL行のみ
        val remarks: String,
        val confidence: String     // "high" | "medium" | "low"
    )

    data class JaSheetParseResult(
        val rows: List<JaSheetRow>,
        val usageStats: AiUsageStats?,
        /** falseの場合、取引日列クロップの行数が本体行数と一致しなかった（dateRawは全体画像コールの値のまま） */
        val dateColumnAligned: Boolean
    )

    suspend fun parseJaSheetFromImage(dewarpedBitmap: Bitmap, apiKey: String): JaSheetParseResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw GeminiApiKeyMissingException()

            coroutineScope {
                val mainDeferred = async {
                    callWithRetry { requestJaSheetMain(dewarpedBitmap, apiKey) }
                }
                val dateDeferred = async {
                    callWithRetry { requestJaSheetDateColumn(dewarpedBitmap, apiKey) }
                }

                val (mainRows, mainUsage) = mainDeferred.await()
                val (dates, dateUsage) = dateDeferred.await()

                val (mergedRows, aligned) = alignDateColumn(mainRows, dates)

                JaSheetParseResult(
                    rows = mergedRows,
                    usageStats = mergeUsageStats(mainUsage, dateUsage),
                    dateColumnAligned = aligned
                )
            }
        }

    /** 指数バックオフ付きリトライ。5xx/429/通信エラーのみ最大 JA_SHEET_MAX_RETRIES 回まで再試行する */
    private suspend fun <T> callWithRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: GeminiApiKeyMissingException) {
                throw e
            } catch (e: GeminiQuotaExhaustedException) {
                throw e
            } catch (e: GeminiApiException) {
                if (e.code != 500 && e.code != 503) throw e
                attempt++
                if (attempt >= JA_SHEET_MAX_RETRIES) throw e
                delay(1000L shl (attempt - 1))
            } catch (e: GeminiRateLimitException) {
                attempt++
                if (attempt >= JA_SHEET_MAX_RETRIES) throw e
                delay(1000L shl (attempt - 1))
            } catch (e: java.io.IOException) {
                attempt++
                if (attempt >= JA_SHEET_MAX_RETRIES) throw e
                delay(1000L shl (attempt - 1))
            }
        }
    }

    /**
     * 全体画像コールの行リストに、取引日列クロップコールの結果をマージする。
     *
     * 単純な件数一致（同数なら順番にそのまま割り当て）に加え、実測で確認された既知の
     * 省略パターンに対する再アラインメントを行う：列クロップコールは指示に反して
     * 日付欄が空欄の行（SUBTOTAL/MONTHLY_TOTAL、伝票の仕様上必ず日付欄を持たない）を
     * 配列から省略することがある。NORMAL行の数と列クロップの件数が一致する場合は、
     * NORMAL行にだけ順番に割り当て直すことで救済する（2026-08-09実機確認で確認済みの
     * パターン。SUBTOTAL/MONTHLY_TOTAL行のdateRawは空文字列で確定させる）。
     * それでも一致しない場合は元の全体画像コールのdateRawをそのまま使い、
     * aligned=falseを返して呼び出し側でのフォールバック判断に委ねる。
     */
    private fun alignDateColumn(mainRows: List<JaSheetRow>, dates: List<String>): Pair<List<JaSheetRow>, Boolean> {
        if (dates.size == mainRows.size) {
            return mainRows.mapIndexed { i, row -> row.copy(dateRaw = dates[i]) } to true
        }

        val normalRowCount = mainRows.count { it.rowType == "NORMAL" }
        if (dates.size == normalRowCount) {
            var dateIdx = 0
            val realigned = mainRows.map { row ->
                if (row.rowType == "NORMAL") {
                    val d = dates[dateIdx]
                    dateIdx++
                    row.copy(dateRaw = d)
                } else {
                    row.copy(dateRaw = "")
                }
            }
            Log.w("GeminiReceiptClient", "JA sheet date column re-aligned by skipping non-NORMAL rows (main=${mainRows.size} date=${dates.size} normalRows=$normalRowCount)")
            return realigned to true
        }

        Log.w("GeminiReceiptClient", "JA sheet date column misalignment (unrecoverable): main=${mainRows.size} date=${dates.size}")
        Log.w("GeminiReceiptClient", "main rowTypes: ${mainRows.map { it.rowType }}")
        Log.w("GeminiReceiptClient", "main dateRaw : ${mainRows.map { it.dateRaw }}")
        Log.w("GeminiReceiptClient", "date column  : $dates")
        return mainRows to false
    }

    private fun mergeUsageStats(a: AiUsageStats?, b: AiUsageStats?): AiUsageStats? {
        if (a == null && b == null) return null
        return AiUsageStats(
            promptTokens = (a?.promptTokens ?: 0) + (b?.promptTokens ?: 0),
            candidatesTokens = (a?.candidatesTokens ?: 0) + (b?.candidatesTokens ?: 0),
            totalTokens = (a?.totalTokens ?: 0) + (b?.totalTokens ?: 0)
        )
    }

    private fun requestJaSheetMain(dewarpedBitmap: Bitmap, apiKey: String): Pair<List<JaSheetRow>, AiUsageStats?> {
        val resized = scaleBitmap(dewarpedBitmap, JA_SHEET_MAIN_RESIZE_PX)
        val base64Image = bitmapToBase64Jpeg(resized)

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
                        put(JSONObject().apply { put("text", buildJaSheetMainPrompt()) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0)
            })
        }

        val body = executeJaSheetHttp(requestBody, apiKey)
        return parseJaSheetMainResponse(body)
    }

    private fun requestJaSheetDateColumn(dewarpedBitmap: Bitmap, apiKey: String): Pair<List<String>, AiUsageStats?> {
        val cropped = cropDateColumn(dewarpedBitmap)
        val base64Image = bitmapToBase64Jpeg(cropped)

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
                        put(JSONObject().apply { put("text", buildJaSheetDateColumnPrompt()) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0)
            })
        }

        val body = executeJaSheetHttp(requestBody, apiKey)
        return parseJaSheetDateColumnResponse(body)
    }

    /**
     * 取引日列だけを切り出す。docs/OCR_SPEC.md の列定義（取引日: 5.5〜20.0mm）に
     * 左右マージンを加え、通常行/小計行(56.5〜120.5mm)＋月合計行(121.0〜135.0mm)を
     * まとめてカバーする範囲を2倍拡大して返す。
     */
    private fun cropDateColumn(dewarpedBitmap: Bitmap): Bitmap {
        val mmToPxX = dewarpedBitmap.width / 203.0
        val mmToPxY = dewarpedBitmap.height / 148.0
        val x = ((5.5 * mmToPxX) - 10).toInt().coerceIn(0, dewarpedBitmap.width - 1)
        val yStart = ((56.5 * mmToPxY) - 5).toInt().coerceIn(0, dewarpedBitmap.height - 1)
        val xEnd = ((20.0 * mmToPxX) + 10).toInt().coerceIn(x + 1, dewarpedBitmap.width)
        val yEnd = ((135.0 * mmToPxY) + 5).toInt().coerceIn(yStart + 1, dewarpedBitmap.height)

        val cropped = Bitmap.createBitmap(dewarpedBitmap, x, yStart, xEnd - x, yEnd - yStart)
        return Bitmap.createScaledBitmap(cropped, cropped.width * 2, cropped.height * 2, true)
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun executeJaSheetHttp(requestBody: JSONObject, apiKey: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$JA_SHEET_MODEL:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e("GeminiReceiptClient", "JA sheet HTTP ${response.code}: $body")
                throwApiError(response.code, body)
            }
            return body
        }
    }

    private fun extractGeminiText(responseBody: String): Pair<String, AiUsageStats?> {
        val root = JSONObject(responseBody)
        val usageStats = parseUsageStats(root)
        val text = root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        return text to usageStats
    }

    private fun parseJaSheetMainResponse(responseBody: String): Pair<List<JaSheetRow>, AiUsageStats?> {
        val (text, usage) = extractGeminiText(responseBody)
        val json = JSONObject(text)
        val arr = json.getJSONArray("rows")
        val rows = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            JaSheetRow(
                rowType = obj.optString("rowType", "NORMAL"),
                dateRaw = obj.optString("dateRaw", ""),
                itemName = obj.optString("itemName", ""),
                quantity = if (obj.has("quantity") && !obj.isNull("quantity")) obj.optDouble("quantity") else null,
                amount = if (obj.has("amount") && !obj.isNull("amount")) obj.optInt("amount") else null,
                categorySum = if (obj.has("categorySum") && !obj.isNull("categorySum")) obj.optInt("categorySum") else null,
                remarks = obj.optString("remarks", ""),
                confidence = obj.optString("confidence", "medium")
            )
        }
        return rows to usage
    }

    private fun parseJaSheetDateColumnResponse(responseBody: String): Pair<List<String>, AiUsageStats?> {
        val (text, usage) = extractGeminiText(responseBody)
        val json = JSONObject(text)
        val arr = json.getJSONArray("dates")
        val dates = (0 until arr.length()).map { arr.optString(it, "") }
        return dates to usage
    }

    private fun buildJaSheetMainPrompt(): String = """
あなたはOCR専門のアシスタントです。以下の画像は、JA(農業協同組合)の「購買代金請求明細書」を
透視変換した表組み部分です。表は左から次の列で構成されます。

1. 取引日(6桁の連続した数字で印字されている。例: 071008)
2. 商品名(農薬・肥料・資材・ガソリン等。規格や容量を含む)
3. 取扱支店(読み取り不要)
4. 数量(整数または小数。返品行は数量がマイナスになる、または備考に「返品」と書かれる)
5. 税込単価(読み取り不要)
6. 税込金額(円の整数。マイナスの場合あり)
7. 分類計(「* 小計(分類名)」という行にのみ記載される、その区分の合計金額)
8. 入金・窓口(読み取り不要)
9. 備考(車両番号等の補足。空欄が多い)

通常の取引行(NORMAL)のほかに、「* 小計(一般購買)」「* 小計(給油所)」のような小計行(SUBTOTAL)が
含まれることがあります。また、明細の最後に「合計(税込)」という行が印字されている場合は、
月計行(MONTHLY_TOTAL)として必ず出力してください（画像に実際に見えているのに省略しないこと）。
ただし、複数ページに分かれた伝票の2ページ目以降など、そのページに「合計(税込)」行が
印字されていない場合は、無理に作り出さず出力しないでください（画像に見えている行だけを
書き写すこと）。小計行が存在せず、取引行の直後に合計(税込)へ到達する伝票もあります。
その場合は無理にSUBTOTAL行を作らず、取引行の次にMONTHLY_TOTAL行(合計(税込))を置いてください。

画像内の全ての行を上から順に、以下のJSON形式だけで返してください。前置き・説明文・
Markdown装飾(```json など)は一切付けないでください。

{
  "rows": [
    {
      "rowType": "NORMAL または SUBTOTAL または MONTHLY_TOTAL",
      "dateRaw": "取引日欄に印字されている数字をそのまま6桁の文字列で出力(例:071008)。
                  変換・整形は一切せず、見えている文字をそのまま書き写すこと。
                  読み取れない・欄が空の場合は空文字列",
      "itemName": "商品名。SUBTOTAL行の場合は分類名(例:一般購買、給油所)。
                   MONTHLY_TOTAL行の場合は「合計(税込)」",
      "quantity": 数値またはnull,
      "amount": 税込金額の整数(マイナスの場合は負の値)、またはnull,
      "categorySum": "SUBTOTAL行は分類計の整数、MONTHLY_TOTAL行は合計(税込)の金額の整数。
                      NORMAL行はnull",
      "remarks": "備考欄。空なら空文字列",
      "confidence": "high、medium、lowのいずれか"
    }
  ]
}

注意:
- dateRawは絶対にMM/DD形式などに変換しないでください。画像に印字されている数字の並びを
  そのまま6桁の文字列として書き写すことだけに専念してください（この列は別途高解像度で
  再確認するため、ここでは大まかな読み取りで構いません）。
- 数量列に小数点が印字されていなくても、ガソリン等の給油量は小数(例: 29.20)である場合があります。
  金額を単価で割った値と整合するか検算し、整合するなら小数として解釈してください。
- 返品行は数量・金額をマイナス値にしてください。
- 取扱支店・税込単価・入金・窓口列の値は出力に含めないでください。
- 明細の最上部にある「前月請求」「前月入金」等のヘッダー部（今回の取引行とは無関係な
  過去の請求サマリー）が空欄の場合は、無理に値を作らず読み取り対象から除外してください。
- 明細末尾の「合計(税込)」行（MONTHLY_TOTAL）は、画像内に実際に印字されている場合のみ
  出力してください。複数ページの伝票でこのページには印字されていない場合は、
  存在しない行として扱い、無理に出力しないでください。
""".trimIndent()

    private fun buildJaSheetDateColumnPrompt(): String = """
この画像は、JA(農業協同組合)の「購買代金請求明細書」から取引日列だけを縦に切り出した部分です。
取引日は6桁の連続した数字で印字されています(例: 071008)。表には横罫線がなく、行と行の間は
白いスペースのみで区切られています。

画像内に見える日付の並びを、上から順にすべて配列で返してください。小計行・月合計行など
日付が印字されていない行(空欄)は空文字列 "" としてください。

以下のJSON形式だけで返してください。前置き・説明文・Markdown装飾は一切付けないでください。

{
  "dates": ["071008", "", "071009"]
}

注意:
- 各値は必ず6桁の数字をそのまま書き写してください。MM/DD形式などへの変換・整形・区切り記号の
  挿入は絶対にしないでください。見えている文字の並びをそのまま出力することだけに専念してください。
- 読み取れない・かすれている場合も、最も近いと判断した数字を6桁で出力してください。
- 空欄行を省略せず、見えている行の数だけ配列要素を出力してください(欠落・重複させない)。
""".trimIndent()

    // -----------------------------------------------------------------------
    // JA購買伝票 部分再OCR（Phase5: 選択セルの行範囲だけをクロップして再送信）
    //
    // ReceiptInputScreen.kt でユーザーがグリッド上の特定セルを選択して再撮影した場合、
    // 選択された行範囲だけを画像として切り出し、かつ選択されたセル種別に応じて
    // 必要な呼び出しだけを行う（取引日が未選択なら取引日列クロップ呼び出し自体を省略）。
    // 新設プロンプトは「空白行も省略せず行範囲と1:1で返す」ことを明示要求するが、
    // 取引日列クロップでも同種の指示が完全には守られなかった実績がある（2026-08-09実機確認で
    // SUBTOTAL行省略を発見済み）ため、返却行数が期待値と一致しない場合は aligned=false を返し、
    // 呼び出し側で伝票全体再送信（parseJaSheetFromImage）へフォールバックすることを前提とする。
    // -----------------------------------------------------------------------

    /** 通常行・小計行のY範囲（mm、伝票上端原点）。20行を均等分割。合計行は別ブロック */
    private const val NORMAL_ROW_Y_START_MM = 56.5
    private const val NORMAL_ROW_Y_END_MM = 120.5
    private const val NORMAL_ROW_COUNT = 20
    private const val TOTAL_ROW_Y_START_MM = 121.0
    private const val TOTAL_ROW_Y_END_MM = 135.0
    private const val TOTAL_ROW_GRID_INDEX = 20

    /** 商品名列の開始〜税込金額列の終了（mm）。部分メイン呼び出しの列範囲（取引日列は含めない） */
    private const val PARTIAL_MAIN_COLUMN_START_MM = 20.0
    private const val PARTIAL_MAIN_COLUMN_END_MM = 156.0

    /** グリッド行インデックス(0〜19=通常行/小計行, 20=合計行)のY範囲(mm)を返す */
    private fun rowYRangeMm(gridIndex: Int): Pair<Double, Double> {
        if (gridIndex == TOTAL_ROW_GRID_INDEX) {
            return TOTAL_ROW_Y_START_MM to TOTAL_ROW_Y_END_MM
        }
        val rowHeightMm = (NORMAL_ROW_Y_END_MM - NORMAL_ROW_Y_START_MM) / NORMAL_ROW_COUNT
        val start = NORMAL_ROW_Y_START_MM + gridIndex * rowHeightMm
        return start to (start + rowHeightMm)
    }

    /** 行範囲・列範囲(mm)をクロップする。cropDateColumn() と同じ mm→px 変換＋パディングパターン */
    private fun cropRowRange(
        dewarpedBitmap: Bitmap,
        rowRange: IntRange,
        columnStartMm: Double,
        columnEndMm: Double
    ): Bitmap {
        val mmToPxX = dewarpedBitmap.width / 203.0
        val mmToPxY = dewarpedBitmap.height / 148.0
        val (yStartMm, _) = rowYRangeMm(rowRange.first)
        val (_, yEndMm) = rowYRangeMm(rowRange.last)

        val x = ((columnStartMm * mmToPxX) - 10).toInt().coerceIn(0, dewarpedBitmap.width - 1)
        val xEnd = ((columnEndMm * mmToPxX) + 10).toInt().coerceIn(x + 1, dewarpedBitmap.width)
        val yStart = ((yStartMm * mmToPxY) - 5).toInt().coerceIn(0, dewarpedBitmap.height - 1)
        val yEnd = ((yEndMm * mmToPxY) + 5).toInt().coerceIn(yStart + 1, dewarpedBitmap.height)

        return Bitmap.createBitmap(dewarpedBitmap, x, yStart, xEnd - x, yEnd - yStart)
    }

    /** 取引日列だけを行範囲に絞って切り出す。列範囲・2倍拡大は cropDateColumn() と同じ */
    private fun cropDateColumnForRows(dewarpedBitmap: Bitmap, rowRange: IntRange): Bitmap {
        val cropped = cropRowRange(dewarpedBitmap, rowRange, 5.5, 20.0)
        return Bitmap.createScaledBitmap(cropped, cropped.width * 2, cropped.height * 2, true)
    }

    data class PartialJaSheetResult(
        val rows: List<JaSheetRow>,
        val usageStats: AiUsageStats?,
        /** falseの場合、返却行数が期待値(rowRangeの行数)と一致しなかった。呼び出し側はparseJaSheetFromImage()へフォールバックすること */
        val aligned: Boolean
    )

    /**
     * 選択された行範囲だけを部分的に再OCRする。needsDate/needsMainに応じて
     * 必要な呼び出しだけを並列実行する。
     */
    suspend fun parseJaSheetPartial(
        dewarpedBitmap: Bitmap,
        apiKey: String,
        rowRange: IntRange,
        needsDate: Boolean,
        needsMain: Boolean
    ): PartialJaSheetResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiApiKeyMissingException()
        val expectedCount = rowRange.count()

        coroutineScope {
            val mainDeferred = if (needsMain) async {
                callWithRetry { requestJaSheetPartialMain(dewarpedBitmap, apiKey, rowRange) }
            } else null
            val dateDeferred = if (needsDate) async {
                callWithRetry { requestJaSheetDateColumnForRows(dewarpedBitmap, apiKey, rowRange) }
            } else null

            val mainResult = mainDeferred?.await()
            val dateResult = dateDeferred?.await()

            val mainAligned = mainResult == null || mainResult.first.size == expectedCount
            val dateAligned = dateResult == null || dateResult.first.size == expectedCount

            if (!mainAligned || !dateAligned) {
                Log.w(
                    "GeminiReceiptClient",
                    "JA sheet partial re-OCR misalignment: rowRange=$rowRange expected=$expectedCount " +
                        "main=${mainResult?.first?.size} date=${dateResult?.first?.size}"
                )
                return@coroutineScope PartialJaSheetResult(
                    rows = emptyList(),
                    usageStats = mergeUsageStats(mainResult?.second, dateResult?.second),
                    aligned = false
                )
            }

            val mergedRows = (0 until expectedCount).map { i ->
                val base = mainResult?.first?.get(i) ?: JaSheetRow(
                    rowType = "NORMAL", dateRaw = "", itemName = "", quantity = null,
                    amount = null, categorySum = null, remarks = "", confidence = "medium"
                )
                if (dateResult != null) base.copy(dateRaw = dateResult.first[i]) else base
            }

            Log.d(
                "GeminiReceiptClient",
                "partial re-OCR: rows=$rowRange needsDate=$needsDate needsMain=$needsMain aligned=true"
            )
            PartialJaSheetResult(
                rows = mergedRows,
                usageStats = mergeUsageStats(mainResult?.second, dateResult?.second),
                aligned = true
            )
        }
    }

    private fun requestJaSheetPartialMain(
        dewarpedBitmap: Bitmap,
        apiKey: String,
        rowRange: IntRange
    ): Pair<List<JaSheetRow>, AiUsageStats?> {
        val cropped = cropRowRange(dewarpedBitmap, rowRange, PARTIAL_MAIN_COLUMN_START_MM, PARTIAL_MAIN_COLUMN_END_MM)
        val base64Image = bitmapToBase64Jpeg(cropped)

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
                        put(JSONObject().apply { put("text", buildJaSheetPartialMainPrompt(rowRange.count())) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0)
            })
        }

        val body = executeJaSheetHttp(requestBody, apiKey)
        return parseJaSheetMainResponse(body)
    }

    private fun requestJaSheetDateColumnForRows(
        dewarpedBitmap: Bitmap,
        apiKey: String,
        rowRange: IntRange
    ): Pair<List<String>, AiUsageStats?> {
        val cropped = cropDateColumnForRows(dewarpedBitmap, rowRange)
        val base64Image = bitmapToBase64Jpeg(cropped)

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
                        put(JSONObject().apply { put("text", buildJaSheetDateColumnPrompt()) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0)
            })
        }

        val body = executeJaSheetHttp(requestBody, apiKey)
        return parseJaSheetDateColumnResponse(body)
    }

    private fun buildJaSheetPartialMainPrompt(rowCount: Int): String = """
あなたはOCR専門のアシスタントです。以下の画像は、JA(農業協同組合)の「購買代金請求明細書」の
一部分（上から連続した${rowCount}行分の行位置だけ）を切り出した表組みです。表は左から
次の列で構成されます（取引日列は含まれていません）。

1. 商品名(農薬・肥料・資材・ガソリン等。規格や容量を含む)
2. 取扱支店(読み取り不要)
3. 数量(整数または小数。返品行は数量がマイナスになる、または備考に「返品」と書かれる)
4. 税込単価(読み取り不要)
5. 税込金額(円の整数。マイナスの場合あり)
6. 分類計(「* 小計(分類名)」という行にのみ記載される、その区分の合計金額)
7. 入金・窓口(読み取り不要)
8. 備考(車両番号等の補足。空欄が多い)

この画像はちょうど${rowCount}行分の行位置を上から順に示しています。**文字が何も見えない
行位置（空白行）も省略せず、必ず${rowCount}件を順番通りに返してください**。空白行は
rowType を "BLANK" とし、他のフィールドは空文字列またはnullにしてください。

通常の取引行(NORMAL)のほかに、「* 小計(一般購買)」「* 小計(給油所)」のような小計行(SUBTOTAL)、
「合計(税込)」という月計行(MONTHLY_TOTAL)が含まれることがあります。

以下のJSON形式だけで返してください。前置き・説明文・Markdown装飾(```json など)は
一切付けないでください。

{
  "rows": [
    {
      "rowType": "NORMAL または SUBTOTAL または MONTHLY_TOTAL または BLANK",
      "itemName": "商品名。SUBTOTAL行の場合は分類名(例:一般購買、給油所)。
                   MONTHLY_TOTAL行の場合は「合計(税込)」。BLANK行は空文字列",
      "quantity": 数値またはnull,
      "amount": 税込金額の整数(マイナスの場合は負の値)、またはnull,
      "categorySum": "SUBTOTAL行は分類計の整数、MONTHLY_TOTAL行は合計(税込)の金額の整数。
                      NORMAL行・BLANK行はnull",
      "remarks": "備考欄。空なら空文字列",
      "confidence": "high、medium、lowのいずれか"
    }
  ]
}

注意:
- 出力する配列の要素数は必ず${rowCount}件にしてください（多くても少なくてもいけません）。
- 数量列に小数点が印字されていなくても、ガソリン等の給油量は小数(例: 29.20)である場合があります。
  金額を単価で割った値と整合するか検算し、整合するなら小数として解釈してください。
- 返品行は数量・金額をマイナス値にしてください。
- 取扱支店・税込単価・入金・窓口列の値は出力に含めないでください。
""".trimIndent()
}
