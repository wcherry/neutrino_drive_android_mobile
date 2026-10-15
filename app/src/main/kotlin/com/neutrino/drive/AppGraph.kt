package com.neutrino.drive

import android.content.Context
import android.os.Build
import com.neutrino.drive.core.api.AuthClient
import com.neutrino.drive.core.api.NeutrinoApi
import com.neutrino.drive.core.drive.DeviceKeyCheck
import com.neutrino.drive.core.drive.Downloader
import com.neutrino.drive.core.drive.FilePendingKeyStore
import com.neutrino.drive.core.drive.KeyManager
import com.neutrino.drive.core.drive.LocalSearchIndex
import com.neutrino.drive.core.drive.SharingService
import com.neutrino.drive.core.drive.Uploader
import com.neutrino.drive.storage.OfflineStore
import com.neutrino.drive.storage.SecureKeyringStore
import com.neutrino.drive.storage.SecureSessionStore
import com.neutrino.drive.storage.SecureStore
import com.neutrino.drive.storage.Settings
import com.neutrino.drive.transfer.ThumbnailCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every long-lived object, built once per process.
 *
 * Built in `Application.onCreate` rather than by the first screen, because WorkManager can start
 * this process with no activity at all to run photo backup or finish an upload — the iOS app hit
 * exactly this when its wiring lived in a view's `.task` (see `NeutrinoDriveApp.init`).
 */
class AppGraph(context: Context) {
    val context: Context = context.applicationContext
    val settings = Settings(this.context)
    private val secure = SecureStore(this.context)
    val keyringStore = SecureKeyringStore(secure)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Whole-file bodies: a large upload or download is one request.
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    val auth = AuthClient(http, SecureSessionStore(secure), settings.serverUrl) { settings.deviceName }
    val api = NeutrinoApi(http, auth)
    val keyCheck = DeviceKeyCheck(api, keyringStore)
    val keys = KeyManager(api, keyringStore, keyCheck)
    val uploader = Uploader(
        api, keyringStore, keyCheck,
        FilePendingKeyStore(File(this.context.noBackupFilesDir, "pending-upload-keys.json")),
        File(this.context.cacheDir, "upload"),
    )
    val downloader = Downloader(api, keyringStore)
    val sharing = SharingService(api, keyringStore)
    val offline = OfflineStore(this.context, downloader)
    val searchIndex = LocalSearchIndex()
    val thumbnails = ThumbnailCache(api)

    private val _signedIn = MutableStateFlow(auth.isSignedIn)
    val signedIn: StateFlow<Boolean> = _signedIn

    /** Bumped whenever the keyring changes, so screens re-read whether a key is present. */
    val keyringVersion = MutableStateFlow(0)

    init {
        auth.onSignedOut = { _signedIn.value = false }
    }

    fun onSignedIn() {
        _signedIn.value = true
    }

    fun signOut() {
        auth.logout()
        searchIndex.replace(emptyList())
        thumbnails.clear()
        _signedIn.value = false
    }

    fun setServerUrl(url: String) {
        settings.serverUrl = url
        auth.serverUrl = settings.serverUrl
    }

    fun keyringChanged() {
        keyringVersion.value++
    }

    private val userAgent = "NeutrinoDrive-Android/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
        .filter { it in ' '..'~' }
}
