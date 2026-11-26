package lab.recovery.feature

import lab.recovery.location.DecisionEvent
import lab.recovery.location.LocationObservation
import lab.recovery.location.ObservationDecision
import lab.recovery.location.Reason
import lab.recovery.location.SignalState
import lab.recovery.map.Point2D
import org.junit.Assert.*
import org.junit.Test

class ObservationMarkerTest {
    @Test fun rejectionBelongsToTheObservationThatReceivedTheDecision() {
        val rejected = LocationObservation(1, "jump", 0, 0, Point2D(0.0, 0.0), 5.0, null, null)
        val next = rejected.copy(sequence = 2, id = "normal")
        val events = listOf(
            DecisionEvent(1, 0, Reason.IMPLAUSIBLE_MOVE, SignalState.TRACKING, SignalState.DEGRADED,
                observation = rejected, decision = ObservationDecision.REJECTED),
            DecisionEvent(1, 0, Reason.ACCEPTED, SignalState.DEGRADED, SignalState.TRACKING,
                observation = next, decision = ObservationDecision.ACCEPTED),
        )
        assertTrue(events.rejects(rejected))
        assertFalse(events.rejects(next))
        assertFalse(emptyList<DecisionEvent>().rejects(rejected))
    }
}
