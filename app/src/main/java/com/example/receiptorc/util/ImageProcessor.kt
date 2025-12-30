package com.example.receiptorc.util

import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * 画像処理ユーティリティクラス
 * Aruco検出、透視変換、画像前処理を実行
 */
object ImageProcessor {
    private const val TAG = "ImageProcessor"

    // デバッグモード：trueにするとセル画像を保存
    private const val DEBUG_SAVE_CELL_IMAGES = true

    // マーカーサイズ（mm）
    private const val MARKER_SIZE_MM = 25.0

    // warp出力サイズ（固定、UNDERLAY台紙用）
    private const val WARP_OUTPUT_WIDTH = 2400
    private const val WARP_OUTPUT_HEIGHT = 1700
    private const val WARP_SCALE_PX_PER_MM = 8.1  // px/mm

    // ArUco中心座標（mm、A4左上原点）
    private const val ARUCO_ID0_X_MM = 25.0
    private const val ARUCO_ID0_Y_MM = 25.0
    private const val ARUCO_ID1_X_MM = 272.0
    private const val ARUCO_ID1_Y_MM = 25.0
    private const val ARUCO_ID2_X_MM = 272.0
    private const val ARUCO_ID2_Y_MM = 185.0
    private const val ARUCO_ID3_X_MM = 25.0
    private const val ARUCO_ID3_Y_MM = 185.0

    // マーカーとブロックの間のマージン（mm）- OVERLAY用
    private const val MARKER_TO_BLOCK_MARGIN_MM = 1.0  // 右端の文字欠けを防ぐため1mmに設定

    // ブロック列幅定義（mm）- 実測値に基づく
    private const val B_BLOCK_COL1_WIDTH_MM = 14.5  // Bブロック左列（取引日）- 微調整
    private const val C_BLOCK_COL1_WIDTH_MM = 21.0  // Cブロック左列（税込金額）

    // 行数
    private const val NUM_ROWS = 20

    // セル抽出時のパディング（ピクセル）
    // 罫線を除外して文字のみを取得するため
    private const val CELL_PADDING_PX = 3

    // 右列の左側パディング（ピクセル）
    // 文字欠けを防ぐため、パディングなし（罫線も含む）
    private const val RIGHT_COLUMN_LEFT_PADDING_PX = 0

    // 最後に計算されたmm->pixel変換率（透視変換で設定される）
    private var lastMmToPixelRatio: Double = 0.0

    /**
     * ブロックタイプ
     */
    enum class BlockType {
        B_BLOCK,  // Bブロック（取引日 + 商品名）
        C_BLOCK   // Cブロック（税込金額 + 分類計）
    }

    /**
     * Aruco検出結果
     */
    data class ArucoDetectionResult(
        val corners: List<MatOfPoint2f>,
        val ids: Mat,
        val isValid: Boolean,
        val blockType: BlockType?
    )

    /**
     * ブロック境界
     */
    data class BlockBounds(
        val topLeft: Point,
        val topRight: Point,
        val bottomRight: Point,
        val bottomLeft: Point
    )

    /**
     * ブロック切り出し結果
     */
    data class BlockExtractionResult(
        val blockImage: Bitmap,
        val cellImages: List<List<Bitmap>>  // [row][column]
    )

    /**
     * Bitmap から Mat に変換
     * 重要: OpenCVのUtils.bitmapToMatはBGRA形式に変換する
     */
    private fun bitmapToMat(bitmap: Bitmap): Mat {
        // Bitmapが適切な形式であることを確認（ARGB_8888に変換）
        val convertedBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            Log.d(TAG, "Converting bitmap from ${bitmap.config} to ARGB_8888")
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        val mat = Mat()
        try {
            // Utils.bitmapToMatはARGB_8888 BitmapをBGRA Matに変換する
            Utils.bitmapToMat(convertedBitmap, mat)
            Log.d(TAG, "Mat created: ${mat.cols()}x${mat.rows()}, channels: ${mat.channels()}, type: ${mat.type()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error converting bitmap to mat: ${e.message}", e)
            // 変換に失敗した場合、空のMatを返す
            return Mat()
        } finally {
            // コピーを作成した場合はリサイクル
            if (convertedBitmap != bitmap) {
                convertedBitmap.recycle()
            }
        }
        return mat
    }

    /**
     * Mat から Bitmap に変換
     */
    private fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    /**
     * Arucoマーカーを検出
     */
    fun detectArucoMarkers(bitmap: Bitmap): ArucoDetectionResult {
        val mat = bitmapToMat(bitmap)
        val grayMat = Mat()

        // 重要: Utils.bitmapToMatはBGRA形式に変換するため、COLOR_BGRA2GRAYを使用
        if (mat.channels() == 4) {
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGRA2GRAY)
        } else if (mat.channels() == 3) {
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
        } else {
            mat.copyTo(grayMat)
        }

        Log.d(TAG, "Image size: ${bitmap.width}x${bitmap.height}, Mat channels: ${mat.channels()}, type: ${mat.type()}")

        // 前処理: グレースケール + 軽いGaussianBlur のみ（固定）
        val blurredMat = Mat()
        Imgproc.GaussianBlur(grayMat, blurredMat, Size(3.0, 3.0), 0.0)

        Log.d(TAG, "Using fixed preprocessing: Grayscale + GaussianBlur(3x3)")

        // 辞書: DICT_4X4_50 固定
        val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
        val detectorParams = DetectorParameters()
        val detector = ArucoDetector(dictionary, detectorParams)

        // ArUco検出
        val corners = ArrayList<Mat>()
        val ids = Mat()
        detector.detectMarkers(blurredMat, corners, ids)

        val detectedCount = corners.size
        Log.d(TAG, "Dictionary DICT_4X4_50: Detected $detectedCount markers")

        // メモリ解放
        mat.release()
        grayMat.release()
        blurredMat.release()

        if (detectedCount > 0) {
            Log.d(TAG, "ArUco detection completed with $detectedCount markers")
            val bestCorners = corners
            val bestIds = ids

            // 検出されたマーカーIDを確認
            val detectedIds = mutableListOf<Int>()
            for (i in 0 until bestIds.rows()) {
                val id = bestIds.get(i, 0)[0].toInt()
                detectedIds.add(id)
                Log.d(TAG, "Marker ID: $id")
            }

            Log.d(TAG, "All detected IDs: $detectedIds")

            // ブロックタイプを判定
            val blockType = when {
                detectedIds.containsAll(listOf(0, 1, 2, 3)) -> {
                    Log.d(TAG, "B_BLOCK detected")
                    BlockType.B_BLOCK
                }
                detectedIds.containsAll(listOf(4, 5, 6, 7)) -> {
                    Log.d(TAG, "C_BLOCK detected")
                    BlockType.C_BLOCK
                }
                else -> {
                    Log.d(TAG, "Incomplete block. Required: [0,1,2,3] or [4,5,6,7], Got: $detectedIds")
                    null
                }
            }

            val isValid = blockType != null && bestCorners.size >= 4
            val matOfPoint2fList = bestCorners.map { MatOfPoint2f(it) }

            return ArucoDetectionResult(matOfPoint2fList, bestIds, isValid, blockType)
        }

        Log.w(TAG, "No markers detected with any dictionary")
        return ArucoDetectionResult(emptyList(), Mat(), false, null)
    }

    /**
     * マーカーの座標からブロックの境界を計算
     *
     * @param corners マーカーの角座標リスト
     * @param ids マーカーIDのMat
     * @param blockType ブロックタイプ
     * @return ブロックの境界座標
     */
    private fun calculateBlockBounds(
        corners: List<MatOfPoint2f>,
        ids: Mat,
        blockType: BlockType
    ): BlockBounds? {
        try {
            // マーカーIDと座標のマップを作成
            val markerCorners = mutableMapOf<Int, Array<Point>>()
            for (i in 0 until ids.rows()) {
                val id = ids.get(i, 0)[0].toInt()
                val cornerArray = corners[i].toArray()
                markerCorners[id] = cornerArray
            }

            // ブロックタイプに応じて必要なマーカーIDを取得
            val (topLeftId, topRightId, bottomLeftId, bottomRightId) = when (blockType) {
                BlockType.B_BLOCK -> listOf(0, 1, 2, 3)
                BlockType.C_BLOCK -> listOf(4, 5, 6, 7)
            }

            // 必要なマーカーが全て存在するか確認
            if (!markerCorners.containsKey(topLeftId) ||
                !markerCorners.containsKey(topRightId) ||
                !markerCorners.containsKey(bottomLeftId) ||
                !markerCorners.containsKey(bottomRightId)
            ) {
                Log.e(TAG, "Required markers not found for $blockType")
                return null
            }

            // ArUcoマーカーの角の順番: [0]=左上, [1]=右上, [2]=右下, [3]=左下
            val tlMarker = markerCorners[topLeftId]!!
            val trMarker = markerCorners[topRightId]!!
            val blMarker = markerCorners[bottomLeftId]!!
            val brMarker = markerCorners[bottomRightId]!!

            // マーカーのピクセルサイズを計算（mm→ピクセル比率を求める）
            val tlMarkerWidth = sqrt(
                (tlMarker[1].x - tlMarker[0].x) * (tlMarker[1].x - tlMarker[0].x) +
                (tlMarker[1].y - tlMarker[0].y) * (tlMarker[1].y - tlMarker[0].y)
            )
            val tlMarkerHeight = sqrt(
                (tlMarker[3].x - tlMarker[0].x) * (tlMarker[3].x - tlMarker[0].x) +
                (tlMarker[3].y - tlMarker[0].y) * (tlMarker[3].y - tlMarker[0].y)
            )
            val markerAvgSize = (tlMarkerWidth + tlMarkerHeight) / 2.0
            val mmToPixel = markerAvgSize / MARKER_SIZE_MM

            // mm->pixel変換率を保存（列区切り検出で使用）
            lastMmToPixelRatio = mmToPixel

            Log.d(TAG, "Marker size: ${markerAvgSize}px, mm->pixel ratio: $mmToPixel")

            // ブロックの境界を計算
            // 上辺Y = 上側マーカー（左上、右上）の上辺Y座標の最小値
            val topY = minOf(tlMarker[0].y, tlMarker[1].y, trMarker[0].y, trMarker[1].y)

            // 下辺Y = 下側マーカー（左下、右下）の下辺Y座標の最大値
            val bottomY = maxOf(blMarker[2].y, blMarker[3].y, brMarker[2].y, brMarker[3].y)

            // B_BLOCK（下に敷くタイプ）は左右が反転しているため、leftXとrightXを入れ替え
            val tempLeftX: Double
            val tempRightX: Double

            if (blockType == BlockType.B_BLOCK) {
                // 右辺X = 右側マーカー（右上、右下）の左辺X座標 - 2mm
                val rightMarkerLeftX = minOf(tlMarker[0].x, tlMarker[3].x, blMarker[0].x, blMarker[3].x)
                tempLeftX = rightMarkerLeftX - (MARKER_TO_BLOCK_MARGIN_MM * mmToPixel)

                // 左辺X = 左側マーカー（左上、左下）の右辺X座標 + 2mm
                val leftMarkerRightX = maxOf(trMarker[1].x, trMarker[2].x, brMarker[1].x, brMarker[2].x)
                tempRightX = leftMarkerRightX + (MARKER_TO_BLOCK_MARGIN_MM * mmToPixel)
            } else {
                // 左辺X = 左側マーカー（左上、左下）の右辺X座標 + 2mm
                val leftMarkerRightX = maxOf(tlMarker[1].x, tlMarker[2].x, blMarker[1].x, blMarker[2].x)
                tempLeftX = leftMarkerRightX + (MARKER_TO_BLOCK_MARGIN_MM * mmToPixel)

                // 右辺X = 右側マーカー（右上、右下）の左辺X座標 - 2mm
                val rightMarkerLeftX = minOf(trMarker[0].x, trMarker[3].x, brMarker[0].x, brMarker[3].x)
                tempRightX = rightMarkerLeftX - (MARKER_TO_BLOCK_MARGIN_MM * mmToPixel)
            }

            val leftX = tempLeftX
            val rightX = tempRightX

            val bounds = BlockBounds(
                topLeft = Point(leftX, topY),
                topRight = Point(rightX, topY),
                bottomRight = Point(rightX, bottomY),
                bottomLeft = Point(leftX, bottomY)
            )

            Log.d(TAG, "Block bounds calculated for $blockType:")
            Log.d(TAG, "  TopLeft: (${leftX}, ${topY})")
            Log.d(TAG, "  TopRight: (${rightX}, ${topY})")
            Log.d(TAG, "  BottomRight: (${rightX}, ${bottomY})")
            Log.d(TAG, "  BottomLeft: (${leftX}, ${bottomY})")
            Log.d(TAG, "  Width: ${rightX - leftX}px, Height: ${bottomY - topY}px")

            return bounds
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating block bounds", e)
            return null
        }
    }

    /**
     * ArUcoマーカーの中心座標を取得
     * @return ID順にソートされた中心座標配列 [ID0, ID1, ID2, ID3]
     */
    private fun getMarkerCenters(
        corners: List<MatOfPoint2f>,
        ids: Mat,
        blockType: BlockType
    ): Array<Point> {
        val markerCenters = mutableMapOf<Int, Point>()

        for (i in 0 until ids.rows()) {
            val id = ids.get(i, 0)[0].toInt()
            val cornerArray = corners[i].toArray()

            // マーカー中心 = 4隅の平均
            val centerX = cornerArray.map { it.x }.average()
            val centerY = cornerArray.map { it.y }.average()
            markerCenters[id] = Point(centerX, centerY)
        }

        // ブロックタイプに応じたID順に並べる
        val (id0, id1, id2, id3) = when (blockType) {
            BlockType.B_BLOCK -> listOf(0, 1, 2, 3)
            BlockType.C_BLOCK -> listOf(4, 5, 6, 7)
        }

        return arrayOf(
            markerCenters[id0]!!,
            markerCenters[id1]!!,
            markerCenters[id2]!!,
            markerCenters[id3]!!
        )
    }

    /**
     * マーカーベースの透視変換でブロックを切り出し
     *
     * マーカーの座標からブロックの境界を計算し、透視変換を実行してブロックを正確に切り出す
     *
     * @param useFixedOutput trueの場合、2400×1700固定出力（UNDERLAY台紙用）
     */
    fun perspectiveTransform(
        bitmap: Bitmap,
        corners: List<MatOfPoint2f>,
        ids: Mat,
        blockType: BlockType,
        useFixedOutput: Boolean = false
    ): Bitmap? {
        try {
            val mat = bitmapToMat(bitmap)

            val srcMat: MatOfPoint2f
            val dstMat: MatOfPoint2f
            val dstWidth: Int
            val dstHeight: Int

            if (useFixedOutput) {
                // UNDERLAY台紙用: 固定2400×1700出力（A4全体）
                // mm→px比率を固定値に設定
                lastMmToPixelRatio = WARP_SCALE_PX_PER_MM

                // ソース4点: ArUcoマーカー中心座標
                val markerCenters = getMarkerCenters(corners, ids, blockType)
                srcMat = MatOfPoint2f(
                    markerCenters[0],  // ID0 左上
                    markerCenters[1],  // ID1 右上
                    markerCenters[2],  // ID2 右下
                    markerCenters[3]   // ID3 左下
                )

                // デスティネーション4点: A4上の設計座標（px）
                dstMat = MatOfPoint2f(
                    Point(ARUCO_ID0_X_MM * WARP_SCALE_PX_PER_MM, ARUCO_ID0_Y_MM * WARP_SCALE_PX_PER_MM),  // 202.5, 202.5
                    Point(ARUCO_ID1_X_MM * WARP_SCALE_PX_PER_MM, ARUCO_ID1_Y_MM * WARP_SCALE_PX_PER_MM),  // 2203.2, 202.5
                    Point(ARUCO_ID2_X_MM * WARP_SCALE_PX_PER_MM, ARUCO_ID2_Y_MM * WARP_SCALE_PX_PER_MM),  // 2203.2, 1498.5
                    Point(ARUCO_ID3_X_MM * WARP_SCALE_PX_PER_MM, ARUCO_ID3_Y_MM * WARP_SCALE_PX_PER_MM)   // 202.5, 1498.5
                )

                dstWidth = WARP_OUTPUT_WIDTH
                dstHeight = WARP_OUTPUT_HEIGHT

                Log.d(TAG, "Perspective transform - Fixed output mode (UNDERLAY)")
                Log.d(TAG, "  Output size: ${dstWidth}px x ${dstHeight}px (A4 @ ${WARP_SCALE_PX_PER_MM}px/mm)")
                Log.d(TAG, "  mm->px ratio set to: $lastMmToPixelRatio")
            } else {
                // OVERLAY台紙用: 可変出力（ブロック領域のみ）
                val blockBounds = calculateBlockBounds(corners, ids, blockType)
                if (blockBounds == null) {
                    Log.e(TAG, "Failed to calculate block bounds")
                    mat.release()
                    return null
                }

                srcMat = MatOfPoint2f(
                    blockBounds.topLeft,
                    blockBounds.topRight,
                    blockBounds.bottomRight,
                    blockBounds.bottomLeft
                )

                val srcWidth = blockBounds.topRight.x - blockBounds.topLeft.x
                val srcHeight = blockBounds.bottomLeft.y - blockBounds.topLeft.y
                dstWidth = srcWidth.toInt()
                dstHeight = srcHeight.toInt()

                dstMat = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(dstWidth.toDouble(), 0.0),
                    Point(dstWidth.toDouble(), dstHeight.toDouble()),
                    Point(0.0, dstHeight.toDouble())
                )

                Log.d(TAG, "Perspective transform - Variable output mode (OVERLAY)")
                Log.d(TAG, "  Block type: $blockType")
                Log.d(TAG, "  Source block size: ${srcWidth}px x ${srcHeight}px")
                Log.d(TAG, "  Target image size: ${dstWidth}px x ${dstHeight}px")
            }

            // 透視変換行列を計算
            val transformMatrix = Imgproc.getPerspectiveTransform(srcMat, dstMat)

            // 透視変換を適用
            val warpedMat = Mat()
            Imgproc.warpPerspective(
                mat,
                warpedMat,
                transformMatrix,
                Size(dstWidth.toDouble(), dstHeight.toDouble())
            )

            mat.release()
            transformMatrix.release()
            srcMat.release()
            dstMat.release()

            return matToBitmap(warpedMat)
        } catch (e: Exception) {
            Log.e(TAG, "Error in perspective transform", e)
            return null
        }
    }

    /**
     * ブロックをセルに分割
     *
     * 注: perspectiveTransform()によって既にブロックが切り出されているため、
     * transformedBitmap全体がブロックとなる
     */
    fun extractBlock(
        transformedBitmap: Bitmap,
        blockType: BlockType
    ): BlockExtractionResult? {
        try {
            val mat = bitmapToMat(transformedBitmap)

            Log.d(TAG, "Block extraction - Type: $blockType")
            Log.d(TAG, "  Block image size: ${mat.cols()}x${mat.rows()} px")

            // デバッグ：ブロック全体の画像を保存
            if (DEBUG_SAVE_CELL_IMAGES) {
                saveDebugImage(transformedBitmap, "${blockType}_full_block.png")
            }

            // セルに分割
            val cellImages = divideBlockIntoCells(mat, blockType)

            mat.release()

            return BlockExtractionResult(transformedBitmap, cellImages)
        } catch (e: Exception) {
            Log.e(TAG, "Error in extracting block", e)
            return null
        }
    }

    /**
     * ブロックをセルに分割
     */
    private fun divideBlockIntoCells(
        blockMat: Mat,
        blockType: BlockType
    ): List<List<Bitmap>> {
        val cellImages = mutableListOf<List<Bitmap>>()
        val rowHeight = blockMat.height() / NUM_ROWS

        // 列の幅を計算（ブロック全体からの比率）
        val columnWidths = when (blockType) {
            BlockType.B_BLOCK -> {
                // Bブロックの列構成を計算（微調整: 日付14.5mm + 商品名59.5mm = 74mm）
                // 左上マーカー右辺から右上マーカー左辺までの距離から1mm×2を引いた幅が実際のブロック幅
                // その中で、左列（取引日）と右列（商品名）を分割
                val col1Width = (blockMat.width() * B_BLOCK_COL1_WIDTH_MM / (B_BLOCK_COL1_WIDTH_MM + 59.5)).toInt()
                listOf(
                    col1Width,  // 取引日列（14.5mm）
                    blockMat.width() - col1Width  // 商品名列（59.5mm）
                )
            }
            BlockType.C_BLOCK -> {
                // Cブロックの列構成を計算
                // 左列（税込金額）: 約21mm
                // 右列（分類計）: 約21mm
                val col1Width = (blockMat.width() * C_BLOCK_COL1_WIDTH_MM / (C_BLOCK_COL1_WIDTH_MM + 21.0)).toInt()
                listOf(
                    col1Width,  // 税込金額列
                    blockMat.width() - col1Width  // 分類計列
                )
            }
        }

        Log.d(TAG, "Block division - Type: $blockType, Width: ${blockMat.width()}px")
        Log.d(TAG, "  Column widths: ${columnWidths[0]}px, ${columnWidths[1]}px")
        Log.d(TAG, "  Row height: ${rowHeight}px")

        // 各行を処理
        for (row in 0 until NUM_ROWS) {
            val rowCells = mutableListOf<Bitmap>()
            val y = row * rowHeight
            val h = rowHeight

            var currentX = 0
            for ((colIndex, colWidth) in columnWidths.withIndex()) {
                // パディングを適用して罫線を除外
                // 右列（商品名）の左側は特に大きくパディング
                val leftPadding = if (colIndex == 1) RIGHT_COLUMN_LEFT_PADDING_PX else CELL_PADDING_PX
                // 右列の右端はパディングなし（文字が切れるのを防ぐ）
                val rightPadding = if (colIndex == 1) 0 else CELL_PADDING_PX

                val paddedX = currentX + leftPadding
                val paddedY = y + CELL_PADDING_PX
                val paddedWidth = (colWidth - leftPadding - rightPadding).coerceAtLeast(1)
                val paddedHeight = (h - CELL_PADDING_PX * 2).coerceAtLeast(1)

                val cellRect = Rect(paddedX, paddedY, paddedWidth, paddedHeight)
                val cellMat = Mat(blockMat, cellRect)

                // デバッグ：前処理前のセル画像を保存
                if (DEBUG_SAVE_CELL_IMAGES) {
                    val beforeBitmap = matToBitmap(cellMat.clone())
                    saveDebugImage(beforeBitmap, "${blockType}_row${row}_col${colIndex}_before.png")
                }

                // 前処理を適用（列タイプに応じて最適な処理を選択）
                val preprocessedCell = preprocessCell(cellMat, blockType, colIndex)

                // デバッグ：前処理後のセル画像を保存
                if (DEBUG_SAVE_CELL_IMAGES) {
                    val afterBitmap = matToBitmap(preprocessedCell.clone())
                    saveDebugImage(afterBitmap, "${blockType}_row${row}_col${colIndex}_after.png")
                }

                rowCells.add(matToBitmap(preprocessedCell))
                currentX += colWidth
            }
            cellImages.add(rowCells)
        }

        return cellImages
    }

    /**
     * セルの前処理（OCR用に最適化）
     * 最小限の処理：等倍のまま処理
     * 拡大は逆効果という説が多いため、等倍でテスト
     */
    private fun preprocessCell(cellMat: Mat, @Suppress("UNUSED_PARAMETER") blockType: BlockType, @Suppress("UNUSED_PARAMETER") colIndex: Int): Mat {
        // 等倍のまま処理（拡大なし）
        Log.d(TAG, "Preprocessed cell (color, 1x): ${cellMat.width()}x${cellMat.height()}")

        // カラー画像のままML Kitに渡す
        return cellMat
    }

    /**
     * OCR結果の後処理（日付用）
     */
    fun postprocessDate(text: String): String {
        // 1. まず、よくある誤認識を修正（数字以外の文字を除去する前に）
        var fixed = text
            // "0"によく似た文字
            .replace("O", "0")
            .replace("o", "0")
            .replace("D", "0")
            .replace("Q", "0")
            .replace("Ω", "0")  // オメガ
            .replace("○", "0")  // 丸
            // "1"によく似た文字
            .replace("l", "1")
            .replace("I", "1")
            .replace("|", "1")
            .replace("i", "1")
            // "2"によく似た文字
            .replace("Z", "2")
            .replace("z", "2")
            // "5"によく似た文字
            .replace("S", "5")
            .replace("s", "5")
            // "6"によく似た文字
            .replace("b", "6")
            // "8"によく似た文字
            .replace("B", "8")

        // 2. 数字のみを抽出
        val digitsOnly = fixed.replace(Regex("[^0-9]"), "")

        // 3. 日付は必ず6桁の数字（MMDDYY または YYMMDD）
        // 6桁に正規化
        val normalized = when {
            digitsOnly.length >= 6 -> {
                // 6桁以上の場合、最初の6桁を使用
                digitsOnly.substring(0, 6)
            }
            digitsOnly.length == 5 -> {
                // 5桁の場合、先頭に0を追加
                "0$digitsOnly"
            }
            digitsOnly.length == 4 -> {
                // 4桁の場合（MM/DD形式から来た可能性）、年を00として追加
                "${digitsOnly}00"
            }
            else -> {
                // 4桁未満の場合はそのまま返す
                digitsOnly
            }
        }

        // 4. 6桁の場合、2桁ずつ区切って見やすくする（MM/DD/YY 形式）
        return if (normalized.length == 6) {
            "${normalized.substring(0, 2)}/${normalized.substring(2, 4)}/${normalized.substring(4, 6)}"
        } else {
            normalized
        }
    }

    /**
     * OCR結果の後処理（金額用）
     */
    fun postprocessAmount(text: String): String {
        // 数字、カンマ、マイナスのみを抽出
        val cleaned = text.replace(Regex("[^0-9,\\-]"), "")

        // よくある誤認識を修正
        return cleaned
            .replace("O", "0")
            .replace("o", "0")
            .replace("l", "1")
            .replace("I", "1")
            .replace("|", "1")
    }

    /**
     * OCR結果の後処理（商品名用）
     */
    fun postprocessProductName(text: String): String {
        // 前後の空白を除去
        return text.trim()
    }

    /**
     * 小計行かどうかを判定
     */
    fun isSubtotalRow(productName: String): Boolean {
        return productName.startsWith("＊") && productName.contains("小計（")
    }

    /**
     * 小計行から分類名を抽出
     */
    fun extractCategoryFromSubtotal(productName: String): String? {
        val regex = Regex("小計（(.+?)）")
        val matchResult = regex.find(productName)
        return matchResult?.groupValues?.get(1)
    }

    /**
     * ブロック内の列区切り線を検出（ハイブリッド方式）
     *
     * ArUcoマーカーから計算した位置の±30px範囲で実際の線を検出。
     * 線が検出されればその位置を使用、検出できなければ計算位置を使用。
     *
     * @param blockBitmap ブロック画像
     * @param blockType ブロックタイプ（列幅の計算に使用）
     * @return 列区切り線のX座標（ピクセル）
     */
    fun detectColumnSeparator(blockBitmap: Bitmap, blockType: BlockType): Int {
        // 1. ArUcoマーカーから計算した位置（基準点）
        val col1WidthMm = when (blockType) {
            BlockType.B_BLOCK -> B_BLOCK_COL1_WIDTH_MM
            BlockType.C_BLOCK -> C_BLOCK_COL1_WIDTH_MM
        }

        val calculatedSeparatorX = if (lastMmToPixelRatio > 0.0) {
            // 正確な計算：列幅(mm) × mm->pixel変換率
            (col1WidthMm * lastMmToPixelRatio).toInt()
        } else {
            // フォールバック：ブロック幅から推定
            Log.w(TAG, "mm->pixel ratio not set, using fallback calculation")
            (blockBitmap.width * 0.15).toInt() // ブロック幅の約15%
        }

        Log.d(TAG, "Calculated separator position: X=$calculatedSeparatorX (col1Width=${col1WidthMm}mm, ratio=$lastMmToPixelRatio)")

        // 2. 計算位置の周辺で実際の線を検出（±30px範囲）
        val searchRangeLeft = calculatedSeparatorX - 30
        val searchRangeRight = calculatedSeparatorX + 30

        val detectedSeparatorX = detectColumnSeparatorInRange(
            blockBitmap,
            searchRangeLeft,
            searchRangeRight,
            calculatedSeparatorX
        )

        // 3. 線が検出されればそれを使用、検出できなければ計算値を使用
        val finalSeparatorX = detectedSeparatorX ?: calculatedSeparatorX

        if (detectedSeparatorX != null) {
            val diff = Math.abs(detectedSeparatorX - calculatedSeparatorX)
            Log.d(TAG, "✓ Line detected at X=$detectedSeparatorX (diff from calculated: ${diff}px)")
        } else {
            Log.d(TAG, "✗ No line detected in range [$searchRangeLeft, $searchRangeRight], using calculated X=$calculatedSeparatorX")
        }

        return finalSeparatorX
    }

    /**
     * 指定範囲内で列区切り線を検出
     *
     * @param blockBitmap ブロック画像
     * @param xMin 探索範囲の左端（ピクセル）
     * @param xMax 探索範囲の右端（ピクセル）
     * @param calculatedSeparatorX 計算された区切り位置（デバッグ表示用）
     * @return 列区切り線のX座標（ピクセル）、検出できない場合はnull
     */
    private fun detectColumnSeparatorInRange(
        blockBitmap: Bitmap,
        xMin: Int,
        xMax: Int,
        calculatedSeparatorX: Int
    ): Int? {
        try {
            val mat = bitmapToMat(blockBitmap)
            val grayMat = Mat()

            // グレースケールに変換
            if (mat.channels() == 4) {
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGRA2GRAY)
            } else if (mat.channels() == 3) {
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
            } else {
                mat.copyTo(grayMat)
            }

            // ヘッダー領域のみを対象にする（約5-6mm、またはブロック高さの10%の小さい方）
            val headerHeightMm = 6.0  // ヘッダー高さ（mm）
            val headerHeightPx = if (lastMmToPixelRatio > 0.0) {
                (headerHeightMm * lastMmToPixelRatio).toInt()
            } else {
                150  // フォールバック値（約6mm相当）
            }
            // ブロック高さの10%と比較して小さい方を使用
            val headerHeight = minOf(headerHeightPx, (blockBitmap.height * 0.1).toInt())
            val headerRoi = Rect(0, 0, blockBitmap.width, headerHeight)
            val headerMat = Mat(grayMat, headerRoi)

            Log.d(TAG, "Detecting line in header region: height=$headerHeight px (${headerHeightMm}mm ≈ ${headerHeightPx}px, max=${(blockBitmap.height * 0.1).toInt()}px)")

            // エッジ検出（Canny）- ヘッダー領域のみ
            val edges = Mat()
            Imgproc.Canny(headerMat, edges, 50.0, 150.0)

            // Hough Line Transform で線分を検出（ヘッダー領域内）
            val lines = Mat()
            Imgproc.HoughLinesP(
                edges,
                lines,
                1.0,                    // rho: 距離解像度（ピクセル）
                Math.PI / 180.0,        // theta: 角度解像度（ラジアン）
                30,                     // threshold: 投票数の閾値（50→30に緩和）
                headerHeight / 2.0,     // minLineLength: ヘッダー高さの1/2
                10.0                    // maxLineGap: 線分間の最大ギャップ
            )

            Log.d(TAG, "Detected ${lines.rows()} line segments")

            // デバッグ用：検出した線を描画するためのカラー画像
            val debugMat = Mat()
            mat.copyTo(debugMat)
            if (debugMat.channels() == 4) {
                Imgproc.cvtColor(debugMat, debugMat, Imgproc.COLOR_BGRA2BGR)
            }

            // 垂直線をフィルタリング（角度が80-90度の範囲）
            data class LineSegment(val x: Int, val y1: Int, val y2: Int, val length: Int)
            val verticalLineSegments = mutableListOf<LineSegment>()
            val allLineSegments = mutableListOf<IntArray>() // デバッグ用：すべての線分

            for (i in 0 until lines.rows()) {
                val line = lines.get(i, 0)
                val x1 = line[0].toInt()
                val y1 = line[1].toInt()
                val x2 = line[2].toInt()
                val y2 = line[3].toInt()

                allLineSegments.add(intArrayOf(x1, y1, x2, y2))

                // 線分の角度を計算（垂直線は90度）
                val dx = Math.abs(x2 - x1).toDouble()
                val dy = Math.abs(y2 - y1).toDouble()
                val angle = Math.toDegrees(Math.atan2(dy, dx))

                // すべての線分をログ出力（デバッグ用）
                val avgX = (x1 + x2) / 2
                val length = dy.toInt()
                Log.d(TAG, "Line segment #$i: X=$avgX, length=$length, angle=$angle°, dx=$dx, dy=$dy")

                // 垂直線（80-90度）のみを選択
                if (angle >= 80.0 && angle <= 90.0) {
                    Log.d(TAG, "  → Vertical line candidate: X=$avgX (search range: $xMin-$xMax)")

                    // 指定範囲内の線のみを選択
                    if (avgX in xMin..xMax) {
                        verticalLineSegments.add(LineSegment(avgX, Math.min(y1, y2), Math.max(y1, y2), length))
                        Log.d(TAG, "  ✓ Vertical line segment ACCEPTED: X=$avgX, length=$length")
                    } else {
                        Log.d(TAG, "  ✗ Out of search range")
                    }
                } else {
                    Log.d(TAG, "  ✗ Not vertical enough (angle=$angle°)")
                }
            }

            // 同じX座標付近の線分をグループ化（実線と破線の区別）
            val lineGroups = mutableMapOf<Int, MutableList<LineSegment>>()
            val xTolerance = 5 // X座標の許容誤差（ピクセル）- より厳しく設定して別のグループを識別

            for (segment in verticalLineSegments) {
                var foundGroup = false
                for ((groupX, group) in lineGroups) {
                    if (Math.abs(segment.x - groupX) <= xTolerance) {
                        group.add(segment)
                        foundGroup = true
                        break
                    }
                }
                if (!foundGroup) {
                    lineGroups[segment.x] = mutableListOf(segment)
                }
            }

            Log.d(TAG, "Found ${lineGroups.size} vertical line groups")

            // 各グループの特性を分析
            data class LineGroupInfo(val x: Int, val segmentCount: Int, val totalLength: Int, val maxSegmentLength: Int, val avgSegmentLength: Double)
            val groupInfos = mutableListOf<LineGroupInfo>()

            for ((_, segments) in lineGroups) {
                val avgX = segments.map { it.x }.average().toInt()
                val segmentCount = segments.size
                val totalLength = segments.sumOf { it.length }
                val maxSegmentLength = segments.maxOf { it.length }
                val avgSegmentLength = segments.map { it.length }.average()

                groupInfos.add(LineGroupInfo(avgX, segmentCount, totalLength, maxSegmentLength, avgSegmentLength))

                Log.d(TAG, "Group at X=$avgX: segments=$segmentCount, totalLength=$totalLength, " +
                        "maxSegmentLength=$maxSegmentLength, avgSegmentLength=$avgSegmentLength")

                // 実線 vs 破線の判定
                val lineType = if (maxSegmentLength > blockBitmap.height / 3) {
                    "実線 (solid)"
                } else if (segmentCount > 3) {
                    "破線 (dashed)"
                } else {
                    "不明 (unknown)"
                }
                Log.d(TAG, "  → Type: $lineType")
            }

            // 実線を優先的に選択
            // まず、実線候補をフィルタリング（最大線分長がブロック高さの1/3以上）
            val solidCandidates = groupInfos.filter { it.maxSegmentLength > blockBitmap.height / 3 }

            // 複数の実線候補がある場合、より右側（X座標が大きい）を選択
            // 1つしかない場合はそれを選択
            val solidLineGroup = if (solidCandidates.size > 1) {
                Log.d(TAG, "Multiple solid line candidates found: ${solidCandidates.size}")
                solidCandidates.forEach {
                    Log.d(TAG, "  Candidate: X=${it.x}, maxSegmentLength=${it.maxSegmentLength}")
                }
                // より右側のグループを選択
                solidCandidates.maxByOrNull { it.x }
            } else {
                solidCandidates.firstOrNull() ?: groupInfos.maxByOrNull { it.maxSegmentLength }
            }

            if (solidLineGroup == null) {
                Log.w(TAG, "No vertical separator line detected")
                mat.release()
                grayMat.release()
                headerMat.release()
                edges.release()
                lines.release()
                debugMat.release()
                return null
            }

            val separatorX = solidLineGroup.x
            Log.d(TAG, "Selected solid line at X=$separatorX (maxSegmentLength=${solidLineGroup.maxSegmentLength})")

            // デバッグ画像に線を描画
            if (DEBUG_SAVE_CELL_IMAGES) {
                // 半透明の黒いオーバーレイを追加（線を目立たせるため）
                val overlay = Mat()
                debugMat.copyTo(overlay)
                Imgproc.rectangle(
                    overlay,
                    Point(0.0, 0.0),
                    Point(debugMat.width().toDouble(), debugMat.height().toDouble()),
                    Scalar(0.0, 0.0, 0.0),
                    -1  // 塗りつぶし
                )
                Core.addWeighted(debugMat, 0.7, overlay, 0.3, 0.0, debugMat)
                overlay.release()

                // すべての検出された線分を白で描画（影付き）
                for (segment in allLineSegments) {
                    // 影（黒）
                    Imgproc.line(
                        debugMat,
                        Point(segment[0].toDouble() + 2, segment[1].toDouble() + 2),
                        Point(segment[2].toDouble() + 2, segment[3].toDouble() + 2),
                        Scalar(0.0, 0.0, 0.0),
                        8
                    )
                    // 線（白）
                    Imgproc.line(
                        debugMat,
                        Point(segment[0].toDouble(), segment[1].toDouble()),
                        Point(segment[2].toDouble(), segment[3].toDouble()),
                        Scalar(255.0, 255.0, 255.0),
                        6
                    )
                }

                // 各グループの線分を蛍光色で描画
                val colors = listOf(
                    Scalar(0.0, 255.0, 255.0),   // イエロー（蛍光）
                    Scalar(255.0, 0.0, 255.0),   // マゼンタ（蛍光）
                    Scalar(255.0, 255.0, 0.0)    // シアン（蛍光）
                )

                for ((index, info) in groupInfos.withIndex()) {
                    val color = colors[index % colors.size]
                    val groupSegments = lineGroups.values.toList()[index]

                    // グループ内のすべての線分を描画（影付き）
                    for (segment in groupSegments) {
                        // 影（黒）
                        Imgproc.line(
                            debugMat,
                            Point(segment.x.toDouble() + 3, segment.y1.toDouble() + 3),
                            Point(segment.x.toDouble() + 3, segment.y2.toDouble() + 3),
                            Scalar(0.0, 0.0, 0.0),
                            15
                        )
                        // 線（蛍光色）
                        Imgproc.line(
                            debugMat,
                            Point(segment.x.toDouble(), segment.y1.toDouble()),
                            Point(segment.x.toDouble(), segment.y2.toDouble()),
                            color,
                            12
                        )
                    }

                    // グループの種類をテキストで表示（影付き、大きめ）
                    val lineType = if (info.maxSegmentLength > blockBitmap.height / 3) {
                        "実線 SOLID"
                    } else if (info.segmentCount > 3) {
                        "破線 DASHED"
                    } else {
                        "不明 UNKNOWN"
                    }

                    val textPos = Point(info.x.toDouble() + 20, 80.0 + index * 80.0)
                    // テキストの影（黒）
                    Imgproc.putText(
                        debugMat,
                        "X=${info.x} ($lineType)",
                        Point(textPos.x + 3, textPos.y + 3),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        1.5,
                        Scalar(0.0, 0.0, 0.0),
                        6
                    )
                    // テキスト（蛍光色）
                    Imgproc.putText(
                        debugMat,
                        "X=${info.x} ($lineType)",
                        textPos,
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        1.5,
                        color,
                        4
                    )
                }

                // 計算された位置を緑で描画（実際に使用される位置）
                // 影（黒）
                Imgproc.line(
                    debugMat,
                    Point(calculatedSeparatorX.toDouble() + 4, 0.0 + 4),
                    Point(calculatedSeparatorX.toDouble() + 4, blockBitmap.height.toDouble() + 4),
                    Scalar(0.0, 0.0, 0.0),
                    20
                )
                // 線（明るい緑 - 計算値）
                Imgproc.line(
                    debugMat,
                    Point(calculatedSeparatorX.toDouble(), 0.0),
                    Point(calculatedSeparatorX.toDouble(), blockBitmap.height.toDouble()),
                    Scalar(0.0, 255.0, 0.0),
                    16
                )

                // 線検出された位置も表示（比較用、赤色、細め）
                if (separatorX != null) {
                    Imgproc.line(
                        debugMat,
                        Point(separatorX.toDouble(), 0.0),
                        Point(separatorX.toDouble(), blockBitmap.height.toDouble()),
                        Scalar(0.0, 0.0, 255.0),
                        8
                    )
                }

                // 選択された位置の情報を大きく表示（背景付き）
                val textWidth = 700
                val textHeight = 160
                val textX = 20.0
                val textY = blockBitmap.height - 120.0

                // 背景（半透明の黒）
                Imgproc.rectangle(
                    debugMat,
                    Point(textX - 10, textY - 100),
                    Point(textX + textWidth, textY + 60),
                    Scalar(0.0, 0.0, 0.0),
                    -1
                )

                // 計算値の情報（緑）
                Imgproc.putText(
                    debugMat,
                    "CALCULATED: X=$calculatedSeparatorX",
                    Point(textX + 3, textY - 40 + 3),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    1.5,
                    Scalar(0.0, 0.0, 0.0),
                    6
                )
                Imgproc.putText(
                    debugMat,
                    "CALCULATED: X=$calculatedSeparatorX",
                    Point(textX, textY - 40),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    1.5,
                    Scalar(0.0, 255.0, 0.0),
                    4
                )

                // 線検出値の情報（赤）
                if (separatorX != null) {
                    val diff = Math.abs(separatorX - calculatedSeparatorX)
                    Imgproc.putText(
                        debugMat,
                        "LINE-DETECTED: X=$separatorX (diff: ${diff}px)",
                        Point(textX + 3, textY + 20 + 3),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        1.2,
                        Scalar(0.0, 0.0, 0.0),
                        5
                    )
                    Imgproc.putText(
                        debugMat,
                        "LINE-DETECTED: X=$separatorX (diff: ${diff}px)",
                        Point(textX, textY + 20),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        1.2,
                        Scalar(0.0, 100.0, 255.0),
                        3
                    )
                }

                // デバッグ画像を保存
                val debugBitmap = matToBitmap(debugMat)
                saveDebugImage(debugBitmap, "line_detection_debug.png")
                Log.d(TAG, "Line detection debug image saved")
            }

            // メモリ解放
            mat.release()
            grayMat.release()
            headerMat.release()
            edges.release()
            lines.release()
            debugMat.release()

            return separatorX

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting column separator", e)
            return null
        }
    }

    /**
     * デバッグ用：画像を外部ストレージに保存
     */
    private fun saveDebugImage(bitmap: Bitmap, fileName: String) {
        if (!DEBUG_SAVE_CELL_IMAGES) return

        try {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val receiptOcrDir = File(picturesDir, "ReceiptOCR_Debug")

            // タイムスタンプ付きのセッションディレクトリを作成
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val sessionDir = File(receiptOcrDir, timestamp)

            if (!sessionDir.exists()) {
                sessionDir.mkdirs()
            }

            val imageFile = File(sessionDir, fileName)
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Log.d(TAG, "Debug image saved: ${imageFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug image: $fileName", e)
        }
    }

    /**
     * OCR用の画像前処理
     * シャープ化とコントラスト強化を適用してOCR精度を向上
     *
     * @param bitmap 入力画像
     * @param useSharpening シャープ化を適用するか (デフォルト: true)
     * @param useCLAHE コントラスト強化を適用するか (デフォルト: true)
     * @return 前処理後の画像
     */
    fun enhanceImageForOCR(
        bitmap: Bitmap,
        useSharpening: Boolean = true,
        useCLAHE: Boolean = true
    ): Bitmap {
        val mat = bitmapToMat(bitmap)
        var processedMat = mat

        try {
            // 1. グレースケール変換
            val grayMat = Mat()
            if (mat.channels() == 4) {
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGRA2GRAY)
            } else if (mat.channels() == 3) {
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
            } else {
                mat.copyTo(grayMat)
            }

            processedMat = grayMat

            // 2. シャープニング (Unsharp Mask)
            if (useSharpening) {
                val blurred = Mat()
                Imgproc.GaussianBlur(processedMat, blurred, Size(0.0, 0.0), 3.0)

                val sharpened = Mat()
                Core.addWeighted(processedMat, 1.5, blurred, -0.5, 0.0, sharpened)

                processedMat.release()
                processedMat = sharpened
                blurred.release()

                Log.d(TAG, "Applied sharpening (Unsharp Mask)")
            }

            // 3. CLAHE (コントラスト制限適応ヒストグラム均等化)
            if (useCLAHE) {
                val clahe = Imgproc.createCLAHE()
                clahe.clipLimit = 2.0
                clahe.tilesGridSize = Size(8.0, 8.0)

                val claheMat = Mat()
                clahe.apply(processedMat, claheMat)

                processedMat.release()
                processedMat = claheMat

                Log.d(TAG, "Applied CLAHE (clipLimit=2.0, tileSize=8x8)")
            }

            // グレースケールをBGRAに変換してBitmapに戻す
            val bgraMat = Mat()
            Imgproc.cvtColor(processedMat, bgraMat, Imgproc.COLOR_GRAY2BGRA)

            val result = matToBitmap(bgraMat)

            // メモリ解放
            mat.release()
            processedMat.release()
            bgraMat.release()

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error in image enhancement", e)
            mat.release()
            if (processedMat != mat) {
                processedMat.release()
            }
            return bitmap
        }
    }

    /**
     * mm→px変換率を取得
     *
     * @return mm→px変換率（透視変換時に計算された値）
     */
    fun getMmToPixelRatio(): Double {
        return lastMmToPixelRatio
    }

    /**
     * warp後画像からmm→px比率を再計算（精度向上）
     *
     * warp後の画像は歪みが補正されているため、より正確な比率を計算できる
     *
     * @param warpedBitmap warp後の画像
     * @param originalCorners 元のマーカー座標
     * @param originalIds 元のマーカーID
     * @param blockType ブロックタイプ
     * @return 再計算されたmm→px比率、失敗時はnull
     */
    fun recalculateMmToPixelRatioFromWarpedImage(
        warpedBitmap: Bitmap,
        originalCorners: List<MatOfPoint2f>,
        originalIds: Mat,
        blockType: BlockType
    ): Double? {
        try {
            Log.d(TAG, "Recalculating mm->px ratio from warped image...")

            // warp後画像でArUco再検出
            val warpedResult = detectArucoMarkers(warpedBitmap)
            if (!warpedResult.isValid || warpedResult.corners.size < 2) {
                Log.w(TAG, "Failed to detect markers in warped image")
                return null
            }

            // マーカーIDと座標のマップを作成
            val markerCorners = mutableMapOf<Int, MatOfPoint2f>()
            for (i in 0 until warpedResult.ids.rows()) {
                val id = warpedResult.ids.get(i, 0)[0].toInt()
                markerCorners[id] = warpedResult.corners[i]
            }

            // ブロックタイプに応じて使用するマーカーIDを決定
            val (leftMarkerId, rightMarkerId) = when (blockType) {
                BlockType.B_BLOCK -> Pair(0, 1)  // 左上と右上
                BlockType.C_BLOCK -> Pair(4, 5)  // 左上と右上
            }

            // 必要なマーカーが存在するか確認
            if (!markerCorners.containsKey(leftMarkerId) || !markerCorners.containsKey(rightMarkerId)) {
                Log.w(TAG, "Required markers not found in warped image")
                return null
            }

            // 左右マーカーの中心座標を計算
            val leftMarker = markerCorners[leftMarkerId]!!.toArray()
            val rightMarker = markerCorners[rightMarkerId]!!.toArray()

            val leftCenter = Point(
                (leftMarker[0].x + leftMarker[1].x + leftMarker[2].x + leftMarker[3].x) / 4.0,
                (leftMarker[0].y + leftMarker[1].y + leftMarker[2].y + leftMarker[3].y) / 4.0
            )
            val rightCenter = Point(
                (rightMarker[0].x + rightMarker[1].x + rightMarker[2].x + rightMarker[3].x) / 4.0,
                (rightMarker[0].y + rightMarker[1].y + rightMarker[2].y + rightMarker[3].y) / 4.0
            )

            // マーカー中心間のピクセル距離を計算
            val distancePx = sqrt(
                (rightCenter.x - leftCenter.x) * (rightCenter.x - leftCenter.x) +
                (rightCenter.y - leftCenter.y) * (rightCenter.y - leftCenter.y)
            )

            // 実距離（mm）を計算
            // 台紙設計値: 伝票幅約180mm、マーカーは左右端に配置
            // マーカー中心間距離 = 伝票幅 - マーカーサイズ（左右の中心なので）
            // 実測に基づく設計値を使用
            val distanceMm = when (blockType) {
                BlockType.B_BLOCK -> 180.0 - MARKER_SIZE_MM  // 約155mm
                BlockType.C_BLOCK -> 180.0 - MARKER_SIZE_MM  // 約155mm
            }

            // mm→px比率を計算
            val mmToPixelRatio = distancePx / distanceMm

            Log.d(TAG, "Warped image recalculation:")
            Log.d(TAG, "  Left marker center: (${leftCenter.x}, ${leftCenter.y})")
            Log.d(TAG, "  Right marker center: (${rightCenter.x}, ${rightCenter.y})")
            Log.d(TAG, "  Distance (px): $distancePx")
            Log.d(TAG, "  Distance (mm): $distanceMm")
            Log.d(TAG, "  Recalculated mm->px ratio: $mmToPixelRatio")
            Log.d(TAG, "  Previous ratio: $lastMmToPixelRatio")
            Log.d(TAG, "  Difference: ${mmToPixelRatio - lastMmToPixelRatio} px/mm (${((mmToPixelRatio - lastMmToPixelRatio) / lastMmToPixelRatio * 100)}%)")

            // lastMmToPixelRatioを更新
            lastMmToPixelRatio = mmToPixelRatio

            return mmToPixelRatio

        } catch (e: Exception) {
            Log.e(TAG, "Error recalculating mm->px ratio from warped image", e)
            return null
        }
    }
}
