package com.neutrino.drive

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.neutrino.drive.ui.FileOpener
import com.neutrino.drive.ui.Root
import com.neutrino.drive.ui.theme.NeutrinoTheme
import com.neutrino.shared.android.LocalNeutrinoAccount
import com.neutrino.shared.android.security.AppLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The one activity. A `FragmentActivity` because `BiometricPrompt` needs one; everything on
 * screen is Compose.
 */
class MainActivity : FragmentActivity() {

    /** An inbound `https://www.getneutrino.app/open/…` link, held until the app can show it. */
    private val pendingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = graph.settings
        if (savedInstanceState == null) {
            AppLock.lockOnLaunch(settings.appLock.value)
            FileOpener.prune(this)
            handle(intent)
        }
        // With the lock on, the recents screen shows a blank card rather than file names.
        lifecycleScope.launch {
            settings.appLock.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            CompositionLocalProvider(LocalNeutrinoAccount provides graph.account) {
                NeutrinoTheme {
                    Root(pendingLink) { pendingLink.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground(graph.settings.appLock.value)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let { pendingLink.value = it }
    }
}
