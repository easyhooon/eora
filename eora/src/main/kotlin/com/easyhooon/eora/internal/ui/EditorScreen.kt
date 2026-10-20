package com.easyhooon.eora.internal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.easyhooon.eora.R
import com.easyhooon.eora.internal.editor.ANNOTATION_COLORS
import com.easyhooon.eora.internal.editor.Annotation
import com.easyhooon.eora.internal.editor.AnnotationRenderer
import com.easyhooon.eora.internal.editor.DEFAULT_COLOR
import com.easyhooon.eora.internal.editor.ImagePoint
import com.easyhooon.eora.internal.editor.ImageRect
import com.easyhooon.eora.internal.editor.ImageTransform
import com.easyhooon.eora.internal.editor.isMeaningful
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class Tool(val label: Int, val isInk: Boolean = false) {
    PEN(R.string.eora_tool_pen, isInk = true),
    HIGHLIGHTER(R.string.eora_tool_highlighter, isInk = true),
    CIRCLE(R.string.eora_tool_circle),
    ARROW(R.string.eora_tool_arrow),
    TEXT(R.string.eora_tool_text),
    REDACT(R.string.eora_tool_hide),
    MOVE(R.string.eora_tool_move),
}

/**
 * On-screen preview only. What is sent is rendered by [AnnotationRenderer] from image coordinates, and the review
 * screen shows that rendered file, so a preview approximation can never differ from the uploaded bytes unnoticed.
 */
@Composable
internal fun EditorScreen(controller: ReportController, modifier: Modifier, onBack: () -> Unit, onCancel: () -> Unit, onNext: () -> Unit) {
    LaunchedEffect(Unit) { controller.loadOriginalIfNeeded() }
    val original by controller.original.collectAsState()
    val annotations by controller.annotations.collectAsState()
    val busy by controller.busy.collectAsState()
    val undoLabel = stringResource(R.string.eora_undo)
    var tool by rememberSaveable { mutableStateOf(Tool.PEN) }
    var color by rememberSaveable { mutableStateOf(DEFAULT_COLOR) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf<ImagePoint?>(null) }
    var dragEnd by remember { mutableStateOf<ImagePoint?>(null) }
    var pendingTextAt by remember { mutableStateOf<ImagePoint?>(null) }

    Column(modifier) {
        EoraHeader(stringResource(R.string.eora_step_mark), STEP_MARK, onBack) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.eora_discard)) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tool.entries.forEach { t -> FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(stringResource(t.label)) }) }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it },
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = original
            if (bitmap == null || viewSize == IntSize.Zero) {
                CircularProgressIndicator()
            } else {
                val transform = ImageTransform(bitmap.width, bitmap.height, viewSize.width.toFloat(), viewSize.height.toFloat(), zoom, pan.x, pan.y)
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                val strokeRenderer = remember { CanvasStrokeRenderer.create() }
                val gestures = when (tool) {
                    // Ink tools take input through InProgressStrokes below.
                    Tool.PEN, Tool.HIGHLIGHTER -> Modifier
                    Tool.MOVE -> Modifier.pointerInput(tool) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(ImageTransform.MIN_ZOOM, ImageTransform.MAX_ZOOM)
                            pan += panChange
                        }
                    }
                    Tool.TEXT -> Modifier.pointerInput(tool, transform) {
                        detectTapGestures { pendingTextAt = transform.toImage(it.x, it.y) }
                    }
                    else -> Modifier.pointerInput(tool, transform) {
                        detectDragGestures(
                            onDragStart = {
                                dragStart = transform.toImage(it.x, it.y)
                                dragEnd = dragStart
                            },
                            onDrag = { change, _ -> dragEnd = transform.toImage(change.position.x, change.position.y) },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragEnd
                                if (start != null && end != null) {
                                    shapeFor(tool, start, end, color)?.takeIf { it.isMeaningful() }?.let(controller::addAnnotation)
                                }
                                dragStart = null
                                dragEnd = null
                            },
                            onDragCancel = {
                                dragStart = null
                                dragEnd = null
                            },
                        )
                    }
                }
                Canvas(Modifier.fillMaxSize().then(gestures)) {
                    drawImage(
                        image,
                        dstOffset = IntOffset(transform.offsetX.roundToInt(), transform.offsetY.roundToInt()),
                        dstSize = IntSize((bitmap.width * transform.scale).roundToInt(), (bitmap.height * transform.scale).roundToInt()),
                    )
                    val inProgress = dragStart?.let { s -> dragEnd?.let { e -> shapeFor(tool, s, e, color) } }
                    (annotations + listOfNotNull(inProgress)).forEach { drawPreview(it, transform, strokeRenderer) }
                }
                if (tool.isInk) {
                    val brush = remember(tool, color, bitmap) { inkBrush(tool, color, bitmap.width, bitmap.height) }
                    // World space is the captured image, so finished strokes come out in image pixels and are
                    // composited into the final PNG without another transform.
                    // InProgressStrokes takes no Modifier; anchor it top-start so its origin matches the Canvas.
                    Box(Modifier.fillMaxSize()) {
                        InProgressStrokes(
                            defaultBrush = brush,
                            pointerEventToWorldTransform = transform.viewToImageComposeMatrix(),
                            onStrokesFinished = { strokes ->
                                strokes.forEach { controller.addAnnotation(Annotation.InkStroke(it)) }
                            },
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = controller::undo, enabled = annotations.isNotEmpty()) {
                Text("↶", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = undoLabel })
            }
            Box(Modifier.weight(1f))
            ColorPalette(selected = color, onSelect = { color = it })
        }
        Button(
            onClick = { controller.finishEditing(onNext) },
            enabled = original != null && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(52.dp),
        ) { Text(stringResource(R.string.eora_next)) }
    }

    pendingTextAt?.let { at ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingTextAt = null },
            title = { Text(stringResource(R.string.eora_add_text)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(MAX_LABEL_LENGTH) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val label = Annotation.Text(at, text.trim(), color)
                    if (label.isMeaningful()) controller.addAnnotation(label)
                    pendingTextAt = null
                }) { Text(stringResource(R.string.eora_add)) }
            },
            dismissButton = { TextButton(onClick = { pendingTextAt = null }) { Text(stringResource(R.string.eora_cancel)) } },
        )
    }
}

private const val MAX_LABEL_LENGTH = 80

private fun shapeFor(tool: Tool, start: ImagePoint, end: ImagePoint, color: Int): Annotation? = when (tool) {
    Tool.CIRCLE -> Annotation.Circle(ImageRect.spanning(start, end), color)
    Tool.ARROW -> Annotation.Arrow(start, end, color)
    Tool.REDACT -> Annotation.Redaction(ImageRect.spanning(start, end))
    Tool.TEXT, Tool.MOVE, Tool.PEN, Tool.HIGHLIGHTER -> null
}

/** Sizes are image pixels, so a stroke looks equally thick at any zoom and in the final file. */
private fun inkBrush(tool: Tool, color: Int, imageWidth: Int, imageHeight: Int): Brush {
    val unit = max(2f, min(imageWidth, imageHeight) / 180f)
    return if (tool == Tool.HIGHLIGHTER) {
        Brush.createWithComposeColor(StockBrushes.highlighter(), Color(AnnotationRenderer.HIGHLIGHTER_COLOR), size = unit * 10f, epsilon = 0.1f)
    } else {
        Brush.createWithComposeColor(StockBrushes.pressurePen(), Color(color), size = unit * 2f, epsilon = 0.1f)
    }
}

private fun DrawScope.drawPreview(annotation: Annotation, t: ImageTransform, strokeRenderer: CanvasStrokeRenderer) {
    val stroke = Stroke(width = 3.dp.toPx())
    fun rect(r: ImageRect) = Offset(t.toViewX(r.left), t.toViewY(r.top)) to Size(r.width * t.scale, r.height * t.scale)
    when (annotation) {
        is Annotation.InkStroke -> AnnotationRenderer.drawInk(drawContext.canvas.nativeCanvas, annotation, t.imageToViewMatrix(), strokeRenderer)
        is Annotation.Circle -> rect(annotation.bounds).let { (o, s) -> drawOval(Color(annotation.color), o, s, style = stroke) }
        is Annotation.Redaction -> rect(annotation.bounds).let { (o, s) -> drawRect(Color.Black, o, s) }
        is Annotation.Arrow -> drawLine(
            Color(annotation.color),
            Offset(t.toViewX(annotation.from.x), t.toViewY(annotation.from.y)),
            Offset(t.toViewX(annotation.to.x), t.toViewY(annotation.to.y)),
            strokeWidth = stroke.width,
        ).also { drawCircle(Color(annotation.color), stroke.width * 2, Offset(t.toViewX(annotation.to.x), t.toViewY(annotation.to.y))) }
        is Annotation.Text -> drawContext.canvas.nativeCanvas.drawText(
            annotation.text,
            t.toViewX(annotation.anchor.x),
            t.toViewY(annotation.anchor.y),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = annotation.color
                textSize = 16.dp.toPx()
                isFakeBoldText = true
            },
        )
    }
}

private val colorNames = listOf(R.string.eora_color_red, R.string.eora_color_orange, R.string.eora_color_green, R.string.eora_color_teal)

@Composable
private fun ColorPalette(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ANNOTATION_COLORS.forEachIndexed { index, value ->
            val name = stringResource(colorNames[index])
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(value))
                    .then(if (value == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .semantics { contentDescription = name },
            )
        }
    }
}
