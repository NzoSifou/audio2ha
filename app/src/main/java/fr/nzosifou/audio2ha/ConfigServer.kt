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
            "Liste indisponible : enregistrez d'abord une adresse et un token valides."
        } else {
            "Uniquement pris en compte avec l'interrupteur virtuel."
        }
        val bootChecked = if (Prefs.isStartOnBoot(ctx)) " checked" else ""
        val tokenPlaceholder = if (Prefs.getToken(ctx).isNotEmpty()) {
            "déjà défini — laisser vide pour conserver"
        } else {
            "coller le token ici"
        }

        return """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#161826">
<title>Audio2HA</title>
<style>
  :root {
    --bg: #161826; --rail: #1b1d2c; --card: #232532;
    --accent: #9184d9; --accent-soft: #d2cefd;
    --text: #e9e9ed; --secondary: #b2b6ca; --muted: #75798c;
    --danger: #d98a8a; --outline: rgba(233,233,237,.16);
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 0 0 40px; background: var(--bg); color: var(--text);
    font-family: Inter, system-ui, -apple-system, sans-serif; font-size: 14px;
  }
  .bar {
    display: flex; align-items: center; gap: 10px; position: sticky; top: 0;
    padding: 12px 16px; background: var(--rail); color: var(--secondary);
    font-size: 12px; letter-spacing: .02em;
  }
  .bar .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--accent); }
  main { max-width: 620px; margin: 0 auto; padding: 22px 16px 0; }
  .brand { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
  .mark {
    width: 26px; height: 26px; border-radius: 7px; background: rgba(145,132,217,.16);
    display: flex; align-items: flex-end; justify-content: center; gap: 2px; padding-bottom: 6px;
  }
  .mark i { width: 3px; background: var(--accent); border-radius: 1px; }
  .mark i:nth-child(1) { height: 7px; }
  .mark i:nth-child(2) { height: 12px; }
  .mark i:nth-child(3) { height: 9px; }
  h1 { font-size: 20px; font-weight: 500; margin: 0; letter-spacing: -.015em; }
  p.sub { color: var(--secondary); margin: 0 0 22px; }
  h2 {
    font-size: 12px; text-transform: uppercase; letter-spacing: .1em; color: var(--accent);
    font-weight: 500; margin: 26px 0 12px;
  }
  label { display: block; margin-bottom: 14px; font-size: 12px; color: var(--secondary); }
  input, select {
    display: block; width: 100%; margin-top: 6px; padding: 10px 12px; font: inherit;
    font-size: 14px; letter-spacing: .02em; background: var(--card); color: var(--text);
    border: 1px solid var(--outline); border-radius: 8px; -webkit-appearance: none;
  }
  input:focus, select:focus { outline: none; border-color: var(--accent); }
  input.filled { border-color: var(--accent); color: var(--accent-soft); }
  .check { display: flex; align-items: center; gap: 10px; color: var(--text); font-size: 14px; }
  .check input { width: 18px; height: 18px; margin: 0; accent-color: var(--accent); }
  button {
    margin-top: 26px; width: 100%; height: 48px; font: inherit; font-size: 15px; font-weight: 500;
    background: transparent; color: var(--accent); border: 1px solid var(--accent);
    border-radius: 8px; cursor: pointer;
  }
  button:active { background: var(--card); }
  .row { display: flex; gap: 12px; }
  .row > * { flex: 1; }
  .banner {
    padding: 12px 14px; border-radius: 8px; margin-bottom: 18px;
    font-size: 13px; line-height: 1.45;
  }
  .banner.ok {
    background: rgba(145,132,217,.12); border: 1px solid var(--accent); color: var(--accent-soft);
  }
  .banner.err {
    background: rgba(217,138,138,.10); border: 1px solid var(--danger); color: var(--danger);
  }
  small { display: block; margin-top: 6px; color: var(--muted); font-size: 12px; }
  .tools { margin-top: 26px; font-size: 12px; color: var(--muted); line-height: 2; }
  .tools a { color: var(--accent); text-decoration: none; }
</style>
</head>
<body>
<div class="bar"><span class="dot"></span>${escape(localIp())}:${Prefs.CONFIG_SERVER_PORT} · ${escape(Build.MODEL ?: "Android TV")}</div>
<main>
  <div class="brand">
    <span class="mark"><i></i><i></i><i></i></span>
    <h1>Audio2HA</h1>
  </div>
  <p class="sub">Collez ici le token longue durée : le saisir à la télécommande est pénible.</p>
  $banner
  <form method="POST" action="/save">

    <h2>Connexion</h2>
    <label>Adresse de Home Assistant
      <input class="filled" name="base_url" value="${escape(Prefs.getBaseUrl(ctx))}" placeholder="http://192.168.1.10:8123">
      <small>Préférez l'adresse IP : les noms en .local ne sont pas résolus par Android TV.</small>
    </label>

    <label>Token longue durée
      <input name="token" value="" placeholder="$tokenPlaceholder">
      <small>Profil Home Assistant &gt; Sécurité &gt; Jetons d'accès longue durée.</small>
    </label>

    <h2>Entité</h2>
    <label>Nom du capteur
      <input name="friendly_name" value="${escape(Prefs.getFriendlyName(ctx))}">
    </label>

    <label>Type d'entité
      <select name="entity_kind">$kindOptions</select>
      <small>Un binary_sensor créé par l'API REST n'entre pas dans le registre de Home
      Assistant : il ne peut pas être rangé dans une pièce. L'interrupteur virtuel, lui,
      est un helper créé par l'application.</small>
    </label>

    <label>Identifiant de l'entité
      <input name="entity_id" value="${escape(Prefs.getEntityId(ctx))}">
    </label>

    <label>Pièce
      <select name="area_id">$areaOptions</select>
      <small>$areaNote</small>
    </label>

    <h2>Comportement</h2>
    <input type="hidden" name="start_on_boot" value="0">
    <label class="check"><input type="checkbox" name="start_on_boot" value="1"$bootChecked> Lancer la détection au démarrage de la TV</label>

    <label>Si le réseau est indisponible
      <select name="offline_mode">$offlineOptions</select>
      <small>« Attendre » conserve le dernier changement et le publie dès le retour du réseau.</small>
    </label>

    <div class="row">
      <label>Nouvelles tentatives
        <input name="retry_count" value="${Prefs.getRetryCount(ctx)}" inputmode="numeric">
      </label>
      <label>Délai par tentative (s)
        <input name="retry_timeout_s" value="${Prefs.getRetryTimeoutSeconds(ctx)}" inputmode="numeric">
      </label>
    </div>

    <div class="row">
      <label>Délai « son démarré » (ms)
        <input name="on_debounce_ms" value="${Prefs.getOnDebounceMs(ctx)}" inputmode="numeric">
      </label>
      <label>Délai « son arrêté » (ms)
        <input name="off_debounce_ms" value="${Prefs.getOffDebounceMs(ctx)}" inputmode="numeric">
      </label>
    </div>

    <button type="submit">Enregistrer et tester</button>
  </form>

  <p class="tools">
    <a href="/tone">Jouer un son de test</a> ·
    <a href="/offline?seconds=30">Simuler 30 s sans réseau</a><br>
    <a href="/status">État (JSON)</a> ·
    <a href="/players">Flux audio détectés</a>
  </p>
</main>
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
