package fr.nzosifou.audio2ha.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

private val NocturneColors = darkColorScheme(
    primary = Nocturne.Accent,
    onPrimary = Nocturne.Bg,
    secondary = Nocturne.AccentSoft,
    background = Nocturne.Bg,
    onBackground = Nocturne.Text,
    surface = Nocturne.Card,
    onSurface = Nocturne.Text,
    surfaceVariant = Nocturne.Rail,
    onSurfaceVariant = Nocturne.TextSecondary,
    outline = Nocturne.Outline,
    error = Nocturne.Danger,
)

/**
 * La maquette utilise Inter ; sur Android TV la police système (Roboto) en est
 * l'équivalent le plus proche, et la seule disponible sans embarquer de fichier.
 */
private val NocturneTypography = Typography().run {
    val f = FontFamily.Default
    copy(
        headlineLarge = headlineLarge.copy(fontFamily = f, fontWeight = FontWeight.Medium),
        headlineMedium = headlineMedium.copy(fontFamily = f, fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontFamily = f, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = f),
        bodyMedium = bodyMedium.copy(fontFamily = f),
        bodySmall = bodySmall.copy(fontFamily = f),
    )
}

/** Chiffres à chasse fixe, pour que les valeurs techniques ne dansent pas. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun Audio2HATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NocturneColors,
        typography = NocturneTypography,
    ) {
        // Les styles Material portent une hauteur de ligne fixe (24sp pour bodyLarge).
        // Comme l'écran fixe la taille de chaque texte au point près, cette hauteur
        // figée gonflait toutes les lignes : on laisse la police décider.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(
                lineHeight = TextUnit.Unspecified,
            ),
            content = content,
        )
    }
}
