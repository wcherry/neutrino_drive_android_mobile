package com.neutrino.drive.storage

import android.content.Context
import com.neutrino.drive.core.drive.Downloader
import com.neutrino.drive.core.drive.DriveItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A file decrypted into durable app storage so it opens with no network. */
@Serializable
data class OfflineFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Relative to the offline directory, so the manifest survives the app's data dir moving. */
    val relativePath: String,
    val cachedAtMillis: Long,
)

/**
 * "Make available offline", as `OfflineService.swift`: download through the normal decrypt path,
 * keep the plaintext under `files/offline/<id>/<name>` (app-private, excluded from backup), record
 * it in a manifest. A manifest entry whose file has vanished is skipped, not fatal.
 */
class OfflineStore(context: Context, private val downloader: Downloader) {

    val directory = File(context.filesDir, "offline").apply { mkdirs() }
    private val manifest = File(directory, "manifest.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(OfflineFile.serializer())

    private val _files = MutableStateFlow(load())
    val files: StateFlow<List<OfflineFile>> = _files

    private fun load(): List<OfflineFile> =
        runCatching { json.decodeFromString(serializer, manifest.readText()) }.getOrDefault(emptyList())
            .filter { File(directory, it.relativePath).exists() }

    fun isOffline(id: String) = _files.value.any { it.id == id }

    fun file(entry: OfflineFile) = File(directory, entry.relativePath)

    val sizeBytes: Long get() = _files.value.sumOf { it.sizeBytes }

    suspend fun makeAvailable(item: DriveItem) {
        val relative = "${item.id}/${safeName(item.name)}"
        val target = File(directory, relative)
        downloader.download(item.id, target)
        val entry = OfflineFile(
            item.id, item.name, item.mimeType ?: "application/octet-stream", target.length(), relative,
            System.currentTimeMillis(),
        )
        update { list -> list.filter { it.id != item.id } + entry }
    }

    fun remove(id: String) {
        _files.value.firstOrNull { it.id == id }?.let { File(directory, it.relativePath).parentFile?.deleteRecursively() }
        update { list -> list.filter { it.id != id } }
    }

    fun removeAll() {
        _files.value.forEach { File(directory, it.relativePath).parentFile?.deleteRecursively() }
        update { emptyList() }
    }

    @Synchronized
    private fun update(change: (List<OfflineFile>) -> List<OfflineFile>) {
        val next = change(_files.value)
        val tmp = File(directory, "manifest.tmp")
        tmp.writeText(json.encodeToString(serializer, next))
        tmp.renameTo(manifest)
        _files.value = next
    }

    companion object {
        /** A name that is a single path component on every filesystem. */
        fun safeName(name: String): String =
            name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').ifEmpty { "file" }.take(180)
    }
}
