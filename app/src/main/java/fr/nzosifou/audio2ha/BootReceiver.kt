package fr.nzosifou.audio2ha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Démarre la surveillance après un redémarrage de la TV, si l'option est activée. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        LogStore.init(context)
        if (!Prefs.isStartOnBoot(context)) return
        if (!Prefs.isConfigured(context)) {
            LogStore.audio(
                "Démarrage automatique annulé : configuration incomplète",
                error = true,
            )
            return
        }
        LogStore.audio("Démarrage de la TV : lancement automatique de la surveillance")
        AudioMonitorService.start(context)
    }
}
