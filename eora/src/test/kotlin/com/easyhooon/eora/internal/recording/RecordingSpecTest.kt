package com.easyhooon.eora.internal.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSpecTest {
    @Test
    fun `a tall phone screen is scaled to a 720 short side, aligned to 16`() {
        val (w, h) = RecordingSpec.videoSize(1080, 2340)
        assertEquals(720, w)
        assertEquals(1568, h) // 2340 * 720 / 1080 = 1560, rounded to the nearest multiple of 16
        assertTrue(w % 16 == 0 && h % 16 == 0)
    }

    @Test
    fun `landscape keeps orientation`() {
        val (w, h) = RecordingSpec.videoSize(2340, 1080)
        assertEquals(720, h)
        assertTrue(w > h)
    }

    @Test
    fun `small screens are not upscaled`() {
        assertEquals(480 to 800, RecordingSpec.videoSize(480, 800))
    }

    @Test
    fun `the worst case fits the attachment limit`() {
        val bytes = RecordingSpec.BIT_RATE.toLong() / 8 * (RecordingSpec.MAX_DURATION_MS / 1000)
        assertTrue(bytes < 50L * 1024 * 1024)
    }
}
