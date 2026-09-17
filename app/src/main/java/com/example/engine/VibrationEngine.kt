package com.example.engine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArraySet

class VibrationEngine(private val context: Context) {
    private var vibratorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var isVibrating = false

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val vibratorManager: VibratorManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        } else null
    }

    companion object {
        private val activeEngines = CopyOnWriteArraySet<VibrationEngine>()

        fun stopAll(context: Context) {
            activeEngines.forEach { engine ->
                try {
                    engine.stopVibration()
                } catch (_: Exception) {}
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    manager?.cancel()
                    manager?.defaultVibrator?.cancel()
                }
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.cancel()
            } catch (_: Exception) {}
        }
    }

    fun startArrivalVibration() {
        if (isVibrating) return
        isVibrating = true
        activeEngines.add(this)

        vibratorJob = scope.launch {
            val timings = longArrayOf(0, 400, 150, 400, 150, 800)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)

            while (isActive && isVibrating) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                        vibrator?.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(timings, -1)
                    }
                } catch (_: Exception) {}

                // Pattern duration: 400 + 150 + 400 + 150 + 800 = 1900ms.
                // Rest ~1100ms so total cycle is ~3000ms (repeat roughly every three seconds)
                delay(3000)
            }
        }
    }

    fun stopVibration() {
        isVibrating = false
        val job = vibratorJob
        vibratorJob = null
        job?.cancel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibratorManager?.cancel()
            }
            vibrator?.cancel()
        } catch (_: Exception) {}
        activeEngines.remove(this)
    }
}
