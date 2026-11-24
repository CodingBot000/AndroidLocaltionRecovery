package lab.recovery.uimap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import lab.recovery.map.LandUse
import lab.recovery.map.Point2D
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadStructure
import kotlin.math.hypot

internal fun Projection.offset(point: Point2D): Offset = screen(point).let { Offset(it.x.toFloat(), it.y.toFloat()) }

internal class MapStrokeCache {
    private var pixelsPerMeter = 0f
    private var density = 0f
    private val strokes = mutableMapOf<Pair<Float, PathEffect?>, Stroke>()
    private val dashes = mutableMapOf<Pair<Float, Float>, PathEffect>()

    fun update(scale: Float, density: Float) {
        if (scale == pixelsPerMeter && density == this.density) return
        pixelsPerMeter = scale
        this.density = density
        strokes.clear()
        dashes.clear()
    }

    fun stroke(dp: Float, effect: PathEffect? = null): Stroke = strokes.getOrPut(dp to effect) {
        Stroke(dp * density / pixelsPerMeter, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
    }

    fun dash(on: Float, off: Float): PathEffect = dashes.getOrPut(on to off) {
        PathEffect.dashPathEffect(floatArrayOf(on * density / pixelsPerMeter, off * density / pixelsPerMeter))
    }
}

internal class OverlayPathCache {
    private var trails: List<MapTrail>? = null
    private var paths = emptyList<Pair<MapTrail, WorldPath>>()

    fun pathsFor(current: List<MapTrail>): List<Pair<MapTrail, WorldPath>> {
        if (trails === current) return paths
        val previous = paths
        paths = current.filter { it.points.size >= 2 }.mapIndexed { i, trail ->
            previous.getOrNull(i)?.takeIf { it.first == trail } ?: (trail to WorldPath(trail.points))
        }
        trails = current
        return paths
    }
}

internal fun DrawScope.drawMap(
    data: MapGeometry, projection: Projection, style: MapStyle, detail: Detail, cached: MapStrokeCache,
) {
    val scale = projection.scale.toFloat()
    val origin = projection.offset(Point2D(0.0, 0.0))
    val visible = projection.visibleBounds(19.dp.toPx().toDouble())
    val roadFactor = when (detail) {
        Detail.DETAIL -> 1f
        Detail.MID -> .78f
        Detail.OVERVIEW -> .48f
    }
    cached.update(scale, density)
    fun stroke(dp: Float, effect: PathEffect? = null) = cached.stroke(dp, effect)
    fun dash(a: Float, b: Float) = cached.dash(a, b)
    val walkwayDash = if (detail == Detail.OVERVIEW) null else dash(1.3f, 4f)
    val tunnelDash = dash(5f, 4f)
    val highwayDash = if (detail == Detail.DETAIL) dash(5f, 6f) else null
    val routeWidth = when (detail) {
        Detail.DETAIL -> 7f
        Detail.MID -> 5.6f
        Detail.OVERVIEW -> 4f
    }
    withTransform({
        translate(origin.x, origin.y)
        scale(scale, scale, Offset.Zero)
    }) {
        for ((geometry, type) in data.world.visibleLand(visible)) {
            if (type == LandUse.BUILDING && detail != Detail.DETAIL) continue
            val paint = style.land(type)
            val shape = data.path(geometry, detail)
            drawPath(shape, paint.fill)
            paint.edge?.let { drawPath(shape, it, style = stroke(if (type == LandUse.BUILDING) .55f else 1.1f)) }
        }
        if (walkwayDash != null) {
            for (walk in data.world.visibleWalks(visible)) {
                drawPath(data.path(walk, detail), style.muted.copy(alpha = .5f), style = stroke(1.3f, walkwayDash))
            }
        }
        val railDash = dash(5f, 3f)
        for (rail in data.world.visibleRails(visible)) {
            val path = data.path(rail, detail)
            drawPath(path, style.labelHalo, style = stroke(if (detail == Detail.OVERVIEW) 2f else 3f))
            drawPath(path, style.muted.copy(alpha = .85f), style = stroke(1.4f, railDash))
        }
        for (layer in data.world.visibleRoads(visible)) {
            val roads = layer.filter { road ->
                roadVisible(road.visual.roadClass, road.visual.structure, detail, data.isPlanned(road), projection.visibleMeters)
            }
            for (road in roads) {
                val (geometry, visual) = road
                val paint = style.road(visual.roadClass, visual.structure)
                val edgeWidth = if (visual.structure == RoadStructure.BRIDGE || visual.structure == RoadStructure.ELEVATED) 2f else 0f
                drawPath(if (data.isPlanned(road)) geometry.shape else data.path(geometry, detail), paint.casing, style = stroke((paint.casingDp + edgeWidth) * roadFactor))
            }
            for (road in roads) {
                val (geometry, visual) = road
                val paint = style.road(visual.roadClass, visual.structure)
                val shape = if (data.isPlanned(road)) geometry.shape else data.path(geometry, detail)
                drawPath(shape, paint.fill, style = stroke(paint.fillDp * roadFactor))
                when {
                    visual.structure == RoadStructure.TUNNEL ->
                        drawPath(shape, style.tunnel.casing, style = stroke(1.2f, tunnelDash))
                    visual.roadClass == RoadClass.EXPRESSWAY && highwayDash != null ->
                        drawPath(if (data.isPlanned(road)) geometry.shape else data.path(geometry, detail), paint.casing.copy(alpha = .65f), style = stroke(1f, highwayDash))
                }
            }
        }
        data.route?.let {
            drawPath(it.shape, style.routeHalo, style = stroke(routeWidth + 3.5f))
            drawPath(it.shape, style.route, style = stroke(routeWidth))
        }
        for (road in data.routeTunnels) if (road.bounds.intersects(visible)) {
            drawPath(road.shape, style.routeHalo.copy(alpha = .88f), style = stroke(1.4f, tunnelDash))
        }
    }
    data.route?.let {
        val start = projection.offset(it.points.first())
        val end = projection.offset(it.points.last())
        drawCircle(style.routeHalo, 6.dp.toPx(), start)
        drawCircle(style.route, 3.dp.toPx(), start)
        val r = 4.dp.toPx()
        val halo = r + 1.6.dp.toPx()
        drawRoundRect(style.routeHalo, end - Offset(halo, halo), Size(halo * 2, halo * 2))
        drawRect(style.route, end - Offset(r, r), Size(r * 2, r * 2))
    }

    for (arrow in data.arrows) {
        val point = projection.offset(arrow.point)
        val arm = 2.7.dp.toPx()
        rotate(arrow.heading.toFloat(), point) {
            val nose = point - Offset(0f, arm)
            drawLine(style.routeHalo, point + Offset(-arm, arm), nose, 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(style.routeHalo, point + Offset(arm, arm), nose, 1.5.dp.toPx(), StrokeCap.Round)
        }
    }
    if (detail == Detail.DETAIL) for (road in data.routeTunnels) {
        val points = road.points
        drawPortal(projection.offset(points.first()), projection.offset(points[1]), style)
        drawPortal(projection.offset(points.last()), projection.offset(points[points.lastIndex - 1]), style)
    }
}

private fun DrawScope.drawPortal(at: Offset, toward: Offset, style: MapStyle) {
    val delta = toward - at
    val scale = 8.dp.toPx() / hypot(delta.x, delta.y).coerceAtLeast(1f)
    val cross = Offset(-delta.y * scale, delta.x * scale)
    drawLine(style.tunnel.casing, at - cross, at + cross, 2.dp.toPx(), StrokeCap.Round)
}

internal fun DrawScope.drawOverlay(
    overlay: MapOverlay, projection: Projection, style: MapStyle,
    paths: OverlayPathCache, strokes: MapStrokeCache,
) {
    if (overlay.trails.isNotEmpty()) {
        val scale = projection.scale.toFloat()
        val origin = projection.offset(Point2D(0.0, 0.0))
        val visible = projection.visibleBounds(4.dp.toPx().toDouble())
        strokes.update(scale, density)
        val dash = strokes.dash(7f, 5f)
        withTransform({
            translate(origin.x, origin.y)
            scale(scale, scale, Offset.Zero)
        }) {
            for ((trail, path) in paths.pathsFor(overlay.trails)) {
                if (!path.bounds.intersects(visible)) continue
                drawPath(path.shape, if (trail.estimated) style.estimate else style.driven,
                    style = strokes.stroke(if (trail.estimated) 3.2f else 2.8f, if (trail.estimated) dash else null))
            }
        }
    }
    val raw = overlay.raw?.let(projection::offset)
    val rawMarker = raw?.let { at ->
        val padding = 8.dp.toPx().coerceAtMost(size.minDimension / 2)
        Offset(at.x.coerceIn(padding, size.width - padding), at.y.coerceIn(padding, size.height - padding))
    }
    raw?.let { at ->
        overlay.accuracyMeters?.let { meters ->
            val radius = (meters * projection.scale).toFloat()
            if (at == rawMarker && radius.isFinite() && radius >= 1f) {
                drawCircle(style.raw.copy(alpha = .12f), radius.coerceAtMost(size.minDimension * .25f), at)
            }
        }
    }

    val vehicle = overlay.vehicle
    if (vehicle?.kind == MarkerKind.FIX) {
        drawVehicle(projection.offset(vehicle.point), vehicle.heading, style)
    }

    // Keep diagnostic symbols above the direction glyph, including when several fixes coincide.
    if (vehicle?.kind == MarkerKind.LAST_KNOWN) {
        drawLastKnown(projection.offset(vehicle.point), style, current = true)
    } else {
        overlay.lastKnown?.takeUnless { vehicle?.kind == MarkerKind.FIX && it == vehicle.point }?.let {
            drawLastKnown(projection.offset(it), style, current = false)
        }
    }
    overlay.truth?.let {
        val at = projection.offset(it)
        val radius = 7.dp.toPx()
        val corner = at - Offset(radius, radius)
        val dimensions = Size(radius * 2, radius * 2)
        drawRect(style.labelHalo, corner, dimensions, style = Stroke(4.dp.toPx()))
        drawRect(style.truth, corner, dimensions, style = Stroke(2.dp.toPx()))
    }
    rawMarker?.let { drawRawCross(it, style) }
    vehicle?.let { marker ->
        val at = projection.offset(marker.point)
        when (marker.kind) {
            MarkerKind.FIX -> {
                drawCircle(style.labelHalo, 3.dp.toPx(), at)
                drawCircle(style.driven, 1.9.dp.toPx(), at)
            }
            MarkerKind.ESTIMATE -> drawEstimate(at, style)
            MarkerKind.LAST_KNOWN -> Unit
        }
    }
}

private fun DrawScope.drawRawCross(at: Offset, style: MapStyle) {
    val radius = 5.dp.toPx()
    val descending = Offset(radius, radius)
    val ascending = Offset(radius, -radius)
    fun stroke(color: Color, width: Float) {
        drawLine(color, at - descending, at + descending, width, StrokeCap.Round)
        drawLine(color, at - ascending, at + ascending, width, StrokeCap.Round)
    }
    stroke(style.labelHalo, 4.dp.toPx())
    stroke(style.raw, 2.dp.toPx())
}

private fun DrawScope.drawLastKnown(at: Offset, style: MapStyle, current: Boolean) {
    val radius = if (current) 11.dp.toPx() else 6.dp.toPx()
    drawCircle(style.labelHalo, radius, at, style = Stroke(4.dp.toPx()))
    drawCircle(style.muted, radius, at, style = Stroke(2.dp.toPx()))
    if (current) {
        val dash = Offset(3.dp.toPx(), 0f)
        drawLine(style.labelHalo, at - dash, at + dash, 4.dp.toPx(), StrokeCap.Round)
        drawLine(style.muted, at - dash, at + dash, 2.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.drawEstimate(at: Offset, style: MapStyle) {
    val r = 12.dp.toPx()
    val diamond = Path().apply {
        moveTo(at.x, at.y - r)
        lineTo(at.x + r * .84f, at.y)
        lineTo(at.x, at.y + r)
        lineTo(at.x - r * .84f, at.y)
        close()
    }
    drawPath(diamond, style.labelHalo, style = Stroke(4.5.dp.toPx(), join = StrokeJoin.Round))
    drawPath(diamond, style.estimate, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
}

private fun DrawScope.drawVehicle(at: Offset, heading: Double?, style: MapStyle) {
    val r = 12.dp.toPx()
    if (heading == null) {
        drawCircle(style.routeHalo, r, at)
        drawCircle(style.route, r * .74f, at)
        return
    }
    rotate(heading.toFloat(), at) {
        val arrow = Path().apply {
            moveTo(at.x, at.y - r)
            lineTo(at.x + r * .76f, at.y + r * .62f)
            lineTo(at.x, at.y + r * .25f)
            lineTo(at.x - r * .76f, at.y + r * .62f)
            close()
        }
        drawPath(arrow, style.routeHalo, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
        drawPath(arrow, style.route)
    }
}
