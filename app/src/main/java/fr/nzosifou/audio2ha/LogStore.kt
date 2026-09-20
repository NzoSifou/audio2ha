package fr.nzosifou.audio2ha

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Catégorie d'un log. */
enum class LogType(val label: String) {
    /** Changement d'état audio détecté localement sur la TV. */
    AUDIO("TV"),

    /** Envoi de l'état vers Home Assistant. */
    HA("Home Assistant"),
}

data class LogEntry(
    val time: Long,
    val type: LogType,
    val message: String,
    val detail: String? = null,
    val error: Boolean = false,
) {
    fun timeString(): String = TIME_FMT.format(Date(time))

    companion object {
        private val TIME_FMT = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
    }
}

/**
 * Journal en mémoire (exposé à l'UI) doublé d'une persistance simple sur disque
 * pour survivre au redémarrage de l'application.
 */
object LogStore {

    private const val TAG = "Audio2HA"
    private const val MAX_ENTRIES = 500
    private const val FILE_NAME = "logs.tsv"
    private const val SEP = ""

    private val io = Executors.newSingleThreadExecutor()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Les entrées les plus récentes en premier. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile
    private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, FILE_NAME)
        file = f
        io.execute {
            runCatching {
                if (!f.exists()) return@runCatching
                val loaded = f.readLines()
                    .takeLast(MAX_ENTRIES)
                    .mapNotNull { parse(it) }
                    .reversed()
                if (loaded.isNotEmpty()) {
                    _entries.value = (_entries.value + loaded).take(MAX_ENTRIES)
                }
            }
        }
    }

    fun audio(message: String, detail: String? = null, error: Boolean = false) =
        add(LogType.AUDIO, message, detail, error)

    fun ha(message: String, detail: String? = null, error: Boolean = false) =
        add(LogType.HA, message, detail, error)

    fun add(type: LogType, message: String, detail: String? = null, error: Boolean = false) {
        val entry = LogEntry(System.currentTimeMillis(), type, message, detail, error)
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        if (error) Log.w(TAG, "[" + type.label + "] " + message + " " + (detail ?: ""))
        else Log.i(TAG, "[" + type.label + "] " + message + " " + (detail ?: ""))
        val f = file ?: return
        io.execute {
            runCatching {
                f.appendText(serialize(entry) + "\n")
                if (f.length() > 512 * 1024) {
                    val keep = f.readLines().takeLast(MAX_ENTRIES)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        val f = file ?: return
        io.execute { runCatching { f.writeText("") } }
    }

    private fun serialize(e: LogEntry): String = listOf(
        e.time.toString(),
        e.type.name,
        if (e.error) "1" else "0",
        e.message.replace("\n", " "),
        (e.detail ?: "").replace("\n", " "),
    ).joinToString(SEP)

    private fun parse(line: String): LogEntry? {
        val parts = line.split(SEP)
        if (parts.size < 5) return null
        val time = parts[0].toLongOrNull() ?: return null
        val type = runCatching { LogType.valueOf(parts[1]) }.getOrNull() ?: return null
        return LogEntry(time, type, parts[3], parts[4].ifEmpty { null }, parts[2] == "1")
    }
}
