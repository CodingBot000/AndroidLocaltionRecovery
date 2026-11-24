package lab.recovery.app

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lab.recovery.location.DecisionEvent
import lab.recovery.map.Point2D
import lab.recovery.location.PositionSource
import lab.recovery.location.TrackingSnapshot
import lab.recovery.simulation.Evaluation
import lab.recovery.simulation.PlaybackSpeeds
import lab.recovery.simulation.ReplayFrame
import lab.recovery.simulation.ReplaySession
import lab.recovery.simulation.Scenario

enum class Playback { LOADING, READY, PLAYING, PAUSED, COMPLETE, ERROR }
data class ScenarioChoice(
    val id: String,
    val title: String,
    @StringRes val description: Int,
    val path: String = "evaluation/$id.json",
)
data class UiState(
    val selected: ScenarioChoice,
    val runId: Long = 0,
    val playback: Playback = Playback.LOADING,
    val speed: Float = PlaybackSpeeds.DEFAULT,
    val showTruth: Boolean = true,
    val scenario: Scenario? = null,
    val snapshot: TrackingSnapshot = TrackingSnapshot(),
    val logs: List<DecisionEvent> = emptyList(),
    val trails: Map<PositionSource, List<Point2D>> = emptyMap(),
    val evaluation: Evaluation? = null,
    val error: String? = null,
)

sealed interface UiIntent {
    data class Select(val choice: ScenarioChoice) : UiIntent
    data class Speed(val value: Float) : UiIntent
    data object PlayPause : UiIntent
    data object Step : UiIntent
    data object Reset : UiIntent
    data object ToggleTruth : UiIntent
}

val scenarioChoices = listOf(
    ScenarioChoice("DEMO", "Jump, loss & recovery", R.string.scenario_demo_description, "development/DEMO.json"),
    ScenarioChoice("S01", "Straight road & curve", R.string.scenario_s01_description),
    ScenarioChoice("S02", "GPS position jump", R.string.scenario_s02_description),
    ScenarioChoice("S03", "Stopped vehicle", R.string.scenario_s03_description),
    ScenarioChoice("S04", "Poor GPS accuracy", R.string.scenario_s04_description),
    ScenarioChoice("S05", "Duplicate & delayed GPS", R.string.scenario_s05_description),
    ScenarioChoice("S06", "Short signal loss", R.string.scenario_s06_description),
    ScenarioChoice("S07", "Long tunnel outage", R.string.scenario_s07_description),
    ScenarioChoice("S08", "Braking during outage", R.string.scenario_s08_description),
    ScenarioChoice("S09", "Unstable recovery", R.string.scenario_s09_description),
    ScenarioChoice("S10", "Parallel roads", R.string.scenario_s10_description),
    ScenarioChoice("S11", "True route departure", R.string.scenario_s11_description),
    ScenarioChoice("S12", "False departure", R.string.scenario_s12_description),
    ScenarioChoice("S13", "Return to route", R.string.scenario_s13_description),
    ScenarioChoice("S14", "No initial fix", R.string.scenario_s14_description),
    ScenarioChoice("S15", "Replay controls", R.string.scenario_s15_description),
    ScenarioChoice("S16", "Departure during outage", R.string.scenario_s16_description),
    ScenarioChoice("S17", "Invalid observations", R.string.scenario_s17_description),
)

class LabViewModel(
    private val load: (ScenarioChoice) -> Scenario,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private sealed interface Command {
        data class Input(val intent: UiIntent) : Command
        data class Loaded(val id: Long, val scenario: Scenario?, val error: String?) : Command
        data class Advance(val id: Long, val generation: Long) : Command
        data class Evaluated(val id: Long, val evaluation: Evaluation) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(UiState(scenarioChoices.first()))
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
        viewModelScope.launch {
            select(scenarioChoices.first())
            for (command in commands) {
                if (closed) break
                when (command) {
                    is Command.Input -> handle(command.intent)
                    is Command.Loaded -> finishLoad(command)
                    is Command.Advance -> if (
                        activeId == command.id && generation == command.generation &&
                        state.value.playback == Playback.PLAYING
                    ) {
                        advance()
                        schedule()
                    }
                    is Command.Evaluated -> if (activeId == command.id) {
                        mutableState.value = state.value.copy(evaluation = command.evaluation)
                    }
                }
            }
        }
    }

    fun send(intent: UiIntent) {
        commands.trySend(Command.Input(intent))
    }

    private suspend fun handle(intent: UiIntent) {
        val current = state.value
        when (intent) {
            is UiIntent.Select -> select(intent.choice)
            UiIntent.Reset -> select(current.selected)
            is UiIntent.Speed -> if (intent.value in PlaybackSpeeds.values && intent.value != current.speed) {
                mutableState.value = current.copy(speed = intent.value)
                if (current.playback == Playback.PLAYING) schedule()
            }
            UiIntent.ToggleTruth -> mutableState.value = current.copy(showTruth = !current.showTruth)
            UiIntent.PlayPause -> when (current.playback) {
                Playback.PLAYING -> pause()
                Playback.READY, Playback.PAUSED -> {
                    mutableState.value = current.copy(playback = Playback.PLAYING)
                    schedule()
                }
                else -> Unit
            }
            UiIntent.Step -> if (current.playback in setOf(Playback.READY, Playback.PLAYING, Playback.PAUSED)) {
                pause()
                advance()
            }
        }
    }

    private fun select(choice: ScenarioChoice) {
        activeId = null
        cancelTasks()
        cursor = null
        val newId = ++nextId
        activeId = newId
        mutableState.value = UiState(choice, runId = newId, speed = state.value.speed, showTruth = state.value.showTruth)
        loadJob = viewModelScope.launch {
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
        mutableState.value = state.value.copy(playback = Playback.PAUSED)
    }

    private fun schedule() {
        generation++
        timer?.cancel()
        val session = cursor ?: return
        val id = activeId ?: return
        val next = session.nextTimeMs() ?: return
        val currentGeneration = generation
        val wallDelay = PlaybackSpeeds.delayMillis(next - session.timeMs, state.value.speed)
        timer = viewModelScope.launch {
            if (wallDelay > 0) wait(wallDelay)
            commands.send(Command.Advance(id, currentGeneration))
        }
    }

    private suspend fun advance() {
        val session = cursor ?: return
        val id = activeId ?: return
        val frame = withContext(worker) { session.step() } ?: return
        if (closed || frame.runId != activeId) return
        publish(frame)
        if (frame.complete) {
            evaluationJob = viewModelScope.launch {
                val evaluation = withContext(worker) { session.result() }
                commands.send(Command.Evaluated(id, evaluation))
            }
        }
    }

    private fun publish(frame: ReplayFrame) {
        val current = state.value
        val snapshot = frame.update.snapshot
        val point = snapshot.position
        val trail = current.trails[snapshot.source].orEmpty()
        val trails = if (point != null && snapshot.source != PositionSource.LAST_KNOWN &&
            (snapshot.source != current.snapshot.source || trail.lastOrNull() != point)
        ) current.trails + (snapshot.source to (trail + point).takeLast(1000)) else current.trails
        val logs = if (frame.update.events.isEmpty()) current.logs else (current.logs + frame.update.events).takeLast(500)
        mutableState.value = current.copy(
            snapshot = snapshot, trails = trails, logs = logs,
            playback = if (frame.complete) Playback.COMPLETE else current.playback,
        )
    }

    private fun cancelTasks() {
        generation++
        timer?.cancel()
        loadJob?.cancel()
        evaluationJob?.cancel()
    }

    override fun onCleared() {
        closed = true
        activeId = null
        cursor = null
        cancelTasks()
        commands.cancel()
    }
}
