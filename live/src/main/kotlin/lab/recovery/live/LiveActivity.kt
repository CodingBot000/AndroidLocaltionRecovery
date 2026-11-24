package lab.recovery.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lab.recovery.location.PositionSource
import lab.recovery.simulation.ScenarioCatalog
import lab.recovery.simulation.ScenarioRepository
import lab.recovery.uimap.MapCredits
import lab.recovery.uimap.MapMarker
import lab.recovery.uimap.MapOverlay
import lab.recovery.uimap.MapScene
import lab.recovery.uimap.MapStrings
import lab.recovery.uimap.MarkerKind
import lab.recovery.uimap.RoadMap
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class LiveActivity : ComponentActivity() {
    private var permissionNotice by mutableStateOf<String?>(null)
    private var notificationsAllowed by mutableStateOf(false)
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionNotice = when (AndroidLocationSource(this).precision()) {
            Precision.NONE -> "Location permission denied"
            Precision.COARSE -> "Approximate permission granted. Tap Start for raw diagnostics."
            Precision.FINE -> "Precise permission granted. Tap Start to begin."
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        permissionNotice = if (granted) null else "Notifications denied. Stop remains available here."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val repository = ScenarioRepository { applicationContext.assets.open(it) }
        setContent {
            val state by LiveSessions.updates.collectAsStateWithLifecycle()
            val catalog by produceState<ScenarioCatalog?>(null) {
                value = withContext(Dispatchers.IO) { repository.osmCatalog }
            }
            val previousSession by produceState(false) {
                value = withContext(Dispatchers.IO) {
                    getSharedPreferences("live-interruption", MODE_PRIVATE).getBoolean("inProgress", false)
                } && LiveSessions.updates.value.phase == SessionPhase.IDLE
            }
            val exits by produceState<List<ExitSummary>?>(null) {
                value = withContext(Dispatchers.IO) { recentExits(applicationContext) }
            }
            MaterialTheme {
                Surface {
                    LiveScreen(state, catalog, previousSession, exits, permissionNotice,
                        Build.VERSION.SDK_INT >= 33 && !notificationsAllowed,
                        start = ::requestStart,
                        stop = { LiveSessions.stop(this) },
                        enableNotifications = {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStart(map: String, route: String?) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !hasWindowFocus()) return
        permissionNotice = null
        if (AndroidLocationSource(this).precision() == Precision.NONE) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            LiveSessions.start(this, map, route)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LiveScreen(
    state: LiveState,
    catalog: ScenarioCatalog?,
    previousSession: Boolean,
    exits: List<ExitSummary>?,
    notice: String?,
    offerNotifications: Boolean,
    start: (String, String?) -> Unit,
    stop: () -> Unit,
    enableNotifications: () -> Unit,
) {
    var mapId by rememberSaveable { mutableStateOf<String?>(null) }
    var routeId by rememberSaveable { mutableStateOf<String?>(null) }
    var startedHere by remember { mutableStateOf(false) }
    val running = state.phase in setOf(SessionPhase.STARTING, SessionPhase.ACTIVE, SessionPhase.STOPPING)
    val map = if (running) state.mapId else mapId
    val route = if (running) state.routeId else routeId
    val language = Locale.getDefault().language
    val mapSelect = catalog?.maps.orEmpty().mapValues { (_, name) -> name.forLanguage(language) }
    val routeSelect = catalog?.entries.orEmpty().filter { !it.evaluation && it.mapId == map }
        .associate { it.id to "${it.id} · ${it.title.forLanguage(language)}" }

    Column(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Live location", style = MaterialTheme.typography.headlineMedium)
        Text("Android location diagnostics", style = MaterialTheme.typography.bodyMedium)
        Choice(mapSelect[map] ?: "Choose an offline map", mapSelect, !running, "live-map") {
            mapId = it
            routeId = null
        }
        Choice(routeSelect[route] ?: "No planned route", linkedMapOf("" to "No planned route") + routeSelect,
            !running && map != null, "live-route") { routeId = it.ifBlank { null } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(stop, enabled = state.phase != SessionPhase.STOPPING, modifier = Modifier.testTag("live-stop")) {
                    Text("Stop")
                }
            } else {
                Button(onClick = {
                    map?.let { start(it, route) }
                }, enabled = map != null, modifier = Modifier.testTag("live-start")) { Text("Start") }
            }
            if (offerNotifications) OutlinedButton(enableNotifications) { Text("Enable notifications") }
        }
        LaunchedEffect(running) { if (running) startedHere = true }
        Text(state.phase.name.readable(), modifier = Modifier.testTag("live-phase"))
        if (previousSession && !startedHere && !running && state.issue == null) Text("Previous session interrupted. Start a new session to continue.")
        state.issue?.let { Text(it.name.readable(), color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it) }
        val reading = state.reading
        if (reading != null) {
            Text(if (state.precision == Precision.COARSE) "Approximate location · raw diagnostics only" else "Precise location",
                modifier = Modifier.testTag("live-precision"))
            Text(reading.status.name.readable(), modifier = Modifier.testTag("live-fix-status"))
            reading.fix?.let { fix ->
                val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.startsWith("sdk_")
                Text(when {
                    emulator && fix.mock -> "Emulator position · Android mock"
                    emulator -> "Emulator position"
                    fix.mock -> "Android mock location"
                    else -> "Android location"
                }, modifier = Modifier.testTag("live-fix-source"))
                Text("Provider ${fix.provider ?: "unknown"}")
                Text("Accuracy ${fix.accuracy?.let { "${it.toInt()} m" } ?: "unknown"}")
            }
            reading.tracking?.let { Text("${it.signal.name.readable()} · ${it.route.name.readable()} · ${it.source.name.readable()}") }
            Text("Fixes received ${state.received} · processed ${state.processed} · dropped ${state.dropped}")
        }
        state.map?.let { LiveRoadMap(it, state) }
        if (Build.VERSION.SDK_INT < 30) {
            Text("Process exit history requires Android 11.", style = MaterialTheme.typography.bodySmall)
        } else {
            exits?.firstOrNull()?.let {
                val date = DateFormat.getDateTimeInstance().format(Date(it.timestamp))
                Text("Last process exit · ${it.reason.name.readable()} · $date · importance ${it.importance}",
                    modifier = Modifier.testTag("live-exit"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Choice(label: String, choices: Map<String, String>, enabled: Boolean, tag: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag(tag), enabled = enabled) { Text(label) }
        DropdownMenu(expanded, { expanded = false }, Modifier.semantics { testTagsAsResourceId = true }) {
            choices.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; select(id) },
                    modifier = Modifier.testTag("$tag-$id"))
            }
        }
    }
}

@Composable
private fun LiveRoadMap(map: LiveMap, state: LiveState) {
    val osm = map.definition.osm ?: return
    var showCredits by remember(map) { mutableStateOf(false) }
    val scene = remember(map) {
        MapScene(map.definition.network, map.route?.route, map.definition.visual, osm.bounds, map.definition.checksum)
    }
    val reading = state.reading
    val tracking = reading?.tracking
    val marker = tracking?.position?.let {
        val kind = when (tracking.source) {
            PositionSource.DEAD_RECKONED -> MarkerKind.ESTIMATE
            PositionSource.LAST_KNOWN -> MarkerKind.LAST_KNOWN
            else -> MarkerKind.FIX
        }
        MapMarker(it, kind)
    }
    RoadMap(scene, MapOverlay(vehicle = marker, raw = reading?.point, accuracyMeters = reading?.fix?.accuracy),
        false, MapStrings("Offline map", "Follow", "Overview", "Free", "Zoom in", "Zoom out", "Tunnel"),
        Modifier.fillMaxWidth().height(340.dp), MapCredits(osm.attribution) { showCredits = true }, runId = state.token)
    if (showCredits) AlertDialog(
        onDismissRequest = { showCredits = false },
        title = { Text(osm.attribution) },
        text = { Text(listOf(osm.license, osm.licenseUrl, osm.snapshot, osm.sourceUrl).joinToString("\n")) },
        confirmButton = { TextButton({ showCredits = false }) { Text("Close") } },
    )
}

private fun String.readable() = lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar(Char::uppercase)
