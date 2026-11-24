package lab.recovery.uimap

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadStructure
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class LabelCandidate(
    val text: String,
    val anchor: Offset,
    val angle: Float,
    val width: Float,
    val height: Float,
    val rank: Int,
    val important: Boolean,
) {
    val bounds: Rect
        get() {
            val angle = Math.toRadians(this.angle.toDouble())
            val x = (abs(cos(angle)) * width + abs(sin(angle)) * height).toFloat() / 2
            val y = (abs(sin(angle)) * width + abs(cos(angle)) * height).toFloat() / 2
            return Rect(anchor.x - x, anchor.y - y, anchor.x + x, anchor.y + y)
        }
}

internal fun arrangeLabels(
    labels: List<LabelCandidate>,
    viewport: Rect,
    obstacles: List<Rect>,
    limit: Int,
): List<LabelCandidate> {
    val occupied = obstacles.toMutableList()
    val seen = mutableSetOf<String>()
    val result = mutableListOf<LabelCandidate>()
    val center = viewport.center
    for (candidate in labels.sortedWith(compareBy<LabelCandidate> { it.rank }
        .thenBy { (it.anchor - center).getDistanceSquared() })) {
        val bounds = candidate.bounds
        if (bounds.left < viewport.left || bounds.top < viewport.top ||
            bounds.right > viewport.right || bounds.bottom > viewport.bottom) continue
        if (candidate.text in seen || occupied.any(bounds::overlaps)) continue
        seen += candidate.text
        occupied += bounds
        result += candidate
        if (result.size == limit) break
    }
    return result
}

internal fun uprightAngle(delta: Offset): Float {
    val angle = Math.toDegrees(atan2(delta.y.toDouble(), delta.x.toDouble())).toFloat()
    return when {
        angle > 90f -> angle - 180f
        angle < -90f -> angle + 180f
        else -> angle
    }
}

/** Clip a candidate label segment so its available length reflects only the visible road. */
internal fun clipLabelSegment(a: Offset, b: Offset, bounds: Rect): Pair<Offset, Offset>? {
    val delta = b - a
    var start = 0f
    var end = 1f
    fun clip(p: Float, q: Float): Boolean {
        if (p == 0f) return q >= 0f
        val t = q / p
        if (p < 0f) start = maxOf(start, t) else end = minOf(end, t)
        return start <= end
    }
    if (!clip(-delta.x, a.x - bounds.left) || !clip(delta.x, bounds.right - a.x) ||
        !clip(-delta.y, a.y - bounds.top) || !clip(delta.y, bounds.bottom - a.y)) return null
    return a + delta * start to a + delta * end
}

internal fun DrawScope.drawMapLabels(
    data: MapGeometry,
    projection: Projection,
    detail: Detail,
    style: MapStyle,
    overlay: MapOverlay,
    chrome: List<Rect>,
    paint: Paint,
) {
    val screen = Rect(6.dp.toPx(), 6.dp.toPx(), size.width - 6.dp.toPx(), size.height - 6.dp.toPx())
    if (screen.width <= 0 || screen.height <= 0) return
    val blocked = chrome + listOfNotNull(overlay.vehicle?.point, overlay.lastKnown, overlay.raw, overlay.truth, data.start, data.end)
        .map { point ->
            val at = projection.offset(point)
            val r = 17.dp.toPx()
            Rect(at.x-r, at.y-r, at.x+r, at.y+r)
        }
    val candidates = mutableListOf<LabelCandidate>()
    fun measure(text: String, important: Boolean): Pair<Float, Float> {
        paint.typeface = if (important) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textSize = (if (important) 11.sp else 10.sp).toPx()
        return (paint.measureText(text) + 10.dp.toPx()) to (paint.textSize + 6.dp.toPx())
    }
    for (label in data.labels) {
        val (width, height) = measure(label.text, important = true)
        candidates += LabelCandidate(label.text, projection.offset(label.position), 0f, width, height, 2, true)
    }
    val worldBounds = projection.visibleBounds(0.0)
    for (layer in data.roadLayers) for (road in layer) {
        val visual = road.visual
        val text = visual.name ?: continue
        if (!road.geometry.bounds.intersects(worldBounds)) continue
        val important = visual.structure != RoadStructure.SURFACE
        if (detail == Detail.OVERVIEW && !important && visual.roadClass != RoadClass.EXPRESSWAY) continue
        if (detail == Detail.MID && !important && visual.roadClass == RoadClass.LOCAL) continue
        val (width, height) = measure(text, important)
        val priority = if (important) 0 else if (visual.roadClass == RoadClass.EXPRESSWAY) 1 else if (road.planned) 3 else 4
        for ((a, b) in road.geometry.points.zipWithNext()) {
            val (start, end) = clipLabelSegment(projection.offset(a), projection.offset(b), screen) ?: continue
            val delta = end - start
            if (delta.getDistance() < width) continue
            val middle = start + delta * .5f
            val angle = uprightAngle(delta)
            val radians = Math.toRadians(angle.toDouble())
            val clearance = (if (detail == Detail.DETAIL) 16.dp else 9.dp).toPx()
            val normal = Offset(sin(radians).toFloat(), -cos(radians).toFloat()) * clearance
            candidates += LabelCandidate(text, middle + normal, angle, width, height, priority, important)
            candidates += LabelCandidate(text, middle - normal, angle, width, height, priority, important)
        }
    }
    val limit = when (detail) { Detail.DETAIL -> 6; Detail.MID -> 5; Detail.OVERVIEW -> 4 }
    for (candidate in arrangeLabels(candidates, screen, blocked, limit)) {
        measure(candidate.text, candidate.important)
        paint.textAlign = Paint.Align.CENTER
        val baseline = -(paint.ascent() + paint.descent()) / 2
        drawIntoCanvas { wrapper ->
            val canvas = wrapper.nativeCanvas
            val saved = canvas.save()
            canvas.translate(candidate.anchor.x, candidate.anchor.y)
            canvas.rotate(candidate.angle)
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 2.dp.toPx()
            paint.color = style.labelHalo.toArgb()
            canvas.drawText(candidate.text, 0f, baseline, paint)
            paint.style = Paint.Style.FILL
            paint.color = (if (candidate.important) style.text else style.muted).toArgb()
            canvas.drawText(candidate.text, 0f, baseline, paint)
            canvas.restoreToCount(saved)
        }
    }
}
