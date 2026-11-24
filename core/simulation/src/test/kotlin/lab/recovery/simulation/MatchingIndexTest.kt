package lab.recovery.simulation

import lab.recovery.location.MapMatcher
import lab.recovery.location.TrackingSettings
import lab.recovery.map.Point2D
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.system.measureNanoTime

class MatchingIndexTest {
    private val repository = ScenarioRepository { file ->
        File(checkNotNull(System.getProperty("fixtureRoot")), file).inputStream()
    }

    @Test fun realRoadQueriesAndMatchResultsEqualTheFullScan() {
        val profile = System.getProperty("matchingProfile")?.let(::File)
        val rows = mutableListOf<String>()
        for (id in listOf("OL03", "ODEMO_LONG")) {
            val scenario = repository.readOsm(id)
            val settings = TrackingSettings()
            val scanned = MapMatcher(scenario.roads, settings, useSpatialIndex = false)
            lateinit var indexed: MapMatcher
            val heapBefore = if (profile == null) 0 else liveHeap()
            val indexNs = measureNanoTime { indexed = MapMatcher(scenario.roads, settings) }
            val heapAfter = if (profile == null) 0 else liveHeap()
            var previous: String? = null
            val priors = scenario.observations.map { gps ->
                val prior = previous
                assertEquals(scanned.candidateRoads(gps.point), indexed.candidateRoads(gps.point))
                val expected = scanned.match(gps, prior)
                assertEquals(expected, indexed.match(gps, prior))
                previous = expected.confirmed?.roadId ?: previous
                prior
            }
            val road = scenario.roads.roads.maxBy { it.length }
            val bounds = road.bounds
            val edgeCases = listOf(Point2D(-120.0, -120.0), Point2D(0.0, 120.0),
                Point2D(bounds.minX - settings.searchRadius, bounds.minY),
                Point2D(bounds.maxX + settings.searchRadius, bounds.maxY),
                Point2D(-1e100, 1e100))
            for (point in edgeCases) assertEquals(scanned.candidateRoads(point), indexed.candidateRoads(point))

            if (profile != null) {
                fun sample(matcher: MapMatcher): Double {
                    val nanos = measureNanoTime {
                        scenario.observations.forEachIndexed { i, observation ->
                            matcher.match(observation, priors[i])
                        }
                    }
                    return nanos.toDouble() / scenario.observations.size / 1000
                }
                repeat(5) { sample(scanned); sample(indexed) }
                val raw = mutableListOf<Double>()
                val grid = mutableListOf<Double>()
                repeat(7) { iteration ->
                    if (iteration % 2 == 0) { raw += sample(scanned); grid += sample(indexed) }
                    else { grid += sample(indexed); raw += sample(scanned) }
                }
                val count = scenario.observations.sumOf { indexed.candidateRoads(it.point).size }
                rows += listOf(scenario.map!!.id, scenario.roads.roads.size, scenario.observations.size,
                    count.toDouble() / scenario.observations.size, indexNs / 1e6, heapAfter - heapBefore,
                    raw.sorted()[3], grid.sorted()[3], raw.joinToString("|"), grid.joinToString("|")).joinToString(",")
            }
        }
        if (profile != null) {
            profile.parentFile?.mkdirs()
            profile.writeText("map,roads,observations,meanCandidates,buildMs,liveHeapDeltaBytes,scanMedianUs,gridMedianUs,scanSamplesUs,gridSamplesUs\n" +
                rows.joinToString("\n", postfix = "\n"))
        }
    }

    private fun liveHeap(): Long {
        System.gc()
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }
}
