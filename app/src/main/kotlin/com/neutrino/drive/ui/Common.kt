package com.neutrino.drive.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.core.drive.NativeKind
import com.neutrino.drive.ui.theme.Brand
import java.io.File
import java.time.Instant

fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun relative(instant: Instant?): String = instant?.let {
    DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
} ?: ""

/** Icon and tint for a row, keyed on the owning app so a `.docx` looks like the document it is. */
fun iconFor(item: DriveItem): Pair<ImageVector, Color?> {
    if (item.isFolder) return Icons.Filled.Folder to Brand.folder
    when (item.nativeKind) {
        NativeKind.DOC -> return Icons.Filled.Description to Brand.doc
        NativeKind.SHEET -> return Icons.Filled.TableChart to Brand.sheet
        NativeKind.SLIDE -> return Icons.Filled.Slideshow to Brand.slide
        NativeKind.DIAGRAM -> return Icons.Filled.AccountTree to Brand.diagram
        NativeKind.DRAWING -> return Icons.Filled.Brush to Brand.drawing
        NativeKind.NOTE -> return Icons.AutoMirrored.Filled.TextSnippet to null
        null -> Unit
    }
    val mime = item.mimeType.orEmpty()
    return when {
        mime.startsWith("image/") -> Icons.Filled.Image to null
        mime == "application/pdf" -> Icons.Filled.PictureAsPdf to Color(0xFFD93025)
        mime.startsWith("video/") -> Icons.Filled.Movie to null
        mime.startsWith("audio/") -> Icons.Filled.MusicNote to null
        mime.contains("zip") || mime.contains("tar") || mime.contains("gzip") -> Icons.Filled.Archive to null
        mime.startsWith("text/") -> Icons.AutoMirrored.Filled.TextSnippet to null
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to null
    }
}

/** Hands a decrypted file to whatever app views its type. */
object FileOpener {
    fun openDir(context: Context): File = File(context.cacheDir, "open")

    /** A fresh directory per open, so the file keeps its real name for the viewer. */
    fun newTarget(context: Context, name: String): File {
        val dir = File(openDir(context), java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, com.neutrino.drive.storage.OfflineStore.safeName(name))
    }

    /** Plaintext must not linger: everything opened more than a day ago is removed at launch. */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        openDir(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    fun view(context: Context, file: File, mimeType: String?) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val type = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: com.neutrino.drive.transfer.Outbox.mimeForName(file.name)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this device can open ${file.name}.", Toast.LENGTH_LONG).show()
        }
    }

    fun send(context: Context, file: File, mimeType: String?) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType(mimeType ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Send ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** A one-field dialog — new folder, rename. The current name is pre-selected up to its extension. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val stem = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = value.text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (action != null) {
            Spacer(Modifier.size(8.dp))
            action()
        }
    }
}

@Composable
fun VerticalSpace(dp: Int) = Spacer(Modifier.height(dp.dp))
