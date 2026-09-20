package fr.nzosifou.audio2ha

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** État courant du service, observable par l'UI. */
object MonitorStatus {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _audioPlaying = MutableStateFlow(false)
    val audioPlaying: StateFlow<Boolean> = _audioPlaying.asStateFlow()

    private val _lastSync = MutableStateFlow("Aucun envoi pour le moment")
    val lastSync: StateFlow<String> = _lastSync.asStateFlow()

    private val _configServer = MutableStateFlow<String?>(null)

    /** URL du serveur de configuration local, ou null s'il est arrêté. */
    val configServer: StateFlow<String?> = _configServer.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setAudioPlaying(value: Boolean) {
        _audioPlaying.value = value
    }

    fun setLastSync(value: String) {
        _lastSync.value = value
    }

    fun setConfigServer(value: String?) {
        _configServer.value = value
    }
}
