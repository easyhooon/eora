package com.easyhooon.eora.internal.recording

import kotlin.math.roundToInt

/**
 * Fixed recording settings: 60 s at ~2 Mbps is about 15 MB, well under the 50 MB attachment limit, and no audio,
 * so no microphone permission and no recorded voices.
 */
internal object RecordingSpec {
    const val MAX_DURATION_MS = 60_000
    const val BIT_RATE = 2_000_000
    const val FRAME_RATE = 30
    private const val MAX_SHORT_SIDE = 720
    private const val ALIGN = 16

    /**
     * Scales the screen down so its short side is at most [MAX_SHORT_SIDE], keeping the aspect ratio, and rounds
     * both sides to a multiple of 16, which every H.264 encoder accepts.
     */
    fun videoSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        require(screenWidth > 0 && screenHeight > 0) { "Screen size must be positive" }
        val scale = minOf(1f, MAX_SHORT_SIDE.toFloat() / minOf(screenWidth, screenHeight))
        fun align(v: Float) = maxOf(ALIGN, (v / ALIGN).roundToInt() * ALIGN)
        return align(screenWidth * scale) to align(screenHeight * scale)
    }
}
