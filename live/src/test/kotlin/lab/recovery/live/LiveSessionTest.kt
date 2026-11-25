package lab.recovery.live

import lab.recovery.location.SignalState
import lab.recovery.map.OsmProvenance
import lab.recovery.map.Point2D
import lab.recovery.simulation.ScenarioRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiveSessionTest {
    private val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "fixtures").isDirectory }
    private val repository = ScenarioRepository { File(root, "fixtures/$it").inputStream() }
    private val controls = File(root, "live/src/main/assets/projection-controls.json").reader().use(ProjectionControl::read)
    private val start = 10_000_000_000L

    @Test fun allMapsMatchPyprojCornersCentersAndRoads() {
        assertEquals(repository.osmCatalog.maps.keys, controls.keys)
        var worst = 0.0
        controls.forEach { (id, control) ->
            val map = repository.readOsmMap(id)
            val projection = checkNotNull(OsmProjection.verified(map, control))
            assertEquals(6, control.samples.size)
            control.samples.forEach {
                val point = projection.local(it.longitude, it.latitude)
                worst = maxOf(worst, it.expected.distance(point))
                assertNotNull("$id coverage", projection.inCoverage(it.longitude, it.latitude))
            }
            assertNull(projection.inCoverage(control.bbox[0] - .0001, control.bbox[1]))
            assertNull(projection.inCoverage(control.bbox[1], control.bbox[0]))
        }
        println("Pyproj controls=60, maximum absolute error in metres=$worst")
        assertTrue(worst <= .5)
    }

    @Test fun checksumCrsAndProjectionMismatchFailClosed() {
        val map = repository.readOsmMap("O01")
        val control = controls.getValue("O01")
        val osm: OsmProvenance = checkNotNull(map.osm)
        assertNull(OsmProjection.verified(map.copy(checksum = "bad"), control))
        assertNull(OsmProjection.verified(map.copy(osm = osm.copy(projection = "EPSG:4326")), control))
        assertNull(OsmProjection.verified(map.copy(osm = osm.copy(origin = Point2D(0.0, 0.0))), control))
    }

    @Test fun coarseOutsideMissingPlanAndLowQualityNeverHaveEngineState() {
        val map = repository.readOsmMap("O01")
        val projection = checkNotNull(OsmProjection.verified(map, controls["O01"]))
        val plan = repository.readOsmPlan("OL01", map)
        val coarse = LiveSession(start, Precision.COARSE, projection, plan)
        val fine = LiveSession(start, Precision.FINE, projection, null)
        val unavailable = LiveSession(start, Precision.FINE, null, plan)
        assertEquals(FixStatus.COARSE_ONLY, coarse.onFix(fix()).status)
        assertNull(coarse.tick(start + 4_000_000_000L).tracking)
        assertEquals(FixStatus.NO_ROUTE, fine.onFix(fix()).status)
        assertEquals(FixStatus.OUTSIDE_COVERAGE, fine.onFix(fix(2).copy(longitude = 128.0)).status)
        assertEquals(FixStatus.LOW_QUALITY, fine.onFix(fix(3).copy(accuracy = null)).status)
        assertEquals(FixStatus.LOW_QUALITY, fine.onFix(fix(4).copy(accuracy = 21.0)).status)
        assertEquals(FixStatus.PROJECTION_UNAVAILABLE, unavailable.onFix(fix()).status)
        assertEquals(FixStatus.INVALID_FIX, fine.onFix(fix(5).copy(latitude = Double.NaN)).status)
        assertEquals(FixStatus.INVALID_FIX, fine.onFix(fix(6).copy(speed = -1.0)).status)
        assertEquals(FixStatus.INVALID_FIX, fine.onFix(fix(7).copy(bearing = 360.0)).status)
        assertEquals(FixStatus.INVALID_FIX, fine.onFix(fix(8).copy(accuracy = Double.NaN)).status)
    }

    @Test fun monotonicFixTimeAndNewCoverageEpochs() {
        val map = repository.readOsmMap("O01")
        val session = LiveSession(start, Precision.FINE, OsmProjection.verified(map, controls["O01"]),
            repository.readOsmPlan("OL01", map))
        assertEquals(FixStatus.STALE_FIX, session.onFix(fix().copy(measuredNs = start - 1)).status)
        assertEquals(FixStatus.STALE_FIX, session.onFix(fix().copy(receivedNs = start)).status)
        assertEquals(FixStatus.STALE_FIX, session.onFix(fix().copy(receivedNs = start + 8_000_000_000L)).status)
        val first = session.onFix(fix())
        assertEquals(FixStatus.TRACKING, first.status)
        assertEquals(1000L, first.tracking?.lastObservation?.measuredAtMs)
        assertEquals(1050L, first.tracking?.lastObservation?.receivedAtMs)
        assertNull(first.tracking?.lastObservation?.speedMps)
        assertNull(first.tracking?.lastObservation?.headingDegrees)
        assertEquals(first, session.onFix(fix().copy(receivedNs = start + 1_200_000_000L)))
        assertEquals(SignalState.LOST, session.tick(start + 4_500_000_000L).tracking?.signal)
        val outside = session.onFix(fix(5).copy(longitude = 128.0))
        assertNull(outside.point)
        assertNull(outside.tracking)
        val reentry = session.onFix(fix(6))
        assertEquals(first.trackingEpoch + 1, reentry.trackingEpoch)
        assertEquals(SignalState.TRACKING, reentry.tracking?.signal)
        val mock = session.onFix(fix(7).copy(mock = true))
        assertEquals(reentry.trackingEpoch + 1, mock.trackingEpoch)
        assertEquals(true, mock.fix?.mock)
        val otherMap = repository.readOsmMap("O10")
        val switched = LiveSession(start, Precision.FINE, OsmProjection.verified(otherMap, controls["O10"]), null)
        assertEquals(FixStatus.OUTSIDE_COVERAGE, switched.onFix(fix()).status)
    }

    @Test fun fakePermissionsProviderErrorsAndLateCallbacksAcrossTwentySessions() {
        val source = FakeLocationSource()
        var processed = 0
        val issues = mutableListOf<LiveIssue>()
        repeat(20) {
            val lease = LocationSubscription(source, Precision.FINE)
            lease.open({ processed++ }, issues::add)
            lease.open({ error("Duplicate registration") }, issues::add)
            source.fix(fix())
            source.problem(LiveIssue.PROVIDER_TEMPORARY)
            source.permission = Precision.COARSE
            assertEquals(LiveIssue.PERMISSION_CHANGED, lease.currentIssue())
            source.permission = Precision.FINE
            source.availability = LiveIssue.PROVIDER_DISABLED
            assertEquals(LiveIssue.PROVIDER_DISABLED, lease.currentIssue())
            source.availability = null
            lease.close()
            lease.close()
            source.fix(fix())
            source.problem(LiveIssue.PROVIDER_DISABLED)
        }
        assertEquals(20, processed)
        assertEquals(20, source.registrations)
        assertEquals(20, source.removals)
        assertEquals(List(20) { LiveIssue.PROVIDER_TEMPORARY }, issues)

        source.availability = LiveIssue.PROVIDER_MISSING
        val unavailable = LocationSubscription(source, Precision.FINE)
        unavailable.open({ fail("Callback") }, issues::add)
        unavailable.close()
        assertEquals(LiveIssue.PROVIDER_MISSING, issues.last())
        assertEquals(20, source.registrations)
        assertEquals(20, source.removals)
    }

    private fun fix(second: Long = 1) = RawFix(37.5275, 126.922, 4.0, null, null,
        start + second * 1_000_000_000L, start + second * 1_000_000_000L + 50_000_000L, "gps", false)

    private class FakeLocationSource : LocationSource {
        var permission = Precision.FINE
        var availability: LiveIssue? = null
        var registrations = 0
        var removals = 0
        lateinit var fix: (RawFix) -> Unit
        lateinit var problem: (LiveIssue) -> Unit
        override fun precision() = permission
        override fun issue(precision: Precision) = availability
        override fun register(precision: Precision, fix: (RawFix) -> Unit, issue: (LiveIssue) -> Unit) {
            ++registrations
            this.fix = fix
            problem = issue
        }
        override fun unregister() { ++removals }
    }
}
