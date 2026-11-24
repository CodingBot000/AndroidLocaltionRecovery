package lab.recovery.map

import org.junit.Assert.*
import org.junit.Test

class RoadNetworkTest {
    @Test fun levelsAndExplicitTurnsControlConnectivity() {
        val west = RoadNode("west", Point2D(-20.0, 0.0))
        val east = RoadNode("east", Point2D(20.0, 0.0))
        val lower = RoadNode("lower", Point2D(0.0, 0.0))
        val upper = RoadNode("upper", lower.point, 1)
        val north = RoadNode("north", Point2D(0.0, 20.0), 1)
        fun edge(id: String, from: RoadNode, to: RoadNode) =
            RoadEdge(Road(id, listOf(from.point, to.point)), from.id, to.id)
        val nodes = listOf(west, east, lower, upper, north)
        val edges = listOf(edge("in", west, lower), edge("out", lower, east),
            edge("reverse", lower, west), edge("over", upper, north))
        val network = RoadNetwork(nodes, edges, mapOf("in" to listOf("out")))
        assertTrue(network.connected("in", "out"))
        assertFalse(network.connected("in", "reverse"))
        assertFalse(network.connected("in", "over"))
        assertThrows(IllegalArgumentException::class.java) { RoadNetwork(nodes, edges, mapOf("in" to listOf("over"))) }
        assertThrows(IllegalArgumentException::class.java) { RoutePlan(network, listOf("in", "over")) }
        val route = RoutePlan(network, listOf("in", "out"))
        assertEquals(40.0, route.length, 0.0)
        assertEquals(west.point, route.at(-1.0).point)
        assertEquals("out", route.at(20.0).edgeId)
        assertEquals(east.point, route.at(40.0).point)
        assertEquals(90.0, route.at(40.0).heading, 0.0)
    }

    @Test fun projectionAndHeadingAtPolylineBoundary() {
        val start = RoadNode("a", Point2D(0.0, 0.0))
        val turn = Point2D(0.0, 10.0)
        val end = RoadNode("b", Point2D(-10.0, 10.0))
        val road = Road("bend", listOf(start.point, turn, end.point))
        val network = RoadNetwork(listOf(start, end), listOf(RoadEdge(road, start.id, end.id)), emptyMap())
        val route = RoutePlan(network, listOf("bend"))
        assertEquals(turn, road.segments.first().project(Point2D(4.0, 20.0)))
        assertEquals(15.0, road.offset(road.segments.last(), Point2D(-5.0, 10.0)), 0.0)
        assertEquals(0.0, route.at(9.0).heading, 0.0)
        assertEquals(270.0, route.at(10.0).heading, 0.0)
    }
}
