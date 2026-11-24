@file:Suppress("DEPRECATION")

package lab.recovery.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/** Called on the main looper, including registration, callbacks, and removal. */
internal class AndroidLocationSource(private val context: Context) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var registeredProvider: String? = null

    override fun precision() = when {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> Precision.FINE
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> Precision.COARSE
        else -> Precision.NONE
    }

    override fun issue(precision: Precision): LiveIssue? {
        if (precision == Precision.NONE) return LiveIssue.PERMISSION_REQUIRED
        if (Build.VERSION.SDK_INT >= 28 && !manager.isLocationEnabled) return LiveIssue.LOCATION_DISABLED
        val candidates = providers(precision).filter { it in manager.allProviders }
        if (candidates.isEmpty()) return LiveIssue.PROVIDER_MISSING
        val registered = registeredProvider
        val enabled = if (registered != null) manager.isProviderEnabled(registered) else candidates.any(manager::isProviderEnabled)
        return if (enabled) null else LiveIssue.PROVIDER_DISABLED
    }

    private fun providers(precision: Precision): List<String> =
        if (precision == Precision.FINE) listOf(LocationManager.GPS_PROVIDER)
        else if (Build.VERSION.SDK_INT >= 31) listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
        else listOf(LocationManager.NETWORK_PROVIDER)

    @SuppressLint("MissingPermission")
    override fun register(precision: Precision, fix: (RawFix) -> Unit, issue: (LiveIssue) -> Unit) {
        check(listener == null)
        val problem = issue(precision)
        if (problem != null) {
            issue(problem)
            return
        }
        val provider = providers(precision).firstOrNull { it in manager.allProviders && manager.isProviderEnabled(it) }
        if (provider == null) {
            issue(LiveIssue.PROVIDER_DISABLED)
            return
        }
        val registration = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (this === listener) fix(location.toRawFix(SystemClock.elapsedRealtimeNanos()))
            }

            override fun onProviderDisabled(provider: String) {
                if (this === listener) issue(LiveIssue.PROVIDER_DISABLED)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onProviderEnabled(provider: String) = Unit

            @Deprecated("Provider status callbacks can occur before Android Q")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                if (this === listener && status != LocationProvider.AVAILABLE) issue(LiveIssue.PROVIDER_TEMPORARY)
            }
        }
        listener = registration
        registeredProvider = provider
        try {
            manager.requestLocationUpdates(provider, 1_000L, 0f, registration, Looper.getMainLooper())
        } catch (_: SecurityException) {
            unregister()
            issue(LiveIssue.PERMISSION_CHANGED)
        } catch (_: IllegalArgumentException) {
            unregister()
            issue(LiveIssue.PROVIDER_MISSING)
        }
    }

    override fun unregister() {
        val current = listener ?: return
        listener = null
        registeredProvider = null
        manager.removeUpdates(current)
    }
}

private fun Location.toRawFix(receivedNs: Long) = RawFix(
    latitude, longitude,
    if (hasAccuracy()) accuracy.toDouble() else null,
    if (hasSpeed()) speed.toDouble() else null,
    if (hasBearing()) bearing.toDouble() else null,
    elapsedRealtimeNanos, receivedNs, provider,
    if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider,
)
