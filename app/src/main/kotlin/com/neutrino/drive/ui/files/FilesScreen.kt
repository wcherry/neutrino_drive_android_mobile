package com.neutrino.drive.ui.files

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.core.drive.NativeKind
import com.neutrino.drive.core.drive.isZip
import com.neutrino.drive.graph
import com.neutrino.drive.transfer.Outbox
import com.neutrino.drive.transfer.UploadWorker
import com.neutrino.drive.ui.Banner
import com.neutrino.drive.ui.ConfirmDialog
import com.neutrino.drive.ui.EmptyState
import com.neutrino.drive.ui.FileOpener
import com.neutrino.drive.ui.Route
import com.neutrino.drive.ui.TextInputDialog
import com.neutrino.drive.ui.formatBytes
import com.neutrino.drive.ui.iconFor
import com.neutrino.drive.ui.openUrl
import com.neutrino.drive.ui.relative
import com.neutrino.drive.ui.settings.BugReport

/** The sections offered at the top of the Files tab — `DriveSection.visibleCases`, minus Recents (its own tab). */
private val sections = listOf(Place.root, Place.Starred, Place.Shared, Place.Trash)

/**
 * One listing: a folder of My Drive, Starred, Shared, Trash or Recents — `FileBrowserView.swift`.
 * Tap opens; the ⋮ button or a long press opens the item's actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    place: Place,
    vm: DriveViewModel,
    isRoot: Boolean,
    canPop: Boolean,
    onBack: () -> Unit,
    navigate: (Route) -> Unit,
    switchSection: (Place) -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val listings by vm.listings.collectAsStateWithLifecycle()
    val listing = listings[place.key] ?: com.neutrino.drive.ui.files.Listing()
    val keyringVersion by graph.keyringVersion.collectAsState()
    val hasKey = remember(keyringVersion) { graph.keys.hasKey }
    var menu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<DriveItem?>(null) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val folder = place as? Place.Folder

    LaunchedEffect(place.key) { vm.load(place) }
    // A folder that failed to load while offline loads again when the connection comes back.
    val online by graph.network.isOnline.collectAsState()
    LaunchedEffect(online) { if (online && vm.listing(place).error != null) vm.load(place, force = true) }

    // Uploads finishing into this folder refresh it.
    val uploads by remember { WorkManager.getInstance(context).getWorkInfosByTagFlow(Outbox.TAG) }.collectAsState(initial = emptyList())
    val running = uploads.firstOrNull { it.state == WorkInfo.State.RUNNING }
    val finished = uploads.count { it.state == WorkInfo.State.SUCCEEDED }
    LaunchedEffect(finished) { if (finished > 0 && folder != null) vm.reload(place, Place.Recents) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (folder != null) vm.upload(uris, folder)
    }

    fun open(item: DriveItem) = openItem(item, vm, navigate, context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(place.title.ifEmpty { "Folder" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (canPop) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (place.isMyDrive) IconButton(onClick = { navigate(Route.Search) }) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        if (folder != null) DropdownMenuItem({ Text("New folder") }, { menu = false; newFolder = true }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) })
                        if (place == Place.Trash) DropdownMenuItem({ Text("Empty Trash") }, { menu = false; confirmEmpty = true }, leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) }, enabled = listing.items.isNotEmpty())
                        DropdownMenuItem({ Text("Refresh") }, { menu = false; vm.load(place, true) }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                        DropdownMenuItem({ Text("Report a bug") }, { menu = false; openUrl(context, BugReport.url(context)) }, leadingIcon = { Icon(Icons.Filled.BugReport, null) })
                    }
                },
            )
        },
        floatingActionButton = {
            if (folder != null && hasKey) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Upload") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (isRoot && place != Place.Recents) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sections.forEach { s ->
                        FilterChip(selected = s.key == place.key, onClick = { switchSection(s) }, label = { Text(if (s == Place.Shared) "Shared" else s.title) })
                    }
                }
            }
            if (!hasKey) {
                Banner("Add your encryption key to open and upload files.", MaterialTheme.colorScheme.error) {
                    TextButton(onClick = { navigate(Route.KeyOptions) }) { Text("Add key") }
                }
            }
            running?.let { info ->
                val p = info.progress
                val count = p.getInt(UploadWorker.KEY_COUNT, 1)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        if (count > 1) "Uploading ${p.getInt(UploadWorker.KEY_INDEX, 0) + 1} of $count — ${p.getString(UploadWorker.KEY_NAME).orEmpty()}"
                        else "Uploading ${p.getString(UploadWorker.KEY_NAME).orEmpty()}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(progress = { p.getFloat(UploadWorker.KEY_FRACTION, 0f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            PullToRefreshBox(isRefreshing = listing.loading && listing.loaded, onRefresh = { vm.load(place, true) }, modifier = Modifier.weight(1f)) {
                when {
                    !listing.loaded && listing.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                    listing.error != null && listing.items.isEmpty() -> ErrorState(listing.error) { vm.load(place, true) }
                    listing.items.isEmpty() && listing.loaded -> EmptyFor(place)
                    else -> ItemList(listing.items, onOpen = ::open, onActions = { actionsFor = it })
                }
            }
        }
        FetchOverlay(vm)
    }

    actionsFor?.let { item ->
        ItemActionsSheet(item, place, vm, navigate, onDismiss = { actionsFor = null })
    }
    if (newFolder && folder != null) {
        TextInputDialog("New folder", "", "Name", "Create", onDismiss = { newFolder = false }) {
            newFolder = false
            vm.createFolder(it, folder)
        }
    }
    if (confirmEmpty) {
        ConfirmDialog(
            "Empty Trash?", "This will permanently delete all items in the Trash. This cannot be undone.", "Empty Trash",
            destructive = true, onDismiss = { confirmEmpty = false },
        ) { vm.emptyTrash() }
    }
}

/** How tapping a file opens it. */
fun openItem(item: DriveItem, vm: DriveViewModel, navigate: (Route) -> Unit, context: android.content.Context) {
    when {
        item.isFolder -> navigate(Route.Browse(Place.Folder(item.id, item.name)))
        item.isTrashed -> Unit
        // Files a Neutrino web editor owns open in it — iOS's `NeutrinoFileViewer`. There are no
        // Android companion apps (Docs, Notes…) to hand them to yet.
        item.nativeKind != null && item.nativeKind != NativeKind.NOTE -> navigate(Route.Web(item))
        isZip(item.mimeType, item.name) -> vm.fetch(item) { navigate(Route.Zip(it, item.name)) }
        else -> vm.fetch(item) { FileOpener.view(context, it, item.mimeType) }
    }
}

@Composable
fun ItemList(items: List<DriveItem>, onOpen: (DriveItem) -> Unit, onActions: (DriveItem) -> Unit, contentPadding: PaddingValues = PaddingValues(bottom = 88.dp)) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(items, key = { (if (it.isFolder) "d" else "f") + it.id }) { item ->
            FileRow(item, onClick = { onOpen(item) }, onLongClick = { onActions(item) }) {
                IconButton(onClick = { onActions(item) }) { Icon(Icons.Filled.MoreVert, "Actions for ${item.name}") }
            }
        }
    }
}

@Composable
fun FileRow(item: DriveItem, onClick: () -> Unit, onLongClick: () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(item)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.isStarred) Icon(Icons.Filled.Star, "Starred", Modifier.padding(start = 4.dp).size(14.dp), tint = Color(0xFFF5B400))
                if (item.isShared) Icon(Icons.Filled.People, "Shared", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val subtitle = listOfNotNull(item.size?.let { formatBytes(context, it) }, relative(item.modifiedAt).ifEmpty { null }).joinToString(" · ")
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing()
    }
}

/** The file's cover thumbnail when it has one, else its type icon on a tinted tile. */
@Composable
fun Thumb(item: DriveItem) {
    val graph = LocalContext.current.graph
    val path = item.thumbnailPath
    val bitmap by produceState<Bitmap?>(path?.let(graph.thumbnails::peek), path) {
        if (path != null && value == null) value = graph.thumbnails.load(path)
    }
    val (icon, tint) = iconFor(item)
    val accent = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun EmptyFor(place: Place) = when (place) {
    is Place.Folder -> EmptyState(Icons.Filled.FolderOpen, "No files here", "Tap Upload to add files, or ⋮ › New folder.")
    Place.Starred -> EmptyState(Icons.Filled.StarBorder, "No starred items", "Open an item's actions and choose Add star to keep it here.")
    Place.Shared -> EmptyState(Icons.Filled.People, "Nothing shared yet", "Files shared with you will appear here.")
    Place.Trash -> EmptyState(Icons.Filled.Delete, "Trash is empty", "Deleted files are moved here before being permanently removed.")
    Place.Recents -> EmptyState(Icons.Filled.History, "No recent files", "Files you upload or change will appear here.")
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = retry) { Text("Try again") }
        }
    }
}

/** The decrypting-for-you overlay — iOS's "Downloading…" card. */
@Composable
fun FetchOverlay(vm: DriveViewModel) {
    val fetch by vm.fetch.collectAsStateWithLifecycle()
    fetch?.let { f ->
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp).size(width = 220.dp, height = 80.dp), verticalArrangement = Arrangement.Center) {
                    Text("Decrypting ${f.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (f.fraction != null) LinearProgressIndicator(progress = { f.fraction }, Modifier.fillMaxWidth().padding(top = 12.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
        }
    }
}

/** Search over file names in My Drive — iOS's `.searchable` on the My Drive list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: DriveViewModel, onBack: () -> Unit, navigate: (Route) -> Unit) {
    val context = LocalContext.current
    val state by vm.search.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<DriveItem?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { vm.clearSearch(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                TextField(
                    state.query, vm::search, placeholder = { Text("Search My Drive") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                    trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.search("") }) { Icon(Icons.Filled.Close, "Clear") } },
                )
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding)) {
            if (state.indexing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.query.isBlank() -> EmptyState(Icons.Filled.Search, "Search your files", "Names are searched on this phone — the server never sees what you look for.")
                state.results.isEmpty() && !state.indexing -> EmptyState(Icons.Filled.Search, "No results", "No files match “${state.query}”.")
                else -> ItemList(state.results, onOpen = { openItem(it, vm, navigate, context) }, onActions = { actionsFor = it })
            }
        }
        FetchOverlay(vm)
    }
    actionsFor?.let { ItemActionsSheet(it, Place.Recents, vm, navigate) { actionsFor = null } }
}
