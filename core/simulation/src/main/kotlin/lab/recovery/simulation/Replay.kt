package lab.recovery.simulation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import lab.recovery.location.EngineInput
import lab.recovery.location.EngineUpdate
import lab.recovery.location.TrackingEngine
import lab.recovery.location.TrackingSettings
import kotlin.math.abs
import kotlin.math.roundToLong

data class ReplayFrame(val runId: Long, val update: EngineUpdate, val complete: Boolean)

/** A worker-confined cursor. UI time changes wall delay only. */
class ReplaySession(
    val scenario: Scenario,
    private val settings: TrackingSettings = TrackingSettings(),
    private val runId: Long = 1,
) {
    private val engine = TrackingEngine(scenario.roads, settings, runId)
    private val evaluator = Evaluator(scenario, settings)
    private var observationIndex = 0
    private var nextTick = 0L
    var timeMs = 0L
        private set
    var complete = false
        private set

    fun nextTimeMs(): Long? = if (complete) null else minOf(
        nextTick, scenario.observations.getOrNull(observationIndex)?.receivedAtMs ?: scenario.durationMs,
    )

    suspend fun step(): ReplayFrame? {
        val time = nextTimeMs() ?: return null
        val updates = mutableListOf<EngineUpdate>()
        while (scenario.observations.getOrNull(observationIndex)?.receivedAtMs == time) {
            currentCoroutineContext().ensureActive()
            val observation = scenario.observations[observationIndex++]
            updates += engine.step(EngineInput.Observation(observation))
        }
        if (nextTick == time) {
            val tick = engine.step(EngineInput.Tick(time))
            updates += tick
            evaluator.recordTick(tick.snapshot)
            nextTick = minOf(time + settings.tickMs, scenario.durationMs)
        }
        if (time == scenario.durationMs) {
            updates += engine.complete()
            complete = true
        }
        timeMs = time
        val events = updates.flatMap(EngineUpdate::events)
        evaluator.recordEvents(events)
        return ReplayFrame(runId, EngineUpdate(updates.last().snapshot, events), complete)
    }

    suspend fun result(): Evaluation {
        check(complete)
        val context = currentCoroutineContext()
        context.ensureActive()
        return evaluator.result { context.ensureActive() }
    }
}

object PlaybackSpeeds {
    const val DEFAULT = 1f
    val values: List<Float> = (1..10).map { it * 0.5f }
    val range = values.first()..values.last()

    fun snap(speed: Float): Float = values.minBy { abs(it - speed) }

    fun delayMillis(virtualDelayMs: Long, speed: Float): Long {
        require(speed in values)
        return (virtualDelayMs.toDouble() / speed).roundToLong()
    }
}

suspend fun playToEnd(
    session: ReplaySession,
    speed: () -> Float = { PlaybackSpeeds.DEFAULT },
    wait: suspend (Long) -> Unit = {},
    onFrame: suspend (ReplayFrame) -> Unit = {},
): Evaluation {
    while (true) {
        val next = session.nextTimeMs() ?: break
        val delay = PlaybackSpeeds.delayMillis(next - session.timeMs, speed())
        if (delay > 0) wait(delay)
        onFrame(checkNotNull(session.step()))
    }
    return session.result()
}
