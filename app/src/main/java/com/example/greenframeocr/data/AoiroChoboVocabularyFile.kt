package com.example.greenframeocr.data

import com.google.gson.JsonObject

/**
 * AoiroChobo が書き出す `vocabulary.json` の形（schemaVersion 2）。
 *
 * Gson はコンストラクタのデフォルト値を使わずフィールドを null のまま残すので、
 * **全フィールドを nullable で受けて**、エンティティへ移すときに既定値を当てる。
 * 未知の enum 値は弾かず、文字列のまま保持する（契約どおり警告に留める）。
 */
data class AoiroChoboVocabularyFile(
    val schemaVersion: Int? = null,
    val kind: String? = null,
    val generatedAt: String? = null,
    val generatedBy: GeneratedBy? = null,
    val fiscalYear: FiscalYear? = null,
    val contentHash: String? = null,
    /** 再計算せず、そのまま持って transactions.json に転記する */
    val enums: JsonObject? = null,
    val accounts: List<Account>? = null,
    /** 契約上の名前は memoTemplates（memos ではない）。間違えると摘要が黙って0件になる */
    val memoTemplates: List<MemoTemplate>? = null
) {
    data class GeneratedBy(val app: String? = null, val appVersion: String? = null)

    data class FiscalYear(val year: Int? = null, val startDate: String? = null, val endDate: String? = null)

    data class Account(
        val accountKey: String? = null,
        val searchKey: String? = null,
        val name: String? = null,
        val accountType: String? = null,
        val groupName: String? = null,
        val displayGroup: String? = null,
        val parentAccountKey: String? = null,
        val ledgerAffinity: String? = null,
        val bankSlotNo: Int? = null,
        val allowsTaxable: Boolean? = null,
        val allowsNonTaxable: Boolean? = null,
        val defaultTaxCategory: String? = null,
        val displayOrder: Int? = null,
        val isSystem: Boolean? = null,
        val ocrRoleExpenseDebit: Boolean? = null,
        val ocrRoleDepositCounter: Boolean? = null
    )

    data class MemoTemplate(
        val memoKey: String? = null,
        val ledgerType: String? = null,
        val direction: String? = null,
        val name: String? = null,
        val searchKey: String? = null,
        val counterAccountKey: String? = null,
        val debitAccountKey: String? = null,
        val creditAccountKey: String? = null,
        val taxRate: String? = null,
        val creditTaxRate: String? = null,
        val businessRatio: Int? = null,
        val creditBusinessRatio: Int? = null,
        val hasInvoiceDefault: Boolean? = null,
        val showInCash: Boolean? = null,
        val showInBank: Boolean? = null,
        val bankSlotNo: Int? = null,
        val displayOrder: Int? = null,
        val isPreset: Boolean? = null
    )
}

/** 取り込める形に整った科目だけをエンティティへ（accountKey と name が無い行は捨てる） */
fun AoiroChoboVocabularyFile.Account.toEntityOrNull(): AoiroChoboAccount? {
    val key = accountKey?.takeIf { it.isNotBlank() } ?: return null
    val displayName = name ?: return null
    return AoiroChoboAccount(
        accountKey = key,
        searchKey = searchKey ?: "",
        name = displayName,
        accountType = accountType ?: "",
        groupName = groupName,
        displayGroup = displayGroup,
        parentAccountKey = parentAccountKey,
        ledgerAffinity = ledgerAffinity ?: "",
        bankSlotNo = bankSlotNo,
        allowsTaxable = allowsTaxable ?: false,
        allowsNonTaxable = allowsNonTaxable ?: false,
        defaultTaxCategory = defaultTaxCategory,
        displayOrder = displayOrder ?: 0,
        isSystem = isSystem ?: false,
        ocrRoleExpenseDebit = ocrRoleExpenseDebit ?: false,
        ocrRoleDepositCounter = ocrRoleDepositCounter ?: false
    )
}

fun AoiroChoboVocabularyFile.MemoTemplate.toEntityOrNull(): AoiroChoboMemoTemplate? {
    val key = memoKey?.takeIf { it.isNotBlank() } ?: return null
    val displayName = name ?: return null
    return AoiroChoboMemoTemplate(
        memoKey = key,
        ledgerType = ledgerType ?: "",
        direction = direction ?: "",
        name = displayName,
        searchKey = searchKey ?: "",
        counterAccountKey = counterAccountKey,
        debitAccountKey = debitAccountKey,
        creditAccountKey = creditAccountKey,
        taxRate = taxRate,
        creditTaxRate = creditTaxRate,
        businessRatio = businessRatio,
        creditBusinessRatio = creditBusinessRatio,
        hasInvoiceDefault = hasInvoiceDefault ?: false,
        showInCash = showInCash ?: false,
        showInBank = showInBank ?: false,
        bankSlotNo = bankSlotNo,
        displayOrder = displayOrder ?: 0,
        isPreset = isPreset ?: false
    )
}
