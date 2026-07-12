package com.example.greenframeocr.data

import android.content.Context
import android.content.SharedPreferences

/**
 * アプリケーション設定を管理するクラス
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // APIキー等の秘匿情報専用ファイル。backup_rules.xml / data_extraction_rules.xml で
    // 自動バックアップから除外しているため、キーが Google のサーバーに上がらない
    private val securePrefs: SharedPreferences =
        context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateSecretsToSecurePrefs()
    }

    /** 旧バージョンで通常prefsに保存していた秘匿情報を秘匿ファイルへ一度だけ移す */
    private fun migrateSecretsToSecurePrefs() {
        val editor = securePrefs.edit()
        var migrated = false
        for (key in listOf(KEY_GEMINI_API_KEY, KEY_NTA_APPLICATION_ID)) {
            if (!securePrefs.contains(key) && prefs.contains(key)) {
                editor.putString(key, prefs.getString(key, ""))
                migrated = true
            }
        }
        if (migrated) {
            editor.apply()
            prefs.edit().remove(KEY_GEMINI_API_KEY).remove(KEY_NTA_APPLICATION_ID).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "receipt_ocr_preferences"
        private const val SECURE_PREFS_NAME = "receipt_ocr_secrets"

        // 年号設定
        private const val KEY_ERA_YEAR = "era_year"
        private const val DEFAULT_ERA_YEAR = 7  // 令和7年（デフォルト）

        // 現在の月設定（OCR撮影時に使用）
        private const val KEY_CURRENT_ISSUE_MONTH = "current_issue_month"
        private const val DEFAULT_CURRENT_ISSUE_MONTH = 1  // 1月（デフォルト）

        // 年月固定設定
        private const val KEY_FIX_YEAR_MONTH = "fix_year_month"
        private const val DEFAULT_FIX_YEAR_MONTH = false

        // 撮影品質設定
        private const val KEY_MIN_SHARPNESS = "min_sharpness"
        const val DEFAULT_MIN_SHARPNESS = 1000

        // カメラ設定
        private const val KEY_CAMERA_RESOLUTION = "camera_resolution"
        private const val DEFAULT_CAMERA_RESOLUTION = "3840x2160"  // 4K UHD (固定)
        private const val KEY_CAMERA_PREVIEW = "camera_preview"
        private const val DEFAULT_CAMERA_PREVIEW = false  // プレビューは基本OFF
        private const val KEY_CAMERA_FLASH = "camera_flash"
        private const val DEFAULT_CAMERA_FLASH = false

        // 預金部門設定
        private const val KEY_DEPOSIT_HIDE_AMOUNT = "deposit_hide_amount"
        private const val DEFAULT_DEPOSIT_HIDE_AMOUNT = false

        // テーマ設定
        private const val KEY_THEME_PRESET = "theme_preset"
        private const val DEFAULT_THEME_PRESET = "GREEN"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val DEFAULT_DARK_MODE = "SYSTEM"

        // Gemini API キー（一般購買OCR用）
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"

        // 国税庁インボイス照会 アプリケーションID
        private const val KEY_NTA_APPLICATION_ID = "nta_application_id"

        // 連携会計ソフト
        private const val KEY_ACCOUNTING_SOFTWARE = "accounting_software"
        private const val DEFAULT_ACCOUNTING_SOFTWARE = "RAKURAKU"

        // 一覧文字サイズ
        private const val KEY_LIST_FONT_SIZE = "list_font_size"
        const val DEFAULT_LIST_FONT_SIZE = 14f
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

    // 最低鮮鋭度（OCR撮影トリガーの閾値）
    var minSharpness: Int
        get() = prefs.getInt(KEY_MIN_SHARPNESS, DEFAULT_MIN_SHARPNESS)
        set(value) = prefs.edit().putInt(KEY_MIN_SHARPNESS, value.coerceIn(500, 3000)).apply()

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

    // 預金部門：金額を非表示
    var depositHideAmount: Boolean
        get() = prefs.getBoolean(KEY_DEPOSIT_HIDE_AMOUNT, DEFAULT_DEPOSIT_HIDE_AMOUNT)
        set(value) = prefs.edit().putBoolean(KEY_DEPOSIT_HIDE_AMOUNT, value).apply()

    // テーマプリセット
    var themePreset: AppThemePreset
        get() = try {
            AppThemePreset.valueOf(prefs.getString(KEY_THEME_PRESET, DEFAULT_THEME_PRESET) ?: DEFAULT_THEME_PRESET)
        } catch (_: IllegalArgumentException) { AppThemePreset.GREEN }
        set(value) = prefs.edit().putString(KEY_THEME_PRESET, value.name).apply()

    // Gemini API キー（秘匿ファイル・バックアップ除外）
    var geminiApiKey: String
        get() = securePrefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    // 国税庁インボイス照会 アプリケーションID（秘匿ファイル・バックアップ除外）
    var ntaApplicationId: String
        get() = securePrefs.getString(KEY_NTA_APPLICATION_ID, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_NTA_APPLICATION_ID, value).apply()

    // 一覧文字サイズ（PassbookDataScreen / GeneralReceiptListScreen / TekiyouMatchingScreen 共通）
    var listFontSize: Float
        get() = prefs.getFloat(KEY_LIST_FONT_SIZE, DEFAULT_LIST_FONT_SIZE)
        set(value) = prefs.edit().putFloat(KEY_LIST_FONT_SIZE, value.coerceIn(10f, 20f)).apply()

    // 連携会計ソフト
    var accountingSoftware: AccountingSoftware
        get() = try {
            AccountingSoftware.valueOf(prefs.getString(KEY_ACCOUNTING_SOFTWARE, DEFAULT_ACCOUNTING_SOFTWARE) ?: DEFAULT_ACCOUNTING_SOFTWARE)
        } catch (_: IllegalArgumentException) { AccountingSoftware.RAKURAKU }
        set(value) = prefs.edit().putString(KEY_ACCOUNTING_SOFTWARE, value.name).apply()

    // ダークモード
    var darkMode: AppDarkMode
        get() = try {
            AppDarkMode.valueOf(prefs.getString(KEY_DARK_MODE, DEFAULT_DARK_MODE) ?: DEFAULT_DARK_MODE)
        } catch (_: IllegalArgumentException) { AppDarkMode.SYSTEM }
        set(value) = prefs.edit().putString(KEY_DARK_MODE, value.name).apply()

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
 * 連携会計ソフト
 */
enum class AccountingSoftware(val displayName: String) {
    RAKURAKU("らくらく青色申告農業版"),
    YAYOI("弥生の青色申告"),
    BLUE_RETURN_PREP("BlueReturnPrep（自作）")
}

/**
 * テーマプリセット
 */
enum class AppThemePreset(val displayName: String) {
    GREEN("農業グリーン"),
    PURPLE("パープル"),
    BLUE("オーシャンブルー"),
    TERRA("テラコッタ"),
    MONO("モノクローム")
}

/**
 * ダークモード設定
 */
enum class AppDarkMode(val displayName: String) {
    SYSTEM("システムに従う"),
    LIGHT("常にライト"),
    DARK("常にダーク")
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
