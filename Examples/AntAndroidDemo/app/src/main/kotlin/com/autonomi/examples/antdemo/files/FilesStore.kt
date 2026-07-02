package com.autonomi.examples.antdemo.files

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.autonomi.examples.antdemo.wallet.EthCalldata
import com.autonomi.examples.antdemo.wallet.WalletConnectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.ant_ffi.Client
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/// Backing store for the Files screen — the mobile analogue of the desktop
/// app's files store (`ant-ui/stores/files.ts`). Holds the Uploads and
/// Downloads lists as Compose state and drives real network operations
/// through the bundled AntFfi AAR (devnet-backed for now; the external-signer
/// prepare/finalize flow slots in once that AAR ships — PR #199).
object FilesStore {
    /// Devnet manifest pushed to the device (see README wiring step).
    private const val MANIFEST_PATH = "/data/local/tmp/devnet-manifest.json"

    val uploads = mutableStateListOf<FileEntry>()
    val downloads = mutableStateListOf<FileEntry>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(1)

    /// Populate the lists with realistic sample rows so the UI looks lived-in
    /// without a live network. Idempotent; call once on launch.
    fun seedMockData() {
        if (uploads.isNotEmpty() || downloads.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val min = 60_000L
        val addr = "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00"
        uploads.addAll(
            listOf(
                FileEntry(ids.getAndIncrement(), FileKind.Upload, "backup.zip", 15_728_640,
                    FileStatus.Uploading, now - 30_000, cost = "42 chunk(s) · auto", progress = null),
                FileEntry(ids.getAndIncrement(), FileKind.Upload, "vacation.jpg", 2_411_724,
                    FileStatus.Complete, now - 12 * min, address = addr, cost = "6 chunk(s) · auto"),
                FileEntry(ids.getAndIncrement(), FileKind.Upload, "quarterly-report.pdf", 842_133,
                    FileStatus.Complete, now - 55 * min, address = "9f8e7d6c5b4a39281706f5e4d3c2b1a0" +
                        "0f1e2d3c4b5a69788796a5b4c3d2e1f0", cost = "3 chunk(s) · auto"),
                FileEntry(ids.getAndIncrement(), FileKind.Upload, "notes.txt", 1_204,
                    FileStatus.Failed, now - 70 * min, error = "Network unavailable"),
            ),
        )
        downloads.addAll(
            listOf(
                FileEntry(ids.getAndIncrement(), FileKind.Download, "download-a1b2c3d4e5…", 2_411_724,
                    FileStatus.Downloaded, now - 8 * min, address = addr,
                    savedTo = "/…/files/downloads/download-a1b2c3d4e5f60718.bin"),
                FileEntry(ids.getAndIncrement(), FileKind.Download, "download-77aa88bb…", 0,
                    FileStatus.Downloading, now - 20_000, address = "77aa88bb"),
            ),
        )
    }

    // Clients are connected on first use and reused. Two flavours:
    //  - walletClient: manifest wallet attached (devnet single-shot fallback).
    //  - esClient: no wallet — for the external-signer flow (works with the
    //    Sepolia devnet, whose manifest has no key).
    private val clientLock = Mutex()
    @Volatile private var walletClient: Client? = null
    @Volatile private var esClient: Client? = null

    private suspend fun client(): Client = clientLock.withLock {
        walletClient ?: Client.connectFromDevnetManifest(MANIFEST_PATH).also { walletClient = it }
    }

    private suspend fun externalSignerClient(): Client = clientLock.withLock {
        esClient ?: Client.connectFromDevnetManifestExternalSigner(MANIFEST_PATH).also { esClient = it }
    }

    // ---- Uploads ----

    /// Upload raw bytes (picked from a content Uri) as public data. If a wallet
    /// is connected, uses the external-signer flow (prepare → wallet signs
    /// approve + payForQuotes → finalize); otherwise falls back to the
    /// devnet-wallet single-shot put.
    fun upload(name: String, bytes: ByteArray, context: Context) {
        val id = ids.getAndIncrement()
        uploads.add(
            0,
            FileEntry(
                id = id, kind = FileKind.Upload, name = name, sizeBytes = bytes.size.toLong(),
                status = FileStatus.Quoting, createdAt = System.currentTimeMillis(),
            ),
        )
        val walletConnected = WalletConnectManager.state.value.address != null
        scope.launch {
            try {
                if (walletConnected) externalSignerUpload(id, bytes, context)
                else devnetUpload(id, bytes)
            } catch (e: Throwable) {
                setUpload(id) { it.copy(status = FileStatus.Failed, error = e.message, progress = null) }
            }
        }
    }

    /// External-signer flow: the connected wallet pays; no key on device.
    private suspend fun externalSignerUpload(id: Long, bytes: ByteArray, context: Context) {
        val evm = parseManifestEvm(context)
        val c = externalSignerClient()

        // Phase 1 — prepare: encrypt + collect quotes (status: Quoting).
        val prepared = withContext(Dispatchers.IO) { c.prepareDataUpload(bytes, "public") }

        if (prepared.alreadyStored) {
            val result = withContext(Dispatchers.IO) { c.finalizeUpload(prepared.uploadId, emptyMap()) }
            setUpload(id) {
                it.copy(status = FileStatus.Complete, address = result.address ?: prepared.dataMapAddress,
                    cost = "already stored", progress = null)
            }
            return
        }

        // Phase 2 — the wallet signs the payment (approve + payForQuotes).
        setUpload(id) { it.copy(status = FileStatus.AwaitingApproval, cost = "${prepared.payments.size} quote(s) · ${prepared.totalAmount} atto") }
        withContext(Dispatchers.Main) {
            WalletConnectManager.sendTransaction(
                to = evm.tokenAddress,
                data = EthCalldata.approve(evm.vaultAddress, prepared.totalAmount),
            )
        }

        setUpload(id) { it.copy(status = FileStatus.Paying) }
        val quotePayments = prepared.payments.map {
            EthCalldata.QuotePayment(rewardsAddress = it.rewardsAddress, amount = it.amount, quoteHash = it.quoteHash)
        }
        val payTx = withContext(Dispatchers.Main) {
            WalletConnectManager.sendTransaction(
                to = evm.vaultAddress,
                data = EthCalldata.payForQuotes(quotePayments),
            )
        }

        // Phase 3 — finalize: store the chunks with the payment tx hashes.
        setUpload(id) { it.copy(status = FileStatus.Uploading, progress = null) }
        val txHashes = prepared.payments.associate { it.quoteHash to payTx }
        val result = withContext(Dispatchers.IO) { c.finalizeUpload(prepared.uploadId, txHashes) }
        setUpload(id) {
            it.copy(status = FileStatus.Complete, address = result.address ?: prepared.dataMapAddress,
                cost = "${result.chunksStored} chunk(s) · gas ${result.gasCostWei}", progress = null)
        }
    }

    /// Devnet fallback: the manifest wallet pays inside ant-core (single-shot).
    private suspend fun devnetUpload(id: Long, bytes: ByteArray) {
        val c = client()
        setUpload(id) { it.copy(status = FileStatus.Uploading, progress = null) }
        val result = withContext(Dispatchers.IO) { c.dataPutPublic(bytes, "auto") }
        setUpload(id) {
            it.copy(status = FileStatus.Complete, address = result.address,
                cost = "${result.chunksStored} chunk(s) · ${result.paymentModeUsed}", progress = null)
        }
    }

    /// EVM contract addresses read from the on-device devnet manifest — needed
    /// to build the approve + payForQuotes calldata the wallet signs.
    private data class DevnetEvm(val rpcUrl: String, val tokenAddress: String, val vaultAddress: String)

    private fun parseManifestEvm(context: Context): DevnetEvm {
        val json = JSONObject(File(MANIFEST_PATH).readText())
        val evm = json.getJSONObject("evm")
        return DevnetEvm(
            rpcUrl = evm.getString("rpc_url"),
            tokenAddress = evm.getString("payment_token_address"),
            vaultAddress = evm.getString("payment_vault_address"),
        )
    }

    // ---- Downloads ----

    /// Download public data by hex address and save it into the app's
    /// downloads dir. `suggestedName` (e.g. from an `autonomi://` deep link)
    /// is used for the row label and the saved file name when provided.
    fun download(addressHex: String, context: Context, suggestedName: String? = null) {
        val addr = addressHex.trim()
        val id = ids.getAndIncrement()
        val shortAddr = if (addr.length > 10) "${addr.take(10)}…" else addr
        val rowName = suggestedName ?: "download-$shortAddr"
        val fileName = suggestedName ?: "download-${addr.take(16)}.bin"
        downloads.add(
            0,
            FileEntry(
                id = id, kind = FileKind.Download, name = rowName, sizeBytes = 0,
                status = FileStatus.Downloading, createdAt = System.currentTimeMillis(), address = addr,
            ),
        )
        scope.launch {
            try {
                // Downloads read from either client; prefer whichever is connected.
                val c = esClient ?: walletClient ?: client()
                val data = withContext(Dispatchers.IO) { c.dataGetPublic(addr) }
                val dir = File(context.filesDir, "downloads").apply { mkdirs() }
                val out = File(dir, fileName)
                out.writeBytes(data)
                setDownload(id) {
                    it.copy(
                        status = FileStatus.Downloaded,
                        sizeBytes = data.size.toLong(),
                        savedTo = out.absolutePath,
                    )
                }
            } catch (e: Throwable) {
                setDownload(id) { it.copy(status = FileStatus.Failed, error = e.message) }
            }
        }
    }

    // ---- mutation helpers (replace-by-id keeps the list observable) ----

    private fun setUpload(id: Long, transform: (FileEntry) -> FileEntry) =
        replace(uploads, id, transform)

    private fun setDownload(id: Long, transform: (FileEntry) -> FileEntry) =
        replace(downloads, id, transform)

    private fun replace(
        list: MutableList<FileEntry>,
        id: Long,
        transform: (FileEntry) -> FileEntry,
    ) {
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = transform(list[idx])
    }
}
