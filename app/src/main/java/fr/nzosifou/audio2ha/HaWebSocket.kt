package fr.nzosifou.audio2ha

import android.util.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

/** Une pièce (area) déclarée dans Home Assistant. */
data class HaArea(val id: String, val name: String)

/**
 * Client WebSocket minimal pour l'API Home Assistant.
 *
 * L'API REST ne permet ni de lister les pièces, ni de créer un helper, ni de ranger
 * une entité dans une pièce : tout cela passe par le WebSocket. On implémente donc
 * le strict nécessaire (RFC 6455, trames texte uniquement) pour éviter une dépendance.
 */
class HaWebSocket private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
) : AutoCloseable {

    private var nextId = 1
    private val random = SecureRandom()

    companion object {

        private const val TIMEOUT_MS = 10_000

        /** Ouvre une session authentifiée, exécute [block], puis referme. */
        fun <T> connect(baseUrl: String, token: String, block: (HaWebSocket) -> T): T {
            val uri = URI(baseUrl.trimEnd('/') + "/api/websocket")
            val secure = uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val socket = if (secure) {
                SSLSocketFactory.getDefault().createSocket(uri.host, port)
            } else {
                Socket(uri.host, port)
            }
            socket.soTimeout = TIMEOUT_MS
            val ws = HaWebSocket(
                socket,
                DataInputStream(socket.getInputStream().buffered()),
                socket.getOutputStream(),
            )
            return ws.use {
                it.handshake(uri.host, port, uri.path)
                it.authenticate(token)
                block(it)
            }
        }
    }

    // ------------------------------------------------------------------ protocole

    private fun handshake(host: String, port: Int, path: String) {
        val key = Base64.encodeToString(ByteArray(16).also { random.nextBytes(it) }, Base64.NO_WRAP)
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        output.write(request.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val statusLine = readHeaderLine()
        require(statusLine.contains("101")) { "Le serveur a refusé le WebSocket : $statusLine" }
        while (readHeaderLine().isNotEmpty()) {
            // on ignore les en-têtes, la validation du Sec-WebSocket-Accept n'apporte
            // rien ici : le canal est déjà authentifié par le token
        }
    }

    private fun readHeaderLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw java.io.EOFException("connexion fermée pendant la poignée de main")
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun authenticate(token: String) {
        val hello = receive()
        require(hello.optString("type") == "auth_required") {
            "Réponse inattendue : ${hello.optString("type")}"
        }
        send(JSONObject().put("type", "auth").put("access_token", token))
        val result = receive()
        require(result.optString("type") == "auth_ok") {
            "Authentification refusée : " + result.optString("message", result.toString())
        }
    }

    private fun send(message: JSONObject) {
        val payload = message.toString().toByteArray(Charsets.UTF_8)
        val header = java.io.ByteArrayOutputStream()
        header.write(0x81) // FIN + opcode texte
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65536 -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) {
                    header.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    private fun receive(): JSONObject {
        while (true) {
            val b0 = input.readUnsignedByte()
            val opcode = b0 and 0x0F
            val b1 = input.readUnsignedByte()
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((input.readUnsignedByte() shl 8) or input.readUnsignedByte()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.readUnsignedByte().toLong() }
            }
            require(length < 4_000_000) { "trame WebSocket trop grande" }
            val payload = ByteArray(length.toInt())
            input.readFully(payload)
            when (opcode) {
                0x1 -> return JSONObject(String(payload, Charsets.UTF_8))
                0x8 -> throw java.io.EOFException("le serveur a fermé la connexion")
                else -> Unit // ping/pong/binaire : ignorés
            }
        }
    }

    /** Envoie une commande et rend le résultat, ou lève une exception explicite. */
    private fun command(type: String, build: JSONObject.() -> Unit = {}): JSONObject {
        val id = nextId++
        send(JSONObject().put("id", id).put("type", type).apply(build))
        while (true) {
            val response = receive()
            if (response.optInt("id") != id) continue
            if (!response.optBoolean("success")) {
                val error = response.optJSONObject("error")
                throw IllegalStateException(
                    error?.optString("message") ?: "commande $type refusée",
                )
            }
            return response
        }
    }

    // ------------------------------------------------------------------ commandes

    /** Liste les pièces déclarées dans Home Assistant. */
    fun listAreas(): List<HaArea> {
        val result = command("config/area_registry/list").optJSONArray("result") ?: return emptyList()
        return (0 until result.length()).map { i ->
            val area = result.getJSONObject(i)
            HaArea(area.getString("area_id"), area.optString("name", area.getString("area_id")))
        }.sortedBy { it.name.lowercase() }
    }

    /** Crée un helper input_boolean et rend son entity_id. */
    fun createInputBoolean(name: String, icon: String = "mdi:volume-high"): String {
        val result = command("input_boolean/create") {
            put("name", name)
            put("icon", icon)
        }.getJSONObject("result")
        return "input_boolean." + result.getString("id")
    }

    /** Met à jour le nom d'un helper existant. */
    fun renameInputBoolean(objectId: String, name: String, icon: String = "mdi:volume-high") {
        command("input_boolean/update") {
            put("input_boolean_id", objectId)
            put("name", name)
            put("icon", icon)
        }
    }

    /** Range une entité dans une pièce ; [areaId] vide la retire de toute pièce. */
    fun setArea(entityId: String, areaId: String) {
        command("config/entity_registry/update") {
            put("entity_id", entityId)
            if (areaId.isEmpty()) put("area_id", JSONObject.NULL) else put("area_id", areaId)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
