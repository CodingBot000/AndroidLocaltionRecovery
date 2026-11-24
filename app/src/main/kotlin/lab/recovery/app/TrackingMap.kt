package lab.recovery.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lab.recovery.map.Point2D
import lab.recovery.location.PositionSource
import lab.recovery.simulation.Scenario

private val MapBackground = Color(0xFF111C1B)
private val PlannedRoad = Color(0xFF477761)
private val OtherRoad = Color(0xFF50645F)
private val TunnelDash = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))

private data class MapRoad(val path: Path, val start: Offset, val color: Color, val stroke: Stroke)

private data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)
private fun Scenario.bounds(): Bounds {
    val points = roads.roads.flatMap { it.points }
    return Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}

private fun Bounds.fit(size: Size, pad: Float): (Point2D) -> Offset {
    val width = (maxX - minX).coerceAtLeast(1.0)
    val height = (maxY - minY).coerceAtLeast(1.0)
    val scale = minOf((size.width - 2 * pad).coerceAtLeast(1f) / width,
        (size.height - 2 * pad).coerceAtLeast(1f) / height)
    val x0 = (size.width - width * scale) / 2
    val y0 = (size.height + height * scale) / 2
    return { point ->
        Offset((x0 + (point.x - minX) * scale).toFloat(), (y0 - (point.y - minY) * scale).toFloat())
    }
}

private fun polyline(points: List<Point2D>, mapped: (Point2D) -> Offset): Path = Path().apply {
    points.forEachIndexed { index, point ->
        val screen = mapped(point)
        if (index == 0) moveTo(screen.x, screen.y) else lineTo(screen.x, screen.y)
    }
}

@Composable
private fun RoadMapBase(scenario: Scenario?, bounds: Bounds?, modifier: Modifier) {
    Box(modifier.drawWithCache {
        val grid = 20.dp.toPx()
        val mapped = bounds?.fit(size, 23.dp.toPx())
        val roads = if (scenario == null || mapped == null) emptyList() else scenario.roads.roads.map { road ->
            val planned = road.id in scenario.roads.plannedRoute
            val color = if (planned) PlannedRoad else OtherRoad
            val width = (if (planned) 5 else 3).dp.toPx()
            val dash = if (road.tunnel) TunnelDash else null
            val shape = polyline(road.points, mapped)
            val start = mapped(road.points.first())
            MapRoad(shape, start, color, Stroke(width, pathEffect = dash))
        }
        onDrawBehind {
            for (x in 1..(size.width / grid).toInt()) {
                drawLine(Rule.copy(alpha = 0.40f), Offset(x * grid, 0f), Offset(x * grid, size.height))
            }
            for (y in 1..(size.height / grid).toInt()) {
                drawLine(Rule.copy(alpha = 0.40f), Offset(0f, y * grid), Offset(size.width, y * grid))
            }
            roads.forEach { (path, start, color, stroke) ->
                drawPath(path, color, style = stroke)
                drawCircle(Muted, radius = 2.dp.toPx(), center = start)
            }
        }
    })
}

@Composable
internal fun TrackingMap(state: UiState, modifier: Modifier) {
    val scenario = state.scenario
    val bounds = remember(scenario) { scenario?.bounds() }
    val tunnelColor = remember(scenario) {
        scenario?.let { selected ->
            selected.roads.roads.firstOrNull { it.tunnel }?.let {
                if (it.id in selected.roads.plannedRoute) PlannedRoad else OtherRoad
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MapBackground).testTag("tracking-map")
        .semantics { contentDescription = "Synthetic road map. Dashed road indicates tunnel. ${state.snapshot.source.label()}." }) {
        RoadMapBase(scenario, bounds, Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            if (scenario == null || bounds == null) return@Canvas
            val mapped = bounds.fit(size, 23.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
            state.trails.forEach { (source, points) ->
                drawPath(polyline(points, mapped), if (source == PositionSource.DEAD_RECKONED) Amber else Mint,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = if (source == PositionSource.DEAD_RECKONED) dash else null))
            }
            if (state.showTruth) scenario.truthAt(state.snapshot.nowMs)?.position?.let {
                val point = mapped(it)
                val r = 5.dp.toPx()
                drawRect(Sky, Offset(point.x - r, point.y - r), Size(2 * r, 2 * r), style = Stroke(2.dp.toPx()))
            }
            state.snapshot.lastObservation?.takeIf {
                it.point.finite && state.snapshot.nowMs - it.receivedAtMs <= 1200
            }?.let {
                val point = mapped(it.point)
                val border = 9.dp.toPx()
                val x = point.x.coerceIn(border, size.width - border)
                val y = point.y.coerceIn(border, size.height - border)
                val r = 5.dp.toPx()
                drawLine(Coral, Offset(x-r, y-r), Offset(x+r, y+r), 2.dp.toPx())
                drawLine(Coral, Offset(x-r, y+r), Offset(x+r, y-r), 2.dp.toPx())
            }
            state.snapshot.position?.let {
                val screen = mapped(it)
                if (state.snapshot.source == PositionSource.DEAD_RECKONED) {
                    val r = 8.dp.toPx()
                    drawPath(Path().apply {
                        moveTo(screen.x, screen.y-r); lineTo(screen.x+r, screen.y)
                        lineTo(screen.x, screen.y+r); lineTo(screen.x-r, screen.y); close()
                    }, Amber, style = Stroke(2.dp.toPx()))
                } else {
                    val color = if (state.snapshot.source == PositionSource.LAST_KNOWN) Muted else Mint
                    drawCircle(color, radius = 8.dp.toPx(), center = screen, style = Stroke(2.dp.toPx()))
                    if (state.snapshot.source != PositionSource.LAST_KNOWN) drawCircle(Mint, radius = 3.dp.toPx(), center = screen)
                }
            }
        }
        tunnelColor?.let { color ->
            TunnelLegend(color, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
    }
}

@Composable
private fun TunnelLegend(color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(Panel, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 22.dp, height = 8.dp)) {
            drawLine(color, Offset(0f, center.y), Offset(size.width, center.y),
                strokeWidth = 3.dp.toPx(), pathEffect = TunnelDash)
        }
        Text(
            stringResource(R.string.map_tunnel),
            color = Ink,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("tunnel-legend"),
        )
    }
}

@Composable
internal fun MapLegend(truthEnabled: Boolean, toggle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)) {
        TextButton(onClick = toggle, modifier = Modifier.testTag("truth-toggle")) {
            Text(if (truthEnabled) "□ Truth" else "□ Truth off", color = if (truthEnabled) Sky else Muted,
                style = MaterialTheme.typography.labelSmall)
        }
        Text("× GPS", color = Coral, style = MaterialTheme.typography.labelSmall)
        Text("● Fix", color = Mint, style = MaterialTheme.typography.labelSmall)
        Text("◇ DR", color = Amber, style = MaterialTheme.typography.labelSmall)
        Text("○ Last", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}
