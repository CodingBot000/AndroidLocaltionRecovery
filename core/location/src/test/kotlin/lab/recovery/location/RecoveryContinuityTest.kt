package lab.recovery.location

import lab.recovery.map.Point2D
import lab.recovery.map.Road
import lab.recovery.map.RoadEdge
import lab.recovery.map.RoadGraph
import lab.recovery.map.RoadNetwork
import lab.recovery.map.RoadNode
import lab.recovery.map.RoutePlan
import org.junit.Assert.*
import org.junit.Test

class RecoveryContinuityTest {
    @Test fun allowsSkippedShortEdgesOnlyWithinForwardDistance() {
        val nodes = listOf(0.0, 70.0, 76.0, 180.0).mapIndexed { index, x ->
            RoadNode(index.toString(), Point2D(x, 0.0))
        }
        val edges = nodes.zipWithNext().mapIndexed { index, (from, to) ->
            RoadEdge(Road("e$index", listOf(from.point, to.point)), from.id, to.id)
        }
        val network = RoadNetwork(nodes, edges, mapOf("e0" to listOf("e1"), "e1" to listOf("e2")))
        val graph = RoadGraph(network, RoutePlan(network, edges.map { it.id }))
        val first = MatchCandidate("e0", 0, Point2D(65.0, 0.0), 0.0, 65.0)
        val next = MatchCandidate("e2", 0, Point2D(82.0, 0.0), 0.0, 6.0)
        assertFalse(graph.connected(first.roadId, next.roadId))
        assertTrue(recoveryRoadContinuous(graph, first, next, 17.0))
        assertFalse(recoveryRoadContinuous(graph, first, next, 16.99))
        assertFalse(recoveryRoadContinuous(graph, next, first, 1000.0))
    }

    @Test fun coincidentUnconnectedLevelsAreAmbiguousWithoutMovementEvidence() {
        val lowerStart = RoadNode("lower-start", Point2D(0.0, 0.0))
        val lowerEnd = RoadNode("lower-end", Point2D(180.0, 0.0))
        val upperStart = RoadNode("upper-start", lowerStart.point, 1)
        val upperEnd = RoadNode("upper-end", lowerEnd.point, 1)
        val edges = listOf(
            RoadEdge(Road("lower", listOf(lowerStart.point, lowerEnd.point)), lowerStart.id, lowerEnd.id),
            RoadEdge(Road("upper", listOf(upperStart.point, upperEnd.point)), upperStart.id, upperEnd.id),
        )
        val network = RoadNetwork(listOf(lowerStart, lowerEnd, upperStart, upperEnd), edges, emptyMap())
        val graph = RoadGraph(network, RoutePlan(network, listOf("upper")))
        val observation = LocationObservation(0, "gps", 0, 0, Point2D(65.0, 0.0), 4.0, 8.0, 90.0)
        val matcher = MapMatcher(graph, TrackingSettings())
        assertEquals(Reason.AMBIGUOUS, matcher.match(observation, previousRoad = null).reason)
        assertEquals("upper", matcher.match(observation, previousRoad = "upper").confirmed?.roadId)
    }
}
