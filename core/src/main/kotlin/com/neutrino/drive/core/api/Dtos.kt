package com.neutrino.drive.core.api

import com.neutrino.shared.core.api.FileDto
import kotlinx.serialization.Serializable

// Drive's own response shapes, from `neutrino/src/drive/filesystem/dto.rs`, `drive/storage/dto.rs`,
// `drive/permissions/dto.rs` and `drive/sharing/dto.rs` — camelCase on the wire. The shapes every
// app shares (files, folders, keys, users) are in neutrino_shared_android.
// Every field a screen can do without is optional, so a server that adds or drops one cannot take
// down browsing. Dates stay strings here; `DriveDate.parse` turns them into instants.

@Serializable
data class FileListDto(val files: List<FileDto> = emptyList())

@Serializable
data class TrashFileDto(
    val id: String,
    val name: String,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val deletedAt: String? = null,
)

@Serializable
data class TrashFolderDto(val id: String, val name: String, val deletedAt: String? = null)

@Serializable
data class TrashContentsDto(
    val files: List<TrashFileDto> = emptyList(),
    val folders: List<TrashFolderDto> = emptyList(),
)

@Serializable
data class BulkResultDto(val affected: Int = 0)

@Serializable
data class QuotaDto(
    val usedBytes: Long = 0,
    val quotaBytes: Long? = null,
    val dailyUploadBytes: Long = 0,
    val dailyCapBytes: Long? = null,
)

/** `PublicKeyResponse` — the field is `version` on the wire, not `keyVersion`. */
@Serializable
data class PermissionDto(
    val id: String,
    val userId: String,
    val userEmail: String = "",
    val userName: String = "",
    val role: String,
)

@Serializable
data class PermissionsDto(val permissions: List<PermissionDto> = emptyList())

@Serializable
data class ShareLinkDto(
    val token: String,
    val role: String = "viewer",
    val visibility: String = "anyoneWithLink",
    val isActive: Boolean = true,
)

@Serializable
data class FileVersionDto(
    val id: String,
    val fileId: String,
    val versionNumber: Int,
    val sizeBytes: Long = 0,
    val label: String? = null,
    val createdAt: String,
    val isNamed: Boolean = false,
)

@Serializable
data class VersionsDto(val versions: List<FileVersionDto> = emptyList())
