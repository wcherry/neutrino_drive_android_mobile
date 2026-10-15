package com.neutrino.drive.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.drive.core.drive.AppLink
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.graph
import com.neutrino.drive.ui.ConfirmDialog
import com.neutrino.drive.ui.FileOpener
import com.neutrino.drive.ui.Route
import com.neutrino.drive.ui.TextInputDialog
import com.neutrino.drive.ui.theme.Brand

/**
 * An item's actions — the iOS context menu and swipe actions in one sheet. Which actions appear
 * depends on where the item is listed, as there: My Drive can rename, move and trash; Trash can
 * restore and delete forever; everything can be starred, shared and opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionsSheet(item: DriveItem, place: Place, vm: DriveViewModel, navigate: (Route) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    var rename by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(true) }
    val offline = remember { graph.offline.isOffline(item.id) }

    fun done() {
        sheet = false
        if (!rename && !move && !confirmForever) onDismiss()
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false; onDismiss() }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(item)
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                }
                HorizontalDivider()
                if (item.isTrashed) {
                    Action(Icons.Filled.Restore, "Restore") { vm.restore(item); done() }
                    Action(Icons.Filled.DeleteForever, "Delete forever", destructive = true) { sheet = false; confirmForever = true }
                    return@Column
                }
                if (!item.isFolder) {
                    Action(Icons.Filled.Visibility, "Open") { done(); openItem(item, vm, navigate, context) }
                    Action(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") {
                        done(); vm.fetch(item) { FileOpener.view(context, it, item.mimeType) }
                    }
                    Action(Icons.AutoMirrored.Filled.Send, "Send a copy") {
                        done(); vm.fetch(item) { FileOpener.send(context, it, item.mimeType) }
                    }
                    Action(Icons.Filled.DownloadForOffline, if (offline) "Remove offline copy" else "Make available offline") {
                        done()
                        if (offline) graph.offline.remove(item.id) else vm.makeOffline(item)
                    }
                }
                Action(if (item.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder, if (item.isStarred) "Remove star" else "Add star") {
                    vm.setStarred(item, !item.isStarred, place); done()
                }
                Action(Icons.Filled.PersonAdd, "Share…") { done(); navigate(Route.Share(item)) }
                if (!item.isFolder) {
                    Action(Icons.Filled.History, "Version history") { done(); navigate(Route.Versions(item)) }
                    Action(Icons.Filled.Link, "Copy app link") {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(item.name, AppLink.url(item)))
                        Toast.makeText(context, "Link copied. It opens the file for anyone who already has access.", Toast.LENGTH_SHORT).show()
                        done()
                    }
                }
                if (place.isMyDrive) {
                    Action(Icons.Filled.Edit, "Rename") { sheet = false; rename = true }
                    Action(Icons.AutoMirrored.Filled.DriveFileMove, "Move") { sheet = false; move = true }
                    Action(Icons.Filled.Delete, "Move to Trash", destructive = true) { vm.trash(item, place); done() }
                }
            }
        }
    }
    if (rename) {
        TextInputDialog("Rename", item.name, "Name", "Rename", onDismiss = { rename = false; onDismiss() }) {
            rename = false
            onDismiss()
            if (it != item.name) vm.rename(item, it, place)
        }
    }
    if (move) {
        MoveDialog(item, onDismiss = { move = false; onDismiss() }) { target ->
            move = false
            onDismiss()
            vm.move(item, target, place)
        }
    }
    if (confirmForever) {
        ConfirmDialog(
            "Delete forever?", "“${item.name}” will be permanently deleted. This cannot be undone.", "Delete forever",
            destructive = true, onDismiss = { confirmForever = false; onDismiss() },
        ) { vm.deleteForever(item) }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, null, tint = color) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Pick a destination folder by browsing from My Drive — iOS's `MoveSheet`. A folder cannot be
 * moved into itself or anything under it; the server refuses, and so does this list.
 */
@Composable
fun MoveDialog(item: DriveItem, onDismiss: () -> Unit, onMove: (Place.Folder) -> Unit) {
    val graph = LocalContext.current.graph
    var path by remember { mutableStateOf(listOf(Place.root)) }
    var folders by remember { mutableStateOf<List<DriveItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val here = path.last()

    LaunchedEffect(here.key) {
        folders = null
        error = null
        runCatching { graph.api.folder(here.id).folders.map { DriveItem.of(it) }.sortedBy { it.name.lowercase() } }
            .onSuccess { list -> folders = list.filter { it.id != item.id } }
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (path.size > 1) IconButton(onClick = { path = path.dropLast(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                Text("Move to ${here.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                folders == null -> CircularProgressIndicator()
                folders!!.isEmpty() -> Text("No folders here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.heightIn(max = 360.dp).fillMaxWidth()) {
                    items(folders!!, key = { it.id }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Filled.Folder, null, tint = Brand.folder) },
                            modifier = Modifier.clickable { path = path + Place.Folder(f.id, f.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(here) }, enabled = here.id != item.parentId) { Text("Move here") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
