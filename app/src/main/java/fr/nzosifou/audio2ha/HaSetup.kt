package fr.nzosifou.audio2ha

import android.content.Context

/**
 * Prépare l'entité côté Home Assistant : création du helper `input_boolean` si besoin
 * et affectation de la pièce. Bloquant, à appeler depuis un thread de travail.
 */
object HaSetup {

    /** Message destiné à l'utilisateur (succès comme échec). */
    data class Report(val ok: Boolean, val message: String)

    fun listAreas(ctx: Context): Result<List<HaArea>> = runCatching {
        HaWebSocket.connect(Prefs.getBaseUrl(ctx), Prefs.getToken(ctx)) { it.listAreas() }
    }.onFailure {
        LogStore.ha("Impossible de lister les pièces", it.toString(), error = true)
    }

    /**
     * Applique la configuration d'entité : crée le helper si nécessaire, renomme,
     * et range l'entité dans la pièce choisie.
     */
    fun apply(ctx: Context): Report {
        val baseUrl = Prefs.getBaseUrl(ctx)
        val token = Prefs.getToken(ctx)
        if (baseUrl.isBlank() || token.isBlank()) {
            return Report(false, "URL ou token manquant.")
        }

        val kind = Prefs.getEntityKind(ctx)
        val areaId = Prefs.getAreaId(ctx)
        val name = Prefs.getFriendlyName(ctx)

        if (kind == EntityKind.BINARY_SENSOR) {
            return if (areaId.isEmpty()) {
                Report(true, "Entité binary_sensor prête.")
            } else {
                Report(
                    false,
                    "Un binary_sensor créé par l'API REST n'est pas enregistré dans Home " +
                        "Assistant : il ne peut pas être rangé dans une pièce. Choisissez " +
                        "« Interrupteur virtuel » pour utiliser la pièce.",
                )
            }
        }

        // input_boolean : l'entité doit exister dans le registre.
        val timeout = Prefs.getRetryTimeoutSeconds(ctx) * 1000
        var entityId = Prefs.getEntityId(ctx)
        val existing = HaClient.getState(baseUrl, token, entityId, timeout)

        return runCatching {
            HaWebSocket.connect(baseUrl, token) { ws ->
                if (!existing.ok) {
                    entityId = ws.createInputBoolean(name)
                    Prefs.setEntityId(ctx, entityId)
                    LogStore.ha("Helper créé dans Home Assistant", entityId)
                } else {
                    runCatching { ws.renameInputBoolean(entityId.substringAfter('.'), name) }
                }
                ws.setArea(entityId, areaId)
            }
            val where = if (areaId.isEmpty()) "sans pièce" else "dans « ${Prefs.getAreaName(ctx)} »"
            LogStore.ha("Entité configurée", "$entityId $where")
            Report(true, "Entité $entityId prête ($where).")
        }.getOrElse {
            LogStore.ha("Configuration de l'entité impossible", it.toString(), error = true)
            Report(false, "Home Assistant a refusé : " + (it.message ?: it.toString()))
        }
    }
}
