package com.example.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Device compass / orientation sensor provider.
 * Uses ROTATION_VECTOR (or ACCELEROMETER + MAGNETIC_FIELD fallback) to deliver
 * real-time azimuth heading (0..360 degrees) even when device is stationary.
 */
class CompassSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor: Sensor? = if (rotationVectorSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private val magneticSensor: Sensor? = if (rotationVectorSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null

    private val _headingFlow = MutableStateFlow<Float?>(null)
    val headingFlow: StateFlow<Float?> = _headingFlow.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastAccelerometer = FloatArray(3)
    private val lastMagnetometer = FloatArray(3)
    private var lastAccelerometerSet = false
    private var lastMagnetometerSet = false

    private var isListening = false
    private var lastEmittedHeading: Float? = null
    private var lastEmitTimeMs = 0L

    fun start() {
        if (isListening || sensorManager == null) return

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_NORMAL)
            isListening = true
        } else if (accelerometerSensor != null && magneticSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_NORMAL)
            isListening = true
        }
    }

    fun stop() {
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthRad = orientationAngles[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                if (azimuthDeg < 0f) azimuthDeg += 360f
                emitFilteredHeading(azimuthDeg)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.size)
                lastAccelerometerSet = true
                calculateHeadingFromAccMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagnetometer, 0, event.values.size)
                lastMagnetometerSet = true
                calculateHeadingFromAccMag()
            }
        }
    }

    private fun calculateHeadingFromAccMag() {
        if (lastAccelerometerSet && lastMagnetometerSet) {
            val success = SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer)
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthRad = orientationAngles[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                if (azimuthDeg < 0f) azimuthDeg += 360f
                emitFilteredHeading(azimuthDeg)
            }
        }
    }

    private fun emitFilteredHeading(newHeading: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastEmittedHeading
        if (prev == null) {
            lastEmittedHeading = newHeading
            lastEmitTimeMs = now
            _headingFlow.value = newHeading
            return
        }

        // Compute shortest angular delta to prevent jump across 0/360 boundary
        var delta = (newHeading - prev) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f

        // Filter out minor jitter (< 2.5 degrees) and throttle to ~10Hz (100ms) unless it's a large turn (>= 10 degrees)
        if (abs(delta) >= 2.5f && (now - lastEmitTimeMs >= 100L || abs(delta) >= 10f)) {
            // Low-pass exponential smoothing
            val smoothed = (prev + delta * 0.45f + 360f) % 360f
            lastEmittedHeading = smoothed
            lastEmitTimeMs = now
            _headingFlow.value = smoothed
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
