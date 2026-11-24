package lab.recovery.location

import lab.recovery.map.RoadGraph
internal class RouteDetector(private val graph: RoadGraph, private val settings: TrackingSettings) {
    var status = RouteStatus.UNKNOWN
        private set
    private var openDeparture = false
    private var offRoad: String? = null
    private var offMatch: MatchCandidate? = null
    private var offCount = 0
    private var onCount = 0
    private var lastEvidenceAt: Long? = null

    fun breakContinuity(unknown: Boolean) {
        offRoad = null
        offMatch = null
        offCount = 0
        onCount = 0
        lastEvidenceAt = null
        if (unknown || status == RouteStatus.SUSPECTED_OFF_ROUTE) status = RouteStatus.UNKNOWN
    }

    fun observe(road: String?, measuredAt: Long, match: MatchCandidate? = null): Reason? {
        if (road == null) {
            breakContinuity(unknown = true)
            return null
        }
        val elapsed = lastEvidenceAt?.let { measuredAt - it }
        if (elapsed != null && elapsed > settings.routeGapMs) breakContinuity(unknown = false)
        lastEvidenceAt = measuredAt
        if (road in graph.plannedRoute) {
            offCount = 0
            offRoad = null
            offMatch = null
            if (openDeparture && ++onCount < settings.routeCount) {
                status = RouteStatus.OFF_ROUTE
                return null
            }
            val returned = openDeparture
            openDeparture = false
            onCount = 0
            status = RouteStatus.ON_ROUTE
            return if (returned) Reason.ROUTE_RETURNED else null
        }
        onCount = 0
        if (offRoad != road) {
            val previous = offMatch
            val consecutive = graph.segmented && elapsed != null && elapsed in 1..settings.routeGapMs &&
                previous != null && match != null && recoveryRoadContinuous(graph, previous, match,
                    elapsed / 1000.0 * settings.maximumSpeed + settings.uncertaintyLimit)
            if (!consecutive) offCount = 0
            offRoad = road
        }
        offMatch = match
        if (openDeparture) {
            status = RouteStatus.OFF_ROUTE
            return null
        }
        if (++offCount < settings.routeCount) {
            status = RouteStatus.SUSPECTED_OFF_ROUTE
            return null
        }
        openDeparture = true
        status = RouteStatus.OFF_ROUTE
        return Reason.REROUTE_RECOMMENDED
    }
}
