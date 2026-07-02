package com.autonomi.examples.antdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.autonomi.examples.antdemo.files.FilesStore
import com.autonomi.examples.antdemo.ui.AntTheme
import com.autonomi.examples.antdemo.ui.AppShell
import com.autonomi.examples.antdemo.ui.ThemeController
import com.autonomi.examples.antdemo.wallet.WalletConnectManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AntFfiBootstrap.plantOnce(applicationContext)
        ThemeController.load(applicationContext)
        FilesStore.seedMockData()
        // WalletConnect: dedicated projectId from https://dashboard.reown.com.
        WalletConnectManager.configure(application, REOWN_PROJECT_ID)
        setContent {
            AntTheme {
                Surface { AppShell() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The wallet may have approved the session while we were backgrounded.
        WalletConnectManager.refreshAccount()
    }

    companion object {
        // Dedicated Reown (WalletConnect Cloud) projectId for the mobile SDK.
        // projectIds are public client identifiers (shipped in apps), not secrets.
        private const val REOWN_PROJECT_ID = "2cd5b44944e27d5234557a9183dc1cdd"
    }
}
