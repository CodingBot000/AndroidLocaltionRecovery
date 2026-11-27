package lab.recovery.feature

import lab.recovery.simulation.Distribution
import lab.recovery.simulation.Ratio
import org.junit.Assert.*
import org.junit.Test

class ResultsReadoutTest {
    @Test fun zeroDenominatorAndNoRecoveriesAreUnavailable() {
        assertEquals("N/A", Ratio(0, 0).display())
        assertNull(emptyList<Long>().recoveryDelayRange())
        assertEquals("11/14", Ratio(11, 14).display())
    }

    @Test fun multipleRecoveriesKeepTheirMeasuredRange() {
        assertEquals(350L..2_250L, listOf(2_250L, 350L, 1_400L).recoveryDelayRange())
        assertEquals(780L..780L, listOf(780L).recoveryDelayRange())
    }

    @Test fun pairedErrorDisplayRequiresTheSameNonEmptyAcceptedSample() {
        val raw = Distribution(5, 4.7, null, null, null)
        val accepted = Distribution(5, 0.8, null, null, null)
        assertEquals(PairedErrors(4.7, 0.8, 5), pairedErrors(raw, accepted))
        assertNull(pairedErrors(raw.copy(count = 8), accepted))
        assertNull(pairedErrors(raw.copy(count = 0), accepted.copy(count = 0)))
    }
}
