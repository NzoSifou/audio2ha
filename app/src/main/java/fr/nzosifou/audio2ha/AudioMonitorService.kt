package fr.nzosifou.audio2ha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.util.concurrent.Executors

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

    /** État en attente d'un réseau, quand le mode hors-ligne est « attendre ». */
    @Volatile
    private var deferredState: Boolean? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
            retryDeferred()
            worker.postDelayed(this, POLL_MS)
        }
    }

    /**
     * Republie l'état mis de côté pendant une coupure réseau. Le rappel de
     * [ConnectivityManager] ne se déclenche pas dans tous les cas (réseau jamais perdu
     * du point de vue du système, retour manqué…), ce relevé sert de filet.
     */
    private fun retryDeferred() {
        val pending = deferredState ?: return
        if (!HaPublisher.isOnline(this)) return
        deferredState = null
        LogStore.ha("Réseau de nouveau disponible : publication de l'état en attente")
        push(pending, forced = true)
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
            registerNetworkCallback()
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
        unregisterNetworkCallback()
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
        goForeground(playing)
        val label = when {
            heartbeat -> "Rafraîchissement périodique"
            forced -> "Envoi forcé"
            else -> "Envoi"
        }
        network.execute {
            when (HaPublisher.publish(applicationContext, playing, label)) {
                PublishOutcome.SENT, PublishOutcome.FAILED -> deferredState = null

                PublishOutcome.NO_NETWORK -> when (Prefs.getOfflineMode(applicationContext)) {
                    OfflineMode.DROP -> {
                        LogStore.ha(
                            "Envoi ignoré : aucun réseau disponible",
                            "état " + (if (playing) "on" else "off") + " abandonné",
                            error = true,
                        )
                        MonitorStatus.setLastSync("Pas de réseau — envoi ignoré")
                    }

                    OfflineMode.WAIT -> {
                        deferredState = playing
                        LogStore.ha(
                            "Aucun réseau : envoi mis en attente",
                            "état " + (if (playing) "on" else "off") +
                                ", sera publié au retour du réseau",
                            error = true,
                        )
                        MonitorStatus.setLastSync("En attente du réseau")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------- réseau

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                val pending = deferredState ?: return
                deferredState = null
                LogStore.ha("Réseau revenu : publication de l'état en attente")
                worker.post { push(pending, forced = true) }
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
            networkCallback = callback
        }.onFailure {
            LogStore.ha("Surveillance du réseau indisponible", it.toString(), error = true)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
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
