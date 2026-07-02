package com.autonomi.examples.antdemo.files

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // A single client is connected on first use and reused across operations.
    private val clientLock = Mutex()
    @Volatile private var client: Client? = null

    private suspend fun client(): Client = clientLock.withLock {
        client ?: Client.connectFromDevnetManifest(MANIFEST_PATH).also { client = it }
    }

    // ---- Uploads ----

    /// Upload raw bytes (picked from a content Uri) as public data.
    fun upload(name: String, bytes: ByteArray) {
        val id = ids.getAndIncrement()
        uploads.add(
            0,
            FileEntry(
                id = id, kind = FileKind.Upload, name = name, sizeBytes = bytes.size.toLong(),
                status = FileStatus.Quoting, createdAt = System.currentTimeMillis(),
            ),
        )
        scope.launch {
            try {
                val c = client()
                setUpload(id) { it.copy(status = FileStatus.Uploading, progress = null) }
                val result = withContext(Dispatchers.IO) { c.dataPutPublic(bytes, "auto") }
                setUpload(id) {
                    it.copy(
                        status = FileStatus.Complete,
                        address = result.address,
                        cost = "${result.chunksStored} chunk(s) · ${result.paymentModeUsed}",
                        progress = null,
                    )
                }
            } catch (e: Throwable) {
                setUpload(id) { it.copy(status = FileStatus.Failed, error = e.message, progress = null) }
            }
        }
    }

    // ---- Downloads ----

    /// Download public data by hex address and save it into the app's
    /// downloads dir.
    fun download(addressHex: String, context: Context) {
        val addr = addressHex.trim()
        val id = ids.getAndIncrement()
        val shortAddr = if (addr.length > 10) "${addr.take(10)}…" else addr
        downloads.add(
            0,
            FileEntry(
                id = id, kind = FileKind.Download, name = "download-$shortAddr", sizeBytes = 0,
                status = FileStatus.Downloading, createdAt = System.currentTimeMillis(), address = addr,
            ),
        )
        scope.launch {
            try {
                val c = client()
                val data = withContext(Dispatchers.IO) { c.dataGetPublic(addr) }
                val dir = File(context.filesDir, "downloads").apply { mkdirs() }
                val out = File(dir, "download-${addr.take(16)}.bin")
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
