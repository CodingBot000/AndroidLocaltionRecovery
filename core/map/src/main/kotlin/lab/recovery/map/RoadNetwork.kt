package lab.recovery.map

data class RoadNode(val id: String, val point: Point2D, val level: Int = 0) {
    init {
        require(id.isNotBlank() && point.finite)
    }
}

data class RoadEdge(val road: Road, val from: String, val to: String) {
    val id get() = road.id
}

class RoadNetwork(
    nodes: List<RoadNode>,
    edges: List<RoadEdge>,
    transitions: Map<String, List<String>>,
) {
    val nodes = nodes.associateBy(RoadNode::id)
    val edges = edges.associateBy(RoadEdge::id)
    private val transitions = transitions.mapValues { (_, next) -> next.toList() }

    init {
        require(nodes.isNotEmpty() && this.nodes.size == nodes.size)
        require(edges.isNotEmpty() && this.edges.size == edges.size)
        edges.forEach { (road, from, to) ->
            require(this.nodes[from]?.point == road.points.first())
            require(this.nodes[to]?.point == road.points.last())
        }
        this.transitions.forEach { (from, next) ->
            val edge = requireNotNull(this.edges[from])
            require(next.distinct().size == next.size)
            require(next.all { this.edges[it]?.from == edge.to })
        }
    }

    fun connected(from: String, to: String): Boolean = to in transitions[from].orEmpty()
    fun outgoing(from: String): List<RoadEdge> = transitions[from].orEmpty().map(edges::getValue)

    companion object {
        /** Legacy endpoints with exactly equal coordinates shared every allowed departure. */
        fun fromLegacy(roads: List<Road>): RoadNetwork {
            val nodes = roads.flatMap { listOf(it.points.first(), it.points.last()) }.distinct()
                .mapIndexed { index, point -> RoadNode("v1-$index", point) }
            val byPoint = nodes.associateBy(RoadNode::point)
            val edges = roads.map { RoadEdge(it, byPoint.getValue(it.points.first()).id, byPoint.getValue(it.points.last()).id) }
            val departures = edges.groupBy(RoadEdge::from)
            return RoadNetwork(nodes, edges, edges.associate { edge ->
                edge.id to departures[edge.to].orEmpty().map(RoadEdge::id)
            })
        }
    }
}

data class RoutePosition(val point: Point2D, val heading: Double, val edgeId: String)

class RoutePlan(val network: RoadNetwork, edgeIds: List<String>, allowRepeatedEdges: Boolean = false) {
    val edgeIds = edgeIds.toList()
    val roads = this.edgeIds.map { requireNotNull(network.edges[it]).road }
    private val starts = roads.runningFold(0.0) { sum, road -> sum + road.length }
    val length = starts.last()

    init {
        require(roads.isNotEmpty() && length.isFinite())
        require(allowRepeatedEdges || this.edgeIds.distinct().size == this.edgeIds.size)
        require(this.edgeIds.zipWithNext().all { (from, to) -> network.connected(from, to) })
    }

    fun at(distance: Double): RoutePosition {
        require(distance.isFinite())
        val clamped = distance.coerceIn(0.0, length)
        val boundary = starts.binarySearch(clamped)
        val index = (if (boundary >= 0) boundary else -boundary - 2).coerceAtMost(roads.lastIndex)
        val road = roads[index]
        val offset = clamped - starts[index]
        var remaining = offset
        val segment = road.segments.firstOrNull {
            if (remaining < it.length) true else {
                remaining -= it.length
                false
            }
        } ?: road.segments.last()
        return RoutePosition(road.at(offset), segment.heading, road.id)
    }
}

/** Engine-facing view of a network paired with one plan. */
class RoadGraph(val network: RoadNetwork, val route: RoutePlan, val legacy: Boolean = false) {
    private constructor(network: RoadNetwork, plannedRoute: List<String>) :
        this(network, RoutePlan(network, plannedRoute, allowRepeatedEdges = true), legacy = true)

    constructor(roads: List<Road>, plannedRoute: List<String>) : this(RoadNetwork.fromLegacy(roads), plannedRoute)

    init { require(route.network === network) }

    val byId = network.edges.mapValues { it.value.road }
    val roads = byId.values.toList()
    val plannedRoute = route.edgeIds
    fun connected(from: String, to: String) = network.connected(from, to)
    fun outgoing(from: String) = network.outgoing(from).map(RoadEdge::road)
}
