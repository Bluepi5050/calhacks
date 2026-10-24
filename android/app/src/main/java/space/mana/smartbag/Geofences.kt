package space.mana.smartbag

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject

/** Decisions behind local notifications and geofences. Pure, unit tested. */
object NotifyRules {
    /** Store arrival: once per visit. Returns the new visit id and whether to notify. */
    fun arrival(visit: String?, store: StoreState, foreground: Boolean): Pair<String?, Boolean> = when (store) {
        is StoreState.InStore -> if (store.id != visit) store.id to !foreground else visit to false
        is StoreState.Away -> null to false
        else -> visit to false                                     // locating / offline: keep the visit
    }
    /** Warn about a lost bag only while it matters: in a store, with items, not looking at the app. */
    fun warnBag(connected: Boolean, inStore: Boolean, count: Int, foreground: Boolean) = !connected && inStore && count > 0 && !foreground
    /** A geofence transition starts the shopping session (which then reports fixes, and charges on the way out). */
    fun geofenceAction(transition: Int): String? = when (transition) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
        Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
        else -> null
    }
    /** Geofence radius: the store's radius plus the same 40 m margin the module uses for leaving. */
    fun radius(s: NearbyStore) = (s.radiusM + 40).toFloat()
}

/**
 * Background geofences around the nearest stores (Android allows 100; we use up to 20). They let Android wake Bag It
 * when you walk into or out of a store while the app is closed. Needs "Allow all the time" location; Android may still
 * delay transitions, and nothing is delivered after a force-stop until the app is opened again.
 */
object Geofences {
    private const val PREF = "geofence_stores"

    fun hasBackground(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    fun save(ctx: Context, stores: List<NearbyStore>) {
        val a = JSONArray()
        stores.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lng", it.lng).put("r", it.radiusM)) }
        ctx.getSharedPreferences("smartbag", 0).edit().putString(PREF, a.toString()).apply()
    }

    fun cached(ctx: Context): List<NearbyStore> = try {
        val a = JSONArray(ctx.getSharedPreferences("smartbag", 0).getString(PREF, "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { NearbyStore(it.getString("id"), it.getString("name"), "", it.getDouble("lat"), it.getDouble("lng"), it.getInt("r"), 0) } }
    } catch (_: Exception) { emptyList() }

    /** Replace the registered geofences with [stores] (or the cached set). Returns false if Android can't take them. */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context, stores: List<NearbyStore> = cached(ctx), onDone: ((Boolean) -> Unit)? = null): Boolean {
        if (stores.isEmpty() || !hasBackground(ctx)) return false
        val fences = stores.take(20).map {
            Geofence.Builder().setRequestId(it.id).setCircularRegion(it.lat, it.lng, NotifyRules.radius(it))
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setExpirationDuration(Geofence.NEVER_EXPIRE).build()
        }
        return try {
            val client = LocationServices.getGeofencingClient(ctx)
            val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
            // Add only after the old set is gone, and record whether Android actually took them (location off,
            // no Play services, limits): the Notifications screen only claims background arrivals when it did.
            client.removeGeofences(intent(ctx)).continueWithTask { client.addGeofences(req, intent(ctx)) }
                .addOnCompleteListener { t -> setOk(ctx, t.isSuccessful); onDone?.invoke(t.isSuccessful) }
            true
        } catch (_: Exception) { setOk(ctx, false); onDone?.invoke(false); false }
    }

    private fun setOk(ctx: Context, ok: Boolean) { ctx.getSharedPreferences("smartbag", 0).edit().putBoolean("geofences_ok", ok).apply() }
    /** null = never tried on this phone. */
    fun ok(ctx: Context): Boolean? = ctx.getSharedPreferences("smartbag", 0).let { if (it.contains("geofences_ok")) it.getBoolean("geofences_ok", false) else null }

    fun clear(ctx: Context) {
        try { LocationServices.getGeofencingClient(ctx).removeGeofences(intent(ctx)) } catch (_: Exception) {}
        ctx.getSharedPreferences("smartbag", 0).edit().remove(PREF).remove("geofences_ok").apply()
    }

    fun signedIn(ctx: Context) = ctx.getSharedPreferences("smartbag", 0).contains("account_v5")
}

/** Android tells us the phone crossed a store's geofence: start the shopping session (an allowed background start). */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = try { GeofencingEvent.fromIntent(intent) } catch (_: Exception) { null } ?: return
        if (ev.hasError()) return
        val action = NotifyRules.geofenceAction(ev.geofenceTransition) ?: return
        if (!Geofences.signedIn(ctx)) return
        // The session reports location fixes; the database decides arrival and charges after two fixes outside.
        WatchService.start(ctx, reason = action)
    }
}

/** Geofences don't survive a reboot or an app update: put them back from the cached store list. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Geofences.signedIn(ctx)) Geofences.register(ctx)
    }
}
