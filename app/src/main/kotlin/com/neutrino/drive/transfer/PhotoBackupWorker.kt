package com.neutrino.drive.transfer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neutrino.drive.AppGraph
import com.neutrino.drive.core.api.ApiException
import com.neutrino.drive.core.drive.ImportMetadata
import com.neutrino.drive.core.drive.KeyException
import com.neutrino.drive.core.drive.UploadSource
import com.neutrino.drive.graph
import com.neutrino.drive.storage.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Backs new photos (and optionally videos) up to an encrypted Drive folder — the Android
 * counterpart of the iOS app's `PhotoSyncService`, and much simpler for it: there is no
 * BGTaskScheduler lottery or extension handoff. WorkManager runs this every 15 minutes within the
 * user's network/charging constraints, and a content-URI trigger runs it soon after a photo is
 * taken.
 *
 * Same rules as iOS: only media added after backup was turned on (minus an optional backfill),
 * into a folder found or created by name, each upload carrying the photo's own dates and an
 * `import_source` of `photo-sync:android:<kind>:<MediaStore id>`. That string is also the upload
 * id, so an interrupted upload is finished rather than duplicated. A photo that keeps failing for
 * a non-transient reason is given up on after five attempts rather than retried forever.
 */
class PhotoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * The periodic job, the content trigger and "Back up now" can all be due at once. Run
     * concurrently, each would find the folder missing and create one, and upload the same photo:
     * so runs are serialised, and each reads the ledger and folder id only once it holds the lock.
     */
    override suspend fun doWork(): Result = RUN_LOCK.withLock { run() }

    private suspend fun run(): Result {
        val graph = applicationContext.graph
        val settings = graph.settings
        try {
            if (!settings.photoBackupEnabled || !graph.auth.isSignedIn) return Result.success()
            if (!graph.keys.hasKey) return finish(settings, "Paused: this device has no encryption key.")
            if (!hasPermission(applicationContext)) return finish(settings, "Paused: photo access is off.")

            val ledger = Ledger.load(applicationContext)
            val candidates = scan(applicationContext, settings).filter { it.key !in ledger.done && (ledger.failures[it.key] ?: 0) < MAX_FAILURES }
            if (candidates.isEmpty()) return finish(settings, "Up to date.")

            val folderId = ensureFolder(graph)
            var uploaded = 0
            for ((i, media) in candidates.withIndex()) {
                if (isStopped) break
                runCatching {
                    setForeground(foreground(NOTIFICATION_ID, progressNotification(applicationContext, "Backing up photos", "${i + 1} of ${candidates.size}", i.toFloat() / candidates.size)))
                }
                try {
                    upload(graph, media, folderId)
                    ledger.done += media.key
                    ledger.failures.remove(media.key)
                    uploaded++
                } catch (e: KeyException) {
                    ledger.save(applicationContext)
                    return finish(settings, e.message ?: "Paused: encryption key problem.")
                } catch (e: ApiException) {
                    if (e.isTransient) {
                        ledger.save(applicationContext)
                        settings.photoBackupLastResult = "Waiting for the network — $uploaded backed up so far."
                        return Result.retry()
                    }
                    if (e.httpStatus == 404 && settings.photoBackupFolderId != null) {
                        // The backup folder was deleted on another device; find or make it again next run.
                        settings.photoBackupFolderId = null
                        ledger.save(applicationContext)
                        return Result.retry()
                    }
                    ledger.failures[media.key] = (ledger.failures[media.key] ?: 0) + 1
                } catch (e: Exception) {
                    ledger.failures[media.key] = (ledger.failures[media.key] ?: 0) + 1
                }
                ledger.save(applicationContext)
            }
            val remaining = candidates.size - uploaded
            return finish(settings, if (remaining == 0) "Backed up $uploaded item${if (uploaded == 1) "" else "s"}." else "Backed up $uploaded; $remaining waiting.")
        } finally {
            // Content-URI triggers fire once; re-arm for the next photo.
            if (settings.photoBackupEnabled) enqueueTrigger(applicationContext, settings)
        }
    }

    private fun finish(settings: Settings, result: String): Result {
        settings.photoBackupLastRun = System.currentTimeMillis()
        settings.photoBackupLastResult = result
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(NOTIFICATION_ID, progressNotification(applicationContext, "Backing up photos", "", 0f))

    private suspend fun upload(graph: AppGraph, media: Media, folderId: String) {
        val resolver = applicationContext.contentResolver
        // The original, with its location intact — MediaStore redacts EXIF GPS otherwise.
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { MediaStore.setRequireOriginal(media.uri) }.getOrDefault(media.uri)
        } else media.uri
        val importSource = "photo-sync:android:${media.kind}:${media.id}"
        graph.uploader.upload(
            UploadSource(media.name, media.mimeType) { resolver.openInputStream(source) ?: resolver.openInputStream(media.uri)!! },
            folderId,
            thumbnailBase64 = ThumbnailGenerator.forUri(resolver, media.uri, media.mimeType),
            importMetadata = ImportMetadata(media.takenAt, media.modifiedAt, importSource),
            uploadId = importSource,
        )
    }

    /** Find-or-create by case-insensitive name at the drive root, as iOS's `ensureFolder` does. */
    private suspend fun ensureFolder(graph: AppGraph): String {
        val settings = graph.settings
        settings.photoBackupFolderId?.let { return it }
        val name = settings.photoBackupFolderName
        val root = graph.api.folder(null)
        val id = root.folders.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            ?: graph.api.createFolder(name, null).id
        settings.photoBackupFolderId = id
        return id
    }

    data class Media(
        val kind: String, val id: Long, val uri: Uri, val name: String, val mimeType: String,
        val takenAt: Instant, val modifiedAt: Instant,
    ) {
        val key get() = "$kind:$id"
    }

    /** Uploaded and given-up-on media, by key. A small JSON file in no-backup storage. */
    @Serializable
    class Ledger(val done: MutableSet<String> = mutableSetOf(), val failures: MutableMap<String, Int> = mutableMapOf()) {
        fun save(context: Context) {
            val tmp = File(context.noBackupFilesDir, "photo-backup.tmp")
            tmp.writeText(json.encodeToString(serializer(), this))
            tmp.renameTo(file(context))
        }

        companion object {
            private val json = Json { ignoreUnknownKeys = true }
            private fun file(context: Context) = File(context.noBackupFilesDir, "photo-backup.json")
            fun load(context: Context): Ledger =
                runCatching { json.decodeFromString(serializer(), file(context).readText()) }.getOrDefault(Ledger())
            fun reset(context: Context) = file(context).delete()
        }
    }

    companion object {
        private val RUN_LOCK = Mutex()
        private const val NOTIFICATION_ID = 4201
        private const val MAX_FAILURES = 5
        private const val PERIODIC = "photo-backup"
        private const val TRIGGER = "photo-backup-trigger"
        private const val NOW = "photo-backup-now"

        fun requiredPermissions(includeVideos: Boolean): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                if (includeVideos) add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }.toTypedArray()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        /** Full or partial (Android 14 "selected photos") access is enough to run. */
        fun hasPermission(context: Context): Boolean {
            fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> granted(Manifest.permission.READ_MEDIA_IMAGES)
                else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        fun scan(context: Context, settings: Settings): List<Media> {
            val since = settings.photoBackupAnchor - settings.photoBackupBackfillDays * 86_400L
            val out = mutableListOf<Media>()
            out += query(context, "image", images(), since)
            if (settings.photoBackupVideos) out += query(context, "video", videos(), since)
            return out.sortedBy { it.takenAt }
        }

        private fun images(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        private fun videos(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        private fun query(context: Context, kind: String, collection: Uri, since: Long): List<Media> {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN,
            )
            val out = mutableListOf<Media>()
            runCatching {
                context.contentResolver.query(
                    collection, projection, "${MediaStore.MediaColumns.DATE_ADDED} >= ?", arrayOf(since.toString()),
                    "${MediaStore.MediaColumns.DATE_ADDED} ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val added = c.getLong(3)
                        val modified = c.getLong(4).takeIf { it > 0 } ?: added
                        val taken = c.getLong(5).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) } ?: Instant.ofEpochSecond(added)
                        out += Media(
                            kind, id, ContentUris.withAppendedId(collection, id),
                            c.getString(1) ?: "$kind-$id", c.getString(2) ?: if (kind == "image") "image/jpeg" else "video/mp4",
                            taken, Instant.ofEpochSecond(modified),
                        )
                    }
                }
            }
            return out
        }

        private fun constraints(settings: Settings) = Constraints.Builder()
            .setRequiredNetworkType(if (settings.photoBackupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(settings.photoBackupChargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Applies the current settings: schedules both triggers, or cancels them. */
        fun schedule(context: Context, settings: Settings) {
            val work = WorkManager.getInstance(context)
            if (!settings.photoBackupEnabled) {
                work.cancelUniqueWork(PERIODIC)
                work.cancelUniqueWork(TRIGGER)
                return
            }
            work.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PhotoBackupWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(settings)).build(),
            )
            enqueueTrigger(context, settings)
        }

        private fun enqueueTrigger(context: Context, settings: Settings) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.photoBackupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.photoBackupChargingOnly)
                .addContentUriTrigger(images(), true)
                .apply { if (settings.photoBackupVideos) addContentUriTrigger(videos(), true) }
                .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                TRIGGER, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PhotoBackupWorker>().setConstraints(constraints).build(),
            )
        }

        /** "Back up now" — still honours the network and charging choices. */
        fun runNow(context: Context, settings: Settings) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PhotoBackupWorker>().setConstraints(constraints(settings)).build(),
            )
        }
    }
}
