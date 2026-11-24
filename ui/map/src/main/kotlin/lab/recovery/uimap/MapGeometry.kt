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

internal data class MapRoad(val geometry: WorldPath, val visual: RoadVisual, val planned: Boolean)
internal data class MapLand(val geometry: WorldPath, val type: LandUse)

internal class MapGeometry(scene: MapScene, val optimized: Boolean = true) {
    private val byId = scene.visual.roads.associateBy(RoadVisual::edgeId)
    private val planned = scene.route.edgeIds.toSet()
    val roadLayers = scene.network.edges.values.map { edge ->
        val style = byId[edge.id] ?: RoadVisual(edge.id, RoadClass.COLLECTOR,
            if (edge.road.tunnel) RoadStructure.TUNNEL else RoadStructure.SURFACE)
        MapRoad(WorldPath(edge.road.points), style, edge.id in planned)
    }.groupBy { it.visual.physicalId }
        .values.map { roads -> roads.firstOrNull(MapRoad::planned) ?: roads.first() }
        .sortedWith(compareBy({ it.visual.level }, { roadOrder(it.visual.roadClass) }))
        .groupBy { it.visual.level }.values.toList()

    val land = scene.visual.polygons.map { MapLand(WorldPath(it.points, closed = true, holes = it.holes, mid = it.mid, overview = it.overview), it.type) }
        .sortedBy { landOrder(it.type) }
    val walks = scene.visual.walkways.map { WorldPath(it) }
    val rails = scene.visual.railways.map { WorldPath(it) }
    private val pois = scene.visual.pois
    private val landIndex = if (optimized) SpatialIndex(land) { it.geometry.bounds } else null
    private val walkwayIndex = if (optimized) SpatialIndex(walks, WorldPath::bounds) else null
    private val railIndex = if (optimized) SpatialIndex(rails, WorldPath::bounds) else null
    private val roadIndexes = if (optimized) roadLayers.map { SpatialIndex(it) { road -> road.geometry.bounds } } else null
    private val poiIndex = if (optimized) SpatialIndex(pois) { it.position.bounds() } else null

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
    fun path(world: WorldPath, detail: Detail): Path = if (optimized) world.shape(detail) else world.shape

    val route = WorldPath(scene.route.roads.flatMap { it.points })
    val routeTunnels = roadLayers.flatten().filter { it.planned && it.visual.structure == RoadStructure.TUNNEL }
    val arrows = listOf(0.18, 0.38, 0.60, 0.80).map { scene.route.at(it * scene.route.length) }
    val labels = scene.visual.labels
    val start = route.points.first()
    val end = route.points.last()
    val cameraBounds = scene.worldBounds?.let { Bounds(it[0], it[1], it[2], it[3]) }
        ?: Bounds.from(scene.network.edges.values.flatMap { it.road.points } + route.points)
            .let { Bounds(it.minX - 95, it.minY - 95, it.maxX + 95, it.maxY + 95) }
    val followMeters = if (scene.worldBounds == null) 365.0 else 480.0
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
