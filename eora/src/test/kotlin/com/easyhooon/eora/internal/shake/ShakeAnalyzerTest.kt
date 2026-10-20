package com.easyhooon.eora.internal.shake

import com.easyhooon.eora.ShakeConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ShakeAnalyzerTest {
    private val config = ShakeConfig(thresholdG = 2f, requiredPeaks = 3, windowMs = 1_000, cooldownMs = 2_000, minSampleIntervalMs = 0)
    private val strong = 9.80665f * 4f // well over 2G after removing gravity
    private val rest = 9.80665f

    /** Alternates strong and resting samples so each strong one is a new peak. Returns detections. */
    private fun ShakeAnalyzer.shake(peaks: Int, startMs: Long, stepMs: Long = 100): Int {
        var detections = 0
        var t = startMs
        repeat(peaks) {
            if (onSample(strong, 0f, 0f, t)) detections++
            t += stepMs / 2
            if (onSample(0f, 0f, rest, t)) detections++
            t += stepMs / 2
        }
        return detections
    }

    @Test
    fun `detects once when enough peaks arrive inside the window`() {
        assertEquals(1, ShakeAnalyzer(config).shake(peaks = 3, startMs = 0))
    }

    @Test
    fun `a resting device never triggers`() {
        val analyzer = ShakeAnalyzer(config)
        val detections = (0 until 500).count { analyzer.onSample(0f, 0f, rest, it * 20L) }
        assertEquals(0, detections)
    }

    @Test
    fun `one long jolt counts as a single peak`() {
        val analyzer = ShakeAnalyzer(config)
        val detections = (0 until 20).count { analyzer.onSample(strong, 0f, 0f, it * 20L) }
        assertEquals(0, detections)
    }

    @Test
    fun `peaks spread wider than the window do not trigger`() {
        assertEquals(0, ShakeAnalyzer(config).shake(peaks = 6, startMs = 0, stepMs = 600))
    }

    @Test
    fun `continuous shaking is merged by the cooldown`() {
        // 12 peaks in 1.2 s: without cooldown that would be 4 detections.
        assertEquals(1, ShakeAnalyzer(config).shake(peaks = 12, startMs = 0, stepMs = 100))
    }

    @Test
    fun `shaking again after the cooldown triggers again`() {
        val analyzer = ShakeAnalyzer(config)
        assertEquals(1, analyzer.shake(peaks = 3, startMs = 0))
        assertEquals(1, analyzer.shake(peaks = 3, startMs = 5_000))
    }

    @Test
    fun `reset drops a half-finished shake`() {
        val analyzer = ShakeAnalyzer(config)
        assertEquals(0, analyzer.shake(peaks = 2, startMs = 0))
        analyzer.reset()
        assertEquals(0, analyzer.shake(peaks = 1, startMs = 250))
    }

    @Test
    fun `samples closer than the minimum interval are ignored`() {
        // Strong/rest every 10 ms: three peaks in 50 ms would trigger, but only t=0 and t=50 are processed.
        fun feed(analyzer: ShakeAnalyzer) = (0..5).count { i -> analyzer.onSample(if (i % 2 == 0) strong else 0f, 0f, rest, i * 10L) }
        assertEquals(1, feed(ShakeAnalyzer(config)))
        assertEquals(0, feed(ShakeAnalyzer(config.copy(minSampleIntervalMs = 50))))
    }
}
