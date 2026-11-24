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
}
