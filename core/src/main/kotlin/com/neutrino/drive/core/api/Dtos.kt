package com.neutrino.drive.core.api

import kotlinx.serialization.Serializable

// Response shapes from `neutrino/src/drive/filesystem/dto.rs`, `drive/storage/dto.rs`,
// `drive/permissions/dto.rs`, `drive/sharing/dto.rs` and `auth/dto.rs` — camelCase on the wire.
// Every field a screen can do without is optional, so a server that adds or drops one cannot take
// down browsing. Dates stay strings here; `DriveDate.parse` turns them into instants.

@Serializable
data class FolderDto(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val isStarred: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class FileDto(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val isStarred: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** `/api/v1/drive/files/{id}/thumbnail?v=…`, bearer-authenticated. Plaintext by design — see the upload. */
    val coverThumbnailUrl: String? = null,
    val contentVersion: Long? = null,
    val importSource: String? = null,
    val deletedAt: String? = null,
)

@Serializable
data class FolderContentsDto(
    val folder: FolderDto? = null,
    val folders: List<FolderDto> = emptyList(),
    val files: List<FileDto> = emptyList(),
)

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

@Serializable
data class FileKeyDto(
    val encryptedFileKey: String,
    /** Absent from servers that predate rotation; those DEKs are sealed to version 1. */
    val keyVersion: Int? = null,
)

/** `PublicKeyResponse` — the field is `version` on the wire, not `keyVersion`. */
@Serializable
data class PublicKeyDto(val userId: String? = null, val publicKey: String, val version: Int = 1)

@Serializable
data class UserDto(val id: String, val email: String, val name: String = "")

@Serializable
data class ProfileDto(
    val id: String,
    val email: String,
    val name: String = "",
    val totpEnabled: Boolean = false,
)

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
