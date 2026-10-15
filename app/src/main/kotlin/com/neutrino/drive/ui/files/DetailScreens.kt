package com.neutrino.drive.ui.files

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.neutrino.drive.core.api.DriveDate
import com.neutrino.drive.core.api.FileVersionDto
import com.neutrino.drive.core.api.PermissionDto
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.core.drive.ShareRole
import com.neutrino.drive.core.drive.SharePartialException
import com.neutrino.drive.graph
import com.neutrino.drive.ui.ConfirmDialog
import com.neutrino.drive.ui.EmptyState
import com.neutrino.drive.ui.FileOpener
import com.neutrino.drive.ui.SectionHeader
import com.neutrino.drive.ui.formatBytes
import com.neutrino.drive.ui.relative
import com.neutrino.drive.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

// ── Sharing ─────────────────────────────────────────────────────────────────────────────────────

/**
 * People with access, adding someone, and the share link — iOS's `ShareSheet`. Adding a person to
 * a file re-seals its key to theirs ([com.neutrino.drive.core.drive.SharingService]); when that
 * part cannot happen yet, the permission stays and the screen says why.
 */
@Composable
fun ShareScreen(item: DriveItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val sharing = context.graph.sharing
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<PermissionDto>?>(null) }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ShareRole.VIEWER) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<PermissionDto?>(null) }

    suspend fun reload() {
        people = runCatching { sharing.permissions(item.isFolder, item.id) }.getOrElse { message = (it.message ?: "Could not load") to true; emptyList() }
    }
    LaunchedEffect(item.id) { reload() }

    DetailScaffold("Share “${item.name}”", onBack) { modifier ->
        LazyColumn(modifier) {
            item { SectionHeader("Add people") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        email, { email = it }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RolePicker(role) { role = it }
                        Box(Modifier.weight(1f))
                        Button(enabled = email.contains('@') && !busy, onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    sharing.addPerson(email, role, item.isFolder, item.id)
                                    message = "Shared with ${email.trim()}." to false
                                    email = ""
                                } catch (e: SharePartialException) {
                                    message = (e.message ?: "") to true
                                } catch (e: Exception) {
                                    message = (e.message ?: "Could not share.") to true
                                }
                                reload()
                                busy = false
                            }
                        }) { Text("Share") }
                    }
                    message?.let { (text, isError) ->
                        Text(text, color = if (isError) MaterialTheme.colorScheme.error else Brand.success, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { SectionHeader("People with access") }
            val list = people
            if (list == null) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            else if (list.isEmpty()) item { Text("Only you.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else items(list, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.userName.ifBlank { p.userEmail }) },
                    supportingContent = { Text(p.userEmail) },
                    trailingContent = {
                        if (ShareRole.of(p.role) == ShareRole.OWNER) Text("Owner", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            RolePicker(ShareRole.of(p.role)) { r ->
                                scope.launch {
                                    runCatching { sharing.updateRole(item.isFolder, item.id, p.userId, r) }.onFailure { message = (it.message ?: "") to true }
                                    reload()
                                }
                            }
                            IconButton(onClick = { revoking = p }) { Icon(Icons.Filled.Close, "Remove ${p.userEmail}") }
                        }
                    },
                )
            }
            item { SectionHeader("Link") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Anyone with the link can view. The file's contents stay encrypted; the link opens it on the web for people you have also added above.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val l = link
                    if (l != null) {
                        Text(l, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, l), "Share link"))
                            }) { Icon(Icons.Filled.Share, null); Text("  Send") }
                            OutlinedButton(onClick = {
                                scope.launch { runCatching { sharing.deleteLink(item.isFolder, item.id) }.onSuccess { link = null } }
                            }) { Icon(Icons.Filled.LinkOff, null); Text("  Turn off") }
                        }
                    } else {
                        // Created only on request: `GET /share-link` would create one as a side effect.
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { sharing.createLink(item.isFolder, item.id) }
                                    .onSuccess { link = it }.onFailure { message = (it.message ?: "") to true }
                            }
                        }) { Icon(Icons.Filled.Link, null); Text("  Create link") }
                    }
                }
            }
        }
    }
    revoking?.let { p ->
        ConfirmDialog(
            "Remove access?", "${p.userEmail} will no longer see “${item.name}”. A copy they already downloaded stays with them.",
            "Remove", destructive = true, onDismiss = { revoking = null },
        ) {
            scope.launch {
                runCatching { sharing.revoke(item.isFolder, item.id, p.userId) }.onFailure { message = (it.message ?: "") to true }
                reload()
            }
        }
    }
}

@Composable
private fun RolePicker(role: ShareRole, onPick: (ShareRole) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(role.label) }
        DropdownMenu(open, { open = false }) {
            ShareRole.assignable.forEach { r -> DropdownMenuItem({ Text(r.label) }, { open = false; onPick(r) }) }
        }
    }
}

// ── Version history ─────────────────────────────────────────────────────────────────────────────

/**
 * A file's earlier versions. A version decrypts with the file's current key — the server keeps no
 * per-version key — so viewing one is the ordinary download path with a version id.
 */
@Composable
fun VersionsScreen(item: DriveItem, vm: DriveViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = context.graph.api
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<FileVersionDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<FileVersionDto?>(null) }

    suspend fun load() {
        runCatching { api.versions(item.id) }.onSuccess { versions = it }.onFailure { error = it.message }
    }
    LaunchedEffect(item.id) { load() }

    DetailScaffold("Versions of ${item.name}", onBack) { modifier ->
        Box(modifier) {
            val list = versions
            when {
                error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> EmptyState(Icons.Filled.History, "No earlier versions", "Each time this file is saved, the previous content is kept here.")
                else -> LazyColumn {
                    items(list, key = { it.id }) { v ->
                        ListItem(
                            headlineContent = { Text(v.label?.takeIf { it.isNotBlank() } ?: "Version ${v.versionNumber}") },
                            supportingContent = { Text("${relative(DriveDate.parse(v.createdAt))} · ${formatBytes(context, v.sizeBytes)}") },
                            trailingContent = { TextButton(onClick = { restoring = v }) { Text("Restore") } },
                            modifier = Modifier.clickable {
                                vm.fetch(item.copy(name = item.name), versionId = v.id) { FileOpener.view(context, it, item.mimeType) }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
            FetchOverlay(vm)
        }
    }
    restoring?.let { v ->
        ConfirmDialog(
            "Restore this version?", "The current content is kept as a version too, so this can be undone.", "Restore",
            onDismiss = { restoring = null },
        ) {
            scope.launch {
                runCatching { api.restoreVersion(item.id, v.id) }.onFailure { error = it.message }
                load()
            }
        }
    }
}

// ── Zip viewer ──────────────────────────────────────────────────────────────────────────────────

/** Lists an archive's entries and opens one by extracting it — iOS's `ZipViewerView`. */
@Composable
fun ZipScreen(file: File, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<Pair<String, Long>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching { ZipFile(file).use { z -> z.entries().toList().map { it.name to it.size } } }
        }.onSuccess { entries = it.sortedBy { e -> e.first.lowercase() } }.onFailure { error = "This archive could not be read." }
    }
    DetailScaffold(name, onBack) { modifier ->
        Box(modifier) {
            val list = entries
            when {
                error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn {
                    items(list) { (entry, size) ->
                        val isDir = entry.endsWith("/")
                        ListItem(
                            headlineContent = { Text(entry.trimEnd('/').substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (isDir) entry else "${entry.substringBeforeLast('/', "").ifEmpty { "/" }} · ${formatBytes(context, size.coerceAtLeast(0))}") },
                            leadingContent = { Icon(if (isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            modifier = Modifier.clickable(enabled = !isDir) {
                                scope.launch {
                                    val target = FileOpener.newTarget(context, entry.substringAfterLast('/'))
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching {
                                            ZipFile(file).use { z -> z.getInputStream(z.getEntry(entry)).use { input -> target.outputStream().use { input.copyTo(it) } } }
                                        }.isSuccess
                                    }
                                    if (ok) FileOpener.view(context, target, null) else error = "Could not extract $entry."
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Web viewer ──────────────────────────────────────────────────────────────────────────────────

/**
 * Opens a file a Neutrino web editor owns (a document, sheet, slide, diagram or drawing) in the web
 * app, the way iOS's `NeutrinoFileViewer` does: load `<server>/files/<id>` and, once the page has
 * loaded, put the access token in the web app's `localStorage` so it can authenticate and decrypt.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewerScreen(item: DriveItem, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val token = graph.auth.currentAccessToken
    val url = "${graph.auth.serverUrl}/files/${item.id}"
    DetailScaffold(item.name, onBack) { modifier ->
        AndroidView(modifier = modifier, factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (token == null) return
                        val literal = kotlinx.serialization.json.JsonPrimitive(token).toString()
                        view.evaluateJavascript(
                            "(function(){try{localStorage.setItem('nd_access_token',$literal);window.dispatchEvent(new CustomEvent('nd:token_injected'));}catch(_){}})();",
                            null,
                        )
                    }
                }
                loadUrl(url)
            }
        })
    }
}
