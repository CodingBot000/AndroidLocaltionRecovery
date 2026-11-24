package lab.recovery.uimap

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import lab.recovery.map.LandUse
import lab.recovery.map.MapLabel
import lab.recovery.map.Point2D
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadStructure
import lab.recovery.map.RoadVisual

private data class DrawRoad(val shape: Path, val visual: RoadVisual, val width: Float)
private data class DrawLand(val shape: Path, val type: LandUse)

private fun width(type: RoadClass): Float = when (type) {
    RoadClass.LOCAL -> 2f
    RoadClass.COLLECTOR -> 3f
    RoadClass.ARTERIAL -> 5f
    RoadClass.EXPRESSWAY -> 8f
    RoadClass.RAMP -> 4f
}

private fun worldPath(points: List<Point2D>, closed: Boolean = false) = Path().apply {
    points.forEachIndexed { index, point ->
        if (index == 0) moveTo(point.x.toFloat(), -point.y.toFloat())
        else lineTo(point.x.toFloat(), -point.y.toFloat())
    }
    if (closed) close()
}

private class Geometry(scene: MapScene) {
    private val byId = scene.visual.roads.associateBy(RoadVisual::edgeId)
    val roads = scene.network.edges.values.map { edge ->
        val visual = byId[edge.id] ?: RoadVisual(edge.id, RoadClass.COLLECTOR,
            if (edge.road.tunnel) RoadStructure.TUNNEL else RoadStructure.SURFACE)
        DrawRoad(worldPath(edge.road.points), visual, width(visual.roadClass))
    }.distinctBy { it.visual.physicalId }.sortedWith(compareBy({ it.visual.level }, { it.width }))
    val land = scene.visual.polygons.map { DrawLand(worldPath(it.points, closed = true), it.type) }
    val routePoints = scene.route.roads.flatMap { it.points }
    val route = worldPath(routePoints)
    val routeTunnels = scene.route.roads.filter { it.tunnel || byId[it.id]?.structure == RoadStructure.TUNNEL }
        .map { worldPath(it.points) }
    val arrows = (0.2..0.8).let { range -> listOf(range.start, 0.5, range.endInclusive) }
        .map { scene.route.at(it * scene.route.length) }
    val labels = scene.visual.labels
    val routeBounds = Bounds.from(routePoints)
    val worldBounds = Bounds.from(scene.network.nodes.values.map { it.point } + routePoints)
    val start = routePoints.first()
}

@Composable
fun RoadMap(
    scene: MapScene?,
    overlay: MapOverlay,
    dark: Boolean,
    strings: MapStrings,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(scene) { scene?.let(::Geometry) }
    var mode by remember(scene) { mutableStateOf(CameraMode.FOLLOW) }
    var free by remember(scene) { mutableStateOf<Projection?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val style = if (dark) MapStyle.Night else MapStyle.Day
    val latest by rememberUpdatedState(overlay)
    val padding = 42.0
    fun projection(data: Geometry): Projection {
        val width = viewport.width.toDouble()
        val height = viewport.height.toDouble()
        val fitted = data.routeBounds.fit(width, height, padding)
        return when (mode) {
            CameraMode.FOLLOW -> Projection(latest.vehicle?.point ?: data.start,
                maxOf(fitted, width / 380.0, 0.01), width, height)
            CameraMode.OVERVIEW -> Projection(data.routeBounds.center, fitted, width, height)
            CameraMode.FREE -> free?.copy(width = width, height = height)
                ?: Projection(data.routeBounds.center, fitted, width, height)
        }
    }

    fun manipulate(anchor: Point2D, pan: Point2D, zoom: Double) {
        val data = geometry ?: return
        val current = projection(data)
        val min = data.routeBounds.fit(current.width, current.height, padding) * 0.5
        val next = current.manipulated(anchor, pan, zoom, min, maxOf(min, current.width / 35.0))
        val margin = 70.0
        val bounds = data.worldBounds
        val limits = Bounds(bounds.minX - margin, bounds.minY - margin, bounds.maxX + margin, bounds.maxY + margin)
        free = next.copy(center = limits.clamp(next.center))
        mode = CameraMode.FREE
    }

    val modeLabel = when (mode) {
        CameraMode.FOLLOW -> strings.follow
        CameraMode.OVERVIEW -> strings.overview
        CameraMode.FREE -> strings.free
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(style.background)
        .testTag("tracking-map").semantics { contentDescription = strings.map }
        .onSizeChanged { viewport = it }) {
        Canvas(Modifier.matchParentSize().pointerInput(geometry) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                manipulate(Point2D(centroid.x.toDouble(), centroid.y.toDouble()),
                    Point2D(pan.x.toDouble(), pan.y.toDouble()), zoom.toDouble())
            }
        }) {
            val data = geometry ?: return@Canvas
            val camera = projection(data)
            drawGeometry(data, camera, style, paint)
            drawOverlay(latest, camera)
        }
        if (geometry != null) {
            Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(8.dp)) {
                Text(modeLabel, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp).testTag("camera-mode"),
                    style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(6.dp), horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for ((label, symbol, amount) in listOf(Triple(strings.zoomOut, "−", 0.8), Triple(strings.zoomIn, "+", 1.25))) {
                            IconButton(onClick = {
                                manipulate(Point2D(viewport.width / 2.0, viewport.height / 2.0), Point2D(0.0, 0.0), amount)
                            }, modifier = Modifier.size(48.dp).testTag(if (amount > 1) "zoom-in" else "zoom-out")
                                .semantics { contentDescription = label }) { Text(symbol) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { mode = CameraMode.OVERVIEW }, modifier = Modifier.testTag("overview")) {
                        Text(strings.overview)
                    }
                    FilledTonalButton(onClick = { mode = CameraMode.FOLLOW }, modifier = Modifier.testTag("recenter")) {
                        Text(strings.follow)
                    }
                }
            }
            if (geometry.roads.any { it.visual.structure == RoadStructure.TUNNEL }) {
                Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(8.dp)) {
                    Text(strings.tunnel, Modifier.padding(6.dp).testTag("tunnel-legend"),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun DrawScope.drawGeometry(data: Geometry, projection: Projection, style: MapStyle, labelPaint: Paint) {
    val zoom = projection.scale.toFloat().coerceAtLeast(0.0001f)
    val origin = projection.offset(Point2D(0.0, 0.0))
    fun stroke(width: Float, dash: Boolean = false) = Stroke(width.dp.toPx() / zoom, cap = StrokeCap.Round,
        join = StrokeJoin.Round, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx() / zoom, 6.dp.toPx() / zoom)) else null)
    withTransform({
        translate(origin.x, origin.y)
        scale(zoom, zoom, Offset.Zero)
    }) {
        data.land.forEach { drawPath(it.shape, style.land(it.type)) }
        data.roads.forEach { (shape, visual, width) ->
            val structure = visual.structure
            val tunnel = structure == RoadStructure.TUNNEL
            val casing = if (tunnel) style.tunnel else style.casing
            drawPath(shape, casing, style = stroke(width + if (structure in listOf(RoadStructure.BRIDGE, RoadStructure.ELEVATED)) 3f else 1.5f, tunnel))
            drawPath(shape, style.road(visual.roadClass), style = stroke(width, tunnel))
        }
        drawPath(data.route, style.background, style = stroke(6f))
        drawPath(data.route, style.route, style = stroke(4f))
        data.routeTunnels.forEach { drawPath(it, style.tunnel, style = stroke(3f, dash = true)) }
    }
    for (endpoint in listOf(data.start, data.routePoints.last())) {
        val point = projection.offset(endpoint)
        drawCircle(style.background, 7.dp.toPx(), point)
        drawCircle(style.route, 4.dp.toPx(), point)
    }
    data.arrows.forEach {
        val point = projection.offset(it.point)
        val r = 4.dp.toPx()
        rotate(it.heading.toFloat(), point) {
            drawLine(style.background, Offset(point.x - r, point.y + r), Offset(point.x, point.y - r), 2.dp.toPx())
            drawLine(style.background, Offset(point.x + r, point.y + r), Offset(point.x, point.y - r), 2.dp.toPx())
        }
    }
    val labelBounds = mutableListOf<Rect>()
    labelPaint.color = style.label.toArgb()
    labelPaint.textSize = 11.dp.toPx()
    for (label: MapLabel in data.labels) {
        val point = projection.offset(label.position)
        val width = labelPaint.measureText(label.text)
        val bounds = Rect(point.x - 3, point.y - labelPaint.textSize, point.x + width + 3, point.y + 4)
        if (bounds.left < 0 || bounds.right > size.width || bounds.top < 0 || bounds.bottom > size.height) continue
        if (labelBounds.any { it.overlaps(bounds) }) continue
        if (zoom < 0.23f && labelBounds.size >= 3) break
        labelBounds += bounds
        drawIntoCanvas { it.nativeCanvas.drawText(label.text, point.x, point.y, labelPaint) }
    }
}

private fun DrawScope.drawOverlay(overlay: MapOverlay, camera: Projection) {
    val fix = Color(0xFF007A5C)
    val estimated = Color(0xFFA86404)
    val raw = Color(0xFFBC3142)
    val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
    overlay.trails.forEach { trail ->
        if (trail.points.size < 2) return@forEach
        val path = Path().apply {
            trail.points.forEachIndexed { index, point ->
                val at = camera.offset(point)
                if (index == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
            }
        }
        drawPath(path, if (trail.estimated) estimated else fix,
            style = Stroke(2.dp.toPx(), pathEffect = if (trail.estimated) dash else null))
    }
    overlay.lastKnown?.let { drawCircle(Color.Gray, 6.dp.toPx(), camera.offset(it), style = Stroke(2.dp.toPx())) }
    overlay.truth?.let {
        val at = camera.offset(it)
        val r = 5.dp.toPx()
        drawRect(Color(0xFF5268AB), at - Offset(r, r), Size(r * 2, r * 2), style = Stroke(2.dp.toPx()))
    }
    overlay.raw?.let {
        val at = camera.offset(it)
        val padding = 8.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 2)
        val shown = Offset(at.x.coerceIn(padding, size.width - padding), at.y.coerceIn(padding, size.height - padding))
        val r = 4.dp.toPx()
        overlay.accuracyMeters?.let { meters ->
            val accuracy = (meters * camera.scale).toFloat()
            if (at == shown && accuracy in 1f..size.minDimension) drawCircle(raw.copy(alpha = 0.14f), accuracy, at)
        }
        drawLine(raw, shown - Offset(r, r), shown + Offset(r, r), 2.dp.toPx())
        drawLine(raw, shown - Offset(r, -r), shown + Offset(r, -r), 2.dp.toPx())
    }
    overlay.vehicle?.let { marker ->
        val at = camera.offset(marker.point)
        val r = 9.dp.toPx()
        drawCircle(Color.White, r + 2.dp.toPx(), at)
        when (marker.kind) {
            MarkerKind.FIX -> {
                drawCircle(fix, r, at)
                marker.heading?.let { heading ->
                    rotate(heading.toFloat(), at) {
                        val arrow = Path().apply {
                            moveTo(at.x, at.y - r)
                            lineTo(at.x + r * 0.55f, at.y + r * 0.5f)
                            lineTo(at.x, at.y + r * 0.2f)
                            lineTo(at.x - r * 0.55f, at.y + r * 0.5f)
                            close()
                        }
                        drawPath(arrow, Color.White)
                    }
                }
            }
            MarkerKind.ESTIMATE -> drawPath(Path().apply {
                moveTo(at.x, at.y - r)
                lineTo(at.x + r, at.y)
                lineTo(at.x, at.y + r)
                lineTo(at.x - r, at.y)
                close()
            }, estimated, style = Stroke(2.dp.toPx()))
            MarkerKind.LAST_KNOWN -> drawCircle(Color.DarkGray, r, at, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun Projection.offset(point: Point2D) = screen(point).let { Offset(it.x.toFloat(), it.y.toFloat()) }
