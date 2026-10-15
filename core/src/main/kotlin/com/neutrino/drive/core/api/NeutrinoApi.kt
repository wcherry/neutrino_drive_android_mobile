package com.neutrino.drive.core.api

import com.neutrino.drive.core.keys.ArchivedKeys
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The Neutrino HTTP API, as the Android client uses it.
 *
 * Every call goes through one token path ([send]): a proactive refresh inside the skew window,
 * then on a 401 a forced refresh and exactly one retry. On iOS the same rule is repeated in each
 * service's `authorized(_:)`; here a call site cannot forget it.
 */
class NeutrinoApi(val http: OkHttpClient, val auth: AuthClient) {

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    fun url(path: String): HttpUrl = "${auth.serverUrl}/api/v1$path".toHttpUrl()

    /** Absolute URL for a server-relative path such as a `coverThumbnailUrl`. */
    fun absolute(serverPath: String): HttpUrl = "${auth.serverUrl}$serverPath".toHttpUrl()

    /**
     * Send with a bearer token; on 401 refresh and retry once. `build` runs per attempt so a body
     * is never replayed half-consumed. The caller owns (and must close) the returned response.
     */
    suspend fun send(build: () -> Request.Builder): Response {
        val first = http.newCall(build().header("Authorization", "Bearer ${auth.accessToken()}").build()).await()
        if (first.code != 401) return first
        first.close()
        val retry = http.newCall(build().header("Authorization", "Bearer ${auth.accessToken(force = true)}").build()).await()
        if (retry.code == 401) {
            retry.close()
            throw ApiException.SessionExpired()
        }
        return retry
    }

    /** Throws [ApiException.Http] for anything but 2xx; returns the body text otherwise. */
    suspend fun text(build: () -> Request.Builder): String = send(build).use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw httpError(response.code, body)
        body
    }

    suspend fun <T> json(serializer: KSerializer<T>, build: () -> Request.Builder): T {
        val body = text(build)
        return try {
            apiJson.decodeFromString(serializer, body)
        } catch (e: Exception) {
            throw ApiException.Decode("${e.message?.take(200)}: ${body.take(200)}")
        }
    }

    /** `null` on 404, for endpoints where absence is an answer rather than a failure. */
    suspend fun <T> jsonOrNull(serializer: KSerializer<T>, build: () -> Request.Builder): T? = try {
        json(serializer, build)
    } catch (e: ApiException.Http) {
        if (e.status == 404) null else throw e
    }

    private fun get(path: String) = { Request.Builder().url(url(path)).get() }
    private fun get(url: HttpUrl) = { Request.Builder().url(url).get() }
    private fun method(method: String, path: String, body: JsonElement? = null) = {
        val requestBody = body?.toString()?.toRequestBody(JSON) ?: ByteArray(0).toRequestBody(null)
        Request.Builder().url(url(path)).method(method, requestBody)
    }

    // ── Folders and listings ────────────────────────────────────────────────────────────────────

    /** The drive root's folder id — the signed-in user's id, read off the token's `sub`. */
    suspend fun rootFolderId(): String {
        auth.accessToken() // fresh before its claims are read
        return auth.userId ?: throw ApiException.NotSignedIn()
    }

    suspend fun folder(folderId: String?, limit: Int? = null, offset: Int? = null, orderByName: Boolean = false): FolderContentsDto {
        val id = folderId ?: rootFolderId()
        val u = url("/drive/folders/$id").newBuilder().apply {
            limit?.let { addQueryParameter("limit", it.toString()) }
            offset?.let { addQueryParameter("offset", it.toString()) }
            if (orderByName) {
                addQueryParameter("orderBy", "name")
                addQueryParameter("direction", "asc")
            }
        }.build()
        val listing = json(FolderContentsDto.serializer(), get(u))
        return listing.copy(files = listing.files.filter { it.deletedAt.isNullOrEmpty() })
    }

    suspend fun recents(limit: Int = 20): List<FileDto> =
        json(FileListDto.serializer(), get("/drive/files?orderBy=updatedAt&direction=desc&limit=$limit")).files

    /** Every file the caller owns, oldest first — paged, for key repair and indexing. */
    suspend fun filesPage(limit: Int, offset: Int): List<FileDto> =
        json(FileListDto.serializer(), get("/drive/files?limit=$limit&offset=$offset&orderBy=createdAt&direction=asc")).files

    suspend fun trash(): TrashContentsDto = json(TrashContentsDto.serializer(), get("/drive/trash"))

    /** The endpoint's default limit is 5 (a "Quick Access" strip), so a real limit is always sent. */
    suspend fun starred(limit: Int = 200): FolderContentsDto = json(FolderContentsDto.serializer(), get("/drive/starred?limit=$limit"))

    suspend fun sharedWithMe(): FolderContentsDto = json(FolderContentsDto.serializer(), get("/drive/shared-with-me"))

    suspend fun fileMetadata(fileId: String): FileDto = json(FileDto.serializer(), get("/drive/files/$fileId/metadata"))

    suspend fun createFolder(name: String, parentId: String?): FolderDto = json(
        FolderDto.serializer(),
        method("POST", "/drive/folders", buildJsonObject { put("name", name); put("parentId", parentId) }),
    )

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

    // ── Keys ────────────────────────────────────────────────────────────────────────────────────

    /** The caller's sealed DEK for a file, or `null` when the file has no key ref (404). */
    suspend fun fileKey(fileId: String): FileKeyDto? = jsonOrNull(FileKeyDto.serializer(), get("/drive/files/$fileId/key"))

    suspend fun setFileKey(fileId: String, sealed: String, keyVersion: Int) {
        text(method("PUT", "/drive/files/$fileId/key", buildJsonObject {
            put("encryptedFileKey", sealed); put("keyVersion", keyVersion)
        }))
    }

    /** The user's **active** published key, or `null` when they have published none (404). */
    suspend fun publishedKey(userId: String): PublicKeyDto? =
        jsonOrNull(PublicKeyDto.serializer(), get("/auth/users/$userId/public-key"))

    /** `POST /auth/keys` is append-only: publishing a different key is a rotation. */
    suspend fun publishKey(publicKey: String): PublicKeyDto =
        json(PublicKeyDto.serializer(), method("POST", "/auth/keys", buildJsonObject { put("publicKey", publicKey) }))

    /** The account's retired keys, or `null` when it has none stored (it never rotated). */
    suspend fun keyFile(): ArchivedKeys.KeyFile? = jsonOrNull(ArchivedKeys.KeyFile.serializer(), get("/drive/key-file"))

    // ── Upload / download plumbing ──────────────────────────────────────────────────────────────

    /** Opens a blob for streaming. The caller closes the response. */
    suspend fun openBlob(fileId: String, versionId: String? = null): Response {
        val path = if (versionId == null) "/drive/files/$fileId" else "/drive/files/$fileId/versions/$versionId/download"
        val response = send(get(path))
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            response.close()
            throw httpError(response.code, body)
        }
        return response
    }

    suspend fun upload(body: RequestBody): FileDto =
        json(FileDto.serializer()) { Request.Builder().url(url("/drive/files/upload")).post(body) }

    // ── Account ─────────────────────────────────────────────────────────────────────────────────

    suspend fun profile(): ProfileDto = json(ProfileDto.serializer(), get("/auth/me"))

    // ── Sharing ─────────────────────────────────────────────────────────────────────────────────

    suspend fun lookupUser(email: String): UserDto? = jsonOrNull(
        UserDto.serializer(),
        get(url("/auth/users/lookup").newBuilder().addQueryParameter("email", email).build()),
    )

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

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
