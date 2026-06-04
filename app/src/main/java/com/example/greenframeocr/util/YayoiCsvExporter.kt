package com.example.greenframeocr.util

fun toYayoiTaxString(defaultTaxCategory: String, isDebitSide: Boolean): String {
    return when (defaultTaxCategory) {
        "対象外"     -> "対象外"
        "非課税"     -> if (isDebitSide) "非課仕入" else "非課売上"
        "課対仕入10" -> if (isDebitSide) "課対仕入込10%" else "課税売上込二10%"
        "課対仕入8"  -> if (isDebitSide) "課対仕入込 軽減8%" else "課税売上込二 軽減8%"
        "課税売上"   -> if (isDebitSide) "課対仕入込10%" else "課税売上込二10%"
        else         -> "対象外"
    }
}
