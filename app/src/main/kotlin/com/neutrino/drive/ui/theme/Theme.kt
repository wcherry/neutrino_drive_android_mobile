package com.neutrino.drive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.neutrino.drive.R
import com.neutrino.shared.android.ui.NeutrinoBrand
import com.neutrino.shared.android.ui.NeutrinoTheme as SharedTheme

/**
 * The Drive brand from `NeutrinoBrand.drive` in the iOS shared package: an indigo → blue gradient
 * (iOS `systemIndigo`, `systemBlue`), the same pair the app icon is drawn in.
 */
object Brand {
    val indigo = Color(0xFF5856D6)
    val blue = Color(0xFF007AFF)
    val gradient = Brush.linearGradient(listOf(indigo, blue))
    val doc = Color(0xFF2F6BFF)
    val sheet = Color(0xFF2EA44F)
    val slide = Color(0xFFF08A24)
    val diagram = Color(0xFF8E5CD9)
    val drawing = Color(0xFFE6407A)
    val folder = Color(0xFF3B82F6)
    val success = Color(0xFF2EA44F)
    val warning = Color(0xFFE08A00)
}

private val Light = lightColorScheme(
    primary = Brand.indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E2FB),
    onPrimaryContainer = Color(0xFF1B1A5E),
    secondary = Brand.blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBFF),
    onSecondaryContainer = Color(0xFF00315F),
    background = Color(0xFFF7F7FB),
    surface = Color(0xFFF7F7FB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F1F7),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E7EF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9A8FF),
    onPrimary = Color(0xFF1B1A5E),
    primaryContainer = Color(0xFF3D3BA8),
    onPrimaryContainer = Color(0xFFE3E2FB),
    secondary = Color(0xFF8CC2FF),
    onSecondary = Color(0xFF00315F),
    secondaryContainer = Color(0xFF004A8F),
    onSecondaryContainer = Color(0xFFDCEBFF),
    background = Color(0xFF121218),
    surface = Color(0xFF121218),
    surfaceContainerLowest = Color(0xFF0C0C11),
    surfaceContainerLow = Color(0xFF1A1A21),
    surfaceContainer = Color(0xFF1E1E26),
    surfaceContainerHigh = Color(0xFF282830),
)

/** How the shared sign-in, lock and key screens draw Drive. */
val DriveBrand = NeutrinoBrand(
    name = "Neutrino Drive",
    tagline = "Secure encrypted file storage",
    primary = Brand.indigo,
    secondary = Brand.blue,
    icon = R.drawable.ic_launcher_art,
    lockedSubtitle = "Your files are encrypted on this phone.",
)

@Composable
fun NeutrinoTheme(content: @Composable () -> Unit) {
    SharedTheme(DriveBrand, colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
