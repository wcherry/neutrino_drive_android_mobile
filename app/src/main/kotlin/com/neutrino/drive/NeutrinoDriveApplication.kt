package com.neutrino.drive

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.neutrino.drive.transfer.PhotoBackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NeutrinoDriveApplication : Application() {

    lateinit var graph: AppGraph
        private set

    /** Work that should outlive any one screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        createNotificationChannel()
        PhotoBackupWorker.schedule(this, graph.settings)

        // On every return to the foreground: finish uploads whose key never reached the server,
        // and top up retired keys from the account's key file — the same housekeeping the iOS app
        // runs on `scenePhase == .active`. Cheap when there is nothing to do.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!graph.auth.isSignedIn) return
                appScope.launch {
                    runCatching { graph.uploader.reconcilePendingKeys() }
                    if (graph.keys.hasKey) runCatching { graph.keys.pullKeyFile() }.onSuccess { graph.keyringChanged() }
                }
            }
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_TRANSFERS, getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_TRANSFERS = "transfers"
    }
}

val Context.graph: AppGraph get() = (applicationContext as NeutrinoDriveApplication).graph
val Context.appScope: CoroutineScope get() = (applicationContext as NeutrinoDriveApplication).appScope
