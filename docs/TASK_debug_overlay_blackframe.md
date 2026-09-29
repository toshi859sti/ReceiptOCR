# TASK: デバッグ画面リアルタイムオーバーレイ + BlackFrameDetector

## 目的

伝票を大きく（近づいて）撮影したとき、緑枠の角が画角に入りきらず
透視変換が不安定になる問題を調査・解決するため、以下を実装する。

1. **緑枠オーバーレイ**: `GreenFrameDetector` の検出結果をリアルタイム表示
2. **BlackFrameDetector**: 色に依存しない「画面内最大矩形」検出（黒罫線ベース）
3. **デバッグ画面への統合**: 緑枠（緑色）と黒枠（青色）を並行表示して比較

---

## 実装方針

- **変更対象**: `CameraViewModel.kt`・`DebugCaptureScreen.kt`
- **新規追加**: `util/BlackFrameDetector.kt`
- **既存コード（GreenFrameDetector・CameraScreen・OcrCaptureScreen）は変更しない**
- BlackFrameDetector は 500ms 間隔の間引き処理（負荷対策）
- 両検出器の結果を `StateFlow` で画面に流してオーバーレイ描画

---

## Part 1: BlackFrameDetector.kt（新規作成）

```kotlin
// util/BlackFrameDetector.kt

object BlackFrameDetector {

    data class DetectionResult(
        val success: Boolean,
        val corners: List<Point>,   // 4頂点（左上・右上・右下・左下の順）
        val imageWidth: Int,
        val imageHeight: Int,
        val errorMessage: String = ""
    )

    /**
     * 入力Bitmapから黒い罫線で構成された最大矩形を検出する。
     * Hough直線検出で水平・垂直線を抽出し、4交点を算出する。
     *
     * @param inputBitmap CameraX ImageAnalysis から取得した RGB Bitmap
     * @return DetectionResult（検出失敗時は success=false）
     */
    fun process(inputBitmap: Bitmap): DetectionResult {
        val imageWidth = inputBitmap.width
        val imageHeight = inputBitmap.height

        // Bitmap → Mat（RGBA）
        val rgbaMat = Mat()
        Utils.bitmapToMat(inputBitmap, rgbaMat)

        // RGBA → BGR
        val bgrMat = Mat()
        Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
        rgbaMat.release()

        // グレースケール変換
        val grayMat = Mat()
        Imgproc.cvtColor(bgrMat, grayMat, Imgproc.COLOR_BGR2GRAY)
        bgrMat.release()

        // Gaussian ブラーでノイズ除去
        val blurredMat = Mat()
        Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)
        grayMat.release()

        // Canny エッジ検出
        val edgeMat = Mat()
        Imgproc.Canny(blurredMat, edgeMat, 50.0, 150.0)
        blurredMat.release()

        // Hough 直線検出（確率的ハフ変換）
        val lines = Mat()
        Imgproc.HoughLinesP(
            edgeMat,
            lines,
            rho = 1.0,
            theta = Math.PI / 180.0,
            threshold = 80,          // 直線として認定する最低投票数
            minLineLength = imageWidth * 0.15,  // 画像幅の15%以上の線のみ採用
            maxLineGap = 20.0        // 20px以内のギャップは同一直線として結合
        )
        edgeMat.release()

        if (lines.rows() == 0) {
            lines.release()
            return DetectionResult(
                success = false,
                corners = emptyList(),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                errorMessage = "直線未検出"
            )
        }

        // 水平線・垂直線に分類
        val horizontalLines = mutableListOf<IntArray>()
        val verticalLines = mutableListOf<IntArray>()

        for (i in 0 until lines.rows()) {
            val line = lines.get(i, 0)
            val x1 = line[0].toInt()
            val y1 = line[1].toInt()
            val x2 = line[2].toInt()
            val y2 = line[3].toInt()

            val angle = Math.abs(Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()))
                .let { Math.toDegrees(it) }

            when {
                angle < 15.0 || angle > 165.0 -> horizontalLines.add(intArrayOf(x1, y1, x2, y2))
                angle in 75.0..105.0 -> verticalLines.add(intArrayOf(x1, y1, x2, y2))
            }
        }
        lines.release()

        if (horizontalLines.size < 2 || verticalLines.size < 2) {
            return DetectionResult(
                success = false,
                corners = emptyList(),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                errorMessage = "水平線${horizontalLines.size}本・垂直線${verticalLines.size}本（最低2本ずつ必要）"
            )
        }

        // 上端・下端・左端・右端を特定
        // 水平線: Y座標が最小 = 上端、最大 = 下端
        val topLine = horizontalLines.minByOrNull { minOf(it[1], it[3]) }!!
        val bottomLine = horizontalLines.maxByOrNull { maxOf(it[1], it[3]) }!!

        // 垂直線: X座標が最小 = 左端、最大 = 右端
        val leftLine = verticalLines.minByOrNull { minOf(it[0], it[2]) }!!
        val rightLine = verticalLines.maxByOrNull { maxOf(it[0], it[2]) }!!

        // 4交点を算出（直線の交点計算）
        val topLeft = lineIntersection(topLine, leftLine)
            ?: Point(minOf(leftLine[0], leftLine[2]).toDouble(),
                     minOf(topLine[1], topLine[3]).toDouble())
        val topRight = lineIntersection(topLine, rightLine)
            ?: Point(maxOf(rightLine[0], rightLine[2]).toDouble(),
                     minOf(topLine[1], topLine[3]).toDouble())
        val bottomRight = lineIntersection(bottomLine, rightLine)
            ?: Point(maxOf(rightLine[0], rightLine[2]).toDouble(),
                     maxOf(bottomLine[1], bottomLine[3]).toDouble())
        val bottomLeft = lineIntersection(bottomLine, leftLine)
            ?: Point(minOf(leftLine[0], leftLine[2]).toDouble(),
                     maxOf(bottomLine[1], bottomLine[3]).toDouble())

        val corners = listOf(topLeft, topRight, bottomRight, bottomLeft)

        // バリデーション: 面積が画像全体の5%以上
        val area = Imgproc.contourArea(MatOfPoint2f(*corners.toTypedArray()))
        val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
        if (area < imageArea * 0.05) {
            return DetectionResult(
                success = false,
                corners = corners,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                errorMessage = "検出矩形が小さすぎ（面積比: ${String.format("%.1f", area / imageArea * 100)}%）"
            )
        }

        return DetectionResult(
            success = true,
            corners = corners,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    /**
     * 2直線の交点を算出する。
     * line: IntArray[x1, y1, x2, y2]
     */
    private fun lineIntersection(line1: IntArray, line2: IntArray): Point? {
        val x1 = line1[0].toDouble(); val y1 = line1[1].toDouble()
        val x2 = line1[2].toDouble(); val y2 = line1[3].toDouble()
        val x3 = line2[0].toDouble(); val y3 = line2[1].toDouble()
        val x4 = line2[2].toDouble(); val y4 = line2[3].toDouble()

        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (Math.abs(denom) < 1e-10) return null  // 平行線

        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        return Point(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    }
}
```

---

## Part 2: CameraViewModel.kt への追記

### 追加する StateFlow

```kotlin
// CameraViewModel.kt に追加

// ── デバッグオーバーレイ用 ──────────────────────────────

// 緑枠検出結果（毎フレーム更新）
data class FrameOverlayState(
    val corners: List<Point>,
    val success: Boolean,
    val imageWidth: Int,
    val imageHeight: Int
)

private val _greenFrameOverlay = MutableStateFlow<FrameOverlayState?>(null)
val greenFrameOverlay: StateFlow<FrameOverlayState?> = _greenFrameOverlay

// 黒枠検出結果（500ms間隔で更新）
private val _blackFrameOverlay = MutableStateFlow<FrameOverlayState?>(null)
val blackFrameOverlay: StateFlow<FrameOverlayState?> = _blackFrameOverlay

// 黒枠検出の最終実行時刻
private var lastBlackFrameDetectionMs = 0L
private val BLACK_FRAME_INTERVAL_MS = 500L
```

### ImageAnalysis コールバックへの追記

既存の ImageAnalysis コールバック内、`GreenFrameDetector.process()` 呼び出しの直後に追記する。

```kotlin
// 既存コード（イメージ）:
val result = GreenFrameDetector.process(bitmap, debugMode = false)

// ↓ 以下を追記 ──────────────────────────────────────────

// 緑枠オーバーレイ更新（毎フレーム）
_greenFrameOverlay.value = FrameOverlayState(
    corners = result.corners,
    success = result.success,
    imageWidth = bitmap.width,
    imageHeight = bitmap.height
)

// 黒枠検出（500ms間隔の間引き処理）
val now = System.currentTimeMillis()
if (now - lastBlackFrameDetectionMs > BLACK_FRAME_INTERVAL_MS) {
    lastBlackFrameDetectionMs = now
    // BlackFrameDetector はブロッキング処理のため
    // すでに Dispatchers.Default で動いているはず（既存処理に準じること）
    val blackResult = BlackFrameDetector.process(bitmap)
    _blackFrameOverlay.value = FrameOverlayState(
        corners = blackResult.corners,
        success = blackResult.success,
        imageWidth = blackResult.imageWidth,
        imageHeight = blackResult.imageHeight
    )
}

// ↑ 追記ここまで ──────────────────────────────────────────

// 既存コード（キャプチャ判定等）はそのまま続ける
```

---

## Part 3: DebugCaptureScreen.kt への追記

### オーバーレイ Composable

```kotlin
// DebugCaptureScreen.kt に追加

/**
 * カメラプレビュー上に緑枠（緑）と黒枠（青）をオーバーレイ描画する
 */
@Composable
private fun FrameDetectionOverlay(
    greenOverlay: CameraViewModel.FrameOverlayState?,
    blackOverlay: CameraViewModel.FrameOverlayState?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {

        // ── 緑枠オーバーレイ ──────────────────────────
        greenOverlay?.let { state ->
            if (state.corners.size == 4) {
                drawFrameOverlay(
                    corners = state.corners,
                    imageWidth = state.imageWidth,
                    imageHeight = state.imageHeight,
                    strokeColor = if (state.success) Color.Green else Color.Red,
                    dotColor = Color.Yellow,
                    strokeWidth = 3.dp.toPx(),
                    dotRadius = 8.dp.toPx()
                )
            }
        }

        // ── 黒枠オーバーレイ（青色）──────────────────
        blackOverlay?.let { state ->
            if (state.corners.size == 4 && state.success) {
                drawFrameOverlay(
                    corners = state.corners,
                    imageWidth = state.imageWidth,
                    imageHeight = state.imageHeight,
                    strokeColor = Color.Cyan,
                    dotColor = Color.White,
                    strokeWidth = 2.dp.toPx(),
                    dotRadius = 6.dp.toPx()
                )
            }
        }
    }
}

/**
 * 4頂点の枠線と頂点マーカーを描画する共通関数
 */
private fun DrawScope.drawFrameOverlay(
    corners: List<org.opencv.core.Point>,
    imageWidth: Int,
    imageHeight: Int,
    strokeColor: Color,
    dotColor: Color,
    strokeWidth: Float,
    dotRadius: Float
) {
    val scaleX = size.width / imageWidth.toFloat()
    val scaleY = size.height / imageHeight.toFloat()

    val screenCorners = corners.map { corner ->
        Offset(
            x = corner.x.toFloat() * scaleX,
            y = corner.y.toFloat() * scaleY
        )
    }

    // 4辺を描画
    val path = Path().apply {
        moveTo(screenCorners[0].x, screenCorners[0].y)
        screenCorners.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path = path, color = strokeColor, style = Stroke(width = strokeWidth))

    // 4頂点に丸印
    screenCorners.forEach { corner ->
        drawCircle(
            color = dotColor,
            radius = dotRadius,
            center = corner,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
```

### DebugCaptureScreen のレイアウト統合

既存の `DebugCaptureScreen` の `Box` 内に以下を追加する。

```kotlin
@Composable
fun DebugCaptureScreen(navController: NavController, viewModel: CameraViewModel) {

    // 追加する State 収集
    val greenOverlay by viewModel.greenFrameOverlay.collectAsState()
    val blackOverlay by viewModel.blackFrameOverlay.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {

        // ① 既存のカメラプレビュー（変更しない）
        // ...

        // ② フレーム検出オーバーレイ（新規追加）
        FrameDetectionOverlay(
            greenOverlay = greenOverlay,
            blackOverlay = blackOverlay,
            modifier = Modifier.fillMaxSize()
        )

        // ③ 検出状態テキスト（既存の鮮鋭度表示の下に追加）
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            // 既存の鮮鋭度テキストはそのまま維持

            // 緑枠検出状態
            Text(
                text = when {
                    greenOverlay == null -> "緑枠: 待機中"
                    greenOverlay!!.success -> "緑枠: OK ✓"
                    else -> "緑枠: NG ✗"
                },
                color = when {
                    greenOverlay == null -> Color.White
                    greenOverlay!!.success -> Color.Green
                    else -> Color.Red
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            // 黒枠検出状態
            Text(
                text = when {
                    blackOverlay == null -> "黒枠: 待機中"
                    blackOverlay!!.success -> "黒枠: OK ✓"
                    else -> "黒枠: NG ✗"
                },
                color = when {
                    blackOverlay == null -> Color.White
                    blackOverlay!!.success -> Color.Cyan
                    else -> Color.Gray
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // ④ 既存のシャッターボタン・その他UIはそのまま維持
    }
}
```

---

## 画面の見た目（完成イメージ）

```
┌─────────────────────────────────┐
│ 鮮鋭度: 1234 ✓                  │  ← 既存
│ 緑枠: OK ✓  （緑文字）          │  ← 新規
│ 黒枠: OK ✓  （シアン文字）      │  ← 新規
│                                  │
│    ┌ ─ ─ ─ ─ ─ ─ ─ ┐           │
│    │  緑色の枠線      │           │  ← GreenFrameDetector
│    └ ─ ─ ─ ─ ─ ─ ─ ┘           │
│   ┌──────────────────┐          │
│   │  シアン色の枠線   │          │  ← BlackFrameDetector
│   └──────────────────┘          │
│                                  │
│         [シャッター]              │  ← 既存
└─────────────────────────────────┘
```

- **緑色枠**: GreenFrameDetector の検出結果（毎フレーム更新）
- **シアン枠**: BlackFrameDetector の検出結果（0.5秒間隔更新）
- **赤色枠**: 緑枠検出失敗時
- **黄色丸**: 緑枠の4頂点
- **白色丸**: 黒枠の4頂点

---

## 注意事項

### RGBA→BGR 変換（必須）
`BlackFrameDetector.process()` 内では
`Utils.bitmapToMat` → `COLOR_RGBA2BGR` の変換を必ず行うこと。
（`development-guidelines.md` の OpenCV ガイドライン参照）

### BlackFrameDetector の実行スレッド
ImageAnalysis コールバックは既存コードに準じたスレッドで実行されているはず。
`BlackFrameDetector.process()` はブロッキング処理のため、
**必ず UI スレッド以外（Dispatchers.Default）で実行すること**。
既存の `GreenFrameDetector.process()` と同じスレッドで呼び出せばよい。

### Hough 直線検出のパラメータ調整
初回テストで検出精度が低い場合は以下を調整する:

| パラメータ | 初期値 | 調整方向 |
|---|---|---|
| `threshold` | 80 | 検出過多→増やす・検出なし→減らす |
| `minLineLength` | 画像幅×0.15 | 短い線も拾いたい→減らす |
| `maxLineGap` | 20.0 | 点線対応→増やす |
| Canny 下閾値 | 50.0 | エッジ検出感度調整 |
| Canny 上閾値 | 150.0 | エッジ検出感度調整 |

### Mat のメモリ解放
`BlackFrameDetector` 内で生成した全 `Mat` は
`release()` で確実に解放すること（既存の `GreenFrameDetector` に準じる）。

---

## 完了条件

- [ ] デバッグ画面のカメラプレビュー上に緑枠（緑/赤）がリアルタイム表示される
- [ ] デバッグ画面のカメラプレビュー上に黒枠（シアン）が約0.5秒間隔で更新表示される
- [ ] 画面左上に「緑枠: OK/NG」「黒枠: OK/NG」のテキストが表示される
- [ ] 通常撮影画面（CameraScreen・OcrCaptureScreen）の動作に影響がない
- [ ] ビルドが通る・実機でクラッシュしない

---

## 変更ファイル一覧

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `util/BlackFrameDetector.kt` | **新規作成** | Hough直線検出による黒枠検出 |
| `viewmodel/CameraViewModel.kt` | 追記 | `FrameOverlayState`・`greenFrameOverlay`・`blackFrameOverlay` StateFlow追加、ImageAnalysisコールバックに更新処理追記 |
| `ui/DebugCaptureScreen.kt` | 追記 | `FrameDetectionOverlay` Composable追加・既存レイアウトに組み込み |
