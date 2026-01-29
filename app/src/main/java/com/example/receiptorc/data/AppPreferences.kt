package com.example.receiptorc.data

import android.content.Context
import android.content.SharedPreferences

/**
 * アプリケーション設定を管理するクラス
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "receipt_ocr_preferences"

        // 年号設定
        private const val KEY_ERA_YEAR = "era_year"
        private const val DEFAULT_ERA_YEAR = 7  // 令和7年（デフォルト）

        // 現在の月設定（OCR撮影時に使用）
        private const val KEY_CURRENT_ISSUE_MONTH = "current_issue_month"
        private const val DEFAULT_CURRENT_ISSUE_MONTH = 1  // 1月（デフォルト）

        // 年月固定設定
        private const val KEY_FIX_YEAR_MONTH = "fix_year_month"
        private const val DEFAULT_FIX_YEAR_MONTH = false

        // カメラ設定
        private const val KEY_CAMERA_RESOLUTION = "camera_resolution"
        private const val DEFAULT_CAMERA_RESOLUTION = "3840x2160"  // 4K UHD (固定)
        private const val KEY_CAMERA_PREVIEW = "camera_preview"
        private const val DEFAULT_CAMERA_PREVIEW = false  // プレビューは基本OFF
        private const val KEY_CAMERA_FLASH = "camera_flash"
        private const val DEFAULT_CAMERA_FLASH = false
    }

    // 年号設定
    var eraYear: Int
        get() = prefs.getInt(KEY_ERA_YEAR, DEFAULT_ERA_YEAR)
        set(value) = prefs.edit().putInt(KEY_ERA_YEAR, value).apply()

    // 現在の月設定（OCR撮影時に使用）
    var currentIssueMonth: Int
        get() = prefs.getInt(KEY_CURRENT_ISSUE_MONTH, DEFAULT_CURRENT_ISSUE_MONTH)
        set(value) {
            // 1-12の範囲チェック
            val validValue = value.coerceIn(1, 12)
            prefs.edit().putInt(KEY_CURRENT_ISSUE_MONTH, validValue).apply()
        }

    // 年月固定設定
    var fixYearMonth: Boolean
        get() = prefs.getBoolean(KEY_FIX_YEAR_MONTH, DEFAULT_FIX_YEAR_MONTH)
        set(value) = prefs.edit().putBoolean(KEY_FIX_YEAR_MONTH, value).apply()

    // カメラ解像度
    var cameraResolution: String
        get() = prefs.getString(KEY_CAMERA_RESOLUTION, DEFAULT_CAMERA_RESOLUTION)
            ?: DEFAULT_CAMERA_RESOLUTION
        set(value) = prefs.edit().putString(KEY_CAMERA_RESOLUTION, value).apply()

    // カメラプレビュー表示
    var cameraPreview: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_PREVIEW, DEFAULT_CAMERA_PREVIEW)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_PREVIEW, value).apply()

    // カメラフラッシュ
    var cameraFlash: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_FLASH, DEFAULT_CAMERA_FLASH)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_FLASH, value).apply()

    /**
     * カメラ解像度を幅と高さのペアに変換
     * 例: "1920x1080" -> Pair(1920, 1080)
     */
    fun getCameraResolutionSize(): Pair<Int, Int> {
        val parts = cameraResolution.split("x")
        return if (parts.size == 2) {
            Pair(parts[0].toIntOrNull() ?: 1920, parts[1].toIntOrNull() ?: 1080)
        } else {
            Pair(1920, 1080)
        }
    }

    /**
     * すべての設定をデフォルトに戻す
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}

/**
 * カメラ解像度の選択肢
 */
enum class CameraResolution(val displayName: String, val value: String, val width: Int, val height: Int) {
    HD("HD (1280x720)", "1280x720", 1280, 720),
    FULL_HD("Full HD (1920x1080)", "1920x1080", 1920, 1080),
    QHD("QHD (2560x1440)", "2560x1440", 2560, 1440),
    UHD_4K("4K UHD (3840x2160)", "3840x2160", 3840, 2160);

    companion object {
        fun fromValue(value: String): CameraResolution {
            return values().find { it.value == value } ?: UHD_4K
        }
    }
}
