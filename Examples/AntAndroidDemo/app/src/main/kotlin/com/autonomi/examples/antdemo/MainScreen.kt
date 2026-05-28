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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ant_ffi.Client
import java.io.File
import kotlin.random.Random

/// Path the rewritten devnet manifest is pushed to on the emulator:
///   adb push /tmp/devnet-manifest-android.json /data/local/tmp/devnet-manifest.json
/// See README for the rewrite step (127.0.0.1 -> 10.0.2.2).
private const val MANIFEST_PATH = "/data/local/tmp/devnet-manifest.json"

@Composable
fun MainScreen() {
    var inputText by remember { mutableStateOf("hello autonomi") }
    var addressInput by remember { mutableStateOf("") }
    var lastUploadedAddress by remember { mutableStateOf("") }
    var downloadedText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Idle. Push a devnet manifest, then tap Upload.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

        Text(status, style = MaterialTheme.typography.bodySmall)
        if (busy) { CircularProgressIndicator() }
    }
}
