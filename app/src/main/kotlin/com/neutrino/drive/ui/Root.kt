package com.neutrino.drive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neutrino.drive.core.drive.AppLink
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.graph
import com.neutrino.drive.ui.files.DriveViewModel
import com.neutrino.drive.ui.files.FilesScreen
import com.neutrino.drive.ui.files.OfflineScreen
import com.neutrino.drive.ui.files.SearchScreen
import com.neutrino.drive.ui.files.ShareScreen
import com.neutrino.drive.ui.files.VersionsScreen
import com.neutrino.drive.ui.files.WebViewerScreen
import com.neutrino.drive.ui.files.ZipScreen
import com.neutrino.drive.ui.files.openItem
import com.neutrino.drive.ui.settings.KeyOptionsScreen
import com.neutrino.drive.ui.settings.PhotoBackupScreen
import com.neutrino.drive.ui.settings.SettingsScreen
import com.neutrino.drive.ui.settings.ShowKitScreen
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.LockScreen
import com.neutrino.shared.android.ui.auth.LoginScreen
import com.neutrino.shared.android.ui.auth.RegisterScreen
import com.neutrino.shared.android.ui.keys.EncryptionSetupScreen
import com.neutrino.shared.android.ui.keys.KeyCodeScreen
import com.neutrino.shared.android.ui.keys.PairingScreen
import com.neutrino.shared.android.ui.keys.RecoveryKitScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Signed out → sign-in; just registered → encryption setup; otherwise the app. The lock sits over all of it. */
@Composable
fun Root(pendingLink: StateFlow<String?>, consumeLink: () -> Unit) {
    val graph = LocalContext.current.graph
    val signedIn by graph.signedIn.collectAsState()
    val locked by AppLock.locked.collectAsState()
    var justRegistered by rememberSaveable { mutableStateOf(false) }
    var registering by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            !signedIn -> if (registering) {
                BackHandler { registering = false }
                RegisterScreen(onBack = { registering = false }, onRegistered = { justRegistered = true; registering = false })
            } else {
                LoginScreen(onRegister = { registering = true })
            }
            justRegistered -> Surface(Modifier.fillMaxSize()) {
                Box(Modifier.padding(top = 32.dp)) { EncryptionSetupScreen(onDone = { justRegistered = false }) }
            }
            // Keyed on the account, so a different sign-in never sees the last one's listings.
            else -> androidx.compose.runtime.key(graph.auth.userId) { Home(pendingLink, consumeLink) }
        }
        if (locked && signedIn) LockScreen()
    }
}

@Composable
private fun Home(pendingLink: StateFlow<String?>, consumeLink: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val userId = graph.auth.userId.orEmpty()
    val nav: NavViewModel = viewModel(key = "nav-$userId")
    val vm: DriveViewModel = viewModel(key = "drive-$userId")
    val snackbar = remember { SnackbarHostState() }
    val link by pendingLink.collectAsState()

    BackHandler(enabled = nav.canPop) { nav.pop() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // An inbound app link: fetch the file's metadata (it may be in a folder never opened) and open it.
    LaunchedEffect(link) {
        val url = link ?: return@LaunchedEffect
        consumeLink()
        val destination = AppLink.parse(url) ?: return@LaunchedEffect
        runCatching { graph.api.fileMetadata(destination.fileId) }
            .onSuccess { openItem(DriveItem.of(it), vm, { r -> nav.pushOn(Tab.FILES, r) }, context) }
            .onFailure { snackbar.showSnackbar("That file could not be opened: ${it.message}") }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = nav.tab == t, onClick = { nav.select(t) }, label = { Text(t.label) },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.FILES -> Icons.Filled.Folder
                                    Tab.RECENTS -> Icons.Filled.History
                                    Tab.OFFLINE -> Icons.Filled.DownloadForOffline
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                null,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            RouteContent(nav.current, nav, vm)
        }
    }
}

@Composable
private fun RouteContent(route: Route, nav: NavViewModel, vm: DriveViewModel) {
    val back: () -> Unit = { nav.pop() }
    when (route) {
        is Route.Browse -> FilesScreen(
            place = route.place, vm = vm, isRoot = !nav.canPop, canPop = nav.canPop, onBack = back,
            navigate = nav::push, switchSection = { nav.root(Route.Browse(it)) },
        )
        Route.Search -> SearchScreen(vm, back, nav::push)
        Route.Offline -> OfflineScreen()
        is Route.Zip -> ZipScreen(route.file, route.name, back)
        is Route.Web -> WebViewerScreen(route.item, back)
        is Route.Versions -> VersionsScreen(route.item, vm, back)
        is Route.Share -> ShareScreen(route.item, back)
        Route.Settings -> SettingsScreen(nav::push)
        Route.KeyOptions -> KeyOptionsScreen(nav::push, back)
        Route.Pairing -> KeyFlow(back) { PairingScreen(it) }
        Route.RecoveryKit -> KeyFlow(back) { RecoveryKitScreen(it) }
        Route.KeyCode -> KeyFlow(back) { KeyCodeScreen(it) }
        Route.Provision -> KeyFlow(back) { EncryptionSetupScreen(it) }
        Route.ShowKit -> ShowKitScreen(back)
        Route.PhotoBackup -> PhotoBackupScreen(back)
    }
}

/** A key screen inside the app: back closes it, and finishing pops back to where it was opened. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyFlow(onBack: () -> Unit, content: @Composable (onDone: () -> Unit) -> Unit) {
    val nav: NavViewModel = viewModel(key = "nav-${LocalContext.current.graph.auth.userId.orEmpty()}")
    Scaffold(topBar = {
        TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding -> Box(Modifier.padding(padding)) {
        content {
            // Done: drop the key screens, leaving whatever opened them.
            while (nav.current is Route.Pairing || nav.current is Route.RecoveryKit || nav.current is Route.KeyCode ||
                nav.current is Route.Provision || nav.current is Route.KeyOptions
            ) {
                if (!nav.pop()) break
            }
        }
    } }
}
