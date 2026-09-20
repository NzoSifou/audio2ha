package fr.nzosifou.audio2ha

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import fr.nzosifou.audio2ha.ui.theme.Audio2HATheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Incrémenté à chaque reprise de l'activité : sert à redonner le focus à un bouton.
     * Sans cela un champ de saisie le récupère, le clavier virtuel s'ouvre et les touches
     * de la télécommande sont tapées dans le champ.
     */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.init(this)
        ConfigServerManager.start(this)
        askNotificationPermission()
        setContent {
            Audio2HATheme {
                // Surface plutôt que Box : elle fixe aussi la couleur du texte par défaut.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SetupScreen(resumeTick)
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

@Composable
private fun SetupScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(Prefs.getBaseUrl(ctx)) }
    var token by remember { mutableStateOf(Prefs.getToken(ctx)) }
    var entityId by remember { mutableStateOf(Prefs.getEntityId(ctx)) }
    var sensorName by remember { mutableStateOf(Prefs.getFriendlyName(ctx)) }
    var entityKind by remember { mutableStateOf(Prefs.getEntityKind(ctx)) }
    var areaName by remember { mutableStateOf(Prefs.getAreaName(ctx)) }
    var startOnBoot by remember { mutableStateOf(Prefs.isStartOnBoot(ctx)) }
    var retryCount by remember { mutableStateOf(Prefs.getRetryCount(ctx)) }
    var retryTimeout by remember { mutableStateOf(Prefs.getRetryTimeoutSeconds(ctx)) }
    var offlineMode by remember { mutableStateOf(Prefs.getOfflineMode(ctx)) }
    var onDebounce by remember { mutableStateOf(Prefs.getOnDebounceMs(ctx)) }
    var offDebounce by remember { mutableStateOf(Prefs.getOffDebounceMs(ctx)) }

    var areas by remember { mutableStateOf<List<HaArea>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val firstButton = remember { FocusRequester() }

    val running by MonitorStatus.running.collectAsState()
    val playing by MonitorStatus.audioPlaying.collectAsState()
    val lastSync by MonitorStatus.lastSync.collectAsState()
    val serverUrl by MonitorStatus.configServer.collectAsState()

    fun reload() {
        baseUrl = Prefs.getBaseUrl(ctx)
        token = Prefs.getToken(ctx)
        entityId = Prefs.getEntityId(ctx)
        sensorName = Prefs.getFriendlyName(ctx)
        entityKind = Prefs.getEntityKind(ctx)
        areaName = Prefs.getAreaName(ctx)
        startOnBoot = Prefs.isStartOnBoot(ctx)
        retryCount = Prefs.getRetryCount(ctx)
        retryTimeout = Prefs.getRetryTimeoutSeconds(ctx)
        offlineMode = Prefs.getOfflineMode(ctx)
        onDebounce = Prefs.getOnDebounceMs(ctx)
        offDebounce = Prefs.getOffDebounceMs(ctx)
    }

    // Au premier affichage et à chaque retour dans l'application : on recharge les valeurs
    // (elles ont pu changer depuis le navigateur) et on place le focus sur un bouton.
    LaunchedEffect(resumeTick) {
        reload()
        delay(250)
        runCatching { firstButton.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            "Audio2HA",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        StatusCard(running, playing, lastSync, serverUrl)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = if (running) "Arrêter la surveillance" else "Démarrer la surveillance",
                primary = true,
                modifier = Modifier.focusRequester(firstButton),
                onClick = {
                    if (running) AudioMonitorService.stop(ctx) else AudioMonitorService.start(ctx)
                },
            )

            TvButton(
                text = if (busy) "Vérification..." else "Appliquer et tester",
                primary = true,
                onClick = {
                    if (busy) return@TvButton
                    busy = true
                    message = "Vérification en cours..."
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
                        reload()
                        busy = false
                        message = text
                        AudioMonitorService.send(ctx, AudioMonitorService.ACTION_CONFIG_CHANGED)
                    }
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = "Consulter les logs",
                onClick = { ctx.startActivity(Intent(ctx, LogsActivity::class.java)) },
            )
            TvButton(
                text = "Renvoyer l'état",
                onClick = {
                    AudioMonitorService.send(ctx, AudioMonitorService.ACTION_RESEND)
                    if (!running) LogStore.ha("Renvoi ignoré : la surveillance est arrêtée", error = true)
                },
            )
            TvButton(text = "Jouer un son de test", onClick = { TestTone.play() })
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.startsWith("OK")) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error,
            )
        }

        TvSection("Connexion à Home Assistant") {
            TvTextField(
                label = "Adresse de Home Assistant",
                value = baseUrl,
                hint = "Les noms .local ne sont pas résolus par Android TV : préférez l'adresse IP.",
                onValueChange = {
                    Prefs.saveConfig(ctx, baseUrl = it)
                    baseUrl = Prefs.getBaseUrl(ctx)
                },
            )
            TvTextField(
                label = "Token d'accès longue durée",
                value = token,
                shortened = true,
                hint = "Plus simple à coller depuis le navigateur : ${serverUrl ?: "serveur local indisponible"}",
                onValueChange = {
                    Prefs.saveConfig(ctx, token = it)
                    token = Prefs.getToken(ctx)
                },
            )
        }

        TvSection("Entité publiée") {
            TvTextField(
                label = "Nom du capteur",
                value = sensorName,
                hint = "Nom affiché dans Home Assistant.",
                onValueChange = {
                    Prefs.saveConfig(ctx, friendlyName = it)
                    sensorName = Prefs.getFriendlyName(ctx)
                },
            )
            TvPickerField(
                label = "Type d'entité",
                value = entityKind.label,
                options = EntityKind.entries.toList(),
                optionLabel = { it.label },
                hint = "Seul l'interrupteur virtuel peut être rangé dans une pièce.",
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
                value = areaName.ifEmpty { "Aucune pièce" },
                options = listOf(HaArea("", "Aucune pièce")) + areas,
                optionLabel = { it.name },
                hint = "Appliquez ensuite avec « Appliquer et tester ».",
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

        TvSection("Comportement") {
            TvToggleField(
                label = "Lancer la détection au démarrage de la TV",
                checked = startOnBoot,
                hint = "La surveillance redémarre seule après un redémarrage.",
                onCheckedChange = {
                    Prefs.setStartOnBoot(ctx, it)
                    startOnBoot = it
                },
            )
            TvPickerField(
                label = "Si le réseau est indisponible",
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
                label = "Nombre de nouvelles tentatives",
                value = retryCount.toString(),
                numeric = true,
                hint = "Réessais après un envoi refusé par Home Assistant (0 = aucun).",
                onValueChange = {
                    it.trim().toIntOrNull()?.let { v ->
                        Prefs.setRetryCount(ctx, v)
                        retryCount = Prefs.getRetryCount(ctx)
                    }
                },
            )
            TvTextField(
                label = "Délai d'attente par tentative (secondes)",
                value = retryTimeout.toString(),
                numeric = true,
                onValueChange = {
                    it.trim().toIntOrNull()?.let { v ->
                        Prefs.setRetryTimeoutSeconds(ctx, v)
                        retryTimeout = Prefs.getRetryTimeoutSeconds(ctx)
                    }
                },
            )
            TvTextField(
                label = "Délai avant « son démarré » (ms)",
                value = onDebounce.toString(),
                numeric = true,
                onValueChange = {
                    it.trim().toLongOrNull()?.let { v ->
                        Prefs.setOnDebounceMs(ctx, v.coerceIn(0, 30_000))
                        onDebounce = Prefs.getOnDebounceMs(ctx)
                    }
                },
            )
            TvTextField(
                label = "Délai avant « son arrêté » (ms)",
                value = offDebounce.toString(),
                numeric = true,
                onValueChange = {
                    it.trim().toLongOrNull()?.let { v ->
                        Prefs.setOffDebounceMs(ctx, v.coerceIn(0, 60_000))
                        offDebounce = Prefs.getOffDebounceMs(ctx)
                    }
                },
            )
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    playing: Boolean,
    lastSync: String,
    serverUrl: String?,
) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(
                    when {
                        !running -> Color(0xFF6E7486)
                        playing -> Color(0xFF52D07E)
                        else -> Color(0xFFE0B341)
                    },
                )
                Text(
                    when {
                        !running -> "  Surveillance arrêtée"
                        playing -> "  Son en cours de lecture"
                        else -> "  Aucun son détecté"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                "Dernier envoi : $lastSync",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Configuration depuis un PC ou un téléphone : " +
                    (serverUrl ?: "serveur local indisponible"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!Prefs.isConfigured(ctx)) {
                Text(
                    "Configuration incomplète : renseignez l'URL, le token et l'entité.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .background(color, CircleShape),
    )
}
