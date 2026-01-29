package com.example.receiptorc.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 補正ログテーブル
 *
 * 補正判定の全履歴を保存。
 * デバッグ、検証、学習品質の監視に使用。
 */
@Entity(
    tableName = "correction_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["decision"]),
        Index(value = ["sessionId"])
    ]
)
data class CorrectionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** セッションID（同一OCR処理のグループ化用） */
    val sessionId: String,

    /** タイムスタンプ */
    val timestamp: Long = System.currentTimeMillis(),

    /** OCR生テキスト */
    val rawText: String,

    /** 正規化後テキスト */
    val normalizedRaw: String,

    /** カテゴリ */
    val category: String,

    /** Layer 0通過数 */
    val hardConstraintsPassed: Int,

    /** 1位商品名 */
    val topProduct: String?,

    /** 1位ベーススコア */
    val topBaseScore: Double,

    /** 1位ボーナス合計 */
    val topBonusTotal: Double,

    /** 1位最終スコア */
    val topFinalScore: Double,

    /** 2位商品名 */
    val secondProduct: String?,

    /** 2位最終スコア */
    val secondFinalScore: Double,

    /** 補正判定結果 */
    val decision: String,

    /** 補正後商品名 */
    val correctedName: String?,

    /** 補正成功フラグ */
    val matched: Boolean,

    /** ボーナス内訳（JSON） */
    val bonusBreakdown: String? = null
)
