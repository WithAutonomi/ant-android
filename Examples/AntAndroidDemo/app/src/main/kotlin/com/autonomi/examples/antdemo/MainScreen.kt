package com.autonomi.examples.antdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.autonomi.examples.antdemo.wallet.AutonomiChain
import com.autonomi.examples.antdemo.wallet.WalletConnectManager
import com.google.accompanist.navigation.material.ExperimentalMaterialNavigationApi
import com.google.accompanist.navigation.material.ModalBottomSheetLayout
import com.google.accompanist.navigation.material.rememberBottomSheetNavigator
import com.reown.appkit.ui.appKitGraph
import com.reown.appkit.ui.components.button.Web3Button
import com.reown.appkit.ui.components.button.rememberAppKitState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ant_ffi.Client
import kotlin.random.Random

/// Path the rewritten devnet manifest is pushed to on the emulator:
///   adb push /tmp/devnet-manifest-android.json /data/local/tmp/devnet-manifest.json
/// See README for the rewrite step (127.0.0.1 -> 10.0.2.2).
private const val MANIFEST_PATH = "/data/local/tmp/devnet-manifest.json"

/// App root: hosts the nav graph the Reown AppKit modal plugs into. appkit
/// 1.4.1's `appKitGraph` presents the modal via Accompanist's bottom-sheet
/// navigation, so the NavController must register Accompanist's
/// BottomSheetNavigator and be wrapped in its ModalBottomSheetLayout.
@OptIn(ExperimentalMaterialNavigationApi::class)
@Composable
fun AppRoot() {
    val bottomSheetNavigator = rememberBottomSheetNavigator()
    val navController = rememberNavController(bottomSheetNavigator)

    ModalBottomSheetLayout(bottomSheetNavigator = bottomSheetNavigator) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") { MainScreen(navController) }
            appKitGraph(navController)
        }
    }
}

@Composable
fun MainScreen(navController: NavController) {
    var inputText by remember { mutableStateOf("hello autonomi") }
    var addressInput by remember { mutableStateOf("") }
    var lastUploadedAddress by remember { mutableStateOf("") }
    var downloadedText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Idle. Push a devnet manifest, then tap Upload.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // WalletConnect spike state.
    val wallet by WalletConnectManager.state.collectAsState()
    val appKitState = rememberAppKitState(navController = navController)

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AntFfi Demo", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Text to upload") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = !busy && inputText.isNotEmpty(),
            onClick = {
                scope.launch {
                    busy = true
                    status = "Connecting to devnet…"
                    try {
                        val client = withContext(Dispatchers.IO) {
                            Client.connectFromDevnetManifest(MANIFEST_PATH)
                        }
                        // Random suffix so successive taps produce distinct chunks
                        // despite content-addressed storage.
                        val suffix = Random.nextInt().toUInt().toString(36)
                        val payload = "$inputText [$suffix]".toByteArray()
                        status = "Uploading ${payload.size} bytes…"
                        val result = withContext(Dispatchers.IO) { client.chunkPut(payload) }
                        lastUploadedAddress = result.address
                        status = "Uploaded. Tap Download or copy the address."
                    } catch (e: Throwable) {
                        status = "Upload failed: ${e.message}"
                    } finally { busy = false }
                }
            }
        ) { Text("Upload (appends random suffix)") }

        if (lastUploadedAddress.isNotEmpty()) {
            SelectionContainer {
                Text(
                    "Address: $lastUploadedAddress",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedTextField(
            value = addressInput,
            onValueChange = { addressInput = it },
            label = { Text("Address (hex)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && addressInput.isNotEmpty(),
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Connecting to devnet…"
                        try {
                            val client = withContext(Dispatchers.IO) {
                                Client.connectFromDevnetManifest(MANIFEST_PATH)
                            }
                            status = "Downloading…"
                            val data = withContext(Dispatchers.IO) { client.chunkGet(addressInput) }
                            downloadedText = runCatching { String(data, Charsets.UTF_8) }
                                .getOrDefault("<${data.size} non-UTF8 bytes>")
                            status = "Downloaded ${data.size} bytes."
                        } catch (e: Throwable) {
                            status = "Download failed: ${e.message}"
                        } finally { busy = false }
                    }
                }
            ) { Text("Download") }
            Button(
                enabled = lastUploadedAddress.isNotEmpty(),
                onClick = { addressInput = lastUploadedAddress }
            ) { Text("Use last") }
        }

        if (downloadedText.isNotEmpty()) {
            SelectionContainer { Text("Content: $downloadedText") }
        }

        // ---- WalletConnect spike ----
        // Web3Button handles connect + account display (opening the AppKit modal
        // via appKitGraph). Once connected, "Send test approve tx" has the wallet
        // sign a real payment-vault `approve` (amount 0 → gas only, no balance).
        // On a physical device this completes the signature end-to-end.
        Text("Wallet (WalletConnect spike)", style = MaterialTheme.typography.titleSmall)
        Web3Button(state = appKitState)
        if (wallet.address != null) {
            Text("Chain: ${wallet.chainId ?: "?"}", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            WalletConnectManager.sendApprove(AutonomiChain.ARBITRUM_ONE, "0")
                        } catch (e: Throwable) {
                            status = "Approve failed: ${e.message}"
                        } finally { busy = false }
                    }
                }
            ) { Text("Send test approve tx (Arbitrum One)") }
        }
        wallet.lastTxHash?.let { hash ->
            SelectionContainer {
                Text("tx: $hash", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(wallet.status, style = MaterialTheme.typography.bodySmall)
        if (busy) { CircularProgressIndicator() }
    }
}
