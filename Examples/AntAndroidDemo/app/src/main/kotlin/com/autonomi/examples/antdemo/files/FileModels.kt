package com.autonomi.examples.antdemo.files

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// Lifecycle states mirroring the desktop app's file manager
/// (ant-ui `pages/files.vue` statusLabel). Uploads progress
/// quoting → (awaiting approval / paying) → uploading → complete;
/// downloads downloading → downloaded. Either can end in failed.
enum class FileStatus(val label: String) {
    Quoting("Quoting"),
    AwaitingApproval("Awaiting approval"),
    Paying("Paying"),
    Uploading("Uploading"),
    Complete("Complete"),
    Downloading("Downloading"),
    Downloaded("Downloaded"),
    Failed("Failed"),
}

val FileStatus.inProgress: Boolean
    get() = this == FileStatus.Quoting || this == FileStatus.AwaitingApproval ||
        this == FileStatus.Paying || this == FileStatus.Uploading || this == FileStatus.Downloading

enum class FileKind { Upload, Download }

/// One row in the Uploads or Downloads list — the mobile analogue of a
/// desktop table row (name / status / size / cost|saved-to / date).
data class FileEntry(
    val id: Long,
    val kind: FileKind,
    val name: String,
    val sizeBytes: Long,
    val status: FileStatus,
    val createdAt: Long,
    /// Content address (hex) once uploaded, or the address used to download.
    val address: String? = null,
    /// Storage cost summary (e.g. chunk count / atto). Blank until known.
    val cost: String? = null,
    /// Where a downloaded file was written on device.
    val savedTo: String? = null,
    /// 0.0–1.0 while in progress, or null for indeterminate.
    val progress: Float? = null,
    val error: String? = null,
)

/// Human-readable status text, matching the desktop's statusLabel.
fun FileEntry.statusText(): String = when (status) {
    FileStatus.Failed -> error?.let { "Failed: $it" } ?: "Failed"
    else -> status.label
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024; i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}

private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
fun formatDate(epochMillis: Long): String = dateFmt.format(Date(epochMillis))
