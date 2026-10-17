package com.neutrino.drive.transfer

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.neutrino.drive.NeutrinoDriveApplication
import com.neutrino.drive.core.drive.UploadSource
import com.neutrino.drive.graph
import com.neutrino.drive.storage.OfflineStore
import com.neutrino.shared.core.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Uploads the user started — from the Files screen or the share sheet — as WorkManager jobs, so
 * they survive the user leaving the app; Android's equivalent of the iOS app's background
 * `URLSession`.
 *
 * A picked or shared `content://` URI is only readable while the grant lasts, so [Outbox.enqueue]
 * copies each file into app-private, never-backed-up storage first and the job works from that
 * copy, deleting it once uploaded. Each file is a stable upload id (`<job>:<n>`), so a retry after
 * the process died finishes the upload it interrupted instead of making a second file.
 */
object Outbox {
    const val TAG = "upload"

    @Serializable
    data class Item(val path: String, val name: String, val mimeType: String, val uploadId: String)

    @Serializable
    data class Job(val id: String, val folderId: String?, val items: List<Item>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun root(context: Context) = File(context.noBackupFilesDir, "outbox").apply { mkdirs() }
    private fun manifest(context: Context, id: String) = File(root(context), "$id.json")

    fun load(context: Context, id: String): Job? =
        runCatching { json.decodeFromString(Job.serializer(), manifest(context, id).readText()) }.getOrNull()

    fun save(context: Context, job: Job) {
        val tmp = File(root(context), "${job.id}.tmp")
        tmp.writeText(json.encodeToString(Job.serializer(), job))
        tmp.renameTo(manifest(context, job.id))
    }

    fun delete(context: Context, id: String) {
        manifest(context, id).delete()
        File(root(context), id).deleteRecursively()
    }

    /** Copies `uris` into the outbox and starts a job. Returns the job id. */
    suspend fun enqueue(context: Context, uris: List<Uri>, folderId: String?): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root(context), id).apply { mkdirs() }
        val items = uris.mapIndexedNotNull { index, uri ->
            val (name, mime) = describe(context, uri)
            val target = File(dir, "$index-${OfflineStore.safeName(name)}")
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            }.getOrNull() ?: return@mapIndexedNotNull null
            Item(target.absolutePath, name, mime, "$id:$index")
        }
        save(context, Job(id, folderId, items))
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_JOB to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-$id", ExistingWorkPolicy.KEEP, request)
        id
    }

    /** Display name and MIME type of a content URI, with sensible fallbacks. */
    fun describe(context: Context, uri: Uri): Pair<String, String> {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        val finalName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = context.contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: mimeForName(finalName)
        return finalName to mime
    }

    fun mimeForName(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: when (name.substringAfterLast('.', "").lowercase()) {
                "md", "markdown" -> "text/markdown"
                else -> "application/octet-stream"
            }
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_JOB) ?: return Result.failure()
        var job = Outbox.load(applicationContext, id) ?: return Result.success()
        val graph = applicationContext.graph
        if (!graph.auth.isSignedIn) return Result.failure(workDataOf(KEY_ERROR to "You are not signed in."))

        val total = job.items.size
        runCatching { setForeground(foregroundInfo(job.items.firstOrNull()?.name ?: "", 0, total, 0f)) }

        for ((index, item) in job.items.toList().withIndex()) {
            if (isStopped) return Result.retry()
            val file = File(item.path)
            val done = index
            try {
                if (file.exists()) {
                    val thumbnail = ThumbnailGenerator.forUri(applicationContext.contentResolver, Uri.fromFile(file), item.mimeType)
                    graph.uploader.upload(
                        UploadSource(item.name, item.mimeType) { file.inputStream() },
                        job.folderId,
                        thumbnailBase64 = thumbnail,
                        uploadId = item.uploadId,
                    ) { p ->
                        val fraction = if (p.total > 0) p.sent.toFloat() / p.total else 0f
                        setProgressAsync(workDataOf(KEY_NAME to item.name, KEY_INDEX to done, KEY_COUNT to total, KEY_FRACTION to fraction))
                    }
                }
                file.delete()
                job = job.copy(items = job.items.filter { it.uploadId != item.uploadId })
                Outbox.save(applicationContext, job)
            } catch (e: ApiException) {
                if (e.isTransient && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
                return fail(id, item.name, e.message)
            } catch (e: Exception) {
                return fail(id, item.name, e.message)
            }
        }
        Outbox.delete(applicationContext, id)
        return Result.success(workDataOf(KEY_COUNT to total))
    }

    private fun fail(jobId: String, name: String, message: String?): Result {
        val text = "Could not upload $name: ${message ?: "unknown error"}"
        notifyFailure(text)
        Outbox.delete(applicationContext, jobId)
        return Result.failure(workDataOf(KEY_ERROR to text))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("", 0, 1, 0f)

    private fun foregroundInfo(name: String, index: Int, count: Int, fraction: Float): ForegroundInfo {
        val notification = progressNotification(
            applicationContext,
            if (count > 1) "Uploading ${index + 1} of $count" else "Uploading",
            name, fraction,
        )
        return foreground(NOTIFICATION_ID, notification)
    }

    private fun notifyFailure(text: String) = runCatching {
        NotificationManagerCompat.from(applicationContext).notify(
            FAILURE_NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, NeutrinoDriveApplication.CHANNEL_TRANSFERS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Upload failed")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .build(),
        )
    }

    companion object {
        const val KEY_JOB = "job"
        const val KEY_NAME = "name"
        const val KEY_INDEX = "index"
        const val KEY_COUNT = "count"
        const val KEY_FRACTION = "fraction"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 8
        private const val NOTIFICATION_ID = 4101
        private const val FAILURE_NOTIFICATION_ID = 4102
    }
}

internal fun progressNotification(context: Context, title: String, text: String, fraction: Float): Notification =
    NotificationCompat.Builder(context, NeutrinoDriveApplication.CHANNEL_TRANSFERS)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
        .setColor(context.getColor(android.R.color.holo_blue_dark))
        .build()

internal fun foreground(id: Int, notification: Notification): ForegroundInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(id, notification)
    }
