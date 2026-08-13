package de.qwikster.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.LocalContentColor

// ---------------------------------------------------------------------------
// Farbpalette
// ---------------------------------------------------------------------------
// Dunkel gehalten (wie TiviMate): Auf einem 55"-Fernseher im abgedunkelten
// Wohnzimmer blendet eine helle Oberfläche stark. Die Akzentfarbe ist
// gleichzeitig die Fokusfarbe – auf dem TV muss auf zwei Meter Entfernung
// sofort erkennbar sein, wo der Fokus steht.

val TvBackground = Color(0xFF0B0E14)
val TvSurface = Color(0xFF141822)
val TvSurfaceVariant = Color(0xFF1E2432)
val TvSurfaceElevated = Color(0xFF262D3D)
val TvAccent = Color(0xFF3D8BFF)
val TvAccentDim = Color(0xFF1F4C8F)
val TvOnSurface = Color(0xFFE6EAF2)
val TvOnSurfaceMuted = Color(0xFF98A2B8)
val TvLive = Color(0xFFFF4757)
val TvFavorite = Color(0xFFFFC048)

/** "Eingeschaltet" – für Zustandsanzeigen wie die Kategorien-Sichtbarkeit. */
val TvOn = Color(0xFF34C759)

private val QwiksterColorScheme = darkColorScheme(
    primary = TvAccent,
    onPrimary = Color.White,
    primaryContainer = TvAccentDim,
    onPrimaryContainer = Color.White,
    secondary = TvFavorite,
    background = TvBackground,
    onBackground = TvOnSurface,
    surface = TvSurface,
    onSurface = TvOnSurface,
    surfaceVariant = TvSurfaceVariant,
    onSurfaceVariant = TvOnSurfaceMuted,
    border = TvAccent,
    error = TvLive,
)

// ---------------------------------------------------------------------------
// Typografie
// ---------------------------------------------------------------------------
// Alle Größen sind gegenüber einem Handy-Layout deutlich angehoben:
// Faustregel für 10-Foot-UI ist Fließtext ab ~14sp bei 1080p-Layoutgröße,
// Listeneinträge ab 16sp.

val QwiksterTypography = Typography(
    displayLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

/** Gemeinsame Abstände – hält die Bildschirme optisch im Raster. */
object TvSpacing {
    val overscanHorizontal = 32.dp
    val overscanVertical = 24.dp
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
}

/**
 * Die App ist bewusst immer dunkel – die Systemeinstellung wird ignoriert.
 * Ein heller Modus ist auf einem großen Bildschirm im Wohnzimmer unangenehm
 * und wird von keiner der Vorlagen-Apps (TiviMate, Kodi) angeboten.
 */
@Composable
fun QwiksterTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = QwiksterColorScheme,
        typography = QwiksterTypography,
    ) {
        CompositionLocalProvider(LocalContentColor provides TvOnSurface, content = content)
    }
}
