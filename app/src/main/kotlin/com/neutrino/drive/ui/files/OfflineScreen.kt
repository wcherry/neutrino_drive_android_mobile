package com.neutrino.drive.ui.files

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.graph
import com.neutrino.drive.ui.EmptyState
import com.neutrino.drive.ui.FileOpener
import java.time.Instant

/** Files kept on this phone, openable with no connection — iOS's `OfflineView`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen() {
    val context = LocalContext.current
    val offline = context.graph.offline
    val files by offline.files.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Offline") }) }) { padding ->
        if (files.isEmpty()) {
            EmptyState(
                Icons.Filled.DownloadForOffline, "No offline files",
                "Choose “Make available offline” on a file to keep a copy here that opens without a connection.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(files, key = { it.id }) { f ->
                    val item = DriveItem(
                        id = f.id, name = f.name, isFolder = false, size = f.sizeBytes,
                        modifiedAt = Instant.ofEpochMilli(f.cachedAtMillis), mimeType = f.mimeType,
                    )
                    FileRow(item, onClick = { FileOpener.view(context, offline.file(f), f.mimeType) }) {
                        IconButton(onClick = { offline.remove(f.id) }) { Icon(Icons.Filled.Close, "Remove offline copy of ${f.name}") }
                    }
                }
            }
        }
    }
}
