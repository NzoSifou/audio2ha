package fr.nzosifou.audio2ha

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bouton adapté au pilotage à la télécommande : l'élément qui a le focus
 * est entouré d'un liseré blanc, sinon on ne sait pas où l'on se trouve sur une TV.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    contentColor: Color? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusModifier = modifier.onFocusChanged { focused = it.isFocused }
    val border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.onBackground) else null

    if (primary) {
        Button(onClick = onClick, modifier = focusModifier, border = border) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = focusModifier,
            border = border ?: BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Text(text, color = contentColor ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}
