package com.example.greenframeocr.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class GeneralItemGroup(
    val canonicalKey: String,
    val itemName: String,
    val count: Int,
    val totalPrice: Int,
    // グループのデフォルト科目（general_item_masterから）。個別上書きの有無はここには出ない
    val yayoiAccountId: Long?
)

data class ReceiptItemPreview(
    val receiptId: Long,
    val itemNamesPreview: String,
    val itemCount: Int
)

@Dao
interface GeneralReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: GeneralReceipt): Long

    @Update
    suspend fun updateReceipt(receipt: GeneralReceipt)

    @Delete
    suspend fun deleteReceipt(receipt: GeneralReceipt)

    @Query("SELECT * FROM general_receipts ORDER BY date DESC, createdAt DESC")
    fun getAllReceipts(): Flow<List<GeneralReceipt>>

    @Query("SELECT * FROM general_receipts ORDER BY date DESC, createdAt DESC")
    suspend fun getAllReceiptsOnce(): List<GeneralReceipt>

    @Query("SELECT * FROM general_receipts WHERE id = :id")
    suspend fun getReceiptById(id: Long): GeneralReceipt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<GeneralReceiptItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: GeneralReceiptItem): Long

    @Update
    suspend fun updateItem(item: GeneralReceiptItem)

    @Delete
    suspend fun deleteItem(item: GeneralReceiptItem)

    @Query("SELECT * FROM general_receipt_items WHERE receiptId = :receiptId ORDER BY id ASC")
    fun getItemsByReceiptId(receiptId: Long): Flow<List<GeneralReceiptItem>>

    @Query("SELECT * FROM general_receipt_items WHERE receiptId = :receiptId ORDER BY id ASC")
    suspend fun getItemsByReceiptIdOnce(receiptId: Long): List<GeneralReceiptItem>

    @Query("DELETE FROM general_receipt_items WHERE receiptId = :receiptId")
    suspend fun deleteItemsByReceiptId(receiptId: Long)

    // canonicalKeyで正規化してグルーピング（スペース・全角半角のブレを吸収）。
    // 代表表記は同一グループ内で最後に追加された行のitemNameを使う。
    // グループのデフォルト科目はgeneral_item_masterから引く（個別上書きはここには出ない）
    @Query("""
        SELECT g.canonicalKey,
               (SELECT itemName FROM general_receipt_items i2
                WHERE i2.canonicalKey = g.canonicalKey AND i2.itemName != '' AND i2.isExcluded = 0
                ORDER BY i2.id DESC LIMIT 1) as itemName,
               COUNT(*) as count,
               SUM(g.price) as totalPrice,
               m.yayoiAccountId as yayoiAccountId
        FROM general_receipt_items g
        LEFT JOIN general_item_master m ON m.canonicalKey = g.canonicalKey
        WHERE g.itemName != '' AND g.isExcluded = 0
        GROUP BY g.canonicalKey
        ORDER BY count DESC, itemName ASC
    """)
    fun getItemGroups(): Flow<List<GeneralItemGroup>>

    // 品目別マッチングでグループを展開したときの個別明細一覧
    @Query("""
        SELECT * FROM general_receipt_items
        WHERE canonicalKey = :canonicalKey AND itemName != '' AND isExcluded = 0
        ORDER BY id DESC
    """)
    fun getItemsByCanonicalKey(canonicalKey: String): Flow<List<GeneralReceiptItem>>

    // 一覧カードの商品名プレビュー用（レシートIDごとに商品名を登録順で連結・経費対象件数を集計）
    @Query("""
        SELECT receiptId, GROUP_CONCAT(itemName, '、') as itemNamesPreview, COUNT(*) as itemCount
        FROM (
            SELECT receiptId, itemName
            FROM general_receipt_items
            WHERE itemName != '' AND isExcluded = 0
            ORDER BY id ASC
        )
        GROUP BY receiptId
    """)
    fun getItemNamePreviews(): Flow<List<ReceiptItemPreview>>

    // 個別明細1件だけを上書き（グループのデフォルトから個別に変更する／nullで解除して
    // デフォルトに戻す）
    @Query("UPDATE general_receipt_items SET yayoiAccountId = :accountId WHERE id = :itemId")
    suspend fun updateAccountForItem(itemId: Long, accountId: Long?)

    // グループのデフォルト科目を変更した際、そのグループ内の個別上書きを全解除する
    // （預金摘要集約リストの「グループ保存時は全件リセット」と同じ挙動）
    @Query("UPDATE general_receipt_items SET yayoiAccountId = NULL WHERE canonicalKey = :canonicalKey")
    suspend fun clearOverridesForGroup(canonicalKey: String)

    // 類似グループ統合：OCR誤読等でcanonicalKeyが完全一致しなかった別グループを
    // 1つのグループに付け替える（個別上書きの値はそのまま持ち越す）
    @Query("UPDATE general_receipt_items SET canonicalKey = :targetKey WHERE canonicalKey = :sourceKey")
    suspend fun reassignCanonicalKey(sourceKey: String, targetKey: String)

    // グループ一括リネーム：レジ番号等のノイズを除いた品目名にグループ内の全明細を書き換える。
    // canonicalKeyも新しい品目名から再計算した値に合わせて更新する
    @Query("UPDATE general_receipt_items SET itemName = :newName, canonicalKey = :newCanonicalKey WHERE canonicalKey = :oldCanonicalKey")
    suspend fun renameGroupItems(oldCanonicalKey: String, newCanonicalKey: String, newName: String)

    @Query("UPDATE general_receipts SET storeName = :storeName WHERE registrationNumber = :registrationNumber")
    suspend fun updateStoreNameByRegistrationNumber(registrationNumber: String, storeName: String)

    // 相手科目（貸方勘定科目）の個別上書き。accountId=nullでReceiptPaymentMethodRuleでの自動判定に戻す
    @Query("UPDATE general_receipts SET paymentAccountOverride = :accountId WHERE id = :receiptId")
    suspend fun updatePaymentAccountOverride(receiptId: Long, accountId: Long?)

    // 登録番号未登録の発行者名を一括リネーム（登録番号・店舗一覧の未登録発行者編集用）
    @Query("UPDATE general_receipts SET storeName = :newName WHERE storeName = :oldName")
    suspend fun updateStoreNameByOldName(oldName: String, newName: String)

    @Query("""
        SELECT * FROM general_receipt_items
        WHERE (:from IS NULL OR (SELECT date FROM general_receipts WHERE id = receiptId) >= :from)
        AND   (:to   IS NULL OR (SELECT date FROM general_receipts WHERE id = receiptId) <= :to)
        ORDER BY (SELECT date FROM general_receipts WHERE id = receiptId) ASC, id ASC
    """)
    suspend fun getItemsForExport(from: String?, to: String?): List<GeneralReceiptItem>

    // CSV出力履歴：出力済みの明細に出力日時を記録
    @Query("UPDATE general_receipt_items SET exportedAt = :exportedAt WHERE id IN (:ids)")
    suspend fun markExported(ids: List<Long>, exportedAt: String)
}
