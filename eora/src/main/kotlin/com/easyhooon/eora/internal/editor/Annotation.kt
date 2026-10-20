package com.easyhooon.eora.internal.editor

import androidx.ink.strokes.Stroke
import kotlin.math.max
import kotlin.math.min

/** A point in captured-image pixels. Annotations are stored this way so rotation and zoom never move them. */
internal data class ImagePoint(val x: Float, val y: Float)

internal data class ImageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        fun spanning(a: ImagePoint, b: ImagePoint) = ImageRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }
}

internal sealed interface Annotation {
    data class Circle(val bounds: ImageRect, val color: Int = DEFAULT_COLOR) : Annotation
    data class Arrow(val from: ImagePoint, val to: ImagePoint, val color: Int = DEFAULT_COLOR) : Annotation
    data class Text(val anchor: ImagePoint, val text: String, val color: Int = DEFAULT_COLOR) : Annotation

    /** A freehand Ink stroke whose coordinates are already image pixels (Ink's world space is the image). */
    class InkStroke(val stroke: Stroke) : Annotation

    /** Fully opaque. Blur is not used because blurred text can often be recovered. */
    data class Redaction(val bounds: ImageRect) : Annotation
}

/** Pen colors offered in the editor, same set as the reference QA tool: red, orange, green, teal. */
internal val ANNOTATION_COLORS = listOf(0xFFE53935.toInt(), 0xFFF5A623.toInt(), 0xFF2DB55D.toInt(), 0xFF1BB3B3.toInt())
internal val DEFAULT_COLOR = ANNOTATION_COLORS.first()

/** Shapes smaller than this many image pixels are treated as accidental taps and dropped. */
internal const val MIN_SHAPE_PX = 8f

internal fun Annotation.isMeaningful(): Boolean = when (this) {
    is Annotation.Circle -> bounds.width >= MIN_SHAPE_PX && bounds.height >= MIN_SHAPE_PX
    is Annotation.Redaction -> bounds.width >= MIN_SHAPE_PX && bounds.height >= MIN_SHAPE_PX
    is Annotation.Arrow -> max(kotlin.math.abs(to.x - from.x), kotlin.math.abs(to.y - from.y)) >= MIN_SHAPE_PX
    is Annotation.Text -> text.isNotBlank()
    is Annotation.InkStroke -> true
}

/**
 * Maps between view pixels and image pixels for an image fitted (centered, aspect kept) into the view, then
 * zoomed by [zoom] around the view center and moved by [panX]/[panY] view pixels.
 */
internal data class ImageTransform(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Float,
    val viewHeight: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    private val fitScale: Float = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val scale: Float get() = fitScale * zoom
    val offsetX: Float get() = (viewWidth - imageWidth * scale) / 2f + panX
    val offsetY: Float get() = (viewHeight - imageHeight * scale) / 2f + panY

    /** Clamped to the image so a drag that leaves the picture still ends on its edge. */
    fun toImage(viewX: Float, viewY: Float): ImagePoint = ImagePoint(
        ((viewX - offsetX) / scale).coerceIn(0f, imageWidth.toFloat()),
        ((viewY - offsetY) / scale).coerceIn(0f, imageHeight.toFloat()),
    )

    fun toViewX(imageX: Float): Float = imageX * scale + offsetX
    fun toViewY(imageY: Float): Float = imageY * scale + offsetY

    /** Image pixels to view pixels, for drawing finished strokes on screen. */
    fun imageToViewMatrix(): android.graphics.Matrix = android.graphics.Matrix().apply {
        setScale(scale, scale)
        postTranslate(offsetX, offsetY)
    }

    /** View pixels to image pixels as a Compose matrix, so Ink records strokes directly in image coordinates. */
    fun viewToImageComposeMatrix(): androidx.compose.ui.graphics.Matrix = androidx.compose.ui.graphics.Matrix().apply {
        // Column-major 4x4: x' = x * values[0] + values[12], y' = y * values[5] + values[13].
        values[0] = 1f / scale
        values[5] = 1f / scale
        values[12] = -offsetX / scale
        values[13] = -offsetY / scale
    }

    fun zoomedBy(factor: Float, panDx: Float, panDy: Float): ImageTransform =
        copy(zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM), panX = panX + panDx, panY = panY + panDy)

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
    }
}
