package lab.recovery.simulation

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExtendedScenarioTest {
    private val fixtures = File(checkNotNull(System.getProperty("fixtureRoot")))
    private val repository = ScenarioRepository { File(fixtures, it).inputStream() }

    @Test fun catalogUsesTenDistinctTopologiesAndReusableRoutes() {
        val catalog = repository.catalog
        val entries = catalog.entries.filterNot(CatalogEntry::evaluation)
        assertEquals("DEMO_LONG", catalog.defaultId)
        val required = (1..17).map { "L" + it.toString().padStart(2, '0') }.toSet() + "DEMO_LONG"
        assertTrue(entries.map(CatalogEntry::id).containsAll(required))
        assertEquals(catalog.maps.keys, entries.map(CatalogEntry::mapId).toSet())
        val groups = entries.map { repository.read(it.id) }.groupBy { it.map!!.id }
        val topology = groups.values.map { scenarios ->
            val network = scenarios.first().roads.network
            val transitions = network.edges.values.map { network.outgoing(it.id).size }.sorted()
            val remaining = network.nodes.keys.toMutableSet()
            val components = mutableListOf<Int>()
            val adjacency = network.edges.values.flatMap { listOf(it.from to it.to, it.to to it.from) }.groupBy({ it.first }, { it.second })
            while (remaining.isNotEmpty()) {
                val frontier = ArrayDeque(listOf(remaining.first()))
                var count = 0
                while (frontier.isNotEmpty()) {
                    val node = frontier.removeFirst()
                    if (remaining.remove(node)) {
                        count++
                        frontier.addAll(adjacency[node].orEmpty())
                    }
                }
                components += count
            }
            transitions to components.sorted()
        }
        assertEquals(10, topology.distinct().size)
        assertTrue(groups.values.count { group -> group.map { it.roads.plannedRoute }.distinct().size >= 2 } >= 2)
        assertEquals(catalog.maps.keys, catalog.entries.filter(CatalogEntry::evaluation).map(CatalogEntry::mapId).toSet())
    }

    @Test fun everyCaseHasPhysicalTruthAndIndependentChecks() = runBlocking {
        for (entry in repository.catalog.entries) {
            val scenario = repository.read(entry.id)
            assertTrue(entry.id, scenario.groundTruth.zipWithNext().all { (a, b) ->
                b.timeMs - a.timeMs == 200L && a.position.distance(b.position) <= 8.0
            })
            assertTrue(scenario.groundTruth.all { fix ->
                scenario.roads.byId.getValue(fix.roadId!!).segments.any { it.project(fix.position).distance(fix.position) < 0.01 }
            })
            val result = playToEnd(ReplaySession(scenario))
            assertTrue(entry.id, result.checks.all(RegressionCheck::passed))
            assertTrue(entry.id, result.extended!!.passed)
            assertEquals(0, result.invalidAcceptance.numerator)
            assertEquals(0, result.departureFalsePositives)
            assertEquals(0, result.departureMisses)
        }
    }

    @Test fun registeringAScenarioOnlyRequiresDataAndUnsafeIdsAreRejected() {
        val catalogPath = "extended/catalog.json"
        val newPath = "extended/scenarios/EXTRA.json"
        val catalog = JsonParser.parseString(File(fixtures, catalogPath).readText()).asJsonObject
        val entries = catalog.getAsJsonArray("entries")
        val extra = entries.first().asJsonObject.deepCopy().apply { addProperty("id", "EXTRA") }
        entries.add(extra)
        val scenario = File(fixtures, "extended/scenarios/L01.json").readText().replace("\"scenarioId\":\"L01\"", "\"scenarioId\":\"EXTRA\"")
        fun repositoryWithCatalog() = ScenarioRepository { path ->
            when (path) {
                catalogPath -> catalog.toString().byteInputStream()
                newPath -> scenario.byteInputStream()
                else -> File(fixtures, path).inputStream()
            }
        }
        assertEquals("EXTRA", repositoryWithCatalog().read("EXTRA").id)
        extra.addProperty("id", "../escape")
        assertThrows(InvalidFixture::class.java) { repositoryWithCatalog().catalog }
        extra.addProperty("id", "L01")
        assertThrows(InvalidFixture::class.java) { repositoryWithCatalog().catalog }
    }

    @Test fun longReplayDecisionsAreIndependentOfPlaybackSpeedAndBatching() = runBlocking {
        val scenario = repository.read("DEMO_LONG")
        fun frames() = mutableListOf<ReplayFrame>()
        val baseline = frames()
        val expected = playToEnd(ReplaySession(scenario), onFrame = { baseline += it })
        for (speed in PlaybackSpeeds.values) {
            val captured = frames()
            val actual = playToEnd(ReplaySession(scenario), speed = { speed }, onFrame = { captured += it })
            assertEquals(expected, actual)
            assertEquals(baseline, captured)
        }
        assertEquals(scenario.durationMs, baseline.last().update.snapshot.nowMs)
        assertEquals(1, baseline.flatMap { it.update.events }.count { it.reason == Reason.RUN_COMPLETED })
        assertTrue(baseline.any { it.update.snapshot.source == PositionSource.DEAD_RECKONED })
        assertTrue(baseline.any { it.update.snapshot.source == PositionSource.LAST_KNOWN })
    }
}
