package fr.nzosifou.audio2ha

import android.content.Context
import android.os.Build

/** Configuration persistée de l'application (SharedPreferences). */
object Prefs {

    private const val FILE = "audio2ha_prefs"

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENTITY_ID = "entity_id"
    private const val KEY_FRIENDLY_NAME = "friendly_name"
    private const val KEY_MONITOR_ENABLED = "monitor_enabled"
    private const val KEY_CONFIG_SERVER_ENABLED = "config_server_enabled"
    private const val KEY_OFF_DEBOUNCE_MS = "off_debounce_ms"
    private const val KEY_ON_DEBOUNCE_MS = "on_debounce_ms"

    const val DEFAULT_BASE_URL = "http://homeassistant.local:8123"
    const val CONFIG_SERVER_PORT = 8099

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun defaultEntityId(): String {
        val model = (Build.MODEL ?: "android_tv")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "android_tv" }
        return "binary_sensor.${model}_audio"
    }

    fun getBaseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun getToken(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, "") ?: ""

    fun getEntityId(ctx: Context): String =
        prefs(ctx).getString(KEY_ENTITY_ID, null) ?: defaultEntityId()

    fun getFriendlyName(ctx: Context): String =
        prefs(ctx).getString(KEY_FRIENDLY_NAME, null) ?: "${Build.MODEL ?: "Android TV"} audio"

    fun isMonitorEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MONITOR_ENABLED, false)

    fun isConfigServerEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CONFIG_SERVER_ENABLED, true)

    /** Délai de confirmation avant de publier "son arrêté" (anti-clignotement). */
    fun getOffDebounceMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_OFF_DEBOUNCE_MS, 3000L)

    /** Délai de confirmation avant de publier "son démarré" (filtre les bips d'interface). */
    fun getOnDebounceMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_ON_DEBOUNCE_MS, 500L)

    fun setMonitorEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply()

    fun setConfigServerEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled).apply()

    fun setOffDebounceMs(ctx: Context, value: Long) =
        prefs(ctx).edit().putLong(KEY_OFF_DEBOUNCE_MS, value).apply()

    fun setOnDebounceMs(ctx: Context, value: Long) =
        prefs(ctx).edit().putLong(KEY_ON_DEBOUNCE_MS, value).apply()

    /** Enregistre la configuration principale. Les champs nuls ne sont pas modifiés. */
    fun saveConfig(
        ctx: Context,
        baseUrl: String? = null,
        token: String? = null,
        entityId: String? = null,
        friendlyName: String? = null,
    ) {
        val e = prefs(ctx).edit()
        baseUrl?.let { e.putString(KEY_BASE_URL, normalizeBaseUrl(it)) }
        token?.let { e.putString(KEY_TOKEN, it.trim()) }
        entityId?.let { e.putString(KEY_ENTITY_ID, it.trim()) }
        friendlyName?.let { e.putString(KEY_FRIENDLY_NAME, it.trim()) }
        e.apply()
    }

    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "http://$url"
        }
        return url.trimEnd('/')
    }

    fun isConfigured(ctx: Context): Boolean =
        getBaseUrl(ctx).isNotBlank() && getToken(ctx).isNotBlank() && getEntityId(ctx).contains('.')
}
