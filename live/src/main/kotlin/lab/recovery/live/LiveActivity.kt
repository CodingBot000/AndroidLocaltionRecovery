package lab.recovery.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
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
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    private var permissionNotice by mutableStateOf<Int?>(null)
    private var availablePrecision by mutableStateOf(Precision.NONE)
    private var notificationsAllowed by mutableStateOf(false)
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        availablePrecision = AndroidLocationSource(this).precision()
        permissionNotice = when (availablePrecision) {
            Precision.NONE -> R.string.live_notice_denied
            Precision.COARSE -> R.string.live_notice_coarse
            Precision.FINE -> R.string.live_notice_fine
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        permissionNotice = if (granted) null else R.string.live_notice_notifications
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
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
            MaterialTheme(colorScheme = liveColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LiveScreen(state, catalog, previousSession, exits, permissionNotice, availablePrecision,
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
        availablePrecision = AndroidLocationSource(this).precision()
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

private val liveColors = darkColorScheme(
    background = Color(0xFF111A27), onBackground = Color(0xFFEDF3F9),
    surface = Color(0xFF1B293B), onSurface = Color(0xFFEDF3F9),
    primary = Color(0xFF94E1CF), onPrimary = Color(0xFF0B3023), error = Color(0xFFFF9F91),
    onSurfaceVariant = Color(0xFFB9C9DA), outline = Color(0xFF465B71),
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LiveScreen(
    state: LiveState,
    catalog: ScenarioCatalog?,
    previousSession: Boolean,
    exits: List<ExitSummary>?,
    notice: Int?,
    availablePrecision: Precision,
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
    val language = LocalConfiguration.current.locales[0].language
    val mapSelect = remember(catalog, language) { catalog?.maps.orEmpty().mapValues { (_, name) -> name.forLanguage(language) } }
    val routeSelect = remember(catalog, map, language) {
        catalog?.entries.orEmpty().filter { !it.evaluation && it.mapId == map }
            .associate { it.id to "${it.id} · ${it.title.forLanguage(language)}" }
    }

    Column(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.live_mode), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("live-mode"))
        Text(stringResource(R.string.live_subtitle), style = MaterialTheme.typography.bodyMedium)
        Choice(mapSelect[map] ?: stringResource(R.string.live_choose_map), mapSelect, !running, "live-map") {
            mapId = it
            routeId = null
        }
        val noRoute = stringResource(R.string.live_no_route)
        Choice(routeSelect[route] ?: noRoute, linkedMapOf("" to noRoute) + routeSelect,
            !running && map != null, "live-route") { routeId = it.ifBlank { null } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(stop, enabled = state.phase != SessionPhase.STOPPING, modifier = Modifier.testTag("live-stop")) {
                    Text(stringResource(R.string.live_stop))
                }
            } else {
                Button(onClick = {
                    map?.let { start(it, route) }
                }, enabled = map != null, modifier = Modifier.testTag("live-start")) { Text(stringResource(R.string.live_start)) }
            }
            if (offerNotifications) OutlinedButton(enableNotifications) { Text(stringResource(R.string.live_enable_notifications)) }
        }
        LaunchedEffect(running) { if (running) startedHere = true }
        if (previousSession && !startedHere && !running && state.issue == null) {
            Text(stringResource(R.string.live_previous_session))
        }
        state.issue?.let { Text(stringResource(it.labelResource()), color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(stringResource(it)) }
        LiveStatus(state, if (running) state.precision else availablePrecision)
        state.map?.let { LiveRoadMap(it, state) }
        if (Build.VERSION.SDK_INT < 30) {
            Text(stringResource(R.string.live_exit_unavailable), style = MaterialTheme.typography.bodySmall)
        } else {
            exits?.firstOrNull()?.let {
                val date = DateFormat.getDateTimeInstance().format(Date(it.timestamp))
                Text(stringResource(R.string.live_process_exit, it.reason.name.readable(), date, it.importance),
                    modifier = Modifier.testTag("live-exit"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LiveStatus(state: LiveState, precision: Precision) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(state.phase.labelResource()), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("live-phase"))
            Text(stringResource(precision.labelResource()), modifier = Modifier.testTag("live-precision"))
            state.reading?.let { reading ->
                Text(stringResource(reading.status.labelResource()), modifier = Modifier.testTag("live-fix-status"))
                reading.fix?.let { fix ->
                    Text(stringResource(fixSourceResource(isEmulator, fix.mock)), modifier = Modifier.testTag("live-fix-source"))
                    val provider = fix.provider ?: stringResource(R.string.live_value_unknown)
                    val accuracy = fix.accuracy?.let { stringResource(R.string.live_meters, it) }
                        ?: stringResource(R.string.live_value_unknown)
                    Text(stringResource(R.string.live_provider_accuracy, provider, accuracy),
                        style = MaterialTheme.typography.bodySmall)
                }
                reading.tracking?.let {
                    Text(stringResource(R.string.live_engine_status, it.signal.name, it.source.name, it.route.name),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.live_fix_counts, state.received, state.processed, state.dropped),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val isEmulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.startsWith("sdk_")

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
        false, MapStrings(stringResource(R.string.live_map), stringResource(R.string.live_follow),
            stringResource(R.string.live_overview), stringResource(R.string.live_free),
            stringResource(R.string.live_zoom_in), stringResource(R.string.live_zoom_out), stringResource(R.string.live_tunnel)),
        Modifier.fillMaxWidth().height(340.dp), MapCredits(osm.attribution) { showCredits = true }, runId = state.token)
    if (showCredits) AlertDialog(
        onDismissRequest = { showCredits = false },
        title = { Text(osm.attribution) },
        text = { Text(listOf(osm.license, osm.licenseUrl, osm.snapshot, osm.sourceUrl).joinToString("\n")) },
        confirmButton = { TextButton({ showCredits = false }) { Text(stringResource(R.string.live_close)) } },
    )
}

private fun String.readable() = lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar(Char::uppercase)
