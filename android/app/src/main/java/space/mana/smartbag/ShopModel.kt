package space.mana.smartbag

import android.app.Application
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

sealed class StoreState {
    object Locating : StoreState()
    object NoLocation : StoreState()
    data class InStore(val id: String, val name: String, val address: String, val leaving: Boolean) : StoreState()
    data class Away(val nearestName: String?, val nearestDistance: Int?) : StoreState()
    data class Offline(val message: String) : StoreState()
}

/**
 * App-wide shopping session (kept alive by WatchService while the phone is in a pocket).
 * The database is the source of truth: every call returns the shopper's full priced bag, and
 * payment happens in the database the moment a location fix shows the shopper has left the store.
 */
const val DEMO_CARD = "Demo Visa •••• 4242"

class ShopModel(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("smartbag", 0)

    var bag by mutableStateOf<BagState>(BagState.Off); private set
    var store by mutableStateOf<StoreState>(StoreState.Locating); private set
    var payment by mutableStateOf(""); private set
    var total by mutableStateOf(0); private set
    var count by mutableStateOf(0); private set
    var paid by mutableStateOf<Receipt?>(null); private set          // receipt to show after a walk-out
    var lastTrip by mutableStateOf<Receipt?>(null); private set      // most recent receipt, reopenable from the shop screen
    var flash by mutableStateOf<Pair<String, Int>?>(null); private set
    var syncError by mutableStateOf<String?>(null); private set
    val lines = mutableStateListOf<Line>()

    var watching = true                    // false if Android refused to keep the foreground service running
    var serviceRunning = false             // the ongoing notification belongs to WatchService; never post it before that
    private var settingCard = false
    private val foreground get() = (app as SmartBagApp).foreground   // app on screen: receipt shows in-app, no heads-up

    private var fix: Location? = null
    private var sentAt: Location? = null
    private var sentTime = 0L
    private var locating = false
    private var flashN = 0
    private var ignoreFixesUntil = 0L

    // Everything goes to the database strictly in order, one call at a time.
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    val link: BagLink
    val location: PhoneLocation

    init {
        Api.init(app)
        link = BagLink(app, ::onLine) { s -> onBag(s) }
        location = PhoneLocation(app) { onFix(it) }
        scope.launch {
            for (op in ops) {
                try { op(); syncError = null } catch (e: Exception) {
                    syncError = e.message ?: "Couldn't reach Bag It."
                    if (store is StoreState.Locating) store = StoreState.Offline(syncError!!)
                }
            }
        }
        bootstrap()
    }

    /** First call: read the shopper and give them the demo card. Rerun by Try again if it failed (offline at launch). */
    private fun bootstrap() = ops.trySend {
        var s = Api.current()
        if (s.payment.isEmpty()) s = Api.setPayment(DEMO_CARD)   // demo: no real card is stored
        apply(s, fromLocation = false)
    }

    /** Try again: re-read the bag (and redo the first-run setup if it never landed), then re-check the store. */
    fun retry() { bootstrap(); refreshStore() }

    private fun apply(s: CartState, fromLocation: Boolean) {
        payment = s.payment
        store = when {
            s.storeId.isNotEmpty() -> StoreState.InStore(s.storeId, s.storeName, s.storeAddress, s.leaving)
            // Try again after being offline, with no location result yet: keep looking rather than claim "not in a store".
            !fromLocation && store is StoreState.Offline -> StoreState.Locating
            fromLocation || store !is StoreState.Locating -> StoreState.Away(s.nearestName.ifEmpty { null }, if (s.nearestName.isEmpty()) null else s.nearestM)
            else -> store
        }
        lines.clear(); lines.addAll(s.lines.sortedByDescending { it.addedMicros })   // newest first: the item you just dropped in is on top
        total = s.totalCents; count = s.itemCount
        val r = s.lastReceipt
        lastTrip = r
        val seen = prefs.getLong("seen_order", -1L)
        if (r != null && seen >= 0 && r.id > seen) {           // the database just charged a walk-out
            paid = r
            link.send("PAID ${r.code}")
            if (!foreground) Notify.paid(app, r)
        }
        if (r != null && r.id > seen || seen < 0) prefs.edit().putLong("seen_order", r?.id ?: 0L).apply()
        if (serviceRunning || !watching) Notify.status(app, statusLine())
        // First-run setup failed earlier (offline at launch): give the shopper the demo card as soon as calls work again.
        if (s.payment.isEmpty() && !settingCard) {
            settingCard = true
            ops.trySend { try { apply(Api.setPayment(DEMO_CARD), false) } finally { settingCard = false } }
        }
    }

    fun statusLine(): String = if (!watching) "Bag It paused. Open the app to keep watching for your walk-out." else when (val s = store) {
        is StoreState.InStore -> if (s.leaving) "Leaving ${s.name}…" else if (count > 0) "${money(total)} in your bag at ${s.name}. Just walk out." else "Shopping at ${s.name}"
        is StoreState.Away -> "Not in a Bag It store"
        else -> "Watching your bag"
    }

    private fun onBag(s: BagState) {
        val before = bag
        bag = s
        val name = (s as? BagState.Connected)?.name
        if ((s is BagState.Connected) != (before is BagState.Connected)) ops.trySend { apply(Api.setBag(name ?: ""), false) }
    }

    fun locationUnavailable() { if (store !is StoreState.InStore) store = StoreState.NoLocation }

    private fun onFix(l: Location) {
        if (System.currentTimeMillis() < ignoreFixesUntil) return
        fix = l
        if (store is StoreState.NoLocation) store = StoreState.Locating
        val q = sentAt
        val age = System.currentTimeMillis() - sentTime
        // Inside a store we report often, so leaving is noticed within seconds.
        val due = q == null || l.distanceTo(q) > 10f || age > (if (store is StoreState.InStore) 8_000 else 20_000)
        if (due) refreshStore()
    }

    fun refreshStore() {
        val l = fix ?: return
        if (locating) return
        locating = true
        sentAt = l; sentTime = System.currentTimeMillis()
        ops.trySend {
            try { apply(Api.locate(l.latitude, l.longitude, l.accuracy), true) } finally { locating = false }
        }
    }

    private fun onLine(raw: String) {
        val ev = Protocol.parse(raw)
        ops.trySend {
            val before = if (ev != null) lines.firstOrNull { it.sku == ev.sku }?.qty ?: 0 else 0
            apply(Api.bagLine(raw), false)
            if (ev != null) {
                val l = lines.firstOrNull { it.sku == ev.sku }
                if (ev.op == "add") {
                    // The coral flash means money moved: only when a priced item's quantity actually went up.
                    if (l != null && l.sold && l.qty > before) {
                        val n = ++flashN
                        flash = ev.sku to n
                        scope.launch { delay(1600); if (flash?.second == n) flash = null }
                    }
                    link.send(if (l != null && l.sold) "OK +${ev.sku} " + String.format(Locale.US, "%.2f", l.priceCents / 100.0) else "ERR ${ev.sku} unknown")
                } else link.send("OK -${ev.sku}")
            }
        }
    }

    fun dismissLine(sku: String) { ops.trySend { apply(Api.removeItem(sku), false) } }

    /** Hidden demo aid (long-press the bag status): behaves exactly as if the bag had read a random item. */
    fun demoDrop() { onLine("+${(1001..1016).random()}@DEMO${(1000..9999).random()}") }

    /**
     * Hidden demo aid (long-press the store name): reports two accurate fixes 300 m north of where
     * the phone is, exactly what walking out produces, then pauses real GPS for a minute.
     */
    fun demoWalkOut() {
        val l = fix ?: return
        ignoreFixesUntil = System.currentTimeMillis() + 60_000
        val lat = l.latitude + 300.0 / 111_320.0
        // Pause between the two fixes so the "Leaving" beat is visible, as it would be on a real walk out the door.
        ops.trySend { apply(Api.locate(lat, l.longitude, 8f), true); delay(1800) }
        ops.trySend { apply(Api.locate(lat + 0.0002, l.longitude, 8f), true) }
    }

    fun finishReceipt() { paid = null }
}
