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
import lab.recovery.map.PoiType
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
    val poi: PoiType? = null,
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
        if ((candidate.text.isNotEmpty() && candidate.text in seen) || occupied.any(bounds::overlaps)) continue
        if (candidate.text.isNotEmpty()) seen += candidate.text
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
    val worldBounds = projection.visibleBounds(0.0)
    for (poi in data.visiblePois(worldBounds)) {
        val strong = poi.type in setOf(PoiType.STATION, PoiType.SCHOOL, PoiType.PARK)
        if (detail == Detail.OVERVIEW && poi.type != PoiType.STATION) continue
        if (detail == Detail.MID && !strong) continue
        val name = poi.name.orEmpty()
        val (width, height) = measure(name, false)
        val point = projection.offset(poi.position)
        val rank = when (poi.type) {
            PoiType.STATION -> 1
            PoiType.SCHOOL, PoiType.PARK -> 3
            PoiType.SPORT -> 5
            PoiType.PARKING, PoiType.FUEL -> 6
        }
        val rowWidth = if (name.isEmpty()) 20.dp.toPx() else width + 18.dp.toPx()
        val rowAnchor = if (name.isEmpty()) point else point + Offset(rowWidth / 2 - 10.dp.toPx(), 0f)
        candidates += LabelCandidate(name, rowAnchor, 0f, rowWidth, maxOf(height, 20.dp.toPx()), rank, false, poi.type)
    }
    for (label in data.labels) {
        val (width, height) = measure(label.text, important = true)
        candidates += LabelCandidate(label.text, projection.offset(label.position), 0f, width, height, 2, true)
    }
    for (layer in data.visibleRoads(worldBounds)) for (road in layer) {
        val visual = road.visual
        val text = visual.name ?: continue
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
    val limit = when (detail) { Detail.DETAIL -> 11; Detail.MID -> 7; Detail.OVERVIEW -> 4 }
    for (candidate in arrangeLabels(candidates, screen, blocked, limit)) {
        measure(candidate.text, candidate.important)
        paint.textAlign = Paint.Align.CENTER
        val baseline = -(paint.ascent() + paint.descent()) / 2
        drawIntoCanvas { wrapper ->
            val canvas = wrapper.nativeCanvas
            val saved = canvas.save()
            canvas.translate(candidate.anchor.x, candidate.anchor.y)
            canvas.rotate(candidate.angle)
            val poi = candidate.poi
            var textX = 0f
            if (poi != null) {
                val radius = 8.dp.toPx()
                val iconX = if (candidate.text.isEmpty()) 0f else -candidate.width / 2 + 10.dp.toPx()
                val icon = when (poi) {
                    PoiType.STATION -> "역"
                    PoiType.SCHOOL -> "학"
                    PoiType.PARK -> "공"
                    PoiType.SPORT -> "운"
                    PoiType.PARKING -> "P"
                    PoiType.FUEL -> "주"
                }
                paint.style = Paint.Style.FILL
                paint.color = style.chrome.toArgb()
                canvas.drawCircle(iconX, 0f, radius, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.dp.toPx()
                paint.color = style.muted.toArgb()
                canvas.drawCircle(iconX, 0f, radius, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = 9.sp.toPx()
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.color = style.text.toArgb()
                canvas.drawText(icon, iconX, -(paint.ascent() + paint.descent()) / 2, paint)
                measure(candidate.text, candidate.important)
                textX = 9.dp.toPx()
            }
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 2.dp.toPx()
            paint.color = style.labelHalo.toArgb()
            canvas.drawText(candidate.text, textX, baseline, paint)
            paint.style = Paint.Style.FILL
            paint.color = (if (candidate.important) style.text else style.muted).toArgb()
            canvas.drawText(candidate.text, textX, baseline, paint)
            canvas.restoreToCount(saved)
        }
    }
}
