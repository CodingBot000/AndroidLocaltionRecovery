package lab.recovery.uimap

import lab.recovery.map.MapVisualData
import lab.recovery.map.Point2D
import lab.recovery.map.RoadNetwork
import lab.recovery.map.RoutePlan

data class MapScene(
    val network: RoadNetwork,
    val route: RoutePlan?,
    val visual: MapVisualData,
    val worldBounds: List<Double>? = null,
    val dataKey: String? = null,
)

data class MapCredits(val text: String, val onClick: () -> Unit)
enum class MarkerKind { FIX, ESTIMATE, LAST_KNOWN }
data class MapMarker(val point: Point2D, val kind: MarkerKind, val heading: Double? = null)
data class MapTrail(val points: List<Point2D>, val estimated: Boolean)
data class MapOverlay(
    val vehicle: MapMarker? = null,
    val raw: Point2D? = null,
    val accuracyMeters: Double? = null,
    val lastKnown: Point2D? = null,
    val truth: Point2D? = null,
    val trails: List<MapTrail> = emptyList(),
    val rawRejected: Boolean = false,
)
data class MapStrings(
    val map: String,
    val follow: String,
    val overview: String,
    val free: String,
    val zoomIn: String,
    val zoomOut: String,
    val tunnel: String,
)
