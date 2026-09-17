package com.example.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.data.model.AlarmTone
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.sin

class AlarmAudioEngine {
    private var audioJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var isPlaying = false

    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    companion object {
        private val activeEngines = CopyOnWriteArraySet<AlarmAudioEngine>()

        fun stopAll() {
            activeEngines.forEach { engine ->
                try {
                    engine.stopAlarm()
                } catch (_: Exception) {}
            }
        }
    }

    fun startAlarm(tone: AlarmTone) {
        if (isPlaying) return
        isPlaying = true
        activeEngines.add(this)

        audioJob = scope.launch {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 2)

            val audioTrack = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                isPlaying = false
                activeEngines.remove(this@AlarmAudioEngine)
                return@launch
            }

            currentAudioTrack = audioTrack

            try {
                audioTrack.play()
                val startTime = System.currentTimeMillis()
                val rampDurationMs = 3000.0 // 3 seconds volume ramp

                var phase = 0.0
                val shortBuffer = ShortArray(1024)

                while (isActive && isPlaying) {
                    val elapsed = (System.currentTimeMillis() - startTime).toDouble()
                    val volume = (elapsed / rampDurationMs).coerceIn(0.15, 1.0)

                    val cycleSeconds = (System.currentTimeMillis() % 1500) / 1000.0

                    // Fill buffer according to chosen tone
                    for (i in shortBuffer.indices) {
                        val currentFreq = when (tone) {
                            AlarmTone.RADAR_BEEP -> {
                                if (cycleSeconds < 0.25 || (cycleSeconds in 0.4..0.65)) 980.0 else 0.0
                            }
                            AlarmTone.GENTLE_BELLS -> {
                                if (cycleSeconds < 0.6) 660.0 else 0.0
                            }
                            AlarmTone.SIREN -> {
                                700.0 + 400.0 * (0.5 + 0.5 * sin(2.0 * Math.PI * cycleSeconds * 1.5))
                            }
                            AlarmTone.INTUITION -> {
                                if (cycleSeconds < 0.75) 852.0 else 0.0
                            }
                            else -> {
                                if (cycleSeconds < 0.75) tone.frequencyHz.toDouble() else 0.0
                            }
                        }

                        if (currentFreq > 0) {
                            val sample = sin(phase) * Short.MAX_VALUE * volume * 0.75
                            shortBuffer[i] = sample.toInt().toShort()
                            val phaseIncrement = (2.0 * Math.PI * currentFreq) / sampleRate
                            phase += phaseIncrement
                            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                        } else {
                            shortBuffer[i] = 0
                        }
                    }

                    if (!isActive || !isPlaying) break
                    val written = audioTrack.write(shortBuffer, 0, shortBuffer.size)
                    if (written < 0) break
                }
            } catch (_: Exception) {
            } finally {
                try {
                    audioTrack.pause()
                    audioTrack.flush()
                    audioTrack.stop()
                    audioTrack.release()
                } catch (_: Exception) {}
                if (currentAudioTrack === audioTrack) {
                    currentAudioTrack = null
                }
                isPlaying = false
                activeEngines.remove(this@AlarmAudioEngine)
            }
        }
    }

    fun stopAlarm() {
        isPlaying = false
        val job = audioJob
        audioJob = null
        job?.cancel()

        val track = currentAudioTrack
        currentAudioTrack = null
        try {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        } catch (_: Exception) {}
        activeEngines.remove(this)
    }

    fun isAlarmActive(): Boolean = isPlaying
}
