package com.autonomi.examples.antdemo.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/// Uploads tab — the mobile analogue of the Uploads half of the desktop file
/// manager (`ant-ui/pages/files.vue`). List of upload rows + a file picker.
@Composable
fun UploadsScreen() {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) FilesStore.upload(name, bytes)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Button(onClick = { picker.launch("*/*") }) { Text("Upload a file") }
        }
        if (FilesStore.uploads.isEmpty()) {
            item { EmptyState("No uploads yet", "Tap “Upload a file” to store data on the network") }
        } else {
            items(FilesStore.uploads, key = { it.id }) { FileRow(it) }
        }
    }
}

/// Downloads tab — the Downloads half: retrieve by content address, list of
/// download rows.
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    var address by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address (hex)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = address.isNotBlank(),
                onClick = { FilesStore.download(address, context); address = "" },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("Download by address") }
        }
        if (FilesStore.downloads.isEmpty()) {
            item { EmptyState("No downloads yet", "Paste a content address above to retrieve it") }
        } else {
            items(FilesStore.downloads, key = { it.id }) { FileRow(it) }
        }
    }
}

// ---- shared row rendering ----

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileRow(entry: FileEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBadge(entry)
            if (entry.sizeBytes > 0) {
                Text(formatSize(entry.sizeBytes), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatDate(entry.createdAt), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.status.inProgress) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        entry.cost?.let {
            Text("Cost: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entry.address?.takeIf { entry.kind == FileKind.Upload }?.let { addr ->
            SelectionContainer {
                Text(addr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        entry.savedTo?.let {
            Text("Saved to: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusBadge(entry: FileEntry) {
    val (bg, fg) = when (entry.status) {
        FileStatus.Complete, FileStatus.Downloaded -> Color(0xFF1B5E20) to Color.White
        FileStatus.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    Text(
        entry.statusText(),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/// Resolve a human-readable file name from a content Uri.
private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx)?.let { return it }
        }
    }
    return uri.lastPathSegment ?: "file"
}
