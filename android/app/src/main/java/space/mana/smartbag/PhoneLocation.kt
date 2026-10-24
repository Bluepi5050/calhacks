package space.mana.smartbag

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** GPS + network location, keeping the best recent fix. */
@SuppressLint("MissingPermission")
class PhoneLocation(ctx: Context, private val onFix: (Location) -> Unit) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var best: Location? = null
    private var running = false

    val enabled: Boolean
        get() = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    fun start() {
        if (running) return
        running = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.allProviders.contains(p)) continue
            try {
                lm.getLastKnownLocation(p)?.let { consider(it) }
                lm.requestLocationUpdates(p, 3000L, 5f, this, Looper.getMainLooper())
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { lm.removeUpdates(this) } catch (_: Exception) {}
    }

    private fun consider(l: Location) {
        val b = best
        if (b == null || l.time - b.time > 20_000 || l.accuracy <= b.accuracy) {
            best = l
            onFix(l)
        }
    }

    override fun onLocationChanged(location: Location) = consider(location)
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
