package com.autonomi.examples.antdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.autonomi.examples.antdemo.wallet.WalletConnectManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AntFfiBootstrap.plantOnce(applicationContext)
        // WalletConnect spike: paste a projectId from https://dashboard.reown.com.
        WalletConnectManager.configure(application, REOWN_PROJECT_ID)
        setContent {
            MaterialTheme {
                Surface { AppRoot() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The wallet may have approved the session while we were backgrounded.
        WalletConnectManager.refreshAccount()
    }

    companion object {
        private const val REOWN_PROJECT_ID = "REPLACE_WITH_REOWN_PROJECT_ID"
    }
}
