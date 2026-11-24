package lab.recovery.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lab.recovery.map.MapDefinition
import lab.recovery.map.RoadGraph
import lab.recovery.simulation.FixtureSource
import lab.recovery.simulation.ScenarioRepository

internal data class LiveMap(val definition: MapDefinition, val projection: OsmProjection?, val route: RoadGraph?)

internal object LiveSessions {
    internal val state = MutableStateFlow(LiveState())
    val updates = state.asStateFlow()

    fun start(context: Context, mapId: String, routeId: String?) {
        if (state.value.phase !in setOf(SessionPhase.IDLE, SessionPhase.ERROR)) return
        val source = AndroidLocationSource(context)
        val precision = source.precision()
        val issue = source.issue(precision)
        if (issue != null) {
            state.value = LiveState(SessionPhase.ERROR, issue = issue)
            return
        }
        val token = SystemClock.elapsedRealtimeNanos()
        state.value = LiveState(SessionPhase.STARTING, token, mapId, routeId, precision)
        try {
            context.startForegroundService(command(context, START, token))
        } catch (_: SecurityException) {
            state.value = LiveState(SessionPhase.ERROR, issue = LiveIssue.PERMISSION_CHANGED)
        } catch (_: IllegalStateException) {
            // Includes API 31+ ForegroundServiceStartNotAllowedException.
            state.value = LiveState(SessionPhase.ERROR, issue = LiveIssue.START_REJECTED)
        }
    }

    fun stop(context: Context) {
        val current = state.value
        if (current.phase !in setOf(SessionPhase.STARTING, SessionPhase.ACTIVE)) return
        try {
            context.startService(command(context, STOP, current.token))
        } catch (_: IllegalStateException) {
            context.stopService(command(context, STOP, current.token))
        }
    }

    fun command(context: Context, action: String, token: Long): Intent =
        Intent(context, LiveTrackingService::class.java).setAction(action).putExtra("sessionToken", token)

    const val START = "lab.recovery.live.START"
    const val STOP = "lab.recovery.live.STOP"
}

class LiveTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var source: LocationSource
    private var subscription: LocationSubscription? = null
    private var job: Job? = null
    private var inbox: Channel<RawFix>? = null
    private var marker: SharedPreferences? = null
    private var token = 0L

    override fun onCreate() {
        super.onCreate()
        source = AndroidLocationSource(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra("sessionToken", 0L) ?: 0L
        val current = LiveSessions.state.value
        when {
            intent?.action == LiveSessions.STOP && requested != 0L && requested == token -> finish()
            intent?.action == LiveSessions.START && requested != 0L && requested == current.token &&
                current.phase == SessionPhase.STARTING && token == 0L -> begin(current)
            token == 0L && current.phase != SessionPhase.STOPPING -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun begin(initial: LiveState) {
        token = initial.token
        try {
            val ongoing = notification(token)
            if (Build.VERSION.SDK_INT >= 29) startForeground(notificationId, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(notificationId, ongoing)
        } catch (_: SecurityException) {
            finish(LiveIssue.PERMISSION_CHANGED)
            return
        } catch (_: IllegalStateException) {
            finish(LiveIssue.START_REJECTED)
            return
        }
        val activeToken = token
        job = scope.launch {
            try {
                val preferences = withContext(Dispatchers.IO) {
                    getSharedPreferences("live-interruption", MODE_PRIVATE).also { it.getBoolean("inProgress", false) }
                }
                marker = preferences
                preferences.edit().putBoolean("inProgress", true).apply()
                val map = withContext(Dispatchers.IO) { loadMap(initial) }
                val location = LocationSubscription(source, initial.precision)
                subscription = location
                runSession(initial, map, activeToken, location)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                finish(LiveIssue.PERMISSION_CHANGED)
            } catch (_: java.io.IOException) {
                finish(LiveIssue.MAP_UNAVAILABLE)
            } catch (_: IllegalArgumentException) {
                finish(LiveIssue.MAP_UNAVAILABLE)
            } catch (_: lab.recovery.simulation.InvalidFixture) {
                finish(LiveIssue.MAP_UNAVAILABLE)
            }
        }
    }

    private fun loadMap(initial: LiveState): LiveMap {
        val repository = ScenarioRepository(FixtureSource(assets::open))
        val map = repository.readOsmMap(requireNotNull(initial.mapId))
        val control = try {
            assets.open("projection-controls.json").bufferedReader().use(ProjectionControl::read)[map.id]
        } catch (_: java.io.IOException) {
            null
        } catch (_: RuntimeException) {
            null // A malformed control cannot enable matching.
        }
        val projection = OsmProjection.verified(map, control)
        val route = initial.routeId?.let { repository.readOsmPlan(it, map) }
        route?.network?.roadGrid // Initialize the matching index off the main looper.
        return LiveMap(map, projection, route)
    }

    private suspend fun runSession(
        initial: LiveState, map: LiveMap, activeToken: Long, location: LocationSubscription,
    ) {
        val started = SystemClock.elapsedRealtimeNanos()
        val session = LiveSession(started, initial.precision, map.projection, map.route)
        val messages = Channel<RawFix>(fixCapacity)
        inbox = messages
        publish { it.copy(phase = SessionPhase.ACTIVE, map = map, reading = session.tick(started)) }
        location.open(fix = { fix ->
            if (token == activeToken) {
                val queued = messages.trySend(fix).isSuccess
                publish { it.copy(received = it.received + 1, dropped = it.dropped + if (queued) 0 else 1) }
            }
        }, issue = { issue ->
            if (token == activeToken) {
                if (issue == LiveIssue.PROVIDER_TEMPORARY) publish { it.copy(issue = issue) }
                else finish(issue)
            }
        })
        if (LiveSessions.state.value.phase != SessionPhase.ACTIVE) return
        var nextPolicyCheck = started
        var nextTick = started + tickNanos
        while (token == activeToken) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now >= nextPolicyCheck) {
                location.currentIssue()?.let {
                    finish(it)
                    return
                }
                nextPolicyCheck = now + 1_000_000_000
            }
            val waitMs = ((nextTick - now) / 1_000_000).coerceAtLeast(1)
            // Drain queued callbacks in reception order. Take a clock tick only when the queue is empty,
            // or at the next callback's reception time if callbacks have arrived continuously.
            val fix = messages.tryReceive().getOrNull() ?: withTimeoutOrNull(waitMs) { messages.receive() }
            val clock = fix?.receivedNs ?: SystemClock.elapsedRealtimeNanos()
            val tickDue = clock >= nextTick
            if (tickDue) nextTick = clock + tickNanos
            val (reading, processingNs) = withContext(Dispatchers.Default) {
                if (fix == null) {
                    session.tick(clock) to null
                } else {
                    if (tickDue) session.tick(clock)
                    val before = SystemClock.elapsedRealtimeNanos()
                    session.onFix(fix) to (SystemClock.elapsedRealtimeNanos() - before)
                }
            }
            publish {
                it.copy(reading = reading, processed = it.processed + if (fix == null) 0 else 1,
                    issue = if (fix == null) it.issue else null,
                    latestFixProcessingNs = processingNs ?: it.latestFixProcessingNs)
            }
        }
    }

    private fun publish(update: (LiveState) -> LiveState) {
        val state = LiveSessions.state.value
        if (token != 0L && state.token == token) LiveSessions.state.value = update(state)
    }

    private fun finish(issue: LiveIssue? = null) {
        if (LiveSessions.state.value.phase !in setOf(SessionPhase.STARTING, SessionPhase.ACTIVE)) return
        publish { LiveState(SessionPhase.STOPPING, token = token, issue = issue) }
        release()
        stopSelf()
    }

    private fun release() {
        subscription?.close()
        subscription = null
        inbox?.cancel()
        inbox = null
        job?.cancel()
        job = null
        marker?.edit()?.remove("inProgress")?.apply()
        marker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        release()
        val state = LiveSessions.state.value
        val issue = if (state.phase == SessionPhase.STOPPING) state.issue else LiveIssue.INTERRUPTED
        publish { LiveState(if (issue == null) SessionPhase.IDLE else SessionPhase.ERROR, issue = issue) }
        token = 0
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(token: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, LiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, LiveSessions.command(this, LiveSessions.STOP, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        val action = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.stop_tracking), stop).build()
        return Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notification_tracking))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
    }

    private companion object {
        const val notificationId = 1
        const val channel = "live-location"
        const val fixCapacity = 16
        const val tickNanos = 200_000_000L
    }
}
