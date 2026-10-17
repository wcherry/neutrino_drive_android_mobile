package com.neutrino.drive.core.drive

import com.neutrino.drive.core.api.NeutrinoApi
import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.apiJson
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.crypto.SecretStream
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.KeyException
import com.neutrino.shared.core.keys.KeyringException
import com.neutrino.shared.core.keys.KeyringStore
import com.neutrino.shared.core.keys.openDek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID

/** Something to upload: its name, plaintext type, and a way to read it (possibly more than once). */
class UploadSource(
    val name: String,
    val mimeType: String,
    val open: () -> InputStream,
)

/**
 * The dates and provenance a file keeps from its source — a photo's capture date. Sent *with* the
 * upload: the content write stamps `updated_at` with the server clock, so dates sent afterwards
 * would be overwritten unless sent as a separate PATCH.
 */
data class ImportMetadata(val createdAt: Instant, val updatedAt: Instant, val importSource: String)

/**
 * A sealed DEK persisted between the blob `POST` and the key `PUT`, so an upload interrupted in
 * between can still be finished: a file the server holds with no key ref is unreadable by every
 * client forever. See `PendingUploadKeyStore.swift`.
 */
@Serializable
data class PendingKey(
    val uploadId: String,
    val sealedFileKey: String,
    val keyVersion: Int,
    val fileName: String,
    val fileId: String? = null,
    val createdAtMillis: Long,
)

interface PendingKeyStore {
    fun get(uploadId: String): PendingKey?
    fun all(): List<PendingKey>
    fun put(record: PendingKey)
    fun remove(uploadId: String)
}

/** A small JSON file — a handful of records at most, written rarely. */
class FilePendingKeyStore(private val file: File) : PendingKeyStore {
    private val serializer = ListSerializer(PendingKey.serializer())

    @Synchronized
    override fun all(): List<PendingKey> =
        runCatching { apiJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    override fun get(uploadId: String) = all().firstOrNull { it.uploadId == uploadId }

    @Synchronized
    override fun put(record: PendingKey) = write(all().filter { it.uploadId != record.uploadId } + record)

    @Synchronized
    override fun remove(uploadId: String) = write(all().filter { it.uploadId != uploadId })

    private fun write(records: List<PendingKey>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(apiJson.encodeToString(serializer, records))
        tmp.renameTo(file)
    }
}

/**
 * The end-to-end-encrypted upload, step for step the protocol `E2EEUploader.swift` and the web's
 * `uploadEncryptedFile` run:
 *
 * 0. finish an earlier attempt whose blob committed but whose key never did
 * 1. refuse unless this device's key is the account's published key ([DeviceKeyCheck])
 * 2. a fresh DEK (or the one an interrupted attempt already sealed), and the content encrypted
 *    with it — streamed to a temp file, never whole in memory
 * 3. `{name, mimeType}` encrypted with the same DEK
 * 4. the DEK sealed to this device's active key, recorded locally before anything is sent
 * 5. one multipart `POST /drive/files/upload` carrying ciphertext, sealed key and dates, so the
 *    server commits the file and its key together
 * 6. a `PUT /files/{id}/key` as well when the server did not echo `importSource` (an older server
 *    that ignored the extra fields) — an upsert, harmless otherwise
 *
 * The cover thumbnail is made by the caller from the plaintext, because the server cannot make one
 * from ciphertext — it is sent unencrypted, as the web sends it.
 */
class Uploader(
    private val api: NeutrinoApi,
    private val keys: KeyringStore,
    private val keyCheck: DeviceKeyCheck,
    private val pending: PendingKeyStore,
    private val tempDir: File,
) {

    data class Progress(val sent: Long, val total: Long)

    suspend fun upload(
        source: UploadSource,
        folderId: String?,
        thumbnailBase64: String? = null,
        importMetadata: ImportMetadata? = null,
        uploadId: String = UUID.randomUUID().toString(),
        progress: (Progress) -> Unit = {},
    ): FileDto = withContext(Dispatchers.IO) {
        finishCommitted(uploadId)?.let { return@withContext it }

        val (active, publishedVersion) = keyCheck.sealingVersion()
        val keyring = keys.load(api.auth.userId ?: throw ApiException.NotSignedIn()) ?: throw KeyException.NoEncryptionKey()

        // A resumed upload re-encrypts under the DEK it was already sealed with, so the key on
        // record still matches whatever ciphertext reaches the server.
        val resumed = pending.get(uploadId)
        val resumedDek = resumed?.let { keyring.openDek(it.sealedFileKey, it.keyVersion)?.first }
        val dek = resumedDek ?: FileCrypto.generateDek()

        tempDir.mkdirs()
        val cipherFile = File.createTempFile("nd-upload-", ".bin", tempDir)
        try {
            cipherFile.outputStream().buffered().use { out ->
                source.open().use { input -> SecretStream.encrypt(input, out, dek) }
            }
            val encryptedMetadata = FileCrypto.encryptMetadata(source.name, source.mimeType, dek)
            val (sealed, keyVersion) = if (resumed != null && resumedDek != null) {
                resumed.sealedFileKey to resumed.keyVersion
            } else {
                FileCrypto.sealDek(dek, active.publicKey) to publishedVersion
            }
            dek.fill(0)

            pending.put(
                PendingKey(uploadId, sealed, keyVersion, source.name, null, resumed?.createdAtMillis ?: System.currentTimeMillis()),
            )

            val body = multipartBody(
                encryptedMetadata = encryptedMetadata,
                folderId = folderId,
                thumbnailBase64 = thumbnailBase64,
                sealedFileKey = sealed,
                keyVersion = keyVersion,
                importMetadata = importMetadata,
                fileName = source.name,
                mimeType = source.mimeType,
                content = ProgressFileBody(cipherFile, "application/octet-stream".toMediaTypeOrNull()) { sent, total ->
                    progress(Progress(sent, total))
                },
            )
            val result = api.upload(body)
            pending.put(pending.get(uploadId)!!.copy(fileId = result.id))
            if (result.importSource == null) api.setFileKey(result.id, sealed, keyVersion)
            pending.remove(uploadId)
            result
        } finally {
            cipherFile.delete()
        }
    }

    /**
     * Finishes an upload a previous attempt left half-committed. A record with a file id means the
     * ciphertext is on the server and only the key never made it — re-posting would make a second
     * file and leave the first unreadable.
     */
    private suspend fun finishCommitted(uploadId: String): FileDto? {
        val record = pending.get(uploadId) ?: return null
        val fileId = record.fileId ?: return null
        api.setFileKey(fileId, record.sealedFileKey, record.keyVersion)
        val result = api.fileMetadata(fileId)
        pending.remove(uploadId)
        return result
    }

    /**
     * Sends every sealed key whose blob committed but whose `PUT` never ran. Run at launch and on
     * foreground; nothing here throws — a failure leaves the record for next time. Records with no
     * file id are left for a retry of the upload itself, or dropped after a week.
     */
    suspend fun reconcilePendingKeys() {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        for (record in pending.all()) {
            val fileId = record.fileId
            if (fileId == null) {
                if (record.createdAtMillis < weekAgo) pending.remove(record.uploadId)
                continue
            }
            try {
                api.setFileKey(fileId, record.sealedFileKey, record.keyVersion)
                pending.remove(record.uploadId)
            } catch (e: ApiException.Http) {
                // The file is gone — the key protects nothing.
                if (e.status == 404) pending.remove(record.uploadId)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /**
         * The upload request body. **Part order is load-bearing**: the server reads scalar fields
         * as it walks the stream and returns as soon as it has consumed the file part, so every
         * scalar — `thumbnail_b64` included — precedes the blob.
         */
        fun multipartBody(
            encryptedMetadata: String,
            folderId: String?,
            thumbnailBase64: String?,
            sealedFileKey: String?,
            keyVersion: Int?,
            importMetadata: ImportMetadata?,
            fileName: String,
            mimeType: String,
            content: RequestBody,
        ): MultipartBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("encrypted_metadata", encryptedMetadata)
            folderId?.let { addFormDataPart("folder_id", it) }
            // The cover, in the clear by design: the server serves it to a browser that has not
            // unlocked a key yet, and the web posts it the same way.
            thumbnailBase64?.let { addFormDataPart("thumbnail_b64", it) }
            if (sealedFileKey != null && keyVersion != null) {
                addFormDataPart("encrypted_file_key", sealedFileKey)
                addFormDataPart("key_version", keyVersion.toString())
            }
            importMetadata?.let {
                addFormDataPart("created_at", DriveDate.wire(it.createdAt))
                addFormDataPart("updated_at", DriveDate.wire(it.updatedAt))
                addFormDataPart("import_source", it.importSource)
            }
            // The blob's part carries the *plaintext* type, which the server stores on the row.
            addFormDataPart("file", fileName, TypedBody(content, mimeType.toMediaTypeOrNull()))
        }.build()
    }
}

/** Streams a file, reporting bytes written. */
class ProgressFileBody(
    private val file: File,
    private val type: MediaType?,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = file.length()
    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        file.source().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val n = source.read(buffer, 64 * 1024)
                if (n < 0) break
                sink.write(buffer, n)
                sent += n
                onProgress(sent, total)
            }
        }
    }
}

/** Re-labels a body's content type without copying it. */
private class TypedBody(private val inner: RequestBody, private val type: MediaType?) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = inner.contentLength()
    override fun writeTo(sink: BufferedSink) = inner.writeTo(sink)
}

/**
 * Download and decrypt, the mirror image of [Uploader]:
 *
 * 1. `GET /files/{id}/key` → the sealed DEK and the key version it names
 * 2. open it with that keyring entry — then, failing that, every other one, newest first (a ref
 *    filed under the wrong number is re-filed under the one that opened it)
 * 3. stream the blob through [SecretStream] into a temp file, renamed into place only once the MAC
 *    has verified
 *
 * A historical version decrypts with the file's *current* DEK: the server stores no per-version
 * key, and clients reuse a file's DEK across saves.
 */
class Downloader(private val api: NeutrinoApi, private val keys: KeyringStore) {

    sealed class DownloadException(message: String) : Exception(message) {
        class DecryptionFailed : DownloadException("This file could not be decrypted — your key may not match this account, or the file is damaged.")
    }

    suspend fun download(
        fileId: String,
        destination: File,
        versionId: String? = null,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val ref = api.fileKey(fileId)
        val dek: ByteArray? = ref?.let {
            val keyring = keys.load(api.auth.userId ?: throw ApiException.NotSignedIn())
                ?: throw KeyException.NoEncryptionKey()
            val named = it.keyVersion ?: 1
            val (dek, openedWith) = keyring.openDek(it.encryptedFileKey, named)
                ?: throw if (keyring.entry(named) == null) KeyringException.MissingVersion(named)
                else DownloadException.DecryptionFailed()
            if (openedWith != named) runCatching { api.setFileKey(fileId, it.encryptedFileKey, openedWith) }
            dek
        }

        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, ".${destination.name}.part")
        try {
            api.openBlob(fileId, versionId).use { response ->
                val body = response.body ?: throw ApiException.Decode("empty body")
                val total = body.contentLength()
                CountingStream(body.byteStream()) { progress(it, total) }.use { input ->
                    partial.outputStream().buffered().use { out ->
                        if (dek == null) {
                            // No key ref: a legacy plaintext upload. Nothing to decrypt.
                            input.copyTo(out)
                        } else if (!SecretStream.decrypt(input, out, dek)) {
                            throw DownloadException.DecryptionFailed()
                        }
                    }
                }
            }
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) throw java.io.IOException("Could not save ${destination.name}")
        } finally {
            dek?.fill(0)
            partial.delete()
        }
    }
}

private class CountingStream(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = super.read().also { if (it >= 0) report(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) report(it.toLong()) }
    private fun report(n: Long) {
        count += n
        onCount(count)
    }
}
