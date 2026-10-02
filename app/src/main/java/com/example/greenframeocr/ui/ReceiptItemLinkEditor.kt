package com.example.greenframeocr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.GeneralItemGroup
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel

/**
 * レシートの品目の科目・摘要をどこに設定するか。商品名・但し書きリストとレシート詳細の両方から使う。
 *
 * - [Group]：同じ品目名（canonicalKey）の明細すべて。保存するとグループ内の個別変更は解除される
 * - [Item]：この明細だけ（個別変更）
 */
sealed interface ReceiptLinkTarget {
    data class Group(val canonicalKey: String) : ReceiptLinkTarget
    data class Item(val item: GeneralReceiptItem) : ReceiptLinkTarget
}

/**
 * [target] の科目・摘要を設定するダイアログ。あおいろモードならあおいろの科目・摘要、弥生モードなら弥生の科目。
 * グループ・科目の一覧・あおいろの辞書・支払方法は自分で読み込む（呼び出し側は対象を渡すだけ）
 */
@Composable
fun ReceiptItemLinkEditor(
    target: ReceiptLinkTarget,
    isAoiro: Boolean,
    viewModel: GeneralReceiptViewModel,
    onDismiss: () -> Unit,
    /** 弥生の科目設定の中で科目の一覧が変わったとき（「レシートで使う」の印の変更など）。呼び出し側の表示を合わせる */
    onYayoiAccountsChanged: (List<YayoiAccount>) -> Unit = {}
) {
    val itemGroups by viewModel.itemGroups.collectAsState()
    val canonicalKey = when (target) {
        is ReceiptLinkTarget.Group -> target.canonicalKey
        is ReceiptLinkTarget.Item -> target.item.canonicalKey
    }
    val group = itemGroups.find { it.canonicalKey == canonicalKey }

    var loaded by remember { mutableStateOf(false) }
    var yayoiAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var yayoiFlaggedAccounts by remember { mutableStateOf<List<YayoiAccount>>(emptyList()) }
    var aoiroVocab by remember { mutableStateOf<GeneralReceiptViewModel.AoiroVocab?>(null) }
    // 明細の摘要の帳簿はそのレシートの支払方法で決まる
    var payment by remember { mutableStateOf<AoiroChoboAccount?>(null) }

    LaunchedEffect(target) {
        val accounts = viewModel.loadYayoiAccounts()
        yayoiAccounts = accounts
        yayoiFlaggedAccounts = accounts.filter { it.usedForReceipt }
        if (isAoiro) {
            aoiroVocab = viewModel.loadAoiroVocab()
            if (target is ReceiptLinkTarget.Item) payment = viewModel.aoiroPaymentAccountForReceipt(target.item.receiptId)
        }
        loaded = true
    }
    // グループが一覧に無い（品目名が空・経費対象外）明細は設定できない。呼び出し側で押せないようにしている
    if (!loaded || group == null) return

    when {
        isAoiro && target is ReceiptLinkTarget.Group -> {
            val vocab = aoiroVocab
            AoiroLinkDialog(
                title = "あおいろ科目・摘要",
                subject = "${group.itemName}（全年で ${group.count}件）",
                kind = AoiroLinkKind.receiptGroup,
                accounts = vocab?.accounts.orEmpty(),
                memos = vocab?.memos.orEmpty(),
                usage = vocab?.usage.orEmpty(),
                initialAccountKey = group.accountKey,
                initialAccountKeyName = group.accountKeyName,
                initialMemoKey = group.memoKey,
                initialMemoKeyName = group.memoKeyName,
                note = "この品目名のレシート明細すべて（ほかの年も含む）に使います（弥生の科目とは別）。保存すると明細ごとの個別変更は解除します",
                resetHint = "科目を外すと、この品目は「科目なし」で PC に送ります",
                extraNote = "摘要は、PC の摘要登録で「${AoiroLinkKind.RECEIPT_COMMON_LABEL}」にしたものから選びます。" +
                    "現金・カード（未払）・家計から（振替）のどの支払方法のレシートでも同じ摘要で送ります",
                onDismiss = onDismiss,
                onSave = { s ->
                    viewModel.updateGroupAoiro(group.canonicalKey, s.accountKey, s.accountKeyName, s.memoKey, s.memoKeyName)
                    onDismiss()
                }
            )
        }
        isAoiro && target is ReceiptLinkTarget.Item -> {
            val vocab = aoiroVocab
            val item = target.item
            AoiroLinkDialog(
                title = "個別変更（あおいろ）",
                subject = "${item.itemName}  ¥${"%,d".format(item.price)}",
                kind = AoiroLinkKind.receiptItem(payment),
                accounts = vocab?.accounts.orEmpty(),
                memos = vocab?.memos.orEmpty(),
                usage = vocab?.usage.orEmpty(),
                initialAccountKey = item.overrideAccountKey,
                initialAccountKeyName = item.overrideAccountKeyName,
                initialMemoKey = item.overrideMemoKey,
                initialMemoKeyName = item.overrideMemoKeyName,
                note = "保存するとグループ設定に関わらずこの明細にのみ適用されます",
                resetHint = "科目を外すとグループの設定に戻ります（${aoiroLinkLabel(vocab, group) ?: "グループも未設定"}）",
                extraNote = "このレシートの支払方法（${payment?.name ?: "未設定"}）の帳簿の摘要と、" +
                    "${AoiroLinkKind.RECEIPT_COMMON_LABEL}の摘要から選びます",
                onDismiss = onDismiss,
                onSave = { s ->
                    viewModel.updateItemAoiroOverride(item.id, s.accountKey, s.accountKeyName, s.memoKey, s.memoKeyName)
                    onDismiss()
                }
            )
        }
        target is ReceiptLinkTarget.Group -> {
            GroupDefaultEditDialog(
                group = group,
                yayoiAccounts = yayoiAccounts,
                yayoiFlaggedAccounts = yayoiFlaggedAccounts,
                onDismiss = onDismiss,
                onSave = { key, accountId ->
                    viewModel.updateGroupDefaultAccount(key, accountId)
                    onDismiss()
                },
                onLoadAccounts = { accounts ->
                    yayoiAccounts = accounts
                    yayoiFlaggedAccounts = accounts.filter { it.usedForReceipt }
                    onYayoiAccountsChanged(accounts)
                },
                viewModel = viewModel
            )
        }
        target is ReceiptLinkTarget.Item -> {
            IndividualItemOverrideDialog(
                item = target.item,
                groupDefaultAccountName = yayoiAccounts.find { it.id == group.yayoiAccountId }?.accountName,
                yayoiAccounts = yayoiAccounts,
                yayoiFlaggedAccounts = yayoiFlaggedAccounts,
                onDismiss = onDismiss,
                onSave = { itemId, accountId ->
                    viewModel.updateItemOverride(itemId, accountId)
                    onDismiss()
                }
            )
        }
    }
}

/**
 * あおいろの「科目 ／ 摘要」の表示。名前は今の辞書から引く（PC で改名されていればそちら）。
 * 辞書から消えたキーは保存時の名前。科目が無ければ null
 */
fun aoiroLinkLabel(
    vocab: GeneralReceiptViewModel.AoiroVocab?,
    accountKey: String?,
    accountKeyName: String?,
    memoKey: String?,
    memoKeyName: String?
): String? {
    val key = accountKey ?: return null
    val account = vocab?.accounts?.find { it.accountKey == key }?.name ?: accountKeyName ?: key
    val memo = memoKey?.let { m -> vocab?.memos?.find { it.memoKey == m }?.name ?: memoKeyName ?: m }
    return if (memo != null) "$account ／ $memo" else account
}

fun aoiroLinkLabel(vocab: GeneralReceiptViewModel.AoiroVocab?, group: GeneralItemGroup): String? =
    aoiroLinkLabel(vocab, group.accountKey, group.accountKeyName, group.memoKey, group.memoKeyName)

/** 明細に効いているあおいろの科目・摘要（個別変更があればそれ、無ければグループ） */
fun aoiroItemLinkLabel(vocab: GeneralReceiptViewModel.AoiroVocab?, item: GeneralReceiptItem, group: GeneralItemGroup?): String? =
    if (item.overrideAccountKey != null) {
        aoiroLinkLabel(vocab, item.overrideAccountKey, item.overrideAccountKeyName, item.overrideMemoKey, item.overrideMemoKeyName)
    } else group?.let { aoiroLinkLabel(vocab, it) }
