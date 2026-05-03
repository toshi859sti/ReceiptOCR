package com.example.greenframeocr.util

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private const val TAG = "GreenFrameDetector"

// 透視変換出力の解像度（px/mm）
// ReceiptOCR実績: 8.1px/mm（精度良好）
// 15px/mm: 行高さ≈48px → ML Kit推奨100px近傍は数量列特化OCRで確保
private const val WARP_PX_PER_MM = 15.0
private const val RECEIPT_WIDTH_MM  = 203.0
private const val RECEIPT_HEIGHT_MM = 148.0

object GreenFrameDetector {

    private val LOWER_GREEN = Scalar(35.0, 50.0, 60.0)
    private val UPPER_GREEN = Scalar(85.0, 255.0, 255.0)

    data class CaptureInfo(
        val inputWidth: Int = 0,
        val inputHeight: Int = 0,
        val warpWidth: Int = 0,
        val warpHeight: Int = 0,
        val sharpness: Double = 0.0,
        val capturedAt: String = ""
    )

    data class DetectionResult(
        val success: Boolean,
        val corners: List<Point>,
        val debugBitmap: Bitmap,
        val maskBitmap: Bitmap?,
        val dewarpedBitmap: Bitmap?,
        val captureInfo: CaptureInfo = CaptureInfo(),
        val rowBitmaps: List<Bitmap>,
        val errorMessage: String = ""
    )

    data class FieldROI(val name: String, val rect: RectF)

    val DETAIL_ROIS = listOf(
        FieldROI("取引日",   RectF(0.02f, 0.28f, 0.10f, 0.85f)),
        FieldROI("商品名",   RectF(0.10f, 0.28f, 0.46f, 0.85f)),
        FieldROI("取扱支店", RectF(0.46f, 0.28f, 0.55f, 0.85f)),
        FieldROI("数量",     RectF(0.55f, 0.28f, 0.62f, 0.85f)),
        FieldROI("税込単価", RectF(0.62f, 0.28f, 0.72f, 0.85f)),
        FieldROI("税込金額", RectF(0.72f, 0.28f, 0.83f, 0.85f))
    )

    // -----------------------------------------------------------------------
    // メインエントリーポイント
    //
    // 1. 緑マスク外側輪郭を白線で描画
    // 2. 緑線のない右辺の黒枠を検出して白線で描画
    // -----------------------------------------------------------------------

    fun process(inputBitmap: Bitmap, debugMode: Boolean = false, sharpness: Double = 0.0): DetectionResult {
        // bitmapToMat は RGBA 4ch を返すため BGR 3ch に変換してから使う
        // （そうしないと toBitmap() の COLOR_BGR2RGBA が正しく機能しない）
        val rgba = Mat()
        Utils.bitmapToMat(inputBitmap, rgba)
        val src = Mat()
        Imgproc.cvtColor(rgba, src, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        val debugMat = src.clone()

        return try {
            val totalStart = System.currentTimeMillis()
            var t = totalStart
            val imgW = src.cols()
            val imgH = src.rows()

            // Step 1: 緑マスク生成
            val greenMask  = buildGreenMask(src)
            val maskBitmap = greenMask.toBitmap()
            Log.d(TAG, "[PERF] Step1 緑マスク生成: ${System.currentTimeMillis() - t} ms")

            val greenPixels    = Core.countNonZero(greenMask)
            val minGreenPixels = (imgW * imgH * 0.002).toInt()
            Log.d(TAG, "緑ピクセル数: $greenPixels (最小: $minGreenPixels)")

            if (greenPixels < minGreenPixels) {
                greenMask.release()
                return buildFailure(debugMat, maskBitmap, "緑枠が検出できませんでした（緑ピクセル不足）")
            }

            // Step 2: 外側輪郭を白線で描画
            t = System.currentTimeMillis()
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val outerMask = Mat()
            Imgproc.dilate(greenMask, outerMask, k, Point(-1.0, -1.0), 5)
            k.release()
            greenMask.release()

            val outerContours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                outerMask.clone(), outerContours, Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            outerMask.release()
            Log.d(TAG, "[PERF] Step2 外側輪郭: ${System.currentTimeMillis() - t} ms  (${outerContours.size}個)")
            Log.d(TAG, "外側輪郭: ${outerContours.size}個")

            val thick = (imgW * 0.002).toInt().coerceAtLeast(3)

            // 色凡例（BGR）:
            //   白  (255,255,255) … Step2 緑枠外側
            //   マゼンタ(255,0,255) … Step3 中心枠モルフォロジー
            //   黄  (  0,255,255) … Step4 4コーナー＋外形
            val WHITE   = Scalar(255.0, 255.0, 255.0)
            val MAGENTA = Scalar(255.0,   0.0, 255.0)
            val YELLOW  = Scalar(  0.0, 255.0, 255.0)

            // Step 2: 緑枠外側 → 白
            Imgproc.drawContours(debugMat, outerContours, -1, WHITE, thick)

            // 最大輪郭のバウンディングボックスを受票領域として利用
            val largest = outerContours.maxByOrNull { Imgproc.contourArea(it) }
            val bounds  = largest?.let { Imgproc.boundingRect(it) }
            Log.d(TAG, "受票バウンド: $bounds")

            val topY    = bounds?.y?.coerceAtLeast(0) ?: 0
            val bottomY = bounds?.let { (it.y + it.height).coerceAtMost(imgH - 1) } ?: (imgH - 1)
            val leftX   = bounds?.x ?: 0
            // Step 3: 中心枠モルフォロジー → マゼンタ
            t = System.currentTimeMillis()
            val frameContours = detectCentralFrame(src, topY, bottomY, leftX, imgW, imgH)
            Log.d(TAG, "[PERF] Step3 中心枠検出: ${System.currentTimeMillis() - t} ms  (${frameContours.size}個)")
            Log.d(TAG, "中心枠輪郭: ${frameContours.size}個")
            Imgproc.drawContours(debugMat, frameContours, -1, MAGENTA, thick)

            // Step 4: 枠の右辺線分を検出し、緑線まで延長して4コーナーを計算
            val receiptH      = bottomY - topY
            val receiptW      = (receiptH * 1.42).toInt()
            val expectedRight = (leftX + receiptW).coerceAtMost(imgW - 1)
            val projLeft      = (leftX + receiptW * 6 / 10).coerceAtMost(imgW - 1)

            // 枠右辺の線分（HoughLinesP で傾きも取得）
            t = System.currentTimeMillis()
            val rightSeg = findFrameRightEdgeSegment(
                src, topY, bottomY, leftX, projLeft, expectedRight, imgW, imgH)
            Log.d(TAG, "[PERF] Step4a 右辺線分検出: ${System.currentTimeMillis() - t} ms")
            Log.d(TAG, "枠右辺線分: ${rightSeg?.let{"(%.0f,%.0f)-(%.0f,%.0f)".format(it.first.x,it.first.y,it.second.x,it.second.y)} ?: "null"}")

            // 緑線の方程式（HoughLines）
            t = System.currentTimeMillis()
            val lineEqs = detectGreenLineEquations(src, imgW, imgH, leftX)
            Log.d(TAG, "[PERF] Step4b 緑線方程式: ${System.currentTimeMillis() - t} ms")

            val tl: Point; val tr: Point; val br: Point; val bl: Point

            if (lineEqs != null && rightSeg != null) {
                val rightLine = FittedLine(rightSeg.first, rightSeg.second)
                tl = lineEqs.left.intersect(lineEqs.top)
                    ?: Point(leftX.toDouble(), topY.toDouble())
                bl = lineEqs.left.intersect(lineEqs.bot)
                    ?: Point(leftX.toDouble(), bottomY.toDouble())
                tr = rightLine.intersect(lineEqs.top)
                    ?: Point(expectedRight.toDouble(), topY.toDouble())
                br = rightLine.intersect(lineEqs.bot)
                    ?: Point(expectedRight.toDouble(), bottomY.toDouble())

                Log.d(TAG, "TL=(%.0f,%.0f) TR=(%.0f,%.0f) BR=(%.0f,%.0f) BL=(%.0f,%.0f)"
                    .format(tl.x,tl.y, tr.x,tr.y, br.x,br.y, bl.x,bl.y))
            } else {
                // フォールバック: バウンディングボックス
                val fx = rightSeg?.let { ((it.first.x + it.second.x) / 2) } ?: expectedRight.toDouble()
                tl = Point(leftX.toDouble(), topY.toDouble())
                tr = Point(fx, topY.toDouble())
                br = Point(fx, bottomY.toDouble())
                bl = Point(leftX.toDouble(), bottomY.toDouble())
            }

            // 4コーナーと外形を黄色で描画
            val corners = listOf(tl, tr, br, bl)
            for (i in corners.indices) {
                Imgproc.line(debugMat, corners[i], corners[(i + 1) % corners.size], YELLOW, thick)
            }
            // 各コーナーの内角を計算して描画
            val cornerLabels = listOf("TL", "TR", "BR", "BL")
            val angleOffsets = listOf(
                Point( 30.0,  60.0),  // TL: 右下
                Point(-200.0,  60.0), // TR: 左下
                Point(-200.0, -30.0), // BR: 左上
                Point( 30.0, -30.0)   // BL: 右上
            )
            corners.forEachIndexed { idx, pt ->
                val prev = corners[(idx + 3) % 4]
                val next = corners[(idx + 1) % 4]
                val v1x = prev.x - pt.x;  val v1y = prev.y - pt.y
                val v2x = next.x - pt.x;  val v2y = next.y - pt.y
                val dot  = v1x * v2x + v1y * v2y
                val mag1 = Math.hypot(v1x, v1y);  val mag2 = Math.hypot(v2x, v2y)
                val angleDeg = if (mag1 > 0 && mag2 > 0)
                    Math.toDegrees(Math.acos((dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)))
                else 0.0
                Imgproc.circle(debugMat, pt, (imgW * 0.006).toInt(), YELLOW, -1)
                val label = "${cornerLabels[idx]}:${"%.1f".format(angleDeg)}°"
                val off = angleOffsets[idx]
                Imgproc.putText(debugMat, label, Point(pt.x + off.x, pt.y + off.y),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 1.8, YELLOW, 4)
                Log.d(TAG, "${cornerLabels[idx]} angle=${"%.1f".format(angleDeg)}°")
            }

            // Step 5: 透視変換（warpPerspective） ─ 4コーナー → 伝票実寸比率に正規化
            // 伝票サイズ: 203mm × 148mm（A5横を途中で切った形状）
            // WARP_PX_PER_MM px/mm で動的計算
            t = System.currentTimeMillis()
            val warpW  = (RECEIPT_WIDTH_MM  * WARP_PX_PER_MM).toInt()  // 3045px at 15px/mm
            val warpH  = (RECEIPT_HEIGHT_MM * WARP_PX_PER_MM).toInt()  // 2220px at 15px/mm
            val srcPts = MatOfPoint2f(tl, tr, br, bl)
            val dstPts = MatOfPoint2f(
                Point(0.0,              0.0),
                Point(warpW.toDouble(), 0.0),
                Point(warpW.toDouble(), warpH.toDouble()),
                Point(0.0,              warpH.toDouble())
            )
            val perspM    = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            val warpedMat = Mat()
            Imgproc.warpPerspective(src, warpedMat, perspM,
                Size(warpW.toDouble(), warpH.toDouble()), Imgproc.INTER_LINEAR)
            perspM.release(); srcPts.release(); dstPts.release()
            Log.d(TAG, "[PERF] Step5 透視変換(${warpW}×${warpH}): ${System.currentTimeMillis() - t} ms")

            // Step 6: 中央枠（明細エリア）検出 → シアン矩形でデバッグ描画
            t = System.currentTimeMillis()
            val centralRect = findCentralFrame(warpedMat)
            Log.d(TAG, "[PERF] Step6 中央枠検出: ${System.currentTimeMillis() - t} ms")
            Log.d(TAG, "中央枠: $centralRect")
            if (centralRect != null) {
                Imgproc.rectangle(
                    warpedMat,
                    Point(centralRect.x.toDouble(), centralRect.y.toDouble()),
                    Point((centralRect.x + centralRect.width).toDouble(),
                          (centralRect.y + centralRect.height).toDouble()),
                    Scalar(0.0, 255.0, 255.0), 6
                )
            }
            val dewarpedBitmap = warpedMat.toBitmap()

            // Step 7: 明細行の検出・切り抜き（デバッグ時のみ）
            t = System.currentTimeMillis()
            val rowBitmaps = if (debugMode) {
                detectDetailRows(warpedMat).also {
                    Log.d(TAG, "[PERF] Step7 行検出・切り抜き: ${System.currentTimeMillis() - t} ms  (${it.size}行)")
                    Log.d(TAG, "明細行数: ${it.size}")
                }
            } else {
                Log.d(TAG, "[PERF] Step7 行検出・切り抜き: スキップ（本番モード）")
                emptyList()
            }

            warpedMat.release()

            val captureInfo = CaptureInfo(
                inputWidth  = inputBitmap.width,
                inputHeight = inputBitmap.height,
                warpWidth   = (RECEIPT_WIDTH_MM  * WARP_PX_PER_MM).toInt(),
                warpHeight  = (RECEIPT_HEIGHT_MM * WARP_PX_PER_MM).toInt(),
                sharpness   = sharpness,
                capturedAt  = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                  .format(java.util.Date())
            )

            Log.d(TAG, "[PERF] ===== GreenFrameDetector 合計: ${System.currentTimeMillis() - totalStart} ms =====")
            DetectionResult(
                success        = true,
                corners        = emptyList(),
                debugBitmap    = debugMat.toBitmap(),
                maskBitmap     = maskBitmap,
                dewarpedBitmap = dewarpedBitmap,
                captureInfo    = captureInfo,
                rowBitmaps     = rowBitmaps
            )

        } catch (e: Exception) {
            Log.e(TAG, "処理エラー", e)
            buildFailure(debugMat, null, "処理中にエラーが発生: ${e.message}")
        } finally {
            src.release()
            debugMat.release()
        }
    }

    // -----------------------------------------------------------------------
    // トリガー判定用の軽量・高速枠検出（プレビューフレーム専用）
    //
    // 全処理を 640px 相当で実行し、4コーナーを最速で返す。
    // ・2400pxリサイズなし・HoughLinesP なし・適応二値化なし
    // ・緑マスクのバウンディングボックス + A5 比率で右辺を推定
    // -----------------------------------------------------------------------

    fun detectCornersFast(inputBitmap: Bitmap): List<Point>? {
        val rgba = Mat()
        Utils.bitmapToMat(inputBitmap, rgba)
        val src = Mat()
        Imgproc.cvtColor(rgba, src, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        // 640px にダウンスケール（処理量を 1/4 以下に削減）
        val scale = 640.0 / maxOf(src.cols(), src.rows())
        val small = Mat()
        Imgproc.resize(src, small, Size(src.cols() * scale, src.rows() * scale))
        src.release()

        val smallW = small.cols()
        val smallH = small.rows()

        return try {
            val greenMask = buildGreenMask(small)
            small.release()

            val greenPixels = Core.countNonZero(greenMask)
            if (greenPixels < (smallW * smallH * 0.002).toInt()) {
                greenMask.release()
                return null
            }

            // 膨張して線セグメントを繋いでからバウンディングボックスを取得
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            val dilated = Mat()
            Imgproc.dilate(greenMask, dilated, k, Point(-1.0, -1.0), 3)
            k.release(); greenMask.release()

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                dilated, contours, Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            dilated.release()

            // 横長（幅>高さ×0.8）かつ画像左端3%より右にある最大輪郭を採用
            // 背景の偽検知（縦長の細いノイズ）を除外するため
            val minLeftX = smallW * 0.03
            val validContours = contours.filter { c ->
                val b = Imgproc.boundingRect(c)
                b.x > minLeftX || b.width > b.height * 0.5
            }
            val largest = (validContours.ifEmpty { contours }).maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val bounds = Imgproc.boundingRect(largest)

            val topY    = bounds.y.toDouble()
            val bottomY = (bounds.y + bounds.height).toDouble()
            val leftX   = bounds.x.toDouble().coerceAtLeast(minLeftX)
            // 右辺はA5横比率(1.42)で推定（右辺に緑枠なし）
            val rightX  = (leftX + (bottomY - topY) * 1.42).coerceAtMost(smallW - 1.0)

            // 640px → 入力画像座標に戻す
            val inv = 1.0 / scale
            listOf(
                Point(leftX  * inv, topY    * inv),  // TL
                Point(rightX * inv, topY    * inv),  // TR
                Point(rightX * inv, bottomY * inv),  // BR
                Point(leftX  * inv, bottomY * inv)   // BL
            )
        } catch (e: Exception) {
            Log.e(TAG, "detectCornersFast error", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // 緑線の方程式を HoughLines で取得
    //
    // 戻り値: (topRho, topTheta, botRho, botTheta, leftRho, leftTheta)
    // 取得できない場合は null
    // -----------------------------------------------------------------------

    /** 2点で表現された直線。交点・座標補完などを提供 */
    data class FittedLine(val p1: Point, val p2: Point) {
        val dx get() = p2.x - p1.x
        val dy get() = p2.y - p1.y

        /** 2直線の交点。平行なら null */
        fun intersect(other: FittedLine): Point? {
            // p1 + t*(dx,dy) = other.p1 + s*(other.dx, other.dy)
            val det = dx * (-other.dy) - dy * (-other.dx)
            if (Math.abs(det) < 1e-6) return null
            val t = ((other.p1.x - p1.x) * (-other.dy) - (other.p1.y - p1.y) * (-other.dx)) / det
            return Point(p1.x + t * dx, p1.y + t * dy)
        }
        /** 指定 y での x 値（近垂直線用） */
        fun xAtY(y: Double) = if (Math.abs(dy) > 1e-6) p1.x + (y - p1.y) / dy * dx else p1.x
        /** 指定 x での y 値（近水平線用） */
        fun yAtX(x: Double) = if (Math.abs(dx) > 1e-6) p1.y + (x - p1.x) / dx * dy else p1.y
    }

    data class LineEqs(val top: FittedLine, val bot: FittedLine, val left: FittedLine)

    private fun detectGreenLineEquations(src: Mat, imgW: Int, imgH: Int, approxLeftX: Int = 0): LineEqs? {
        val greenMask = buildGreenMask(src)
        val minLen    = (maxOf(imgW, imgH) * 0.05).toDouble()
        val minVotes  = (maxOf(imgW, imgH) * 0.05).toInt().coerceAtLeast(40)
        val segments  = Mat()
        Imgproc.HoughLinesP(greenMask, segments, 1.0, Math.PI / 180.0,
            minVotes, minLen, minLen * 0.3)
        // greenMask は外縁スキャンに使うため後で解放する

        data class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
            val len  get() = Math.hypot(x2 - x1, y2 - y1).coerceAtLeast(1.0)
            val midX get() = (x1 + x2) / 2.0
            val midY get() = (y1 + y2) / 2.0
        }
        val hSegs = mutableListOf<Seg>()  // 水平（上辺・下辺）
        val vSegs = mutableListOf<Seg>()  // 垂直（左辺）

        for (i in 0 until segments.rows()) {
            val v  = segments.get(i, 0)
            val dx = Math.abs(v[2] - v[0]); val dy = Math.abs(v[3] - v[1])
            val ln = Math.hypot(dx, dy); if (ln < 1.0) continue
            when {
                dy / ln < 0.3 -> hSegs.add(Seg(v[0], v[1], v[2], v[3]))
                dx / ln < 0.3 -> vSegs.add(Seg(v[0], v[1], v[2], v[3]))
            }
        }
        segments.release()
        Log.d(TAG, "緑線分: H=${hSegs.size} V=${vSegs.size}")
        if (hSegs.size < 2 || vSegs.isEmpty()) { greenMask.release(); return null }

        // --- クラスタリング ---
        fun clusterByMidY(segs: List<Seg>): List<List<Seg>> {
            val sorted = segs.sortedBy { it.midY }
            val tol = imgH * 0.03
            val clusters = mutableListOf<MutableList<Seg>>(); var cur = mutableListOf(sorted[0])
            for (s in sorted.drop(1)) {
                if (s.midY - cur.last().midY < tol) cur.add(s)
                else { clusters.add(cur); cur = mutableListOf(s) }
            }
            clusters.add(cur); return clusters
        }
        fun clusterByMidX(segs: List<Seg>): List<List<Seg>> {
            val sorted = segs.sortedBy { it.midX }
            val tol = imgW * 0.03
            val clusters = mutableListOf<MutableList<Seg>>(); var cur = mutableListOf(sorted[0])
            for (s in sorted.drop(1)) {
                if (s.midX - cur.last().midX < tol) cur.add(s)
                else { clusters.add(cur); cur = mutableListOf(s) }
            }
            clusters.add(cur); return clusters
        }

        // --- セグメント中心フィット（フォールバック用）---
        fun fitH(segs: List<Seg>): FittedLine {
            var sw=0.0; var swX=0.0; var swY=0.0; var swXX=0.0; var swXY=0.0
            for (seg in segs) { val w = seg.len
                for ((px,py) in listOf(seg.x1 to seg.y1, seg.x2 to seg.y2)) {
                    sw+=w; swX+=px*w; swY+=py*w; swXX+=px*px*w; swXY+=px*py*w } }
            val det = sw*swXX - swX*swX
            val a = if (Math.abs(det)>1e-6) (sw*swXY-swX*swY)/det else 0.0
            val b = (swY - a*swX) / sw
            return FittedLine(Point(0.0, b), Point(imgW.toDouble(), a*imgW+b))
        }
        fun fitV(segs: List<Seg>): FittedLine {
            var sw=0.0; var swY=0.0; var swX=0.0; var swYY=0.0; var swXY=0.0
            for (seg in segs) { val w = seg.len
                for ((px,py) in listOf(seg.x1 to seg.y1, seg.x2 to seg.y2)) {
                    sw+=w; swY+=py*w; swX+=px*w; swYY+=py*py*w; swXY+=px*py*w } }
            val det = sw*swYY - swY*swY
            val a = if (Math.abs(det)>1e-6) (sw*swXY-swY*swX)/det else 0.0
            val b = (swX - a*swY) / sw
            return FittedLine(Point(b, 0.0), Point(a*imgH+b, imgH.toDouble()))
        }

        // ---------------------------------------------------------------
        // 緑帯の外縁フィット（緑マスクをピクセルスキャン）
        //
        // 上辺外縁: 上→下スキャン、列ごとに最初の緑ピクセル = 最小y（外側上端）
        // 下辺外縁: 下→上スキャン、列ごとに最初の緑ピクセル = 最大y（外側下端）
        // 左辺外縁: 左→右スキャン、行ごとに最初の緑ピクセル = 最小x（外側左端）
        // ---------------------------------------------------------------

        /** 水平緑帯の外縁をフィット。useMaxY=false: 上辺(外縁=min y), true: 下辺(外縁=max y) */
        fun fitHOuter(segs: List<Seg>, useMaxY: Boolean): FittedLine {
            val allY = segs.flatMap { listOf(it.y1, it.y2) }
            val pad  = (imgH * 0.02).toInt().coerceAtLeast(10)
            val yFrom = (allY.min()!! - pad).toInt().coerceAtLeast(0)
            val yTo   = (allY.max()!! + pad).toInt().coerceAtMost(imgH - 1)

            val edgeY = IntArray(imgW) { -1 }
            val rowBuf = ByteArray(imgW)
            if (!useMaxY) {
                // 上辺外縁: 上→下へ走査、列ごとに最初の緑ピクセル（最小y）
                for (y in yFrom..yTo) {
                    greenMask.get(y, 0, rowBuf)
                    for (x in 0 until imgW) {
                        if (edgeY[x] < 0 && rowBuf[x].toInt() and 0xFF > 0) edgeY[x] = y
                    }
                }
            } else {
                // 下辺外縁: 下→上へ走査、列ごとに最初の緑ピクセル（最大y）
                for (y in yTo downTo yFrom) {
                    greenMask.get(y, 0, rowBuf)
                    for (x in 0 until imgW) {
                        if (edgeY[x] < 0 && rowBuf[x].toInt() and 0xFF > 0) edgeY[x] = y
                    }
                }
            }
            val pts = (0 until imgW).filter { edgeY[it] >= 0 }
            Log.d(TAG, "fitHOuter(useMaxY=$useMaxY): ${pts.size}点")
            if (pts.size < 5) return fitH(segs)

            var sw=0.0; var swX=0.0; var swY=0.0; var swXX=0.0; var swXY=0.0
            for (x in pts) {
                val px = x.toDouble(); val py = edgeY[x].toDouble()
                sw+=1.0; swX+=px; swY+=py; swXX+=px*px; swXY+=px*py
            }
            val det = sw*swXX - swX*swX
            val a = if (Math.abs(det)>1e-6) (sw*swXY-swX*swY)/det else 0.0
            val b = (swY - a*swX) / sw
            return FittedLine(Point(0.0, b), Point(imgW.toDouble(), a*imgW+b))
        }

        /** 垂直緑帯の外縁をフィット（左辺: 左→右スキャン、行ごとに最初の緑ピクセル = 最小x） */
        fun fitVOuter(segs: List<Seg>): FittedLine {
            val allX = segs.flatMap { listOf(it.x1, it.x2) }
            val pad  = (imgW * 0.02).toInt().coerceAtLeast(10)
            val xFrom = (allX.min()!! - pad).toInt().coerceAtLeast(0)
            val xTo   = (allX.max()!! + pad).toInt().coerceAtMost(imgW - 1)

            val edgeX = IntArray(imgH) { -1 }
            val rowBuf = ByteArray(imgW)
            for (y in 0 until imgH) {
                greenMask.get(y, 0, rowBuf)
                for (x in xFrom..xTo) {
                    if (rowBuf[x].toInt() and 0xFF > 0) { edgeX[y] = x; break }
                }
            }
            val pts = (0 until imgH).filter { edgeX[it] >= 0 }
            Log.d(TAG, "fitVOuter: ${pts.size}点")
            if (pts.size < 5) return fitV(segs)

            var sw=0.0; var swY=0.0; var swX=0.0; var swYY=0.0; var swXY=0.0
            for (y in pts) {
                val py = y.toDouble(); val px = edgeX[y].toDouble()
                sw+=1.0; swY+=py; swX+=px; swYY+=py*py; swXY+=px*py
            }
            val det = sw*swYY - swY*swY
            val a = if (Math.abs(det)>1e-6) (sw*swXY-swY*swX)/det else 0.0
            val b = (swX - a*swY) / sw
            return FittedLine(Point(b, 0.0), Point(a*imgH+b, imgH.toDouble()))
        }

        // 水平クラスタ: 総長が長い上位2つを上辺・下辺として採用
        val hClusters = clusterByMidY(hSegs)
            .filter { it.sumOf { s -> s.len } >= minLen }
            .sortedByDescending { it.sumOf { s -> s.len } }
        if (hClusters.size < 2) { Log.w(TAG, "水平クラスタ不足: ${hClusters.size}"); greenMask.release(); return null }
        val topBot = hClusters.take(2)
            .sortedBy { grp -> grp.sumOf { s -> s.midY * s.len } / grp.sumOf { s -> s.len } }

        // 垂直クラスタ: 左辺として採用するクラスタを選択
        // approxLeftX が有効な場合はそれに最も近いクラスタを選ぶ（背景の偽緑による誤検知を排除）
        // approxLeftX が不明（0）の場合は画像幅の3%以上にある最左クラスタを採用
        val vClusters = clusterByMidX(vSegs)
            .filter { it.sumOf { s -> s.len } >= minLen * 0.5 }
        if (vClusters.isEmpty()) { Log.w(TAG, "垂直クラスタなし"); greenMask.release(); return null }
        val minEdgeX = imgW * 0.03  // 画像左端3%以内は背景ノイズとみなして除外
        val candidateClusters = vClusters.filter { grp ->
            grp.sumOf { s -> s.midX * s.len } / grp.sumOf { s -> s.len } > minEdgeX
        }.ifEmpty { vClusters }
        val leftCluster = if (approxLeftX > 0) {
            candidateClusters.minByOrNull { grp ->
                val midX = grp.sumOf { s -> s.midX * s.len } / grp.sumOf { s -> s.len }
                Math.abs(midX - approxLeftX)
            }!!
        } else {
            candidateClusters.minByOrNull { grp ->
                grp.sumOf { s -> s.midX * s.len } / grp.sumOf { s -> s.len }
            }!!
        }
        Log.d(TAG, "leftCluster midX=${(leftCluster.sumOf { it.midX * it.len } / leftCluster.sumOf { it.len }).toInt()} approxLeftX=$approxLeftX")

        // 緑帯の外縁でフィット（セグメント中心フィットより正確）
        val topLine  = fitHOuter(topBot[0], useMaxY = false)  // 上辺外縁 = min y
        val botLine  = fitHOuter(topBot[1], useMaxY = true)   // 下辺外縁 = max y
        val leftLine = fitVOuter(leftCluster)                  // 左辺外縁 = min x
        greenMask.release()

        Log.d(TAG, "緑線外縁フィット: top=${topLine.p1.y.toInt()}-${topLine.p2.y.toInt()} " +
            "bot=${botLine.p1.y.toInt()}-${botLine.p2.y.toInt()} " +
            "left=${leftLine.p1.x.toInt()}-${leftLine.p2.x.toInt()}")
        return LineEqs(topLine, botLine, leftLine)
    }

    // -----------------------------------------------------------------------
    // 垂直プロジェクション（列方向合計）による明細枠右辺検出
    //
    // 印字（インク）= 白 の二値化画像を列方向に合計し、
    // 右側から走査して「黒ピクセルが密集している最初の列」を右辺とする。
    // 枠線が掠れていても列内の文字密度で検出できるため堅牢。
    // -----------------------------------------------------------------------

    private fun buildInkMask(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val binary = Mat()
        // THRESH_BINARY_INV: 印字（暗い）= 白 (255)、紙地（明るい）= 黒 (0)
        Imgproc.threshold(gray, binary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        gray.release()
        return binary
    }

    private fun findTableRightEdgeByProjection(
        binary: Mat,
        topY: Double,
        bottomY: Double,
        searchLeft: Int,
        searchRight: Int
    ): Double {
        val roiY  = topY.toInt().coerceAtLeast(0)
        val roiH  = (bottomY - topY).toInt().coerceAtMost(binary.rows() - roiY)
        val imgW  = binary.cols()
        if (roiH <= 0) return searchRight.toDouble()

        // ROI 内で列方向合計（Core.reduce で一括計算）
        val tableRegion = binary.submat(roiY, roiY + roiH, 0, imgW)
        val colSums = Mat()
        Core.reduce(tableRegion, colSums, 0, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)

        val sums = IntArray(imgW)
        colSums.get(0, 0, sums)
        colSums.release()

        // 枠線とみなす閾値: ROI 高さの 30% 以上の行に印字があること
        val threshold = (roiH * 255 * 0.3).toInt()
        Log.d(TAG, "プロジェクション閾値: $threshold  探索: x=$searchLeft..$searchRight")

        // searchRight → searchLeft の範囲を右から走査
        for (x in searchRight.coerceAtMost(imgW - 1) downTo searchLeft.coerceAtLeast(0)) {
            if (sums[x] > threshold) {
                Log.d(TAG, "プロジェクション peak: x=$x sum=${sums[x]}")
                return x.toDouble()
            }
        }

        Log.w(TAG, "プロジェクション: ピーク未検出、フォールバック=$searchRight")
        return searchRight.toDouble()
    }

    // -----------------------------------------------------------------------
    // 枠右辺の精密化（垂直プロジェクション）
    //
    // モルフォロジーで得た枠の右端 approxRightX を起点に、
    // ±bandHalf の狭い列バンドで垂直プロジェクション（列合計）を計算し、
    // 右から走査して最初の高密度列を精密な右辺とする。
    // -----------------------------------------------------------------------

    private fun refineFrameRightEdge(
        src: Mat,
        frameX: Int, frameY: Int, frameW: Int, frameH: Int,
        imgW: Int, imgH: Int
    ): Double {
        val approxRight = frameX + frameW
        // 探索幅: フレーム幅の ±10%（最低 30px）
        val bandHalf = (frameW * 0.10).toInt().coerceAtLeast(30)
        val x1 = (approxRight - bandHalf).coerceAtLeast(0)
        val x2 = (approxRight + bandHalf).coerceAtMost(imgW - 1)
        val y1 = frameY.coerceAtLeast(0)
        val y2 = (frameY + frameH).coerceAtMost(imgH - 1)
        val roiH = y2 - y1
        val bandW = x2 - x1
        if (roiH <= 0 || bandW <= 0) return approxRight.toDouble()

        // 印字マスク（インク=白）で列合計を取得
        val binary = buildInkMask(src)
        val roi     = binary.submat(y1, y2, x1, x2)
        val colSums = Mat()
        Core.reduce(roi, colSums, 0, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
        binary.release()

        val sums = IntArray(bandW)
        colSums.get(0, 0, sums)
        colSums.release()

        // 閾値: ROI 高さの 15% 以上の行に印字 → 枠線とみなす
        val threshold = (roiH * 255 * 0.15).toInt()

        // 右から走査して最初の高密度列を採用
        for (i in bandW - 1 downTo 0) {
            if (sums[i] > threshold) {
                Log.d(TAG, "精密化peak localX=$i sum=${sums[i]} absX=${x1 + i}")
                return (x1 + i).toDouble()
            }
        }
        Log.w(TAG, "精密化: ピーク未検出、フォールバック=$approxRight")
        return approxRight.toDouble()
    }

    // -----------------------------------------------------------------------
    // 中心の大きな枠線検出（モルフォロジー水平・垂直線合成）
    //
    // 適応二値化で印字を白に変換 → 水平カーネルで長い横線のみ残す →
    // 垂直カーネルで長い縦線のみ残す → 合成して輪郭を取得。
    // 文字や短い罫線はカーネル長でフィルタアウトされ、大きな枠線のみ残る。
    // -----------------------------------------------------------------------

    private fun detectCentralFrame(
        src: Mat,
        topY: Int,
        bottomY: Int,
        leftX: Int,
        imgW: Int,
        imgH: Int
    ): ArrayList<MatOfPoint> {

        val roiY1 = topY.coerceAtLeast(0)
        val roiY2 = bottomY.coerceAtMost(imgH - 1)
        val roiX1 = leftX.coerceAtLeast(0)
        val roiW  = imgW - roiX1
        val roiH  = roiY2 - roiY1

        // グレースケール → 適応二値化（印字=白、紙地=黒）
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val roi = gray.submat(roiY1, roiY2, roiX1, imgW)
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            roi, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV, 15, 10.0
        )
        gray.release()

        // 水平線カーネル: ROI 幅の 1/15 以上の線だけ残す
        val hLen = (roiW / 15).toDouble().coerceAtLeast(20.0)
        val hKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(hLen, 1.0))
        val hLines = Mat()
        Imgproc.morphologyEx(binary, hLines, Imgproc.MORPH_OPEN, hKernel)
        hKernel.release()

        // 垂直線カーネル: ROI 高さの 1/15 以上の線だけ残す
        val vLen = (roiH / 15).toDouble().coerceAtLeast(20.0)
        val vKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, vLen))
        val vLines = Mat()
        Imgproc.morphologyEx(binary, vLines, Imgproc.MORPH_OPEN, vKernel)
        vKernel.release()
        binary.release()

        // 水平 + 垂直を合成し、膨張で端点を接続
        val combined = Mat()
        Core.add(hLines, vLines, combined)
        hLines.release(); vLines.release()
        val dk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(combined, combined, dk)
        dk.release()

        // 輪郭検出
        val rawContours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            combined.clone(), rawContours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        combined.release()

        // ROI 内座標 → 元画像座標にオフセット変換
        // 面積が ROI の 3% 未満の輪郭は除外（小さなゴミ）
        val minArea = roiW * roiH * 0.03
        val result  = ArrayList<MatOfPoint>()
        for (c in rawContours) {
            if (Imgproc.contourArea(c) < minArea) continue
            val shifted = MatOfPoint()
            shifted.fromList(c.toArray().map { Point(it.x + roiX1, it.y + roiY1) })
            result.add(shifted)
        }
        Log.d(TAG, "中心枠候補（面積フィルタ後）: ${result.size}個")
        return result
    }

    // -----------------------------------------------------------------------
    // 枠右辺の線分検出（HoughLinesP による近垂直線）
    //
    // detectCentralFrame と同じモルフォロジー処理で垂直線マスクを生成し、
    // HoughLinesP で近垂直線分を取得。x が searchLeft〜searchRight の範囲で
    // 最も右側にある線分を返す（傾きも含む）。
    // -----------------------------------------------------------------------

    private fun findFrameRightEdgeSegment(
        src: Mat,
        topY: Int, bottomY: Int, leftX: Int,
        searchLeft: Int, searchRight: Int,
        imgW: Int, imgH: Int
    ): Pair<Point, Point>? {
        val roiY1 = topY.coerceAtLeast(0)
        val roiY2 = bottomY.coerceAtMost(imgH - 1)
        val roiX1 = leftX.coerceAtLeast(0)
        val roiH  = roiY2 - roiY1
        if (roiH < 10) return null

        // グレースケール → 適応二値化（印字=白）
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val roiMat = gray.submat(roiY1, roiY2, roiX1, imgW)
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            roiMat, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV, 15, 10.0
        )
        gray.release()

        // 垂直線カーネルのみ（ROI 高さの 1/15 以上の線だけ残す）
        val vLen = (roiH / 15).toDouble().coerceAtLeast(20.0)
        val vKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, vLen))
        val vLinesMat = Mat()
        Imgproc.morphologyEx(binary, vLinesMat, Imgproc.MORPH_OPEN, vKernel)
        vKernel.release()
        binary.release()

        // HoughLinesP で線分検出
        val segments = Mat()
        Imgproc.HoughLinesP(vLinesMat, segments, 1.0, Math.PI / 180.0,
            (roiH * 0.10).toInt().coerceAtLeast(20),
            (roiH * 0.08).toDouble(), (roiH * 0.05).toDouble())
        vLinesMat.release()

        Log.d(TAG, "HoughLinesP 検出数: ${segments.rows()}")

        // 近垂直（|dx|/|dy| < 0.4）かつ x 範囲内の全線分を収集
        data class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
            val midX get() = (x1 + x2) / 2.0
            val len  get() = Math.abs(y2 - y1).coerceAtLeast(1.0)
        }
        val candidates = mutableListOf<Seg>()
        for (i in 0 until segments.rows()) {
            val v = segments.get(i, 0)
            val ax1 = v[0] + roiX1; val ay1 = v[1] + roiY1
            val ax2 = v[2] + roiX1; val ay2 = v[3] + roiY1
            val dx = Math.abs(ax2 - ax1); val dy = Math.abs(ay2 - ay1)
            if (dy < 1.0 || dx / dy > 0.4) continue
            val midX = (ax1 + ax2) / 2.0
            if (midX < searchLeft || midX > searchRight) continue
            val (p1x, p1y, p2x, p2y) = if (ay1 <= ay2)
                listOf(ax1, ay1, ax2, ay2) else listOf(ax2, ay2, ax1, ay1)
            candidates.add(Seg(p1x, p1y, p2x, p2y))
        }
        segments.release()

        Log.d(TAG, "近垂直候補: ${candidates.size}本")
        if (candidates.isEmpty()) return null

        // x でクラスタリング（許容差: imgW × 1%）
        val clusterTol = imgW * 0.012
        val sorted = candidates.sortedBy { it.midX }
        val clusters = mutableListOf<MutableList<Seg>>()
        var cur = mutableListOf(sorted[0])
        for (seg in sorted.drop(1)) {
            if (seg.midX - cur.last().midX < clusterTol) cur.add(seg)
            else { clusters.add(cur); cur = mutableListOf(seg) }
        }
        clusters.add(cur)

        // 最も右側で総長さが十分なクラスタを選択
        val minTotalLen = roiH * 0.15
        val eligible = clusters.filter { grp -> grp.sumOf { it.len } >= minTotalLen }
        val best = (if (eligible.isNotEmpty()) eligible else clusters)
            .maxByOrNull { grp -> grp.sumOf { it.midX * it.len } / grp.sumOf { it.len } }
            ?: return null

        Log.d(TAG, "採用クラスタ: ${best.size}本 総長=${best.sumOf{it.len}.toInt()}")

        // クラスタ全点を長さ重み付き最小二乗でフィット  x = a*y + b
        var swY = 0.0; var swX = 0.0; var swYY = 0.0; var swXY = 0.0; var sw = 0.0
        for (seg in best) {
            val w = seg.len
            for ((px, py) in listOf(Pair(seg.x1, seg.y1), Pair(seg.x2, seg.y2))) {
                swY  += py * w;  swX  += px * w
                swYY += py * py * w;  swXY += px * py * w
                sw   += w
            }
        }
        val denom = sw * swYY - swY * swY
        val fitA: Double; val fitB: Double
        if (Math.abs(denom) > 1e-6) {
            fitA = (sw * swXY - swY * swX) / denom
            fitB = (swX - fitA * swY) / sw
        } else {
            fitA = 0.0; fitB = swX / sw
        }

        val topPt = Point(fitA * roiY1 + fitB, roiY1.toDouble())
        val botPt = Point(fitA * roiY2 + fitB, roiY2.toDouble())

        Log.d(TAG, "枠右辺フィット: a=${"%.4f".format(fitA)} b=${"%.1f".format(fitB)} " +
            "top=(%.0f,%.0f) bot=(%.0f,%.0f)".format(topPt.x, topPt.y, botPt.x, botPt.y))
        return Pair(topPt, botPt)
    }

    // -----------------------------------------------------------------------
    // 透視変換後の画像から明細データ行を検出・切り抜き
    //
    // 1. imgW×50%以上の長い水平線でヘッダー行の下端を特定（15〜40% Y範囲）
    // 2. ヘッダー下端〜85% Y の明細エリアで水平プロジェクション（行合計）
    // 3. インク密度 0.5% 以上の連続区間を1行として切り出す
    // 4. 高さフィルタ（30px〜roiH/4）で空行・小計行を除外
    // -----------------------------------------------------------------------

    private fun detectDetailRows(warpedFull: Mat): List<Bitmap> {
        val imgW = warpedFull.cols()
        val imgH = warpedFull.rows()

        // 適応二値化（インク=白）
        // OTSU は warped 画像コーナーの黒背景で閾値がずれるため adaptive を使用
        val gray = Mat()
        Imgproc.cvtColor(warpedFull, gray, Imgproc.COLOR_BGR2GRAY)
        val binary = Mat()
        Imgproc.adaptiveThreshold(gray, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 15, 8.0)
        gray.release()

        // ─── 1. 長い水平線でヘッダー行下端を特定 ──────────────────────────
        // 30% カーネル（50% だと適応二値化の微小ギャップで検出失敗するため短縮）
        val hKernelLen = (imgW * 0.30).toInt().coerceAtLeast(20).toDouble()
        val hKernel    = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(hKernelLen, 1.0))
        val hLinesMat  = Mat()
        Imgproc.morphologyEx(binary, hLinesMat, Imgproc.MORPH_OPEN, hKernel)
        hKernel.release()

        val hLineSumMat = Mat()
        Core.reduce(hLinesMat, hLineSumMat, 1, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
        hLinesMat.release()

        val hLineSums = IntArray(imgH)
        val hBuf = IntArray(1)
        for (y in 0 until imgH) { hLineSumMat.get(y, 0, hBuf); hLineSums[y] = hBuf[0] }
        hLineSumMat.release()

        // 連続ピクセルをクラスタリングして水平線の中心Y座標を収集
        val hLineThresh = (imgW * 255 * 0.2).toInt()  // 0.3→0.2（短縮カーネル対応）
        val hLineYs = mutableListOf<Int>()
        var prevLine = false; var lineStart = 0
        for (y in 0 until imgH) {
            val isLine = hLineSums[y] > hLineThresh
            if (isLine && !prevLine) lineStart = y
            if (!isLine && prevLine) hLineYs.add((lineStart + y) / 2)
            prevLine = isLine
        }
        Log.d(TAG, "長い水平線: ${hLineYs.size}本 @ $hLineYs")

        // 画像高さ 10〜50% の範囲にある水平線（検索範囲を広げて確実に捕捉）
        val searchStart = (imgH * 0.10).toInt()
        val searchEnd   = (imgH * 0.50).toInt()
        val headerLines = hLineYs.filter { it in searchStart..searchEnd }

        // 上端: ヘッダー行下辺（10-50%範囲の最後の横線）の直下
        val detailStartY = when {
            headerLines.isNotEmpty() -> headerLines.last() + 5
            else                     -> (imgH * 0.35).toInt()  // フォールバック（35%）
        }

        // 下端: フッター上端（60-90%範囲の最初の横線）の直上
        // 「合計（税込）」行や「※以下の方法にて...」の上の線を使用
        val footerLines = hLineYs.filter { it in (imgH * 0.60).toInt()..(imgH * 0.90).toInt() }
        val detailEndY = when {
            footerLines.isNotEmpty() -> footerLines.first() - 5
            else                     -> (imgH * 0.82).toInt()  // フォールバック（82%）
        }
        Log.d(TAG, "明細エリア: y=$detailStartY..$detailEndY  header=$headerLines footer=$footerLines")

        // ─── 2. 水平プロジェクション → 行分割 ────────────────────────────
        val clampedStart = detailStartY.coerceIn(0, imgH - 1)
        val clampedEnd   = detailEndY.coerceIn(clampedStart + 1, imgH)
        val roiH         = clampedEnd - clampedStart

        val detailBin = binary.submat(clampedStart, clampedEnd, 0, imgW)

        // 縦罫線除去: roiH×30%以上の縦線を検出して差し引く
        val vKernelLen = (roiH * 0.3).toInt().coerceAtLeast(20).toDouble()
        val vKernel    = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, vKernelLen))
        val vLinesMat  = Mat()
        Imgproc.morphologyEx(detailBin, vLinesMat, Imgproc.MORPH_OPEN, vKernel)
        vKernel.release()
        val textBin0 = Mat()
        Core.subtract(detailBin, vLinesMat, textBin0)
        vLinesMat.release()
        detailBin.release()
        binary.release()

        // 横罫線除去: imgW×20%以上の水平線を検出して差し引く
        // 境界線が残ると max が膨らんで inkThresh が過大になる
        val hKernelLen2 = (imgW * 0.20).toInt().coerceAtLeast(20).toDouble()
        val hKernel2    = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(hKernelLen2, 1.0))
        val hLinesMat2  = Mat()
        Imgproc.morphologyEx(textBin0, hLinesMat2, Imgproc.MORPH_OPEN, hKernel2)
        hKernel2.release()
        val textBin = Mat()
        Core.subtract(textBin0, hLinesMat2, textBin)
        hLinesMat2.release()
        textBin0.release()

        val rowSumMat = Mat()
        Core.reduce(textBin, rowSumMat, 1, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
        textBin.release()

        val rowSums = IntArray(roiH)
        val buf = IntArray(1)
        for (y in 0 until roiH) { rowSumMat.get(y, 0, buf); rowSums[y] = buf[0] }
        rowSumMat.release()

        // 行とノイズを分離するため P95 相対閾値を使用
        // max だと残留ライン1本で inkThresh が過大になるため95パーセンタイルを使用
        val maxRowSum = rowSums.maxOrNull() ?: 1
        val minRowSum = rowSums.minOrNull() ?: 0
        val sortedSums = rowSums.sorted()
        val p95Index  = (sortedSums.size * 0.95).toInt().coerceAtMost(sortedSums.size - 1)
        val p95Sum    = sortedSums[p95Index].coerceAtLeast(1)
        val inkThresh = (p95Sum * 0.15).toInt().coerceAtLeast(1)
        Log.d(TAG, "rowSum min=$minRowSum max=$maxRowSum p95=$p95Sum inkThresh=$inkThresh")

        val groups    = mutableListOf<Pair<Int, Int>>()
        var inContent = false; var groupStart = 0
        for (y in 0 until roiH) {
            val hasInk = rowSums[y] > inkThresh
            if      ( hasInk && !inContent) { groupStart = y; inContent = true }
            else if (!hasInk &&  inContent) { groups.add(groupStart to y - 1); inContent = false }
        }
        if (inContent) groups.add(groupStart to roiH - 1)
        Log.d(TAG, "行グループ候補: ${groups.size}  heights=${groups.map { it.second - it.first }}")

        // 高さフィルタ: 20px ≤ h ≤ roiH/3
        val minH = 20
        val maxH = roiH / 3
        val validGroups = groups.filter { (s, e) -> (e - s) in minH..maxH }
        Log.d(TAG, "有効行数: ${validGroups.size}  maxH=$maxH")

        // 各行をクロップして Bitmap で返す
        return validGroups.mapNotNull { (s, e) ->
            val y1 = (clampedStart + s).coerceIn(0, imgH - 1)
            val y2 = (clampedStart + e + 1).coerceIn(0, imgH)
            if (y2 <= y1) return@mapNotNull null
            val roi = warpedFull.submat(y1, y2, 0, imgW)
            val bmp = roi.toBitmap()
            roi.release()
            bmp
        }
    }

    // -----------------------------------------------------------------------
    // 透視変換後の画像から中央の明細枠を検出
    //
    // 適応二値化 → findContours(RETR_TREE) で全輪郭を取得し、
    // 「全体面積の30〜70%」かつ「横長比率1.2〜2.5」の最大矩形を採用。
    // 見つからない場合は水平・垂直プロジェクションでフォールバック。
    // -----------------------------------------------------------------------

    private fun findCentralFrame(warpedFull: Mat): Rect? {
        val gray = Mat()
        Imgproc.cvtColor(warpedFull, gray, Imgproc.COLOR_BGR2GRAY)
        // ガウシアンフィルタでドット印刷の隙間を埋める
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val binary = Mat()
        Imgproc.adaptiveThreshold(gray, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 15, 8.0)
        gray.release()

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary.clone(), contours, Mat(),
            Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        binary.release()

        val fullArea = warpedFull.size().area()
        val best = contours
            .map { Imgproc.boundingRect(it) }
            .filter { r ->
                val area = r.area()
                val ratio = r.width.toDouble() / r.height
                area > fullArea * 0.3 && area < fullArea * 0.8 && ratio in 1.2..2.5
            }
            .maxByOrNull { it.area() }

        if (best != null) {
            Log.d(TAG, "中央枠(輪郭法): $best ratio=${"%.2f".format(best.width.toDouble() / best.height)}")
            return best
        }

        Log.w(TAG, "中央枠: 輪郭法で未検出、プロジェクション法でフォールバック")
        return findCentralFrameByProjection(warpedFull)
    }

    // -----------------------------------------------------------------------
    // プロジェクション法による明細枠検出（フォールバック）
    //
    // 垂直プロジェクション（列合計）で左右の壁を、
    // 水平プロジェクション（行合計）で上下の壁を特定する。
    // 枠線が途切れていてもピクセル密度の集合体として検出できる。
    // -----------------------------------------------------------------------

    private fun findCentralFrameByProjection(warpedFull: Mat): Rect? {
        val imgW = warpedFull.cols()
        val imgH = warpedFull.rows()

        val binary = buildInkMask(warpedFull)

        // 垂直プロジェクション（列合計）→ 1×imgW の行ベクトル
        val colSumMat = Mat()
        Core.reduce(binary, colSumMat, 0, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
        val colSums = IntArray(imgW)
        colSumMat.get(0, 0, colSums)
        colSumMat.release()

        // 水平プロジェクション（行合計）→ imgH×1 の列ベクトル
        val rowSumMat = Mat()
        Core.reduce(binary, rowSumMat, 1, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
        val rowSums = IntArray(imgH)
        val rowBuf  = IntArray(1)
        for (y in 0 until imgH) { rowSumMat.get(y, 0, rowBuf); rowSums[y] = rowBuf[0] }
        rowSumMat.release()
        binary.release()

        // 閾値: その行・列の 10% 以上にインクがあれば「壁」とみなす
        val colThresh = (imgH * 255 * 0.1).toInt()
        val rowThresh = (imgW * 255 * 0.1).toInt()

        var left   = 0;       for (x in 0 until imgW)       { if (colSums[x] > colThresh) { left   = x; break } }
        var right  = imgW - 1; for (x in imgW - 1 downTo 0) { if (colSums[x] > colThresh) { right  = x; break } }
        var top    = 0;       for (y in 0 until imgH)       { if (rowSums[y] > rowThresh) { top    = y; break } }
        var bottom = imgH - 1; for (y in imgH - 1 downTo 0) { if (rowSums[y] > rowThresh) { bottom = y; break } }

        val w = right - left
        val h = bottom - top
        if (w < imgW * 0.2 || h < imgH * 0.2) {
            Log.w(TAG, "プロジェクション法も失敗: L=$left R=$right T=$top B=$bottom")
            return null
        }

        val rect = Rect(left, top, w, h)
        Log.d(TAG, "中央枠(プロジェクション): $rect")
        return rect
    }

    // -----------------------------------------------------------------------
    // 右辺検出（用紙の右端スキャン方式）
    //
    // Otsu 二値化で用紙（明るい）=白、背景（暗い）=黒 に変換し、
    // 右から左へ行ごとにスキャンして最初に白ピクセルが現れた x を記録。
    // 外れ値（背景ノイズ）を除くため上位 90% 点を右辺として採用。
    // -----------------------------------------------------------------------

    private fun detectRightBlackBorder(
        src: Mat,
        topY: Int,
        bottomY: Int,
        searchLeft: Int,
        searchRight: Int,
        imgW: Int
    ): Pair<Point, Point>? {

        val receiptH = bottomY - topY
        if (receiptH < 100) {
            Log.w(TAG, "受票高さ不足: $receiptH")
            return null
        }

        // グレースケール → Otsu 二値化
        //   用紙（明るい） = 255（白）、背景＋印字（暗い） = 0（黒）
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0,
            Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        gray.release()

        val rightX = scanRightEdgeX(binary, topY, bottomY, searchLeft, searchRight)
        binary.release()

        Log.d(TAG, "用紙右辺スキャン: x=%.0f".format(rightX))
        return Pair(
            Point(rightX, topY.toDouble()),
            Point(rightX, bottomY.toDouble())
        )
    }

    // -----------------------------------------------------------------------
    // 右から左へスキャンして用紙右辺の x 座標を返す
    //
    // ・行ごとに ByteArray バルク読み出し（JNI コール数最小化）
    // ・上下 5% マージンは捨てて机端ノイズを抑制
    // ・外れ値除去: ソート後 90 パーセンタイル値を採用
    // -----------------------------------------------------------------------

    private fun scanRightEdgeX(
        binary: Mat,
        topY: Int,
        bottomY: Int,
        searchLeft: Int,
        searchRight: Int
    ): Double {
        val margin = ((bottomY - topY) * 0.05).toInt()
        val startY = (topY    + margin).coerceAtLeast(0)
        val endY   = (bottomY - margin).coerceAtMost(binary.rows() - 1)
        val cols   = binary.cols()

        val scanPoints = mutableListOf<Int>()
        val row = ByteArray(cols)   // 行バッファを使い回す

        for (y in startY..endY step 5) {
            binary.get(y, 0, row)   // 1 行まとめて読み出し
            // searchRight〜searchLeft の範囲内だけスキャン
            for (x in searchRight.coerceAtMost(cols - 1) downTo searchLeft.coerceAtLeast(0)) {
                if (row[x].toInt() and 0xFF > 127) {
                    scanPoints.add(x)
                    break
                }
            }
        }

        Log.d(TAG, "スキャン点数: ${scanPoints.size}")
        if (scanPoints.isEmpty()) return searchRight.toDouble()

        scanPoints.sort()
        // 上位 10% はノイズとして切り捨て、90 パーセンタイルを右辺とする
        return scanPoints[(scanPoints.size * 0.9).toInt()].toDouble()
    }

    // -----------------------------------------------------------------------
    // HSV 緑マスク生成（モルフォロジークローズでギャップ補完）
    // -----------------------------------------------------------------------

    private fun buildGreenMask(src: Mat): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(hsv, LOWER_GREEN, UPPER_GREEN, mask)
        hsv.release()

        val k      = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closed = Mat()
        Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, k)
        mask.release()
        k.release()
        return closed
    }

    // -----------------------------------------------------------------------
    // 失敗時レスポンス生成
    // -----------------------------------------------------------------------

    private fun buildFailure(
        debugMat: Mat,
        maskBitmap: Bitmap?,
        message: String
    ): DetectionResult {
        Log.e(TAG, message)
        return DetectionResult(
            success        = false,
            corners        = emptyList(),
            debugBitmap    = debugMat.toBitmap(),
            maskBitmap     = maskBitmap,
            dewarpedBitmap = null,
            rowBitmaps     = emptyList(),
            errorMessage   = message
        )
    }
}

// -----------------------------------------------------------------------
// Mat → Bitmap 変換拡張関数
// -----------------------------------------------------------------------
fun Mat.toBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
    val matToConvert = when (channels()) {
        1    -> Mat().also { Imgproc.cvtColor(this, it, Imgproc.COLOR_GRAY2RGBA) }
        3    -> Mat().also { Imgproc.cvtColor(this, it, Imgproc.COLOR_BGR2RGBA) }
        4    -> Mat().also { Imgproc.cvtColor(this, it, Imgproc.COLOR_BGRA2RGBA) }
        else -> null
    }
    if (matToConvert != null) {
        Utils.matToBitmap(matToConvert, bmp)
        matToConvert.release()
    } else {
        Utils.matToBitmap(this, bmp)
    }
    return bmp
}
