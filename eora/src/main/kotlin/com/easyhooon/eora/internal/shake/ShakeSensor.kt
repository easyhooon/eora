package com.easyhooon.eora.internal.shake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.easyhooon.eora.ShakeConfig

/** Subscribes to the accelerometer only between [start] and [stop]. Raw samples are never stored. */
internal class ShakeSensor(context: Context, private val config: ShakeConfig, private val onShake: () -> Unit) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val analyzer = ShakeAnalyzer(config)
    private var listening = false

    val isAvailable: Boolean get() = accelerometer != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (analyzer.onSample(event.values[0], event.values[1], event.values[2], SystemClock.elapsedRealtime())) onShake()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val sensor = accelerometer ?: return
        if (listening) return
        // A half-finished shake from before the pause must not complete after resume.
        analyzer.reset()
        // ~50 Hz. SENSOR_DELAY_UI (~15 Hz) missed the short peaks of a real shake on a Galaxy S25+.
        listening = sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME) == true
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(listener)
        listening = false
    }
}
