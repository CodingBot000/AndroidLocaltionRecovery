package lab.recovery.feature

import lab.recovery.location.DecisionEvent
import lab.recovery.location.Reason
import lab.recovery.location.SignalState
import org.junit.Assert.*
import org.junit.Test

class RecentDecisionTest {
    @Test fun recentDecisionUsesEngineOrderAndIgnoresOrdinaryAcceptances() {
        val lost = event(1, 5_000, Reason.SIGNAL_LOST)
        val estimate = event(1, 5_000, Reason.DR_STARTED)
        val recovered = event(1, 8_000, Reason.SIGNAL_RECOVERED)
        assertEquals(estimate, listOf(lost, estimate).recentDecision(1))
        val logs = listOf(lost, estimate, recovered, event(1, 9_000, Reason.ACCEPTED))
        assertEquals(recovered, logs.recentDecision(1))
        assertEquals(4, logs.size)
    }

    @Test fun resetOrSelectionCannotDisplayThePreviousRunDecision() {
        val log = listOf(event(1, 5_000, Reason.IMPLAUSIBLE_MOVE))
        assertNull(log.recentDecision(2))
        assertNull(emptyList<DecisionEvent>().recentDecision(1))
    }

    private fun event(runId: Long, time: Long, reason: Reason) = DecisionEvent(
        runId, time, reason, SignalState.TRACKING, SignalState.LOST,
    )
}
