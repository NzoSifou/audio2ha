package fr.nzosifou.audio2ha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service de premier plan qui surveille la lecture audio de la TV via
 * [AudioManager.AudioPlaybackCallback] et pousse l'état vers Home Assistant.
 */
class AudioMonitorService : Service() {

    companion object {
        const val ACTION_START = "fr.nzosifou.audio2ha.START"
        const val ACTION_STOP = "fr.nzosifou.audio2ha.STOP"
        const val ACTION_RESEND = "fr.nzosifou.audio2ha.RESEND"
        const val ACTION_CONFIG_CHANGED = "fr.nzosifou.audio2ha.CONFIG_CHANGED"

        private const val CHANNEL_ID = "audio2ha_monitor"
        private const val NOTIF_ID = 1001

        /** Ré-émission périodique de l'état (les entités créées par l'API REST
         *  disparaissent si Home Assistant redémarre). */
        private const val HEARTBEAT_MS = 5 * 60 * 1000L

        /** Filet de sécurité si le callback ne se déclenche pas. */
        private const val POLL_MS = 2000L

        fun start(ctx: Context) {
            val i = Intent(ctx, AudioMonitorService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AudioMonitorService::class.java).setAction(ACTION_STOP))
        }

        fun send(ctx: Context, action: String) {
            if (!MonitorStatus.running.value) return
            ctx.startService(Intent(ctx, AudioMonitorService::class.java).setAction(action))
        }
    }

    private lateinit var audioManager: AudioManager
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private val network = Executors.newSingleThreadExecutor()
    private val seq = AtomicInteger(0)

    /** Dernier état confirmé (après anti-rebond) ; null tant que rien n'a été publié. */
    private var committedPlaying: Boolean? = null

    /** Dernier état brut observé. */
    private var rawPlaying = false
    private var pendingCommit: Runnable? = null
    private var started = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            evaluate(configs, "callback")
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            evaluate(audioManager.activePlaybackConfigurations, "poll")
            worker.postDelayed(this, POLL_MS)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            committedPlaying?.let { push(it, heartbeat = true) }
            worker.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        workerThread = HandlerThread("audio2ha-monitor").also { it.start() }
        worker = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (!started && (action == ACTION_RESEND || action == ACTION_CONFIG_CHANGED)) {
            // rien à faire tant que la surveillance n'est pas démarrée
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESEND -> {
                worker.post { push(committedPlaying ?: rawPlaying, forced = true) }
                return START_STICKY
            }

            ACTION_CONFIG_CHANGED -> {
                worker.post {
                    restartConfigServer()
                    push(committedPlaying ?: rawPlaying, forced = true)
                }
                return START_STICKY
            }
        }

        goForeground(false)
        if (!started) {
            started = true
            MonitorStatus.setRunning(true)
            Prefs.setMonitorEnabled(this, true)
            LogStore.audio("Surveillance démarrée")
            audioManager.registerAudioPlaybackCallback(playbackCallback, worker)
            worker.post {
                initialEvaluate()
                restartConfigServer()
            }
            worker.postDelayed(poller, POLL_MS)
            worker.postDelayed(heartbeat, HEARTBEAT_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (started) {
            runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
            worker.removeCallbacksAndMessages(null)
            LogStore.audio("Surveillance arrêtée")
        }
        MonitorStatus.setRunning(false)
        Prefs.setMonitorEnabled(this, false)
        workerThread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- détection

    /** Usages ignorés : bips d'interface, notifications… */
    private fun isRelevant(config: AudioPlaybackConfiguration): Boolean {
        val usage = config.audioAttributes.usage
        return usage != AudioAttributes.USAGE_ASSISTANCE_SONIFICATION &&
            usage != AudioAttributes.USAGE_NOTIFICATION &&
            usage != AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    }

    /** Premier relevé au démarrage : on publie l'état sans anti-rebond. */
    private fun initialEvaluate() {
        val configs = audioManager.activePlaybackConfigurations
        val relevant = configs.filter { isRelevant(it) }
        val playing = relevant.isNotEmpty() || audioManager.isMusicActive
        rawPlaying = playing
        committedPlaying = playing
        MonitorStatus.setAudioPlaying(playing)
        LogStore.audio(
            if (playing) "État initial : son en cours" else "État initial : aucun son",
            describe(configs, relevant, "démarrage"),
        )
        push(playing, forced = true)
    }

    private fun describe(
        configs: List<AudioPlaybackConfiguration>,
        relevant: List<AudioPlaybackConfiguration>,
        source: String,
    ): String = buildString {
        append("source=").append(source)
        append(", flux actifs=").append(configs.size)
        append(" (retenus=").append(relevant.size).append(")")
        append(", isMusicActive=").append(audioManager.isMusicActive)
        if (relevant.isNotEmpty()) {
            append(", usages=")
            append(relevant.joinToString("/") { usageName(it.audioAttributes.usage) })
        }
    }

    private fun evaluate(configs: List<AudioPlaybackConfiguration>, source: String) {
        val relevant = configs.filter { isRelevant(it) }
        val playing = relevant.isNotEmpty() || audioManager.isMusicActive
        val detail = describe(configs, relevant, source)

        if (playing == rawPlaying && committedPlaying != null) return
        rawPlaying = playing

        val delay = if (playing) Prefs.getOnDebounceMs(this) else Prefs.getOffDebounceMs(this)
        pendingCommit?.let { worker.removeCallbacks(it) }

        if (committedPlaying == playing) {
            // retour à l'état déjà publié : on annule simplement le changement en attente
            pendingCommit = null
            return
        }

        LogStore.audio(
            if (playing) "Son détecté (en attente de confirmation ${delay} ms)"
            else "Plus de son (en attente de confirmation ${delay} ms)",
            detail,
        )

        val commit = Runnable {
            if (rawPlaying != playing) return@Runnable
            if (committedPlaying == playing) return@Runnable
            committedPlaying = playing
            MonitorStatus.setAudioPlaying(playing)
            LogStore.audio(
                if (playing) "▶ LE SON A COMMENCÉ" else "■ LE SON S'EST ARRÊTÉ",
                detail,
            )
            push(playing)
        }
        pendingCommit = commit
        if (delay <= 0) worker.post(commit) else worker.postDelayed(commit, delay)
    }

    private fun usageName(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE"
        AudioAttributes.USAGE_ALARM -> "ALARM"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "A11Y"
        AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
        else -> "USAGE_$usage"
    }

    // ------------------------------------------------------------ Home Assistant

    private fun push(playing: Boolean, forced: Boolean = false, heartbeat: Boolean = false) {
        val ctx = applicationContext
        val baseUrl = Prefs.getBaseUrl(ctx)
        val token = Prefs.getToken(ctx)
        val entityId = Prefs.getEntityId(ctx)

        goForeground(playing)

        if (baseUrl.isBlank() || token.isBlank()) {
            LogStore.ha("Envoi ignoré : configuration incomplète", "URL ou token manquant", error = true)
            MonitorStatus.setLastSync("Configuration incomplète")
            return
        }

        val state = if (playing) "on" else "off"
        val id = seq.incrementAndGet()
        val prefix = when {
            heartbeat -> "Rafraîchissement périodique"
            forced -> "Envoi forcé"
            else -> "Envoi"
        }
        LogStore.ha("$prefix -> $state", "$entityId sur $baseUrl")

        network.execute {
            var attempt = 0
            while (attempt < 3) {
                attempt++
                val result = HaClient.postState(
                    baseUrl = baseUrl,
                    token = token,
                    entityId = entityId,
                    state = state,
                    attributes = mapOf(
                        "friendly_name" to Prefs.getFriendlyName(ctx),
                        "device_class" to "sound",
                        "source" to "Audio2HA",
                        "device" to (Build.MODEL ?: "Android TV"),
                    ),
                )
                if (result.ok) {
                    LogStore.ha(
                        "OK ${result.httpCode} — état $state accepté",
                        "en ${result.durationMs} ms" + (if (attempt > 1) ", tentative $attempt" else ""),
                    )
                    MonitorStatus.setLastSync("$state envoyé (HTTP ${result.httpCode})")
                    return@execute
                }
                val codeLabel = if (result.httpCode > 0) "HTTP ${result.httpCode}" else "réseau"
                val fatal = result.httpCode in 400..499
                LogStore.ha(
                    "Échec ($codeLabel) — état $state" + if (fatal || attempt == 3) "" else ", nouvelle tentative",
                    result.shortBody(),
                    error = true,
                )
                MonitorStatus.setLastSync("Échec $codeLabel")
                if (fatal) return@execute
                Thread.sleep(1500L * attempt)
                if (seq.get() != id) return@execute // un envoi plus récent a pris le relais
            }
        }
    }

    // ------------------------------------------------------------- notification

    private fun goForeground(playing: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Surveillance audio", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio2HA")
            .setContentText(if (playing) "Son en cours de lecture" else "Aucun son détecté")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure {
            LogStore.audio("Impossible de passer en premier plan", it.toString(), error = true)
        }
    }

    // ---------------------------------------------------- serveur de configuration

    private fun restartConfigServer() {
        ConfigServerManager.start(applicationContext)
    }
}
