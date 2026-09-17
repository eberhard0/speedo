package com.iameberhard.speedo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel

/** Everything the screen shows. Speeds are m/s, converted to km/h or mph only for display. */
data class Trip(
    /** SystemClock.elapsedRealtime() of the newest fix; 0 until the first one arrives. */
    val lastFixMs: Long = 0L,
    val speedMps: Float = 0f,
    val accuracyM: Float? = null,
    val maxMps: Float = 0f,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0L,
) {
    val averageMps: Float
        get() = if (movingMs > 0L) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
}

/**
 * Reads speed straight from the GPS chip (Location.getSpeed() is Doppler-derived, so it stays
 * accurate at train speeds where position-difference estimates get noisy). Updates only run
 * between start() and stop(), i.e. while the app is on screen — nothing runs in the background.
 */
class SpeedViewModel(app: Application) : AndroidViewModel(app), LocationListener {

    private val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs = app.getSharedPreferences("speedo", Context.MODE_PRIVATE)

    var trip by mutableStateOf(Trip())
        private set
    var useMph by mutableStateOf(prefs.getBoolean(KEY_MPH, false))
        private set
    var gpsEnabled by mutableStateOf(true)
        private set
    /** Satellites used for the current position. */
    var satellites by mutableIntStateOf(0)
        private set
    /** Satellites whose signal is being received at all; climbs while still searching for a fix. */
    var satellitesInView by mutableIntStateOf(0)
        private set

    private var last: Location? = null
    private var running = false

    private val gnssCallback = object : GnssStatusCompat.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
            var used = 0
            var inView = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
                // The list also holds satellites known only from the almanac; count those actually heard.
                if (status.getCn0DbHz(i) > 0f) inView++
            }
            satellites = used
            satellitesInView = maxOf(inView, used)
        }
    }

    fun toggleUnit() {
        useMph = !useMph
        prefs.edit().putBoolean(KEY_MPH, useMph).apply()
    }

    fun resetTrip() {
        trip = trip.copy(maxMps = 0f, distanceM = 0.0, movingMs = 0L)
    }

    @SuppressLint("MissingPermission") // checked explicitly below
    fun start() {
        if (running) return
        val context = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper(),
            )
            LocationManagerCompat.registerGnssStatusCallback(
                locationManager, ContextCompat.getMainExecutor(context), gnssCallback,
            )
            running = true
        } catch (e: RuntimeException) {
            // No GPS provider on this device, or the permission was revoked mid-call.
            gpsEnabled = false
        }
    }

    fun stop() {
        if (!running) return
        locationManager.removeUpdates(this)
        LocationManagerCompat.unregisterGnssStatusCallback(locationManager, gnssCallback)
        satellites = 0
        satellitesInView = 0
        running = false
    }

    override fun onCleared() = stop()

    override fun onLocationChanged(location: Location) {
        val nowMs = location.elapsedRealtimeNanos / 1_000_000L
        val prev = last
        val previous = trip
        val dtS = if (prev != null) (nowMs - prev.elapsedRealtimeNanos / 1_000_000L) / 1000.0 else 0.0

        var speed = when {
            location.hasSpeed() -> location.speed
            prev != null && dtS > 0.2 -> (prev.distanceTo(location) / dtS).toFloat()
            else -> 0f
        }
        // Below walking pace the reading is mostly GPS jitter; show a steady 0 when standing still.
        if (speed < MIN_MOVING_MPS) speed = 0f

        var distance = previous.distanceM
        var moving = previous.movingMs
        if (prev != null && dtS > 0.0) {
            if (dtS <= MAX_STEP_S) {
                // Normal 1 Hz stream: integrate speed, which is smoother than chaining positions.
                val mean = (speed + previous.speedMps) / 2.0
                if (mean > 0.0) {
                    distance += mean * dtS
                    moving += (dtS * 1000).toLong()
                }
            } else if (dtS <= MAX_GAP_S) {
                // Signal gap (tunnel, app briefly in the background): bridge it in a straight line.
                val gap = prev.distanceTo(location)
                if (gap > prev.accuracy + location.accuracy && gap / dtS >= MIN_MOVING_MPS) {
                    distance += gap
                    moving += (dtS * 1000).toLong()
                }
            }
        }

        // A single wild fix must not become the trip maximum.
        val trustworthy = location.hasSpeed() &&
            (!location.hasAccuracy() || location.accuracy <= 50f) &&
            (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 2.5f)
        val max = if (trustworthy && speed > previous.maxMps) speed else previous.maxMps

        last = location
        trip = Trip(
            lastFixMs = nowMs,
            speedMps = speed,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            maxMps = max,
            distanceM = distance,
            movingMs = moving,
        )
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) gpsEnabled = true
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) gpsEnabled = false
    }

    // Abstract before Android 11; without this override older phones crash when it is called.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private companion object {
        const val KEY_MPH = "mph"
        const val MIN_MOVING_MPS = 0.5f
        const val MAX_STEP_S = 5.0
        const val MAX_GAP_S = 600.0
    }
}
