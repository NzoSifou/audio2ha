package fr.nzosifou.audio2ha

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Joue un bip de quelques secondes avec l'usage MEDIA, afin de vérifier
 * la chaîne complète détection -> Home Assistant sans lancer d'application tierce.
 */
object TestTone {

    private const val SAMPLE_RATE = 44100
    private const val FREQUENCY = 440.0

    @Volatile
    private var playing = false

    fun play(durationSeconds: Int = 4) {
        if (playing) return
        playing = true
        Thread {
            var track: AudioTrack? = null
            try {
                val count = SAMPLE_RATE * durationSeconds
                val samples = ShortArray(count)
                for (i in 0 until count) {
                    val fade = when {
                        i < SAMPLE_RATE / 20 -> i.toDouble() / (SAMPLE_RATE / 20)
                        i > count - SAMPLE_RATE / 20 -> (count - i).toDouble() / (SAMPLE_RATE / 20)
                        else -> 1.0
                    }
                    samples[i] = (sin(2 * PI * i * FREQUENCY / SAMPLE_RATE) * 6000 * fade).toInt().toShort()
                }
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                LogStore.audio("Son de test lancé ($durationSeconds s)")
                track.play()
                Thread.sleep(durationSeconds * 1000L + 300)
            } catch (e: Exception) {
                LogStore.audio("Son de test impossible", e.toString(), error = true)
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                playing = false
            }
        }.start()
    }
}
