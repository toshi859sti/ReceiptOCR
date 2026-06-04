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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.greenframeocr.data.AppPreferences
import com.example.greenframeocr.data.ReceiptDao
import com.example.greenframeocr.ui.DebugCaptureScreen
import com.example.greenframeocr.ui.DepositMenuScreen
import com.example.greenframeocr.ui.KaikakeTekiyouScreen
import com.example.greenframeocr.ui.MenuScreen
import com.example.greenframeocr.ui.MonthlySummaryScreen
import com.example.greenframeocr.ui.OcrCaptureScreen
import com.example.greenframeocr.ui.OcrLearningStatusScreen
import com.example.greenframeocr.ui.OutputConfirmScreen
import com.example.greenframeocr.ui.PassbookDataScreen
import com.example.greenframeocr.ui.ProductListScreen
import com.example.greenframeocr.ui.PurchaseMenuScreen
import com.example.greenframeocr.ui.RakurakuTekiyouScreen
import com.example.greenframeocr.ui.ReceiptInputScreen
import com.example.greenframeocr.ui.SettingsScreen
import com.example.greenframeocr.ui.SheetEditorScreen
import com.example.greenframeocr.ui.TekiyouMatchingScreen
import com.example.greenframeocr.ui.AccountSettingsScreen
import com.example.greenframeocr.ui.BookkeepingMenuScreen
import com.example.greenframeocr.ui.RakurakuAccountSettingsScreen
import com.example.greenframeocr.ui.YayoiAccountEditScreen
import com.example.greenframeocr.ui.YayoiAccountSettingsScreen
import com.example.greenframeocr.ui.GeneralPurchaseMenuScreen
import com.example.greenframeocr.ui.GeneralReceiptCaptureScreen
import com.example.greenframeocr.ui.GeneralReceiptConfirmScreen
import com.example.greenframeocr.ui.GeneralReceiptListScreen
import com.example.greenframeocr.ui.GeneralReceiptOutputScreen
import com.example.greenframeocr.ui.YearSummaryScreen
import com.example.greenframeocr.ui.YokinTekiyouScreen
import com.example.greenframeocr.viewmodel.GeneralReceiptViewModel
import com.example.greenframeocr.viewmodel.OcrCaptureViewModel
import com.example.greenframeocr.viewmodel.SheetEditorViewModel

/**
 * 画面の定義
 */
sealed class Screen(val route: String) {
    object Menu : Screen("menu")
    object PurchaseMenu : Screen("purchase_menu")
    object DepositMenu : Screen("deposit_menu")
    object OcrCapture : Screen("ocr_capture")
    object Settings : Screen("settings")
    object ReceiptInput : Screen("receipt_input")
    object OcrLearningStatus : Screen("ocr_learning_status")
    object ProductList : Screen("product_list")
    object KaikakeTekiyou : Screen("kaikake_tekiyou")
    object PurchaseOutputConfirm : Screen("purchase_output_confirm")
    object PassbookData : Screen("passbook_data")
    object TekiyouMatching : Screen("tekiyou_matching")
    object YokinTekiyou : Screen("yokin_tekiyou")
    object DepositOutputConfirm : Screen("deposit_output_confirm")
    object RakurakuTekiyou : Screen("rakuraku_tekiyou")
    object DebugCapture : Screen("debug_capture")
    object YearSummary : Screen("year_summary")
    object AccountSettings : Screen("account_settings?initialTab={initialTab}") {
        val route0 = "account_settings?initialTab=0"
        val route1 = "account_settings?initialTab=1"
    }
    object BookkeepingMenu : Screen("bookkeeping_menu")
    object YayoiAccountSettings : Screen("yayoi_account_settings")
    object RakurakuAccountSettings : Screen("rakuraku_account_settings")
    object YayoiAccountEdit : Screen("yayoi_account_edit/{accountId}?parentId={parentId}") {
        fun createRoute(accountId: Long, parentId: Long = -1L): String =
            "yayoi_account_edit/$accountId?parentId=$parentId"
    }
    object GeneralPurchaseMenu : Screen("general_purchase_menu")
    object GeneralReceiptCapture : Screen("general_receipt_capture")
    object GeneralReceiptConfirm : Screen("general_receipt_confirm")
    object GeneralReceiptList : Screen("general_receipt_list")
    object GeneralReceiptOutput : Screen("general_receipt_output")
    object MonthlySummary : Screen("monthly_summary/{year}/{month}") {
        fun createRoute(year: Int, month: Int): String {
            return "monthly_summary/$year/$month"
        }
    }
    object SheetEditor : Screen("sheet_editor/{year}/{month}/{sheetNumber}") {
        fun createRoute(year: Int, month: Int, sheetNumber: Int): String {
            return "sheet_editor/$year/$month/$sheetNumber"
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
    onNavigateToDebugCapture: (() -> Unit)? = null,
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
                },
                onNavigateToDebugCapture = {
                    navController.navigate(Screen.DebugCapture.route)
                }
            )
        }

        // デバッグ撮影画面
        composable(Screen.DebugCapture.route) {
            DebugCaptureScreen(
                onBack = { navController.popBackStack() }
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

        // OCR撮影画面
        composable(Screen.OcrCapture.route) {
            val viewModel: OcrCaptureViewModel = viewModel(
                factory = OcrCaptureViewModelFactory(
                    dao = dao,
                    issueYear = appPreferences.eraYear,
                    issueMonth = appPreferences.currentIssueMonth
                )
            )
            OcrCaptureScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onComplete = { year, month, sheetNumber ->
                    navController.navigate(
                        Screen.SheetEditor.createRoute(year, month, sheetNumber)
                    ) {
                        popUpTo(Screen.Menu.route)
                    }
                }
            )
        }

        // 伝票編集画面
        composable(
            route = Screen.SheetEditor.route,
            arguments = listOf(
                navArgument("year") { type = NavType.IntType },
                navArgument("month") { type = NavType.IntType },
                navArgument("sheetNumber") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val year = backStackEntry.arguments?.getInt("year") ?: 0
            val month = backStackEntry.arguments?.getInt("month") ?: 1
            val sheetNumber = backStackEntry.arguments?.getInt("sheetNumber") ?: 1

            val viewModel: SheetEditorViewModel = viewModel(
                factory = SheetEditorViewModelFactory(
                    dao = dao,
                    issueYear = year,
                    issueMonth = month,
                    sheetNumber = sheetNumber
                )
            )
            SheetEditorScreen(
                viewModel = viewModel,
                eraYear = appPreferences.eraYear,
                onBack = { navController.popBackStack() },
                onNavigateToPreviousSheet = {
                    if (sheetNumber > 1) {
                        navController.navigate(
                            Screen.SheetEditor.createRoute(year, month, sheetNumber - 1)
                        ) {
                            popUpTo(Screen.SheetEditor.route) { inclusive = true }
                        }
                    }
                },
                onNavigateToNextSheet = {
                    navController.navigate(
                        Screen.SheetEditor.createRoute(year, month, sheetNumber + 1)
                    ) {
                        popUpTo(Screen.SheetEditor.route) { inclusive = true }
                    }
                },
                onReOcr = {
                    // 再OCR: OCR撮影画面へ遷移
                    navController.navigate(Screen.OcrCapture.route)
                }
            )
        }

        // 設定画面
        composable(Screen.Settings.route) {
            SettingsScreen(
                appPreferences = appPreferences,
                onBack = { navController.popBackStack() },
                onThemeChanged = onThemeChanged,
                onNavigateToOcrLearningStatus = {
                    navController.navigate(Screen.OcrLearningStatus.route)
                },
                onNavigateToAccountSettings = {
                    navController.navigate(Screen.AccountSettings.route0)
                }
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
                }
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

        // 勘定科目設定画面（initialTab: 0=らくらく, 1=弥生）
        composable(
            route = Screen.AccountSettings.route,
            arguments = listOf(
                navArgument("initialTab") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { backStackEntry ->
            val initialTab = backStackEntry.arguments?.getInt("initialTab") ?: 0
            AccountSettingsScreen(
                database = database,
                onBack = { navController.popBackStack() },
                onNavigateToYayoiEdit = { accountId ->
                    navController.navigate(Screen.YayoiAccountEdit.createRoute(accountId))
                },
                initialTab = initialTab
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

        // OCR学習状況画面
        composable(Screen.OcrLearningStatus.route) {
            OcrLearningStatusScreen(
                database = database,
                onBack = { navController.popBackStack() }
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
                onNavigateToOutput = { navController.navigate(Screen.GeneralReceiptOutput.route) }
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
                onBack = { navController.popBackStack() }
            )
        }

        // 一般レシート確認・編集画面
        composable(Screen.GeneralReceiptConfirm.route) {
            GeneralReceiptConfirmScreen(
                viewModel = generalReceiptViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToList = {
                    navController.navigate(Screen.GeneralReceiptList.route) {
                        popUpTo(Screen.GeneralPurchaseMenu.route)
                    }
                }
            )
        }

        // 一般レシート一覧画面
        composable(Screen.GeneralReceiptList.route) {
            GeneralReceiptListScreen(
                viewModel = generalReceiptViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // 一般購買CSV出力画面
        composable(Screen.GeneralReceiptOutput.route) {
            GeneralReceiptOutputScreen(
                viewModel = generalReceiptViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // 伝票入力画面
        composable(Screen.ReceiptInput.route) {
            ReceiptInputScreen(
                eraYear = appPreferences.eraYear,
                database = database,
                onBack = { navController.popBackStack() },
                onCapture = {
                    navController.navigate(Screen.OcrCapture.route)
                },
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

/**
 * OcrCaptureViewModel用のFactory
 */
class OcrCaptureViewModelFactory(
    private val dao: ReceiptDao,
    private val issueYear: Int,
    private val issueMonth: Int
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OcrCaptureViewModel::class.java)) {
            return OcrCaptureViewModel(dao, issueYear, issueMonth) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * SheetEditorViewModel用のFactory
 */
class SheetEditorViewModelFactory(
    private val dao: ReceiptDao,
    private val issueYear: Int,
    private val issueMonth: Int,
    private val sheetNumber: Int
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SheetEditorViewModel::class.java)) {
            return SheetEditorViewModel(dao, issueYear, issueMonth, sheetNumber) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
