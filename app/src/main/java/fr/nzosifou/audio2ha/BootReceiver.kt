package fr.nzosifou.audio2ha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relance la surveillance après un redémarrage de la TV si elle était active. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        LogStore.init(context)
        if (Prefs.isMonitorEnabled(context) && Prefs.isConfigured(context)) {
            LogStore.audio("Redémarrage de la TV : relance de la surveillance")
            AudioMonitorService.start(context)
        }
    }
}
