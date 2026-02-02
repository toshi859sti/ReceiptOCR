package com.example.receiptorc.util

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
 */
object OCRProcessor {
    private const val TAG = "OCRProcessor"

    // 日本語モデル（商品名など日本語テキスト用）
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    // Latinモデル（数字・記号に特化、日付列などに使用）
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * セルデータ
     */
    data class CellData(
        val row: Int,
        val column: Int,
        val text: String,
        val confidence: Float
    )

    /**
     * ブロックOCR結果
     */
    data class BlockOCRResult(
        val cells: List<List<CellData>>,  // [row][column]
        val blockType: ImageProcessor.BlockType
    )

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
     * ブロック全体をOCR
     */
    suspend fun recognizeBlock(
        cellImages: List<List<Bitmap>>,
        blockType: ImageProcessor.BlockType
    ): BlockOCRResult {
        val cells = mutableListOf<List<CellData>>()

        for ((rowIndex, rowCells) in cellImages.withIndex()) {
            val rowData = mutableListOf<CellData>()

            for ((colIndex, cellBitmap) in rowCells.withIndex()) {
                val text = recognizeText(cellBitmap)
                val recognizedText = text?.text ?: ""

                // テキストの信頼度を計算（平均）
                val confidence = if (text != null && text.textBlocks.isNotEmpty()) {
                    text.textBlocks.map { block ->
                        block.lines.map { line ->
                            line.elements.map { element ->
                                // ML Kit doesn't provide confidence directly
                                // Use a default value
                                1.0f
                            }.average().toFloat()
                        }.average().toFloat()
                    }.average().toFloat()
                } else {
                    0.0f
                }

                // 後処理を適用
                val processedText = when (blockType) {
                    ImageProcessor.BlockType.B_BLOCK -> {
                        if (colIndex == 0) {
                            // 取引日列
                            ImageProcessor.postprocessDate(recognizedText)
                        } else {
                            // 商品名列
                            ImageProcessor.postprocessProductName(recognizedText)
                        }
                    }
                    ImageProcessor.BlockType.C_BLOCK -> {
                        // 金額列と分類計列
                        ImageProcessor.postprocessAmount(recognizedText)
                    }
                }

                val cellData = CellData(rowIndex, colIndex, processedText, confidence)
                rowData.add(cellData)

                Log.d(TAG, "Row $rowIndex, Col $colIndex: $processedText (confidence: $confidence)")
            }

            cells.add(rowData)
        }

        return BlockOCRResult(cells, blockType)
    }

    /**
     * ブロック全体を列ごとに2回OCR（ハイブリッド方式）
     *
     * 1. 列区切り線を検出（ArUco計算 + 実際の線検出）
     * 2. 左列（日付）をクロップ → Latinモデルで全体をOCR
     * 3. 右列（商品名）をクロップ → 日本語モデルで全体をOCR
     * 4. Y座標でマッチング
     */
    suspend fun recognizeWholeBlock(
        blockBitmap: Bitmap,
        blockType: ImageProcessor.BlockType,
        useUpscaling: Boolean = false
    ): List<BBlockRow> {
        val scalingMode = if (useUpscaling) "4x upscaling" else "1x (no scaling)"
        Log.d(TAG, "Recognizing whole block (2-column OCR, $scalingMode): ${blockBitmap.width}x${blockBitmap.height}")

        // 1. 列区切り線を検出（ハイブリッド方式: ArUco計算 + 実際の線検出）
        val separatorX = ImageProcessor.detectColumnSeparator(blockBitmap, blockType)
        Log.d(TAG, "Column separator at X=$separatorX")

        // 2. 左列（日付列）をクロップ
        var leftColumnBitmap = Bitmap.createBitmap(
            blockBitmap,
            0,
            0,
            separatorX.coerceAtMost(blockBitmap.width),
            blockBitmap.height
        )

        // 3. 右列（商品名列）をクロップ
        var rightColumnBitmap = if (separatorX < blockBitmap.width) {
            Bitmap.createBitmap(
                blockBitmap,
                separatorX,
                0,
                blockBitmap.width - separatorX,
                blockBitmap.height
            )
        } else {
            // 区切り位置がブロック幅を超える場合は空の画像
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        // 4. 拡大処理（オプション）
        if (useUpscaling) {
            Log.d(TAG, "Applying 4x upscaling to columns...")
            leftColumnBitmap = Bitmap.createScaledBitmap(
                leftColumnBitmap,
                leftColumnBitmap.width * 4,
                leftColumnBitmap.height * 4,
                true
            )
            rightColumnBitmap = Bitmap.createScaledBitmap(
                rightColumnBitmap,
                rightColumnBitmap.width * 4,
                rightColumnBitmap.height * 4,
                true
            )
        }

        Log.d(TAG, "Left column size: ${leftColumnBitmap.width}x${leftColumnBitmap.height}")
        Log.d(TAG, "Right column size: ${rightColumnBitmap.width}x${rightColumnBitmap.height}")

        // 4. 左列を Latinモデル で全体OCR（数字に強い）
        val leftText = recognizeTextLatin(leftColumnBitmap)
        Log.d(TAG, "Left column OCR (Latin model) complete")

        // 5. 右列を 日本語モデル で全体OCR
        val rightText = recognizeText(rightColumnBitmap)
        Log.d(TAG, "Right column OCR (Japanese model) complete")

        // 6. 左列のテキストをY座標順に抽出
        val leftLines = mutableListOf<TextWithBounds>()
        leftText?.textBlocks?.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox
                if (bounds != null) {
                    leftLines.add(
                        TextWithBounds(
                            text = line.text.trim(),
                            boundingBox = bounds,
                            centerY = bounds.centerY(),
                            centerX = bounds.centerX()
                        )
                    )
                }
            }
        }
        leftLines.sortBy { it.centerY }
        Log.d(TAG, "Left column: ${leftLines.size} lines extracted")

        // 7. 右列のテキストをY座標順に抽出
        val rightLines = mutableListOf<TextWithBounds>()
        rightText?.textBlocks?.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox
                if (bounds != null) {
                    rightLines.add(
                        TextWithBounds(
                            text = line.text.trim(),
                            boundingBox = bounds,
                            centerY = bounds.centerY(),
                            centerX = bounds.centerX()
                        )
                    )
                }
            }
        }
        rightLines.sortBy { it.centerY }
        Log.d(TAG, "Right column: ${rightLines.size} lines extracted")

        // 8. Y座標でマッチング（同じ行のテキストを対応付け）
        val rows = mutableListOf<BBlockRow>()
        val rowHeightThreshold = 40 // 40px以内なら同じ行とみなす

        // 左列の各行に対して、対応する右列の行を探す
        var rowIndex = 0
        val usedRightIndices = mutableSetOf<Int>()

        for (leftLine in leftLines) {
            // 同じY座標付近の右列テキストを探す
            var bestRightLine: TextWithBounds? = null
            var bestDistance = Int.MAX_VALUE

            for ((rightIndex, rightLine) in rightLines.withIndex()) {
                if (usedRightIndices.contains(rightIndex)) continue

                val distance = Math.abs(leftLine.centerY - rightLine.centerY)
                if (distance <= rowHeightThreshold && distance < bestDistance) {
                    bestDistance = distance
                    bestRightLine = rightLine
                }
            }

            // 対応する右列の行が見つかった場合、そのインデックスを使用済みとしてマーク
            if (bestRightLine != null) {
                usedRightIndices.add(rightLines.indexOf(bestRightLine))
            }

            // 後処理を適用
            val processedDate = if (blockType == ImageProcessor.BlockType.B_BLOCK) {
                ImageProcessor.postprocessDate(leftLine.text)
            } else {
                leftLine.text
            }

            val processedProduct = if (blockType == ImageProcessor.BlockType.B_BLOCK) {
                ImageProcessor.postprocessProductName(bestRightLine?.text ?: "")
            } else {
                bestRightLine?.text ?: ""
            }

            // 日付の妥当性チェック
            val isDateValid = validateDate(processedDate)

            rows.add(
                BBlockRow(
                    rowIndex = rowIndex,
                    date = processedDate,
                    productName = processedProduct,
                    dateWithBounds = leftLine.copy(text = processedDate),
                    productNameWithBounds = bestRightLine?.copy(text = processedProduct),
                    isDateValid = isDateValid
                )
            )

            Log.d(TAG, "Row $rowIndex: Date='$processedDate' (valid=$isDateValid), Product='$processedProduct' (Y distance=$bestDistance)")
            rowIndex++
        }

        // クリーンアップ
        leftColumnBitmap.recycle()
        rightColumnBitmap.recycle()

        Log.d(TAG, "Matched ${rows.size} rows")
        return rows
    }

    /**
     * 日付の妥当性チェック（YY/MM/DD形式）
     * YY = 06または07（令和6年または7年）、MM = 01-12、DD = 01-31
     */
    private fun validateDate(dateStr: String): Boolean {
        // YY/MM/DD形式をパース
        val parts = dateStr.split("/")
        if (parts.size != 3) {
            return false  // 形式が正しくない
        }

        val yy = parts[0].toIntOrNull()
        val mm = parts[1].toIntOrNull()
        val dd = parts[2].toIntOrNull()

        // 各値が妥当な範囲内かチェック
        return when {
            yy == null || mm == null || dd == null -> false
            yy !in 6..7 -> false  // YYは06（6）または07（7）
            mm !in 1..12 -> false  // MMは1-12
            dd !in 1..31 -> false  // DDは1-31
            else -> true
        }
    }

    /**
     * テキストと座標情報
     */
    data class TextWithBounds(
        val text: String,
        val boundingBox: android.graphics.Rect?,
        val centerY: Int,
        val centerX: Int
    )

    /**
     * Bブロックの結果をパース
     */
    data class BBlockRow(
        val rowIndex: Int,
        val date: String,
        val productName: String,
        val dateWithBounds: TextWithBounds? = null,
        val productNameWithBounds: TextWithBounds? = null,
        val isDateValid: Boolean = true  // 日付の妥当性
    )

    fun parseBBlockResult(result: BlockOCRResult): List<BBlockRow> {
        require(result.blockType == ImageProcessor.BlockType.B_BLOCK) {
            "Invalid block type. Expected B_BLOCK"
        }

        return result.cells.mapIndexed { rowIndex, rowCells ->
            val date = rowCells.getOrNull(0)?.text ?: ""
            val productName = rowCells.getOrNull(1)?.text ?: ""
            BBlockRow(rowIndex, date, productName)
        }
    }

    /**
     * Cブロックの結果をパース
     */
    data class CBlockRow(
        val rowIndex: Int,
        val amount: String,
        val categoryTotal: String
    )

    fun parseCBlockResult(result: BlockOCRResult): List<CBlockRow> {
        require(result.blockType == ImageProcessor.BlockType.C_BLOCK) {
            "Invalid block type. Expected C_BLOCK"
        }

        return result.cells.mapIndexed { rowIndex, rowCells ->
            val amount = rowCells.getOrNull(0)?.text ?: ""
            val categoryTotal = rowCells.getOrNull(1)?.text ?: ""
            CBlockRow(rowIndex, amount, categoryTotal)
        }
    }

    /**
     * Cブロック全体をOCR（列ごと2回OCR方式）
     * 1. 列区切り線を検出
     * 2. 左列（税込金額）をクロップ → Latinモデルで全体をOCR
     * 3. Y座標でマッチング（Bブロックと同じ行に対応）
     */
    suspend fun recognizeWholeCBlock(
        blockBitmap: Bitmap,
        blockType: ImageProcessor.BlockType,
        useUpscaling: Boolean = false
    ): List<CBlockRow> {
        val scalingMode = if (useUpscaling) "4x upscaling" else "1x (no scaling)"
        Log.d(TAG, "recognizeWholeCBlock called with $scalingMode")
        Log.d(TAG, "Block size: ${blockBitmap.width}x${blockBitmap.height}")

        require(blockType == ImageProcessor.BlockType.C_BLOCK) {
            "Invalid block type. Expected C_BLOCK, got $blockType"
        }

        // ハイブリッド方式の列区切り検出
        val separatorX = ImageProcessor.detectColumnSeparator(blockBitmap, blockType)
        Log.d(TAG, "Column separator detected at X=$separatorX")

        // 左列（税込金額）をクロップ
        val leftColumnBitmap = Bitmap.createBitmap(
            blockBitmap,
            0,
            0,
            separatorX.coerceAtMost(blockBitmap.width),
            blockBitmap.height
        )

        // オプション：4倍拡大
        val leftColumnForOcr = if (useUpscaling) {
            Bitmap.createScaledBitmap(
                leftColumnBitmap,
                leftColumnBitmap.width * 4,
                leftColumnBitmap.height * 4,
                true
            )
        } else {
            leftColumnBitmap
        }

        Log.d(TAG, "Left column (amount) size: ${leftColumnForOcr.width}x${leftColumnForOcr.height}")

        // Latinモデルで金額列を一括OCR
        val leftText = recognizeTextLatin(leftColumnForOcr) ?: run {
            Log.w(TAG, "OCR failed for left column (amount)")
            if (useUpscaling) leftColumnForOcr.recycle()
            leftColumnBitmap.recycle()
            return emptyList()
        }

        if (useUpscaling) leftColumnForOcr.recycle()

        Log.d(TAG, "Left column (amount) OCR completed: ${leftText.textBlocks.size} blocks")

        // 左列のテキストをY座標順に抽出
        val leftLines = mutableListOf<TextWithBounds>()
        leftText.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox
                leftLines.add(
                    TextWithBounds(
                        text = line.text,
                        boundingBox = bounds,
                        centerY = bounds?.centerY() ?: 0,
                        centerX = bounds?.centerX() ?: 0
                    )
                )
            }
        }
        leftLines.sortBy { it.centerY }

        Log.d(TAG, "Extracted ${leftLines.size} lines from left column")

        // CBlockRowのリストを作成
        val rows = mutableListOf<CBlockRow>()
        var rowIndex = 0

        for (leftLine in leftLines) {
            val processedAmount = ImageProcessor.postprocessAmount(leftLine.text)

            rows.add(
                CBlockRow(
                    rowIndex = rowIndex,
                    amount = processedAmount,
                    categoryTotal = ""  // 分類計は今回は使わない
                )
            )

            Log.d(TAG, "Row $rowIndex: Amount='$processedAmount'")
            rowIndex++
        }

        // クリーンアップ
        leftColumnBitmap.recycle()

        Log.d(TAG, "Extracted ${rows.size} rows from C block")
        return rows
    }

    /**
     * 金額文字列を数値に変換
     */
    fun parseAmount(amountStr: String): Int? {
        return try {
            amountStr
                .replace(",", "")
                .replace("−", "-")  // 全角マイナス
                .replace("ー", "-")  // 全角ダッシュ
                .toIntOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing amount: $amountStr", e)
            null
        }
    }

    /**
     * 商品名列の複数スケールOCR処理（実験的）
     *
     * 仕様書ベースの新実装:
     * 1. 商品名列を切り出し
     * 2. 複数スケール(1x/2x/3x)でOCR
     * 3. 辞書スコアリングで最良の結果を選択
     *
     * @param warpedBitmap warp後の伝票画像
     * @param rows 既存の行データ（Y座標でマッピングするために使用）
     * @param rowYCoordinates 各行の実際のY座標（centerY）
     * @param dictionary 商品名辞書
     * @return 商品名のマップ（行インデックス → 商品名）
     */
    private suspend fun extractProductNamesMultiScale(
        warpedBitmap: Bitmap,
        rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        rowYCoordinates: List<Int>,
        dictionary: List<String>
    ): Map<Int, String> {
        Log.d(TAG, "[PRODUCT-MULTISCALE] ========== Multi-Scale Product Name OCR Start ==========")

        // TODO: 実装を追加
        // 1. 商品名列ROIを取得
        // 2. 行ごとに切り出し
        // 3. 複数スケール(1x/2x/3x)でOCR
        // 4. 辞書スコアリングで最良の結果を選択

        Log.d(TAG, "[PRODUCT-MULTISCALE] ========== Multi-Scale Product Name OCR Complete ==========")
        return emptyMap()
    }

    /**
     * リソースのクリーンアップ
     */
    fun close() {
        recognizer.close()
        latinRecognizer.close()
    }

    // ============================================
    // 下に敷くタイプ用の処理（UnderlyingBaseProcessor統合）
    // ============================================

    /**
     * 数量列を特化OCRで再処理（高精度化）
     *
     * 設計思想:
     * - 固定4倍拡大を廃止 → 文字高さ正規化を使用
     * - 罫線をOCR前に物理的に消す
     * - OCR後にも形状で最終防御
     * - 行単位で処理（列全体一括はNG）
     *
     * フロー:
     * 1. 数量列ROI切り出し（行単位）
     * 2. グレースケール化
     * 3. 罫線除去（縦罫線優先）
     * 4. 文字高さ推定
     * 5. 適応的スケーリング（1.0-3.0倍、目標30px）
     * 6. Latin OCR実行
     * 7. bbox形状フィルタ
     * 8. 正規表現チェック
     *
     * @param warpedBitmap warp後の伝票画像
     * @param rows 既存の行データ（Y座標でマッピングするために使用）
     * @param rowYCoordinates 各行の実際のY座標（centerY）
     * @return 数量のマップ（行インデックス → 数量文字列）
     */
    private suspend fun extractQuantitiesFromColumn(
        warpedBitmap: Bitmap,
        rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        rowYCoordinates: List<Int>
    ): Map<Int, String> {
        Log.d(TAG, "[QTY] ========== Quantity Column Re-OCR Start ==========")

        // 1. 数量列ROI範囲を取得
        val quantityRange = UnderlyingBaseProcessor.getQuantityRange()
        val quantityX = quantityRange.first
        val quantityWidth = quantityRange.last - quantityRange.first

        Log.d(TAG, "[QTY] Quantity column ROI: X=$quantityX, Width=$quantityWidth")

        val quantityMap = mutableMapOf<Int, String>()

        // 2. 行単位で処理（列全体一括はNG）
        rows.forEachIndexed { rowIndex, row ->
            val rowY = rowYCoordinates.getOrNull(rowIndex)
            if (rowY == null || row.rowType != UnderlyingBaseProcessor.RowType.NORMAL) {
                return@forEachIndexed
            }

            try {
                // 2-1. 行のROIを切り出し（Y方向はその行のみ）
                val rowHeight = 50  // 行の高さ（推定）
                val roiY = (rowY - rowHeight / 2).coerceAtLeast(0)
                val roiHeight = rowHeight.coerceAtMost(warpedBitmap.height - roiY)

                val rowRoiBitmap = Bitmap.createBitmap(
                    warpedBitmap,
                    quantityX,
                    roiY,
                    quantityWidth.coerceAtMost(warpedBitmap.width - quantityX),
                    roiHeight
                )

                // 2-2. グレースケール化（必須）
                val grayMat = org.opencv.core.Mat()
                org.opencv.android.Utils.bitmapToMat(rowRoiBitmap, grayMat)
                val grayMatGray = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.cvtColor(grayMat, grayMatGray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
                grayMat.release()

                // 2-3. 罫線除去（OCR前・最重要）
                val cleanedMat = ImagePreprocessor.removeLines(grayMatGray, removeVertical = true, removeHorizontal = false)
                grayMatGray.release()

                // 2-4. 文字高さ推定
                val charPx = ImagePreprocessor.estimateCharHeightSimple(cleanedMat)

                // 2-5. 適応的スケーリング（固定4倍は使わない）
                val targetHeight = 30.0  // 目標文字高さ
                val scale = (targetHeight / charPx).coerceIn(1.0, 3.0)

                val scaledMat = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.resize(
                    cleanedMat,
                    scaledMat,
                    org.opencv.core.Size(0.0, 0.0),
                    scale,
                    scale,
                    org.opencv.imgproc.Imgproc.INTER_CUBIC
                )
                cleanedMat.release()

                Log.d(TAG, "[QTY] Row $rowIndex: charPx=${charPx.toInt()}, scale=${String.format("%.2f", scale)}")

                // 2-6. Latin OCR実行
                val scaledBitmap = matToBitmap(scaledMat)
                val ocrText = recognizeTextLatin(scaledBitmap)
                scaledMat.release()
                scaledBitmap.recycle()
                rowRoiBitmap.recycle()

                if (ocrText == null) {
                    Log.d(TAG, "[QTY] Row $rowIndex: OCR failed")
                    return@forEachIndexed
                }

                // 2-7. OCR結果を処理（bbox形状フィルタ + 正規表現）
                ocrText.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        val text = line.text.trim()
                        val bounds = line.boundingBox

                        if (bounds != null) {
                            // 2-7-A. bbox形状フィルタ（罫線っぽい結果を弾く）
                            val aspectRatio = bounds.width().toFloat() / bounds.height()
                            if (aspectRatio > 5.0) {
                                Log.d(TAG, "[QTY] Row $rowIndex: Rejected '$text' (横線, aspect=${String.format("%.2f", aspectRatio)})")
                                return@forEach
                            }
                            if (bounds.height() < 12) {
                                Log.d(TAG, "[QTY] Row $rowIndex: Rejected '$text' (細線, height=${bounds.height()})")
                                return@forEach
                            }

                            // 2-7-B. 正規表現チェック（1-3桁の数字のみ）
                            if (text.matches(Regex("^[0-9]{1,3}$"))) {
                                quantityMap[rowIndex] = text
                                Log.d(TAG, "[QTY] Row $rowIndex: Accepted '$text' (charPx=${charPx.toInt()}, scale=${String.format("%.2f", scale)})")
                            } else {
                                Log.d(TAG, "[QTY] Row $rowIndex: Rejected '$text' (regex mismatch)")
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "[QTY] Row $rowIndex: Error", e)
            }
        }

        Log.d(TAG, "[QTY] ========== Quantity Column Re-OCR Complete: ${quantityMap.size} quantities ==========")
        return quantityMap
    }

    /**
     * OpenCV Mat を Bitmap に変換（ヘルパー）
     */
    private fun matToBitmap(mat: org.opencv.core.Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(mat, bitmap)
        return bitmap
    }


    /**
     * ダブルOCR結果（グレー版 + 二値版）
     */
    data class DoubleOcrResult(
        val grayText: String?,
        val binaryText: String?,
        val binaryCandidateScore: Double = 0.0,  // 段階Aスコア
        val fallbackUsed: Boolean = false,       // フォールバック発動フラグ
        val fallbackSource: String? = null       // フォールバック元テキスト（ログ用）
    )

    /**
     * フォールバック発動理由
     */
    enum class FallbackReason {
        ITEM_OCR_EMPTY  // 商品名列OCRが空
    }

    /**
     * フォールバックイベント（ログ用）
     */
    data class FallbackEvent(
        val rowIndex: Int,
        val rowY: Int,
        val rawText: String,
        val reason: FallbackReason,
        val textHeight: Float = 0f,
        val boxCount: Int = 1,
        val separatedTexts: List<String> = emptyList()  // 個別TextBoxのテキスト（explicitJoin学習用）
    )

    /**
     * 商品名列を特化OCRで再処理（高精度化）
     *
     * 全体OCR（日本語モデル）は小さい文字を落としやすい
     * → 商品名列だけ切り出して、拡大 + Japanese OCR で再処理
     * → グレー版 + 二値版のダブルOCRを実行
     *
     * @param warpedBitmap warp後の伝票画像
     * @param rows 既存の行データ（Y座標でマッピングするために使用）
     * @param rowYCoordinates 各行の実際のY座標（centerY）
     * @return 商品名のマップ（行インデックス → ダブルOCR結果）
     */
    private suspend fun extractProductNamesFromColumn(
        warpedBitmap: Bitmap,
        rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        rowYCoordinates: List<Int>
    ): Map<Int, DoubleOcrResult> {
        Log.d(TAG, "[PRODUCT] ========== Product Name Column Re-OCR Start ==========")

        // 1. 商品名列ROIを取得
        val itemRange = UnderlyingBaseProcessor.getItemRange()
        val itemX = itemRange.first
        val itemWidth = itemRange.last - itemRange.first

        Log.d(TAG, "[PRODUCT] Product name column ROI: X=$itemX, Width=$itemWidth")

        // 2. 商品名列を切り出し（通常行のY座標範囲に限定、合計行を除外）
        val normalRowYRange = UnderlyingBaseProcessor.getNormalRowYRange()
        val itemY = normalRowYRange.first
        val itemHeight = normalRowYRange.last - normalRowYRange.first

        Log.d(TAG, "[PRODUCT] Y-range limited to normal rows: Y=$itemY, Height=$itemHeight (excluding total row)")

        val itemColumnBitmap = Bitmap.createBitmap(
            warpedBitmap,
            itemX,
            itemY,
            itemWidth.coerceAtMost(warpedBitmap.width - itemX),
            itemHeight.coerceAtMost(warpedBitmap.height - itemY)
        )

        // 3. 前処理: グレースケール + コントラスト調整
        val grayBitmap = ImagePreprocessor.toGray(itemColumnBitmap)
        val enhancedBitmap = ImagePreprocessor.adjustContrast(grayBitmap, 1.2f)

        // 4. Bitmap → Mat 変換
        val grayMat = org.opencv.core.Mat()
        org.opencv.android.Utils.bitmapToMat(enhancedBitmap, grayMat)
        val grayMatGray = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.cvtColor(grayMat, grayMatGray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
        grayMat.release()

        // 5. 文字高さ測定
        val charPx = ImagePreprocessor.estimateCharHeightPx(enhancedBitmap)
        Log.d(TAG, "[PRODUCT] Character height: ${charPx}px")

        // 6. 画像特徴計測（段階A: 二値OCRを走らせるか判定）
        val edgeDensity = ImagePreprocessor.calcEdgeDensity(grayMatGray)
        val blackRatio = ImagePreprocessor.calcBlackRatio(grayMatGray)
        val strokeWidthVar = ImagePreprocessor.calcStrokeWidthVariance(grayMatGray)

        // 7. 二値OCR候補スコアを計算
        val binaryCandidateScore = ImagePreprocessor.calcBinaryCandidateScore(
            charPx, edgeDensity, blackRatio, strokeWidthVar
        )

        // 8. モルフォロジーOpen（ノイズ除去）
        val openedMat = ImagePreprocessor.safeMorphOpen(grayMatGray, charPx)
        Log.d(TAG, "[PRODUCT] Applied morph open")

        // 9. 段階A判定: 二値OCRを走らせるか
        // ハード条件（最低限）: strokeWidthVarは減点要素として使用（ハード条件ではない）
        val canTryBinary = (
            charPx >= 18f &&
            blackRatio <= 0.45 &&
            edgeDensity >= 0.02
        )

        // スコア条件
        val shouldUseBinary = canTryBinary && binaryCandidateScore >= 0.5

        Log.d(TAG, "[PRODUCT] 段階A - Binary candidate: score=${String.format("%.3f", binaryCandidateScore)}, " +
            "charPx=$charPx, blackRatio=${String.format("%.3f", blackRatio)}, " +
            "edgeDensity=${String.format("%.3f", edgeDensity)}, " +
            "canTry=$canTryBinary, useBinary=$shouldUseBinary")

        // スケールファクター計算（座標逆変換用）
        val scaleFactor = ImagePreprocessor.calcScaleFactor(charPx, 32f)
        Log.d(TAG, "[PRODUCT] Scale factor: $scaleFactor")

        // 10. グレー版OCR（常に実行 = 主系）
        val grayMatForOcr = openedMat.clone()
        val grayBitmapForOcr = matToBitmap(grayMatForOcr)
        val scaledGrayBitmap = scaleForOcr(grayBitmapForOcr, charPx)
        val grayOcrText = recognizeText(scaledGrayBitmap)
        Log.d(TAG, "[PRODUCT-GRAY] OCR result: ${if (grayOcrText != null) "success" else "failed"}")

        // 11. 二値版OCR（条件付き = 補助火力）
        var binaryOcrText: com.google.mlkit.vision.text.Text? = null
        if (shouldUseBinary) {
            val binaryMat = ImagePreprocessor.safeAdaptiveThreshold(openedMat, charPx)
            val binaryBitmapForOcr = matToBitmap(binaryMat)
            val scaledBinaryBitmap = scaleForOcr(binaryBitmapForOcr, charPx)
            binaryOcrText = recognizeText(scaledBinaryBitmap)
            Log.d(TAG, "[PRODUCT-BINARY] OCR result: ${if (binaryOcrText != null) "success" else "failed"}")

            binaryMat.release()
            binaryBitmapForOcr.recycle()
            scaledBinaryBitmap.recycle()
        } else {
            Log.d(TAG, "[PRODUCT-BINARY] Skipped (段階A条件を満たさず)")
        }

        // クリーンアップ
        itemColumnBitmap.recycle()
        grayBitmap.recycle()
        enhancedBitmap.recycle()
        grayMatGray.release()
        openedMat.release()
        grayMatForOcr.release()
        grayBitmapForOcr.recycle()
        scaledGrayBitmap.recycle()

        if (grayOcrText == null && binaryOcrText == null) {
            Log.w(TAG, "[PRODUCT] Both gray and binary OCR failed")
            return emptyMap()
        }

        // 9. グレー版のTextBox抽出
        val grayProductBoxes = mutableListOf<UnderlyingBaseProcessor.TextBox>()
        grayOcrText?.textBlocks?.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox
                if (bounds != null) {
                    val scaledBounds = android.graphics.Rect(
                        (bounds.left / scaleFactor).toInt() + itemX,
                        (bounds.top / scaleFactor).toInt() + itemY,
                        (bounds.right / scaleFactor).toInt() + itemX,
                        (bounds.bottom / scaleFactor).toInt() + itemY
                    )
                    grayProductBoxes.add(
                        UnderlyingBaseProcessor.TextBox(
                            text = line.text.trim(),
                            bounds = scaledBounds,
                            centerX = scaledBounds.centerX(),
                            centerY = scaledBounds.centerY()
                        )
                    )
                }
            }
        }

        // 10. 二値版のTextBox抽出
        val binaryProductBoxes = mutableListOf<UnderlyingBaseProcessor.TextBox>()
        binaryOcrText?.textBlocks?.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox
                if (bounds != null) {
                    val scaledBounds = android.graphics.Rect(
                        (bounds.left / scaleFactor).toInt() + itemX,
                        (bounds.top / scaleFactor).toInt() + itemY,
                        (bounds.right / scaleFactor).toInt() + itemX,
                        (bounds.bottom / scaleFactor).toInt() + itemY
                    )
                    binaryProductBoxes.add(
                        UnderlyingBaseProcessor.TextBox(
                            text = line.text.trim(),
                            bounds = scaledBounds,
                            centerX = scaledBounds.centerX(),
                            centerY = scaledBounds.centerY()
                        )
                    )
                }
            }
        }

        // 11. 両方のTextBoxをソート
        grayProductBoxes.sortBy { it.centerY }
        binaryProductBoxes.sortBy { it.centerY }

        // 12. 行にマッピング（グレー版）
        val grayProductMap = mutableMapOf<Int, String>()
        val yThreshold = 15

        rows.forEachIndexed { rowIndex, row ->
            if (row.rowType != UnderlyingBaseProcessor.RowType.NORMAL) return@forEachIndexed
            val rowY = rowYCoordinates.getOrNull(rowIndex) ?: return@forEachIndexed

            val matchingBoxes = grayProductBoxes.filter { box ->
                kotlin.math.abs(box.centerY - rowY) <= yThreshold
            }

            if (matchingBoxes.isNotEmpty()) {
                val combinedText = matchingBoxes.sortedBy { it.centerX }.joinToString("") { it.text }
                val cleanedText = UnderlyingBaseProcessor.cleanItemName(combinedText)
                grayProductMap[rowIndex] = cleanedText
            }
        }

        // 13. 行にマッピング（二値版）
        val binaryProductMap = mutableMapOf<Int, String>()

        rows.forEachIndexed { rowIndex, row ->
            if (row.rowType != UnderlyingBaseProcessor.RowType.NORMAL) return@forEachIndexed
            val rowY = rowYCoordinates.getOrNull(rowIndex) ?: return@forEachIndexed

            val matchingBoxes = binaryProductBoxes.filter { box ->
                kotlin.math.abs(box.centerY - rowY) <= yThreshold
            }

            if (matchingBoxes.isNotEmpty()) {
                val combinedText = matchingBoxes.sortedBy { it.centerX }.joinToString("") { it.text }
                val cleanedText = UnderlyingBaseProcessor.cleanItemName(combinedText)
                binaryProductMap[rowIndex] = cleanedText
            }
        }

        // 14. ダブルOCR結果を統合（binaryCandidateScoreを含む）
        val doubleOcrMap = mutableMapOf<Int, DoubleOcrResult>()
        val allIndices = (grayProductMap.keys + binaryProductMap.keys).toSet()

        allIndices.forEach { index ->
            val grayText = grayProductMap[index]
            val binaryText = binaryProductMap[index]

            if (grayText != null || binaryText != null) {
                doubleOcrMap[index] = DoubleOcrResult(
                    grayText = grayText,
                    binaryText = binaryText,
                    binaryCandidateScore = binaryCandidateScore  // 段階Aスコアを保存
                )
                Log.d(TAG, "[PRODUCT] Row $index: gray='$grayText', binary='$binaryText'")
            }
        }

        Log.d(TAG, "[PRODUCT] ========== Product Name Column Re-OCR Complete: ${doubleOcrMap.size} products ==========")
        return doubleOcrMap
    }

    /**
     * ML Kit Text結果を TextBox リストに変換
     *
     * Line単位で処理し、日付（先頭6桁）を分離
     *
     * @param text ML Kit OCR結果
     * @return TextBoxのリスト
     */
    private fun convertToTextBoxes(text: Text): List<UnderlyingBaseProcessor.TextBox> {
        val textBoxes = mutableListOf<UnderlyingBaseProcessor.TextBox>()

        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox
                if (bounds != null) {
                    // Line全体をTextBoxとして追加（商品名などに使用）
                    textBoxes.add(
                        UnderlyingBaseProcessor.TextBox(
                            text = line.text,
                            bounds = bounds,
                            centerX = bounds.centerX(),
                            centerY = bounds.centerY()
                        )
                    )

                    // 先頭6桁が数字（正規化後）なら、日付として別途追加
                    val normalized = UnderlyingBaseProcessor.normalizeToDigits(line.text).take(6)

                    if (normalized.length == 6) {
                        // 日付部分のbbox（左端から固定幅）
                        // 日付は6桁、約15文字幅相当（実測ベース）
                        val avgCharWidth = bounds.width() / line.text.length.coerceAtLeast(1)
                        val dateWidth = (avgCharWidth * 6).toInt()
                        val dateBounds = android.graphics.Rect(
                            bounds.left,
                            bounds.top,
                            (bounds.left + dateWidth).coerceAtMost(bounds.right),
                            bounds.bottom
                        )

                        // centerXを左端寄りに配置（DATE列の範囲内に収める）
                        val dateCenterX = bounds.left + (dateWidth / 2)

                        textBoxes.add(
                            UnderlyingBaseProcessor.TextBox(
                                text = normalized,  // 正規化済みの6桁数字
                                bounds = dateBounds,
                                centerX = dateCenterX,
                                centerY = dateBounds.centerY()
                            )
                        )
                        Log.d(TAG, "  Date extracted: '$normalized' at centerX=$dateCenterX, Y=${dateBounds.centerY()}")
                    }
                }
            }
        }

        Log.d(TAG, "Converted ${textBoxes.size} text boxes from ML Kit result")
        return textBoxes
    }

    /**
     * 下に敷くタイプの台紙でOCR処理
     *
     * 正しい処理フロー:
     * 1. ArUco検出 & warp（呼び出し元で実行済み）
     * 2. ML Kit OCR（日本語）
     * 3. TextBox変換（bbox + text）
     * 4. ノイズ除去（空・記号・極小bbox）
     * 5. 行クラスタリング（Y、閾値15px）
     * 6. 行タイプ判定（通常 / 小計 / 月合計 / 空白）
     * 7. 各行を処理
     *    ├ 列判定（X + 安全px範囲）
     *    ├ 正規表現確定
     *    └ 行データ生成
     * 8. 小計・月合計の意味付け（結果から抽出）
     * 9. UI表示（呼び出し元）
     *
     * @param warpedBitmap warp後の伝票画像
     * @return ReceiptRowのリスト
     */
    suspend fun processUnderlayingBase(
        warpedBitmap: Bitmap
    ): ProcessUnderlayingBaseResult {
        Log.d(TAG, "[UNDERLAY] ========== Processing Start ==========")
        Log.d(TAG, "[UNDERLAY] Image size: ${warpedBitmap.width}x${warpedBitmap.height}")

        // 0. 列範囲を初期化（透視変換で計算されたmm->px比率を使用）
        val mmToPixelRatio = ImageProcessor.getMmToPixelRatio()
        if (mmToPixelRatio <= 0.0) {
            Log.e(TAG, "[UNDERLAY] Invalid mmToPixelRatio: $mmToPixelRatio")
            return ProcessUnderlayingBaseResult(
                rows = emptyList(),
                subtotals = emptyList(),
                productNameDoubleOcrMap = emptyMap()
            )
        }
        UnderlyingBaseProcessor.initializeColumnRanges(mmToPixelRatio)

        // 1. ML Kit OCR（日本語モデル）
        val text = recognizeText(warpedBitmap) ?: run {
            Log.w(TAG, "[UNDERLAY] OCR failed")
            return ProcessUnderlayingBaseResult(
                rows = emptyList(),
                subtotals = emptyList(),
                productNameDoubleOcrMap = emptyMap()
            )
        }

        Log.d(TAG, "[UNDERLAY] Step 2: OCR completed (${text.textBlocks.size} blocks)")

        // 3. TextBoxに変換
        val textBoxes = convertToTextBoxes(text)
        Log.d(TAG, "[UNDERLAY] Step 3: Converted to ${textBoxes.size} text boxes")

        // 4. ノイズ除去（空、記号、極小bbox）
        val filteredBoxes = UnderlyingBaseProcessor.filterNoise(textBoxes)
        Log.d(TAG, "[UNDERLAY] Step 4: Noise filtering (${textBoxes.size} → ${filteredBoxes.size})")

        // 4.5. フォールバック用: 商品名列範囲内のTextBoxを抽出
        val itemRange = UnderlyingBaseProcessor.getItemRange()
        val fallbackCandidateBoxes = filteredBoxes.filter { box ->
            // TextBoxのcenterXが商品名列範囲内にあるものを抽出
            box.centerX in itemRange.first..itemRange.last
        }
        Log.d(TAG, "[UNDERLAY] Step 4.5: Fallback candidates extracted (${fallbackCandidateBoxes.size} boxes in ITEM column)")

        // 5. 行クラスタリング（Y座標、閾値15px）
        val rows = UnderlyingBaseProcessor.clusterRows(filteredBoxes)
        Log.d(TAG, "[UNDERLAY] Step 5: Row clustering (${rows.size} rows)")

        // 6-7. 各行を処理（行タイプ判定 → 列判定 → 行データ生成）
        // Y座標フィルタリングは行タイプ判定後に実施（合計行を取りこぼさないため）
        // また、各行のY座標も記録（数量列OCRのマッピングに使用）
        val rowYCoordinates = mutableListOf<Int>()
        val receiptRows = rows.mapIndexed { index, rowBoxes ->
            Log.d(TAG, "[UNDERLAY] Processing row $index (${rowBoxes.size} boxes):")

            // 行のY座標を計算（行内の全TextBoxのY座標平均）
            val avgY = rowBoxes.map { it.centerY }.average().toInt()
            rowYCoordinates.add(avgY)

            // 6. 行タイプ判定
            val rowType = UnderlyingBaseProcessor.detectRowType(rowBoxes)

            // 7. 行データ生成
            val row = UnderlyingBaseProcessor.processRow(rowBoxes, rowType)

            Log.d(TAG, "[UNDERLAY]   → Type: $rowType, Y=$avgY, Data: ${row.rawText}")
            row
        }

        Log.d(TAG, "[UNDERLAY] Step 7: Processed ${receiptRows.size} rows")

        // 7.5. 行タイプに応じたY座標フィルタリング（ヘッダー・フッターの不要な行を除外）
        val normalRange = UnderlyingBaseProcessor.getNormalRowYRange()
        val totalRange = UnderlyingBaseProcessor.getMonthlyTotalYRange()

        val filteredRowsWithIndices = receiptRows.mapIndexedNotNull { index, row ->
            val y = rowYCoordinates[index]

            when (row.rowType) {
                UnderlyingBaseProcessor.RowType.NORMAL -> {
                    if (y in normalRange.first..normalRange.last) {
                        index to row
                    } else {
                        Log.d(TAG, "[UNDERLAY]   ✗ Filtered: NORMAL row at Y=$y (outside ${normalRange.first}-${normalRange.last})")
                        null
                    }
                }
                UnderlyingBaseProcessor.RowType.SUBTOTAL -> {
                    if (y in normalRange.first..normalRange.last) {
                        index to row
                    } else {
                        Log.d(TAG, "[UNDERLAY]   ✗ Filtered: SUBTOTAL row at Y=$y (outside ${normalRange.first}-${normalRange.last})")
                        null
                    }
                }
                UnderlyingBaseProcessor.RowType.MONTHLY_TOTAL -> {
                    if (y in totalRange.first..totalRange.last) {
                        index to row
                    } else {
                        Log.d(TAG, "[UNDERLAY]   ✗ Filtered: MONTHLY_TOTAL row at Y=$y (outside ${totalRange.first}-${totalRange.last})")
                        null
                    }
                }
                UnderlyingBaseProcessor.RowType.EMPTY -> {
                    Log.d(TAG, "[UNDERLAY]   ✗ Filtered: EMPTY row at Y=$y")
                    null
                }
            }
        }

        // 元のインデックスから新しいインデックスへのマッピング（数量列・商品名列OCRで使用）
        val oldIndexToNewIndex = filteredRowsWithIndices.mapIndexed { newIndex, (oldIndex, _) ->
            oldIndex to newIndex
        }.toMap()

        val filteredRows = filteredRowsWithIndices.map { it.second }
        val filteredYCoordinates = filteredRowsWithIndices.map { rowYCoordinates[it.first] }

        Log.d(TAG, "[UNDERLAY] Step 7.5: Y-coordinate filtering (${receiptRows.size} → ${filteredRows.size} rows)")

        // 8. 数量列特化OCR処理（Latin OCR + 4倍拡大）
        val quantityMap = extractQuantitiesFromColumn(warpedBitmap, filteredRows, filteredYCoordinates)
        Log.d(TAG, "[UNDERLAY] Step 8: Quantity re-OCR completed (${quantityMap.size} quantities)")

        // 8.5. 商品名列特化OCR処理（Japanese OCR + ダブルOCR）
        val productNameDoubleOcrMapRaw = extractProductNamesFromColumn(warpedBitmap, filteredRows, filteredYCoordinates)
        Log.d(TAG, "[UNDERLAY] Step 8.5: Product name re-OCR completed (${productNameDoubleOcrMapRaw.size} product names)")

        // 8.6. フォールバック処理: 商品名列OCRが空の行に対して、フルOCRのTextBoxからテキストを補完
        val yThreshold = 15
        val productNameDoubleOcrMap = productNameDoubleOcrMapRaw.toMutableMap()
        val fallbackEvents = mutableListOf<FallbackEvent>()

        filteredRows.forEachIndexed { rowIndex, row ->
            if (row.rowType != UnderlyingBaseProcessor.RowType.NORMAL) return@forEachIndexed

            val existingResult = productNameDoubleOcrMapRaw[rowIndex]
            val hasValidResult = existingResult != null &&
                (!existingResult.grayText.isNullOrBlank() || !existingResult.binaryText.isNullOrBlank())

            if (!hasValidResult) {
                // 商品名列OCRが空の場合、フルOCRのTextBoxからフォールバック
                val rowY = filteredYCoordinates.getOrNull(rowIndex) ?: return@forEachIndexed

                // 同じY座標にあるフォールバック候補TextBoxを取得
                val matchingFallbackBoxes = fallbackCandidateBoxes.filter { box ->
                    kotlin.math.abs(box.centerY - rowY) <= yThreshold
                }

                if (matchingFallbackBoxes.isNotEmpty()) {
                    // X座標でソートして個別テキストを取得
                    val sortedBoxes = matchingFallbackBoxes.sortedBy { it.centerX }
                    val separatedTexts = sortedBoxes.map { it.text }
                    val fallbackText = separatedTexts.joinToString("")
                    val cleanedFallbackText = UnderlyingBaseProcessor.cleanItemName(fallbackText)

                    if (cleanedFallbackText.isNotBlank()) {
                        // フォールバック結果を追加
                        productNameDoubleOcrMap[rowIndex] = DoubleOcrResult(
                            grayText = cleanedFallbackText,
                            binaryText = null,
                            binaryCandidateScore = 0.0,
                            fallbackUsed = true,
                            fallbackSource = fallbackText  // クリーン前のテキストをログ用に保存
                        )

                        // フォールバックイベントを記録
                        val avgTextHeight = sortedBoxes
                            .map { it.bounds.height().toFloat() }
                            .average()
                            .toFloat()

                        fallbackEvents.add(
                            FallbackEvent(
                                rowIndex = rowIndex,
                                rowY = rowY,
                                rawText = fallbackText,
                                reason = FallbackReason.ITEM_OCR_EMPTY,
                                textHeight = avgTextHeight,
                                boxCount = sortedBoxes.size,
                                separatedTexts = separatedTexts  // 個別テキストを保存（explicitJoin学習用）
                            )
                        )

                        Log.d(TAG, "[UNDERLAY] 🔄 Fallback applied: Row $rowIndex (Y=$rowY): '$cleanedFallbackText' " +
                            "(from ${sortedBoxes.size} full-OCR boxes: $separatedTexts, avgHeight=${avgTextHeight}px)")
                    }
                }
            }
        }

        if (fallbackEvents.isNotEmpty()) {
            Log.d(TAG, "[UNDERLAY] Step 8.6: Fallback applied to ${fallbackEvents.size} rows")
        }

        // 9. 数量・商品名を上書き & 返品処理
        val updatedRows = filteredRows.mapIndexed { index, row ->
            // 数量列OCRで取得した値で上書き
            val newQuantity = quantityMap[index] ?: row.quantity

            // 商品名列OCRで取得した値で上書き（仮: グレー版を優先、最終選択はCameraViewModel側）
            val doubleOcrResult = productNameDoubleOcrMap[index]
            val newItemName = doubleOcrResult?.grayText ?: doubleOcrResult?.binaryText ?: row.itemName

            // 返品処理（商品名に「返品」が含まれていれば数量を負にする）
            val finalQuantity = if (newItemName?.contains("返品") == true && newQuantity != null) {
                val qty = newQuantity.toIntOrNull()
                if (qty != null && qty > 0) {
                    (-qty).toString()
                } else {
                    newQuantity
                }
            } else {
                newQuantity
            }

            // ログ出力
            if (quantityMap.containsKey(index)) {
                Log.d(TAG, "[UNDERLAY]   Row $index: quantity updated: '${row.quantity}' → '$finalQuantity'" +
                        (if (newItemName?.contains("返品") == true) " (返品処理)" else ""))
            }
            if (productNameDoubleOcrMap.containsKey(index)) {
                Log.d(TAG, "[UNDERLAY]   Row $index: itemName updated: '${row.itemName}' → '$newItemName'")
            }

            // 新しいRowを作成
            row.copy(quantity = finalQuantity, itemName = newItemName)
        }

        // 10. 小計・月合計の抽出（結果から）
        val subtotals = updatedRows
            .filter { it.rowType == UnderlyingBaseProcessor.RowType.SUBTOTAL }
            .mapNotNull { row ->
                row.categorySum?.let { value ->
                    UnderlyingBaseProcessor.SubtotalData(
                        value = value,
                        centerX = 0,  // 行から抽出した場合は座標不要
                        centerY = 0
                    )
                }
            }

        Log.d(TAG, "[UNDERLAY] Step 10: Extracted ${subtotals.size} subtotals")

        // 11. カテゴリ判定（小計行から逆算）
        val rowsWithCategories = UnderlyingBaseProcessor.assignCategories(updatedRows)
        Log.d(TAG, "[UNDERLAY] Step 11: Assigned categories to ${rowsWithCategories.size} rows")

        // 12. 辞書ベース商品名補正
        Log.d(TAG, "[UNDERLAY] Step 12: Dictionary-based product name correction...")
        // TODO: ここでProductNameCorrectorを使用するには、ContextとDatabaseが必要
        // 現在はスキップし、後でCameraViewModelで呼び出す

        Log.d(TAG, "[UNDERLAY] ========== Processing Complete ==========")

        return ProcessUnderlayingBaseResult(
            rows = updatedRows,
            subtotals = subtotals,
            productNameDoubleOcrMap = productNameDoubleOcrMap,
            fallbackEvents = fallbackEvents
        )
    }

    /**
     * 下に敷くタイプの処理結果
     */
    data class ProcessUnderlayingBaseResult(
        val rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        val subtotals: List<UnderlyingBaseProcessor.SubtotalData>,
        val productNameDoubleOcrMap: Map<Int, DoubleOcrResult>,  // 行インデックス → ダブルOCR結果
        val fallbackEvents: List<FallbackEvent> = emptyList()    // フォールバック発動イベント
    )

    /**
     * OCR用スケーリング
     *
     * 文字高さに応じて適応的にスケーリング
     *
     * @param bitmap 入力画像
     * @param charPx 文字高さ
     * @return スケーリング後の画像
     */
    private fun scaleForOcr(bitmap: Bitmap, charPx: Float): Bitmap {
        val scaleFactor = ImagePreprocessor.calcScaleFactor(charPx, 32f)

        if (scaleFactor <= 1f) {
            return bitmap
        }

        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scaleFactor).toInt(),
            (bitmap.height * scaleFactor).toInt(),
            true
        )
    }
}
