package fr.nzosifou.audio2ha

import android.content.Context

/**
 * Détient l'unique instance du serveur de configuration : l'activité comme le service
 * peuvent demander son démarrage, il ne doit y avoir qu'un seul socket sur le port.
 */
object ConfigServerManager {

    private var server: ConfigServer? = null

    @Synchronized
    fun start(ctx: Context) {
        if (!Prefs.isConfigServerEnabled(ctx)) return
        if (server != null) return
        val s = ConfigServer(ctx.applicationContext)
        if (s.start()) {
            server = s
            MonitorStatus.setConfigServer(s.url())
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        MonitorStatus.setConfigServer(null)
    }
}
