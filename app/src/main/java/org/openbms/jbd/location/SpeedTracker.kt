package org.openbms.jbd.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openbms.jbd.model.SpeedUnit

class SpeedTracker(private val context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val _speedVal = MutableStateFlow(0.0f)
    val speedVal = _speedVal.asStateFlow()

    private var activeUnit = SpeedUnit.OFF
    private var isTracking = false

    fun setUnit(unit: SpeedUnit) {
        activeUnit = unit
        if (unit == SpeedUnit.OFF) {
            stop()
        } else {
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (isTracking || locationManager == null) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1.0f,
                    this
                )
                isTracking = true
            }
        } catch (_: SecurityException) { }
    }

    fun stop() {
        if (!isTracking) return
        locationManager?.removeUpdates(this)
        _speedVal.value = 0.0f
        isTracking = false
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) {
            _speedVal.value = 0.0f
            return
        }
        val speedMps = location.speed
        _speedVal.value = when (activeUnit) {
            SpeedUnit.KM_H -> speedMps * 3.6f
            SpeedUnit.NMI_H -> speedMps * 1.943844f
            SpeedUnit.OFF -> 0.0f
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        _speedVal.value = 0.0f
    }
    @Deprecated("Deprecated in Android API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
