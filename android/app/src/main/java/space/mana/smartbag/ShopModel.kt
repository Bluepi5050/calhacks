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

private val FULL_CART = Regex("SmartCart-[0-9A-F]{4}")

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

    // ---------------------------------------------------------------- SmartCart (Jetson) state
    lateinit var cart: CartLink; private set
    val cartSync: CartSync
    private var cartBag: BagState = BagState.Off
    /** The hidden Hardware test screen's log (newest last). */
    val cartLog = mutableStateListOf<String>()
    var cartStatus by mutableStateOf<String?>(null); private set
    /** Backend session for this trip (contract §4); null when not in a store or the backend has no sessions. */
    var session by mutableStateOf<SessionState?>(null); private set
    var budgetCents by mutableStateOf(prefs.getInt("budget_cents", 0)); private set
    var recs by mutableStateOf<Recommendations?>(null); private set
    var summary by mutableStateOf<TripSummary?>(null); private set
    /** null = unknown, false = this database doesn't have the SmartCart procedures yet. */
    var hwBackend by mutableStateOf<Boolean?>(null); private set
    var lastHw by mutableStateOf<HwOutcome?>(null); private set
    var simulated by mutableStateOf(false); private set
    private var mock: MockCart? = null
    private var sessionAsked: String? = null
    private var recsGen = 0
    private var pumpRetry = 0
    private var rekeying = false

    init {
        Api.init(app)
        link = BagLink(app, ::onLine, { s -> onBag(s) }, ::onControl)
        location = PhoneLocation(app) { onFix(it) }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        cartSync = CartSync(
            write = { m -> if (simulated) mock?.control(m) else cart.write(m) },
            forward = { e -> val r = Api.hwEvent(e.sid, e.seq, e.action, e.label, e.confidence); onHw(r, e); HwOutcome(r.status, r.message) },
            store = object : QueueStore {
                override fun load() = prefs.getString("cart_queue", null)
                override fun save(json: String) { prefs.edit().putString("cart_queue", json).apply() }
            },
            log = ::clog,
        )
        cart = CartLink(app,
            onState = { s -> if (!simulated) { cartBag = s; onCartState(s) } },
            // A cart being checked during pairing (maybe not ours) never gets the protocol: no START to a stranger's cart.
            onReady = { if (!simulated && pairing == null && cart.name in cart.allowed) cartSync.onConnected() },
            onStatus = { t -> if (!simulated) cartStatusIn(t) },
            onEvent = { t -> if (!simulated) cartEventIn(t) },
            onDown = { if (!simulated) cartSync.onDisconnected() },
            log = ::clog)
        if (cartSync.pending.isNotEmpty()) main.post { pumpCart() }       // events left from before a restart
        scope.launch { while (true) { delay(5_000); cartSync.watchdog() } }
        // Contract v1.2 §3 "Lost session": drain the old sid's queue (terminal answers), rekey, persist, START the new sid.
        cartSync.onLost = lost@{ sid ->
            if (rekeying) return@lost true                       // already on it: never START the lost sid meanwhile
            val s = session
            if (s == null || s.sessionId != sid) return@lost false
            rekeying = true
            ops.trySend {
                try {
                    if (!cartSync.drain(sid)) { clog("rekey waits: backend unreachable (retried on the next STATUS)"); return@trySend }
                    val n = Api.sessionRekey()
                    clog("rekeyed $sid → ${n.sessionId}")
                    session = n
                    prefs.edit().putString("cart_session", "${n.sessionId}|${n.storeId}").apply()
                    cartSync.setSession(n.sessionId.takeIf { n.active }, n.storeId, n.lastSeq)
                } catch (e: Exception) { clog("rekey failed: ${e.message}") }
                finally { rekeying = false }
            }
            true
        }
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
    val demoStandIn: Boolean get() = physical !is BagState.Connected && demoOnly

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
        cartSync.setSession(null, null)      // STOP on the cart (if connected) before letting go of it
        cart.allowed = emptySet()
        cart.disconnectSoon()               // let the STOP go out first
        session = null; recs = null; summary = null; sessionAsked = null
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
        val all = a?.bags.orEmpty().filter { !it.isDemo }.map { it.bagId }
        val carts = all.filter(CartProtocol::isCartName).toSet()
        val real = all.filter { !CartProtocol.isCartName(it) }.toSet()
        val cartsBefore = cart.allowed
        cart.allowed = carts
        if (carts.isEmpty() && cartsBefore.isNotEmpty()) { cartSync.setSession(null, null); cart.disconnectSoon() }
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
    private val hasCarts get() = cart.allowed.isNotEmpty()
    private val hasNusBags get() = link.allowed.isNotEmpty()

    /** Connect what the account has: its carts (SmartCart service) and/or bags (NUS; any bag when none is paired). */
    fun connectAll() {
        if (hasCarts) cart.connect()
        if (hasNusBags || !hasCarts) link.connect()
    }

    /** The bag the shopper sees: a connected cart or bag wins; otherwise the device the account actually has. */
    private val physical: BagState get() = when {
        simulated -> BagState.Connected(mock?.device ?: "SmartCart-TEST")
        cartBag is BagState.Connected -> cartBag
        linkBag is BagState.Connected -> linkBag
        hasCarts && !hasNusBags -> cartBag
        else -> linkBag
    }

    private var sentBag: String? = null
    private fun updateBag() {
        val now = if (physical !is BagState.Connected && demoOnly) BagState.Connected("Demo bag") else physical
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
        // Bags (NUS) and carts (SmartCart service) show up in the same "Bags nearby" list; carts read "Cart XXXX".
        var nus = emptyList<FoundBag>(); var carts = emptyList<FoundBag>()
        fun merge() { val p = pairing ?: return; if (p.probe == null) pairing = p.copy(scan = "scanning", found = nus + carts) }
        val err = link.scanForPairing({ list -> nus = list; merge() }, { failed -> pairing = pairing?.copy(scan = failed) })
        if (err != null) pairing = pairing?.copy(scan = err)
        else cart.scanForPairing { list -> carts = list; merge() }
    }

    /** Tap a bag in the list: connect, then ask it to blink (HELLO) every few seconds while the shopper checks. */
    fun probe(b: FoundBag) {
        pairing = (pairing ?: PairUi("scanning", emptyList(), System.currentTimeMillis())).copy(probe = Probe(b.address, b.bleName))
        if (CartProtocol.isCartName(b.bleName)) cart.connectTo(b.address, b.bleName) else link.connectTo(b.address, b.bleName)
        scope.launch {
            // A cart may need a few quick reconnects (CartLink.firstAnswer), about 4.5 s each: give it room for them.
            delay(if (CartProtocol.isCartName(b.bleName)) 30_000 else 12_000)
            val p = pairing?.probe
            if (p != null && p.address == b.address && !p.connected && p.error == null)
                pairing = pairing?.copy(probe = p.copy(error = "Couldn't connect to ${bagDisplayName(b.bleName)}. Make sure it's on and close by."))
        }
    }

    private fun hello() {
        val p = pairing?.probe ?: return
        if (!p.connected || CartProtocol.isCartName(p.bleName)) return          // carts have no light: STATUS names them
        link.send("HELLO")
        pairing = pairing?.copy(probe = p.copy(helloCount = p.helloCount + 1))
        val n = p.helloCount + 1
        scope.launch { delay(3_000); if (pairing?.probe?.helloCount == n) hello() }
    }

    private fun onControl(line: String) {
        val p = pairing?.probe ?: return
        // "BAG SmartBag-3F2A fw3": the bag in the shopper's hand answered.
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
        val keep = pairing?.probe?.takeIf { it.connected && (it.bleName in link.allowed || it.bleName in cart.allowed) && physical is BagState.Connected }
        pairing = null
        link.stopPairScan(); cart.stopPairScan()
        // A cart that finished connecting during pairing never got the protocol (onReady skips it while pairing): the
        // one just checked, or ours reconnecting meanwhile. Ours: start it now (STATUS read → reconcile → START if in a
        // store). Someone else's: drop it so connectAll() can look for ours.
        if (!simulated && cart.ready) {
            if (cart.name in cart.allowed) { if (!cartSync.connected) { cartSync.onConnected(); cart.readStatus() } }
            else cart.disconnect()
        }
        if (keep == null) connectAll()
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
            // The trip is over: STOP the cart now (later detections can't change it) and fetch the trip summary.
            cartSync.setSession(null, null); session = null; recs = null; sessionAsked = null
            summary = null
            // v1.2 §4.6: shop_summary(0) after every walk-out; here it is this receipt's trip.
            if (hwBackend != false) ops.trySend { summary = try { Api.summary(0).takeIf { it.receipt.id == r.id } } catch (_: Exception) { null } }
            // The bag's light tells how the trip ended: PAID (one blink) or, for payment due, DUE (three slow blinks, fw v3;
            // older bags ignore DUE and stay dark).
            if (linkBag is BagState.Connected) link.send(walkOutSignal(r))   // never queued for a later connection
            // In the foreground the receipt is on screen; still claim the code so a server push can't repeat it.
            if (!foreground) Notify.receipt(app, r) else Notify.claim(app, r.code)
            trips?.let { t -> trips = listOf(r) + t.filter { it.id != r.id } }
        }
        if (r != null && r.id > seen || seen < 0) prefs.edit().putLong("seen_order", r?.id ?: 0L).apply()
        if (serviceRunning || !watching) Notify.status(app, statusLine())
        if (account?.signedIn == true || hasCarts) sessionFor((store as? StoreState.InStore)?.id)
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
        if (holdStore != null) { fix = l; return }                 // demo: pinned in the store, real GPS doesn't count
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
        holdStore?.let { h -> ops.trySend { apply(Api.locate(h.lat, h.lng, 5f), true) }; return }
        val l = fix ?: return
        if (locating) return
        locating = true
        sentAt = l; sentTime = System.currentTimeMillis()
        ops.trySend {
            // Items the cart reported must reach the bag before a fix that could walk out (else they'd go uncharged).
            if (cartSync.pending.isNotEmpty()) cartSync.pump()
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
        // From the pinned store's centre if "Stay in store" is on (and that ends it), else from the real fix.
        val h = holdStore
        holdStore = null
        val (baseLat, baseLng) = h?.let { it.lat to it.lng }
            ?: fix?.let { it.latitude to it.longitude }
            ?: run { clog("walk out: no location yet, wait for a GPS fix"); return }
        ignoreFixesUntil = System.currentTimeMillis() + 60_000
        val lat = baseLat + 300.0 / 111_320.0
        // Pause between the two fixes so the "Leaving" beat is visible, as it would be on a real walk out the door.
        ops.trySend { apply(Api.locate(lat, baseLng, 8f), true); delay(1800) }
        ops.trySend { apply(Api.locate(lat + 0.0002, baseLng, 8f), true) }
    }

    /**
     * Demo aid ("Stay in store"): indoor GPS drifts past the store's edge and reads as leaving. Pin the phone at the
     * nearest store's centre, reported every 8 s like a real fix in a store, until Simulate walk out.
     */
    private var holdStore: NearbyStore? = null
    var holdingInStore by mutableStateOf(false); private set
    fun demoEnterStore() {
        val l = fix
        ops.trySend {
            val s = try {
                (if (l != null) Api.nearbyStores(l.latitude, l.longitude, 5) else Geofences.cached(app)).minByOrNull { it.distanceM }
            } catch (e: Exception) { clog("stay in store: ${friendly(e)}"); null }
            if (s == null) { clog("stay in store: no store nearby"); return@trySend }
            clog("stay in store: pinned at ${s.name}")
            holdStore = s; holdingInStore = true
            apply(Api.locate(s.lat, s.lng, 5f), true)
            scope.launch {
                while (holdStore === s) { delay(8_000); if (holdStore === s) ops.trySend { apply(Api.locate(s.lat, s.lng, 5f), true) } }
                holdingInStore = false
            }
        }
    }


    // ---------------------------------------------------------------- SmartCart: link events, sessions, corrections

    private fun clog(m: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
        cartLog.add("$t $m")
        while (cartLog.size > 200) cartLog.removeAt(0)
    }

    private fun onCartState(s: BagState) {
        val p = pairing?.probe
        if (p != null && CartProtocol.isCartName(p.bleName)) when {
            s is BagState.Connected && !p.connected -> pairing = pairing?.copy(probe = p.copy(connected = true))
            s is BagState.Problem -> pairing = pairing?.copy(probe = p.copy(error = s.message, connected = false))
        }
        updateBag()
    }

    private fun cartStatusIn(t: String) {
        cartStatus = t
        // Pairing: the cart has no light to blink, so its STATUS (device name) confirms which cart this phone reached.
        (CartProtocol.status(t) as? CartProtocol.Status.State)?.let { st ->
            // The real name from STATUS replaces a name guessed from the address; pairing saves this one.
            pairing?.probe?.takeIf { CartProtocol.isCartName(it.bleName) }?.let { pairing = pairing?.copy(probe = it.copy(hello = st.device,
                bleName = if (CartProtocol.isCartName(st.device)) st.device else it.bleName)) }
        }
        val st = CartProtocol.status(t) as? CartProtocol.Status.State
        if (pairing?.probe == null) {
            // Connected by a guessed name (nameless advertisement): STATUS says which cart this really is.
            // Only a full SmartCart-XXXX name proves it's another cart (never a cut or odd name).
            if (st != null && !simulated && cart.allowed.isNotEmpty() && FULL_CART.matches(st.device) && st.device !in cart.allowed) { cartSync.onDisconnected(); cart.reject(); return }
            cartSync.onStatus(t)
        }
    }

    private fun cartEventIn(t: String) {
        if (pairing != null || account?.signedIn != true) { clog("← $t (ignored: not shopping)"); return }
        val r = cartSync.onEvent(t)
        if (r == "queued") pumpCart()
    }

    /** Forward queued cart events in order (one backend call at a time, inside the shared queue); back off if offline. */
    private var pumpJob: kotlinx.coroutines.Job? = null
    fun pumpCart() {
        ops.trySend {
            if (cartSync.pump()) { pumpRetry = 0; pumpJob?.cancel(); pumpJob = null }
            else if (pumpJob?.isActive != true) {                 // one retry loop, however many events arrive offline
                val d = minOf(30_000L, 2000L shl minOf(pumpRetry++, 4))
                pumpJob = scope.launch { delay(d); pumpJob = null; pumpCart() }
            }
        }
    }

    /** The backend answered a cart event: show its cart (the same CartState as every other call). */
    private fun onHw(r: HwEventResult, e: QueuedEvent) {
        lastHw = HwOutcome(r.status, r.message)
        if (r.state.sessionId.isNotEmpty()) { session = r.state; cartSync.noteBackendSeq(r.state.sessionId, r.state.lastSeq) }
        noteBudget(r.state)
        apply(r.state.cart, false)
        if (r.status == "accepted" && e.action == "A") {
            val l = lines.maxByOrNull { it.addedMicros }
            if (l != null && l.sold) { val n = ++flashN; flash = l.sku to n; scope.launch { delay(1600); if (flash?.second == n) flash = null } }
        }
        scheduleRecs()
        // The backend says this session is over or not ours while we think we're shopping: find the current one.
        if ((r.status == "stale_session" || r.status == "no_session") && e.sid == cartSync.desired?.first) {
            val id = (store as? StoreState.InStore)?.id
            // v1.2 §4.7: (acked already) shop_locate → shop_session_state → START the new sid.
            if (id != null) {
                session = null; sessionAsked = id; cartSync.setSession(null, null)
                ops.trySend { fix?.let { l -> apply(Api.locate(l.latitude, l.longitude, l.accuracy), true) }; refreshSession() }
            }
        }
    }

    private fun noteBudget(s: SessionState) {
        if (s.budgetCents != budgetCents) { budgetCents = s.budgetCents; prefs.edit().putInt("budget_cents", budgetCents).apply() }
    }

    /** Called from apply(): open/refresh the backend session when we enter a store; end it when we leave. */
    private fun sessionFor(storeId: String?) {
        if (storeId == null) {
            if (session != null || cartSync.desired != null) {
                val wasActive = session?.active == true
                session = null; recs = null; cartSync.setSession(null, null)
                // Left with nothing to pay for (no receipt): the summary still tells how the visit went.
                if (wasActive && hwBackend == true) ops.trySend { val su = try { Api.summary(0) } catch (_: Exception) { null }; if (su != null && su.receipt.id == 0L) summary = su }
            }
            sessionAsked = null
            return
        }
        val s = session
        if (hwBackend == false || (s != null && s.storeId == storeId && s.active) || sessionAsked == storeId) return
        sessionAsked = storeId
        ops.trySend { refreshSession() }
    }

    private suspend fun refreshSession() {
        try {
            val s = Api.sessionState()
            hwBackend = true
            noteBudget(s)
            if (s.active && s.sessionId.isNotEmpty()) {
                session = s; prefs.edit().putString("cart_session", "${s.sessionId}|${s.storeId}").apply()
                cartSync.setSession(s.sessionId, s.storeId, s.lastSeq); scheduleRecs()
            }
            else { session = null; cartSync.setSession(null, null) }
        } catch (e: ApiException) {
            // Not deployed yet (unknown procedure): shopping works as before, without a cart session.
            if (e.message.orEmpty().contains("No such procedure", true)) hwBackend = false
            sessionAsked = null
        } catch (_: Exception) { sessionAsked = null }                    // offline: ask again on the next fix
    }

    /** Up to three quiet suggestions, refreshed 1.5 s after the bag settles. */
    private fun scheduleRecs() {
        if (session?.active != true) return
        val n = ++recsGen
        scope.launch {
            delay(1500)
            if (n != recsGen || session?.active != true) return@launch
            ops.trySend { recs = try { Api.recommend() } catch (_: Exception) { recs } }
        }
    }

    /** Budget for this and later trips (0 clears). [done] gets null or the server's message. */
    fun setBudget(cents: Int, done: (String?) -> Unit) = ops.trySend {
        try { val s = Api.setBudget(cents); noteBudget(s); if (s.active) session = s; done(null); scheduleRecs() }
        catch (e: Exception) { done(friendly(e)) }
    }

    /** Manual correction: set a line's quantity (0 removes it). */
    fun setQty(sku: String, qty: Int) = ops.trySend {
        try { val s = Api.setQty(sku, qty); if (s.active) session = s; apply(s.cart, false); scheduleRecs() }
        catch (e: Exception) { syncError = friendly(e) }
    }

    /** "Change item": the cart saw the wrong thing; move one unit to the right product. */
    fun relabel(from: String, to: String, done: (String?) -> Unit = {}) = ops.trySend {
        try { val s = Api.relabel(from, to); if (s.active) session = s; apply(s.cart, false); done(null); scheduleRecs() }
        catch (e: Exception) { done(friendly(e)) }
    }

    fun storeProducts(done: (List<Recommendation>?, String?) -> Unit) = ops.trySend {
        try { done(Api.storeProducts(), null) } catch (e: Exception) { done(null, friendly(e)) }
    }

    // ---- hidden Hardware test screen

    fun hwWrite(msg: String) { clog("test → $msg"); if (simulated) mock?.control(msg) else cart.write(msg) }
    fun hwSync() = cartSync.sync()

    /** Simulated cart: the same CartSync path as a real cart, with [MockCart] answering like the Jetson. */
    fun simulateCart(on: Boolean) {
        if (on == simulated) return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (on) {
            cartSync.setSession(null, null)               // STOP the real cart's session before handing the protocol over
            cartSync.onDisconnected()
            cart.disconnectSoon()
            simulated = true
            mock = MockCart("SmartCart-TEST", { t -> cartStatus = t; cartSync.onStatus(t) }, { t -> cartEventIn(t) }, { f -> main.post(f) })
            clog("simulated cart on")
            cartSync.onConnected()
            session?.takeIf { it.active }?.let { cartSync.setSession(it.sessionId, it.storeId, it.lastSeq) }   // in a store: START the simulated cart
            mock?.let { m -> main.post { cartStatus = m.status(); cartSync.onStatus(m.status()) } }
        } else {
            cartSync.onDisconnected(); simulated = false; mock = null; clog("simulated cart off")
            sessionAsked = null; session = null                 // ask again so the real cart gets START
            cartBag = BagState.Off; connectAll()
        }
        updateBag()
    }

    /** Simulated crossing (A/R + COCO label), as the Jetson scanner would report it. */
    fun simulate(action: String, label: String) {
        val m = mock ?: return
        if (!m.crossing(action, label)) clog("simulated $action $label not sent: cart has no ACTIVE session")
    }

    fun finishReceipt() { paid = null }
    fun dismissVisit() { if (summary?.receipt?.id == 0L) summary = null }
}
