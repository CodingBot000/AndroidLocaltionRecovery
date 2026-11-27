package lab.recovery.live

import androidx.annotation.StringRes

@StringRes
internal fun Precision.labelResource(): Int = when (this) {
    Precision.NONE -> R.string.live_permission_none
    Precision.COARSE -> R.string.live_permission_coarse
    Precision.FINE -> R.string.live_permission_fine
}

@StringRes
internal fun SessionPhase.labelResource(): Int = when (this) {
    SessionPhase.IDLE -> R.string.live_phase_idle
    SessionPhase.STARTING -> R.string.live_phase_starting
    SessionPhase.ACTIVE -> R.string.live_phase_active
    SessionPhase.STOPPING -> R.string.live_phase_stopping
    SessionPhase.ERROR -> R.string.live_phase_error
}

@StringRes
internal fun FixStatus.labelResource(): Int = when (this) {
    FixStatus.WAITING -> R.string.live_fix_waiting
    FixStatus.COARSE_ONLY -> R.string.live_fix_coarse
    FixStatus.NO_ROUTE -> R.string.live_fix_no_route
    FixStatus.OUTSIDE_COVERAGE -> R.string.live_fix_outside
    FixStatus.PROJECTION_UNAVAILABLE -> R.string.live_fix_projection
    FixStatus.LOW_QUALITY -> R.string.live_fix_quality
    FixStatus.INVALID_FIX -> R.string.live_fix_invalid
    FixStatus.STALE_FIX -> R.string.live_fix_stale
    FixStatus.TRACKING -> R.string.live_fix_tracking
}

@StringRes
internal fun LiveIssue.labelResource(): Int = when (this) {
    LiveIssue.PERMISSION_REQUIRED -> R.string.live_issue_permission_required
    LiveIssue.PERMISSION_CHANGED -> R.string.live_issue_permission_changed
    LiveIssue.LOCATION_DISABLED -> R.string.live_issue_location_disabled
    LiveIssue.PROVIDER_MISSING -> R.string.live_issue_provider_missing
    LiveIssue.PROVIDER_DISABLED -> R.string.live_issue_provider_disabled
    LiveIssue.PROVIDER_TEMPORARY -> R.string.live_issue_provider_temporary
    LiveIssue.START_REJECTED -> R.string.live_issue_start_rejected
    LiveIssue.MAP_UNAVAILABLE -> R.string.live_issue_map_unavailable
    LiveIssue.INTERRUPTED -> R.string.live_issue_interrupted
}

@StringRes
internal fun fixSourceResource(emulator: Boolean, mock: Boolean): Int = when {
    emulator && mock -> R.string.live_source_emulator_mock
    emulator -> R.string.live_source_emulator
    mock -> R.string.live_source_mock
    else -> R.string.live_source_android
}
