package com.example.receiptorc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.example.receiptorc.data.AppPreferences
import com.example.receiptorc.data.DatabaseInitializer
import com.example.receiptorc.data.ReceiptDatabase
import com.example.receiptorc.navigation.ReceiptNavGraph
import com.example.receiptorc.ui.theme.ReceiptOCRTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var appPreferences: AppPreferences

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

        setContent {
            ReceiptOCRTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    ReceiptNavGraph(
                        navController = navController,
                        appPreferences = appPreferences,
                        dao = dao,
                        database = database
                    )
                }
            }
        }
    }
}
