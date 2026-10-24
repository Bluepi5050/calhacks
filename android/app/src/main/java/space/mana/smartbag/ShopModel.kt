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
/** Pairing flow state. [scan]: "scanning", "off" (Bluetooth off), "denied", or an error message. */
data class PairUi(val scan: String, val found: List<FoundBag>, val startedAt: Long, val probe: Probe? = null)
/** The bag the shopper tapped: connecting, then connected (HELLO sent, light blinking), [hello] = its "BAG …" reply. */
data class Probe(val address: String, val bleName: String, val connected: Boolean = false, val hello: String? = null,
                 val error: String? = null, val helloCount: Int = 0)

class ShopModel(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("smartbag", 0)

    var bag by mutableStateOf<BagState>(BagState.Off); private set     // what the shopper sees (a demo bag counts as connected)
    private var linkBag: BagState = BagState.Off                         // what Bluetooth reports
    /** The shopper's account. Cached on the phone (no password, just what the server returned) for a fast launch. */
    var account by mutableStateOf(loadCachedAccount()); private set
    var trips by mutableStateOf<List<Receipt>?>(null); private set
    var tripsError by mutableStateOf<String?>(null); private set
    var tripsLoading by mutableStateOf(false); private set
    var pairing by mutableStateOf<PairUi?>(null); private set
    /** Server push for this phone; null until asked. Without FCM in the build, only push_status is used. */
    var push by mutableStateOf<PushState?>(null); private set
    /** A receipt a notification asked to open (tap routing). */
    var openReceipt by mutableStateOf<String?>(null)
    val notifPrefs = NotifPrefs(app)
    var notifVersion by mutableStateOf(0); private set          // bumps when a notification switch changes (UI re-reads prefs)
    // Store id of the current visit (arrival notifies once); persisted so a process restart in the store doesn't repeat it.
    private var visit: String? = app.getSharedPreferences("smartbag", 0).getString("visit_store", null)
        set(v) { field = v; app.getSharedPreferences("smartbag", 0).edit().putString("visit_store", v).apply() }
    var geofencesOk by mutableStateOf(Geofences.ok(app)); private set
    @Volatile var geoWoken = false                               // this session was started by a geofence, not by the shopper
    private var awaySince = 0L
    private var bagCheck = 0
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
        link = BagLink(app, ::onLine, { s -> onBag(s) }, ::onControl)
        location = PhoneLocation(app) { onFix(it) }
        scope.launch {
            for (op in ops) {
                try { op(); syncError = null } catch (e: Exception) {
                    syncError = friendly(e)
                    if (store is StoreState.Locating) store = StoreState.Offline(syncError!!)
                }
            }
        }
        applyBags(account)
        bootstrap()
    }

    /** First call: refresh the account (shown from cache meanwhile) and the bag. Rerun by Try again if offline at launch. */
    private fun bootstrap() = ops.trySend {
        val (a, raw) = Api.accountState()
        setAccount(a, raw)
        apply(Api.current(), fromLocation = false)
        if (a.signedIn) syncPush()
    }

    // ---------------------------------------------------------------- push + geofences

    /** Register this phone for server push (if this build has FCM) or just read the server's push state. */
    fun syncPush() {
        val fetch = PushHooks.tokenFetcher
        if (account?.signedIn != true) return
        if (fetch == null) { ops.trySend { push = try { Api.pushStatus() } catch (_: Exception) { push } }; return }
        fetch { token -> if (token != null) onPushToken(token) else ops.trySend { push = try { Api.pushStatus() } catch (_: Exception) { push } } }
    }

    /** A new FCM token (first launch, refresh). Cheap and idempotent on the server. */
    fun onPushToken(token: String) {
        scope.launch {
            if (account?.signedIn != true) return@launch
            ops.trySend { push = try { Api.pushRegister(token, notifPrefs.serverPrefs()) } catch (_: Exception) { push } }
        }
    }

    fun setNotif(k: NotifKind, on: Boolean) {
        notifPrefs.set(k, on); notifVersion++
        if (k == NotifKind.Bag && !on) Notify.cancelBag(app)
        if (k == NotifKind.Store && !on) Notify.cancelArrival(app)
        if ((k == NotifKind.Trips || k == NotifKind.Security) && push?.registered == true)
            ops.trySend { push = try { Api.pushSetPrefs(notifPrefs.serverPrefs()) } catch (_: Exception) { push } }
    }

    /** Test: through the server when this phone is registered and the server can push; otherwise a local one. */
    fun sendTest(done: (String) -> Unit) {
        val p = push
        if (p != null && p.registered && p.serverPush) {
            ops.trySend {
                val r = try { Api.pushTest() } catch (e: Exception) { friendly(e) }
                done(if (r.startsWith("sent 1/1")) "Sent. It should arrive in a few seconds." else "The server couldn't send it ($r). Here's one from this phone.")
                if (!r.startsWith("sent 1/1")) Notify.test(app)
            }
        } else { Notify.test(app); done("Sent from this phone.") }
    }

    private var geoAt = 0L
    private var geoFix: Location? = null
    /** Keep geofences around the nearest stores (every 15 min or 1 km), only with "Allow all the time". */
    private fun maybeRefreshGeofences(l: Location, force: Boolean = false) {
        if (account?.signedIn != true || !Geofences.hasBackground(app)) return
        val last = geoFix
        if (!force && last != null && System.currentTimeMillis() - geoAt < 15 * 60_000L && l.distanceTo(last) < 1000f) return
        geoAt = System.currentTimeMillis(); geoFix = l
        ops.trySend {
            try {
                val s = Api.nearbyStores(l.latitude, l.longitude, 20)
                Geofences.save(app, s)
                Geofences.register(app, s) { ok -> geofencesOk = ok; if (!ok) geoAt = 0L }
            } catch (_: Exception) { geoAt = 0L }                    // offline: try again on the next fix, not in 15 min
        }
    }
    fun refreshGeofences() { fix?.let { maybeRefreshGeofences(it, force = true) } ?: Geofences.register(app) { geofencesOk = it } }

    /** Try again: re-read the account and the bag, then re-check the store. */
    fun retry() { bootstrap(); refreshStore() }

    // ---------------------------------------------------------------- account

    /** Shopper copy for any failure: the module's own messages pass through; network internals never do. */
    private fun friendly(e: Exception): String = (e as? ApiException)?.message ?: "Couldn't reach Bag It. Check your connection."

    /** Receipts at or below this id belong to whoever used this phone before the current sign-in: hide them. */
    private var tripFloor = prefs.getLong("trip_floor", 0L)
    /** True while the demo bag stands in for a real one (no Bluetooth bag connected). */
    val demoStandIn: Boolean get() = linkBag !is BagState.Connected && demoOnly

    private fun loadCachedAccount(): AccountState? = try {
        prefs.getString("account_v5", null)?.let { Api.decodeAccount(java.util.Base64.getDecoder().decode(it)) }?.takeIf { it.signedIn }
    } catch (_: Exception) { null }

    private fun setAccount(a: AccountState, raw: ByteArray) {
        val wasIn = account?.signedIn == true
        account = a
        if (a.signedIn) {
            prefs.edit().putString("account_v5", java.util.Base64.getEncoder().encodeToString(raw)).apply()
            payment = a.payment
        } else {
            prefs.edit().remove("account_v5").apply()
            payment = ""
            if (wasIn) signedOutHere()
        }
        // v5.2 clears the bag name on every account change: send it again after sign-in / sign-up.
        if (a.signedIn && !wasIn) sentBag = null
        applyBags(a)
        if (a.signedIn && !wasIn) { syncPush(); fix?.let { maybeRefreshGeofences(it, force = true) } }
    }

    /** Signed out (here, on another phone's delete, or after delete): forget the bag and stop watching for walk-outs. */
    private fun signedOutHere() {
        trips = null; tripsError = null
        // The phone's identity outlives the account: don't show this account's last receipt to the next one.
        tripFloor = maxOf(tripFloor, lastTrip?.id ?: 0L, prefs.getLong("seen_order", 0L))
        prefs.edit().putLong("trip_floor", tripFloor).apply()
        paid = null; lastTrip = null; flash = null; syncError = null
        pairing = null
        link.allowed = emptySet()
        link.disconnect()
        location.stop()
        WatchService.stop(app)
        Geofences.clear(app)                 // a signed-out phone isn't woken by stores
        geofencesOk = null
        push = null; visit = null; openReceipt = null
        Notify.clearAccount(app)             // the next person on this phone can't open the previous account's receipts
        Notify.cancelBag(app); Notify.cancelArrival(app)
        lines.clear(); total = 0; count = 0
    }

    /**
     * Run an account call in order with everything else. [done] gets null on success or the server's message
     * (already shopper copy). [then] runs after success inside the same queue slot (e.g. refresh the bag).
     */
    fun accountCall(call: suspend () -> Pair<AccountState, ByteArray>, done: (String?) -> Unit = {}, then: (suspend () -> Unit)? = null) {
        ops.trySend {
            val ok = try { val (a, raw) = call(); setAccount(a, raw); true } catch (e: Exception) { done(friendly(e)); false }
            if (ok) {
                done(null)                                   // before the follow-up round trip: the UI moves on now
                try { then?.invoke() } catch (_: Exception) {}
            }
        }
    }

    /** Sign in / sign up also copy the default card onto this phone; re-read the bag so the dock says so. */
    fun refreshBag(): suspend () -> Unit = { apply(Api.current(), false) }

    fun loadTrips() {
        if (tripsLoading || account?.signedIn != true) return
        tripsLoading = true
        ops.trySend {
            try { trips = Api.trips(50); tripsError = null }
            catch (e: Exception) { tripsError = (e as? ApiException)?.message ?: "Couldn't load your trips. Check your connection." }
            finally { tripsLoading = false }
        }
    }

    /** Auto-connect only to the account's bags (any bag if none, or only the demo bag, is paired). */
    private fun applyBags(a: AccountState?) {
        val real = a?.bags.orEmpty().filter { !it.isDemo }.map { it.bagId }.toSet()
        val before = link.allowed
        link.allowed = real
        val cur = link.currentName
        if (linkBag is BagState.Connected && pairing == null && cur !in real) {
            when {
                cur in before && real.isEmpty() -> link.disconnect()   // unpaired our only bag: stop listening to it
                real.isNotEmpty() -> link.connect()                    // connected to a bag that isn't ours: look for ours
            }
        }
        updateBag()
    }

    private val demoOnly get() = account?.signedIn == true && account!!.bags.isNotEmpty() && account!!.bags.all { it.isDemo }

    private var sentBag: String? = null
    private fun updateBag() {
        val now = if (linkBag !is BagState.Connected && demoOnly) BagState.Connected("Demo bag") else linkBag
        val was = bag
        bag = now
        // Bag alert: disconnected for 20 s in a store with items, and Bag It not on screen. Reconnecting clears it.
        if (now is BagState.Connected) Notify.cancelBag(app)
        else if (was is BagState.Connected && pairing == null) {
            val n = ++bagCheck
            scope.launch {
                delay(20_000)
                if (n == bagCheck && NotifyRules.warnBag(bag is BagState.Connected, store is StoreState.InStore, count, foreground)) Notify.bagLost(app)
            }
        }
        // Tell the database which bag is shopping, but not about a bag that is only being checked during pairing.
        val name = (now as? BagState.Connected)?.name ?: ""
        if (pairing == null && account?.signedIn == true && name != sentBag) { sentBag = name; ops.trySend { apply(Api.setBag(name), false) } }
    }

    // ---------------------------------------------------------------- pairing

    fun startPairing() {
        pairing = PairUi("scanning", emptyList(), System.currentTimeMillis())
        val err = link.scanForPairing({ list ->
            val p = pairing ?: return@scanForPairing
            if (p.probe == null) pairing = p.copy(scan = "scanning", found = list)
        }, { failed -> pairing = pairing?.copy(scan = failed) })
        if (err != null) pairing = pairing?.copy(scan = err)
    }

    /** Tap a bag in the list: connect, then ask it to blink (HELLO) every few seconds while the shopper checks. */
    fun probe(b: FoundBag) {
        pairing = (pairing ?: PairUi("scanning", emptyList(), System.currentTimeMillis())).copy(probe = Probe(b.address, b.bleName))
        link.connectTo(b.address, b.bleName)
        scope.launch {
            delay(12_000)
            val p = pairing?.probe
            if (p != null && p.address == b.address && !p.connected && p.error == null)
                pairing = pairing?.copy(probe = p.copy(error = "Couldn't connect to ${bagDisplayName(b.bleName)}. Make sure it's on and close by."))
        }
    }

    private fun hello() {
        val p = pairing?.probe ?: return
        if (!p.connected) return
        link.send("HELLO")
        pairing = pairing?.copy(probe = p.copy(helloCount = p.helloCount + 1))
        val n = p.helloCount + 1
        scope.launch { delay(3_000); if (pairing?.probe?.helloCount == n) hello() }
    }

    private fun onControl(line: String) {
        val p = pairing?.probe ?: return
        // "BAG SmartBag-3F2A fw2": the bag in the shopper's hand answered.
        pairing = pairing?.copy(probe = p.copy(hello = line.trim().removePrefix("BAG ").trim()))
    }

    /** "Yes, that's my bag": stop asking it to blink. */
    fun confirmProbe() { pairing?.probe?.let { pairing = pairing?.copy(probe = it.copy(helloCount = -1)) } }

    /** "No, try another": drop this bag and go back to the list. */
    fun rejectProbe() {
        pairing = pairing?.copy(probe = null)
        startPairing()
    }

    fun pair(bleName: String, name: String, done: (String?) -> Unit) = accountCall({ Api.bagPair(bleName, name) }, done)
    fun pairDemo(done: (String?) -> Unit) = accountCall({ Api.bagPair(DEMO_BAG, "Demo bag") }, done)

    /** Leave the pairing flow: stop listing bags and go back to connecting the account's bags. */
    fun endPairing() {
        // Keep the connection only if it is the bag just paired; otherwise go back to auto-connecting.
        val keep = pairing?.probe?.takeIf { it.connected && it.bleName in link.allowed && linkBag is BagState.Connected }
        pairing = null
        link.stopPairScan()
        if (keep == null) link.connect()
        updateBag()
    }

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
        val r = s.lastReceipt?.takeIf { it.id > tripFloor }
        lastTrip = r
        val seen = prefs.getLong("seen_order", -1L)
        if (r != null && seen >= 0 && r.id > seen) {           // the database just charged a walk-out
            paid = r
            // The bag's light says "paid" only when it really was; a payment-due trip leaves it dark.
            if (r.status == "paid") link.send("PAID ${r.code}")
            // In the foreground the receipt is on screen; still claim the code so a server push can't repeat it.
            if (!foreground) Notify.receipt(app, r) else Notify.claim(app, r.code)
            trips?.let { t -> trips = listOf(r) + t.filter { it.id != r.id } }
        }
        if (r != null && r.id > seen || seen < 0) prefs.edit().putLong("seen_order", r?.id ?: 0L).apply()
        if (serviceRunning || !watching) Notify.status(app, statusLine())
        // Store arrival: once per visit, only when Bag It isn't on screen.
        val (v, arrive) = NotifyRules.arrival(visit, store, foreground)
        if (v != visit && v == null) Notify.cancelArrival(app)
        if (v != visit) visit = v
        if (arrive && account?.signedIn == true) (store as? StoreState.InStore)?.let { Notify.arrival(app, it.name, payment) }
        // A session Android started from a geofence ends after 10 min away from any store with Bag It closed.
        if (store is StoreState.Away) { if (awaySince == 0L) awaySince = System.currentTimeMillis() } else awaySince = 0L
        if (geoWoken && !foreground && awaySince > 0 && System.currentTimeMillis() - awaySince > 10 * 60_000L) {
            geoWoken = false; location.stop(); WatchService.stop(app)
        }
    }

    fun statusLine(): String = if (!watching) "Bag It paused. Open the app to keep watching for your walk-out." else when (val s = store) {
        is StoreState.InStore -> if (s.leaving) "Leaving ${s.name}…" else if (count > 0) "${money(total)} in your bag at ${s.name}. Just walk out." else "Shopping at ${s.name}"
        is StoreState.Away -> "Not in a Bag It store"
        else -> "Watching your bag"
    }

    private fun onBag(s: BagState) {
        linkBag = s
        val p = pairing?.probe
        if (p != null) {
            when {
                s is BagState.Connected && !p.connected -> { pairing = pairing?.copy(probe = p.copy(connected = true)); hello() }
                s is BagState.Problem -> pairing = pairing?.copy(probe = p.copy(error = s.message, connected = false))
            }
        }
        updateBag()
    }

    fun locationUnavailable() { if (store !is StoreState.InStore) store = StoreState.NoLocation }

    private fun onFix(l: Location) {
        if (System.currentTimeMillis() < ignoreFixesUntil) return
        fix = l
        maybeRefreshGeofences(l)
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
        if (isControlLine(raw)) { onControl(raw); return }      // never an item
        if (pairing != null || account?.signedIn != true) return  // not shopping: checking a bag, or signed out
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
