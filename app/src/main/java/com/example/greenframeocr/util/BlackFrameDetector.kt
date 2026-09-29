package com.example.greenframeocr.util

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object BlackFrameDetector {

    data class DetectionResult(
        val success: Boolean,
        val corners: List<Point>,
        val imageWidth: Int,
        val imageHeight: Int,
        val errorMessage: String = ""
    )

    fun process(inputBitmap: Bitmap): DetectionResult {
        val imageWidth = inputBitmap.width
        val imageHeight = inputBitmap.height

        val rgbaMat = Mat()
        Utils.bitmapToMat(inputBitmap, rgbaMat)
        val bgrMat = Mat()
        Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
        rgbaMat.release()

        val grayMat = Mat()
        Imgproc.cvtColor(bgrMat, grayMat, Imgproc.COLOR_BGR2GRAY)
        bgrMat.release()

        val blurredMat = Mat()
        Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)
        grayMat.release()

        val edgeMat = Mat()
        Imgproc.Canny(blurredMat, edgeMat, 50.0, 150.0)
        blurredMat.release()

        val lines = Mat()
        Imgproc.HoughLinesP(
            edgeMat, lines,
            1.0, Math.PI / 180.0,
            80,
            imageWidth * 0.15,
            20.0
        )
        edgeMat.release()

        if (lines.rows() == 0) {
            lines.release()
            return DetectionResult(false, emptyList(), imageWidth, imageHeight, "直線未検出")
        }

        val horizontalLines = mutableListOf<IntArray>()
        val verticalLines = mutableListOf<IntArray>()

        for (i in 0 until lines.rows()) {
            val line = lines.get(i, 0)
            val x1 = line[0].toInt(); val y1 = line[1].toInt()
            val x2 = line[2].toInt(); val y2 = line[3].toInt()
            val angle = Math.toDegrees(Math.abs(Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())))
            when {
                angle < 15.0 || angle > 165.0 -> horizontalLines.add(intArrayOf(x1, y1, x2, y2))
                angle in 75.0..105.0           -> verticalLines.add(intArrayOf(x1, y1, x2, y2))
            }
        }
        lines.release()

        if (horizontalLines.size < 2 || verticalLines.size < 2) {
            return DetectionResult(
                false, emptyList(), imageWidth, imageHeight,
                "水平線${horizontalLines.size}本・垂直線${verticalLines.size}本（最低2本ずつ必要）"
            )
        }

        val topLine    = horizontalLines.minByOrNull { minOf(it[1], it[3]) }!!
        val bottomLine = horizontalLines.maxByOrNull { maxOf(it[1], it[3]) }!!
        val leftLine   = verticalLines.minByOrNull { minOf(it[0], it[2]) }!!
        val rightLine  = verticalLines.maxByOrNull { maxOf(it[0], it[2]) }!!

        val topLeft     = lineIntersection(topLine, leftLine)
            ?: Point(minOf(leftLine[0], leftLine[2]).toDouble(), minOf(topLine[1], topLine[3]).toDouble())
        val topRight    = lineIntersection(topLine, rightLine)
            ?: Point(maxOf(rightLine[0], rightLine[2]).toDouble(), minOf(topLine[1], topLine[3]).toDouble())
        val bottomRight = lineIntersection(bottomLine, rightLine)
            ?: Point(maxOf(rightLine[0], rightLine[2]).toDouble(), maxOf(bottomLine[1], bottomLine[3]).toDouble())
        val bottomLeft  = lineIntersection(bottomLine, leftLine)
            ?: Point(minOf(leftLine[0], leftLine[2]).toDouble(), maxOf(bottomLine[1], bottomLine[3]).toDouble())

        val corners = listOf(topLeft, topRight, bottomRight, bottomLeft)

        val mat2f = MatOfPoint2f(*corners.toTypedArray())
        val area = Imgproc.contourArea(mat2f)
        mat2f.release()

        val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
        if (area < imageArea * 0.05) {
            return DetectionResult(
                false, corners, imageWidth, imageHeight,
                "検出矩形が小さすぎ（面積比: ${String.format("%.1f", area / imageArea * 100)}%）"
            )
        }

        return DetectionResult(true, corners, imageWidth, imageHeight)
    }

    private fun lineIntersection(line1: IntArray, line2: IntArray): Point? {
        val x1 = line1[0].toDouble(); val y1 = line1[1].toDouble()
        val x2 = line1[2].toDouble(); val y2 = line1[3].toDouble()
        val x3 = line2[0].toDouble(); val y3 = line2[1].toDouble()
        val x4 = line2[2].toDouble(); val y4 = line2[3].toDouble()

        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (Math.abs(denom) < 1e-10) return null

        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        return Point(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    }
}
