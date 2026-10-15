package com.neutrino.drive.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.neutrino.drive.MainActivity
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.graph
import com.neutrino.drive.transfer.Outbox
import com.neutrino.drive.ui.files.MoveDialog
import com.neutrino.drive.ui.files.Place
import com.neutrino.drive.ui.theme.NeutrinoTheme
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * "Share → Drive" from any app — the iOS share extension. Files, images, video and plain text
 * (saved as a `.txt`) are accepted, encrypted and uploaded into the chosen folder.
 *
 * The shared URIs are readable only while this activity holds the grant, so they are copied into
 * the outbox here, before the activity finishes; the upload itself then runs as background work and
 * survives the user going straight back to where they were.
 */
class ShareReceiverActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        setContent { NeutrinoTheme { ShareScreen(uris) } }
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val streams: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (streams.isNotEmpty()) return streams
        // Text (a note, a link): kept as a text file named for the moment it was shared.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        val name = (subject ?: "Shared text ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))}")
            .replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80) + ".txt"
        val dir = File(cacheDir, "shared-text").apply { mkdirs() }
        val file = File(dir, name).apply { writeText(text) }
        return listOf(Uri.fromFile(file))
    }

    @Composable
    private fun ShareScreen(uris: List<Uri>) {
        val graph = graph
        val scope = rememberCoroutineScope()
        var destination by remember { mutableStateOf<Place.Folder>(Place.root) }
        var choosing by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        val signedIn = graph.auth.isSignedIn
        val hasKey = signedIn && graph.keys.hasKey

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Save to Neutrino Drive", style = MaterialTheme.typography.headlineSmall)
                when {
                    uris.isEmpty() -> Text("There is nothing here Drive can save.")
                    !signedIn -> Text("Sign in to Neutrino Drive first, then share again.")
                    !hasKey -> Text("Add your encryption key in Neutrino Drive first — files are encrypted on this phone before they are uploaded.")
                    else -> {
                        Text(
                            "${uris.size} item${if (uris.size == 1) "" else "s"} will be encrypted on this phone and uploaded.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Folder: ${destination.title.ifEmpty { "My Drive" }}")
                        }
                        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            busy = true
                            scope.launch {
                                runCatching { Outbox.enqueue(this@ShareReceiverActivity, uris, destination.id) }
                                    .onSuccess { Toast.makeText(this@ShareReceiverActivity, "Uploading to Neutrino Drive…", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(this@ShareReceiverActivity, it.message ?: "Could not save.", Toast.LENGTH_LONG).show() }
                                finish()
                            }
                        }) { Text(if (busy) "Preparing…" else "Upload") }
                    }
                }
                if (!signedIn || !hasKey) {
                    Button(onClick = { startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java)); finish() }) { Text("Open Drive") }
                }
                TextButton(onClick = { finish() }) { Text("Cancel") }
            }
        }
        if (choosing) {
            // The picker is the move dialog, pointed at a placeholder item that lives nowhere.
            MoveDialog(DriveItem(id = "", name = "", isFolder = true, parentId = "\u0000"), onDismiss = { choosing = false }) {
                destination = it
                choosing = false
            }
        }
    }
}
