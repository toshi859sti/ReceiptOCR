# GreenFrameOCR テストアプリ 仕様書 v2
## Claude Code 実装用

---

## 目的

台紙（ArUcoマーカー）なしで、伝票の緑色外枠を基準点として：
1. 透視変換（デワーピング）
2. 行単位の正確な切り抜き

を実現するテストアプリを構築する。

---

## 技術スタック

| 項目 | 採用技術 |
|------|---------|
| 言語 | Kotlin |
| カメラ | CameraX |
| 画像処理 | OpenCV for Android |
| OCR | ML Kit Text Recognition（オフライン・日本語） |
| アーキテクチャ | MVVM（Compose） |
| 最小SDK | 26 |

---

## 既存アプリからの変更方針

- **維持する機能**：台紙（ArUco）OCR以外のすべての機能（Room DB、全UI画面、カテゴリ再計算、摘要辞書等）
- **削除するファイル**：
  - `util/UnderlyingBaseProcessor.kt`（台紙専用）
  - `util/ImageProcessor.kt`（ArUco/BlockType専用）
  - `ui/UnderlayResultScreen.kt`
  - `ui/TestScreen.kt`
  - `viewmodel/TestViewModel.kt`
- **書き換えるファイル**：
  - `util/OCRProcessor.kt` → GreenFrameDetector呼び出しに変更
  - `viewmodel/CameraViewModel.kt` → GreenFrame対応
  - `ui/CameraScreen.kt` → GreenFrame対応
  - `ui/OcrCaptureScreen.kt` → GreenFrame対応
  - `viewmodel/OcrCaptureViewModel.kt` → GreenFrame対応
  - `navigation/Navigation.kt` → UnderlayResult・TestScreenルート削除
- **追加するファイル**：
  - `util/GreenFrameDetector.kt`（コアロジック）

---

## アプリ画面フロー

既存のナビゲーション構造を維持しつつ、OCRキャプチャ部分をGreenFrame対応に置き換える。

```
MainMenu
  └─ 購買メニュー → OCRキャプチャ（GreenFrame版）
                        └─ 緑枠検出 → 透視変換 → 行切り抜き → OCR → ReceiptInput
```

---

## コアロジック：GreenFrameDetector.kt

```kotlin
object GreenFrameDetector {

    data class DetectionResult(
        val success: Boolean,
        val corners: List<Point>,       // 4頂点（右辺は推定含む）
        val debugBitmap: Bitmap,        // 頂点・輪郭描画済みデバッグ画像
        val dewarpedBitmap: Bitmap?,    // 透視変換後画像
        val binaryBitmap: Bitmap?,      // 二値化後画像（デバッグ用）
        val rowBitmaps: List<Bitmap>,   // 行単位切り抜きリスト
        val errorMessage: String = ""
    )

    fun process(inputBitmap: Bitmap): DetectionResult
}
```

---

## 処理ステップ詳細

### Step 1：HSV緑マスク生成

```kotlin
Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)

// 撮影環境の色相ズレを考慮し広めに設定
val lowerGreen = Scalar(35.0, 30.0, 80.0)
val upperGreen = Scalar(85.0, 255.0, 255.0)
Core.inRange(hsv, lowerGreen, upperGreen, mask)

// 点線 → 実線化（膨張）
val kernel = Imgproc.getStructuringElement(
    Imgproc.MORPH_RECT, Size(15.0, 15.0)
)
Imgproc.dilate(mask, mask, kernel, Point(-1.0, -1.0), 3)
```

---

### Step 2：輪郭検出 + 4点確定（コの字＋たわみ対応）

#### 2-1. minAreaRect で初期3辺を取得

```kotlin
val contours = mutableListOf<MatOfPoint>()
Imgproc.findContours(
    mask, contours, Mat(),
    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
)
val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
    ?: return DetectionResult(success = false, errorMessage = "輪郭未検出")

val rotatedRect = Imgproc.minAreaRect(MatOfPoint2f(*maxContour.toArray()))
val boxPoints = Mat()
Imgproc.boxPoints(rotatedRect, boxPoints)
```

#### 2-2. 右辺の精密推定（「追い込み」ロジック）

`minAreaRect` だけでは伝票のたわみで右端精度が落ちるため、
以下の「スキャン追い込み」を実施する：

```kotlin
fun estimateRightEdge(dewarpBase: Mat, topRight: Point, bottomRight: Point): Pair<Point, Point> {

    // 1. 上辺・下辺の右端点のX座標のうち大きい方を起点とする
    val startX = maxOf(topRight.x, bottomRight.x).toInt()

    // 2. 上端・下端それぞれで右向きに輝度スキャン
    fun scanRightEdge(y: Int, startX: Int): Int {
        var prevBrightness = 255.0
        for (x in startX until dewarpBase.cols()) {
            val brightness = dewarpBase.get(y, x)?.get(0) ?: break
            // 急激な輝度低下 = 用紙端（机の色）
            if (prevBrightness - brightness > 60) return x - 1
            prevBrightness = brightness
        }
        return dewarpBase.cols() - 1
    }

    val trueRightX_top    = scanRightEdge(topRight.y.toInt(),    startX)
    val trueRightX_bottom = scanRightEdge(bottomRight.y.toInt(), startX)

    return Pair(
        Point(trueRightX_top.toDouble(),    topRight.y),
        Point(trueRightX_bottom.toDouble(), bottomRight.y)
    )
}
```

**このロジックが必要な理由：**
- この伝票は上・左・下の3辺のみ緑枠あり（右辺なし）
- 撮影距離・角度が変わると `minAreaRect` の右端推定がズレる
- 用紙と机の輝度差（急激な輝度低下）を実測することで安定化する

---

### Step 2.5：座標バリデーション（クラッシュ防止）

透視変換前に必ず実行する。異常形状を検出してエラー返却。

```kotlin
fun isValidShape(pts: List<Point>, imageWidth: Int, imageHeight: Int): Boolean {
    if (pts.size != 4) return false

    // 1. 凸形状チェック
    val hull = MatOfInt()
    Imgproc.convexHull(MatOfPoint(*pts.toTypedArray()), hull)
    if (hull.toArray().size != 4) return false

    // 2. 面積チェック（画像全体の5%以上）
    val mat = MatOfPoint2f(*pts.toTypedArray())
    val area = Imgproc.contourArea(mat)
    val imageArea = imageWidth * imageHeight
    if (area < imageArea * 0.05) return false

    // 3. 内角チェック（30度以下は異常）
    for (i in pts.indices) {
        val a = pts[(i + pts.size - 1) % pts.size]
        val b = pts[i]
        val c = pts[(i + 1) % pts.size]
        val angle = computeAngle(a, b, c)
        if (angle < 30.0) return false
    }

    return true
}
```

**検出する異常ケース：**
| ケース | 判定 |
|--------|------|
| 凸形状でない（ねじれ） | convexHull ≠ 4点 |
| 極端に細長い台形 | 内角 < 30度 |
| 検出領域が小さすぎ | area < 画像の5% |

---

### Step 3：透視変換

```kotlin
// 出力サイズ：A4横相当に固定
val OUTPUT_WIDTH  = 2100
val OUTPUT_HEIGHT = 1485

// 頂点を左上・右上・右下・左下の順にソート
fun sortCorners(pts: List<Point>): List<Point> {
    val sorted = pts.sortedBy { it.x + it.y }
    val topLeft     = sorted.first()
    val bottomRight = sorted.last()
    val topRight    = pts.minByOrNull { it.y - it.x }!!
    val bottomLeft  = pts.maxByOrNull { it.y - it.x }!!
    return listOf(topLeft, topRight, bottomRight, bottomLeft)
}

val srcPoints = MatOfPoint2f(*sortedCorners.toTypedArray())
val dstPoints = MatOfPoint2f(
    Point(0.0, 0.0),
    Point(OUTPUT_WIDTH.toDouble(), 0.0),
    Point(OUTPUT_WIDTH.toDouble(), OUTPUT_HEIGHT.toDouble()),
    Point(0.0, OUTPUT_HEIGHT.toDouble())
)
val M = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
Imgproc.warpPerspective(
    src, dst, M,
    Size(OUTPUT_WIDTH.toDouble(), OUTPUT_HEIGHT.toDouble())
)
```

---

### Step 4：OCR用前処理（Greenチャンネル二値化）

```kotlin
val channels = mutableListOf<Mat>()
Core.split(bgrImage, channels)
val greenChannel = channels[1]  // BGR順: 0=Blue, 1=Green, 2=Red

// コントラスト強調
val enhanced = Mat()
greenChannel.convertTo(enhanced, -1, 1.5, -30.0)

// 適応的二値化
val binary = Mat()
Imgproc.adaptiveThreshold(
    enhanced, binary,
    255.0,
    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
    Imgproc.THRESH_BINARY,
    21,    // blockSize（奇数、ドット文字に合わせて調整）
    10.0   // C値（緑枠の白飛びを促進）
)
```

**Greenチャンネルを使う理由：**
| チャンネル | 黒文字 | 緑枠 | 採用 |
|-----------|--------|------|------|
| Red   | 暗い | 暗い | ✗ 緑枠も拾う |
| Green | 暗い | **明るい** | ✓ 緑枠が白飛び消滅 |
| Blue  | 暗い | 暗い | ✗ 緑枠も拾う |

---

### Step 5：行検出（動的閾値）

```kotlin
fun detectRowBoundaries(binary: Mat): List<IntRange> {

    val histogram = IntArray(binary.rows())
    for (y in 0 until binary.rows()) {
        var count = 0
        for (x in 0 until binary.cols()) {
            if (binary.get(y, x)[0] < 128) count++
        }
        histogram[y] = count
    }

    val maxDensity = histogram.maxOrNull() ?: return emptyList()
    val threshold  = maxDensity * 0.10

    return groupConsecutiveRows(histogram, threshold.toInt())
}

fun groupConsecutiveRows(histogram: IntArray, threshold: Int): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = -1
    val GAP_TOLERANCE = 3

    var y = 0
    while (y < histogram.size) {
        if (histogram[y] >= threshold) {
            if (start == -1) start = y
        } else {
            if (start != -1) {
                // ギャップ許容：次の3行以内にまた文字があれば継続
                val nextStart = histogram.drop(y).indexOfFirst { it >= threshold }
                if (nextStart in 1..GAP_TOLERANCE) {
                    y += nextStart
                    continue
                }
                ranges.add(start until y)
                start = -1
            }
        }
        y++
    }
    if (start != -1) ranges.add(start until histogram.size)
    return ranges
}
```

---

### Step 6：行Bitmapの切り出し + 解像度保証

```kotlin
fun cropRowWithMinHeight(
    binary: Mat,
    range: IntRange,
    minHeightPx: Int = 100
): Bitmap {

    val padding = 4
    val top    = maxOf(0, range.first - padding)
    val bottom = minOf(binary.rows() - 1, range.last + padding)

    val rowMat = binary.submat(top, bottom, 0, binary.cols())

    return if (rowMat.rows() < minHeightPx) {
        val scale  = minHeightPx.toDouble() / rowMat.rows()
        val scaled = Mat()
        Imgproc.resize(
            rowMat, scaled,
            Size(rowMat.cols() * scale, minHeightPx.toDouble()),
            0.0, 0.0,
            Imgproc.INTER_CUBIC
        )
        scaled.toBitmap()
    } else {
        rowMat.toBitmap()
    }
}
```

---

## ROI定義（透視変換後の相対座標）

```kotlin
data class FieldROI(val name: String, val rect: RectF)

val DETAIL_ROIS = listOf(
    FieldROI("取引日",   RectF(0.02f, 0.28f, 0.10f, 0.85f)),
    FieldROI("商品名",   RectF(0.10f, 0.28f, 0.46f, 0.85f)),
    FieldROI("取扱支店", RectF(0.46f, 0.28f, 0.55f, 0.85f)),
    FieldROI("数量",     RectF(0.55f, 0.28f, 0.62f, 0.85f)),
    FieldROI("税込単価", RectF(0.62f, 0.28f, 0.72f, 0.85f)),
    FieldROI("税込金額", RectF(0.72f, 0.28f, 0.83f, 0.85f))
)
// Y座標は行検出結果で動的に上書きする
```

---

## Step 7：検算バリデーション

```kotlin
fun validateWithRounding(item: PurchaseItem): Boolean {
    val exact = item.unitPrice.toBigDecimal() * item.quantity.toBigDecimal()
    val candidates = listOf(
        exact.setScale(0, java.math.RoundingMode.FLOOR).toInt(),
        exact.setScale(0, java.math.RoundingMode.HALF_UP).toInt(),
        exact.setScale(0, java.math.RoundingMode.CEILING).toInt()
    )
    return candidates.any { it == item.amount }
}

val OCR_CORRECTIONS = mapOf("O" to "0", "l" to "1", "S" to "5", "B" to "8")

fun sanitizeNumber(raw: String): Int? {
    var cleaned = raw
    OCR_CORRECTIONS.forEach { (wrong, correct) ->
        cleaned = cleaned.replace(wrong, correct)
    }
    return cleaned.filter { it.isDigit() || it == '-' }.toIntOrNull()
}
```

---

## 実装優先順位

| 優先度 | ステップ | 内容 |
|--------|---------|------|
| 1 | 不要ファイル削除 | 台紙関連ファイルを削除・パッケージ名変更 |
| 2 | GreenFrameDetector.kt | Step 1-2（緑枠検出） |
| 3 | Step 2.5 | 座標バリデーション |
| 4 | Step 3 | 透視変換 |
| 5 | Step 4 | Greenチャンネル二値化 |
| 6 | Step 5-6 | 行検出 + Bitmap切り出し |
| 7 | CameraViewModel書き換え | GreenFrame対応 |
| 8 | OcrCaptureViewModel書き換え | GreenFrame対応 |
| 9 | Step 7 | ML Kit投入 + 検算バリデーション |

---

## 注意事項

- **HSV閾値は環境依存**：初期値は広めで試し、照明条件に合わせて調整
- **コの字枠**：右辺は必ず推定ロジック（Step 2追い込み）で補完すること
- **バリデーションは Step 2.5 で必須**：省略すると透視変換でクラッシュする
- **ML Kit最小高さ**：40px以下で精度急落。`minHeightPx=100`を必ず守ること
- **ガソリン行の数量**（2920、2850）はリットル×100表記。検算で自動検知可能
- **端数処理**：切り捨て・四捨五入・切り上げの3パターンで検算すること
- **groupConsecutiveRowsのバグ修正済み**：`continue`ではなく`y += nextStart; continue`方式
- **isValidShapeの修正済み**：imageWidth/imageHeightを引数で受け取る方式

---

*v2 作成日：2026年3月*
*対象伝票：島原雲仙農業協同組合 購買代金請求明細書*
