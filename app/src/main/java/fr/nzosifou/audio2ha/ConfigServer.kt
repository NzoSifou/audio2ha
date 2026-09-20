package fr.nzosifou.audio2ha

import android.content.Context
import android.media.AudioManager
import android.os.Build
import org.json.JSONArray
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

    /** Pièces lues dans Home Assistant, avec un cache court pour ne pas ralentir la page. */
    @Volatile
    private var areas: List<HaArea> = emptyList()

    @Volatile
    private var areasFetchedAt = 0L

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
        client.soTimeout = 15_000
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
            method == "GET" && (path == "/" || path.startsWith("/?")) -> {
                refreshAreas()
                respond(client, 200, "text/html; charset=utf-8", page(null))
            }

            path.startsWith("/offline") -> {
                val seconds = Regex("""seconds=(\d+)""").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 30
                HaPublisher.simulateOutage(seconds.coerceIn(1, 600))
                respond(
                    client, 200, "text/plain; charset=utf-8",
                    "Coupure reseau simulee pendant $seconds s",
                )
            }

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
                refreshAreas(force = true)
                respond(client, 200, "text/html; charset=utf-8", page(message))
            }

            else -> respond(client, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun refreshAreas(force: Boolean = false) {
        if (Prefs.getToken(ctx).isEmpty()) return
        val age = System.currentTimeMillis() - areasFetchedAt
        if (!force && areas.isNotEmpty() && age < 60_000) return
        HaSetup.listAreas(ctx).getOrNull()?.let {
            areas = it
            areasFetchedAt = System.currentTimeMillis()
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
            return "error|L'adresse et l'identifiant de l'entité sont obligatoires."
        }

        form["entity_kind"]?.let { Prefs.setEntityKind(ctx, EntityKind.from(it)) }
        Prefs.saveConfig(
            ctx,
            baseUrl = url,
            token = if (token.isEmpty()) null else token,
            entityId = entity,
            friendlyName = name.ifEmpty { null },
        )
        form["area_id"]?.let { id ->
            Prefs.setArea(ctx, id, areas.firstOrNull { it.id == id }?.name ?: "")
        }
        form["offline_mode"]?.let { Prefs.setOfflineMode(ctx, OfflineMode.from(it)) }
        Prefs.setStartOnBoot(ctx, form["start_on_boot"] == "1")
        form["retry_count"]?.toIntOrNull()?.let { Prefs.setRetryCount(ctx, it) }
        form["retry_timeout_s"]?.toIntOrNull()?.let { Prefs.setRetryTimeoutSeconds(ctx, it) }
        form["on_debounce_ms"]?.toLongOrNull()?.let { Prefs.setOnDebounceMs(ctx, it.coerceIn(0, 30_000)) }
        form["off_debounce_ms"]?.toLongOrNull()?.let { Prefs.setOffDebounceMs(ctx, it.coerceIn(0, 60_000)) }

        LogStore.ha("Configuration enregistrée depuis le navigateur", url)
        AudioMonitorService.send(ctx, AudioMonitorService.ACTION_CONFIG_CHANGED)

        if (Prefs.getToken(ctx).isEmpty()) {
            return "error|Configuration enregistrée, mais aucun token n'est défini."
        }
        val ping = HaClient.ping(Prefs.getBaseUrl(ctx), Prefs.getToken(ctx))
        if (!ping.ok) {
            return "error|Configuration enregistrée, mais la connexion a échoué : " +
                (if (ping.httpCode > 0) "HTTP ${ping.httpCode} " else "") + escape(ping.shortBody(160))
        }
        val report = HaSetup.apply(ctx)
        return if (report.ok) {
            "ok|Configuration enregistrée et appliquée. ${escape(report.message)}"
        } else {
            "error|Connexion réussie, mais : ${escape(report.message)}"
        }
    }

    /** Diagnostic : ce que l'application voit réellement via AudioManager. */
    private fun playersJson(): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val configs = am.activePlaybackConfigurations
        val arr = JSONArray()
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
        put("entity_kind", Prefs.getEntityKind(ctx).name)
        put("friendly_name", Prefs.getFriendlyName(ctx))
        put("area", Prefs.getAreaName(ctx))
        put("token_defined", Prefs.getToken(ctx).isNotEmpty())
        put("start_on_boot", Prefs.isStartOnBoot(ctx))
        put("retry_count", Prefs.getRetryCount(ctx))
        put("retry_timeout_s", Prefs.getRetryTimeoutSeconds(ctx))
        put("offline_mode", Prefs.getOfflineMode(ctx).name)
        put("monitor_running", MonitorStatus.running.value)
        put("audio_playing", MonitorStatus.audioPlaying.value)
        put("network_available", HaPublisher.isOnline(ctx))
        put("simulated_outage_seconds_left", HaPublisher.outageSecondsLeft())
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

    private fun options(
        values: List<Pair<String, String>>,
        selected: String,
    ): String = values.joinToString("\n") { (value, label) ->
        val sel = if (value == selected) " selected" else ""
        "<option value=\"${escape(value)}\"$sel>${escape(label)}</option>"
    }

    private fun page(message: String?): String {
        val banner = message?.let {
            val isOk = it.startsWith("ok|")
            val text = it.substringAfter("|")
            "<div class=\"banner " + (if (isOk) "ok" else "err") + "\">" + text + "</div>"
        } ?: ""

        val kindOptions = options(
            EntityKind.entries.map { it.name to it.label },
            Prefs.getEntityKind(ctx).name,
        )
        val offlineOptions = options(
            OfflineMode.entries.map { it.name to it.label },
            Prefs.getOfflineMode(ctx).name,
        )
        val areaOptions = options(
            listOf("" to "Aucune pièce") + areas.map { it.id to it.name },
            Prefs.getAreaId(ctx),
        )
        val areaNote = if (areas.isEmpty()) {
            "Liste des pièces indisponible : enregistrez d'abord une adresse et un token valides."
        } else {
            "Uniquement pris en compte avec l'interrupteur virtuel."
        }
        val bootChecked = if (Prefs.isStartOnBoot(ctx)) " checked" else ""

        return """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Audio2HA - Configuration</title>
<style>
  body { font-family: system-ui, sans-serif; background:#12141a; color:#e8eaf0; margin:0; padding:24px; }
  .card { max-width:680px; margin:0 auto; background:#1c1f28; border:1px solid #2c303c; border-radius:12px; padding:24px; }
  h1 { font-size:20px; margin:0 0 4px; }
  h2 { font-size:14px; text-transform:uppercase; letter-spacing:.08em; color:#6fa8ff;
       margin:26px 0 0; padding-top:18px; border-top:1px solid #2c303c; }
  p.sub { color:#9aa1b1; margin:0 0 20px; font-size:14px; }
  label { display:block; margin-top:16px; font-size:13px; color:#9aa1b1; }
  input, select { width:100%; box-sizing:border-box; margin-top:6px; padding:10px 12px; font-size:14px;
          background:#12141a; color:#e8eaf0; border:1px solid #333847; border-radius:8px; }
  input[type=checkbox] { width:auto; margin-right:8px; vertical-align:middle; }
  button { margin-top:24px; width:100%; padding:12px; font-size:15px; font-weight:600;
           background:#3d7dff; color:#fff; border:0; border-radius:8px; cursor:pointer; }
  .row { display:flex; gap:12px; }
  .row > div { flex:1; }
  .banner { padding:12px; border-radius:8px; margin-bottom:16px; font-size:14px; }
  .banner.ok { background:#12331f; border:1px solid #2c6b41; color:#9be8b4; }
  .banner.err { background:#331616; border:1px solid #6b2c2c; color:#f0a5a5; }
  .tools { margin-top:20px; font-size:13px; color:#6e7486; }
  .tools a { color:#6fa8ff; }
  small { color:#6e7486; display:block; margin-top:6px; font-size:12px; }
</style>
</head>
<body>
<div class="card">
  <h1>Audio2HA</h1>
  <p class="sub">Configuration de ${escape(Build.MODEL ?: "Android TV")}</p>
  $banner
  <form method="POST" action="/save">

    <h2>Connexion</h2>
    <label>Adresse de Home Assistant
      <input name="base_url" value="${escape(Prefs.getBaseUrl(ctx))}" placeholder="http://192.168.1.10:8123">
    </label>
    <small>Préférez l'adresse IP : les noms en .local ne sont pas résolus par Android TV.</small>

    <label>Token d'accès longue durée
      <input name="token" value="" placeholder="${if (Prefs.getToken(ctx).isEmpty()) "coller le token ici" else "(déjà défini - laisser vide pour conserver)"}">
    </label>
    <small>Profil Home Assistant &gt; Sécurité &gt; Jetons d'accès longue durée.</small>

    <h2>Entité</h2>
    <label>Nom du capteur
      <input name="friendly_name" value="${escape(Prefs.getFriendlyName(ctx))}">
    </label>

    <label>Type d'entité
      <select name="entity_kind">$kindOptions</select>
    </label>
    <small>Un binary_sensor créé par l'API REST n'est pas enregistré dans Home Assistant et
    ne peut donc pas être rangé dans une pièce. L'interrupteur virtuel, lui, est un helper
    créé par l'application : il est rangeable.</small>

    <label>Identifiant de l'entité
      <input name="entity_id" value="${escape(Prefs.getEntityId(ctx))}">
    </label>

    <label>Pièce
      <select name="area_id">$areaOptions</select>
    </label>
    <small>$areaNote</small>

    <h2>Comportement</h2>
    <input type="hidden" name="start_on_boot" value="0">
    <label><input type="checkbox" name="start_on_boot" value="1"$bootChecked> Lancer la détection au démarrage de la TV</label>

    <label>Si le réseau est indisponible
      <select name="offline_mode">$offlineOptions</select>
    </label>
    <small>« Attendre » conserve le dernier changement et le publie dès le retour du réseau.</small>

    <div class="row">
      <div>
        <label>Nombre de nouvelles tentatives
          <input name="retry_count" value="${Prefs.getRetryCount(ctx)}">
        </label>
      </div>
      <div>
        <label>Délai d'attente par tentative (s)
          <input name="retry_timeout_s" value="${Prefs.getRetryTimeoutSeconds(ctx)}">
        </label>
      </div>
    </div>

    <div class="row">
      <div>
        <label>Délai avant « son démarré » (ms)
          <input name="on_debounce_ms" value="${Prefs.getOnDebounceMs(ctx)}">
        </label>
      </div>
      <div>
        <label>Délai avant « son arrêté » (ms)
          <input name="off_debounce_ms" value="${Prefs.getOffDebounceMs(ctx)}">
        </label>
      </div>
    </div>

    <button type="submit">Enregistrer et appliquer</button>
  </form>
  <p class="tools">Outils :
    <a href="/tone">jouer un son de test</a> ·
    <a href="/offline?seconds=30">simuler 30 s sans réseau</a> ·
    <a href="/status">état</a> ·
    <a href="/players">flux audio détectés</a>
  </p>
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
