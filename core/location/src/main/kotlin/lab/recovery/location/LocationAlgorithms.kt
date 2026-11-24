package lab.recovery.location

import lab.recovery.map.Point2D
import lab.recovery.map.RoadGraph
import kotlin.math.abs
import kotlin.math.min

data class MatchCandidate(val roadId: String, val segment: Int, val position: Point2D, val cost: Double, val offset: Double)
data class MatchResult(val confirmed: MatchCandidate?, val candidates: List<MatchCandidate>, val reason: Reason?)

fun angleDifference(first: Double, second: Double): Double {
    val difference = abs(first - second) % 360
    return min(difference, 360 - difference)
}

class MapMatcher(private val graph: RoadGraph, private val settings: TrackingSettings) {
    fun match(observation: LocationObservation, previousRoad: String?): MatchResult {
        val useHeading = observation.headingDegrees != null &&
            observation.speedMps?.let { it in settings.minimumHeadingSpeed..settings.maximumSpeed } == true
        val normalization = settings.distanceWeight + settings.planWeight +
            (if (useHeading) settings.headingWeight else 0.0) +
            (if (previousRoad != null) settings.continuityWeight else 0.0)
        val ordering = compareBy(MatchCandidate::cost, MatchCandidate::roadId, MatchCandidate::segment)
        val candidates = graph.roads.mapNotNull { road ->
            if (!road.withinBounds(observation.point, settings.searchRadius)) return@mapNotNull null
            val continuity = when {
                previousRoad == null || previousRoad == road.id -> 0.0
                graph.connected(previousRoad, road.id) -> 0.5
                else -> 1.0
            }
            val planCost = if (road.id in graph.plannedRoute) 0.0 else settings.planWeight
            road.segments.mapNotNull { segment ->
                val projected = segment.project(observation.point)
                val distance = observation.point.distance(projected)
                if (distance > settings.searchRadius) return@mapNotNull null
                val direction = if (useHeading) {
                    angleDifference(observation.headingDegrees!!, segment.heading) / 180 * settings.headingWeight
                } else 0.0
                val cost = (distance / settings.searchRadius * settings.distanceWeight + direction +
                    continuity * settings.continuityWeight + planCost) / normalization
                MatchCandidate(road.id, segment.index, projected, cost, road.offset(segment, projected))
            }.minWithOrNull(ordering)
        }.sortedWith(ordering)
        // Keep the better direction of a physical centerline. Distinct carriageways retain their own keys.
        val bestByRoad = if (graph.segmented) candidates.distinctBy {
            graph.byId.getValue(it.roadId).physicalId ?: it.roadId
        } else candidates
        val best = bestByRoad.firstOrNull()
        val runnerUp = bestByRoad.getOrNull(1)
        return when {
            best == null || best.cost > settings.maximumMatchCost -> MatchResult(null, bestByRoad, Reason.NO_MATCH)
            runnerUp != null && runnerUp.cost - best.cost < settings.minimumMatchMargin ->
                MatchResult(null, bestByRoad, Reason.AMBIGUOUS)
            else -> MatchResult(best, bestByRoad, null)
        }
    }
}

internal fun formatError(obs: LocationObservation): Reason? = when {
    obs.id.isBlank() -> Reason.INVALID_ID
    !obs.point.finite -> Reason.INVALID_COORDINATE
    obs.measuredAtMs < 0 -> Reason.INVALID_TIME
    !obs.accuracyMeters.isFinite() || obs.accuracyMeters < 0 -> Reason.INVALID_ACCURACY
    obs.speedMps?.let { !it.isFinite() || it < 0 } == true -> Reason.INVALID_SPEED
    obs.headingDegrees?.let { !it.isFinite() || it < 0 || it >= 360 } == true -> Reason.INVALID_HEADING
    else -> null
}

internal fun withinMovement(previous: LocationObservation, next: LocationObservation, settings: TrackingSettings): Boolean {
    val seconds = (next.measuredAtMs - previous.measuredAtMs) / 1000.0
    return seconds > 0 && previous.point.distance(next.point) <= movementLimit(previous, next, settings)
}

internal fun movementLimit(previous: LocationObservation, next: LocationObservation, settings: TrackingSettings): Double {
    val seconds = (next.measuredAtMs - previous.measuredAtMs) / 1000.0
    val uncertainty = min(settings.uncertaintyLimit, previous.accuracyMeters + next.accuracyMeters)
    return seconds * settings.maximumSpeed + uncertainty
}

internal data class Estimate(val position: Point2D? = null, val road: String? = null, val reason: Reason? = null)

internal fun estimate(anchor: DeadReckoningAnchor?, graph: RoadGraph, now: Long, settings: TrackingSettings): Estimate {
    if (anchor == null) return Estimate(reason = Reason.DR_NO_ANCHOR)
    val elapsed = now - anchor.measuredAtMs
    if (elapsed >= settings.drMaximumMs) return Estimate(reason = Reason.DR_LIMIT)
    val occurrences = graph.plannedRoute.indices.filter { graph.plannedRoute[it] == anchor.roadId }
    if (occurrences.size != 1) return Estimate(reason = Reason.DR_BRANCH)
    var offset = anchor.roadOffset + anchor.speedMps * elapsed / 1000.0
    for (index in occurrences.single()..graph.plannedRoute.lastIndex) {
        val road = graph.byId.getValue(graph.plannedRoute[index])
        if (offset < road.length) return Estimate(road.at(offset), road.id)
        val next = graph.plannedRoute.getOrNull(index + 1) ?: return Estimate(reason = Reason.DR_ROUTE_END)
        if (graph.outgoing(road.id).singleOrNull()?.id != next) return Estimate(reason = Reason.DR_BRANCH)
        offset -= road.length
    }
    return Estimate(reason = Reason.DR_ROUTE_END)
}

/** Distance-bounded forward traversal, with each outgoing edge expanded at its best known cost. */
internal fun recoveryRoadContinuous(
    graph: RoadGraph,
    first: MatchCandidate,
    next: MatchCandidate,
    maximumDistance: Double,
): Boolean {
    if (first.roadId == next.roadId) return true
    if (graph.legacy) return graph.connected(first.roadId, next.roadId)

    data class Departure(val roadId: String, val distance: Double)
    val departures = java.util.PriorityQueue(compareBy(Departure::distance))
    val firstDistance = graph.byId.getValue(first.roadId).length - first.offset
    departures += Departure(first.roadId, firstDistance)
    val visited = mutableSetOf<String>()
    while (departures.isNotEmpty()) {
        val (roadId, distance) = departures.remove()
        if (distance + next.offset > maximumDistance) break
        if (!visited.add(roadId)) continue
        for (road in graph.outgoing(roadId)) {
            if (road.id == next.roadId) return true
            val departure = distance + road.length
            if (departure <= maximumDistance) departures += Departure(road.id, departure)
        }
    }
    return false
}
