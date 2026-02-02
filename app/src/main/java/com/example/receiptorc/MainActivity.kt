package com.example.receiptorc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.example.receiptorc.data.AppPreferences
import com.example.receiptorc.data.DatabaseInitializer
import com.example.receiptorc.data.ReceiptDatabase
import com.example.receiptorc.navigation.ReceiptNavGraph
import com.example.receiptorc.navigation.Screen
import com.example.receiptorc.ui.theme.ReceiptOCRTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var appPreferences: AppPreferences

    // 共有されたCSVのURI
    private val sharedCsvUri = mutableStateOf<Uri?>(null)

    // カメラ権限リクエストランチャー
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 権限が許可された
            android.util.Log.d("MainActivity", "Camera permission granted")
        } else {
            // 権限が拒否された
            android.util.Log.w("MainActivity", "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // カメラ権限をチェックしてリクエスト
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // 権限が既に許可されている
                android.util.Log.d("MainActivity", "Camera permission already granted")
            }
            else -> {
                // 権限をリクエスト
                android.util.Log.d("MainActivity", "Requesting camera permission")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // 設定管理の初期化
        appPreferences = AppPreferences(this)

        // データベースの初期化
        val database = ReceiptDatabase.getDatabase(this)
        val dao = database.receiptDao()

        // 辞書データベースの初期化（CSVインポート）
        lifecycleScope.launch {
            try {
                DatabaseInitializer.initializeIfNeeded(this@MainActivity, database)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to initialize dictionary database", e)
            }
        }

        // 外部アプリからの共有インテントを処理
        handleIntent(intent)

        setContent {
            ReceiptOCRTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    // 共有CSVがある場合は通帳データ画面から開始
                    val startDestination = if (sharedCsvUri.value != null) {
                        Screen.PassbookData.route
                    } else {
                        Screen.Menu.route
                    }

                    ReceiptNavGraph(
                        navController = navController,
                        appPreferences = appPreferences,
                        dao = dao,
                        database = database,
                        startDestination = startDestination,
                        sharedCsvUri = sharedCsvUri.value
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 共有インテントを処理してCSV URIを取得
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        android.util.Log.d("MainActivity", "handleIntent: action=$action, type=$type")

        when (action) {
            Intent.ACTION_SEND -> {
                // 共有で受け取った場合
                if (type != null && isTextOrCsvType(type)) {
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        android.util.Log.d("MainActivity", "Received shared CSV: $uri")
                        sharedCsvUri.value = uri
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                // ファイルを直接開いた場合
                val uri = intent.data
                if (uri != null) {
                    android.util.Log.d("MainActivity", "Received VIEW intent: $uri")
                    sharedCsvUri.value = uri
                }
            }
        }
    }

    /**
     * MIMEタイプがCSVまたはテキストかどうかを判定
     */
    private fun isTextOrCsvType(type: String): Boolean {
        return type == "text/csv" ||
                type == "text/comma-separated-values" ||
                type == "text/plain" ||
                type == "application/octet-stream" ||
                type.startsWith("text/")
    }
}
