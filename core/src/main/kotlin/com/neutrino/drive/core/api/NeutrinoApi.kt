package com.neutrino.drive.core.api

import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.FolderContentsDto
import com.neutrino.shared.core.api.UserDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import com.neutrino.shared.core.api.NeutrinoApi as SharedApi

/**
 * The Neutrino API as Drive uses it: the shared client (token plumbing, keys, files and folders,
 * upload and download) plus Drive's own endpoints — listings, trash, sharing, versions, quota.
 */
class NeutrinoApi(http: OkHttpClient, auth: AuthClient) : SharedApi(http, auth) {

    // ── Listings ────────────────────────────────────────────────────────────────────────────────

    suspend fun recents(limit: Int = 20): List<FileDto> =
        json(FileListDto.serializer(), get("/drive/files?orderBy=updatedAt&direction=desc&limit=$limit")).files

    /** Every file the caller owns, oldest first — paged, for key repair and indexing. */
    suspend fun filesPage(limit: Int, offset: Int): List<FileDto> =
        json(FileListDto.serializer(), get("/drive/files?limit=$limit&offset=$offset&orderBy=createdAt&direction=asc")).files

    suspend fun trash(): TrashContentsDto = json(TrashContentsDto.serializer(), get("/drive/trash"))

    /** The endpoint's default limit is 5 (a "Quick Access" strip), so a real limit is always sent. */
    suspend fun starred(limit: Int = 200): FolderContentsDto = json(FolderContentsDto.serializer(), get("/drive/starred?limit=$limit"))

    suspend fun sharedWithMe(): FolderContentsDto = json(FolderContentsDto.serializer(), get("/drive/shared-with-me"))

    // ── Changes ─────────────────────────────────────────────────────────────────────────────────

    suspend fun renameFolder(id: String, name: String) {
        text(method("PATCH", "/drive/folders/$id", buildJsonObject { put("name", name) }))
    }

    suspend fun renameFile(id: String, name: String) {
        text(method("PATCH", "/drive/files/$id", buildJsonObject { put("name", name) }))
    }

    /** No dedicated endpoint: `is_starred` is a field of the ordinary update. */
    suspend fun setStarred(id: String, isFolder: Boolean, starred: Boolean) {
        val path = if (isFolder) "/drive/folders/$id" else "/drive/files/$id"
        text(method("PATCH", path, buildJsonObject { put("isStarred", starred) }))
    }

    suspend fun trashItems(fileIds: List<String>, folderIds: List<String>): Int = json(
        BulkResultDto.serializer(),
        method("POST", "/drive/bulk/trash", buildJsonObject {
            put("fileIds", strings(fileIds)); put("folderIds", strings(folderIds))
        }),
    ).affected

    suspend fun moveItems(fileIds: List<String>, folderIds: List<String>, targetFolderId: String?): Int = json(
        BulkResultDto.serializer(),
        method("POST", "/drive/bulk/move", buildJsonObject {
            put("fileIds", strings(fileIds)); put("folderIds", strings(folderIds))
            put("targetFolderId", targetFolderId?.let { JsonPrimitive(it) } ?: JsonNull)
        }),
    ).affected

    suspend fun restore(id: String, isFolder: Boolean) {
        text(method("POST", "/drive/trash/${if (isFolder) "folders" else "files"}/$id/restore"))
    }

    suspend fun deleteForever(id: String, isFolder: Boolean) {
        text(method("DELETE", "/drive/trash/${if (isFolder) "folders" else "files"}/$id"))
    }

    suspend fun emptyTrash() {
        text(method("DELETE", "/drive/trash"))
    }

    suspend fun quota(): QuotaDto = json(QuotaDto.serializer(), get("/drive/quota"))

    /** A cover thumbnail's bytes, or `null` when the file has none (404). */
    suspend fun thumbnail(serverPath: String): ByteArray? = send(get(absolute(serverPath))).use {
        if (it.isSuccessful) it.body?.bytes() else null
    }

    // ── Sharing ─────────────────────────────────────────────────────────────────────────────────

    suspend fun searchUsers(query: String): List<UserDto> = json(
        ListSerializer(UserDto.serializer()),
        get(url("/auth/users/search").newBuilder().addQueryParameter("q", query).build()),
    )

    suspend fun permissions(resource: String, id: String): List<PermissionDto> =
        json(PermissionsDto.serializer(), get("/drive/$resource/$id/permissions")).permissions

    suspend fun grant(resource: String, id: String, user: UserDto, role: String): PermissionDto = json(
        PermissionDto.serializer(),
        method("POST", "/drive/$resource/$id/permissions", buildJsonObject {
            put("userId", user.id); put("userEmail", user.email); put("userName", user.name); put("role", role)
        }),
    )

    suspend fun updatePermission(resource: String, id: String, userId: String, role: String): PermissionDto = json(
        PermissionDto.serializer(),
        method("PATCH", "/drive/$resource/$id/permissions/$userId", buildJsonObject { put("role", role) }),
    )

    suspend fun revoke(resource: String, id: String, userId: String) {
        text(method("DELETE", "/drive/$resource/$id/permissions/$userId"))
    }

    /** Stores a DEK re-sealed for `recipientId`, filed under the *recipient's* key version. */
    suspend fun shareFileKey(fileId: String, recipientId: String, sealed: String, recipientKeyVersion: Int) {
        text(method("POST", "/drive/files/$fileId/key/share", buildJsonObject {
            put("recipientId", recipientId); put("encryptedFileKey", sealed); put("keyVersion", recipientKeyVersion)
        }))
    }

    /**
     * Create or replace a link. `PUT`, because `GET /share-link` has a side effect (it creates a
     * default link), so merely opening the share screen must not call it.
     */
    suspend fun upsertShareLink(resource: String, id: String, role: String): ShareLinkDto = json(
        ShareLinkDto.serializer(),
        method("PUT", "/drive/$resource/$id/share-link", buildJsonObject {
            put("visibility", "anyoneWithLink"); put("role", role)
        }),
    )

    suspend fun deleteShareLink(resource: String, id: String) {
        text(method("DELETE", "/drive/$resource/$id/share-link"))
    }

    // ── Versions ────────────────────────────────────────────────────────────────────────────────

    suspend fun versions(fileId: String): List<FileVersionDto> =
        json(VersionsDto.serializer(), get("/drive/files/$fileId/versions")).versions.sortedByDescending { it.versionNumber }

    /** The server snapshots the current content first, so a restore is itself reversible. */
    suspend fun restoreVersion(fileId: String, versionId: String) {
        text(method("POST", "/drive/files/$fileId/versions/$versionId/restore"))
    }

    private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
