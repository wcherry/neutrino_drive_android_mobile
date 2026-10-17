package com.neutrino.drive

import android.content.Context
import com.neutrino.drive.core.api.AppConfig
import com.neutrino.drive.core.api.NeutrinoApi
import com.neutrino.drive.core.drive.Downloader
import com.neutrino.drive.core.drive.FilePendingKeyStore
import com.neutrino.drive.core.drive.LocalSearchIndex
import com.neutrino.drive.core.drive.SharingService
import com.neutrino.drive.core.drive.Uploader
import com.neutrino.drive.storage.OfflineStore
import com.neutrino.drive.storage.Settings
import com.neutrino.drive.transfer.ThumbnailCache
import com.neutrino.shared.android.NeutrinoAccount
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File

/**
 * Every long-lived object, built once per process.
 *
 * Built in `Application.onCreate` rather than by the first screen, because WorkManager can start
 * this process with no activity at all to run photo backup or finish an upload — the iOS app hit
 * exactly this when its wiring lived in a view's `.task` (see `NeutrinoDriveApp.init`).
 */
class AppGraph(context: Context) {
    val context: Context = context.applicationContext

    /** Sign-in, the session, the keyring and the HTTP client, from neutrino_shared_android. */
    val account = NeutrinoAccount(this.context, AppConfig.config, "NeutrinoDrive-Android/${BuildConfig.VERSION_NAME}") { http, auth ->
        NeutrinoApi(http, auth)
    }
    val settings = Settings(this.context, account.settings)
    val keyringStore = account.keyringStore
    val http: OkHttpClient = account.http
    val auth = account.auth
    val api: NeutrinoApi = account.api
    val keyCheck = account.keyCheck
    val keys = account.keys

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

    val signedIn: StateFlow<Boolean> = account.signedIn

    /** Bumped whenever the keyring changes, so screens re-read whether a key is present. */
    val keyringVersion = account.keyringVersion

    init {
        // A sign-out of either kind — chosen, or the server ending the session — forgets the
        // last account's listings.
        account.onSignOut {
            searchIndex.replace(emptyList())
            thumbnails.clear()
        }
    }

    fun onSignedIn() = account.onSignedIn()

    fun signOut() = account.signOut()

    fun setServerUrl(url: String) = account.setServerUrl(url)

    fun keyringChanged() = account.keyringChanged()
}
