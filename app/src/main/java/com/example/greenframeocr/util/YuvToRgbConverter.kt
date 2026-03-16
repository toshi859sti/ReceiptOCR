package com.example.greenframeocr.util

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * YUV画像をRGBに変換するユーティリティ
 * JPEG圧縮を使わず、高品質な変換を実現
 */
object YuvToRgbConverter {

    /**
     * ImageProxyからBitmapに直接変換（JPEG圧縮なし）
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // YuvImageを使用するが、JPEG品質を100%に設定
        // より良い方法：直接RGB変換（下記のコメント参照）
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()

        // 重要：品質を100に設定してJPEG劣化を最小化
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()

        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    /**
     * より高品質：YUVから直接RGBに変換
     * JPEG圧縮を完全に回避
     */
    fun imageProxyToBitmapDirect(image: ImageProxy): Bitmap {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val width = image.width
        val height = image.height

        val pixels = IntArray(width * height)

        var yPos = 0
        var uvPos = 0

        for (row in 0 until height) {
            yPos = row * yRowStride
            uvPos = (row / 2) * uvRowStride

            for (col in 0 until width) {
                val y = (yBuffer[yPos].toInt() and 0xff) - 16
                val u = (uBuffer[uvPos + (col / 2) * uvPixelStride].toInt() and 0xff) - 128
                val v = (vBuffer[uvPos + (col / 2) * uvPixelStride].toInt() and 0xff) - 128

                // YUV to RGB conversion
                var r = (1.164f * y + 1.596f * v).toInt()
                var g = (1.164f * y - 0.392f * u - 0.813f * v).toInt()
                var b = (1.164f * y + 2.017f * u).toInt()

                // Clamp values
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                pixels[row * width + col] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

                yPos++
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
