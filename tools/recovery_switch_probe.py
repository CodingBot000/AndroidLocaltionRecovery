#!/usr/bin/env python3
"""Apply the switch probe to a clean, disposable A, B, or C worktree."""

import argparse
from pathlib import Path
import subprocess


SIM = 'core/simulation/src/main/kotlin/lab/recovery/simulation'
LAB = 'feature/lab/src/main/kotlin/lab/recovery/feature'
MAP = 'ui/map/src/main/kotlin/lab/recovery/uimap/RoadMap.kt'
APP = 'app/src/main/kotlin/lab/recovery/app/MainActivity.kt'
BENCH = 'benchmark/src/main/kotlin/lab/recovery/benchmark/RefactorMapBenchmark.kt'

OBSERVER = '''package lab.recovery.simulation

// Compiled only in disposable diagnostic builds. One serial benchmark request at a time.
object ReplaySwitchProbe {
    var sink: ((Long, Long, String, String) -> Unit)? = null
    private var request = 0L
    private var target = ""
    private var runId = 0L
    private val stages = mutableSetOf<String>()
    private val loadingRun = ThreadLocal<Long>()

    @Synchronized fun begin(id: Long, choice: String) {
        request = id
        target = choice
        runId = 0
        stages.clear()
        note(0, "T1", choice)
    }

    @Synchronized fun bind(choice: String, run: Long) {
        if (request == 0L || choice != target) return
        runId = run
        note(run, "T2", choice)
    }

    @Synchronized fun note(run: Long, stage: String, details: String = "") {
        if (request != 0L && run == runId && stages.add(stage)) sink?.invoke(request, run, stage, details)
    }

    @Synchronized fun queued(choice: String, accepted: Boolean) {
        if (choice == target) note(runId, "T1q", accepted.toString())
    }

    fun <T> load(run: Long, body: () -> T): T {
        loadingRun.set(run)
        try { return body() } finally { loadingRun.remove() }
    }

    fun asset(stage: String, details: String) {
        loadingRun.get()?.let { note(it, stage, details) }
    }
}
'''


def instrument(root):
    def replace(file, before, after):
        path = root / file
        source = path.read_text()
        if source.count(before) != 1:
            raise ValueError(f'{file}: expected exactly one match for {before!r}')
        path.write_text(source.replace(before, after))

    (root / SIM / 'ReplaySwitchProbe.kt').write_text(OBSERVER)
    controller = f'{SIM}/ReplayController.kt'
    replace(controller, 'fun send(intent: ReplayIntent): Boolean = commands.trySend(Command.Input(intent)).isSuccess',
            '''fun send(intent: ReplayIntent): Boolean = commands.trySend(Command.Input(intent)).isSuccess.also {
        if (intent is ReplayIntent.Select) ReplaySwitchProbe.queued(intent.choice, it)
    }''')
    replace(controller, '        activeId = newId\n', '        activeId = newId\n        ReplaySwitchProbe.bind(choice, newId)\n')
    replace(controller, '        loadJob = scope.launch {',
            '        ReplaySwitchProbe.note(newId, "T3")\n        loadJob = scope.launch {')
    replace(controller, 'val scenario = withContext(io) { load(choice) }', '''val scenario = withContext(io) {
                    ReplaySwitchProbe.note(newId, "T3w")
                    ReplaySwitchProbe.load(newId) { load(choice) }.also {
                        ReplaySwitchProbe.note(newId, "T4", it.map?.id.orEmpty())
                    }
                }''')
    session = next(line for line in (root / controller).read_text().splitlines() if 'cursor = withContext(worker)' in line)
    call = session.split('{ ', 1)[1].removesuffix(' }')
    replace(controller, session, f'''            ReplaySwitchProbe.note(result.id, "T5")
            cursor = withContext(worker) {{
                ReplaySwitchProbe.note(result.id, "T5w")
                {call}.also {{ ReplaySwitchProbe.note(result.id, "T5e") }}
            }}''')
    publish = 'mutableState.value = state.value.copy(selected = scenario.id, playback = Playback.READY, scenario = scenario)'
    replace(controller, publish, 'ReplaySwitchProbe.note(result.id, "T6", scenario.id)\n                ' + publish)

    repository = f'{SIM}/ScenarioRepository.kt'
    replace(repository, '        val loaded = loadMap(path, entry.mapId, profile)', '''        val hit = lastMap?.path == path
        ReplaySwitchProbe.asset("T3m", "${entry.mapId},$hit")
        val loaded = loadMap(path, entry.mapId, profile)
        ReplaySwitchProbe.asset("T4m", entry.mapId)''')
    replace(APP, 'import lab.recovery.simulation.ScenarioRepository', '''import lab.recovery.simulation.ScenarioRepository
import lab.recovery.simulation.ReplaySwitchProbe
import android.os.SystemClock
import android.os.Trace
import android.util.Log''')
    replace(APP, '        val model = ViewModelProvider(this)[LabViewModel::class.java]', '''        val request = intent.getLongExtra("benchmarkRequest", 0)
        if (request != 0L) {
            ReplaySwitchProbe.sink = { id, run, stage, details ->
                val stamp = SystemClock.elapsedRealtimeNanos()
                Trace.beginSection("SwitchRCA|$id|$run|$stage")
                Trace.endSection()
                Log.i("RecoverySwitchRCA", "$id $run $stage $stamp $details")
            }
            ReplaySwitchProbe.begin(request, scenario)
        }
        val model = ViewModelProvider(this)[LabViewModel::class.java]''')
    replace(APP, 'RecoveryLab(model, optimizedMap) { readyRun.value = it }', '''RecoveryLab(model, optimizedMap) {
                ReplaySwitchProbe.note(it, "T11")
                readyRun.value = it
            }''')

    model = f'{LAB}/LabViewModel.kt'
    replace(model, '        UiState(replay, entries.firstOrNull', '''        if (replay.scenario != null) ReplaySwitchProbe.note(replay.runId, "T6u")
        UiState(replay, entries.firstOrNull''')
    replace(model, 'import lab.recovery.simulation.ReplayController', '''import lab.recovery.simulation.ReplayController
import lab.recovery.simulation.ReplaySwitchProbe''')
    hud = f'{LAB}/DriveHud.kt' if (root / LAB / 'DriveHud.kt').exists() else f'{LAB}/RecoveryLab.kt'
    replace(hud, 'import androidx.compose.runtime.Composable', '''import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.onGloballyPositioned
import lab.recovery.simulation.ReplaySwitchProbe''')
    start = '    val snapshot = state.snapshot'
    replace(hud, start, '''    val mapId = state.scenario?.map?.id
    SideEffect { if (mapId != null) ReplaySwitchProbe.note(state.runId, "T7", mapId) }
''' + start)
    replace(hud, 'modifier = Modifier.testTag("map-status")', '''modifier = Modifier.testTag("map-status").onGloballyPositioned {
                if (mapId != null) ReplaySwitchProbe.note(state.runId, "T7l", "${it.size.width}x${it.size.height}")
            }''')

    # Keeping the bridge as an optional parameter limits changes to the two diagnostic modules.
    replace(MAP, '    onMapReady: (Long) -> Unit = {},', '''    switchStage: (Long, String, String) -> Unit = { _, _, _ -> },
    onMapReady: (Long) -> Unit = {},''')
    replace(MAP, '        val geometry = withContext(Dispatchers.Default) {\n            val world', '''        switchStage(runId, "T8", (existing != null).toString())
        val geometry = withContext(Dispatchers.Default) {
            switchStage(runId, "T8w", "")
            val world''')
    replace(MAP, '            MapGeometry(target, world)\n', '''            MapGeometry(target, world).also { switchStage(runId, "T9", "") }
''')
    replace(MAP, '        value = geometry\n', '''        switchStage(runId, "T9p", "")
        value = geometry
''')
    # One draw sample per prepared scene, including camera workload but excluding coordinates.
    replace(MAP, '    val mapReady by rememberUpdatedState(onMapReady)', '''    val mapReady by rememberUpdatedState(onMapReady)
    val drewScene = remember(geometry, runId) { booleanArrayOf(false) }''')
    replace(MAP, '            else softwareMap.draw(this, data, camera, style, detail, strokes)', '''            else softwareMap.draw(this, data, camera, style, detail, strokes)
            if (!drewScene[0]) {
                drewScene[0] = true
                switchStage(runId, "T10", "${viewport.width}x${viewport.height},$mode,$detail,${camera.scale}")
            }''')
    tracking = f'{LAB}/TrackingMap.kt'
    replace(tracking, 'import androidx.compose.runtime.Composable', '''import androidx.compose.runtime.Composable
import lab.recovery.simulation.ReplaySwitchProbe''')
    call = 'optimized, state.runId, onMapReady)'
    if call in (root / tracking).read_text():
        replace(tracking, call, '''optimized, state.runId,
        switchStage = ReplaySwitchProbe::note, onMapReady = onMapReady)''')
    else:
        replace(tracking, 'onMapReady = onMapReady)',
                'switchStage = ReplaySwitchProbe::note, onMapReady = onMapReady)')

    replace(BENCH, '        select("OL03")\n        ready("O07")', '''        stamp(start, "T0", start)
        select("OL03", start)
        stamp(start, "U0")
        ready("O07", start)''')
    replace(BENCH, '    private fun MacrobenchmarkScope.select(id: String) {', '''    private fun MacrobenchmarkScope.select(id: String, request: Long = 0) {''')
    replace(BENCH, ' --es benchmarkSelect $id")', ' --es benchmarkSelect $id --el benchmarkRequest $request")')
    replace(BENCH, '    private fun MacrobenchmarkScope.ready(map: String) {', '''    private fun stamp(request: Long, stage: String, stamp: Long = SystemClock.elapsedRealtimeNanos()) {
        if (request == 0L) return
        android.os.Trace.beginSection("SwitchRCA|$request|0|$stage")
        android.os.Trace.endSection()
        Log.i("RecoverySwitchRCA", "$request 0 $stage $stamp")
    }

    private fun MacrobenchmarkScope.ready(map: String, request: Long = 0) {''')
    for line, stage in [('Expected map', 'U1'), ('Map must finish drawing', 'U2'), ('Playback must be available', 'U3')]:
        original = next(item for item in (root / BENCH).read_text().splitlines() if f'assertTrue("{line}' in item)
        replace(BENCH, original, original + f'\n        stamp(request, "{stage}")')
    expected_map = next(item for item in (root / BENCH).read_text().splitlines() if 'assertTrue("Expected map' in item)
    replace(BENCH, expected_map, '''        val selector = By.res("map-status").textStartsWith("$map ·")
        val clear = request != 0L && args().getString("switchClearCache") == "true"
        val found = if (clear) {
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            device.wait(androidx.test.uiautomator.Condition<androidx.test.uiautomator.UiDevice, Boolean> {
                check(automation.clearCache())
                it.hasObject(selector)
            }, 20_000)
        } else device.wait(Until.hasObject(selector), 20_000)
        assertTrue("Expected map $map", found)''')
    replace(BENCH, '    private fun stamp(', '''    private fun args() = androidx.test.platform.app.InstrumentationRegistry.getArguments()

    private fun stamp(''')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('worktree', type=Path)
    args = parser.parse_args()
    if subprocess.check_output(['git', '-C', args.worktree, 'status', '--porcelain'], text=True).strip():
        parser.error('worktree must be clean')
    instrument(args.worktree)
