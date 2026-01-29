# System Architecture

Receipt OCRアプリケーションのシステムアーキテクチャドキュメント。

---

## 技術スタック

- **プラットフォーム**: Android
- **言語**: Kotlin
- **UIフレームワーク**: Jetpack Compose
- **カメラ**: CameraX
- **画像処理**: OpenCV 4.9.0
- **OCR**: ML Kit (日本語認識 + Latin)
- **データベース**: Room
- **ビルドツール**: Gradle

---

## 全体フロー

```
[CameraX ImageAnalysis 4K]
         ↓
[ArUco Marker Detection]
    (B_BLOCK: ID 0,1,2,3)
         ↓
[Quality Evaluation]
    - Focus (Laplacian)
    - Character Height (Edge detection)
    - Contrast (Standard deviation)
         ↓
    Quality ≥ 70% ?
         ↓ YES
[Perspective Transform]
    - Dynamic px/mm (10-14)
    - Output: ~4158×2940px
         ↓
[OCR Processing]
    ├─ [Full Page OCR] (Japanese + Latin)
    ├─ [Quantity Column OCR] (Adaptive scale 1-3x, Latin)
    └─ [Product Name Column OCR] (Adaptive scale, Gray + Binary)
         ↓
[Row Clustering]
    - Y-coordinate based (15px threshold)
         ↓
[Column Extraction]
    - Date, Item, Quantity, Amount, Category
         ↓
[Category Assignment]
    - Lookahead from subtotal lines
         ↓
[Dictionary-Based Correction]
    - Levenshtein distance
    - Capacity protection
    - OcrResultEvaluator (Gray vs Binary)
         ↓
[Result Presentation]
    - UnderlayResultScreen
```

---

## 主要コンポーネント

### 1. CameraScreen.kt

**役割**: カメラUI、プレビュー、品質評価、自動撮影

**主要機能:**
- ImageAnalysis設定 (4K解像度: 3840×2160)
- ArUcoマーカー検出 & 可視化 (緑の四角)
- 品質メトリクス表示 (シャープネス, 輝度)
- 自動撮影トリガー (品質≥70%)

**コード例:**
```kotlin
val imageAnalyzer = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(android.util.Size(3840, 2160))  // 4K
    .build()
    .also {
        it.setAnalyzer(cameraExecutor) { imageProxy ->
            // ArUco検出 + 品質評価
            val bitmap = imageProxy.toBitmap()
            val arucoResult = ImageProcessor.detectArUcoMarkers(bitmap)
            val quality = OcrQualityEvaluator.evaluateOcrQuality(bitmap)

            // 品質チェック + 自動撮影
            if (quality.isGood && arucoResult.isDetected) {
                viewModel.processImage(bitmap, arucoResult)
            }
        }
    }
```

**主要定数:**
```kotlin
private const val FOCUS_THRESHOLD = 250.0
private const val BRIGHTNESS_MIN = 40.0
private const val BRIGHTNESS_MAX = 220.0
```

---

### 2. CameraViewModel.kt

**役割**: 画像処理オーケストレーション、辞書補正統合、結果状態管理

**主要機能:**
- 画像処理フロー制御
- 辞書補正の呼び出し
- 結果UIState管理

**コード例:**
```kotlin
fun processImage(bitmap: Bitmap, arucoResult: ArUcoResult) {
    viewModelScope.launch {
        _state.value = UiState.Processing

        try {
            // 透視変換
            val transformed = ImageProcessor.perspectiveTransform(
                bitmap, arucoResult, useFixedOutput = true
            )

            // OCR処理
            val result = OCRProcessor.processUnderlayingBase(transformed)

            // 辞書補正
            val corrected = result.rows.map { row ->
                if (row.rowType == RowType.ITEM && row.itemName != null) {
                    val (correctedName, similarity) =
                        ProductNameCorrector.correctProductName(
                            row.itemName,
                            row.category,
                            productDao
                        )
                    row.copy(itemName = correctedName)
                } else {
                    row
                }
            }

            _state.value = UiState.Success(corrected)
        } catch (e: Exception) {
            _state.value = UiState.Error(e.message ?: "Unknown error")
        }
    }
}
```

---

### 3. ImageProcessor.kt

**役割**: ArUcoマーカー検出、透視変換、画像前処理

**主要機能:**
1. **ArUcoマーカー検出**
   ```kotlin
   fun detectArUcoMarkers(bitmap: Bitmap): ArUcoResult {
       val mat = bitmapToMat(bitmap)
       val gray = Mat()
       Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

       val corners = mutableListOf<Mat>()
       val ids = Mat()
       val dictionary = Aruco.getPredefinedDictionary(Aruco.DICT_4X4_50)

       Aruco.detectMarkers(gray, dictionary, corners, ids)

       return if (ids.rows() >= 4) {
           ArUcoResult(
               corners = corners,
               ids = ids,
               blockType = BlockType.B_BLOCK,
               isDetected = true
           )
       } else {
           ArUcoResult(isDetected = false)
       }
   }
   ```

2. **動的透視変換** (2025-12-31~)
   ```kotlin
   fun perspectiveTransform(
       bitmap: Bitmap,
       arucoResult: ArUcoResult,
       useFixedOutput: Boolean = true
   ): Bitmap {
       // マーカー間距離からpx/mm実測
       val markerCenters = getMarkerCenters(corners, ids, blockType)
       val p0 = markerCenters[0]
       val p1 = markerCenters[1]
       val pxWidth = sqrt((p1.x - p0.x)² + (p1.y - p0.y)²)
       val mmWidth = ARUCO_ID1_X_MM - ARUCO_ID0_X_MM  // 247mm
       val measuredPxPerMm = pxWidth / mmWidth

       // 目標px/mm: 文字高さ30px以上確保
       val targetPxPerMm = when {
           measuredPxPerMm < 10.0 -> 14.0
           measuredPxPerMm < 14.0 -> measuredPxPerMm
           else -> 14.0
       }

       // 出力サイズ計算
       val dstWidth = (paperWidthMm * targetPxPerMm).toInt()   // ~4158px
       val dstHeight = (paperHeightMm * targetPxPerMm).toInt() // ~2940px

       // 透視変換実行
       val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
       Imgproc.warpPerspective(mat, warped, transformMatrix, dstSize)

       return matToBitmap(warped)
   }
   ```

3. **OCR画像前処理**
   ```kotlin
   fun enhanceImageForOCR(bitmap: Bitmap): Bitmap {
       // 1. グレースケール変換
       val grayMat = Mat()
       Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGRA2GRAY)

       // 2. シャープニング (Unsharp Mask)
       val blurred = Mat()
       Imgproc.GaussianBlur(grayMat, blurred, Size(0.0, 0.0), 3.0)
       val sharpened = Mat()
       Core.addWeighted(grayMat, 1.5, blurred, -0.5, 0.0, sharpened)

       // 3. CLAHE
       val clahe = Imgproc.createCLAHE()
       clahe.clipLimit = 2.0
       clahe.tilesGridSize = Size(8.0, 8.0)
       clahe.apply(sharpened, claheMat)

       return matToBitmap(claheMat)
   }
   ```

---

### 4. ImagePreprocessor.kt (2025-12-31~)

**役割**: 文字高さ推定、スケール倍率計算、適応的二値化

**主要機能:**
1. **文字高さ推定**
   ```kotlin
   fun estimateCharHeightPx(bitmap: Bitmap): Float {
       // Bitmap → Mat → Grayscale
       val src = Mat()
       Utils.bitmapToMat(bitmap, src)
       val gray = Mat()
       Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

       // Cannyエッジ検出
       val edges = Mat()
       Imgproc.Canny(gray, edges, 80.0, 160.0)

       // 膨張（文字をまとめる）
       val kernel = Imgproc.getStructuringElement(
           Imgproc.MORPH_RECT, Size(3.0, 3.0)
       )
       Imgproc.dilate(edges, edges, kernel)

       // 輪郭検出
       val contours = mutableListOf<MatOfPoint>()
       Imgproc.findContours(edges, contours, hierarchy,
           Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

       // 高さを収集（6-80pxフィルタ）
       val heights = contours.mapNotNull { cnt ->
           val rect = Imgproc.boundingRect(cnt)
           when {
               rect.height < 6 -> null   // ノイズ
               rect.height > 80 -> null  // 行・罫線
               else -> rect.height.toFloat()
           }
       }

       // 中央値（外れ値に強い）
       return heights.sorted()[heights.size / 2]
   }
   ```

2. **スケール倍率計算**
   ```kotlin
   fun calcScaleFactor(
       currentCharPx: Float,
       targetCharPx: Float = 32f
   ): Float {
       if (currentCharPx <= 0f) return 1f
       val rawScale = targetCharPx / currentCharPx
       return rawScale.coerceIn(1.0f, 3.0f)  // 1-3倍に制限
   }
   ```

3. **適応的二値化**
   ```kotlin
   fun adaptiveThreshold(bitmap: Bitmap): Bitmap {
       val gray = toGray(bitmap)
       val mat = Mat()
       Utils.bitmapToMat(gray, mat)

       val binary = Mat()
       Imgproc.adaptiveThreshold(
           mat, binary, 255.0,
           Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
           Imgproc.THRESH_BINARY,
           11, 2.0
       )

       return matToBitmap(binary)
   }
   ```

---

### 5. OCRProcessor.kt

**役割**: ML Kit OCR実行、列別OCR処理、行座標マッピング

**主要機能:**
1. **全体OCR (日本語+Latin)**
   ```kotlin
   fun processUnderlayingBase(warpedBitmap: Bitmap): OcrResult {
       val recognizer = TextRecognition.getClient(
           TextRecognizerOptions.Builder()
               .setLanguageHint("ja")
               .build()
       )

       val visionImage = InputImage.fromBitmap(warpedBitmap, 0)
       val task = recognizer.process(visionImage)

       return task.await()
   }
   ```

2. **数量列特化OCR (適応的スケーリング1.0-3.0倍, Latin)**
   ```kotlin
   // 1. 数量列切り出し (行単位でROI)
   val quantityColumnBitmap = Bitmap.createBitmap(
       warpedBitmap, quantityX, rowY, quantityWidth, rowHeight
   )

   // 2. グレースケール化 + 罫線除去
   val grayMat = toGray(quantityColumnBitmap)
   val cleanedMat = removeVerticalLines(grayMat)

   // 3. 文字高さ推定 → 適応的スケーリング
   val charPx = ImagePreprocessor.estimateCharHeightSimple(cleanedMat)
   val targetHeight = 30.0
   val scale = (targetHeight / charPx).coerceIn(1.0, 3.0)

   val scaledMat = resize(cleanedMat, scale)

   // 4. Latin OCR
   val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
   val visionImage = InputImage.fromBitmap(scaledMat.toBitmap(), 0)
   val result = recognizer.process(visionImage).await()

   // 5. bbox形状フィルタ + 正規表現チェック
   val quantity = extractQuantityFromResult(result)
   ```

3. **商品名列OCR (適応的スケーリング, 2025-12-31~)**
   ```kotlin
   // 1. 列切り出し
   val itemColumnBitmap = Bitmap.createBitmap(
       warpedBitmap, itemX, 0, itemWidth, height
   )

   // 2. 前処理
   val grayBitmap = ImagePreprocessor.toGray(itemColumnBitmap)
   val enhancedBitmap = ImagePreprocessor.adjustContrast(grayBitmap, 1.2f)

   // 3. 文字高さベース適応的スケーリング
   val charPx = ImagePreprocessor.estimateCharHeightPx(enhancedBitmap)
   val scaleFactor = ImagePreprocessor.calcScaleFactor(charPx, 32f)

   val upscaledBitmap = if (scaleFactor > 1f) {
       Bitmap.createScaledBitmap(
           enhancedBitmap,
           (enhancedBitmap.width * scaleFactor).toInt(),
           (enhancedBitmap.height * scaleFactor).toInt(),
           true
       )
   } else {
       enhancedBitmap
   }

   // 4. OCR実行
   val result = recognizeTextJapanese(upscaledBitmap)
   ```

4. **DoubleOCR処理 (2026-01-01~)**
   ```kotlin
   // Gray版OCR
   val grayResult = recognizeTextJapanese(grayBitmap)

   // Binary版OCR (条件付き)
   val binaryResult = if (charPx >= 18 && edgeDensity >= 0.02) {
       val binaryBitmap = ImagePreprocessor.adaptiveThreshold(enhancedBitmap)
       recognizeTextJapanese(binaryBitmap)
   } else null

   // 評価・選択
   val selectedResult = OcrResultEvaluator.selectBest(
       grayResult, binaryResult, productDao, category
   )
   ```

---

### 6. UnderlyingBaseProcessor.kt

**役割**: 行クラスタリング、列抽出、小計検出、商品名クリーニング

**主要機能:**
1. **行クラスタリング (Y座標ベース, 15px閾値)**
   ```kotlin
   fun clusterRowsByY(boxes: List<OcrBox>): List<List<OcrBox>> {
       val sortedBoxes = boxes.sortedBy { it.bounds.centerY() }
       val clusters = mutableListOf<MutableList<OcrBox>>()

       for (box in sortedBoxes) {
           val y = box.bounds.centerY()

           // 既存クラスタに追加 or 新規作成
           val cluster = clusters.lastOrNull()
           if (cluster != null && abs(y - cluster.first().bounds.centerY()) < 15) {
               cluster.add(box)
           } else {
               clusters.add(mutableListOf(box))
           }
       }

       return clusters
   }
   ```

2. **列抽出**
   ```kotlin
   for (box in cluster) {
       val x = box.bounds.centerX()

       when {
           x in DATE_RANGE -> date = box.text
           x in ITEM_RANGE -> itemName = box.text
           x in QUANTITY_RANGE -> quantity = normalizeQuantity(box.text)
           x in AMOUNT_RANGE -> {
               val isNegative = box.text.contains("-")
               val normalized = normalizeToDigits(box.text)
               val value = normalized.toIntOrNull()
               amount = if (isNegative && value != null) -value else value
           }
           x in CATEGORY_RANGE -> {
               val normalized = normalizeToDigits(box.text)
               categorySum = normalized.toIntOrNull()
           }
       }
   }
   ```

3. **小計検出**
   ```kotlin
   fun detectSubtotalRow(text: String): Boolean {
       val keywords = listOf("小計", "計", "給油所", "給値所", "農業機械", "展業慢城")
       return keywords.any { text.contains(it) }
   }
   ```

4. **商品名クリーニング**
   ```kotlin
   private fun cleanItemName(itemName: String): String {
       // パターン1: OCR誤認識を含む日付 (p71xxx, めE1021)
       val pattern1 = Regex("^.{0,3}[0-9oOlI.:/ ]{4,7}[|]?")
       var cleaned = itemName.replace(pattern1, "")

       // パターン2: 正確な6桁日付 (071011)
       if (cleaned == itemName) {
           val pattern2 = Regex("^\\d{6}[|]?")
           cleaned = itemName.replace(pattern2, "")
       }

       // 前方区切り文字削除
       cleaned = cleaned.trimStart('|', ' ', '　')

       return if (cleaned.isEmpty() || cleaned.length < 2) itemName else cleaned
   }
   ```

5. **カテゴリ割り当て (Lookahead方式)**
   ```kotlin
   fun assignCategories(rows: List<ReceiptRow>): List<ReceiptRow> {
       var currentCategory = "一般購買"

       // 逆順スキャン
       for (i in rows.indices.reversed()) {
           val row = rows[i]

           if (row.rowType == RowType.CATEGORY_SUBTOTAL) {
               currentCategory = when {
                   row.rawText?.contains("給油所") == true ||
                   row.rawText?.contains("給値所") == true -> "給油所"
                   row.rawText?.contains("農業機械") == true ||
                   row.rawText?.contains("展業慢城") == true -> "農業機械"
                   else -> "一般購買"
               }
           }

           if (row.rowType == RowType.ITEM) {
               row.category = currentCategory
           }
       }
   }
   ```

---

### 7. OcrQualityEvaluator.kt (2025-12-31~)

**役割**: OCR品質評価、3指標スコアリング、自動撮影判定

**主要機能:**
1. **総合品質評価**
   ```kotlin
   fun evaluateOcrQuality(bitmap: Bitmap): OcrQuality {
       // ステップ1: フォーカス・コントラスト (1280px - 高速)
       val previewScale = 1280.0f / maxOf(bitmap.width, bitmap.height)
       val previewBitmap = Bitmap.createScaledBitmap(...)
       val grayPreview = ImagePreprocessor.toGray(previewBitmap)

       val focus = calculateSharpness(grayPreview)
       val contrastValue = contrastScore(grayPreview)

       // ステップ2: 文字高さ (2400px - OCRスケール)
       val ocrScale = 2400.0f / maxOf(bitmap.width, bitmap.height)
       val ocrBitmap = Bitmap.createScaledBitmap(...)
       val edge = ImagePreprocessor.detectEdges(ocrBitmap)
       val charHeight = ImagePreprocessor.estimateCharHeight(edge)

       // 総合スコア計算
       val focusScoreValue = focusScore(focus)
       val charHeightScoreValue = charHeightScore(charHeight)

       val totalScore = focusScoreValue * 0.2 +
                        charHeightScoreValue * 0.5 +
                        contrastValue * 0.3

       return OcrQuality(
           score = totalScore,
           focus = focus,
           charHeight = charHeight,
           contrast = contrastValue,
           isGood = totalScore >= QUALITY_THRESHOLD  // 0.70
       )
   }
   ```

2. **フォーカススコア (Laplacian分散)**
   ```kotlin
   private fun calculateSharpness(grayBitmap: Bitmap): Double {
       val mat = Mat()
       Utils.bitmapToMat(grayBitmap, mat)

       val laplacian = Mat()
       Imgproc.Laplacian(mat, laplacian, CvType.CV_64F)

       val mean = Mat()
       val stddev = Mat()
       Core.meanStdDev(laplacian, mean, stddev)

       return stddev.get(0, 0)[0] * stddev.get(0, 0)[0]  // 分散
   }

   private fun focusScore(sharpness: Double): Float {
       return when {
           sharpness < 5 -> 0.0f
           sharpness < 40 -> ((sharpness - 5) / 35 * 0.8).toFloat()
           else -> 1.0f
       }
   }
   ```

3. **文字高さスコア (2400pxスケール対応)**
   ```kotlin
   private fun charHeightScore(height: Float): Float {
       return when {
           height < 15 -> 0.0f
           height < 20 -> lerp(0.0f, 0.4f, (height - 15) / 5)
           height < 25 -> lerp(0.4f, 0.6f, (height - 20) / 5)
           height < 30 -> lerp(0.6f, 0.75f, (height - 25) / 5)
           height <= 40 -> lerp(0.75f, 1.0f, (height - 30) / 10)
           height <= 50 -> 1.0f
           height <= 70 -> 0.95f
           else -> 0.7f
       }
   }
   ```

4. **コントラストスコア**
   ```kotlin
   private fun contrastScore(grayBitmap: Bitmap): Float {
       val mat = Mat()
       Utils.bitmapToMat(grayBitmap, mat)

       val mean = Mat()
       val stddev = Mat()
       Core.meanStdDev(mat, mean, stddev)

       val std = stddev.get(0, 0)[0]
       return (std / 127.5).toFloat().coerceIn(0f, 1f)
   }
   ```

---

### 8. ProductNameCorrector.kt / ProductNameCorrectorV2.kt

**役割**: 辞書ベース商品名補正、容量保護型マッチング

**詳細**: `docs/DICTIONARY.md` 参照

---

### 9. OcrResultEvaluator.kt (2026-01-01~)

**役割**: Gray/Binary OCR結果の評価・選択

**主要機能:**
```kotlin
fun selectBest(
    grayResult: OcrResult,
    binaryResult: OcrResult?,
    productDao: ProductMasterDao,
    category: String
): OcrResult {
    if (binaryResult == null) return grayResult

    val grayScore = evaluateResult(grayResult, productDao, category)
    val binaryScore = evaluateResult(binaryResult, productDao, category)

    return if (binaryScore > grayScore) binaryResult else grayResult
}

private fun evaluateResult(
    result: OcrResult,
    productDao: ProductMasterDao,
    category: String
): Float {
    val dictionaryMatchScore = calcDictionaryMatchScore(result.text, productDao, category)
    val editDistanceScore = calcEditDistanceScore(result.text)
    val numericScore = calcNumericScore(result.text)
    val confidenceScore = result.confidence
    val lengthScore = calcLengthScore(result.text.length)

    return dictionaryMatchScore * 0.40 +
           editDistanceScore * 0.25 +
           numericScore * 0.20 +
           confidenceScore * 0.10 +
           lengthScore * 0.05
}
```

**詳細**: `docs/OCR_SPEC.md` 参照

---

### 10. OcrResultMerger.kt (2026-01-01~)

**役割**: Gray/Binary OCR結果の統合

**データ構造:**
```kotlin
data class DoubleOcrResult(
    val grayResult: OcrResult,
    val binaryResult: OcrResult?,
    val selectedResult: OcrResult
)
```

---

## データベース設計

### ReceiptDatabase (Room)

**エンティティ:**
1. **ProductMaster** - 商品マスタ
2. **OcrVariant** - OCR誤認識パターン
3. **YayoiAccount** - 弥生勘定科目
4. **RakurakuAccount** - らくらく青色申告勘定科目

**DAO:**
- ProductMasterDao
- OcrVariantDao
- YayoiAccountDao
- RakurakuAccountDao

**Migration:**
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE product_master (...)")
        database.execSQL("CREATE TABLE ocr_variants (...)")
        database.execSQL("CREATE TABLE yayoi_accounts (...)")
        database.execSQL("CREATE TABLE rakuraku_accounts (...)")
    }
}
```

**詳細**: `docs/DICTIONARY.md` 参照

---

## UI構成

### 画面一覧
1. **MainActivity** - アプリエントリポイント、権限リクエスト
2. **CameraScreen** - カメラプレビュー、品質表示、ArUcoマーカー可視化
3. **UnderlayResultScreen** - OCR結果表示、編集機能

### Compose UI構成
```kotlin
@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    Box(modifier = Modifier.fillMaxSize()) {
        // カメラプレビュー
        AndroidView(factory = { PreviewView(it) })

        // ArUcoマーカー可視化
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 緑の四角描画
        }

        // 品質表示カード
        Card(modifier = Modifier.align(Alignment.TopCenter)) {
            Column {
                Text("シャープネス: ${sharpness.toInt()} ${if (isFocused) "✓" else "✗"}")
                Text("輝度: ${brightness.toInt()} ${if (isBrightnessGood) "✓" else "✗"}")
                Text(
                    text = if (allGood) "✓ 撮影準備完了" else "カメラを調整してください",
                    color = if (allGood) Color.Green else Color.Red
                )
            }
        }
    }
}
```

---

## ビルド & デプロイ

### ビルドコマンド
```bash
# クリーンビルド (推奨)
.\gradlew.bat clean assembleDebug

# インストール
adb uninstall com.example.receiptorc
adb install "C:\Users\toshiro\AndroidStudioProjects\ReceiptOCR\app\build\outputs\apk\debug\app-debug.apk"

# ログ確認
adb logcat -d -s UnderlyingBaseProcessor:D OCRProcessor:D CameraViewModel:D
```

### Gradle設定
```gradle
dependencies {
    // CameraX
    implementation "androidx.camera:camera-camera2:1.x.x"
    implementation "androidx.camera:camera-lifecycle:1.x.x"
    implementation "androidx.camera:camera-view:1.x.x"

    // OpenCV
    implementation "org.opencv:opencv:4.9.0"

    // ML Kit
    implementation "com.google.mlkit:text-recognition:16.0.0"
    implementation "com.google.mlkit:text-recognition-japanese:16.0.0"

    // Room
    implementation "androidx.room:room-runtime:2.x.x"
    kapt "androidx.room:room-compiler:2.x.x"
    implementation "androidx.room:room-ktx:2.x.x"

    // Compose
    implementation "androidx.compose.ui:ui:1.x.x"
    implementation "androidx.compose.material3:material3:1.x.x"
}
```

---

## 開発ガイドライン

1. **ドキュメント更新**: 新機能実装時は `.clinerules` と各種ドキュメントを更新
2. **クリーンビルド**: ビルド前に必ず `clean` 実行
3. **実機テスト**: 変更後は実機でのテストを実施
4. **ログ活用**: デバッグ時は `Log.d()` を積極的に使用
5. **正規化関数**: 数値処理は必ず `normalizeToDigits()` 適用
6. **クリーニング関数**: 商品名は `cleanItemName()` でクリーニング
7. **品質チェック**: フォーカス + 輝度チェックを通過した画像のみ処理

---

## パフォーマンス最適化

### 品質評価の2段階処理
- **プレビュースケール (1280px)**: フォーカス・コントラスト評価 (高速)
- **OCRスケール (2400px)**: 文字高さ評価 (精度)
- **効果**: 品質評価速度 10-20倍高速化

### 適応的スケーリング
- **旧**: 固定3倍拡大
- **新**: 文字高さ測定 → 動的倍率計算 (1.0-3.0倍)
- **効果**: 無駄な拡大を削減、処理時間短縮

### カメラ解像度最適化
- **ImageAnalysis 4K**: 解像度向上 (1600×1200 → 3264×2448)
- **ImageCapture廃止**: 複雑な座標変換削除 (500行以上削減)

---

## トラブルシューティング

### ArUcoマーカー検出失敗
- **原因**: 照明不足、マーカー汚れ、距離不適切
- **対策**: 輝度チェック (40-220), フォーカスチェック (≥250.0)
- **暫定**: `DEBUG_SKIP_MARKER_CHECK = true` (開発時のみ)

### OCR精度低下
- **原因**: 文字高さ不足 (<30px)
- **対策**: 動的透視変換 (目標px/mm: 14.0), 適応的スケーリング
- **検証**: `estimateCharHeightPx()` で文字高さ測定

### 品質スコア低すぎて撮影されない
- **原因**: 閾値設定ミス、スケール不一致
- **対策**: 評価スケールとOCRスケールを統一、閾値調整
- **デバッグ**: 品質メトリクスをUI表示して確認

---

## 更新履歴

- **2026-01-01**: OcrResultEvaluator, OcrResultMerger, DoubleOCR追加
- **2025-12-31**: ImagePreprocessor, 適応的解像度OCRシステム追加
- **2025-12-31**: OcrQualityEvaluator追加、品質評価システム実装
- **2025-12-30**: 辞書ベース補正システム統合
- **2025-12-29**: ProductNameCorrector, データベース設計実装
- **2025-12-25**: OCR画像前処理 (シャープニング, CLAHE) 追加
- **2025-12-24**: 商品名クリーニング、総合品質管理システム実装
- **2025-12-23**: 行クラスタリング最適化、列範囲再定義
