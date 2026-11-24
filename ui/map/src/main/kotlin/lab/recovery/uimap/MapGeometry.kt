package lab.recovery.uimap

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import lab.recovery.map.LandUse
import lab.recovery.map.Point2D
import lab.recovery.map.MapPoi
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadStructure
import lab.recovery.map.RoadVisual

internal class WorldPath(
    val points: List<Point2D>,
    private val closed: Boolean = false,
    private val holes: List<List<Point2D>> = emptyList(),
    private val mid: List<List<Point2D>> = emptyList(),
    private val overview: List<List<Point2D>> = emptyList(),
) {
    val bounds = Bounds.from(points)
    val shape by lazy(LazyThreadSafetyMode.NONE) { makePath(listOf(points) + holes) }
    private val midShape by lazy(LazyThreadSafetyMode.NONE) { reduced(mid, 1.0) }
    private val overviewShape by lazy(LazyThreadSafetyMode.NONE) { reduced(overview, 3.0) }

    fun shape(detail: Detail): Path = when (detail) {
        Detail.DETAIL -> shape
        Detail.MID -> midShape
        Detail.OVERVIEW -> overviewShape
    }

    private fun reduced(contours: List<List<Point2D>>, tolerance: Double): Path = when {
        contours.isNotEmpty() -> makePath(contours)
        closed || points.size <= 2 -> shape
        else -> makePath(listOf(simplifyLine(points, tolerance)))
    }

    private fun makePath(contours: List<List<Point2D>>) = Path().apply {
        if (contours.size > 1) fillType = PathFillType.EvenOdd
        for (ring in contours) {
            ring.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x.toFloat(), -point.y.toFloat())
                else lineTo(point.x.toFloat(), -point.y.toFloat())
            }
            if (closed) close()
        }
    }
}

internal data class MapRoad(val geometry: WorldPath, val visual: RoadVisual)
internal data class MapLand(val geometry: WorldPath, val type: LandUse)

/** Prepared on a worker. WorldPath only builds its lazy graphics paths when drawn on the UI thread. */
internal class MapWorld(
    scene: MapScene,
    val optimized: Boolean,
    checkpoint: () -> Unit = {},
) {
    private val byId = scene.visual.roads.associateBy(RoadVisual::edgeId)
    private fun roadVisual(roadId: String, tunnel: Boolean) = byId[roadId] ?: RoadVisual(roadId, RoadClass.COLLECTOR,
        if (tunnel) RoadStructure.TUNNEL else RoadStructure.SURFACE)

    val roadLayers = buildMap<String, MapRoad> {
        scene.network.edges.values.forEachIndexed { index, edge ->
            if (index % 256 == 0) checkpoint()
            val visual = roadVisual(edge.id, edge.road.tunnel)
            if (visual.physicalId !in this) put(visual.physicalId, MapRoad(WorldPath(edge.road.points), visual))
        }
    }.values.sortedWith(compareBy({ it.visual.level }, { roadOrder(it.visual.roadClass) }))
        .groupBy { it.visual.level }.values.toList()

    val land = scene.visual.polygons.mapIndexed { index, polygon ->
        if (index % 256 == 0) checkpoint()
        MapLand(WorldPath(polygon.points, closed = true, holes = polygon.holes,
            mid = polygon.mid, overview = polygon.overview), polygon.type)
    }.sortedBy { landOrder(it.type) }

    private fun linePaths(lines: List<List<Point2D>>, checkpoint: () -> Unit) = lines.mapIndexed { index, points ->
        if (index % 256 == 0) checkpoint()
        WorldPath(points)
    }

    private val walks = linePaths(scene.visual.walkways, checkpoint)
    private val rails = linePaths(scene.visual.railways, checkpoint)
    private val pois = scene.visual.pois
    private val landIndex = if (optimized) SpatialIndex(land, checkpoint) { it.geometry.bounds } else null
    private val walkwayIndex = if (optimized) SpatialIndex(walks, checkpoint, WorldPath::bounds) else null
    private val railIndex = if (optimized) SpatialIndex(rails, checkpoint, WorldPath::bounds) else null
    private val roadIndexes = if (optimized) roadLayers.map { SpatialIndex(it, checkpoint) { road -> road.geometry.bounds } } else null
    private val namedRoads = roadLayers.flatten().filter { it.visual.name != null }
    private val roadLabelIndex = if (optimized) SpatialIndex(namedRoads, checkpoint) { it.geometry.bounds } else null
    private val poiIndex = if (optimized) SpatialIndex(pois, checkpoint) { it.position.bounds() } else null

    fun visibleLand(bounds: Bounds): List<MapLand> =
        landIndex?.visible(bounds) ?: land.filter { it.geometry.bounds.intersects(bounds) }
    fun visibleWalks(bounds: Bounds): List<WorldPath> =
        walkwayIndex?.visible(bounds) ?: walks.filter { it.bounds.intersects(bounds) }
    fun visibleRails(bounds: Bounds): List<WorldPath> =
        railIndex?.visible(bounds) ?: rails.filter { it.bounds.intersects(bounds) }
    fun visiblePois(bounds: Bounds): List<MapPoi> =
        poiIndex?.visible(bounds) ?: pois.filter { it.position.bounds().intersects(bounds) }
    fun visibleRoads(bounds: Bounds): List<List<MapRoad>> =
        roadIndexes?.map { it.visible(bounds) }
            ?: roadLayers.map { layer -> layer.filter { it.geometry.bounds.intersects(bounds) } }
    fun visibleNamedRoads(bounds: Bounds): List<MapRoad> =
        roadLabelIndex?.visible(bounds) ?: namedRoads.filter { it.geometry.bounds.intersects(bounds) }

    val labels = scene.visual.labels
    val cameraBounds = scene.worldBounds?.let { Bounds(it[0], it[1], it[2], it[3]) }
        ?: Bounds.from(scene.network.nodes.values.map { it.point })
            .let { Bounds(it.minX - 95, it.minY - 95, it.maxX + 95, it.maxY + 95) }
    val followMeters = if (scene.worldBounds == null) 365.0 else 480.0

    fun physicalId(roadId: String): String = byId[roadId]?.physicalId ?: roadId
}

internal class MapGeometry(val scene: MapScene, val world: MapWorld) {
    private val planned = scene.route?.edgeIds.orEmpty().map(world::physicalId).toSet()
    fun isPlanned(road: MapRoad): Boolean = road.visual.physicalId in planned
    fun path(path: WorldPath, detail: Detail): Path = if (world.optimized) path.shape(detail) else path.shape

    val route = scene.route?.let { WorldPath(it.roads.flatMap { road -> road.points }) }
    val routeTunnels = scene.route?.roads.orEmpty().filter { it.tunnel }.map { WorldPath(it.points) }
    val arrows = scene.route?.let { plan -> listOf(0.18, 0.38, 0.60, 0.80).map { plan.at(it * plan.length) } }.orEmpty()
    val overviewBounds = route?.bounds ?: world.cameraBounds
    val focus = route?.points?.first() ?: world.cameraBounds.center
    val cameraBounds = if (scene.worldBounds != null || route == null) world.cameraBounds else {
        val routeBounds = route.bounds
        val mapBounds = world.cameraBounds
        Bounds(minOf(mapBounds.minX, routeBounds.minX - 95), minOf(mapBounds.minY, routeBounds.minY - 95),
            maxOf(mapBounds.maxX, routeBounds.maxX + 95), maxOf(mapBounds.maxY, routeBounds.maxY + 95))
    }
    val followMeters get() = world.followMeters
}

internal fun roadOrder(type: RoadClass) = when (type) {
    RoadClass.LOCAL -> 0
    RoadClass.COLLECTOR -> 1
    RoadClass.RAMP -> 2
    RoadClass.ARTERIAL -> 3
    RoadClass.EXPRESSWAY -> 4
}

private fun landOrder(type: LandUse) = when (type) {
    LandUse.BLOCK, LandUse.RESIDENTIAL, LandUse.COMMERCIAL, LandUse.INDUSTRIAL -> 0
    LandUse.PARK, LandUse.SCHOOL, LandUse.SPORT, LandUse.PARKING -> 1
    LandUse.WATER -> 2
    LandUse.BUILDING -> 3
}
