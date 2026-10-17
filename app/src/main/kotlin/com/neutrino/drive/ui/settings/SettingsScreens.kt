package com.neutrino.drive.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.neutrino.drive.BuildConfig
import com.neutrino.drive.core.api.QuotaDto
import com.neutrino.drive.graph
import com.neutrino.drive.transfer.PhotoBackupWorker
import com.neutrino.drive.ui.ConfirmDialog
import com.neutrino.drive.ui.Route
import com.neutrino.drive.ui.SectionHeader
import com.neutrino.drive.ui.TextInputDialog
import com.neutrino.drive.ui.formatBytes
import com.neutrino.drive.ui.openUrl
import com.neutrino.drive.ui.theme.Brand
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.keys.KitBox
import com.neutrino.shared.android.ui.keys.copySensitive
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.RecoveryKit
import kotlinx.coroutines.launch

/** "Report a bug": the central tracker's form, prefilled with what a report needs — never the server address. */
object BugReport {
    fun url(context: Context): String {
        val server = context.graph.settings.serverUrl.lowercase()
        val kind = when {
            "getneutrino.app" in server -> "getneutrino.app (hosted)"
            "localhost" in server || "10.0.2.2" in server || "127.0.0.1" in server -> "localhost / development"
            else -> "Self-hosted"
        }
        return Uri.parse("https://github.com/wcherry/neutrino/issues/new").buildUpon()
            .appendQueryParameter("title", "[Android Drive] ")
            .appendQueryParameter(
                "body",
                "**What happened**\n\n\n**Steps to reproduce**\n\n\n---\nApp: Neutrino Drive for Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nServer: $kind\n",
            ).build().toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { content() }
    }
}

@Composable
fun SettingRow(icon: ImageVector, title: String, subtitle: String? = null, tint: androidx.compose.ui.graphics.Color? = null, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title, color = tint ?: MaterialTheme.colorScheme.onSurface) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
fun SettingsScreen(navigate: (Route) -> Unit) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val keyringVersion by graph.keyringVersion.collectAsState()
    val keyring = remember(keyringVersion) { graph.keys.keyring() }
    var keyStatus by remember { mutableStateOf<DeviceKeyCheck.Status?>(null) }
    var quota by remember { mutableStateOf<QuotaDto?>(null) }
    val appLock by graph.settings.appLock.collectAsState()
    val offline by graph.offline.files.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var editDeviceName by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(keyringVersion) {
        keyStatus = runCatching { graph.keyCheck.check() }.getOrNull()
    }
    LaunchedEffect(Unit) { quota = runCatching { graph.api.quota() }.getOrNull() }

    Page("Settings", null) {
        SectionHeader("Account")
        SettingRow(Icons.Filled.VerifiedUser, graph.auth.email ?: "Signed in", graph.settings.serverUrl.removePrefix("https://"))
        SettingRow(Icons.Filled.Devices, "Device name", graph.settings.deviceName, onClick = { editDeviceName = true })

        SectionHeader("Encryption key")
        if (keyring?.active != null) {
            SettingRow(Icons.Filled.Key, "Encryption key on this phone", "Key ${keyring.describe()}", tint = null, trailing = { Icon(Icons.Filled.CheckCircle, null, tint = Brand.success) })
            when (val s = keyStatus) {
                is DeviceKeyCheck.Status.Stale -> SettingRow(
                    Icons.Filled.Warning,
                    "This key is out of date",
                    "Your account's key was replaced on another device (now version ${s.published.version}). Uploads are paused. Remove this key and add your current one.",
                    tint = Brand.warning,
                )
                DeviceKeyCheck.Status.Unpublished -> SettingRow(Icons.Filled.Warning, "Your account publishes no key", "Uploads are paused until it does.", tint = Brand.warning)
                else -> Unit
            }
            SettingRow(Icons.Filled.TextSnippet, "Show recovery kit", "Your key on paper — the only copy if you lose every device") {
                scope.launch {
                    if (AppLock.authorizeKeyAccess(activity, "Show your recovery kit")) navigate(Route.ShowKit)
                }
            }
            SettingRow(Icons.Filled.Key, "Remove key from this phone", tint = MaterialTheme.colorScheme.error) { confirmRemove = true }
        } else {
            SettingRow(Icons.Filled.Warning, "No encryption key on this phone", "Add one to open and upload files.", tint = Brand.warning)
            SettingRow(Icons.Filled.Key, "Add encryption key", "Pair, recovery kit, key code, or set up a new key") { navigate(Route.KeyOptions) }
        }

        SectionHeader("Security")
        SettingRow(
            Icons.Filled.Lock, "App lock",
            if (AppLock.isAvailable(context)) "Require your fingerprint, face or PIN to open Drive" else "Set a screen lock on this phone to use this",
            trailing = {
                Switch(appLock, enabled = AppLock.isAvailable(context), onCheckedChange = { on ->
                    scope.launch {
                        // Turning it on proves the user can get back in before locking them out.
                        if (!on || AppLock.authenticate(activity, "Turn on app lock")) {
                            graph.settings.setAppLock(on)
                            if (on) AppLock.unlocked()
                        }
                    }
                })
            },
        )

        SectionHeader("Photo backup")
        SettingRow(
            Icons.Filled.Photo, "Back up my photos",
            if (graph.settings.photoBackupEnabled) "On · ${graph.settings.photoBackupFolderName}" else "Off",
        ) { navigate(Route.PhotoBackup) }

        SectionHeader("Storage")
        StorageRow(context, quota)
        SettingRow(Icons.Filled.Storage, "Offline files", "${offline.size} file${if (offline.size == 1) "" else "s"} · ${formatBytes(context, offline.sumOf { it.sizeBytes })}",
            trailing = { TextButton(onClick = { graph.offline.removeAll() }, enabled = offline.isNotEmpty()) { Text("Clear") } })

        SectionHeader("About")
        SettingRow(Icons.Filled.Shield, "Neutrino Drive ${BuildConfig.VERSION_NAME}", "Files are encrypted on this phone before upload.")
        SettingRow(Icons.Filled.BugReport, "Report a bug") { openUrl(context, BugReport.url(context)) }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingRow(Icons.AutoMirrored.Filled.Logout, "Sign out", tint = MaterialTheme.colorScheme.error) { confirmSignOut = true }
    }

    if (confirmRemove) {
        ConfirmDialog(
            "Remove encryption key?",
            "Your files will not open on this phone until you add the key again. Make sure you have your recovery kit or another device with the key — nobody can recover it for you.",
            "Remove", destructive = true, onDismiss = { confirmRemove = false },
        ) {
            scope.launch {
                if (AppLock.authorizeKeyAccess(activity, "Remove your encryption key")) {
                    graph.keys.remove()
                    graph.keyringChanged()
                } else error = "Authentication is required to remove your key."
            }
        }
    }
    if (confirmSignOut) {
        ConfirmDialog(
            "Sign out?", "Your encryption key stays on this phone, so signing back in will not need your recovery kit.",
            "Sign out", onDismiss = { confirmSignOut = false },
        ) { graph.signOut() }
    }
    if (editDeviceName) {
        TextInputDialog("Device name", graph.settings.deviceName, "Shown in your account's device list", "Save", onDismiss = { editDeviceName = false }) {
            editDeviceName = false
            graph.settings.customDeviceName = it
        }
    }
}

@Composable
private fun StorageRow(context: Context, quota: QuotaDto?) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        if (quota == null) {
            Text("Storage usage unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val used = formatBytes(context, quota.usedBytes)
        val total = quota.quotaBytes
        Text(if (total != null) "$used of ${formatBytes(context, total)} used" else "$used used of Unlimited")
        if (total != null && total > 0) {
            LinearProgressIndicator(progress = { (quota.usedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** The ways a key reaches this phone, best first — the iOS Settings › Encryption Key choices. */
@Composable
fun KeyOptionsScreen(navigate: (Route) -> Unit, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    var canProvision by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { canProvision = graph.keys.canProvision() }
    Page("Add encryption key", onBack) {
        Text(
            "Your key is created on your devices and never sent to the server, so this phone has to receive it from you.",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingRow(Icons.Filled.Devices, "Pair with another device", "Recommended — scan codes with the web app or your computer") { navigate(Route.Pairing) }
        SettingRow(Icons.Filled.TextSnippet, "Enter recovery kit", "The kit you saved when encryption was set up") { navigate(Route.RecoveryKit) }
        SettingRow(Icons.Filled.QrCodeScanner, "Scan key code", "The PIN-protected code the web shows, or an exported key file") { navigate(Route.KeyCode) }
        if (canProvision) {
            SectionHeader("New account")
            SettingRow(Icons.Filled.Key, "Set up a new key", "Your account has no key yet — create it on this phone") { navigate(Route.Provision) }
        }
    }
}

/** The recovery kit for the keyring on this phone, after the device-auth gate. */
@Composable
fun ShowKitScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyring = remember { context.graph.keys.keyring() }
    Page("Recovery kit", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (keyring == null) {
                Text("This phone has no key.")
            } else {
                val kit = remember { RecoveryKit.export(keyring) }
                Text(
                    "Anyone with this kit can read your files. Write it down and keep it somewhere safe; do not store it in this account.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KitBox(kit)
                OutlinedButton(onClick = { copySensitive(context, "Recovery kit", kit) }) { Text("Copy") }
            }
        }
    }
}

/** Photo backup settings — iOS's `PhotoSyncSettingsView`, for MediaStore and WorkManager. */
@Composable
fun PhotoBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val settings = graph.settings
    var enabled by remember { mutableStateOf(settings.photoBackupEnabled) }
    var videos by remember { mutableStateOf(settings.photoBackupVideos) }
    var wifi by remember { mutableStateOf(settings.photoBackupWifiOnly) }
    var charging by remember { mutableStateOf(settings.photoBackupChargingOnly) }
    var backfill by remember { mutableStateOf(settings.photoBackupBackfillDays) }
    var folder by remember { mutableStateOf(settings.photoBackupFolderName) }
    var editFolder by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    fun apply() = PhotoBackupWorker.schedule(context, settings)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PhotoBackupWorker.hasPermission(context)) {
            settings.photoBackupEnabled = true
            if (settings.photoBackupAnchor == 0L) settings.photoBackupAnchor = System.currentTimeMillis() / 1000
            enabled = true
            denied = false
            apply()
            PhotoBackupWorker.runNow(context, settings)
        } else denied = true
    }

    Page("Photo backup", onBack) {
        SettingRow(Icons.Filled.Photo, "Back up my photos", "New photos are encrypted on this phone and uploaded to “$folder”.", trailing = {
            Switch(enabled, onCheckedChange = { on ->
                if (on) permission.launch(PhotoBackupWorker.requiredPermissions(videos))
                else {
                    settings.photoBackupEnabled = false
                    enabled = false
                    apply()
                }
            })
        })
        if (denied) Text("Drive needs access to your photos to back them up.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        SettingRow(Icons.Filled.Storage, "Folder", folder) { editFolder = true }
        SettingRow(Icons.Filled.Photo, "Include videos", trailing = {
            Switch(videos, onCheckedChange = { videos = it; settings.photoBackupVideos = it; apply() })
        })
        SettingRow(Icons.Filled.Devices, "Wi-Fi only", "Wait for an unmetered network", trailing = {
            Switch(wifi, onCheckedChange = { wifi = it; settings.photoBackupWifiOnly = it; apply() })
        })
        SettingRow(Icons.Filled.Devices, "Only while charging", trailing = {
            Switch(charging, onCheckedChange = { charging = it; settings.photoBackupChargingOnly = it; apply() })
        })
        SectionHeader("Also back up photos from the last")
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            listOf(0 to "None", 7 to "7 days", 30 to "30 days", 365 to "Year").forEachIndexed { i, (days, label) ->
                SegmentedButton(
                    selected = backfill == days, onClick = { backfill = days; settings.photoBackupBackfillDays = days },
                    shape = SegmentedButtonDefaults.itemShape(i, 4),
                ) { Text(label) }
            }
        }
        Text(
            "Photos taken before backup was turned on are not uploaded unless you reach back here.",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            SettingRow(Icons.Filled.CheckCircle, "Back up now", settings.photoBackupLastResult?.let { r ->
                val at = settings.photoBackupLastRun.takeIf { it > 0 }?.let { com.neutrino.drive.ui.relative(java.time.Instant.ofEpochMilli(it)) }
                if (at != null) "$r ($at)" else r
            }) { PhotoBackupWorker.runNow(context, settings) }
        }
    }
    if (editFolder) {
        TextInputDialog("Backup folder", folder, "Folder in My Drive", "Save", onDismiss = { editFolder = false }) {
            editFolder = false
            folder = it
            settings.photoBackupFolderName = it
        }
    }
}
