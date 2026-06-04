# TASK: 一般購買部門（一般レシートOCR）新設

## 概要

一般量販店等のレシートをML Kit（オフライン）でOCRし、
オンライン時はGemini 2.5 Flash APIで商品名・金額を構造化抽出する機能を追加する。
既存のJA伝票（購買部門）とは完全に分離した独立機能として実装する。

---

## 実装方針

- 既存コード（receipt_items・OCRProcessor・GreenFrameDetector等）は**一切変更しない**
- DB は v19 へマイグレーション（v18 は既存）
- 新規ファイルのみ追加する方針で進める
- Gemini API キーはアプリ設定画面（SettingsScreen）で入力・SharedPreferences に保存

---

## Phase 1: データベース（DB v17）

### 新規テーブル 1: `general_receipts`（レシートヘッダー）

```sql
CREATE TABLE general_receipts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,           -- 取引日 (yyyy-MM-dd)
    storeName TEXT NOT NULL DEFAULT '',
    total INTEGER NOT NULL DEFAULT 0,
    rawOcrText TEXT NOT NULL DEFAULT '',  -- ML Kit生テキスト全文
    geminiUsed INTEGER NOT NULL DEFAULT 0, -- Boolean
    createdAt INTEGER NOT NULL    -- Unix timestamp
)
```

### 新規テーブル 2: `general_receipt_items`（明細行）

```sql
CREATE TABLE general_receipt_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    receiptId INTEGER NOT NULL,   -- FK → general_receipts.id
    itemName TEXT NOT NULL DEFAULT '',
    price INTEGER NOT NULL DEFAULT 0,
    category TEXT NOT NULL DEFAULT '未分類',
    tekiyouId INTEGER,            -- nullable FK → rakuraku_tekiyou.id
    FOREIGN KEY (receiptId) REFERENCES general_receipts(id) ON DELETE CASCADE
)
```

### Entityクラス

```kotlin
// data/entity/GeneralReceipt.kt
@Entity(tableName = "general_receipts")
data class GeneralReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val storeName: String = "",
    val total: Int = 0,
    val rawOcrText: String = "",
    val geminiUsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// data/entity/GeneralReceiptItem.kt
@Entity(
    tableName = "general_receipt_items",
    foreignKeys = [ForeignKey(
        entity = GeneralReceipt::class,
        parentColumns = ["id"],
        childColumns = ["receiptId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("receiptId")]
)
data class GeneralReceiptItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val itemName: String = "",
    val price: Int = 0,
    val category: String = "未分類",
    val tekiyouId: Int? = null
)
```

### DAOクラス

```kotlin
// data/dao/GeneralReceiptDao.kt
@Dao
interface GeneralReceiptDao {

    // ヘッダー
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: GeneralReceipt): Long

    @Update
    suspend fun updateReceipt(receipt: GeneralReceipt)

    @Delete
    suspend fun deleteReceipt(receipt: GeneralReceipt)

    @Query("SELECT * FROM general_receipts ORDER BY date DESC, createdAt DESC")
    fun getAllReceipts(): Flow<List<GeneralReceipt>>

    @Query("SELECT * FROM general_receipts WHERE id = :id")
    suspend fun getReceiptById(id: Long): GeneralReceipt?

    // 明細行
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<GeneralReceiptItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: GeneralReceiptItem): Long

    @Update
    suspend fun updateItem(item: GeneralReceiptItem)

    @Delete
    suspend fun deleteItem(item: GeneralReceiptItem)

    @Query("SELECT * FROM general_receipt_items WHERE receiptId = :receiptId ORDER BY id ASC")
    fun getItemsByReceiptId(receiptId: Long): Flow<List<GeneralReceiptItem>>

    @Query("SELECT * FROM general_receipt_items WHERE receiptId = :receiptId ORDER BY id ASC")
    suspend fun getItemsByReceiptIdOnce(receiptId: Long): List<GeneralReceiptItem>

    @Query("DELETE FROM general_receipt_items WHERE receiptId = :receiptId")
    suspend fun deleteItemsByReceiptId(receiptId: Long)

    // CSV出力用
    @Query("""
        SELECT * FROM general_receipt_items
        WHERE (:from IS NULL OR (SELECT date FROM general_receipts WHERE id = receiptId) >= :from)
        AND   (:to   IS NULL OR (SELECT date FROM general_receipts WHERE id = receiptId) <= :to)
        ORDER BY (SELECT date FROM general_receipts WHERE id = receiptId) ASC, id ASC
    """)
    suspend fun getItemsForExport(from: String?, to: String?): List<GeneralReceiptItem>
}
```

### ReceiptDatabase.kt への追記

```kotlin
// @Database の entities リストに追加
entities = [
    // ... 既存 ...
    GeneralReceipt::class,
    GeneralReceiptItem::class,
],
version = 19,

// マイグレーション追加
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS general_receipts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                date TEXT NOT NULL,
                storeName TEXT NOT NULL DEFAULT '',
                total INTEGER NOT NULL DEFAULT 0,
                rawOcrText TEXT NOT NULL DEFAULT '',
                geminiUsed INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
        """)
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS general_receipt_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                receiptId INTEGER NOT NULL,
                itemName TEXT NOT NULL DEFAULT '',
                price INTEGER NOT NULL DEFAULT 0,
                category TEXT NOT NULL DEFAULT '未分類',
                tekiyouId INTEGER,
                FOREIGN KEY (receiptId) REFERENCES general_receipts(id) ON DELETE CASCADE
            )
        """)
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_general_receipt_items_receiptId ON general_receipt_items(receiptId)"
        )
    }
}

// addMigrations に追加
.addMigrations(MIGRATION_18_19)
```

---

## Phase 2: Gemini API クライアント

### 依存関係追加

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

※ Gson はすでに依存済み（`com.google.code.gson:gson:2.10.1`）

### GeminiReceiptClient.kt

```kotlin
// util/GeminiReceiptClient.kt
object GeminiReceiptClient {

    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    data class ReceiptParseResult(
        val storeName: String,
        val date: String,       // yyyy-MM-dd 形式。不明なら ""
        val items: List<ParsedItem>,
        val total: Int
    )

    data class ParsedItem(
        val name: String,
        val price: Int
    )

    /**
     * ML Kitで取得したOCRテキストをGeminiに渡し、構造化JSONを返す。
     * @param ocrText ML Kitの認識テキスト全文
     * @param apiKey  Gemini APIキー
     * @return ReceiptParseResult（失敗時は null）
     */
    suspend fun parseReceipt(ocrText: String, apiKey: String): ReceiptParseResult? =
        withContext(Dispatchers.IO) {
            val prompt = buildPrompt(ocrText)

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
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
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$API_URL?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                parseGeminiResponse(body)
            } catch (e: Exception) {
                Log.e("GeminiReceiptClient", "API error: ${e.message}")
                null
            }
        }

    private fun buildPrompt(ocrText: String): String = """
以下はレシートをOCRで読み取ったテキストです。
このテキストから以下のJSON形式で情報を抽出してください。
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

OCRテキスト:
$ocrText
""".trimIndent()

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
```

---

## Phase 3: ViewModel

### GeneralReceiptViewModel.kt

```kotlin
// viewmodel/GeneralReceiptViewModel.kt
class GeneralReceiptViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ReceiptDatabase.getDatabase(application)
    private val dao = db.generalReceiptDao()
    private val prefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    val receipts: StateFlow<List<GeneralReceipt>> =
        dao.getAllReceipts().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 撮影・OCR処理後の確認用一時データ
    private val _pendingReceipt = MutableStateFlow<GeneralReceipt?>(null)
    val pendingReceipt: StateFlow<GeneralReceipt?> = _pendingReceipt

    private val _pendingItems = MutableStateFlow<List<GeneralReceiptItem>>(emptyList())
    val pendingItems: StateFlow<List<GeneralReceiptItem>> = _pendingItems

    // 処理状態
    sealed class UiState {
        object Idle : UiState()
        object OcrRunning : UiState()
        object GeminiRunning : UiState()
        object GeminiUnavailable : UiState()  // オフライン or APIキー未設定
        data class Done(val receiptId: Long) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    /**
     * ML Kit OCR完了後に呼び出す。
     * ocrText: ML Kitで取得したテキスト全文
     */
    fun onOcrCompleted(ocrText: String) {
        viewModelScope.launch {
            val apiKey = prefs.getString("gemini_api_key", "") ?: ""
            val isOnline = isNetworkAvailable()

            if (apiKey.isNotBlank() && isOnline) {
                _uiState.value = UiState.GeminiRunning
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
                    // Gemini失敗 → テキストそのまま表示
                    fallbackToRawOcr(ocrText)
                }
            } else {
                // オフライン or APIキー未設定 → フォールバック
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

    /** 確認画面でユーザーが編集・確定したデータをDBに保存 */
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

    fun deleteReceipt(receipt: GeneralReceipt) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteReceipt(receipt) }
    }

    fun updateItem(item: GeneralReceiptItem) {
        viewModelScope.launch(Dispatchers.IO) { dao.updateItem(item) }
    }

    fun deleteItem(item: GeneralReceiptItem) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteItem(item) }
    }

    suspend fun getItemsForReceipt(receiptId: Long): List<GeneralReceiptItem> =
        withContext(Dispatchers.IO) { dao.getItemsByReceiptIdOnce(receiptId) }

    /** CSV出力用データ取得 */
    suspend fun getItemsForExport(from: String?, to: String?): List<GeneralReceiptItem> =
        withContext(Dispatchers.IO) { dao.getItemsForExport(from, to) }

    private fun isNetworkAvailable(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }
}
```

---

## Phase 4: 画面実装

### 4-1. GeneralPurchaseMenuScreen.kt

```
ui/GeneralPurchaseMenuScreen.kt
```

ボタン構成:
- 「レシート撮影・OCR」→ `general_receipt_capture` ルートへ遷移
- 「レシート一覧」→ `general_receipt_list` ルートへ遷移
- 「CSV出力」→ `general_receipt_output` ルートへ遷移

既存の `PurchaseMenuScreen.kt` を参考に同じレイアウトで作成すること。

---

### 4-2. GeneralReceiptCaptureScreen.kt

```
ui/GeneralReceiptCaptureScreen.kt
```

**処理フロー:**

```
1. CameraX静止画キャプチャ（ImageCapture）
   - シャッターボタン1つ
   - プレビューのみ表示（GreenFrameDetectorは使わない）

2. ML Kit OCR実行
   - InputImage.fromBitmap(bitmap, 0)
   - 日本語モデル（TextRecognizer Japanese）を使用
   - 認識結果の全テキストを結合して文字列を生成

3. viewModel.onOcrCompleted(ocrText) を呼ぶ

4. uiState を監視:
   - OcrRunning → 「OCR処理中...」ローディング表示
   - GeminiRunning → 「AIで解析中...」ローディング表示
   - GeminiUnavailable → Snackbar「オフラインのため手動入力が必要です」
   - Idle（pendingReceiptがnon-null）→ 確認画面へ自動遷移
   - Error → エラーダイアログ
```

**注意点:**
- `ImageCapture` ユースケースを使う（ImageAnalysisではない）
- `takePicture(executor, callback)` で Bitmap を取得する
- ML Kit の TextRecognizer は既存の `OCRProcessor` と同じモデルを流用可
- カメラ権限チェックは既存実装を参照

---

### 4-3. GeneralReceiptConfirmScreen.kt

```
ui/GeneralReceiptConfirmScreen.kt
```

**表示内容:**

```
┌─────────────────────────────────┐
│ 店舗名: [編集可能テキストフィールド] │
│ 日付:   [編集可能・yyyy-MM-dd]   │
│ Gemini使用: ✓ / ✗               │
├─────────────────────────────────┤
│ 商品一覧                         │
│ ┌──────────────────┬──────────┐  │
│ │ 商品名           │ 金額     │  │
│ ├──────────────────┼──────────┤  │
│ │ [編集可能]       │ [編集可] │  │
│ │ ...              │ ...      │  │
│ └──────────────────┴──────────┘  │
│ [+ 行追加]                       │
├─────────────────────────────────┤
│ 合計: ¥X,XXX                    │
├─────────────────────────────────┤
│ [キャンセル]    [保存]            │
└─────────────────────────────────┘
```

**備考:**
- Gemini未使用（フォールバック）時は `rawOcrText` をスクロール表示し、
  ユーザーが見ながら手動入力できるレイアウトにする
- 合計は `items.sumOf { it.price }` でリアルタイム計算
- 保存ボタンで `viewModel.saveReceipt()` を呼ぶ

---

### 4-4. GeneralReceiptListScreen.kt

```
ui/GeneralReceiptListScreen.kt
```

**表示内容:**
- レシート一覧をカード形式で表示（日付・店舗名・合計・Gemini使用フラグ）
- 長押しで削除確認ダイアログ
- タップで明細詳細（読み取り専用）を表示

---

### 4-5. GeneralReceiptOutputScreen.kt

```
ui/GeneralReceiptOutputScreen.kt
```

**CSV出力仕様（既存の購買CSVと同形式）:**

```
ヘッダー: ID,日付,摘要,メモ,金額
日付形式: yyyy/MM/dd（スラッシュ区切り）
摘要: tekiyouId が設定されていれば rakuraku_tekiyou.tekiyouName、なければ空文字
メモ: general_receipt_items.itemName
金額: general_receipt_items.price
```

- 日付範囲フィルター（from/to）を設置
- SAF（Storage Access Framework）でファイル保存
- ファイル名: `一般購買_YYYYMMDD_HHmmss.csv`

---

## Phase 5: ナビゲーション追加

### Navigation.kt への追記

```kotlin
// ルート定数追加
const val ROUTE_GENERAL_PURCHASE_MENU = "general_purchase_menu"
const val ROUTE_GENERAL_RECEIPT_CAPTURE = "general_receipt_capture"
const val ROUTE_GENERAL_RECEIPT_CONFIRM = "general_receipt_confirm"
const val ROUTE_GENERAL_RECEIPT_LIST = "general_receipt_list"
const val ROUTE_GENERAL_RECEIPT_OUTPUT = "general_receipt_output"

// NavHost に追加
composable(ROUTE_GENERAL_PURCHASE_MENU) {
    GeneralPurchaseMenuScreen(navController)
}
composable(ROUTE_GENERAL_RECEIPT_CAPTURE) {
    GeneralReceiptCaptureScreen(navController, viewModel)
}
composable(ROUTE_GENERAL_RECEIPT_CONFIRM) {
    GeneralReceiptConfirmScreen(navController, viewModel)
}
composable(ROUTE_GENERAL_RECEIPT_LIST) {
    GeneralReceiptListScreen(navController, viewModel)
}
composable(ROUTE_GENERAL_RECEIPT_OUTPUT) {
    GeneralReceiptOutputScreen(navController, viewModel)
}
```

### MenuScreen.kt への追記

既存の「購買部門」「預金部門」ボタンの間に以下を追加:

```kotlin
MenuButton(
    text = "一般購買部門",
    onClick = { navController.navigate(ROUTE_GENERAL_PURCHASE_MENU) }
)
```

---

## Phase 6: 設定画面にGemini APIキー入力欄を追加

### SettingsScreen.kt への追記

```kotlin
// Gemini APIキー入力フィールドを追加
var geminiApiKey by remember {
    mutableStateOf(prefs.getString("gemini_api_key", "") ?: "")
}

OutlinedTextField(
    value = geminiApiKey,
    onValueChange = { geminiApiKey = it },
    label = { Text("Gemini APIキー") },
    placeholder = { Text("AIza...") },
    visualTransformation = PasswordVisualTransformation(),
    trailingIcon = {
        IconButton(onClick = {
            prefs.edit().putString("gemini_api_key", geminiApiKey).apply()
        }) {
            Icon(Icons.Default.Save, contentDescription = "保存")
        }
    },
    modifier = Modifier.fillMaxWidth()
)
```

---

## 実装順序

| 順序 | Phase | 内容 |
|------|-------|------|
| 1 | Phase 1 | DB v17 マイグレーション・Entity・DAO |
| 2 | Phase 2 | GeminiReceiptClient.kt |
| 3 | Phase 3 | GeneralReceiptViewModel.kt |
| 4 | Phase 5 | Navigation.kt・MenuScreen.kt 修正 |
| 5 | Phase 4-1 | GeneralPurchaseMenuScreen.kt |
| 6 | Phase 4-2 | GeneralReceiptCaptureScreen.kt |
| 7 | Phase 4-3 | GeneralReceiptConfirmScreen.kt |
| 8 | Phase 4-4 | GeneralReceiptListScreen.kt |
| 9 | Phase 4-5 | GeneralReceiptOutputScreen.kt |
| 10 | Phase 6 | SettingsScreen.kt Gemini APIキー追加 |

---

## 注意事項・制約

### 既存コードへの影響ゼロ原則
- `receipt_items` / `sheet_data` / `monthly_data` は変更しない
- `OCRProcessor` / `GreenFrameDetector` / `ProductNameCorrectorV3` は変更しない
- `ReceiptDatabase.kt` への追記のみ（既存マイグレーションは触らない）

### OpenCV・BGR変換
- `GeneralReceiptCaptureScreen` では OpenCV を使わない
- ML Kit には `InputImage.fromBitmap(bitmap, 0)` で直接渡す

### ML Kitモデル
- 日本語モデルは既存アプリで assets にバンドル済みのため追加不要
- `TextRecognition.getClient(JapaneseTextRecognizerOptions.defaultOptions())` を使用

### ネットワーク権限
- `AndroidManifest.xml` に `INTERNET` 権限が既に宣言されている場合はそのまま
- 未宣言の場合は追加: `<uses-permission android:name="android.permission.INTERNET" />`
- `ACCESS_NETWORK_STATE` も同様に確認・追加

### Gemini API 無料枠
- モデル: `gemini-2.5-flash`（無料枠 1,500 リクエスト/日）
- テキストのみ送信のため1リクエストあたりのトークンは少ない
- APIキー未設定・オフライン時は必ずフォールバックすること

### エラーハンドリング
- Gemini APIタイムアウト（30秒）でフォールバック
- HTTP 429（レート制限）時はSnackbarで「APIの利用上限に達しました」を表示
- JSONパース失敗時もフォールバック

---

## ファイル一覧（新規作成）

```
app/src/main/java/com/example/greenframeocr/
├── data/
│   ├── entity/
│   │   ├── GeneralReceipt.kt          （新規）
│   │   └── GeneralReceiptItem.kt      （新規）
│   └── dao/
│       └── GeneralReceiptDao.kt       （新規）
├── util/
│   └── GeminiReceiptClient.kt         （新規）
├── viewmodel/
│   └── GeneralReceiptViewModel.kt     （新規）
└── ui/
    ├── GeneralPurchaseMenuScreen.kt   （新規）
    ├── GeneralReceiptCaptureScreen.kt （新規）
    ├── GeneralReceiptConfirmScreen.kt （新規）
    ├── GeneralReceiptListScreen.kt    （新規）
    └── GeneralReceiptOutputScreen.kt  （新規）

app/src/main/java/com/example/greenframeocr/
├── data/
│   └── ReceiptDatabase.kt             （追記: v17マイグレーション・Entity・DAO登録）
├── navigation/
│   └── Navigation.kt                  （追記: ルート追加）
└── ui/
    ├── MenuScreen.kt                  （追記: 一般購買部門ボタン）
    └── SettingsScreen.kt              （追記: Gemini APIキー入力欄）
```
