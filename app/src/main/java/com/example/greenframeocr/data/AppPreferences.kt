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
        for (key in listOf(KEY_GEMINI_API_KEY)) {
            if (!securePrefs.contains(key) && prefs.contains(key)) {
                editor.putString(key, prefs.getString(key, ""))
                migrated = true
            }
        }
        if (migrated) {
            editor.apply()
            prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
        }
        // 廃止した国税庁インボイス照会機能の旧設定値（平文prefs・秘匿ファイル両方）を除去
        if (prefs.contains(KEY_NTA_APPLICATION_ID_LEGACY)) {
            prefs.edit().remove(KEY_NTA_APPLICATION_ID_LEGACY).apply()
        }
        if (securePrefs.contains(KEY_NTA_APPLICATION_ID_LEGACY)) {
            securePrefs.edit().remove(KEY_NTA_APPLICATION_ID_LEGACY).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "receipt_ocr_preferences"
        private const val SECURE_PREFS_NAME = "receipt_ocr_secrets"

        // 年号設定（作業年。1月〜12月区切りの暦年で、年度＝4月始まりではない）
        private const val KEY_ERA_YEAR = "era_year"
        private const val DEFAULT_ERA_YEAR = 7  // 令和7年（デフォルト）
        private const val REIWA_TO_SEIREKI_OFFSET = 2018  // 令和1年 = 西暦2019年

        // 通帳データ・レシート一覧の年フィルターを作業年に固定するか
        private const val KEY_LOCK_YEAR_TO_WORKING = "lock_year_to_working"
        private const val KEY_LAST_PASSBOOK_ID = "last_passbook_id"
        private const val DEFAULT_LOCK_YEAR_TO_WORKING = false

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

        // 廃止済み：国税庁インボイス照会 アプリケーションID（旧設定値の削除用にキー名のみ残す）
        private const val KEY_NTA_APPLICATION_ID_LEGACY = "nta_application_id"

        // 連携会計ソフト
        private const val KEY_ACCOUNTING_SOFTWARE = "accounting_software"
        private const val DEFAULT_ACCOUNTING_SOFTWARE = "YAYOI"

        // 一覧文字サイズ
        private const val KEY_LIST_FONT_SIZE = "list_font_size"
        const val DEFAULT_LIST_FONT_SIZE = 14f

        // APIトークン使用量の累計（他者への請求目的。リセット可能な単純合計）
        private const val KEY_CUMULATIVE_PROMPT_TOKENS = "cumulative_prompt_tokens"
        private const val KEY_CUMULATIVE_CANDIDATES_TOKENS = "cumulative_candidates_tokens"
        private const val KEY_CUMULATIVE_TOTAL_TOKENS = "cumulative_total_tokens"
        private const val KEY_TOKEN_USAGE_RESET_AT = "token_usage_reset_at"
    }

    // 年号設定（作業年・JA購買伝票／JA預金／レシートの初期表示年に共通で使用）
    var eraYear: Int
        get() = prefs.getInt(KEY_ERA_YEAR, DEFAULT_ERA_YEAR)
        set(value) = prefs.edit().putInt(KEY_ERA_YEAR, value).apply()

    // 作業年を西暦に変換したもの（JA預金・レシート画面の年フィルターのデフォルト値に使用）
    val workingCalendarYear: Int
        get() = eraYear + REIWA_TO_SEIREKI_OFFSET

    // 通帳データ・レシート一覧の年フィルターを作業年に固定するか
    var lockYearToWorking: Boolean
        get() = prefs.getBoolean(KEY_LOCK_YEAR_TO_WORKING, DEFAULT_LOCK_YEAR_TO_WORKING)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_YEAR_TO_WORKING, value).apply()

    // 通帳CSVを最後に取り込んだ通帳（次の取込で最初から選んでおく・通帳データ画面の表示通帳）
    var lastPassbookId: Int
        get() = prefs.getInt(KEY_LAST_PASSBOOK_ID, Passbook.DEFAULT_ID)
        set(value) = prefs.edit().putInt(KEY_LAST_PASSBOOK_ID, value).apply()

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

    // 一覧文字サイズ（PassbookDataScreen / GeneralReceiptListScreen / TekiyouMatchingScreen 共通）
    var listFontSize: Float
        get() = prefs.getFloat(KEY_LIST_FONT_SIZE, DEFAULT_LIST_FONT_SIZE)
        set(value) = prefs.edit().putFloat(KEY_LIST_FONT_SIZE, value.coerceIn(10f, 20f)).apply()

    // 連携会計ソフト。古い保存値は今の値に移して書き戻す：あおいろの旧名 "BLUE_RETURN_PREP" は AOIRO、
    // それ以外の知らない値（サポート終了したらくらくの "RAKURAKU" など）は弥生
    var accountingSoftware: AccountingSoftware
        get() {
            val stored = prefs.getString(KEY_ACCOUNTING_SOFTWARE, DEFAULT_ACCOUNTING_SOFTWARE) ?: DEFAULT_ACCOUNTING_SOFTWARE
            AccountingSoftware.entries.firstOrNull { it.name == stored }?.let { return it }
            val migrated = if (stored == "BLUE_RETURN_PREP") AccountingSoftware.AOIRO else AccountingSoftware.YAYOI
            prefs.edit().putString(KEY_ACCOUNTING_SOFTWARE, migrated.name).apply()
            return migrated
        }
        set(value) = prefs.edit().putString(KEY_ACCOUNTING_SOFTWARE, value.name).apply()

    // ダークモード
    var darkMode: AppDarkMode
        get() = try {
            AppDarkMode.valueOf(prefs.getString(KEY_DARK_MODE, DEFAULT_DARK_MODE) ?: DEFAULT_DARK_MODE)
        } catch (_: IllegalArgumentException) { AppDarkMode.SYSTEM }
        set(value) = prefs.edit().putString(KEY_DARK_MODE, value.name).apply()

    // APIトークン使用量の累計（入力・出力・合計）。起点は tokenUsageResetAt（未リセットなら0）
    val cumulativePromptTokens: Long
        get() = prefs.getLong(KEY_CUMULATIVE_PROMPT_TOKENS, 0L)
    val cumulativeCandidatesTokens: Long
        get() = prefs.getLong(KEY_CUMULATIVE_CANDIDATES_TOKENS, 0L)
    val cumulativeTotalTokens: Long
        get() = prefs.getLong(KEY_CUMULATIVE_TOTAL_TOKENS, 0L)
    val tokenUsageResetAt: Long
        get() = prefs.getLong(KEY_TOKEN_USAGE_RESET_AT, 0L)

    /** AI呼び出し1回分のトークン使用量を累計に加算する（他者への請求目的の集計） */
    fun addTokenUsage(promptTokens: Int, candidatesTokens: Int, totalTokens: Int) {
        prefs.edit()
            .putLong(KEY_CUMULATIVE_PROMPT_TOKENS, cumulativePromptTokens + promptTokens)
            .putLong(KEY_CUMULATIVE_CANDIDATES_TOKENS, cumulativeCandidatesTokens + candidatesTokens)
            .putLong(KEY_CUMULATIVE_TOTAL_TOKENS, cumulativeTotalTokens + totalTokens)
            .apply()
    }

    /** 累計トークン数をリセットし、この時点を新しい集計起点として記録する */
    fun resetTokenUsage() {
        prefs.edit()
            .putLong(KEY_CUMULATIVE_PROMPT_TOKENS, 0L)
            .putLong(KEY_CUMULATIVE_CANDIDATES_TOKENS, 0L)
            .putLong(KEY_CUMULATIVE_TOTAL_TOKENS, 0L)
            .putLong(KEY_TOKEN_USAGE_RESET_AT, System.currentTimeMillis())
            .apply()
    }

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
    YAYOI("弥生の青色申告"),
    AOIRO("あおいろ帳簿")  // PC 会計アプリ AoiroChobo。2026-09-29 までの保存値は "BLUE_RETURN_PREP"（AppPreferences.accountingSoftware で移す）
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
