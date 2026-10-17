package com.neutrino.drive.storage

import android.content.Context
import com.neutrino.shared.android.AccountSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Non-secret preferences. Secrets live in the shared `SecureStore`. The account's own (server,
 * device name, app lock) are the shared [AccountSettings], in the same `nd.settings` file under the
 * same keys Drive always used; photo backup's are Drive's alone.
 */
class Settings(context: Context, private val account: AccountSettings) {

    private val prefs = account.prefs

    var serverUrl: String
        get() = account.serverUrl
        set(value) {
            account.serverUrl = value
        }

    var customDeviceName: String?
        get() = account.customDeviceName
        set(value) {
            account.customDeviceName = value
        }

    val deviceName: String get() = account.deviceName

    val appLock: StateFlow<Boolean> get() = account.appLock
    fun setAppLock(enabled: Boolean) = account.setAppLock(enabled)

    // ── Photo backup ────────────────────────────────────────────────────────────────────────────

    var photoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_PHOTO_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PHOTO_ENABLED, value).apply()

    var photoBackupFolderName: String
        get() = prefs.getString(KEY_PHOTO_FOLDER, null) ?: DEFAULT_PHOTO_FOLDER
        set(value) = prefs.edit().putString(KEY_PHOTO_FOLDER, value.trim().ifEmpty { DEFAULT_PHOTO_FOLDER })
            .remove(KEY_PHOTO_FOLDER_ID).apply()

    var photoBackupFolderId: String?
        get() = prefs.getString(KEY_PHOTO_FOLDER_ID, null)
        set(value) = prefs.edit().putString(KEY_PHOTO_FOLDER_ID, value).apply()

    var photoBackupVideos: Boolean
        get() = prefs.getBoolean(KEY_PHOTO_VIDEOS, false)
        set(value) = prefs.edit().putBoolean(KEY_PHOTO_VIDEOS, value).apply()

    var photoBackupWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_PHOTO_WIFI, true)
        set(value) = prefs.edit().putBoolean(KEY_PHOTO_WIFI, value).apply()

    var photoBackupChargingOnly: Boolean
        get() = prefs.getBoolean(KEY_PHOTO_CHARGING, false)
        set(value) = prefs.edit().putBoolean(KEY_PHOTO_CHARGING, value).apply()

    /**
     * Photos added before this moment (epoch seconds) are not backed up — turning backup on does
     * not upload a whole library unasked. [photoBackupBackfillDays] reaches back from it.
     */
    var photoBackupAnchor: Long
        get() = prefs.getLong(KEY_PHOTO_ANCHOR, 0)
        set(value) = prefs.edit().putLong(KEY_PHOTO_ANCHOR, value).apply()

    var photoBackupBackfillDays: Int
        get() = prefs.getInt(KEY_PHOTO_BACKFILL, 0)
        set(value) = prefs.edit().putInt(KEY_PHOTO_BACKFILL, value).apply()

    var photoBackupLastRun: Long
        get() = prefs.getLong(KEY_PHOTO_LAST_RUN, 0)
        set(value) = prefs.edit().putLong(KEY_PHOTO_LAST_RUN, value).apply()

    var photoBackupLastResult: String?
        get() = prefs.getString(KEY_PHOTO_LAST_RESULT, null)
        set(value) = prefs.edit().putString(KEY_PHOTO_LAST_RESULT, value).apply()

    companion object {
        const val DEFAULT_PHOTO_FOLDER = "Android Photos"
        private const val KEY_PHOTO_ENABLED = "photoSync.enabled"
        private const val KEY_PHOTO_FOLDER = "photoSync.folderName"
        private const val KEY_PHOTO_FOLDER_ID = "photoSync.folderID"
        private const val KEY_PHOTO_VIDEOS = "photoSync.includeVideos"
        private const val KEY_PHOTO_WIFI = "photoSync.wifiOnly"
        private const val KEY_PHOTO_CHARGING = "photoSync.whileChargingOnly"
        private const val KEY_PHOTO_ANCHOR = "photoSync.anchorDate"
        private const val KEY_PHOTO_BACKFILL = "photoSync.backfillDays"
        private const val KEY_PHOTO_LAST_RUN = "photoSync.lastRun"
        private const val KEY_PHOTO_LAST_RESULT = "photoSync.lastResult"
    }
}
