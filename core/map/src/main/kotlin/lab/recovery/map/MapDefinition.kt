package lab.recovery.map

enum class RoadClass { LOCAL, COLLECTOR, ARTERIAL, EXPRESSWAY, RAMP }
enum class RoadStructure { SURFACE, TUNNEL, BRIDGE, ELEVATED }
enum class LandUse { BLOCK, RESIDENTIAL, COMMERCIAL, INDUSTRIAL, BUILDING, PARK, WATER, SCHOOL, SPORT, PARKING }

data class RoadVisual(
    val edgeId: String,
    val roadClass: RoadClass,
    val structure: RoadStructure,
    val level: Int = 0,
    val physicalId: String = edgeId,
    val name: String? = null,
)
data class LandPolygon(
    val type: LandUse,
    val points: List<Point2D>,
    val id: String? = null,
    val parcelId: String? = null,
    val holes: List<List<Point2D>> = emptyList(),
    val mid: List<List<Point2D>> = emptyList(),
    val overview: List<List<Point2D>> = emptyList(),
)
data class MapLabel(val text: String, val position: Point2D)
enum class PoiType { STATION, SCHOOL, PARK, SPORT, PARKING, FUEL }
data class MapPoi(val type: PoiType, val position: Point2D, val name: String? = null)
data class MapVisualData(
    val roads: List<RoadVisual> = emptyList(),
    val polygons: List<LandPolygon> = emptyList(),
    val labels: List<MapLabel> = emptyList(),
    val walkways: List<List<Point2D>> = emptyList(),
    val version: Int = 1,
    val railways: List<List<Point2D>> = emptyList(),
    val pois: List<MapPoi> = emptyList(),
)
data class OsmProvenance(
    val attribution: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val snapshot: String,
    val sourceChecksum: String,
    val projection: String,
    val origin: Point2D,
    val bounds: List<Double>,
)

data class MapDefinition(
    val id: String,
    val version: Int,
    val network: RoadNetwork,
    val visual: MapVisualData = MapVisualData(),
    val checksum: String? = null,
    val osm: OsmProvenance? = null,
)
