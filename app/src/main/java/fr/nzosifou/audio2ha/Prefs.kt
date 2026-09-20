package fr.nzosifou.audio2ha

import android.content.Context
import android.os.Build

/** Type d'entité publiée dans Home Assistant. */
enum class EntityKind(val label: String, val prefix: String) {
    /**
     * Entité créée à la volée par l'API REST. Simple, mais absente du registre
     * d'entités de Home Assistant : elle ne peut pas être rangée dans une pièce.
     */
    BINARY_SENSOR("Capteur binaire (binary_sensor)", "binary_sensor"),

    /**
     * Helper créé par l'application. Enregistré dans Home Assistant, donc
     * affectable à une pièce.
     */
    INPUT_BOOLEAN("Interrupteur virtuel (input_boolean)", "input_boolean"),
    ;

    companion object {
        fun from(value: String?): EntityKind =
            entries.firstOrNull { it.name == value } ?: BINARY_SENSOR
    }
}

/** Comportement quand le réseau est indisponible au moment d'un changement d'état. */
enum class OfflineMode(val label: String) {
    /** On abandonne l'envoi (comportement par défaut). */
    DROP("Ignorer l'envoi"),

    /** On garde l'état de côté et on l'envoie dès le retour du réseau. */
    WAIT("Attendre le retour du réseau"),
    ;

    companion object {
        fun from(value: String?): OfflineMode =
            entries.firstOrNull { it.name == value } ?: DROP
    }
}

/** Configuration persistée de l'application (SharedPreferences). */
object Prefs {

    private const val FILE = "audio2ha_prefs"

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENTITY_ID = "entity_id"
    private const val KEY_FRIENDLY_NAME = "friendly_name"
    private const val KEY_ENTITY_KIND = "entity_kind"
    private const val KEY_AREA_ID = "area_id"
    private const val KEY_AREA_NAME = "area_name"
    private const val KEY_MONITOR_ENABLED = "monitor_enabled"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_CONFIG_SERVER_ENABLED = "config_server_enabled"
    private const val KEY_OFF_DEBOUNCE_MS = "off_debounce_ms"
    private const val KEY_ON_DEBOUNCE_MS = "on_debounce_ms"
    private const val KEY_RETRY_COUNT = "retry_count"
    private const val KEY_RETRY_TIMEOUT_S = "retry_timeout_s"
    private const val KEY_OFFLINE_MODE = "offline_mode"

    const val DEFAULT_BASE_URL = "http://homeassistant.local:8123"
    const val CONFIG_SERVER_PORT = 8099

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun deviceSlug(): String = (Build.MODEL ?: "android_tv")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifEmpty { "android_tv" }

    // ------------------------------------------------------------------ lecture

    fun getBaseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun getToken(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, "") ?: ""

    fun getEntityId(ctx: Context): String =
        prefs(ctx).getString(KEY_ENTITY_ID, null) ?: "binary_sensor.${deviceSlug()}_audio"

    fun getFriendlyName(ctx: Context): String =
        prefs(ctx).getString(KEY_FRIENDLY_NAME, null) ?: "${Build.MODEL ?: "Android TV"} audio"

    fun getEntityKind(ctx: Context): EntityKind =
        EntityKind.from(prefs(ctx).getString(KEY_ENTITY_KIND, null))

    fun getAreaId(ctx: Context): String = prefs(ctx).getString(KEY_AREA_ID, "") ?: ""

    fun getAreaName(ctx: Context): String = prefs(ctx).getString(KEY_AREA_NAME, "") ?: ""

    fun isMonitorEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MONITOR_ENABLED, false)

    fun isStartOnBoot(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_START_ON_BOOT, false)

    fun isConfigServerEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CONFIG_SERVER_ENABLED, true)

    /** Délai de confirmation avant de publier « son arrêté » (anti-clignotement). */
    fun getOffDebounceMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_OFF_DEBOUNCE_MS, 3000L)

    /** Délai de confirmation avant de publier « son démarré » (filtre les bips d'interface). */
    fun getOnDebounceMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_ON_DEBOUNCE_MS, 500L)

    /** Nombre de nouvelles tentatives après un échec (0 = aucune). */
    fun getRetryCount(ctx: Context): Int = prefs(ctx).getInt(KEY_RETRY_COUNT, 3)

    /** Délai d'attente maximal d'une tentative, en secondes. */
    fun getRetryTimeoutSeconds(ctx: Context): Int = prefs(ctx).getInt(KEY_RETRY_TIMEOUT_S, 8)

    fun getOfflineMode(ctx: Context): OfflineMode =
        OfflineMode.from(prefs(ctx).getString(KEY_OFFLINE_MODE, null))

    // ------------------------------------------------------------------ écriture

    fun setMonitorEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply()

    fun setStartOnBoot(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()

    fun setConfigServerEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled).apply()

    fun setOffDebounceMs(ctx: Context, value: Long) =
        prefs(ctx).edit().putLong(KEY_OFF_DEBOUNCE_MS, value).apply()

    fun setOnDebounceMs(ctx: Context, value: Long) =
        prefs(ctx).edit().putLong(KEY_ON_DEBOUNCE_MS, value).apply()

    fun setRetryCount(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_RETRY_COUNT, value.coerceIn(0, 20)).apply()

    fun setRetryTimeoutSeconds(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_RETRY_TIMEOUT_S, value.coerceIn(1, 120)).apply()

    fun setOfflineMode(ctx: Context, mode: OfflineMode) =
        prefs(ctx).edit().putString(KEY_OFFLINE_MODE, mode.name).apply()

    fun setEntityKind(ctx: Context, kind: EntityKind) {
        val e = prefs(ctx).edit().putString(KEY_ENTITY_KIND, kind.name)
        // L'identifiant doit rester cohérent avec le type choisi.
        val current = getEntityId(ctx)
        val objectId = current.substringAfter('.', "${deviceSlug()}_audio")
        e.putString(KEY_ENTITY_ID, "${kind.prefix}.$objectId")
        e.apply()
    }

    fun setArea(ctx: Context, areaId: String, areaName: String) =
        prefs(ctx).edit()
            .putString(KEY_AREA_ID, areaId.trim())
            .putString(KEY_AREA_NAME, areaName.trim())
            .apply()

    fun setEntityId(ctx: Context, entityId: String) =
        prefs(ctx).edit().putString(KEY_ENTITY_ID, entityId.trim()).apply()

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
