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

internal fun DrawScope.drawMap(data: MapGeometry, projection: Projection, style: MapStyle, detail: Detail) {
    val scale = projection.scale.toFloat()
    val origin = projection.offset(Point2D(0.0, 0.0))
    val visible = projection.visibleBounds(19.dp.toPx().toDouble())
    val roadFactor = when (detail) {
        Detail.DETAIL -> 1f
        Detail.MID -> .78f
        Detail.OVERVIEW -> .48f
    }
    fun stroke(dp: Float, effect: PathEffect? = null) =
        Stroke(dp.dp.toPx() / scale, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
    fun dash(a: Float, b: Float) = PathEffect.dashPathEffect(floatArrayOf(a.dp.toPx() / scale, b.dp.toPx() / scale))
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
        for ((geometry, type) in data.land) {
            if (!geometry.bounds.intersects(visible) || (type == LandUse.BUILDING && detail != Detail.DETAIL)) continue
            val paint = style.land(type)
            drawPath(geometry.shape, paint.fill)
            paint.edge?.let { drawPath(geometry.shape, it, style = stroke(if (type == LandUse.BUILDING) .55f else 1.1f)) }
        }
        if (walkwayDash != null) {
            for (walk in data.walks) if (walk.bounds.intersects(visible)) {
                drawPath(walk.shape, style.muted.copy(alpha = .54f), style = stroke(1.5f, walkwayDash))
            }
        }
        for (layer in data.roadLayers) {
            val roads = layer.filter { road ->
                road.geometry.bounds.intersects(visible) &&
                    roadVisible(road.visual.roadClass, road.visual.structure, detail, road.planned, projection.visibleMeters)
            }
            for (road in roads) {
                val (geometry, visual) = road
                val paint = style.road(visual.roadClass, visual.structure)
                val edgeWidth = if (visual.structure == RoadStructure.BRIDGE || visual.structure == RoadStructure.ELEVATED) 2f else 0f
                drawPath(geometry.shape, paint.casing, style = stroke((paint.casingDp + edgeWidth) * roadFactor))
            }
            for (road in roads) {
                val (geometry, visual) = road
                val paint = style.road(visual.roadClass, visual.structure)
                drawPath(geometry.shape, paint.fill, style = stroke(paint.fillDp * roadFactor))
                when {
                    visual.structure == RoadStructure.TUNNEL ->
                        drawPath(geometry.shape, style.tunnel.casing, style = stroke(1.2f, tunnelDash))
                    visual.roadClass == RoadClass.EXPRESSWAY && highwayDash != null ->
                        drawPath(geometry.shape, paint.casing.copy(alpha = .65f), style = stroke(1f, highwayDash))
                }
            }
        }
        drawPath(data.route.shape, style.routeHalo, style = stroke(routeWidth + 3.5f))
        drawPath(data.route.shape, style.route, style = stroke(routeWidth))
        for (road in data.routeTunnels) if (road.geometry.bounds.intersects(visible)) {
            drawPath(road.geometry.shape, style.routeHalo.copy(alpha = .88f), style = stroke(1.4f, tunnelDash))
        }
    }
    val start = projection.offset(data.start)
    val end = projection.offset(data.end)
    drawCircle(style.routeHalo, 6.dp.toPx(), start)
    drawCircle(style.route, 3.dp.toPx(), start)
    val r = 4.dp.toPx()
    val halo = r + 1.6.dp.toPx()
    drawRoundRect(style.routeHalo, end - Offset(halo, halo), Size(halo * 2, halo * 2))
    drawRect(style.route, end - Offset(r, r), Size(r * 2, r * 2))

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
        val points = road.geometry.points
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

internal fun DrawScope.drawOverlay(overlay: MapOverlay, projection: Projection, style: MapStyle) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
    overlay.trails.forEach { trail ->
        if (trail.points.size < 2) return@forEach
        val path = Path().apply {
            trail.points.forEachIndexed { index, point ->
                val at = projection.offset(point)
                if (index == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
            }
        }
        drawPath(path, if (trail.estimated) style.estimate else style.driven, style =
            Stroke(if (trail.estimated) 3.2.dp.toPx() else 2.8.dp.toPx(), cap = StrokeCap.Round,
                join = StrokeJoin.Round, pathEffect = if (trail.estimated) dash else null))
    }
    overlay.lastKnown?.let {
        drawCircle(style.muted, 6.dp.toPx(), projection.offset(it), style = Stroke(2.dp.toPx()))
    }
    overlay.truth?.let {
        val at = projection.offset(it)
        val radius = 5.dp.toPx()
        drawRect(style.truth, at - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(2.dp.toPx()))
    }
    overlay.raw?.let { point ->
        val at = projection.offset(point)
        val padding = 8.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 2)
        val shown = Offset(at.x.coerceIn(padding, size.width - padding), at.y.coerceIn(padding, size.height - padding))
        overlay.accuracyMeters?.let { meters ->
            val radius = (meters * projection.scale).toFloat()
            if (at == shown && radius.isFinite() && radius >= 1f) {
                drawCircle(style.raw.copy(alpha = .12f), radius.coerceAtMost(size.minDimension * .25f), at)
            }
        }
        val r = 4.dp.toPx()
        drawLine(style.raw, shown - Offset(r, r), shown + Offset(r, r), 2.dp.toPx(), StrokeCap.Round)
        drawLine(style.raw, shown - Offset(r, -r), shown + Offset(r, -r), 2.dp.toPx(), StrokeCap.Round)
    }
    overlay.vehicle?.let { drawVehicle(it, projection.offset(it.point), style) }
}

private fun DrawScope.drawVehicle(marker: MapMarker, at: Offset, style: MapStyle) {
    val r = 12.dp.toPx()
    when (marker.kind) {
        MarkerKind.FIX -> {
            val heading = marker.heading
            if (heading == null) {
                drawCircle(style.routeHalo, r, at)
                drawCircle(style.route, r * .74f, at)
                drawCircle(style.routeHalo, r * .27f, at)
            } else {
                rotate(heading.toFloat(), at) {
                    val arrow = Path().apply {
                        moveTo(at.x, at.y-r)
                        lineTo(at.x+r*.76f, at.y+r*.62f)
                        lineTo(at.x, at.y+r*.25f)
                        lineTo(at.x-r*.76f, at.y+r*.62f)
                        close()
                    }
                    drawPath(arrow, style.routeHalo, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
                    drawPath(arrow, style.route)
                }
            }
        }
        MarkerKind.ESTIMATE -> {
            val path = Path().apply {
                moveTo(at.x, at.y-r)
                lineTo(at.x+r*.84f, at.y)
                lineTo(at.x, at.y+r)
                lineTo(at.x-r*.84f, at.y)
                close()
            }
            drawPath(path, style.chrome)
            drawPath(path, style.estimate, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        }
        MarkerKind.LAST_KNOWN -> {
            drawCircle(style.chrome, r, at)
            drawCircle(style.muted, r * .8f, at, style = Stroke(2.dp.toPx()))
            drawLine(style.muted, at - Offset(3.dp.toPx(), 0f), at + Offset(3.dp.toPx(), 0f), 2.dp.toPx())
        }
    }
}
