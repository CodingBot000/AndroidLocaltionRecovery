package lab.recovery.uimap

import androidx.compose.ui.graphics.Path
import lab.recovery.map.LandUse
import lab.recovery.map.Point2D
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadStructure
import lab.recovery.map.RoadVisual

internal data class WorldPath(val points: List<Point2D>, val closed: Boolean = false) {
    val bounds = Bounds.from(points)
    val shape = Path().apply {
        points.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x.toFloat(), -point.y.toFloat())
            else lineTo(point.x.toFloat(), -point.y.toFloat())
        }
        if (closed) close()
    }
}

internal data class MapRoad(val geometry: WorldPath, val visual: RoadVisual, val planned: Boolean)
internal data class MapLand(val geometry: WorldPath, val type: LandUse)

internal class MapGeometry(scene: MapScene) {
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

    val land = scene.visual.polygons.map { MapLand(WorldPath(it.points, closed = true), it.type) }
        .sortedBy { landOrder(it.type) }
    val walks = scene.visual.walkways.map { WorldPath(it) }
    val route = WorldPath(scene.route.roads.flatMap { it.points })
    val routeTunnels = roadLayers.flatten().filter { it.planned && it.visual.structure == RoadStructure.TUNNEL }
    val arrows = listOf(0.18, 0.38, 0.60, 0.80).map { scene.route.at(it * scene.route.length) }
    val labels = scene.visual.labels
    val start = route.points.first()
    val end = route.points.last()
    val cameraBounds = Bounds.from(scene.network.edges.values.flatMap { it.road.points } + route.points)
        .let { Bounds(it.minX - 95, it.minY - 95, it.maxX + 95, it.maxY + 95) }
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
    LandUse.PARK -> 1
    LandUse.WATER -> 2
    LandUse.BUILDING -> 3
}
