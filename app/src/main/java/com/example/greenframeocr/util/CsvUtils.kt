package com.example.greenframeocr.util

import java.nio.charset.Charset

/**
 * CSV出力の共有ユーティリティ。
 * 弥生仕訳CSV（25列・windows-31j・CRLF）とらくらくシンプルCSV（UTF-8）の両方から使用する。
 */
object CsvUtils {

    /** カンマ・引用符・改行を含むフィールドのみ引用符で囲む（シンプルCSV用） */
    fun escapeCsvField(field: String): String =
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"${field.replace("\"", "\"\"")}\""
        } else field

    /** 常に引用符で囲む（弥生25列形式は全フィールド引用符囲みが仕様） */
    fun quoteField(s: String): String = "\"${s.replace("\"", "\"\"")}\""

    /** 西暦日付文字列（区切りは - / どちらも可）→ 弥生和暦形式（R.yy/MM/dd） */
    fun toYayoiDate(dateStr: String): String {
        val parts = dateStr.split(Regex("[-/]"))
        if (parts.size < 3) return dateStr
        val year = parts[0].toIntOrNull() ?: return dateStr
        val month = parts[1].toIntOrNull() ?: return dateStr
        val day = parts[2].toIntOrNull() ?: return dateStr
        val isReiwa = year > 2019 || (year == 2019 && month >= 5)
        return if (isReiwa) {
            "R.%02d/%02d/%02d".format(year - 2018, month, day)
        } else {
            "H.%02d/%02d/%02d".format(year - 1988, month, day)
        }
    }

    /**
     * 弥生CSV出力用の文字コード。
     * "Shift_JIS" は ①・㈱ 等の機種依存文字をエンコードできず無警告で ? に化けるため、
     * Windowsコードページ相当の windows-31j（MS932）を優先する。
     */
    fun yayoiCharset(): Charset =
        try {
            Charset.forName("windows-31j")
        } catch (_: Exception) {
            Charset.forName("Shift_JIS")
        }
}
