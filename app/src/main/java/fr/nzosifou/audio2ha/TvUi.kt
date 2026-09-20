package fr.nzosifou.audio2ha

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import fr.nzosifou.audio2ha.ui.theme.Nocturne
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Rend un élément navigable à la télécommande : anneau d'accent au focus, car sur
 * une TV rien n'indique où l'on se trouve sans repère visuel explicite.
 */
private fun Modifier.tvSurface(
    focused: Boolean,
    shape: Shape,
    background: Color,
    focusBackground: Color = background,
): Modifier = this
    .background(if (focused) focusBackground else background, shape)
    .border(
        width = if (focused) Nocturne.FocusRing else 0.dp,
        color = if (focused) Nocturne.Accent else Color.Transparent,
        shape = shape,
    )

// ---------------------------------------------------------------- menu latéral

/** Une rubrique du menu latéral. */
@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Nocturne.Radius)
    val tint = if (focused || selected) Nocturne.Accent else Nocturne.TextSecondary
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Nocturne.RailPaddingH)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .tvSurface(
                focused = focused,
                shape = shape,
                background = if (selected) Nocturne.Card else Color.Transparent,
                focusBackground = Nocturne.Card,
            )
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Text(
            label,
            fontSize = Nocturne.BodySize,
            color = if (selected || focused) Nocturne.Text else Nocturne.TextSecondary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** Marque de l'application : barres d'égaliseur + nom. */
@Composable
fun Brand(modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = Nocturne.RailPaddingH),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(15.dp)
                .clip(RoundedCornerShape(Nocturne.Radius))
                .background(Nocturne.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Equalizer(active = true, modifier = Modifier.size(9.dp), bars = 4)
        }
        Text("Audio2HA", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Nocturne.Text)
    }
}

/** Petit rappel, en pied de menu. */
@Composable
fun RailFooter(lines: List<String>) {
    Column(
        Modifier.padding(horizontal = Nocturne.RailPaddingH),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach {
            Text(
                it,
                fontSize = Nocturne.HintSize,
                color = Nocturne.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------- titrages

/** Bandeau de rubrique : petites majuscules espacées, en accent. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Nocturne.Accent) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = Nocturne.LabelSize,
        letterSpacing = Nocturne.LabelTracking,
        color = color,
        fontWeight = FontWeight.Medium,
    )
}

/** Titre d'une page de réglages, avec son action principale à droite. */
@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                fontSize = Nocturne.TitleSize,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.3).sp,
                color = Nocturne.Text,
            )
            Text(subtitle, fontSize = Nocturne.RowLabelSize, color = Nocturne.TextMuted)
        }
        action?.invoke()
    }
}

/** Filet dégradé qui sépare deux zones. */
@Composable
fun GradientDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Nocturne.Outline, Nocturne.Outline.copy(alpha = 0.02f)),
                ),
            ),
    )
}

// -------------------------------------------------------------------- boutons

enum class TvButtonStyle { Accent, Neutral, Danger }

/** Bouton en contour, à la façon de la maquette (jamais d'aplat plein). */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: TvButtonStyle = TvButtonStyle.Neutral,
    small: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Nocturne.Radius)
    val color = when (style) {
        TvButtonStyle.Accent -> Nocturne.Accent
        TvButtonStyle.Neutral -> Nocturne.Text
        TvButtonStyle.Danger -> Nocturne.Danger
    }
    val border = when {
        focused -> Nocturne.Accent
        style == TvButtonStyle.Accent -> Nocturne.Accent
        else -> Nocturne.Outline
    }
    Row(
        modifier
            .height(if (small) Nocturne.ButtonHeightSmall else Nocturne.ButtonHeight)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) Nocturne.Card else Color.Transparent, shape)
            .border(if (focused) Nocturne.FocusRing else 1.dp, border, shape)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        }
        Text(
            text,
            fontSize = if (small) 8.5.sp else Nocturne.BodySize,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

// ------------------------------------------------------------------- réglages

/**
 * Ligne de réglage : libellé (et son aide) dans une colonne fixe à gauche,
 * valeur à droite, chevron au bout.
 */
@Composable
fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    valueColor: Color = Nocturne.Text,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Nocturne.Radius)
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .tvSurface(focused, shape, Nocturne.Card)
            .padding(horizontal = Nocturne.RowPaddingH, vertical = Nocturne.RowPaddingV),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.width(Nocturne.SettingLabelWidth),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, fontSize = Nocturne.RowLabelSize, color = Nocturne.Text)
            hint?.let { Text(it, fontSize = Nocturne.HintSize, color = Nocturne.TextMuted) }
        }
        Text(
            value.ifEmpty { "—" },
            modifier = Modifier.weight(1f),
            fontSize = Nocturne.ValueSize,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = if (focused) Nocturne.Accent else Nocturne.TextMuted,
            modifier = Modifier.size(11.dp),
        )
    }
}

/** Ligne de réglage à choix multiples côte à côte (type d'entité). */
@Composable
fun <T> ChoiceRow(
    label: String,
    hint: String?,
    options: List<T>,
    selected: T,
    optionTitle: (T) -> String,
    optionSubtitle: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Nocturne.Card, RoundedCornerShape(Nocturne.Radius))
            .padding(horizontal = Nocturne.RowPaddingH, vertical = Nocturne.RowPaddingV),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.width(Nocturne.SettingLabelWidth),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, fontSize = Nocturne.RowLabelSize, color = Nocturne.Text)
            hint?.let { Text(it, fontSize = Nocturne.HintSize, color = Nocturne.TextMuted) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            options.forEach { option ->
                ChoiceChip(
                    title = optionTitle(option),
                    subtitle = optionSubtitle(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Nocturne.Radius)
    val border = if (focused || selected) Nocturne.Accent else Nocturne.Outline
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) Nocturne.Bg else Color.Transparent, shape)
            .border(if (focused || selected) Nocturne.FocusRing else 1.dp, border, shape)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            title,
            fontSize = Nocturne.RowLabelSize,
            color = if (selected) Nocturne.Accent else Nocturne.Text,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        Text(subtitle, fontSize = Nocturne.HintSize, color = Nocturne.TextMuted)
    }
}

/** Carte d'information de l'accueil : intitulé en accent, valeur, précision. */
@Composable
fun InfoCard(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Nocturne.Text,
    valueSize: TextUnit = Nocturne.ValueSize,
) {
    Column(
        modifier
            .background(Nocturne.Card, RoundedCornerShape(Nocturne.Radius))
            .padding(Nocturne.CardPadding),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SectionLabel(label)
        Text(
            value,
            fontSize = valueSize,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(hint, fontSize = Nocturne.SmallSize, color = Nocturne.TextMuted, maxLines = 2)
    }
}

/** Bascule oui / non actionnée par « OK ». */
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
        valueColor = if (checked) Nocturne.AccentSoft else Nocturne.TextMuted,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
    )
}

// ------------------------------------------------------------------ égaliseur

/**
 * Barres décoratives de l'accueil. L'API de détection ne donne pas l'amplitude du
 * son (il faudrait le micro) : ces barres indiquent seulement qu'un flux est en
 * cours — elles s'animent quand il y a du son, et s'aplatissent sinon.
 */
@Composable
fun Equalizer(active: Boolean, modifier: Modifier = Modifier, bars: Int = 26) {
    val transition = rememberInfiniteTransition(label = "eq")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Canvas(modifier) {
        val slot = size.width / bars
        val gap = slot * 0.38f
        val barWidth = slot - gap
        for (i in 0 until bars) {
            val seed = sin(i * 2.7f) * 0.5f + 0.5f
            val level = if (active) {
                0.25f + 0.75f * ((sin(phase + i * 0.55f) * 0.5f + 0.5f) * 0.7f + seed * 0.3f)
            } else {
                0.10f + seed * 0.05f
            }
            val h = size.height * level
            drawRect(
                color = Nocturne.Accent.copy(alpha = if (active) 0.35f + 0.5f * level else 0.18f),
                topLeft = Offset(i * slot, size.height - h),
                size = Size(barWidth, h),
            )
        }
    }
}

// ------------------------------------------------------------------- journaux

/** Une entrée de journal, en carte. */
@Composable
fun LogCard(entry: LogEntry, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Nocturne.Radius)
    val accent = when {
        entry.error -> Nocturne.Danger
        entry.type == LogType.AUDIO -> Nocturne.Accent
        else -> Nocturne.AccentSoft
    }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable { }
            .tvSurface(focused, shape, Nocturne.Card)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.timeString(),
            modifier = Modifier.width(62.dp),
            fontSize = Nocturne.HintSize,
            color = Nocturne.TextMuted,
            maxLines = 1,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.message, fontSize = Nocturne.RowLabelSize, color = Nocturne.Text)
            entry.detail?.let {
                Text(
                    it,
                    fontSize = Nocturne.HintSize,
                    color = Nocturne.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SectionLabel(
            if (entry.type == LogType.AUDIO) "TV" else "Home Assistant",
            color = accent,
        )
    }
}

// ------------------------------------------------------------------ dialogues

@Composable
private fun NocturneDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Nocturne.RadiusLarge),
            color = Nocturne.Rail,
        ) {
            Column(
                Modifier
                    .widthIn(min = 300.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel(title)
                content()
            }
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
    valueColor: Color = Nocturne.Text,
) {
    var editing by remember { mutableStateOf(false) }

    SettingRow(
        label = label,
        value = if (shortened) shorten(value) else value,
        hint = hint,
        valueColor = valueColor,
        onClick = { editing = true },
        modifier = modifier,
    )

    if (editing) {
        NocturneDialog(title = label, onDismiss = { editing = false }) {
            EditDialogBody(
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
}

private fun shorten(value: String): String =
    if (value.length <= 30) value else value.take(18) + "…" + value.takeLast(8)

@Composable
private fun EditDialogBody(
    initial: String,
    numeric: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current

    // Le contrôleur de clavier et la fenêtre doivent être ceux du dialogue,
    // sinon l'IME reste masqué par le stateAlwaysHidden de l'activité.
    LaunchedEffect(Unit) {
        (view.parent as? DialogWindowProvider)?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        delay(150)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = Nocturne.ValueSize),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        shape = RoundedCornerShape(Nocturne.Radius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Nocturne.Accent,
            unfocusedBorderColor = Nocturne.Outline,
            focusedContainerColor = Nocturne.Card,
            unfocusedContainerColor = Nocturne.Card,
            cursorColor = Nocturne.Accent,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        TvButton("Valider", { onConfirm(text) }, style = TvButtonStyle.Accent, small = true)
        TvButton("Annuler", onDismiss, small = true)
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
        NocturneDialog(title = label, onDismiss = { open = false }) {
            // Sans cela le focus atterrit sur « Fermer » à l'ouverture.
            LaunchedEffect(options.size) {
                delay(150)
                runCatching { firstOption.requestFocus() }
            }
            if (options.isEmpty()) {
                Text(emptyMessage, fontSize = Nocturne.RowLabelSize, color = Nocturne.TextMuted)
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 230.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    itemsIndexed(options) { index, option ->
                        PickerOption(
                            label = optionLabel(option),
                            modifier = if (index == 0) {
                                Modifier.focusRequester(firstOption)
                            } else {
                                Modifier
                            },
                            onClick = {
                                onSelect(option)
                                open = false
                            },
                        )
                    }
                }
            }
            TvButton("Fermer", { open = false }, small = true)
        }
    }
}

@Composable
private fun PickerOption(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Nocturne.Radius)
    Box(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .tvSurface(focused, shape, Nocturne.Card)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            fontSize = Nocturne.RowLabelSize,
            color = if (focused) Nocturne.Accent else Nocturne.Text,
        )
    }
}

// ---------------------------------------------------------------- mise en page

/** Groupe de réglages précédé de son intitulé. */
@Composable
fun SettingsGroup(title: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { SectionLabel(it) }
        content()
    }
}

/** Zone de contenu d'une rubrique, avec les marges de la maquette. */
@Composable
fun PageScaffold(
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .padding(
                start = Nocturne.ContentPaddingH,
                end = Nocturne.ContentPaddingH,
                top = Nocturne.ContentPaddingTop,
                bottom = Nocturne.ContentPaddingBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        GradientDivider()
        content()
    }
}
