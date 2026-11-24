package lab.recovery.uimap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import lab.recovery.map.Point2D
import lab.recovery.map.RoadStructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private class PreparedMapSlot {
    var key: Any? = null
    var world: MapWorld? = null
}

@Composable
fun RoadMap(
    scene: MapScene?,
    overlay: MapOverlay,
    dark: Boolean,
    strings: MapStrings,
    modifier: Modifier = Modifier,
    credits: MapCredits? = null,
    optimized: Boolean = true,
    runId: Long = 0,
    onMapReady: (Long) -> Unit = {},
) {
    val slot = remember(optimized) { PreparedMapSlot() }
    val prepared by produceState<MapGeometry?>(null, scene, runId, optimized) {
        val target = scene
        if (target == null) {
            value = null
            return@produceState
        }
        val key = target.dataKey ?: target.network
        val existing = slot.world.takeIf { slot.key == key }
        val geometry = withContext(Dispatchers.Default) {
            val world = existing ?: MapWorld(target, optimized) { ensureActive() }
            ensureActive()
            MapGeometry(target, world)
        }
        ensureActive()
        slot.key = key
        slot.world = geometry.world
        value = geometry
    }
    val geometry = prepared?.takeIf { it.scene === scene }
    val mapReady by rememberUpdatedState(onMapReady)
    LaunchedEffect(geometry, runId) {
        if (geometry != null) {
            withFrameNanos { }
            mapReady(runId)
        }
    }
    var mode by remember(scene, runId) { mutableStateOf(CameraMode.FOLLOW) }
    var free by remember(scene, runId) { mutableStateOf<Projection?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var controlsBounds by remember { mutableStateOf(Rect.Zero) }
    var modeBounds by remember { mutableStateOf(Rect.Zero) }
    var tunnelBounds by remember { mutableStateOf(Rect.Zero) }
    var creditBounds by remember { mutableStateOf(Rect.Zero) }
    val style = if (dark) MapStyle.Night else MapStyle.Day
    val latest by rememberUpdatedState(overlay)
    val followPoint by rememberUpdatedState(overlay.vehicle?.point)
    val padding = with(LocalDensity.current) { 19.dp.toPx().toDouble() }
    fun projection(data: MapGeometry): Projection {
        val width = viewport.width.toDouble()
        val height = viewport.height.toDouble()
        val routeBounds = data.route.bounds
        val fitted = routeBounds.fit(width, height, padding)
        val overview = Projection(routeBounds.center, fitted, width, height)
        return when (mode) {
            CameraMode.FOLLOW -> Projection(followPoint?.let(data.cameraBounds::clamp) ?: data.start,
                maxOf(fitted, width / data.followMeters, .01), width, height).followOffset()
            CameraMode.OVERVIEW -> overview
            CameraMode.FREE -> free?.copy(width = width, height = height) ?: overview
        }
    }

    fun manipulate(anchor: Point2D, pan: Point2D, zoom: Double) {
        val data = geometry ?: return
        val current = projection(data)
        val min = data.route.bounds.fit(current.width, current.height, padding) * .5
        val next = current.manipulated(anchor, pan, zoom, min, maxOf(min, current.width / 35.0))
        free = next.copy(center = data.cameraBounds.clamp(next.center))
        mode = CameraMode.FREE
    }

    val modeLabel = when (mode) {
        CameraMode.FOLLOW -> strings.follow
        CameraMode.OVERVIEW -> strings.overview
        CameraMode.FREE -> strings.free
    }
    val density = LocalDensity.current
    val labelCache = remember(geometry, density) { MapLabelCache(density) }
    val strokes = remember { MapStrokeCache() }
    val trailPaths = remember(runId) { OverlayPathCache() }
    val trailStrokes = remember { MapStrokeCache() }
    val shape = RoundedCornerShape(14.dp)
    val border = style.muted.copy(alpha = .32f)
    val hasTunnel = remember(geometry, credits != null) {
        if (credits != null) geometry?.routeTunnels?.isNotEmpty() == true
        else geometry?.world?.roadLayers?.any { layer -> layer.any { it.visual.structure == RoadStructure.TUNNEL } } == true
    }
    Box(modifier.clip(shape).background(style.background).border(1.dp, border, shape)
        .testTag("tracking-map").semantics { contentDescription = strings.map }
        .onSizeChanged { viewport = it }) {
        Canvas(Modifier.matchParentSize().pointerInput(geometry) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                manipulate(Point2D(centroid.x.toDouble(), centroid.y.toDouble()),
                    Point2D(pan.x.toDouble(), pan.y.toDouble()), zoom.toDouble())
            }
        }) {
            val data = geometry ?: return@Canvas
            if (viewport.width < 1 || viewport.height < 1) return@Canvas
            val camera = projection(data)
            val detail = mapDetail(mode, camera.visibleMeters)
            drawMap(data, camera, style, detail, strokes)
            if (!optimized) {
                drawOverlay(latest, camera, style, trailPaths, trailStrokes)
                val occupied = listOf(modeBounds, controlsBounds, creditBounds, tunnelBounds)
                drawMapLabels(data, camera, detail, style, latest, occupied, labelCache)
            }
        }
        if (optimized) Canvas(Modifier.matchParentSize()) {
            val data = geometry ?: return@Canvas
            if (viewport.width < 1 || viewport.height < 1) return@Canvas
            val camera = projection(data)
            drawOverlay(latest, camera, style, trailPaths, trailStrokes)
        }
        if (optimized) Canvas(Modifier.matchParentSize()) {
            val data = geometry ?: return@Canvas
            if (viewport.width < 1 || viewport.height < 1) return@Canvas
            val camera = projection(data)
            val occupied = listOf(modeBounds, controlsBounds, creditBounds, tunnelBounds)
            drawMapLabels(data, camera, mapDetail(mode, camera.visibleMeters), style, latest, occupied, labelCache)
        }
        if (scene != null && geometry == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp).testTag("map-preparing"))
        }
        if (geometry != null) {
            credits?.let {
                Surface(onClick = it.onClick, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .onGloballyPositioned { creditBounds = it.boundsInParent() }.testTag("osm-attribution"),
                    shape = RoundedCornerShape(7.dp), color = style.chrome, contentColor = style.chromeText,
                    border = BorderStroke(1.dp, border)) {
                    Text(it.text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                .onGloballyPositioned { modeBounds = it.boundsInParent() },
                shape = RoundedCornerShape(8.dp), color = style.chrome, contentColor = style.chromeText,
                border = BorderStroke(1.dp, border)) {
                Text(modeLabel, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp).testTag("camera-mode"),
                    style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .onGloballyPositioned { controlsBounds = it.boundsInParent() }, horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(18.dp), color = style.chrome, contentColor = style.chromeText,
                    border = BorderStroke(1.dp, border)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = {
                            manipulate(Point2D(viewport.width / 2.0, viewport.height / 2.0), Point2D(0.0, 0.0), .8)
                        }, modifier = Modifier.size(48.dp).testTag("zoom-out")
                            .semantics { contentDescription = strings.zoomOut }) { Text("−") }
                        IconButton(onClick = {
                            manipulate(Point2D(viewport.width / 2.0, viewport.height / 2.0), Point2D(0.0, 0.0), 1.25)
                        }, modifier = Modifier.size(48.dp).testTag("zoom-in")
                            .semantics { contentDescription = strings.zoomIn }) { Text("+") }
                    }
                }
                val colors = ButtonDefaults.outlinedButtonColors(containerColor = style.chrome, contentColor = style.chromeText)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { mode = CameraMode.OVERVIEW }, colors = colors, border = BorderStroke(1.dp, border),
                        modifier = Modifier.testTag("overview")) { Text(strings.overview) }
                    OutlinedButton(onClick = { mode = CameraMode.FOLLOW }, colors = colors, border = BorderStroke(1.dp, border),
                        modifier = Modifier.testTag("recenter")) { Text(strings.follow) }
                }
            }
            if (hasTunnel) {
                Surface(Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .onGloballyPositioned { tunnelBounds = it.boundsInParent() },
                    shape = RoundedCornerShape(8.dp), color = style.chrome, contentColor = style.chromeText,
                    border = BorderStroke(1.dp, border)) {
                    Text(strings.tunnel, Modifier.padding(6.dp).testTag("tunnel-legend"),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
