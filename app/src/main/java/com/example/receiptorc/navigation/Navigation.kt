package com.example.receiptorc.navigation

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
import com.example.receiptorc.data.AppPreferences
import com.example.receiptorc.data.ReceiptDao
import com.example.receiptorc.ui.DepositMenuScreen
import com.example.receiptorc.ui.KaikakeTekiyouScreen
import com.example.receiptorc.ui.MenuScreen
import com.example.receiptorc.ui.MonthlySummaryScreen
import com.example.receiptorc.ui.OcrCaptureScreen
import com.example.receiptorc.ui.OcrLearningStatusScreen
import com.example.receiptorc.ui.OutputConfirmScreen
import com.example.receiptorc.ui.PassbookDataScreen
import com.example.receiptorc.ui.ProductListScreen
import com.example.receiptorc.ui.PurchaseMenuScreen
import com.example.receiptorc.ui.RakurakuTekiyouScreen
import com.example.receiptorc.ui.ReceiptInputScreen
import com.example.receiptorc.ui.SettingsScreen
import com.example.receiptorc.ui.SheetEditorScreen
import com.example.receiptorc.ui.TekiyouMatchingScreen
import com.example.receiptorc.ui.YokinTekiyouScreen
import com.example.receiptorc.viewmodel.OcrCaptureViewModel
import com.example.receiptorc.viewmodel.SheetEditorViewModel

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
    database: com.example.receiptorc.data.ReceiptDatabase,
    startDestination: String = Screen.Menu.route,
    sharedCsvUri: Uri? = null
) {
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
                onNavigateToProductList = {
                    navController.navigate(Screen.ProductList.route)
                },
                onNavigateToKaikakeTekiyou = {
                    navController.navigate(Screen.KaikakeTekiyou.route)
                },
                onNavigateToOutputConfirm = {
                    navController.navigate(Screen.PurchaseOutputConfirm.route)
                }
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
                onNavigateToYokinTekiyou = {
                    navController.navigate(Screen.YokinTekiyou.route)
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
                onNavigateToOcrLearningStatus = {
                    navController.navigate(Screen.OcrLearningStatus.route)
                }
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
                    // TODO: カメラ画面への遷移
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
