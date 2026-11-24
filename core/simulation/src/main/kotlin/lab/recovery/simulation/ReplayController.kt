package lab.recovery.simulation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lab.recovery.location.DecisionEvent
import lab.recovery.location.TrackingSnapshot

enum class Playback { LOADING, READY, PLAYING, PAUSED, COMPLETE, ERROR }
data class ReplayState(
    val selected: String,
    val runId: Long = 0,
    val playback: Playback = Playback.LOADING,
    val speed: Float = PlaybackSpeeds.DEFAULT,
    val scenario: Scenario? = null,
    val snapshot: TrackingSnapshot = TrackingSnapshot(),
    val logs: List<DecisionEvent> = emptyList(),
    val trails: List<TrailSegment> = emptyList(),
    val backlogMs: Long = 0,
    val evaluation: Evaluation? = null,
    val error: String? = null,
)

sealed interface ReplayIntent {
    data class Select(val choice: String) : ReplayIntent
    data class Speed(val value: Float) : ReplayIntent
    data object PlayPause : ReplayIntent
    data object Step : ReplayIntent
    data object Reset : ReplayIntent
}

class ReplayController(
    private val scope: CoroutineScope,
    initial: String,
    private val load: (String) -> Scenario,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    clock: ReplayClock = MonotonicReplayClock,
) : AutoCloseable {
    private val timeline = ReplayTimeline(clock)
    private val clock = clock
    private var history = ReplayHistory()
    private sealed interface Command {
        data class Input(val intent: ReplayIntent) : Command
        data class Loaded(val id: Long, val scenario: Scenario?, val error: String?) : Command
        data class Advance(val id: Long, val generation: Long) : Command
        data class Evaluated(val id: Long, val evaluation: Evaluation?, val error: String? = null) : Command
    }

    private val commands = Channel<Command>(256)
    private val mutableState = MutableStateFlow(ReplayState(initial))
    val state = mutableState.asStateFlow()
    private var cursor: ReplaySession? = null
    private var activeId: Long? = null
    private var nextId = 0L
    private var generation = 0L
    private var timer: Job? = null
    private var loadJob: Job? = null
    private var evaluationJob: Job? = null
    private var closed = false

    init {
        scope.launch {
            select(initial)
            for (command in commands) {
                if (closed) break
                when (command) {
                    is Command.Input -> handle(command.intent)
                    is Command.Loaded -> finishLoad(command)
                    is Command.Advance -> if (
                        activeId == command.id && generation == command.generation &&
                        state.value.playback == Playback.PLAYING
                    ) {
                        advanceBatch()
                        schedule()
                    }
                    is Command.Evaluated -> if (activeId == command.id) {
                        mutableState.value = state.value.copy(evaluation = command.evaluation, error = command.error,
                            playback = if (command.error == null) Playback.COMPLETE else Playback.ERROR)
                    }
                }
            }
        }
    }

    fun send(intent: ReplayIntent): Boolean = commands.trySend(Command.Input(intent)).isSuccess

    private suspend fun handle(intent: ReplayIntent) {
        val current = state.value
        when (intent) {
            is ReplayIntent.Select -> select(intent.choice)
            ReplayIntent.Reset -> select(current.selected)
            is ReplayIntent.Speed -> if (intent.value in PlaybackSpeeds.values && intent.value != current.speed) {
                mutableState.value = current.copy(speed = intent.value)
                if (current.playback == Playback.PLAYING) {
                    timeline.changeRate(intent.value)
                    schedule()
                }
            }
            ReplayIntent.PlayPause -> when (current.playback) {
                Playback.PLAYING -> pause()
                Playback.READY, Playback.PAUSED -> {
                    mutableState.value = current.copy(playback = Playback.PLAYING)
                    timeline.changeRate(current.speed)
                    schedule()
                }
                else -> Unit
            }
            ReplayIntent.Step -> if (current.playback in setOf(Playback.READY, Playback.PLAYING, Playback.PAUSED)) {
                pause()
                advance()
                timeline.seek(state.value.snapshot.nowMs)
                publishTrail()
            }
        }
    }

    private fun select(choice: String) {
        activeId = null
        cancelTasks()
        cursor = null
        history = ReplayHistory()
        timeline.seek(0)
        val newId = ++nextId
        activeId = newId
        mutableState.value = ReplayState(choice, runId = newId, speed = state.value.speed)
        loadJob = scope.launch {
            try {
                val scenario = withContext(io) { load(choice) }
                commands.send(Command.Loaded(newId, scenario, null))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                commands.send(Command.Loaded(newId, null, error.message ?: "Unable to open this fixture"))
            }
        }
    }

    private suspend fun finishLoad(result: Command.Loaded) {
        if (activeId != result.id) return
        val scenario = result.scenario
        if (scenario == null) {
            mutableState.value = state.value.copy(playback = Playback.ERROR, error = result.error)
        } else {
            cursor = withContext(worker) { ReplaySession(scenario, runId = result.id) }
            if (activeId == result.id) {
                mutableState.value = state.value.copy(playback = Playback.READY, scenario = scenario)
            }
        }
    }

    private fun pause() {
        generation++
        timer?.cancel()
        timeline.changeRate(0f)
        mutableState.value = state.value.copy(playback = Playback.PAUSED, backlogMs = 0)
    }

    private fun schedule() {
        generation++
        timer?.cancel()
        val session = cursor ?: return
        val id = activeId ?: return
        val next = session.nextTimeMs() ?: return
        val currentGeneration = generation
        val wallDelay = timeline.delayUntil(next)
        timer = scope.launch {
            if (wallDelay > 0) clock.await(wallDelay)
            commands.send(Command.Advance(id, currentGeneration))
        }
    }

    private suspend fun advanceBatch() {
        val backlog = cursor?.nextTimeMs()?.let { (timeline.position() - it).toLong().coerceAtLeast(0) } ?: 0L
        repeat(12) { index ->
            val next = cursor?.nextTimeMs() ?: return@repeat
            if (index == 0 || next <= timeline.position()) advance()
        }
        mutableState.value = state.value.copy(backlogMs = backlog)
        publishTrail()
    }

    private fun publishTrail() {
        history.publishTrail()
        mutableState.value = state.value.copy(trails = history.trailSnapshot)
    }

    private suspend fun advance() {
        val session = cursor ?: return
        val id = activeId ?: return
        val frame = withContext(worker) { session.step() } ?: return
        if (closed || frame.runId != activeId) return
        publish(frame)
        if (frame.complete) {
            evaluationJob = scope.launch {
                try {
                    val evaluation = withContext(worker) { session.result() }
                    commands.send(Command.Evaluated(id, evaluation))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    commands.send(Command.Evaluated(id, null, error.message ?: "Evaluation failed"))
                }
            }
        }
    }

    private fun publish(frame: ReplayFrame) {
        val current = state.value
        val snapshot = frame.update.snapshot
        history.record(snapshot, frame.update.events)
        mutableState.value = current.copy(
            snapshot = snapshot, logs = history.logSnapshot,
            playback = if (frame.complete) Playback.COMPLETE else current.playback,
        )
    }

    private fun cancelTasks() {
        generation++
        timer?.cancel()
        loadJob?.cancel()
        evaluationJob?.cancel()
    }

    override fun close() {
        closed = true
        activeId = null
        cursor = null
        cancelTasks()
        commands.cancel()
    }
}
