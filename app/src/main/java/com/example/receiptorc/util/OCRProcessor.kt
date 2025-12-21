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
     * ML Kit Text結果を TextBox リストに変換
     *
     * @param text ML Kit OCR結果
     * @return TextBoxのリスト
     */
    private fun convertToTextBoxes(text: Text): List<UnderlyingBaseProcessor.TextBox> {
        val textBoxes = mutableListOf<UnderlyingBaseProcessor.TextBox>()

        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    val bounds = element.boundingBox
                    if (bounds != null) {
                        textBoxes.add(
                            UnderlyingBaseProcessor.TextBox(
                                text = element.text,
                                bounds = bounds,
                                centerX = bounds.centerX(),
                                centerY = bounds.centerY()
                            )
                        )
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

        // 1. ML Kit OCR（日本語モデル）
        val text = recognizeText(warpedBitmap) ?: run {
            Log.w(TAG, "[UNDERLAY] OCR failed")
            return ProcessUnderlayingBaseResult(emptyList(), emptyList())
        }

        Log.d(TAG, "[UNDERLAY] Step 2: OCR completed (${text.textBlocks.size} blocks)")

        // 3. TextBoxに変換
        val textBoxes = convertToTextBoxes(text)
        Log.d(TAG, "[UNDERLAY] Step 3: Converted to ${textBoxes.size} text boxes")

        // 4. ノイズ除去（空、記号、極小bbox）
        val filteredBoxes = UnderlyingBaseProcessor.filterNoise(textBoxes)
        Log.d(TAG, "[UNDERLAY] Step 4: Noise filtering (${textBoxes.size} → ${filteredBoxes.size})")

        // 5. 行クラスタリング（Y座標、閾値15px）
        val rows = UnderlyingBaseProcessor.clusterRows(filteredBoxes)
        Log.d(TAG, "[UNDERLAY] Step 5: Row clustering (${rows.size} rows)")

        // 6-7. 各行を処理（行タイプ判定 → 列判定 → 行データ生成）
        val receiptRows = rows.mapIndexed { index, rowBoxes ->
            Log.d(TAG, "[UNDERLAY] Processing row $index (${rowBoxes.size} boxes):")

            // 6. 行タイプ判定
            val rowType = UnderlyingBaseProcessor.detectRowType(rowBoxes)

            // 7. 行データ生成
            val row = UnderlyingBaseProcessor.processRow(rowBoxes, rowType)

            Log.d(TAG, "[UNDERLAY]   → Type: $rowType, Data: ${row.rawText}")
            row
        }

        Log.d(TAG, "[UNDERLAY] Step 7: Processed ${receiptRows.size} rows")

        // 8. 小計・月合計の抽出（結果から）
        val subtotals = receiptRows
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

        Log.d(TAG, "[UNDERLAY] Step 8: Extracted ${subtotals.size} subtotals")
        Log.d(TAG, "[UNDERLAY] ========== Processing Complete ==========")

        return ProcessUnderlayingBaseResult(receiptRows, subtotals)
    }

    /**
     * 下に敷くタイプの処理結果
     */
    data class ProcessUnderlayingBaseResult(
        val rows: List<UnderlyingBaseProcessor.ReceiptRow>,
        val subtotals: List<UnderlyingBaseProcessor.SubtotalData>
    )
}
