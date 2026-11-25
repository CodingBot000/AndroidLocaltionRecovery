package lab.recovery.routelab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lab.recovery.location.EngineInput
import lab.recovery.location.EngineUpdate
import lab.recovery.location.Reason
import lab.recovery.location.TrackingEngine
import lab.recovery.map.RoadGraph
import lab.recovery.map.RoutePlan
import kotlin.random.Random

internal enum class RoutePhase { IDLE, REQUESTING, BACKOFF, SUCCEEDED, FAILED, CANCELLED }

internal data class RouteState(
    val phase: RoutePhase = RoutePhase.IDLE,
    val version: Int = 1,
    val generation: Long = 0,
    val requestId: String? = null,
    val attempts: Int = 0,
    val failure: RouteError? = null,
    val httpStatus: Int? = null,
    val backoffMs: Long? = null,
    val engineEpoch: Long = 0,
    val staleResponses: Long = 0,
)

internal data class RetryPolicy(
    val budgetMs: Long = 12_000,
    val maximumAttempts: Int = 3,
    val baseBackoffMs: Long = 250,
    val maxBackoffMs: Long = 3_000,
    val jitterMs: () -> Long = { Random.nextLong(100) },
) {
    init {
        require(budgetMs > 0 && maximumAttempts in 1..3 && baseBackoffMs in 1..10_000 &&
            maxBackoffMs in baseBackoffMs..10_000)
    }

    fun backoff(attempt: Int, after: Long?) = maxOf(
        baseBackoffMs * (1L shl (attempt - 1)) + jitterMs().coerceIn(0, 99),
        after ?: 0,
    )
}

/**
 * Standalone synthetic engine session. Eval/replay and Live never instantiate it. The active plan,
 * engine, request generation and UI-facing state commit under the same lock.
 */
internal class RouteCoordinator(
    private val scope: CoroutineScope,
    private val transport: RouteTransport,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    private data class Session(
        val id: String,
        val mapId: String,
        val checksum: String,
        val base: RoadGraph,
        var plan: RoadGraph = base,
        var engine: TrackingEngine,
    )

    private class Flight(val query: RouteRequest, val generation: Long) {
        lateinit var job: Job
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow(RouteState())
    val state = mutableState.asStateFlow()
    private var session: Session? = null
    private var flight: Flight? = null
    private var sequence = 0L
    private var epoch = 0L

    fun reset(syntheticId: String, mapId: String, checksum: String, base: RoadGraph) = synchronized(lock) {
        invalidate()
        val run = ++epoch
        session = Session(syntheticId, mapId, checksum, base, engine = TrackingEngine(base, runId = run))
        mutableState.value = RouteState(generation = sequence, engineEpoch = run)
    }

    fun stop() = synchronized(lock) {
        invalidate()
        session = null
        mutableState.value = state.value.copy(phase = RoutePhase.CANCELLED, generation = sequence, backoffMs = null)
    }

    fun planEdges(): List<String> = synchronized(lock) { session?.plan?.plannedRoute.orEmpty() }

    fun step(input: EngineInput): EngineUpdate? = synchronized(lock) {
        val current = session ?: return null
        val update = current.engine.step(input)
        if (update.events.any { it.reason == Reason.REROUTE_RECOMMENDED }) {
            update.snapshot.roadId?.let { requestFrom(it) }
        }
        update
    }

    fun requestFrom(startEdge: String) {
        val new = synchronized(lock) {
            val current = session ?: return
            val currentState = state.value
            if (startEdge !in current.base.network.edges || currentState.version == Int.MAX_VALUE) return
            if (flight?.query?.startEdgeId == startEdge) return
            invalidate()
            val request = RouteRequest(current.id, "request-$sequence", current.mapId, current.checksum,
                currentState.version, startEdge, current.base.plannedRoute.last())
            Flight(request, sequence).also { run ->
                mutableState.value = currentState.copy(phase = RoutePhase.REQUESTING, generation = sequence,
                    requestId = request.requestId, attempts = 0, failure = null, httpStatus = null, backoffMs = null)
                flight = run
                run.job = scope.launch(start = CoroutineStart.LAZY) { execute(run) }
                run.job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) synchronized(lock) {
                        if (matches(run)) {
                            flight = null
                            mutableState.value = state.value.copy(phase = RoutePhase.CANCELLED, backoffMs = null)
                        }
                    }
                }
            }
        }
        new.job.start()
    }

    private suspend fun execute(run: Flight) {
        val finished = withTimeoutOrNull(retry.budgetMs) {
            for (attempt in 1..retry.maximumAttempts) {
                if (!publish(run) { it.copy(phase = RoutePhase.REQUESTING, attempts = attempt, backoffMs = null) }) return@withTimeoutOrNull true
                when (val result = transport.request(run.query)) {
                    is TransportResult.Response -> {
                        if (synchronized(lock) { matches(run) }) currentCoroutineContext().ensureActive()
                        accept(run, result.value)
                        return@withTimeoutOrNull true
                    }
                    is TransportResult.Failure -> {
                        currentCoroutineContext().ensureActive()
                        if (!result.retryable || attempt == retry.maximumAttempts) {
                            fail(run, result.reason, result.status)
                            return@withTimeoutOrNull true
                        }
                        val backoff = retry.backoff(attempt, result.retryAfterMs)
                        // Respect Retry-After by declining a retry if the required wait exceeds the lab ceiling.
                        if (backoff > retry.maxBackoffMs) {
                            fail(run, RouteError.BUDGET, result.status)
                            return@withTimeoutOrNull true
                        }
                        if (!publish(run) { it.copy(phase = RoutePhase.BACKOFF, backoffMs = backoff, httpStatus = result.status) }) {
                            return@withTimeoutOrNull true
                        }
                        delay(backoff)
                    }
                }
            }
            true
        }
        if (finished == null) fail(run, RouteError.BUDGET)
    }

    private fun accept(run: Flight, response: RouteResponse) = synchronized(lock) {
        val active = session
        val query = run.query
        if (!matches(run) || active == null || active.id != query.sessionId || state.value.version != query.baseRouteVersion) {
            mutableState.value = state.value.copy(staleResponses = state.value.staleResponses + 1)
            return
        }
        val valid = response.requestId == query.requestId && response.mapId == query.mapId &&
            response.mapChecksum == query.mapChecksum && response.schemaVersion == query.schemaVersion &&
            response.baseRouteVersion == query.baseRouteVersion && response.newRouteVersion == query.baseRouteVersion + 1 &&
            response.routeEdgeIds.size in 1..maxRouteEdges && response.routeEdgeIds.first() == query.startEdgeId &&
            response.routeEdgeIds.last() == query.destinationEdgeId
        val network = active.base.network
        val plan = if (valid) runCatching { RoutePlan(network, response.routeEdgeIds) }.getOrNull() else null
        if (plan == null) {
            fail(run, RouteError.SEMANTIC)
            return
        }
        val graph = RoadGraph(network, plan, segmented = active.base.segmented)
        val next = ++epoch
        active.plan = graph
        active.engine = TrackingEngine(graph, runId = next)
        flight = null
        mutableState.value = state.value.copy(phase = RoutePhase.SUCCEEDED, version = response.newRouteVersion,
            engineEpoch = next, failure = null, httpStatus = null)
    }

    private fun fail(run: Flight, reason: RouteError, status: Int? = null) = synchronized(lock) {
        if (!matches(run)) return
        flight = null
        mutableState.value = state.value.copy(phase = RoutePhase.FAILED, failure = reason, httpStatus = status, backoffMs = null)
    }

    private fun publish(run: Flight, update: (RouteState) -> RouteState): Boolean = synchronized(lock) {
        if (!matches(run)) return false
        mutableState.value = update(state.value)
        true
    }

    private fun matches(run: Flight) = flight === run && state.value.generation == run.generation

    private fun invalidate() {
        ++sequence
        val old = flight
        flight = null
        old?.job?.cancel()
    }
}
