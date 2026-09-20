package fr.nzosifou.audio2ha

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

enum class PublishOutcome { SENT, FAILED, NO_NETWORK }

/** Envoi de l'état vers Home Assistant : réessais, réseau absent, journalisation. */
object HaPublisher {

    /** Fin d'une coupure réseau simulée, pour tester le comportement hors-ligne. */
    @Volatile
    private var simulatedOutageUntil = 0L

    /** Fait croire à l'application qu'il n'y a plus de réseau pendant [seconds]. */
    fun simulateOutage(seconds: Int) {
        simulatedOutageUntil = System.currentTimeMillis() + seconds * 1000L
        LogStore.ha("Coupure réseau simulée", "pendant $seconds s")
    }

    fun outageSecondsLeft(): Long =
        ((simulatedOutageUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    /** Vrai si un réseau capable d'atteindre Home Assistant est disponible. */
    fun isOnline(ctx: Context): Boolean {
        if (System.currentTimeMillis() < simulatedOutageUntil) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        // NET_CAPABILITY_VALIDATED n'est pas exigé : Home Assistant est sur le réseau
        // local, il reste joignable même sans accès Internet.
        return true
    }

    /**
     * Publie [playing] sur l'entité configurée. Bloquant : à appeler depuis un thread
     * de travail. [label] décrit l'origine de l'envoi pour les journaux.
     */
    fun publish(ctx: Context, playing: Boolean, label: String): PublishOutcome {
        val baseUrl = Prefs.getBaseUrl(ctx)
        val token = Prefs.getToken(ctx)
        val entityId = Prefs.getEntityId(ctx)
        val kind = Prefs.getEntityKind(ctx)
        val state = if (playing) "on" else "off"

        if (baseUrl.isBlank() || token.isBlank()) {
            LogStore.ha("Envoi ignoré : configuration incomplète", "URL ou token manquant", error = true)
            MonitorStatus.setLastSync("Configuration incomplète")
            return PublishOutcome.FAILED
        }

        if (!isOnline(ctx)) return PublishOutcome.NO_NETWORK

        val timeoutMs = Prefs.getRetryTimeoutSeconds(ctx) * 1000
        val attempts = 1 + Prefs.getRetryCount(ctx)
        LogStore.ha("$label -> $state", "$entityId sur $baseUrl")

        for (attempt in 1..attempts) {
            val result = when (kind) {
                EntityKind.BINARY_SENSOR -> HaClient.postState(
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
                    timeoutMs = timeoutMs,
                )

                EntityKind.INPUT_BOOLEAN -> HaClient.callService(
                    baseUrl = baseUrl,
                    token = token,
                    domain = "input_boolean",
                    service = if (playing) "turn_on" else "turn_off",
                    entityId = entityId,
                    timeoutMs = timeoutMs,
                )
            }

            if (result.ok) {
                LogStore.ha(
                    "OK ${result.httpCode} — état $state accepté",
                    "en ${result.durationMs} ms" +
                        if (attempt > 1) ", tentative $attempt/$attempts" else "",
                )
                MonitorStatus.setLastSync("$state envoyé (HTTP ${result.httpCode})")
                return PublishOutcome.SENT
            }

            val codeLabel = if (result.httpCode > 0) "HTTP ${result.httpCode}" else "réseau"
            LogStore.ha(
                "Échec ($codeLabel) — tentative $attempt/$attempts pour l'état $state",
                result.shortBody(),
                error = true,
            )
            MonitorStatus.setLastSync("Échec $codeLabel")

            if (attempt < attempts) {
                if (!isOnline(ctx)) return PublishOutcome.NO_NETWORK
                runCatching { Thread.sleep(1000L) }
            }
        }

        LogStore.ha("Abandon après $attempts tentative(s) — état $state non publié", error = true)
        return PublishOutcome.FAILED
    }
}
