package com.example.greenframeocr.util

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * OCR処理ユーティリティクラス
 * ML Kit Text Recognition を使用
 *
 * processUnderlayingBase() が旧ArUco方式の高精度OCRパイプライン。
 * 透視変換後の全体画像を ML Kit で一発認識し、
 * TextBox の座標から行・列に振り分ける。
 */
object OCRProcessor {
    private const val TAG = "OCRProcessor"

    // 日本語モデル（商品名など日本語テキスト用）
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    // Latinモデル（数字・記号に特化、日付列などに使用）
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 画像からテキストを認識（日本語モデル）
     */
    suspend fun recognizeText(bitmap: Bitmap): Text? {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(inputImage).await()
            Log.d(TAG, "Recognized text (Japanese): ${result.text}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing text", e)
            null
        }
    }

    /**
     * 画像からテキストを認識（Latinモデル - 数字・記号に強い）
     */
    suspend fun recognizeTextLatin(bitmap: Bitmap): Text? {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = latinRecognizer.process(inputImage).await()
            Log.d(TAG, "Recognized text (Latin): ${result.text}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing text with Latin model", e)
            null
        }
    }

    /**
     * リソースのクリーンアップ
     */
    fun close() {
        recognizer.close()
        latinRecognizer.close()
    }

    // ============================================================
    // 旧ArUco方式 OCR パイプライン
    // ============================================================

    /**
     * ダブルOCR結果（グレー版 + 二値版）
     */
    data class DoubleOcrResult(
        val grayText: String?,
        val binaryText: String?,
        val binaryCandidateScore: Double = 0.0,
        val fallbackUsed: Boolean = false,
        val fallbackSource: String? = null
    )

    enum class FallbackReason { ITEM_OCR_EMPTY }

    data class FallbackEvent(
        val rowIndex: Int,
        val rowY: Int,
        val rawText: String,
        val reason: FallbackReason,
        val textHeight: Float = 0f,
        val boxCount: Int = 1,
        val separatedTexts: List<String> = emptyList()
    )

    /**
     * 透視変換後の伝票画像を旧ArUco方式のパイプラインで OCR 処理する。
     *
     * フロー:
     * 1. 列範囲を初期化（mmToPixelRatio に基づく）
     * 2. ML Kit 全体OCR（日本語モデル）
     * 3. TextBox 変換
     * 4. ノイズ除去
     * 5. 行クラスタリング
     * 6. 行タイプ判定 → 行データ生成
     * 7. Y座標フィルタリング
     * 8. 数量列特化OCR（Latin model）
     * 8.5. 商品名列特化OCR（Japanese model + ダブルOCR）
     * 8.6. 商品名フォールバック（列特化OCRが空の場合）
     * 9. 数量・商品名を上書き
     * 10. 小計抽出
     * 11. カテゴリ判定
     *
     * @param warpedBitmap 透視変換後の伝票画像（4000×2916px 想定）
     * @param mmToPixelRatio mm→px変換率 = warpedBitmap.width / 203.0
     */
    suspend fun processUnderlayingBase(
        warpedBitmap: Bitmap,
        mmToPixelRatio: Double
    ): ProcessUnderlayingBaseResult {
        val totalStart = System.currentTimeMillis()
        Log.d(TAG, "[UNDERLAY] ===== Start (${warpedBitmap.width}×${warpedBitmap.height}) =====")

        // 1. 列範囲を初期化
        UnderlyingBaseProcessor.initializeColumnRanges(mmToPixelRatio)

        // 2. ML Kit 全体OCR
        var t = System.currentTimeMillis()
        val text = recognizeText(warpedBitmap) ?: run {
            Log.w(TAG, "[UNDERLAY] OCR failed")
            return ProcessUnderlayingBaseResult(emptyList(), emptyList(), emptyMap())
        }
        Log.d(TAG, "[PERF] Step2 全体OCR(日本語): ${System.currentTimeMillis() - t} ms  (${text.textBlocks.size} blocks)")

        // 3. TextBox 変換
        t = System.currentTimeMillis()
        val textBoxes = convertToTextBoxes(text)
        Log.d(TAG, "[PERF] Step3 TextBox変換: ${System.currentTimeMillis() - t} ms  (${textBoxes.size} boxes)")

        // 4. ノイズ除去
        t = System.currentTimeMillis()
        val filteredBoxes = UnderlyingBaseProcessor.filterNoise(textBoxes)
        Log.d(TAG, "[PERF] Step4 ノイズ除去: ${System.currentTimeMillis() - t} ms  (${filteredBoxes.size} boxes)")

        // 4.5. フォールバック用: 商品名列重複TextBox を抽出
        val itemRange = UnderlyingBaseProcessor.getItemRange()
        val fallbackCandidateBoxes = filteredBoxes.filter { box ->
            box.bounds.right >= itemRange.first && box.bounds.left <= itemRange.last
        }

        // 5. 行クラスタリング
        t = System.currentTimeMillis()
        val rows = UnderlyingBaseProcessor.clusterRows(filteredBoxes)
        Log.d(TAG, "[PERF] Step5 行クラスタリング: ${System.currentTimeMillis() - t} ms  (${rows.size} rows)")

        // 6-7. 行処理 + Y座標フィルタリング
        t = System.currentTimeMillis()
        val rowYCoordinates = mutableListOf<Int>()
        val receiptRows = rows.mapIndexed { index, rowBoxes ->
            val avgY    = rowBoxes.map { it.centerY }.average().toInt()
            rowYCoordinates.add(avgY)
            val rowType = UnderlyingBaseProcessor.detectRowType(rowBoxes)
            val row     = UnderlyingBaseProcessor.processRow(rowBoxes, rowType)
            Log.d(TAG, "[UNDERLAY] Row $index (Y=$avgY) type=$rowType: ${row.rawText}")
            row
        }

        val normalRange = UnderlyingBaseProcessor.getNormalRowYRange()
        val totalRange  = UnderlyingBaseProcessor.getMonthlyTotalYRange()

        // 小計行は通常行グリッド内に混在するため normalRange でフィルタ（原ReceiptOCRと同じ）
        val filteredRowsWithIndices = receiptRows.mapIndexedNotNull { index, row ->
            val y = rowYCoordinates[index]
            when (row.rowType) {
                UnderlyingBaseProcessor.RowType.NORMAL ->
                    if (y in normalRange.first..normalRange.last) index to row
                    else { Log.d(TAG, "[UNDERLAY] Filtered normal row $index (Y=$y, outside normal range)"); null }
                UnderlyingBaseProcessor.RowType.SUBTOTAL ->
                    if (y in normalRange.first..normalRange.last) index to row
                    else { Log.d(TAG, "[UNDERLAY] Filtered subtotal row $index (Y=$y, outside normal range)"); null }
                UnderlyingBaseProcessor.RowType.MONTHLY_TOTAL ->
                    if (y in totalRange.first..totalRange.last) index to row
                    else { Log.d(TAG, "[UNDERLAY] Filtered monthly total row $index (Y=$y)"); null }
                UnderlyingBaseProcessor.RowType.EMPTY -> null
            }
        }

        val filteredRows         = filteredRowsWithIndices.map { it.second }
        val filteredYCoordinates = filteredRowsWithIndices.map { rowYCoordinates[it.first] }
        Log.d(TAG, "[PERF] Step6-7 行処理+フィルタ: ${System.currentTimeMillis() - t} ms  (${receiptRows.size} → ${filteredRows.size} rows)")

        // 8. 数量列特化OCR
        t = System.currentTimeMillis()
        val quantityMap = extractQuantitiesFromColumn(warpedBitmap, filteredRows, filteredYCoordinates)
        Log.d(TAG, "[PERF] Step8 数量列OCR: ${System.currentTimeMillis() - t} ms  (${quantityMap.size} quantities)")

        // 8.5. 商品名列特化OCR
        t = System.currentTimeMillis()
        val productNameDoubleOcrMapRaw = extractProductNamesFromColumn(warpedBitmap, filteredRows, filteredYCoordinates)
        Log.d(TAG, "[PERF] Step8.5 商品名列OCR: ${System.currentTimeMillis() - t} ms  (${productNameDoubleOcrMapRaw.size} names)")

        // 8.6. 商品名フォールバック
        t = System.currentTimeMillis()
        val yThreshold = 15
        val productNameDoubleOcrMap = productNameDoubleOcrMapRaw.toMutableMap()
        val fallbackEvents = mutableListOf<FallbackEvent>()

        filteredRows.forEachIndexed { rowIndex, row ->
            if (row.rowType != UnderlyingBaseProcessor.RowType.NORMAL) return@forEachIndexed
            val existingResult = productNameDoubleOcrMapRaw[rowIndex]
            val hasValid = existingResult != null &&
                    (!existingResult.grayText.isNullOrBlank() || !existingResult.binaryText.isNullOrBlank())

            if (!hasValid) {
                val rowY = filteredYCoordinates.getOrNull(rowIndex) ?: return@forEachIndexed
                val matchingBoxes = fallbackCandidateBoxes.filter {
                    kotlin.math.abs(it.centerY - rowY) <= yThreshold
                }
                if (matchingBoxes.isNotEmpty()) {
                    val sortedBoxes     = matchingBoxes.sortedBy { it.centerX }
                    val separatedTexts  = sortedBoxes.map { it.text }
                    val fallbackText    = separatedTexts.joinToString("")
                    val dateRemovedText = fallbackText
                        .replace(Regex("^[a-zA-Zp]?\\d{6}"), "").trim()
                    val cleanedFallback = UnderlyingBaseProcessor.cleanItemName(dateRemovedText)

                    if (cleanedFallback.isNotBlank()) {
                        productNameDoubleOcrMap[rowIndex] = DoubleOcrResult(
                            grayText           = cleanedFallback,
                            binaryText         = null,
                            fallbackUsed       = true,
                            fallbackSource     = fallbackText
                        )
                        val avgH = sortedBoxes.map { it.bounds.height().toFloat() }.average().toFloat()
                        fallbackEvents.add(FallbackEvent(
                            rowIndex       = rowIndex,
                            rowY           = rowY,
                            rawText        = fallbackText,
                            reason         = FallbackReason.ITEM_OCR_EMPTY,
                            textHeight     = avgH,
                            boxCount       = sortedBoxes.size,
                            separatedTexts = separatedTexts
                        ))
                        Log.d(TAG, "[UNDERLAY] Fallback row $rowIndex: '$cleanedFallback'")
                    }
                }
            }
        }
        Log.d(TAG, "[PERF] Step8.6 商品名フォールバック: ${System.currentTimeMillis() - t} ms")

        // 9. 数量・商品名を上書き
        val updatedRows = filteredRows.mapIndexed { index, row ->
            val newQuantity = quantityMap[index] ?: row.quantity
            val doubleResult = productNameDoubleOcrMap[index]
            val newItemName  = doubleResult?.grayText ?: doubleResult?.binaryText ?: row.itemName

            val finalQuantity = if (newItemName?.contains("返品") == true && newQuantity != null) {
                val qty = newQuantity.toIntOrNull()
                if (qty != null && qty > 0) (-qty).toString() else newQuantity
            } else newQuantity

            row.copy(quantity = finalQuantity, itemName = newItemName)
        }

        // 10. 小計抽出
        val subtotals = updatedRows
            .filter { it.rowType == UnderlyingBaseProcessor.RowType.SUBTOTAL }
            .mapNotNull { row -> row.categorySum?.let { UnderlyingBaseProcessor.SubtotalData(it, 0, 0) } }

        // 11. カテゴリ判定
        t = System.currentTimeMillis()
        val rowsWithCategories = UnderlyingBaseProcessor.assignCategories(updatedRows)
        Log.d(TAG, "[PERF] Step11 カテゴリ判定: ${System.currentTimeMillis() - t} ms")

        Log.d(TAG, "[PERF] ===== 合計: ${System.currentTimeMillis() - totalStart} ms =====")
        Log.d(TAG, "[UNDERLAY] ===== Complete: ${rowsWithCategories.size} rows =====")

        return ProcessUnderlayingBaseResult(
            rows                   = updatedRows,
            subtotals              = subtotals,
            productNameDoubleOcrMap = productNameDoubleOcrMap,
            fallbackEvents         = fallbackEvents,
            rowsWithCategories     = rowsWithCategories,
            textBoxes              = filteredBoxes
        )
    }

    data class ProcessUnderlayingBaseResult(
        val rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        val subtotals: List<UnderlyingBaseProcessor.SubtotalData>,
        val productNameDoubleOcrMap: Map<Int, DoubleOcrResult>,
        val fallbackEvents: List<FallbackEvent> = emptyList(),
        val rowsWithCategories: List<Pair<UnderlyingBaseProcessor.ReceiptRow, String>> = emptyList(),
        val textBoxes: List<UnderlyingBaseProcessor.TextBox> = emptyList()
    )

    // ============================================================
    // 数量列特化OCR
    // ============================================================

    private suspend fun extractQuantitiesFromColumn(
        warpedBitmap: Bitmap,
        rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        rowYCoordinates: List<Int>
    ): Map<Int, String> {
        Log.d(TAG, "[QTY] ===== Start =====")

        val quantityRange = UnderlyingBaseProcessor.getQuantityRange()
        val quantityX     = quantityRange.first
        val quantityWidth = quantityRange.last - quantityRange.first

        val quantityMap = mutableMapOf<Int, String>()

        rows.forEachIndexed { rowIndex, row ->
            val rowY = rowYCoordinates.getOrNull(rowIndex)
            if (rowY == null || row.rowType != UnderlyingBaseProcessor.RowType.NORMAL) return@forEachIndexed

            try {
                val rowHeight = 50
                val roiY      = (rowY - rowHeight / 2).coerceAtLeast(0)
                val roiH      = rowHeight.coerceAtMost(warpedBitmap.height - roiY)

                val rowRoiBitmap = Bitmap.createBitmap(
                    warpedBitmap, quantityX, roiY,
                    quantityWidth.coerceAtMost(warpedBitmap.width - quantityX), roiH
                )

                val grayMat = org.opencv.core.Mat()
                org.opencv.android.Utils.bitmapToMat(rowRoiBitmap, grayMat)
                val grayGray = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.cvtColor(grayMat, grayGray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
                grayMat.release()

                val cleanedMat  = ImagePreprocessor.removeLines(grayGray, removeVertical = true, removeHorizontal = false)
                grayGray.release()

                // アップスケーリング無し（15px/mm で十分な解像度）
                val roiBitmap = matToBitmap(cleanedMat)
                cleanedMat.release()

                val ocrText = recognizeTextLatin(roiBitmap)
                roiBitmap.recycle()
                rowRoiBitmap.recycle()

                ocrText?.textBlocks?.forEach { block ->
                    block.lines.forEach { line ->
                        val t      = line.text.trim()
                        val bounds = line.boundingBox ?: return@forEach
                        val aspect = bounds.width().toFloat() / bounds.height()
                        if (aspect > 5.0 || bounds.height() < 12) return@forEach
                        if (t.matches(Regex("^[0-9]{1,3}$"))) {
                            quantityMap[rowIndex] = t
                            Log.d(TAG, "[QTY] Row $rowIndex: '$t'")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[QTY] Row $rowIndex error", e)
            }
        }

        Log.d(TAG, "[QTY] ===== Complete: ${quantityMap.size} =====")
        return quantityMap
    }

    // ============================================================
    // 商品名列特化OCR
    // ============================================================

    private suspend fun extractProductNamesFromColumn(
        warpedBitmap: Bitmap,
        rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        rowYCoordinates: List<Int>
    ): Map<Int, DoubleOcrResult> {
        Log.d(TAG, "[PRODUCT] ===== Start =====")

        var itemColumnBitmap: Bitmap? = null
        var grayBitmap: Bitmap? = null
        var enhancedBitmap: Bitmap? = null
        var grayMat: org.opencv.core.Mat? = null
        var grayMatGray: org.opencv.core.Mat? = null
        var openedMat: org.opencv.core.Mat? = null
        var grayMatForOcr: org.opencv.core.Mat? = null
        var grayBitmapForOcr: Bitmap? = null
        var scaledGrayBitmap: Bitmap? = null
        var binaryMat: org.opencv.core.Mat? = null
        var binaryBitmapForOcr: Bitmap? = null
        var scaledBinaryBitmap: Bitmap? = null

        var grayOcrText: com.google.mlkit.vision.text.Text? = null
        var binaryOcrText: com.google.mlkit.vision.text.Text? = null
        var scaleFactor = 1f
        var itemX = 0
        var itemY = 0
        var binaryCandidateScore = 0.0

        try {
            val itemRange = UnderlyingBaseProcessor.getItemRange()
            itemX = itemRange.first
            val itemWidth = itemRange.last - itemRange.first

            val normalRowYRange = UnderlyingBaseProcessor.getNormalRowYRange()
            itemY = normalRowYRange.first
            val itemHeight = normalRowYRange.last - normalRowYRange.first

            itemColumnBitmap = Bitmap.createBitmap(
                warpedBitmap, itemX, itemY,
                itemWidth.coerceAtMost(warpedBitmap.width - itemX),
                itemHeight.coerceAtMost(warpedBitmap.height - itemY)
            )

            grayBitmap     = ImagePreprocessor.toGray(itemColumnBitmap!!)
            enhancedBitmap = ImagePreprocessor.adjustContrast(grayBitmap!!, 1.2f)

            grayMat = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(enhancedBitmap, grayMat)
            grayMatGray = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(grayMat, grayMatGray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            grayMat!!.release(); grayMat = null

            val charPx         = ImagePreprocessor.estimateCharHeightPx(enhancedBitmap!!)
            Log.d(TAG, "[PRODUCT] charPx=$charPx")

            val edgeDensity    = ImagePreprocessor.calcEdgeDensity(grayMatGray!!)
            val blackRatio     = ImagePreprocessor.calcBlackRatio(grayMatGray!!)
            val strokeWidthVar = ImagePreprocessor.calcStrokeWidthVariance(grayMatGray!!)

            binaryCandidateScore = ImagePreprocessor.calcBinaryCandidateScore(
                charPx, edgeDensity, blackRatio, strokeWidthVar
            )

            openedMat = ImagePreprocessor.safeMorphOpen(grayMatGray!!, charPx)

            val canTryBinary  = charPx >= 18f && blackRatio <= 0.45 && edgeDensity >= 0.02
            val shouldUseBinary = canTryBinary && binaryCandidateScore >= 0.5
            Log.d(TAG, "[PRODUCT] binaryScore=${"%.3f".format(binaryCandidateScore)}, useBinary=$shouldUseBinary")

            // アップスケーリング無し（scaleFactor=1.0f）
            scaleFactor = 1.0f

            grayMatForOcr    = openedMat!!.clone()
            grayBitmapForOcr = matToBitmap(grayMatForOcr!!)
            grayOcrText      = recognizeText(grayBitmapForOcr!!)

            if (shouldUseBinary) {
                binaryMat          = ImagePreprocessor.safeAdaptiveThreshold(openedMat!!, charPx)
                binaryBitmapForOcr = matToBitmap(binaryMat!!)
                binaryOcrText      = recognizeText(binaryBitmapForOcr!!)
            }

        } finally {
            itemColumnBitmap?.recycle()
            grayBitmap?.recycle()
            enhancedBitmap?.recycle()
            grayMat?.release()
            grayMatGray?.release()
            openedMat?.release()
            grayMatForOcr?.release()
            grayBitmapForOcr?.recycle()
            binaryMat?.release()
            binaryBitmapForOcr?.recycle()
        }

        if (grayOcrText == null && binaryOcrText == null) {
            Log.w(TAG, "[PRODUCT] Both OCRs failed")
            return emptyMap()
        }

        val grayProductBoxes   = extractTextBoxesFromOcrResult(grayOcrText,   scaleFactor, itemX, itemY).sortedBy { it.centerY }
        val binaryProductBoxes = extractTextBoxesFromOcrResult(binaryOcrText, scaleFactor, itemX, itemY).sortedBy { it.centerY }

        val grayProductMap   = mapTextBoxesToRows(grayProductBoxes,   rows, rowYCoordinates)
        val binaryProductMap = mapTextBoxesToRows(binaryProductBoxes, rows, rowYCoordinates)

        val doubleOcrMap = mutableMapOf<Int, DoubleOcrResult>()
        (grayProductMap.keys + binaryProductMap.keys).toSet().forEach { index ->
            val grayText   = grayProductMap[index]
            val binaryText = binaryProductMap[index]
            if (grayText != null || binaryText != null) {
                doubleOcrMap[index] = DoubleOcrResult(
                    grayText             = grayText,
                    binaryText           = binaryText,
                    binaryCandidateScore = binaryCandidateScore
                )
                Log.d(TAG, "[PRODUCT] Row $index: gray='$grayText', binary='$binaryText'")
            }
        }

        Log.d(TAG, "[PRODUCT] ===== Complete: ${doubleOcrMap.size} =====")
        return doubleOcrMap
    }

    // ============================================================
    // ヘルパー
    // ============================================================

    private fun extractTextBoxesFromOcrResult(
        ocrText: Text?,
        scaleFactor: Float,
        offsetX: Int,
        offsetY: Int
    ): List<UnderlyingBaseProcessor.TextBox> {
        if (ocrText == null) return emptyList()

        return ocrText.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                val text   = line.text.trim()
                if (text.isEmpty()) return@mapNotNull null

                val origLeft   = (bounds.left   / scaleFactor).toInt() + offsetX
                val origTop    = (bounds.top    / scaleFactor).toInt() + offsetY
                val origRight  = (bounds.right  / scaleFactor).toInt() + offsetX
                val origBottom = (bounds.bottom / scaleFactor).toInt() + offsetY
                val origBounds = android.graphics.Rect(origLeft, origTop, origRight, origBottom)

                UnderlyingBaseProcessor.TextBox(
                    text    = text,
                    bounds  = origBounds,
                    centerX = origBounds.centerX(),
                    centerY = origBounds.centerY()
                )
            }
        }
    }

    /**
     * 商品名テキストの先頭に混入しやすい縦罫線ノイズを除去する。
     * - `|` は常に除去（縦罫線の誤認識、商品名の先頭には絶対に来ない）
     * - `I` は直後が日本語文字（ひらがな・カタカナ・漢字）の場合のみ除去
     * - 数字 `1` は誤除去リスクがあるため対象外
     */
    private fun cleanLeadingRuleNoise(text: String): String {
        var s = text.trimStart('|')
        // 先頭が `I` で、その次が日本語文字なら縦罫線の誤認識とみなして除去
        if (s.length >= 2 && s[0] == 'I' && s[1].code.let { c ->
                c in 0x3040..0x30FF ||  // ひらがな・カタカナ
                c in 0x4E00..0x9FFF     // CJK漢字
            }) {
            s = s.substring(1)
        }
        return s.trim()
    }

    private fun mapTextBoxesToRows(
        productBoxes: List<UnderlyingBaseProcessor.TextBox>,
        rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        rowYCoordinates: List<Int>
    ): Map<Int, String> {
        val productMap = mutableMapOf<Int, String>()

        val lineHeights = productBoxes.mapNotNull { it.bounds.height() }
        val avgLineH    = if (lineHeights.isNotEmpty()) lineHeights.average() else 28.0
        val rowHThresh  = maxOf((avgLineH * 0.8).toInt(), 15)

        productBoxes.forEach { box ->
            var bestIndex    = -1
            var bestDistance = Int.MAX_VALUE

            rowYCoordinates.forEachIndexed { idx, rowY ->
                val distance = kotlin.math.abs(box.centerY - rowY)
                if (distance < bestDistance && distance < rowHThresh * 2) {
                    bestDistance = distance
                    bestIndex    = idx
                }
            }

            if (bestIndex in 0 until rows.size) {
                val cleaned  = cleanLeadingRuleNoise(box.text)
                val existing = productMap[bestIndex]
                if (cleaned != box.text) Log.d(TAG, "[PRODUCT] Cleaned '${box.text}' → '$cleaned'")
                productMap[bestIndex] = if (existing != null) "$existing $cleaned" else cleaned
                Log.d(TAG, "[PRODUCT] Map '$cleaned' → row $bestIndex (dist=$bestDistance)")
            }
        }

        return productMap
    }

    private fun convertToTextBoxes(text: Text): List<UnderlyingBaseProcessor.TextBox> {
        val textBoxes = mutableListOf<UnderlyingBaseProcessor.TextBox>()

        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox ?: return@forEach
                textBoxes.add(
                    UnderlyingBaseProcessor.TextBox(
                        text    = line.text,
                        bounds  = bounds,
                        centerX = bounds.centerX(),
                        centerY = bounds.centerY()
                    )
                )

                // 先頭6桁が数字なら日付として別途追加
                val normalized = UnderlyingBaseProcessor.normalizeToDigits(line.text).take(6)
                if (normalized.length == 6) {
                    val avgCharWidth = bounds.width() / line.text.length.coerceAtLeast(1)
                    val dateWidth    = (avgCharWidth * 6).toInt()
                    val dateBounds   = android.graphics.Rect(
                        bounds.left, bounds.top,
                        (bounds.left + dateWidth).coerceAtMost(bounds.right), bounds.bottom
                    )
                    textBoxes.add(
                        UnderlyingBaseProcessor.TextBox(
                            text    = normalized,
                            bounds  = dateBounds,
                            centerX = bounds.left + dateWidth / 2,
                            centerY = dateBounds.centerY()
                        )
                    )
                }
            }
        }

        Log.d(TAG, "convertToTextBoxes: ${textBoxes.size} boxes")
        return textBoxes
    }

    private fun scaleForOcr(bitmap: Bitmap, charPx: Float): Bitmap {
        val scaleFactor = ImagePreprocessor.calcScaleFactor(charPx, 32f)
        if (scaleFactor <= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width  * scaleFactor).toInt(),
            (bitmap.height * scaleFactor).toInt(),
            true
        )
    }

    private fun matToBitmap(mat: org.opencv.core.Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(mat, bitmap)
        return bitmap
    }
}
