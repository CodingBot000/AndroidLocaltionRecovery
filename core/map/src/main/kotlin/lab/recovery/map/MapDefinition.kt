package lab.recovery.map

enum class RoadClass { LOCAL, COLLECTOR, ARTERIAL, EXPRESSWAY, RAMP }
enum class RoadStructure { SURFACE, TUNNEL, BRIDGE, ELEVATED }
enum class LandUse { BLOCK, BUILDING, PARK, WATER }

data class RoadVisual(
    val edgeId: String,
    val roadClass: RoadClass,
    val structure: RoadStructure,
    val level: Int = 0,
    val physicalId: String = edgeId,
)
data class LandPolygon(val type: LandUse, val points: List<Point2D>)
data class MapLabel(val text: String, val position: Point2D)
data class MapVisualData(
    val roads: List<RoadVisual> = emptyList(),
    val polygons: List<LandPolygon> = emptyList(),
    val labels: List<MapLabel> = emptyList(),
)
data class MapDefinition(
    val id: String,
    val version: Int,
    val network: RoadNetwork,
    val visual: MapVisualData = MapVisualData(),
    val checksum: String? = null,
)
