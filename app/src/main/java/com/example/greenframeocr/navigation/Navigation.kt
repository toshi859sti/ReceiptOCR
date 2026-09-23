package com.example.greenframeocr.navigation

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.ReceiptDao
import com.example.greenframeocr.ui.DepositMenuScreen
import com.example.greenframeocr.ui.KaikakeTekiyouScreen
import com.example.greenframeocr.ui.MenuScreen
import com.example.greenframeocr.ui.MonthlySummaryScreen
import com.example.greenframeocr.ui.OutputConfirmScreen
import com.example.greenframeocr.ui.PassbookDataScreen
import com.example.greenframeocr.ui.ProductListScreen
import com.example.greenframeocr.ui.PurchaseMenuScreen
import com.example.greenframeocr.ui.RakurakuTekiyouScreen
import com.example.greenframeocr.ui.ReceiptInputScreen
import com.example.greenframeocr.ui.SettingsScreen
import com.example.greenframeocr.ui.TekiyouMatchingScreen
import com.example.greenframeocr.ui.BookkeepingMenuScreen
import com.example.greenframeocr.ui.RakurakuAccountSettingsScreen
import com.example.greenframeocr.ui.AoiroChoboAccountMappingScreen
import com.example.greenframeocr.ui.YayoiAccountEditScreen
import com.example.greenframeocr.ui.YayoiAccountSettingsScreen
import com.example.greenframeocr.ui.GeneralItemMatchingScreen
import com.example.greenframeocr.ui.GeneralPurchaseMenuScreen
import com.example.greenframeocr.ui.GeneralReceiptCaptureScreen
import com.example.greenframeocr.ui.GeneralReceiptConfirmScreen
import com.example.greenframeocr.ui.GeneralReceiptListScreen
import com.example.greenframeocr.ui.GeneralReceiptOutputScreen
import com.example.greenframeocr.ui.InvoiceStoreListScreen
import com.example.greenframeocr.ui.ReceiptPaymentMethodRuleScreen
import com.example.greenframeocr.ui.YearSummaryScreen
import com.example.greenframeocr.ui.YokinTekiyouScreen
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel

/**
 * 画面の定義
 */
sealed class Screen(val route: String) {
    object Menu : Screen("menu")
    object PurchaseMenu : Screen("purchase_menu")
    object DepositMenu : Screen("deposit_menu")
    object Settings : Screen("settings")
    object ReceiptInput : Screen("receipt_input")
    object ProductList : Screen("product_list")
    object KaikakeTekiyou : Screen("kaikake_tekiyou")
    object PurchaseOutputConfirm : Screen("purchase_output_confirm")
    object PassbookData : Screen("passbook_data")
    object TekiyouMatching : Screen("tekiyou_matching")
    object YokinTekiyou : Screen("yokin_tekiyou")
    object DepositOutputConfirm : Screen("deposit_output_confirm")
    object RakurakuTekiyou : Screen("rakuraku_tekiyou")
    object YearSummary : Screen("year_summary")
    object BookkeepingMenu : Screen("bookkeeping_menu")
    object YayoiAccountSettings : Screen("yayoi_account_settings")
    object RakurakuAccountSettings : Screen("rakuraku_account_settings")
    object AoiroChoboAccountMapping : Screen("aoirochobo_account_mapping")
    object YayoiAccountEdit : Screen("yayoi_account_edit/{accountId}?parentId={parentId}") {
        fun createRoute(accountId: Long, parentId: Long = -1L): String =
            "yayoi_account_edit/$accountId?parentId=$parentId"
    }
    object GeneralPurchaseMenu : Screen("general_purchase_menu")
    object GeneralReceiptCapture : Screen("general_receipt_capture")
    object GeneralReceiptConfirm : Screen("general_receipt_confirm")
    object GeneralReceiptList : Screen("general_receipt_list")
    object GeneralItemMatching : Screen("general_item_matching")
    object GeneralReceiptOutput : Screen("general_receipt_output")
    object InvoiceStoreList : Screen("invoice_store_list")
    object ReceiptPaymentMethodRules : Screen("receipt_payment_method_rules")
    object MonthlySummary : Screen("monthly_summary/{year}/{month}") {
        fun createRoute(year: Int, month: Int): String {
            return "monthly_summary/$year/$month"
        }
    }
}

/**
 * ナビゲーショングラフ
 */
@Composable
fun ReceiptNavGraph(
    navController: NavHostController = rememberNavController(),
    appPreferences: AppPreferences,
    dao: ReceiptDao,
    database: com.example.greenframeocr.data.ReceiptDatabase,
    startDestination: String = Screen.Menu.route,
    sharedCsvUri: Uri? = null,
    onThemeChanged: () -> Unit = {}
) {
    val generalReceiptViewModel: GeneralReceiptViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // メインメニュー画面
        composable(Screen.Menu.route) {
            MenuScreen(
                appPreferences = appPreferences,
                onNavigateToPurchaseMenu = {
                    navController.navigate(Screen.PurchaseMenu.route)
                },
                onNavigateToDepositMenu = {
                    navController.navigate(Screen.DepositMenu.route)
                },
                onNavigateToGeneralPurchaseMenu = {
                    navController.navigate(Screen.GeneralPurchaseMenu.route)
                },
                onNavigateToBookkeepingMenu = {
                    navController.navigate(Screen.BookkeepingMenu.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        // 購買部門サブメニュー
        composable(Screen.PurchaseMenu.route) {
            PurchaseMenuScreen(
                onBack = { navController.popBackStack() },
                onNavigateToReceiptInput = {
                    navController.navigate(Screen.ReceiptInput.route)
                },
                onNavigateToYearSummary = {
                    navController.navigate(Screen.YearSummary.route)
                },
                onNavigateToProductList = {
                    navController.navigate(Screen.ProductList.route)
                },
                onNavigateToOutputConfirm = {
                    navController.navigate(Screen.PurchaseOutputConfirm.route)
                }
            )
        }

        // 年次サマリー画面
        composable(Screen.YearSummary.route) {
            YearSummaryScreen(
                database = database,
                appPreferences = appPreferences,
                onNavigateToMonth = { year, month ->
                    navController.navigate(Screen.MonthlySummary.createRoute(year, month))
                },
                onBack = { navController.popBackStack() }
            )
        }

        // 預金部門サブメニュー
        composable(Screen.DepositMenu.route) {
            DepositMenuScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPassbookData = {
                    navController.navigate(Screen.PassbookData.route)
                },
                onNavigateToTekiyouMatching = {
                    navController.navigate(Screen.TekiyouMatching.route)
                },
                onNavigateToOutputConfirm = {
                    navController.navigate(Screen.DepositOutputConfirm.route)
                }
            )
        }

        // 買掛摘要辞書画面
        composable(Screen.KaikakeTekiyou.route) {
            KaikakeTekiyouScreen(
                database = database,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 購買出力確認画面
        composable(Screen.PurchaseOutputConfirm.route) {
            OutputConfirmScreen(
                department = "購買",
                database = database,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 通帳データ画面
        composable(Screen.PassbookData.route) {
            PassbookDataScreen(
                database = database,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() },
                initialUri = sharedCsvUri
            )
        }

        // 預金摘要辞書画面
        composable(Screen.YokinTekiyou.route) {
            YokinTekiyouScreen(
                database = database,
                onBack = { navController.popBackStack() }
            )
        }

        // 預金出力確認画面
        composable(Screen.DepositOutputConfirm.route) {
            OutputConfirmScreen(
                department = "預金",
                database = database,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 設定画面
        composable(Screen.Settings.route) {
            SettingsScreen(
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() },
                onThemeChanged = onThemeChanged
            )
        }

        // 簿記ソフト連携メニュー
        composable(Screen.BookkeepingMenu.route) {
            BookkeepingMenuScreen(
                onBack = { navController.popBackStack() },
                onNavigateToYayoiAccounts = {
                    navController.navigate(Screen.YayoiAccountSettings.route)
                },
                onNavigateToRakurakuAccounts = {
                    navController.navigate(Screen.RakurakuAccountSettings.route)
                },
                onNavigateToRakurakuTekiyou = {
                    navController.navigate(Screen.RakurakuTekiyou.route)
                },
                onNavigateToAoiroChoboAccountMapping = {
                    navController.navigate(Screen.AoiroChoboAccountMapping.route)
                }
            )
        }

        // あおいろ帳簿 科目マッピング画面（弥生科目に accountKey を割り当てる）
        composable(Screen.AoiroChoboAccountMapping.route) {
            AoiroChoboAccountMappingScreen(
                database = database,
                onBack = { navController.popBackStack() }
            )
        }

        // 弥生勘定科目設定画面（階層化表示）
        composable(Screen.YayoiAccountSettings.route) {
            YayoiAccountSettingsScreen(
                database = database,
                onBack = { navController.popBackStack() },
                onNavigateToEdit = { accountId, parentId ->
                    navController.navigate(Screen.YayoiAccountEdit.createRoute(accountId, parentId))
                }
            )
        }

        // らくらく勘定科目設定画面
        composable(Screen.RakurakuAccountSettings.route) {
            RakurakuAccountSettingsScreen(
                database = database,
                onBack = { navController.popBackStack() }
            )
        }

        // 弥生勘定科目編集画面（accountId = -1 で新規、parentId = -1 で単独作成）
        composable(
            route = Screen.YayoiAccountEdit.route,
            arguments = listOf(
                navArgument("accountId") { type = NavType.LongType },
                navArgument("parentId")  { type = NavType.LongType; defaultValue = -1L }
            )
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: -1L
            val parentId  = backStackEntry.arguments?.getLong("parentId")  ?: -1L
            YayoiAccountEditScreen(
                accountId     = accountId,
                initialParentId = if (parentId >= 0L) parentId else null,
                database      = database,
                onBack        = { navController.popBackStack() }
            )
        }

        // 購買品リスト画面
        composable(Screen.ProductList.route) {
            ProductListScreen(
                database = database,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 摘要辞書画面
        composable(Screen.RakurakuTekiyou.route) {
            RakurakuTekiyouScreen(
                database = database,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 摘要マッチング画面
        composable(Screen.TekiyouMatching.route) {
            TekiyouMatchingScreen(
                database = database,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 一般購買部門サブメニュー
        composable(Screen.GeneralPurchaseMenu.route) {
            GeneralPurchaseMenuScreen(
                onBack = { navController.popBackStack() },
                onNavigateToCapture = { navController.navigate(Screen.GeneralReceiptCapture.route) },
                onNavigateToList = { navController.navigate(Screen.GeneralReceiptList.route) },
                onNavigateToItemMatching = { navController.navigate(Screen.GeneralItemMatching.route) },
                onNavigateToOutput = { navController.navigate(Screen.GeneralReceiptOutput.route) },
                onNavigateToStoreList = { navController.navigate(Screen.InvoiceStoreList.route) },
                onNavigateToPaymentMethodRules = { navController.navigate(Screen.ReceiptPaymentMethodRules.route) }
            )
        }

        // 登録番号・店舗一覧画面
        composable(Screen.InvoiceStoreList.route) {
            InvoiceStoreListScreen(
                viewModel = generalReceiptViewModel,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 支払方法→相手科目設定画面
        composable(Screen.ReceiptPaymentMethodRules.route) {
            ReceiptPaymentMethodRuleScreen(
                viewModel = generalReceiptViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // 一般レシート撮影画面
        composable(Screen.GeneralReceiptCapture.route) {
            GeneralReceiptCaptureScreen(
                viewModel = generalReceiptViewModel,
                onNavigateToConfirm = {
                    navController.navigate(Screen.GeneralReceiptConfirm.route) {
                        popUpTo(Screen.GeneralReceiptCapture.route) { inclusive = true }
                    }
                },
                onNavigateToList = { navController.navigate(Screen.GeneralReceiptList.route) },
                onBack = { navController.popBackStack() }
            )
        }

        // 一般レシート確認・編集画面
        composable(Screen.GeneralReceiptConfirm.route) {
            GeneralReceiptConfirmScreen(
                viewModel = generalReceiptViewModel,
                onBack = { navController.popBackStack() },
                onSaved = {
                    // 連続撮影のため一覧ではなく撮影画面へループバックする
                    navController.navigate(Screen.GeneralReceiptCapture.route) {
                        popUpTo(Screen.GeneralPurchaseMenu.route)
                    }
                }
            )
        }

        // 一般レシート一覧画面
        composable(Screen.GeneralReceiptList.route) {
            GeneralReceiptListScreen(
                viewModel = generalReceiptViewModel,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 品目別マッチング画面
        composable(Screen.GeneralItemMatching.route) {
            GeneralItemMatchingScreen(
                viewModel = generalReceiptViewModel,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 一般購買CSV出力画面
        composable(Screen.GeneralReceiptOutput.route) {
            GeneralReceiptOutputScreen(
                viewModel = generalReceiptViewModel,
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() }
            )
        }

        // 伝票入力画面
        composable(Screen.ReceiptInput.route) {
            ReceiptInputScreen(
                eraYear = appPreferences.eraYear,
                database = database,
                onBack = { navController.popBackStack() },
                onNavigateToSummary = { year, month ->
                    navController.navigate(
                        Screen.MonthlySummary.createRoute(year, month)
                    )
                }
            )
        }

        // 月次サマリー画面
        composable(
            route = Screen.MonthlySummary.route,
            arguments = listOf(
                navArgument("year") { type = NavType.IntType },
                navArgument("month") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val year = backStackEntry.arguments?.getInt("year") ?: appPreferences.eraYear
            val month = backStackEntry.arguments?.getInt("month") ?: 1

            MonthlySummaryScreen(
                year = year,
                month = month,
                database = database,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * プレースホルダー画面（実装前の仮画面）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(
    screenName: String,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "戻る"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$screenName\n（実装中）",
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}
