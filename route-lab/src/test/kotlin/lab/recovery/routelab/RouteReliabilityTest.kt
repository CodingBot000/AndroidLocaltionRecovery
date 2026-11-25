package lab.recovery.routelab

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import lab.recovery.location.EngineInput
import lab.recovery.location.Reason
import lab.recovery.simulation.ScenarioRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class RouteReliabilityTest {
    @Test(timeout = 15_000)
    fun N01_replayDepartureMakesOneReadOnlyHttpRequestAndStartsNewEngine() = lab { server, transport ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response(readQuery(request))
        }
        val coordinator = coordinator(this, transport)
        val oldEpoch = coordinator.state.value.engineEpoch
        val ordered = (scenario.observations.map { EngineInput.Observation(it) } +
            (0L..scenario.durationMs step 200).map { EngineInput.Tick(it) }).sortedBy(EngineInput::timeMs)
        val departure = ordered.first { input ->
            coordinator.step(input)!!.events.any { it.reason == Reason.REROUTE_RECOMMENDED }
        }
        val committed = coordinator.terminal()
        assertEquals(RoutePhase.SUCCEEDED, committed.phase)
        assertEquals(2, committed.version)
        assertEquals(oldEpoch + 1, committed.engineEpoch)
        val recorded = take(server)
        val query = readQuery(recorded)
        assertEquals("POST", recorded.method)
        assertEquals("/route/lookup", recorded.path)
        assertEquals("127.0.0.1:${server.port}", recorded.getHeader("Host"))
        assertEquals("synthetic-ol13", query.sessionId)
        assertEquals("O05", query.mapId)
        assertEquals(scenario.map?.checksum, query.mapChecksum)
        assertEquals(1, query.baseRouteVersion)
        assertEquals(scenario.roads.plannedRoute.last(), query.destinationEdgeId)
        assertEquals(alternatives.getValue(query.startEdgeId), coordinator.planEdges())
        assertEquals(1, server.requestCount)
        val fresh = coordinator.step(EngineInput.Tick(departure.timeMs + 200))!!.snapshot
        assertNull(fresh.lastObservation)
        assertNull(fresh.position)
    }

    @Test(timeout = 15_000)
    fun N02_delayedHeadersAndBodyTimeoutAndKeepPlan() = lab(HttpTimeouts(100, 80, 100, 220)) { server, transport ->
        val coordinator = coordinator(this, transport, RetryPolicy(maximumAttempts = 1))
        server.enqueue(successForFirst(2).setHeadersDelay(250, MILLISECONDS))
        coordinator.requestFrom(startA)
        assertEquals(RouteError.TIMEOUT, coordinator.terminal().failure)
        assertEquals(scenario.roads.plannedRoute, coordinator.planEdges())
        server.enqueue(successForFirst(3).setBodyDelay(250, MILLISECONDS))
        coordinator.requestFrom(startA)
        assertEquals(RouteError.TIMEOUT, coordinator.terminal().failure)
        assertEquals(2, server.requestCount)
        awaitIdle(transport)
    }

    @Test(timeout = 15_000)
    fun N03_disconnectAndPartialBodyRetryOnlyWithinAttemptLimit() = lab { server, transport ->
        val coordinator = coordinator(this, transport, RetryPolicy(maximumAttempts = 2, baseBackoffMs = 5, jitterMs = { 0 }))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("x".repeat(1_024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        coordinator.requestFrom(startA)
        assertEquals(RouteError.IO, coordinator.terminal().failure)
        assertEquals(2, coordinator.state.value.attempts)
        assertEquals(2, server.requestCount)
        assertEquals(scenario.roads.plannedRoute, coordinator.planEdges())
        awaitIdle(transport)
    }

    @Test(timeout = 15_000)
    fun N04_503WithRetryAfterZeroIsRetriedByCoordinatorOnce() = lab { server, transport ->
        val coordinator = coordinator(this, transport, RetryPolicy(baseBackoffMs = 10, jitterMs = { 0 }))
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        server.enqueue(successForFirst(2))
        coordinator.requestFrom(startA)
        assertEquals(RoutePhase.SUCCEEDED, coordinator.terminal().phase)
        assertEquals(2, coordinator.state.value.attempts)
        assertEquals(2, server.requestCount)
        assertEquals(readQuery(take(server)), readQuery(take(server)))
        assertEquals(2, coordinator.state.value.engineEpoch)
    }

    @Test(timeout = 15_000)
    fun N05_429RetryAfterIsObservedAndCancellationStopsTheRetry() = lab { server, transport ->
        val coordinator = coordinator(this, transport)
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
        coordinator.requestFrom(startA)
        val backoff = withTimeout(4_000) { coordinator.state.first { it.phase == RoutePhase.BACKOFF } }
        assertEquals(1_000L, backoff.backoffMs)
        coordinator.stop()
        delay(1_050)
        assertEquals(1, server.requestCount)
        assertEquals(RoutePhase.CANCELLED, coordinator.state.value.phase)
        awaitIdle(transport)
    }

    @Test(timeout = 15_000)
    fun N06_permanentStatusesAndRedirectAreNotRetried() = lab { server, transport ->
        val coordinator = coordinator(this, transport)
        for (status in listOf(400, 401, 403, 404, 307)) {
            server.enqueue(MockResponse().setResponseCode(status).addHeader("Location", "http://192.0.2.1/blocked"))
            coordinator.requestFrom(startA)
            val result = coordinator.terminal()
            assertEquals(RouteError.HTTP_STATUS, result.failure)
            assertEquals(status, result.httpStatus)
            assertEquals(1, result.attempts)
            assertEquals(scenario.roads.plannedRoute, coordinator.planEdges())
        }
        assertEquals(5, server.requestCount)
    }

    @Test(timeout = 15_000)
    fun N07_malformedEmptyWrongTypesDeepAndOversizedResponsesFailOnce() = lab { server, transport ->
        val coordinator = coordinator(this, transport)
        val bodies = listOf(MockResponse().setBody("{bad"), MockResponse(),
            MockResponse().setBody("""{"schemaVersion":"1"}"""),
            MockResponse().setBody("""{"routeEdgeIds":${"[".repeat(70)}"nested"}"""),
            MockResponse().setChunkedBody("x".repeat(maxResponseBytes.toInt() + 1), 1024),
            MockResponse().setBody("x".repeat(maxResponseBytes.toInt() + 1)))
        for (body in bodies) {
            server.enqueue(body)
            coordinator.requestFrom(startA)
            assertEquals(RouteError.SCHEMA, coordinator.terminal().failure)
            assertEquals(1, coordinator.state.value.attempts)
            assertEquals(scenario.roads.plannedRoute, coordinator.planEdges())
        }
        assertEquals(bodies.size, server.requestCount)
        awaitIdle(transport)
    }

    @Test(timeout = 15_000)
    fun N08_wrongIdentityVersionDirectionAndTransitionsCannotCommit() = lab { server, transport ->
        val coordinator = coordinator(this, transport)
        val wrongDestination = alternatives.getValue(startA).dropLast(1)
        val reversedMidpoint = alternatives.getValue(startA).toMutableList().apply {
            val edge = this[1]
            this[1] = if (edge.endsWith("_F")) edge.removeSuffix("_F") + "_R" else edge.removeSuffix("_R") + "_F"
        }
        val forbidden = scenario.roads.network.edges.values.first { edge ->
            edge.from == scenario.roads.network.edges.getValue(startA).to &&
                !scenario.roads.network.connected(startA, edge.id)
        }.id
        val invalid: List<(RouteResponse) -> RouteResponse> = listOf(
            { it.copy(requestId = "different") }, { it.copy(mapId = "O06") },
            { it.copy(mapChecksum = "0".repeat(64)) }, { it.copy(schemaVersion = 2) },
            { it.copy(baseRouteVersion = 2) }, { it.copy(newRouteVersion = 3) },
            { it.copy(routeEdgeIds = emptyList()) }, { it.copy(routeEdgeIds = wrongDestination) },
            { it.copy(routeEdgeIds = listOf(startA, "unknown", it.routeEdgeIds.last())) },
            { it.copy(routeEdgeIds = listOf(startA, it.routeEdgeIds.last())) },
            { it.copy(routeEdgeIds = reversedMidpoint) },
            { it.copy(routeEdgeIds = listOf(startA, forbidden, it.routeEdgeIds.last())) },
        )
        for ((index, modify) in invalid.withIndex()) {
            val query = query("request-${index + 2}", startA)
            server.enqueue(json(modify(validResponse(query))))
            coordinator.requestFrom(startA)
            val result = coordinator.terminal()
            assertEquals("case ${index + 1}", RouteError.SEMANTIC, result.failure)
            assertEquals(1, result.version)
            assertEquals(1, result.attempts)
            assertEquals(scenario.roads.plannedRoute, coordinator.planEdges())
        }
        assertEquals(invalid.size, server.requestCount)
    }

    @Test(timeout = 15_000)
    fun N09_duplicateDepartureCollapsesToOneInFlightHttpRequest() = lab { server, transport ->
        val coordinator = coordinator(this, transport)
        server.enqueue(successForFirst(2).setHeadersDelay(300, MILLISECONDS))
        repeat(20) { coordinator.requestFrom(startA) }
        assertEquals(RoutePhase.SUCCEEDED, coordinator.terminal().phase)
        assertEquals(1, server.requestCount)
        awaitIdle(transport)
    }

    @Test(timeout = 15_000)
    fun N10_delayedHttpAIsCancelledAndOnlyBCommits() = lab { server, transport ->
        val coordinator = coordinator(this, transport)
        server.enqueue(successForFirst(2).setHeadersDelay(400, MILLISECONDS))
        server.enqueue(response(query("request-3", startB)))
        coordinator.requestFrom(startA)
        assertEquals(startA, readQuery(take(server)).startEdgeId)
        coordinator.requestFrom(startB)
        assertEquals(RoutePhase.SUCCEEDED, coordinator.terminal().phase)
        assertEquals(startB, readQuery(take(server)).startEdgeId)
        assertEquals(alternatives.getValue(startB), coordinator.planEdges())
        assertEquals(2, server.requestCount)
        awaitIdle(transport)
    }

    @Test
    fun N10_noncancellableCallbackFromAExercisesGenerationGuardAfterB() = runTest {
        val fake = LateTransport()
        val coordinator = coordinator(this, fake)
        coordinator.requestFrom(startA)
        runCurrent()
        coordinator.requestFrom(startB)
        runCurrent()
        val pending = coordinator.state.value
        fake.reply(0)
        runCurrent()
        val afterLateA = coordinator.state.value
        val edgesAfterLateA = coordinator.planEdges()
        fake.reply(1)
        runCurrent()
        assertEquals(pending.copy(staleResponses = 1), afterLateA)
        assertEquals(scenario.roads.plannedRoute, edgesAfterLateA)
        assertEquals(RoutePhase.SUCCEEDED, coordinator.state.value.phase)
        assertEquals(alternatives.getValue(startB), coordinator.planEdges())
    }

    @Test(timeout = 15_000)
    fun N11_httpResetStopAndMapChangeCancelWithoutApplying() = lab { server, transport ->
        val coordinator = coordinator(this, transport)
        val replacement = repository.readOsm("OL01")
        val interruptions = listOf(
            { coordinator.reset("synthetic-reset", "O05", checkNotNull(scenario.map?.checksum), scenario.roads) },
            { coordinator.stop() },
            { coordinator.reset("synthetic-other-map", "O01", checkNotNull(replacement.map?.checksum), replacement.roads) },
        )
        for ((index, interrupt) in interruptions.withIndex()) {
            coordinator.reset("synthetic-$index", "O05", checkNotNull(scenario.map?.checksum), scenario.roads)
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(350, MILLISECONDS))
            coordinator.requestFrom(startA)
            take(server)
            interrupt()
            awaitIdle(transport)
            assertEquals(1, coordinator.state.value.version)
            assertEquals(index + 1, server.requestCount)
        }
        assertEquals(replacement.roads.plannedRoute, coordinator.planEdges())
    }

    @Test fun N11_fakeCallbackAfterMapChangeStopAndResetIsStale() = runTest {
        val fake = LateTransport()
        val coordinator = coordinator(this, fake)
        repeat(3) { index ->
            coordinator.reset("synthetic-$index", "O05", checkNotNull(scenario.map?.checksum), scenario.roads)
            coordinator.requestFrom(startA)
            runCurrent()
            when (index) {
                0 -> coordinator.stop()
                1 -> coordinator.reset("synthetic-new", "O05", checkNotNull(scenario.map?.checksum), scenario.roads)
                2 -> {
                    val other = repository.readOsm("OL01")
                    coordinator.reset("synthetic-other", "O01", checkNotNull(other.map?.checksum), other.roads)
                }
            }
            val before = coordinator.state.value
            fake.reply(index)
            runCurrent()
            assertEquals(before.copy(staleResponses = before.staleResponses + 1), coordinator.state.value)
        }
    }

    @Test fun N12_virtualBackoffDeadlineAndCancellation() = runTest {
        var calls = 0
        val unavailable = RouteTransport {
            calls++
            TransportResult.Failure(RouteError.HTTP_STATUS, 503, retryAfterMs = 200, retryable = true)
        }
        val coordinator = coordinator(this, unavailable, RetryPolicy(budgetMs = 150, jitterMs = { 0 }))
        coordinator.requestFrom(startA)
        runCurrent()
        assertEquals(250L, coordinator.state.value.backoffMs)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(RouteError.BUDGET, coordinator.state.value.failure)
        assertEquals(1, calls)
        assertEquals(scenario.roads.plannedRoute, coordinator.planEdges())

        coordinator.requestFrom(startA)
        runCurrent()
        coordinator.stop()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(RoutePhase.CANCELLED, coordinator.state.value.phase)
        assertEquals(2, calls)
    }

    @Test(timeout = 15_000)
    fun N12_actualCallIsCancelledOnTotalDeadlineAndSocketIsReleased() = lab { server, transport ->
        val coordinator = coordinator(this, transport, RetryPolicy(budgetMs = 230))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        coordinator.requestFrom(startA)
        val result = coordinator.terminal()
        assertEquals(RouteError.BUDGET, result.failure)
        assertEquals(1, result.attempts)
        assertEquals(1, server.requestCount)
        awaitIdle(transport)
    }

    @Test(timeout = 15_000)
    fun N13_recoversOnlyAfterExplicitRequestAndPreservesLastValidPlan() = lab { server, transport ->
        val coordinator = coordinator(this, transport, RetryPolicy(baseBackoffMs = 5, jitterMs = { 0 }))
        server.enqueue(successForFirst(2))
        coordinator.requestFrom(startA)
        assertEquals(2, coordinator.terminal().version)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(504)) }
        coordinator.requestFrom(startB)
        val failed = coordinator.terminal()
        assertEquals(3, failed.attempts)
        assertEquals(2, failed.version)
        assertEquals(alternatives.getValue(startA), coordinator.planEdges())
        assertEquals(4, server.requestCount)
        server.enqueue(response(query("request-4", startB, 2)))
        coordinator.requestFrom(startB)
        val ended = coordinator.terminal()
        assertEquals(3, ended.version)
        assertEquals(alternatives.getValue(startB), coordinator.planEdges())
        assertEquals(5, server.requestCount)
        awaitIdle(transport)
    }

    @Test(timeout = 15_000)
    fun twentyHttpCancelCyclesReleaseCallsAndJobs() = lab { server, transport ->
        val coordinator = coordinator(this, transport)
        repeat(20) { cycle ->
            coordinator.reset("synthetic-$cycle", "O05", checkNotNull(scenario.map?.checksum), scenario.roads)
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(200, MILLISECONDS))
            coordinator.requestFrom(startA)
            take(server)
            coordinator.stop()
            awaitIdle(transport)
        }
        assertEquals(20, server.requestCount)
    }

    private fun coordinator(scope: CoroutineScope, transport: RouteTransport, retry: RetryPolicy = RetryPolicy()) =
        RouteCoordinator(scope, transport, retry).apply {
            reset("synthetic-ol13", "O05", checkNotNull(scenario.map?.checksum), scenario.roads)
        }

    private fun lab(
        timeouts: HttpTimeouts = HttpTimeouts(),
        test: suspend CoroutineScope.(MockWebServer, LoopbackRouteTransport) -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            LoopbackRouteTransport(server.port, timeouts).use { transport ->
                withTimeout(11_000) { test(server, transport) }
            }
        }
    }

    private suspend fun take(server: MockWebServer): RecordedRequest = withContext(Dispatchers.IO) {
        checkNotNull(server.takeRequest(2_000, MILLISECONDS))
    }

    private suspend fun RouteCoordinator.terminal(): RouteState = withTimeout(5_000) {
        state.first { it.phase in setOf(RoutePhase.SUCCEEDED, RoutePhase.FAILED, RoutePhase.CANCELLED) }
    }

    private suspend fun awaitIdle(transport: LoopbackRouteTransport) = withTimeout(3_000) {
        while (transport.runningCalls != 0 || transport.queuedCalls != 0) delay(10)
    }

    private class LateTransport : RouteTransport {
        private val requests = mutableListOf<Pair<RouteRequest, Continuation<TransportResult>>>()
        override suspend fun request(query: RouteRequest): TransportResult = suspendCoroutine { requests += query to it }
        fun reply(index: Int) {
            val (query, callback) = requests[index]
            callback.resume(TransportResult.Response(validResponse(query)))
        }
    }

    private companion object {
        val repository = ScenarioRepository { File("fixtures/$it").inputStream() }
        val scenario by lazy { repository.readOsm("OL13") }
        val alternatives by lazy {
            RouteReliabilityTest::class.java.getResourceAsStream("/o05-loopback-routes.json")!!.bufferedReader().use { reader ->
                JsonParser.parseReader(reader).asJsonObject.entrySet().associate { (key, list) -> key to list.asJsonArray.map { it.asString } }
            }
        }
        val startA by lazy { alternatives.keys.first() }
        val startB by lazy { alternatives.keys.elementAt(1) }
        fun query(id: String, start: String, version: Int = 1) = RouteRequest("synthetic-ol13", id, "O05",
            checkNotNull(scenario.map?.checksum), version, start, scenario.roads.plannedRoute.last())
        fun readQuery(request: RecordedRequest): RouteRequest = Gson().fromJson(request.body.clone().readUtf8(), RouteRequest::class.java)
        fun validResponse(query: RouteRequest) = RouteResponse(query.requestId, query.mapId, query.mapChecksum,
            query.baseRouteVersion, query.baseRouteVersion + 1, alternatives.getValue(query.startEdgeId))
        fun response(query: RouteRequest) = json(validResponse(query))
        fun successForFirst(sequence: Int) = response(query("request-$sequence", startA))
        fun json(value: RouteResponse) = MockResponse().setHeader("Content-Type", "application/json").setBody(Gson().toJson(value))
    }
}
