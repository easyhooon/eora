package com.easyhooon.eora.internal.shake

import com.easyhooon.eora.ShakeConfig
import kotlin.math.sqrt

private const val STANDARD_GRAVITY = 9.80665f

/**
 * Detects a shake from raw accelerometer samples. A sample is a peak when the acceleration magnitude minus gravity
 * reaches the threshold; a shake is [ShakeConfig.requiredPeaks] peaks inside [ShakeConfig.windowMs].
 *
 * Consecutive over-threshold samples count as one peak, so a single long jolt (dropping the phone on a desk) does
 * not count as a shake. Pure Kotlin so it can be tested without sensors.
 */
internal class ShakeAnalyzer(private val config: ShakeConfig) {
    private val thresholdMs2 = config.thresholdG * STANDARD_GRAVITY
    private val peakTimes = ArrayDeque<Long>()
    private var aboveThreshold = false
    private var lastSampleMs = Long.MIN_VALUE
    private var lastShakeMs = Long.MIN_VALUE

    /** Returns true exactly once per detected shake. */
    fun onSample(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        if (lastSampleMs != Long.MIN_VALUE && nowMs - lastSampleMs < config.minSampleIntervalMs) return false
        lastSampleMs = nowMs

        val linear = sqrt(x * x + y * y + z * z) - STANDARD_GRAVITY
        val isAbove = linear >= thresholdMs2
        val isNewPeak = isAbove && !aboveThreshold
        aboveThreshold = isAbove

        if (lastShakeMs != Long.MIN_VALUE && nowMs - lastShakeMs < config.cooldownMs) return false
        if (!isNewPeak) return false

        while (peakTimes.isNotEmpty() && nowMs - peakTimes.first() > config.windowMs) peakTimes.removeFirst()
        peakTimes.addLast(nowMs)
        if (peakTimes.size < config.requiredPeaks) return false

        lastShakeMs = nowMs
        peakTimes.clear()
        return true
    }

    /** Forgets everything except the cooldown, e.g. when the screen goes to the background and back. */
    fun reset() {
        peakTimes.clear()
        aboveThreshold = false
        lastSampleMs = Long.MIN_VALUE
    }
}
