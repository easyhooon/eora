package com.easyhooon.eora.internal.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import java.io.OutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Burns annotations into a copy of the capture and writes it as PNG. Redactions are drawn last so no annotation
 * can end up on top of a hidden area, and PNG re-encoding carries no EXIF or other source metadata.
 */
internal object AnnotationRenderer {
    const val HIGHLIGHTER_COLOR = 0x66FFD600

    /** Created on first use so code paths without Ink strokes never load Ink's native library. */
    val strokeRenderer: CanvasStrokeRenderer by lazy { CanvasStrokeRenderer.create() }

    /**
     * Draws an Ink stroke stored in image coordinates. Highlighters use a translucent color instead of a multiply
     * layer: PorterDuff MULTIPLY also multiplies the layer's transparent pixels and would erase the whole capture.
     */
    fun drawInk(canvas: Canvas, ink: Annotation.InkStroke, imageToCanvas: Matrix) {
        // The renderer only uses the matrix to pick rendering quality; the canvas itself must carry the transform.
        val saved = canvas.save()
        canvas.concat(imageToCanvas)
        strokeRenderer.draw(canvas, ink.stroke, imageToCanvas)
        canvas.restoreToCount(saved)
    }

    fun render(source: Bitmap, annotations: List<Annotation>): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val unit = max(2f, min(out.width, out.height) / 180f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit * 1.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        annotations.filterNot { it is Annotation.Redaction }.forEach { drawAnnotation(canvas, it, stroke, unit) }
        val opaque = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        annotations.filterIsInstance<Annotation.Redaction>().forEach { canvas.drawRect(it.bounds.toRectF(), opaque) }
        return out
    }

    fun writePng(bitmap: Bitmap, out: OutputStream) {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encoding failed" }
    }

    private fun drawAnnotation(canvas: Canvas, annotation: Annotation, stroke: Paint, unit: Float) {
        when (annotation) {
            is Annotation.Circle -> canvas.drawOval(annotation.bounds.toRectF(), stroke.apply { color = annotation.color })
            is Annotation.Arrow -> drawArrow(canvas, annotation, stroke.apply { color = annotation.color }, unit)
            is Annotation.Text -> drawText(canvas, annotation, unit)
            is Annotation.InkStroke -> drawInk(canvas, annotation, Matrix())
            is Annotation.Redaction -> Unit
        }
    }

    private fun drawArrow(canvas: Canvas, arrow: Annotation.Arrow, stroke: Paint, unit: Float) {
        canvas.drawLine(arrow.from.x, arrow.from.y, arrow.to.x, arrow.to.y, stroke)
        val angle = atan2(arrow.to.y - arrow.from.y, arrow.to.x - arrow.from.x)
        val head = unit * 7f
        val spread = Math.toRadians(28.0).toFloat()
        val path = Path().apply {
            moveTo(arrow.to.x, arrow.to.y)
            lineTo(arrow.to.x - head * cos(angle - spread), arrow.to.y - head * sin(angle - spread))
            moveTo(arrow.to.x, arrow.to.y)
            lineTo(arrow.to.x - head * cos(angle + spread), arrow.to.y - head * sin(angle + spread))
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawText(canvas: Canvas, text: Annotation.Text, unit: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text.color
            textSize = unit * 9f
            typeface = Typeface.DEFAULT_BOLD
        }
        val background = Paint().apply { color = 0xCCFFFFFF.toInt() }
        val width = paint.measureText(text.text)
        val metrics = paint.fontMetrics
        canvas.drawRect(
            text.anchor.x - unit,
            text.anchor.y + metrics.ascent - unit,
            text.anchor.x + width + unit,
            text.anchor.y + metrics.descent + unit,
            background,
        )
        canvas.drawText(text.text, text.anchor.x, text.anchor.y, paint)
    }

    private fun ImageRect.toRectF() = RectF(left, top, right, bottom)
}
