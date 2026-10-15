package com.neutrino.drive.storage

import android.content.Context
import android.os.Build
import android.provider.Settings as SystemSettings
import com.neutrino.drive.core.api.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Non-secret preferences. Secrets live in [SecureStore]. */
class Settings(private val context: Context) {

    private val prefs = context.getSharedPreferences("nd.settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null) ?: AppConfig.DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER, AppConfig.normalizeServerUrl(value)).apply()

    /**
     * How this device appears in the account's session list: "<device> - Neutrino Drive", as the
     * iOS apps name themselves (`DeviceIdentity`), unless the user picked a name.
     */
    var customDeviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value?.trim()?.takeIf { it.isNotEmpty() }).apply()

    val deviceName: String
        get() = customDeviceName ?: "${systemDeviceName()} - ${AppConfig.DISPLAY_NAME}"

    private fun systemDeviceName(): String =
        SystemSettings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private val _appLock = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK, false))
    val appLock: StateFlow<Boolean> = _appLock
    fun setAppLock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        _appLock.value = enabled
    }

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
        private const val KEY_SERVER = "nd.server_host"
        private const val KEY_DEVICE_NAME = "nd.device_name"
        private const val KEY_APP_LOCK = "nd.app_lock"
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
