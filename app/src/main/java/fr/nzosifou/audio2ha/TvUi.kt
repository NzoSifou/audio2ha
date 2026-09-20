package fr.nzosifou.audio2ha

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

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

/** Ligne de réglage focusable : libellé, valeur, et action au clic. */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    hint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.ifEmpty { "—" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Champ de saisie pour télécommande : le clavier virtuel ne s'ouvre pas au simple
 * passage du focus, seulement après validation par « OK », dans une fenêtre dédiée.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    shortened: Boolean = false,
    numeric: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }

    SettingRow(
        label = label,
        value = if (shortened) shorten(value) else value,
        hint = hint,
        onClick = { editing = true },
        modifier = modifier,
    )

    if (editing) {
        EditDialog(
            label = label,
            initial = value,
            numeric = numeric,
            onDismiss = { editing = false },
            onConfirm = {
                onValueChange(it)
                editing = false
            },
        )
    }
}

private fun shorten(value: String): String =
    if (value.length <= 34) value else value.take(20) + "…" + value.takeLast(8)

@Composable
private fun EditDialog(
    label: String,
    initial: String,
    numeric: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            // Le contrôleur de clavier et la fenêtre doivent être ceux du dialogue,
            // sinon l'IME reste masqué par le stateAlwaysHidden de l'activité.
            val keyboard = LocalSoftwareKeyboardController.current
            val view = LocalView.current
            LaunchedEffect(Unit) {
                (view.parent as? DialogWindowProvider)?.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                )
                delay(150)
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            }

            Column(
                Modifier
                    .widthIn(min = 520.dp)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(text = "Valider", primary = true, onClick = { onConfirm(text) })
                    TvButton(text = "Annuler", onClick = onDismiss)
                }
            }
        }
    }
}

/** Choix dans une liste, présenté dans une fenêtre navigable à la télécommande. */
@Composable
fun <T> TvPickerField(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onOpen: () -> Unit = {},
    emptyMessage: String = "Aucun choix disponible.",
) {
    var open by remember { mutableStateOf(false) }

    SettingRow(
        label = label,
        value = value,
        hint = hint,
        onClick = {
            onOpen()
            open = true
        },
        modifier = modifier,
    )

    if (open) {
        val firstOption = remember { FocusRequester() }
        Dialog(onDismissRequest = { open = false }) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                // Sans cela le focus atterrit sur « Fermer » à l'ouverture.
                LaunchedEffect(options.size) {
                    delay(150)
                    runCatching { firstOption.requestFocus() }
                }
                Column(
                    Modifier
                        .widthIn(min = 520.dp)
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    if (options.isEmpty()) {
                        Text(
                            emptyMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(options) { index, option ->
                                TvButton(
                                    text = optionLabel(option),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (index == 0) Modifier.focusRequester(firstOption)
                                            else Modifier,
                                        ),
                                    onClick = {
                                        onSelect(option)
                                        open = false
                                    },
                                )
                            }
                        }
                    }
                    TvButton(text = "Fermer", onClick = { open = false })
                }
            }
        }
    }
}

/** Interrupteur oui / non actionné par « OK ». */
@Composable
fun TvToggleField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    SettingRow(
        label = label,
        value = if (checked) "Activé" else "Désactivé",
        hint = hint,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
    )
}

/** Conteneur de section avec un titre. */
@Composable
fun TvSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/** Ligne horizontale de composants de même largeur. */
@Composable
fun TvRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

/** Utilitaire : colonne défilante, utile quand une fenêtre déborde. */
@Composable
fun TvScrollColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
