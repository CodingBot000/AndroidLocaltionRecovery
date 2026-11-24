package lab.recovery.simulation

import lab.recovery.location.DecisionEvent
import lab.recovery.location.ObservationDecision
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import lab.recovery.location.TrackingSnapshot
import lab.recovery.map.Point2D

data class TrailSegment(val source: PositionSource, val points: List<Point2D>)
internal class ReplayHistory {
    private data class Sample(val segment: Long, val point: Point2D)
    private val diagnostics = ArrayDeque<DecisionEvent>()
    private val trails = mutableMapOf<PositionSource, ArrayDeque<Sample>>()
    private var segment = 0L
    private var source = PositionSource.NONE
    private var dirty = false
    var logSnapshot: List<DecisionEvent> = emptyList()
        private set
    var trailSnapshot: List<TrailSegment> = emptyList()
        private set

    fun record(snapshot: TrackingSnapshot, events: List<DecisionEvent>) {
        if (events.isNotEmpty()) {
            diagnostics.addAll(events)
            while (diagnostics.size > 500) diagnostics.removeFirst()
            logSnapshot = diagnostics.toList()
        }
        val newSource = snapshot.source
        if (source != newSource || events.any { it.reason in boundaries || it.decision == ObservationDecision.REJECTED }) segment++
        source = newSource
        val point = snapshot.position ?: return
        if (newSource == PositionSource.LAST_KNOWN) return
        val samples = trails.getOrPut(newSource) { ArrayDeque() }
        if (samples.lastOrNull()?.point == point && samples.lastOrNull()?.segment == segment) return
        if (samples.size == 1000) samples.removeFirst()
        samples.addLast(Sample(segment, point))
        dirty = true
    }

    fun publishTrail() {
        if (!dirty) return
        trailSnapshot = trails.flatMap { (source, samples) ->
            samples.groupBy(Sample::segment).map { (segment, group) ->
                segment to TrailSegment(source, group.map(Sample::point))
            }
        }.sortedBy { it.first }.map { it.second }
        dirty = false
    }

    private companion object {
        val boundaries = setOf(Reason.LOW_QUALITY, Reason.ACCURACY_EXCEEDED, Reason.AMBIGUOUS, Reason.NO_MATCH,
            Reason.SIGNAL_LOST, Reason.SIGNAL_RECOVERED, Reason.DR_STARTED, Reason.DR_LIMIT,
            Reason.DR_REALIGNED, Reason.DR_BRANCH, Reason.DR_ROUTE_END)
    }
}
