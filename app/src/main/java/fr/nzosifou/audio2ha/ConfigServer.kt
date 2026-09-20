package fr.nzosifou.audio2ha

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Mini serveur HTTP local (sans dépendance) permettant de configurer l'application
 * depuis un PC ou un téléphone, pour éviter de saisir un token de 180 caractères
 * à la télécommande.
 */
class ConfigServer(private val ctx: Context) {

    private var socket: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start(): Boolean {
        return try {
            val s = ServerSocket(Prefs.CONFIG_SERVER_PORT)
            s.reuseAddress = true
            socket = s
            running = true
            thread = Thread({ loop(s) }, "audio2ha-config-server").apply {
                isDaemon = true
                start()
            }
            LogStore.ha("Serveur de configuration démarré", url())
            true
        } catch (e: Exception) {
            LogStore.ha("Serveur de configuration indisponible", e.toString(), error = true)
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    fun url(): String = "http://" + localIp() + ":" + Prefs.CONFIG_SERVER_PORT

    private fun loop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (running) LogStore.ha("Serveur de configuration : erreur", e.toString(), error = true)
                return
            }
            runCatching { handle(client) }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1]

        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).equals("Content-Length", true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else {
            ""
        }

        when {
            method == "GET" && (path == "/" || path.startsWith("/?")) ->
                respond(client, 200, "text/html; charset=utf-8", page(null))

            path.startsWith("/tone") -> {
                TestTone.play()
                respond(client, 200, "text/plain; charset=utf-8", "Son de test lance")
            }

            method == "GET" && path.startsWith("/status") ->
                respond(client, 200, "application/json; charset=utf-8", statusJson())

            method == "GET" && path.startsWith("/players") ->
                respond(client, 200, "application/json; charset=utf-8", playersJson())

            method == "POST" && path.startsWith("/save") -> {
                val message = save(parseForm(body))
                respond(client, 200, "text/html; charset=utf-8", page(message))
            }

            else -> respond(client, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val k = URLDecoder.decode(pair.substring(0, i), "UTF-8")
            val v = URLDecoder.decode(pair.substring(i + 1).replace("+", "%20"), "UTF-8")
            k to v
        }.toMap()

    private fun save(form: Map<String, String>): String {
        val url = form["base_url"]?.trim().orEmpty()
        val token = form["token"]?.trim().orEmpty()
        val entity = form["entity_id"]?.trim().orEmpty()
        val name = form["friendly_name"]?.trim().orEmpty()

        if (url.isEmpty() || entity.isEmpty()) {
            return "error|L'URL et l'entité sont obligatoires."
        }
        Prefs.saveConfig(
            ctx,
            baseUrl = url,
            token = if (token.isEmpty()) null else token,
            entityId = entity,
            friendlyName = name.ifEmpty { null },
        )
        form["on_debounce_ms"]?.toLongOrNull()?.let { Prefs.setOnDebounceMs(ctx, it.coerceIn(0, 30_000)) }
        form["off_debounce_ms"]?.toLongOrNull()?.let { Prefs.setOffDebounceMs(ctx, it.coerceIn(0, 60_000)) }

        LogStore.ha("Configuration enregistrée depuis le navigateur", url)
        AudioMonitorService.send(ctx, AudioMonitorService.ACTION_CONFIG_CHANGED)

        val effectiveToken = Prefs.getToken(ctx)
        if (effectiveToken.isEmpty()) {
            return "error|Configuration enregistrée, mais aucun token n'est défini."
        }
        val ping = HaClient.ping(Prefs.getBaseUrl(ctx), effectiveToken)
        return if (ping.ok) {
            "ok|Configuration enregistrée. Connexion à Home Assistant réussie (HTTP ${ping.httpCode})."
        } else {
            "error|Configuration enregistrée, mais le test a échoué : " +
                (if (ping.httpCode > 0) "HTTP ${ping.httpCode} " else "") + escape(ping.shortBody(160))
        }
    }

    /** Diagnostic : ce que l'application voit réellement via AudioManager. */
    private fun playersJson(): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val configs = am.activePlaybackConfigurations
        val arr = org.json.JSONArray()
        configs.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("usage", c.audioAttributes.usage)
                    put("content_type", c.audioAttributes.contentType)
                },
            )
        }
        return JSONObject().apply {
            put("active_playback_count", configs.size)
            put("is_music_active", am.isMusicActive)
            put("configurations", arr)
        }.toString()
    }

    private fun statusJson(): String = JSONObject().apply {
        put("device", Build.MODEL ?: "Android TV")
        put("base_url", Prefs.getBaseUrl(ctx))
        put("entity_id", Prefs.getEntityId(ctx))
        put("token_defined", Prefs.getToken(ctx).isNotEmpty())
        put("monitor_running", MonitorStatus.running.value)
        put("audio_playing", MonitorStatus.audioPlaying.value)
        put("last_sync", MonitorStatus.lastSync.value)
    }.toString()

    private fun respond(client: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (code == 200) "OK" else "Error"
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun page(message: String?): String {
        val banner = message?.let {
            val isOk = it.startsWith("ok|")
            val text = it.substringAfter("|")
            "<div class=\"banner " + (if (isOk) "ok" else "err") + "\">" + text + "</div>"
        } ?: ""

        return """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Audio2HA - Configuration</title>
<style>
  body { font-family: system-ui, sans-serif; background:#12141a; color:#e8eaf0; margin:0; padding:24px; }
  .card { max-width:640px; margin:0 auto; background:#1c1f28; border:1px solid #2c303c; border-radius:12px; padding:24px; }
  h1 { font-size:20px; margin:0 0 4px; }
  p.sub { color:#9aa1b1; margin:0 0 20px; font-size:14px; }
  label { display:block; margin-top:16px; font-size:13px; color:#9aa1b1; }
  input { width:100%; box-sizing:border-box; margin-top:6px; padding:10px 12px; font-size:14px;
          background:#12141a; color:#e8eaf0; border:1px solid #333847; border-radius:8px; }
  button { margin-top:22px; width:100%; padding:12px; font-size:15px; font-weight:600;
           background:#3d7dff; color:#fff; border:0; border-radius:8px; cursor:pointer; }
  .row { display:flex; gap:12px; }
  .row > div { flex:1; }
  .banner { padding:12px; border-radius:8px; margin-bottom:16px; font-size:14px; }
  .banner.ok { background:#12331f; border:1px solid #2c6b41; color:#9be8b4; }
  .banner.err { background:#331616; border:1px solid #6b2c2c; color:#f0a5a5; }
  small { color:#6e7486; display:block; margin-top:6px; font-size:12px; }
</style>
</head>
<body>
<div class="card">
  <h1>Audio2HA</h1>
  <p class="sub">Configuration de ${escape(Build.MODEL ?: "Android TV")}</p>
  $banner
  <form method="POST" action="/save">
    <label>Adresse de Home Assistant
      <input name="base_url" value="${escape(Prefs.getBaseUrl(ctx))}" placeholder="http://192.168.1.10:8123">
    </label>
    <small>Préférez l'adresse IP : les noms en .local ne sont pas toujours résolus par Android TV.</small>

    <label>Token d'accès longue durée
      <input name="token" value="" placeholder="${if (Prefs.getToken(ctx).isEmpty()) "coller le token ici" else "(déjà défini - laisser vide pour conserver)"}">
    </label>
    <small>Profil Home Assistant &gt; Sécurité &gt; Jetons d'accès longue durée.</small>

    <label>Entité à mettre à jour
      <input name="entity_id" value="${escape(Prefs.getEntityId(ctx))}">
    </label>
    <small>L'entité est créée automatiquement par l'API (état on / off).</small>

    <label>Nom affiché
      <input name="friendly_name" value="${escape(Prefs.getFriendlyName(ctx))}">
    </label>

    <div class="row">
      <div>
        <label>Délai avant "son démarré" (ms)
          <input name="on_debounce_ms" value="${Prefs.getOnDebounceMs(ctx)}">
        </label>
      </div>
      <div>
        <label>Délai avant "son arrêté" (ms)
          <input name="off_debounce_ms" value="${Prefs.getOffDebounceMs(ctx)}">
        </label>
      </div>
    </div>

    <button type="submit">Enregistrer et tester</button>
  </form>
</div>
</body>
</html>"""
    }

    companion object {
        /** Première adresse IPv4 non locale de la TV. */
        fun localIp(): String {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            }.getOrNull() ?: "adresse-ip-de-la-tv"
        }
    }
}
