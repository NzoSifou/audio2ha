package fr.nzosifou.audio2ha

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.nzosifou.audio2ha.ui.theme.Audio2HATheme
import fr.nzosifou.audio2ha.ui.theme.Nocturne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Incrémenté à chaque reprise de l'activité : sert à redonner le focus au menu.
     * Sans cela un champ le récupère, le clavier virtuel s'ouvre et les touches de
     * la télécommande sont tapées dedans.
     */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.init(this)
        ConfigServerManager.start(this)
        askNotificationPermission()
        setContent {
            Audio2HATheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Nocturne.Bg,
                ) {
                    AppScreen(resumeTick)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private enum class Section(val label: String, val icon: ImageVector) {
    ETAT("État", Icons.Default.PlayArrow),
    ENTITE("Entité", Icons.Default.Home),
    COMPORTEMENT("Comportement", Icons.Default.Settings),
    JOURNAUX("Journaux", Icons.Default.List),
}

@Composable
private fun AppScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    var section by remember { mutableStateOf(Section.ETAT) }
    val firstNavItem = remember { FocusRequester() }

    val serverUrl by MonitorStatus.configServer.collectAsState()

    LaunchedEffect(resumeTick) {
        delay(250)
        runCatching { firstNavItem.requestFocus() }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(Nocturne.RailWidth)
                .fillMaxHeight()
                .background(Nocturne.Rail)
                .padding(vertical = Nocturne.RailPaddingV),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Brand()
            Spacer(Modifier.height(10.dp))
            Section.entries.forEachIndexed { index, item ->
                NavItem(
                    icon = item.icon,
                    label = item.label,
                    selected = section == item,
                    onClick = { section = item },
                    modifier = if (index == 0) Modifier.focusRequester(firstNavItem) else Modifier,
                )
            }
            Spacer(Modifier.weight(1f))
            RailFooter(
                listOf(
                    Build.MODEL ?: "Android TV",
                    (serverUrl ?: "serveur local indisponible").removePrefix("http://"),
                ),
            )
        }

        Box(Modifier.fillMaxSize()) {
            when (section) {
                Section.ETAT -> EtatPage()
                Section.ENTITE -> EntitePage()
                Section.COMPORTEMENT -> ComportementPage()
                Section.JOURNAUX -> JournauxPage()
            }
        }
    }
}

// ---------------------------------------------------------------------- état

@Composable
private fun EtatPage() {
    val ctx = LocalContext.current
    val running by MonitorStatus.running.collectAsState()
    val playing by MonitorStatus.audioPlaying.collectAsState()
    val lastSync by MonitorStatus.lastSync.collectAsState()
    val detail by MonitorStatus.detail.collectAsState()
    val serverUrl by MonitorStatus.configServer.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = Nocturne.ContentPaddingH,
                end = Nocturne.ContentPaddingH,
                top = Nocturne.ContentPaddingTop,
                bottom = Nocturne.ContentPaddingBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(
                        if (running) Nocturne.Accent else Nocturne.TextDim,
                        androidx.compose.foundation.shape.CircleShape,
                    ),
            )
            SectionLabel(
                if (running) "Surveillance active" else "Surveillance arrêtée",
                color = if (running) Nocturne.Accent else Nocturne.TextMuted,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                when {
                    !running -> "En pause"
                    playing -> "Son en cours"
                    else -> "Aucun son"
                },
                fontSize = Nocturne.HeroSize,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-1).sp,
                color = Nocturne.Text,
            )
            // Largeur fixe : étalées sur toute la ligne, les barres deviennent
            // des tirets espacés au lieu d'un égaliseur.
            Equalizer(
                active = running && playing,
                modifier = Modifier
                    .width(240.dp)
                    .height(55.dp)
                    .padding(bottom = 8.dp),
            )
        }

        Text(
            detail.ifEmpty { "En attente du premier relevé" },
            fontSize = Nocturne.ValueSize,
            color = Nocturne.TextSecondary,
        )

        GradientDivider()

        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(
                label = "Entité",
                value = Prefs.getEntityId(ctx),
                hint = Prefs.getFriendlyName(ctx) + " · " +
                    Prefs.getAreaName(ctx).ifEmpty { "aucune pièce" },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            InfoCard(
                label = "Dernier envoi",
                value = lastSync,
                hint = Prefs.getBaseUrl(ctx).removePrefix("http://"),
                valueSize = 15.sp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            InfoCard(
                label = "Configuration à distance",
                value = (serverUrl ?: "indisponible").removePrefix("http://"),
                hint = "Collez le token depuis un téléphone",
                valueColor = Nocturne.AccentSoft,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TvButton(
                text = if (running) "Arrêter la surveillance" else "Démarrer la surveillance",
                icon = if (running) Icons.Default.Close else Icons.Default.PlayArrow,
                style = TvButtonStyle.Accent,
                onClick = {
                    if (running) AudioMonitorService.stop(ctx) else AudioMonitorService.start(ctx)
                },
            )
            TvButton(
                text = "Renvoyer l'état",
                icon = Icons.Default.Refresh,
                onClick = {
                    AudioMonitorService.send(ctx, AudioMonitorService.ACTION_RESEND)
                    if (!running) {
                        LogStore.ha("Renvoi ignoré : la surveillance est arrêtée", error = true)
                    }
                },
            )
            TvButton(
                text = "Son de test",
                icon = Icons.Default.Notifications,
                onClick = { TestTone.play() },
            )
        }
    }
}

// -------------------------------------------------------------------- entité

@Composable
private fun EntitePage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(Prefs.getBaseUrl(ctx)) }
    var token by remember { mutableStateOf(Prefs.getToken(ctx)) }
    var entityId by remember { mutableStateOf(Prefs.getEntityId(ctx)) }
    var sensorName by remember { mutableStateOf(Prefs.getFriendlyName(ctx)) }
    var entityKind by remember { mutableStateOf(Prefs.getEntityKind(ctx)) }
    var areaName by remember { mutableStateOf(Prefs.getAreaName(ctx)) }
    var areas by remember { mutableStateOf<List<HaArea>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    PageScaffold(
        header = {
            PageHeader(
                title = "Entité publiée",
                subtitle = "Ce que la TV crée dans Home Assistant",
            ) {
                TvButton(
                    text = if (busy) "Vérification…" else "Appliquer et tester",
                    icon = Icons.Default.Check,
                    style = TvButtonStyle.Accent,
                    small = true,
                    onClick = {
                        if (busy) return@TvButton
                        busy = true
                        message = "Vérification en cours…"
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                val ping = HaClient.ping(
                                    Prefs.getBaseUrl(ctx),
                                    Prefs.getToken(ctx),
                                    Prefs.getRetryTimeoutSeconds(ctx) * 1000,
                                )
                                if (!ping.ok) {
                                    LogStore.ha("Test de connexion échoué", ping.shortBody(), error = true)
                                    return@withContext "Échec : " +
                                        (if (ping.httpCode > 0) "HTTP ${ping.httpCode} " else "") +
                                        ping.shortBody(120)
                                }
                                LogStore.ha("Test de connexion réussi", "HTTP ${ping.httpCode}")
                                val report = HaSetup.apply(ctx)
                                (if (report.ok) "OK — " else "Attention — ") + report.message
                            }
                            entityId = Prefs.getEntityId(ctx)
                            busy = false
                            message = text
                            AudioMonitorService.send(ctx, AudioMonitorService.ACTION_CONFIG_CHANGED)
                        }
                    },
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsGroup {
                TvTextField(
                    label = "Nom du capteur",
                    hint = "Nom affiché dans Home Assistant",
                    value = sensorName,
                    onValueChange = {
                        Prefs.saveConfig(ctx, friendlyName = it)
                        sensorName = Prefs.getFriendlyName(ctx)
                    },
                )
                ChoiceRow(
                    label = "Type d'entité",
                    hint = "Seul l'interrupteur virtuel a une pièce",
                    options = EntityKind.entries.toList(),
                    selected = entityKind,
                    optionTitle = {
                        if (it == EntityKind.BINARY_SENSOR) "Capteur binaire" else "Interrupteur virtuel"
                    },
                    optionSubtitle = {
                        if (it == EntityKind.BINARY_SENSOR) "binary_sensor · API REST" else "input_boolean · registre"
                    },
                    onSelect = {
                        Prefs.setEntityKind(ctx, it)
                        entityKind = it
                        entityId = Prefs.getEntityId(ctx)
                    },
                )
                TvTextField(
                    label = "Identifiant de l'entité",
                    value = entityId,
                    onValueChange = {
                        Prefs.setEntityId(ctx, it)
                        entityId = Prefs.getEntityId(ctx)
                    },
                )
                TvPickerField(
                    label = "Pièce",
                    hint = "Lue dans Home Assistant",
                    value = areaName.ifEmpty { "Aucune pièce" },
                    options = listOf(HaArea("", "Aucune pièce")) + areas,
                    optionLabel = { it.name },
                    emptyMessage = "Liste indisponible : vérifiez l'adresse et le token.",
                    onOpen = {
                        scope.launch {
                            val loaded = withContext(Dispatchers.IO) {
                                HaSetup.listAreas(ctx).getOrElse { emptyList() }
                            }
                            if (loaded.isNotEmpty()) areas = loaded
                        }
                    },
                    onSelect = {
                        Prefs.setArea(ctx, it.id, if (it.id.isEmpty()) "" else it.name)
                        areaName = Prefs.getAreaName(ctx)
                    },
                )
            }

            SettingsGroup("Connexion à Home Assistant") {
                TvTextField(
                    label = "Adresse",
                    hint = "Les noms .local ne sont pas résolus par Android TV",
                    value = baseUrl,
                    onValueChange = {
                        Prefs.saveConfig(ctx, baseUrl = it)
                        baseUrl = Prefs.getBaseUrl(ctx)
                    },
                )
                TvTextField(
                    label = "Token longue durée",
                    hint = "Plus simple à coller depuis le navigateur",
                    value = token,
                    shortened = true,
                    valueColor = Nocturne.AccentSoft,
                    onValueChange = {
                        Prefs.saveConfig(ctx, token = it)
                        token = Prefs.getToken(ctx)
                    },
                )
            }

            message?.let {
                Text(
                    it,
                    fontSize = Nocturne.RowLabelSize,
                    color = if (it.startsWith("OK")) Nocturne.AccentSoft else Nocturne.Danger,
                )
            }
        }
    }
}

// -------------------------------------------------------------- comportement

@Composable
private fun ComportementPage() {
    val ctx = LocalContext.current

    var startOnBoot by remember { mutableStateOf(Prefs.isStartOnBoot(ctx)) }
    var offlineMode by remember { mutableStateOf(Prefs.getOfflineMode(ctx)) }
    var retryCount by remember { mutableStateOf(Prefs.getRetryCount(ctx)) }
    var retryTimeout by remember { mutableStateOf(Prefs.getRetryTimeoutSeconds(ctx)) }
    var onDebounce by remember { mutableStateOf(Prefs.getOnDebounceMs(ctx)) }
    var offDebounce by remember { mutableStateOf(Prefs.getOffDebounceMs(ctx)) }

    PageScaffold(
        header = {
            PageHeader(
                title = "Comportement",
                subtitle = "Détection du son et envois vers Home Assistant",
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsGroup("Détection") {
                TvToggleField(
                    label = "Lancer au démarrage de la TV",
                    hint = "Relance seule après un redémarrage",
                    checked = startOnBoot,
                    onCheckedChange = {
                        Prefs.setStartOnBoot(ctx, it)
                        startOnBoot = it
                    },
                )
                TvTextField(
                    label = "Délai avant « son démarré »",
                    hint = "Filtre les bips d'interface",
                    value = "$onDebounce ms",
                    numeric = true,
                    onValueChange = {
                        it.filter(Char::isDigit).toLongOrNull()?.let { v ->
                            Prefs.setOnDebounceMs(ctx, v.coerceIn(0, 30_000))
                            onDebounce = Prefs.getOnDebounceMs(ctx)
                        }
                    },
                )
                TvTextField(
                    label = "Délai avant « son arrêté »",
                    hint = "Évite le clignotement entre deux pistes",
                    value = "$offDebounce ms",
                    numeric = true,
                    onValueChange = {
                        it.filter(Char::isDigit).toLongOrNull()?.let { v ->
                            Prefs.setOffDebounceMs(ctx, v.coerceIn(0, 60_000))
                            offDebounce = Prefs.getOffDebounceMs(ctx)
                        }
                    },
                )
            }

            SettingsGroup("Envois") {
                TvPickerField(
                    label = "Si le réseau est indisponible",
                    hint = "Au moment d'un changement d'état",
                    value = offlineMode.label,
                    options = OfflineMode.entries.toList(),
                    optionLabel = { it.label },
                    onSelect = {
                        Prefs.setOfflineMode(ctx, it)
                        offlineMode = it
                        AudioMonitorService.send(ctx, AudioMonitorService.ACTION_CONFIG_CHANGED)
                    },
                )
                TvTextField(
                    label = "Nouvelles tentatives",
                    hint = "Après une réponse autre que 2xx",
                    value = retryCount.toString(),
                    numeric = true,
                    onValueChange = {
                        it.filter(Char::isDigit).toIntOrNull()?.let { v ->
                            Prefs.setRetryCount(ctx, v)
                            retryCount = Prefs.getRetryCount(ctx)
                        }
                    },
                )
                TvTextField(
                    label = "Délai d'attente par tentative",
                    hint = "Connexion et lecture",
                    value = "$retryTimeout s",
                    numeric = true,
                    onValueChange = {
                        it.filter(Char::isDigit).toIntOrNull()?.let { v ->
                            Prefs.setRetryTimeoutSeconds(ctx, v)
                            retryTimeout = Prefs.getRetryTimeoutSeconds(ctx)
                        }
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ journaux

private enum class LogFilter(val label: String) {
    ALL("Tout"),
    AUDIO("Changements TV"),
    HA("Envois HA"),
}

@Composable
private fun JournauxPage() {
    val entries by LogStore.entries.collectAsState()
    var filter by remember { mutableStateOf(LogFilter.ALL) }

    val shown = remember(entries, filter) {
        when (filter) {
            LogFilter.ALL -> entries
            LogFilter.AUDIO -> entries.filter { it.type == LogType.AUDIO }
            LogFilter.HA -> entries.filter { it.type == LogType.HA }
        }
    }

    PageScaffold(
        header = {
            PageHeader(
                title = "Journaux",
                subtitle = "Détection locale et envois vers Home Assistant",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    LogFilter.entries.forEach { f ->
                        TvButton(
                            text = f.label,
                            small = true,
                            style = if (f == filter) TvButtonStyle.Accent else TvButtonStyle.Neutral,
                            onClick = { filter = f },
                        )
                    }
                    TvButton(
                        text = "Effacer",
                        icon = Icons.Default.Delete,
                        small = true,
                        style = TvButtonStyle.Danger,
                        onClick = { LogStore.clear() },
                    )
                }
            }
        },
    ) {
        if (shown.isEmpty()) {
            Text(
                "Aucun événement pour le moment.",
                fontSize = Nocturne.RowLabelSize,
                color = Nocturne.TextMuted,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown) { entry -> LogCard(entry) }
            }
        }
    }
}
