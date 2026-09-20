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
import androidx.compose.material3.OutlinedTextField
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
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
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
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val firstButton = remember { FocusRequester() }

    val running by MonitorStatus.running.collectAsState()
    val playing by MonitorStatus.audioPlaying.collectAsState()
    val lastSync by MonitorStatus.lastSync.collectAsState()
    val serverUrl by MonitorStatus.configServer.collectAsState()

    // Au premier affichage et à chaque retour dans l'application : on recharge les valeurs
    // (elles ont pu changer depuis le navigateur) et on place le focus sur un bouton.
    LaunchedEffect(resumeTick) {
        baseUrl = Prefs.getBaseUrl(ctx)
        token = Prefs.getToken(ctx)
        entityId = Prefs.getEntityId(ctx)
        delay(250)
        runCatching { firstButton.requestFocus() }
    }

    fun save() {
        Prefs.saveConfig(ctx, baseUrl = baseUrl, token = token, entityId = entityId)
        baseUrl = Prefs.getBaseUrl(ctx)
        AudioMonitorService.send(ctx, AudioMonitorService.ACTION_CONFIG_CHANGED)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    save()
                    if (running) AudioMonitorService.stop(ctx) else AudioMonitorService.start(ctx)
                },
            )

            TvButton(
                text = if (testing) "Test en cours..." else "Enregistrer et tester",
                primary = true,
                onClick = {
                    if (testing) return@TvButton
                    save()
                    testing = true
                    testResult = "Test en cours..."
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { HaClient.ping(baseUrl, token) }
                        testing = false
                        testResult = if (r.ok) {
                            LogStore.ha("Test de connexion réussi", "HTTP ${r.httpCode}")
                            "OK — Home Assistant répond (HTTP ${r.httpCode})"
                        } else {
                            LogStore.ha("Test de connexion échoué", r.shortBody(), error = true)
                            "Échec : " + (if (r.httpCode > 0) "HTTP ${r.httpCode} " else "") + r.shortBody(120)
                        }
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

        testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.startsWith("OK")) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error,
            )
        }

        Text(
            "Configuration — bien plus simple à saisir depuis un navigateur qu'à la télécommande.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Adresse de Home Assistant") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token d'accès longue durée") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = entityId,
            onValueChange = { entityId = it },
            label = { Text("Entité Home Assistant") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "L'entité est créée automatiquement côté Home Assistant : elle passe à « on » " +
                "quand un son est joué et à « off » sinon.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
