package com.example.receiptorc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.receiptorc.data.AppPreferences
import com.example.receiptorc.data.ReceiptDatabase
import com.example.receiptorc.navigation.ReceiptNavGraph
import com.example.receiptorc.ui.theme.ReceiptOCRTheme

class MainActivity : ComponentActivity() {
    private lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 設定管理の初期化
        appPreferences = AppPreferences(this)

        // データベースの初期化
        val database = ReceiptDatabase.getDatabase(this)
        val dao = database.receiptDao()

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
                        dao = dao
                    )
                }
            }
        }
    }
}
