package lab.recovery.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveReadoutTest {
    @Test fun fixOriginSeparatesActualAndroidMockAndEmulator() {
        assertEquals(R.string.live_source_android, fixSourceResource(emulator = false, mock = false))
        assertEquals(R.string.live_source_mock, fixSourceResource(emulator = false, mock = true))
        assertEquals(R.string.live_source_emulator, fixSourceResource(emulator = true, mock = false))
        assertEquals(R.string.live_source_emulator_mock, fixSourceResource(emulator = true, mock = true))
    }

    @Test fun permissionsAndUnavailableFixesHaveDistinctLabels() {
        assertNotEquals(Precision.COARSE.labelResource(), Precision.FINE.labelResource())
        assertNotEquals(FixStatus.STALE_FIX.labelResource(), FixStatus.WAITING.labelResource())
        assertNotEquals(FixStatus.OUTSIDE_COVERAGE.labelResource(), FixStatus.TRACKING.labelResource())
        assertEquals(R.string.live_issue_provider_disabled, LiveIssue.PROVIDER_DISABLED.labelResource())
    }
}
