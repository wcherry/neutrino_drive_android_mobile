package com.neutrino.drive.ui.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.core.drive.LocalSearchIndex
import com.neutrino.drive.graph
import com.neutrino.drive.transfer.Outbox
import com.neutrino.drive.ui.FileOpener
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Where a listing comes from. */
sealed class Place(val key: String, val title: String) {
    class Folder(val id: String?, name: String) : Place("folder:${id ?: "root"}", name)
    object Starred : Place("starred", "Starred")
    object Shared : Place("shared", "Shared with me")
    object Trash : Place("trash", "Trash")
    object Recents : Place("recents", "Recents")

    val isMyDrive get() = this is Folder
    companion object {
        val root = Folder(null, "My Drive")
    }
}

data class Listing(val items: List<DriveItem> = emptyList(), val loading: Boolean = false, val error: String? = null, val loaded: Boolean = false)

/** A file being fetched and decrypted for viewing. */
data class Fetch(val name: String, val fraction: Float?)

/**
 * Listings and every mutation on them — `DriveService.swift`'s job.
 *
 * Mutations go to the server and the affected listings are reloaded, rather than patched
 * optimistically: a phone list is short, and a reload can never disagree with the server the way a
 * hand-maintained cache can.
 */
class DriveViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val api get() = graph.api

    private val _listings = MutableStateFlow<Map<String, Listing>>(emptyMap())
    val listings: StateFlow<Map<String, Listing>> = _listings

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _fetch = MutableStateFlow<Fetch?>(null)
    val fetch: StateFlow<Fetch?> = _fetch

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search
    private var searchJob: Job? = null

    data class SearchState(val query: String = "", val results: List<DriveItem> = emptyList(), val indexing: Boolean = false)

    fun listing(place: Place): Listing = _listings.value[place.key] ?: Listing()

    fun load(place: Place, force: Boolean = false) {
        val current = listing(place)
        if (current.loading || (current.loaded && !force)) return
        _listings.update { it + (place.key to current.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = runCatching { fetchItems(place) }
            _listings.update {
                it + (place.key to result.fold(
                    { items -> Listing(items, loading = false, loaded = true) },
                    { e -> current.copy(loading = false, error = e.message ?: "Could not load.", loaded = current.loaded) },
                ))
            }
        }
    }

    fun reload(vararg places: Place) = places.forEach { load(it, force = true) }

    private suspend fun fetchItems(place: Place): List<DriveItem> = when (place) {
        is Place.Folder -> api.folder(place.id).let { l ->
            l.folders.map { DriveItem.of(it) }.sortedBy { it.name.lowercase() } +
                l.files.map { DriveItem.of(it) }.sortedBy { it.name.lowercase() }
        }
        Place.Starred -> api.starred().let { l -> l.folders.map { DriveItem.of(it) } + l.files.map { DriveItem.of(it) } }
        // The shared-with-me listing has no fixed schema; decoded best-effort, like iOS.
        Place.Shared -> runCatching { api.sharedWithMe() }.getOrNull()
            ?.let { l -> l.folders.map { DriveItem.of(it, shared = true) } + l.files.map { DriveItem.of(it, shared = true) } }
            ?: emptyList()
        Place.Trash -> api.trash().let { t -> t.folders.map { DriveItem.of(it) } + t.files.map { DriveItem.of(it) } }
        Place.Recents -> api.recents(30).map { DriveItem.of(it) }
    }

    private fun act(vararg reload: Place, success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.let { _messages.tryEmit(it) }
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Something went wrong.")
            }
            reload(*reload)
        }
    }

    fun createFolder(name: String, parent: Place.Folder) = act(parent) { api.createFolder(name, parent.id) }

    fun rename(item: DriveItem, name: String, from: Place) = act(from, Place.Starred) {
        if (item.isFolder) api.renameFolder(item.id, name) else api.renameFile(item.id, name)
    }

    fun move(item: DriveItem, target: Place.Folder, from: Place) =
        act(from, target, success = "Moved to ${target.title.ifEmpty { "My Drive" }}.") {
            if (item.isFolder) api.moveItems(emptyList(), listOf(item.id), target.id)
            else api.moveItems(listOf(item.id), emptyList(), target.id)
        }

    fun trash(item: DriveItem, from: Place) = act(from, Place.Trash, Place.Starred, Place.Recents, success = "Moved to Trash.") {
        if (item.isFolder) api.trashItems(emptyList(), listOf(item.id)) else api.trashItems(listOf(item.id), emptyList())
    }

    fun restore(item: DriveItem) = act(Place.Trash, Place.root, success = "Restored.") { api.restore(item.id, item.isFolder) }

    fun deleteForever(item: DriveItem) = act(Place.Trash) { api.deleteForever(item.id, item.isFolder) }

    fun emptyTrash() = act(Place.Trash, success = "Trash emptied.") { api.emptyTrash() }

    fun setStarred(item: DriveItem, starred: Boolean, from: Place) = act(from, Place.Starred) {
        api.setStarred(item.id, item.isFolder, starred)
    }

    fun upload(uris: List<Uri>, folder: Place.Folder) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            runCatching { Outbox.enqueue(getApplication(), uris, folder.id) }
                .onSuccess { _messages.tryEmit(if (uris.size == 1) "Uploading 1 file…" else "Uploading ${uris.size} files…") }
                .onFailure { _messages.tryEmit(it.message ?: "Could not start the upload.") }
        }
    }

    /** Downloads and decrypts into the open cache, then hands the file to [then]. */
    fun fetch(item: DriveItem, versionId: String? = null, then: (File) -> Unit) {
        if (_fetch.value != null) return
        _fetch.value = Fetch(item.name, null)
        viewModelScope.launch {
            val target = FileOpener.newTarget(getApplication(), item.name)
            try {
                graph.downloader.download(item.id, target, versionId) { done, total ->
                    if (total > 0) _fetch.value = Fetch(item.name, done.toFloat() / total)
                }
                _fetch.value = null
                then(target)
            } catch (e: Exception) {
                _fetch.value = null
                target.parentFile?.deleteRecursively()
                _messages.tryEmit(e.message ?: "Could not open ${item.name}.")
            }
        }
    }

    fun makeOffline(item: DriveItem) {
        viewModelScope.launch {
            _fetch.value = Fetch(item.name, null)
            runCatching { graph.offline.makeAvailable(item) }
                .onSuccess { _messages.tryEmit("${item.name} is available offline.") }
                .onFailure { _messages.tryEmit(it.message ?: "Could not save ${item.name} offline.") }
            _fetch.value = null
        }
    }

    // ── Search ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Searches file names in the on-device index, building it first (a walk of the whole drive)
     * when it is empty or more than five minutes old.
     */
    fun search(query: String) {
        _search.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            val index = graph.searchIndex
            if (index.isEmpty || System.currentTimeMillis() - index.builtAtMillis > 5 * 60_000) {
                _search.update { it.copy(indexing = true) }
                LocalSearchIndex.walk(api)?.let(index::replace)
                _search.update { it.copy(indexing = false) }
            }
            _search.update { it.copy(results = index.query(query)) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }
}
