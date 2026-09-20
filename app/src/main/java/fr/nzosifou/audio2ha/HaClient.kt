package fr.nzosifou.audio2ha

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Résultat d'un appel à l'API Home Assistant. */
data class HaResult(
    val ok: Boolean,
    val httpCode: Int,
    val body: String,
    val durationMs: Long,
) {
    fun shortBody(max: Int = 220): String =
        if (body.length <= max) body else body.take(max) + "..."
}

/** Petit client REST pour l'API Home Assistant (pas de dépendance externe). */
object HaClient {

    private const val CONNECT_TIMEOUT = 5000
    private const val READ_TIMEOUT = 8000

    /** POST /api/states/<entity_id> : crée ou met à jour l'entité. */
    fun postState(
        baseUrl: String,
        token: String,
        entityId: String,
        state: String,
        attributes: Map<String, Any?>,
    ): HaResult {
        val payload = JSONObject().apply {
            put("state", state)
            put("attributes", JSONObject(attributes.filterValues { it != null }))
        }
        return request("POST", "$baseUrl/api/states/$entityId", token, payload.toString())
    }

    /** GET /api/ : vérifie l'URL et le token. */
    fun ping(baseUrl: String, token: String): HaResult =
        request("GET", "$baseUrl/api/", token, null)

    private fun request(
        method: String,
        url: String,
        token: String,
        body: String?,
    ): HaResult {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (this is HttpsURLConnection) {
                    // rien de particulier : on garde la validation TLS par défaut
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HaResult(code in 200..299, code, text, System.currentTimeMillis() - start)
        } catch (e: IOException) {
            val hint = describe(e, url)
            HaResult(false, -1, hint, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            HaResult(false, -1, e.toString(), System.currentTimeMillis() - start)
        } finally {
            conn?.disconnect()
        }
    }

    private fun describe(e: IOException, url: String): String {
        val base = e.javaClass.simpleName + ": " + (e.message ?: "")
        val host = runCatching { URL(url).host }.getOrNull() ?: ""
        return if (e is java.net.UnknownHostException && host.endsWith(".local")) {
            "$base — les noms .local (mDNS) sont souvent non résolus sur Android TV, " +
                "utilisez plutôt l'adresse IP de Home Assistant."
        } else {
            base
        }
    }
}
