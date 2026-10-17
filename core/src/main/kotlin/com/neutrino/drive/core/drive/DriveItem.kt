package com.neutrino.drive.core.drive

import com.neutrino.drive.core.api.TrashFileDto
import com.neutrino.drive.core.api.TrashFolderDto
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.FolderDto
import java.time.Instant

/** One file or folder, as every screen shows it. */
data class DriveItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val parentId: String? = null,
    val size: Long? = null,
    val modifiedAt: Instant? = null,
    val mimeType: String? = null,
    val isStarred: Boolean = false,
    val isShared: Boolean = false,
    val isTrashed: Boolean = false,
    val thumbnailPath: String? = null,
    val contentVersion: Long? = null,
) {
    /** The Neutrino app that owns this file's format, or `null` for an ordinary upload. */
    val nativeKind: NativeKind? get() = if (isFolder) null else NativeKind.forMime(mimeType)

    companion object {
        fun of(f: FolderDto, shared: Boolean = false) = DriveItem(
            id = f.id, name = f.name, isFolder = true, parentId = f.parentId,
            modifiedAt = DriveDate.parse(f.updatedAt), isStarred = f.isStarred ?: false, isShared = shared,
        )

        fun of(f: FileDto, shared: Boolean = false) = DriveItem(
            id = f.id, name = f.name, isFolder = false, parentId = f.folderId, size = f.sizeBytes,
            modifiedAt = DriveDate.parse(f.updatedAt), mimeType = f.mimeType, isStarred = f.isStarred ?: false,
            isShared = shared, thumbnailPath = f.coverThumbnailUrl, contentVersion = f.contentVersion,
        )

        fun of(f: TrashFileDto) = DriveItem(
            id = f.id, name = f.name, isFolder = false, size = f.sizeBytes,
            modifiedAt = DriveDate.parse(f.deletedAt), mimeType = f.mimeType, isTrashed = true,
        )

        fun of(f: TrashFolderDto) = DriveItem(
            id = f.id, name = f.name, isFolder = true, modifiedAt = DriveDate.parse(f.deletedAt), isTrashed = true,
        )
    }
}

/**
 * The Neutrino app that owns a file format — `NeutrinoAppLink.Kind` in the iOS shared package,
 * mirroring the web's `routeForFile.ts`. Three vintages of MIME type are matched, because a Drive
 * listing routinely holds all three: real OOXML (what Docs, Sheets and Slides write today), the
 * bespoke `application/x-neutrino-*` JSON, and the oldest `application/vnd.neutrino.*`.
 */
enum class NativeKind(val path: String, val appName: String, val mimeTypes: List<String>) {
    NOTE("note", "Neutrino Notes", listOf("text/markdown")),
    DOC("doc", "Neutrino Docs", listOf(OOXML_DOCX, "application/x-neutrino-doc", "application/vnd.neutrino.doc")),
    SHEET("sheet", "Neutrino Sheets", listOf(OOXML_XLSX, "application/x-neutrino-sheet", "application/vnd.neutrino.sheet")),
    SLIDE("slide", "Neutrino Slides", listOf(OOXML_PPTX, "application/x-neutrino-slide", "application/vnd.neutrino.slide")),
    DIAGRAM("diagram", "Neutrino Diagrams", listOf("application/x-neutrino-diagram", "application/vnd.neutrino.diagram")),
    DRAWING("drawing", "Neutrino Drawings", listOf("application/x-neutrino-drawing", "application/vnd.neutrino.drawing"));

    companion object {
        /** Servers append parameters (`; charset=utf-8`) and casing is not significant. */
        fun forMime(mime: String?): NativeKind? {
            val normalized = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { normalized in it.mimeTypes }
        }
    }
}

const val OOXML_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val OOXML_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
const val OOXML_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

/**
 * The cross-app link vocabulary: `https://www.getneutrino.app/open/<kind>/<fileId>[?v=<contentVersion>]`.
 * Only the file id travels — never contents, never a key. Drive accepts every kind: they are all
 * Drive files, and refusing would strand a link that has nowhere else to go.
 */
object AppLink {
    const val HOST = "www.getneutrino.app"
    private val hosts = setOf(HOST, "getneutrino.app")
    private val kinds = setOf("file") + NativeKind.entries.map { it.path }

    data class Destination(val kind: String, val fileId: String, val contentVersion: Long?)

    fun parse(url: String): Destination? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() !in hosts) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.size != 3 || segments[0] != "open" || segments[1] !in kinds) return null
        val version = uri.query?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")?.toLongOrNull()
        return Destination(segments[1], segments[2].trim().ifEmpty { return null }, version)
    }

    fun url(item: DriveItem): String = "https://$HOST/open/${item.nativeKind?.path ?: "file"}/${item.id}"
}

/** Recognises archives the in-app zip viewer can list. */
fun isZip(mimeType: String?, name: String): Boolean =
    mimeType in setOf("application/zip", "application/x-zip-compressed") || name.lowercase().endsWith(".zip")
