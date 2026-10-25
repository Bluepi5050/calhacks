package space.mana.smartbag

import org.json.JSONArray
import org.json.JSONObject

/**
 * SmartCart (Jetson) Bluetooth protocol, contract v1.1 (docs/SMARTCART-BLE.md). UTF-8 text, fields split by '|',
 * one message per write/notification. Pure: no Android types, unit tested.
 */
object CartProtocol {
    const val SERVICE = "7ccd0001-d416-4afc-a97d-f7c98e1afca9"
    const val CONTROL = "7ccd0002-d416-4afc-a97d-f7c98e1afca9"
    const val EVENTS = "7ccd0003-d416-4afc-a97d-f7c98e1afca9"
    const val STATUS = "7ccd0004-d416-4afc-a97d-f7c98e1afca9"
    const val MTU = 247
    const val MIN_MTU = 64
    const val MAX_MSG = 60

    private val SID = Regex("[a-z0-9]{1,12}")
    private val STORE = Regex("[a-z0-9-]{1,32}")
    private val LABEL = Regex("[a-z][a-z ]{0,19}")

    fun validSid(s: String) = SID.matches(s)
    fun validStore(s: String) = STORE.matches(s)

    fun start(sid: String, store: String): String { require(validSid(sid) && validStore(store)) { "bad START" }; return "START|$sid|$store" }
    fun stop(sid: String): String { require(validSid(sid)) { "bad STOP" }; return "STOP|$sid" }
    fun ack(sid: String, seq: Long): String { require(validSid(sid) && seq in 1..999_999) { "bad ACK" }; return "ACK|$sid|$seq" }
    const val SYNC = "SYNC"

    /** One EVENTS notification: `<seq>|<A|R>|<label>|<confidence>`. */
    data class Event(val seq: Long, val action: String, val label: String, val confidence: Int)

    fun event(text: String): Event? {
        val f = text.trim().split('|')
        if (f.size != 4) return null
        val seq = f[0].toLongOrNull()?.takeIf { it in 1..999_999 } ?: return null
        val action = f[1].takeIf { it == "A" || it == "R" } ?: return null
        val label = f[2].takeIf { LABEL.matches(it) } ?: return null
        val conf = f[3].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        return Event(seq, action, label, conf)
    }

    sealed class Status {
        /** `S|<sid or ->|<IDLE|ACTIVE>|<last_seq>|<unacked>|<device_name>` (also the STATUS read value). */
        data class State(val sid: String?, val active: Boolean, val lastSeq: Long, val unacked: Int, val device: String) : Status()
        data class OkStart(val sid: String) : Status()
        data class OkStop(val sid: String) : Status()
        data class Err(val code: String, val detail: String) : Status()
    }

    fun status(text: String): Status? {
        val f = text.trim().split('|')
        return when (f.firstOrNull()) {
            "S" -> if (f.size != 6 || (f[2] != "IDLE" && f[2] != "ACTIVE")) null else {
                val sid = f[1].takeIf { it != "-" }
                if (sid != null && !validSid(sid)) null
                else Status.State(sid, f[2] == "ACTIVE", f[3].toLongOrNull() ?: return null, f[4].toIntOrNull() ?: return null, f[5])
            }
            "OK" -> when {
                f.size != 3 || !validSid(f[2]) -> null
                f[1] == "START" -> Status.OkStart(f[2])
                f[1] == "STOP" -> Status.OkStop(f[2])
                else -> null
            }
            "ERR" -> if (f.size >= 2) Status.Err(f[1], f.drop(2).joinToString("|")) else null
            else -> null
        }
    }

    /** "SmartCart-3F2A" → "Cart 3F2A". */
    fun isCartName(bleName: String) = bleName.startsWith("SmartCart-")
}

object CartRules {
    /**
     * Backend issue 1 (hardware-protocol/issues/backend): a cart that lost our session restarts the same sid at seq 1 and the
     * backend answers "duplicate". The fix (shop_session_rekey, live) is not in contract v1.1 yet, so it stays off until the
     * contract adopts it; the code path is built and tested.
     */
    const val REKEY_LOST_SESSION = false
}

/** What the backend said about one hardware event (contract §5 HwEventResult). */
data class HwOutcome(val status: String, val message: String)

/** An event waiting for the backend, in arrival order. Persisted so a restart never loses or reorders it. */
data class QueuedEvent(val sid: String, val seq: Long, val action: String, val label: String, val confidence: Int)

/** Where the queue lives (SharedPreferences in the app, memory in tests). */
interface QueueStore { fun load(): String?; fun save(json: String) }

/**
 * The phone side of the SmartCart contract (§3–§4) as a state machine, independent of Android's GATT:
 * - reconcile on every (re)connect and STATUS: our backend session → START (resume or start); none but cart ACTIVE → STOP;
 * - events count only after OK|START|<our sid>; earlier ones are logged and ignored (not acked);
 * - each (sid, seq) is forwarded to the backend once, strictly in order, through a persisted queue;
 * - ACK only after the backend answered (any terminal status); network failures keep the queue and retry later;
 * - an already-answered event that the cart resends (its ACK was lost) is acked again without a second backend call.
 * All methods are called on one thread (the main looper in the app).
 */
class CartSync(
    private val write: (String) -> Unit,                     // CONTROL write (the GATT driver queues it)
    private val forward: suspend (QueuedEvent) -> HwOutcome, // shop_hw_event; throws when the backend is unreachable
    private val store: QueueStore,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** Errors that retrying can't fix (server error, unreadable reply): the event is finished and acked. */
    private val permanent: (Exception) -> Boolean = { it is ProcedureError },
) {
    private var pendingSince = 0L
    private var errSincePending = false
    var connected = false; private set
    /** The backend session the phone wants on the cart (null = none). */
    var desired: Pair<String, String>? = null; private set            // sid to storeId
    /** The session the cart confirmed with OK|START since our last START. */
    var confirmed: String? = null; private set
    private var pendingStart: String? = null
    var cart: CartProtocol.Status.State? = null; private set
    private val queue = ArrayDeque<QueuedEvent>()
    private val answered = LinkedHashMap<String, MutableSet<Long>>()  // sid -> seqs the backend answered (bounded)
    var pumping = false; private set
    /**
     * The cart doesn't have our session (rebooted, or its own stale stop). Return true if the caller takes over (e.g.
     * asks the backend for a new session id) instead of the contract's START|sid. See [CartRules.REKEY_LOST_SESSION].
     */
    var onLost: ((String) -> Boolean)? = null
    var lastOutcome: HwOutcome? = null; private set
    val pending: List<QueuedEvent> get() = queue.toList()

    init { load() }

    private fun load() {
        try {
            val a = JSONArray(store.load() ?: "[]")
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                queue.addLast(QueuedEvent(it.getString("sid"), it.getLong("seq"), it.getString("a"), it.getString("l"), it.getInt("c")))
            }
        } catch (_: Exception) {}
    }
    private fun persist() {
        val a = JSONArray()
        queue.forEach { a.put(JSONObject().put("sid", it.sid).put("seq", it.seq).put("a", it.action).put("l", it.label).put("c", it.confidence)) }
        store.save(a.toString())
    }

    private fun send(msg: String) { log("→ $msg"); if (connected) write(msg) }

    /** GATT is ready (MTU ok, notifications on). The driver reads STATUS next and passes it to [onStatus]. */
    fun onConnected() { connected = true; confirmed = null; pendingStart = null; cart = null; log("connected") }
    fun onDisconnected() { connected = false; confirmed = null; pendingStart = null; log("disconnected") }

    /** The backend session changed (entered a store, walked out, signed out). */
    fun setSession(sid: String?, storeId: String?) {
        val next = if (sid.isNullOrEmpty() || storeId.isNullOrEmpty()) null else sid to storeId
        if (next == desired) return
        val old = confirmed ?: desired?.first
        desired = next
        if (next == null) {
            confirmed = null; pendingStart = null
            if (old != null && CartProtocol.validSid(old)) send(CartProtocol.stop(old))
        } else {
            confirmed = null; pendingStart = null
            reconcile()
        }
    }

    private fun reconcile() {
        if (!connected) return
        val want = desired
        val c = cart
        when {
            want != null && pendingStart == null && confirmed != want.first -> {
                if (!CartProtocol.validSid(want.first) || !CartProtocol.validStore(want.second)) { log("session id not usable on the cart: ${want.first}"); return }
                if (c != null && !(c.sid == want.first && c.active) && onLost?.invoke(want.first) == true) { log("cart doesn't have ${want.first}: new session requested"); return }
                pendingStart = want.first; pendingSince = now(); errSincePending = false
                send(CartProtocol.start(want.first, want.second))       // resume if the cart still has it, else a fresh start
            }
            want == null && c != null && c.active && c.sid != null -> send(CartProtocol.stop(c.sid))
        }
    }

    fun onStatus(text: String) {
        log("← $text")
        when (val s = CartProtocol.status(text)) {
            null -> log("unreadable status ignored")
            is CartProtocol.Status.State -> {
                cart = s
                // A cart that reports a different (or no) session after our confirmation lost it: start again.
                if (confirmed != null && (s.sid != confirmed || !s.active)) confirmed = null
                // STATUS after an ERR (we asked with SYNC) and still no OK: our START didn't take. Send it again.
                if (errSincePending && pendingStart != null) { pendingStart = null; errSincePending = false }
                reconcile()
            }
            // Our session confirmed, even if an unrelated ERR arrived in between.
            is CartProtocol.Status.OkStart -> if (s.sid == desired?.first) { confirmed = s.sid; pendingStart = null }
                else if (confirmed != null) { confirmed = null; reconcile() }        // the cart now runs someone else's session
            is CartProtocol.Status.OkStop -> if (s.sid == confirmed) { confirmed = null; reconcile() }
            // An ERR may answer an ACK or SYNC, not our START: keep waiting and ask for STATUS; the watchdog retries.
            is CartProtocol.Status.Err -> { log("cart error ${s.code}: ${s.detail}"); if (pendingStart != null) errSincePending = true; send(CartProtocol.SYNC) }
        }
    }

    /** One EVENTS notification. Returns what happened, for the log and the hardware test screen. */
    fun onEvent(text: String): String {
        log("← $text")
        val ev = CartProtocol.event(text) ?: return "unreadable event ignored".also(log)
        val sid = confirmed ?: return "ignored: no confirmed session".also(log)
        if (answered[sid]?.contains(ev.seq) == true) { send(CartProtocol.ack(sid, ev.seq)); return "resent, acked again" }
        if (queue.any { it.sid == sid && it.seq == ev.seq }) return "duplicate, already queued"
        queue.addLast(QueuedEvent(sid, ev.seq, ev.action, ev.label, ev.confidence))
        persist()
        return "queued"
    }

    /** Forward queued events in order until the queue is empty or the backend is unreachable. Returns false on failure. */
    suspend fun pump(): Boolean {
        if (pumping) return true
        pumping = true
        try {
            while (true) {
                val e = queue.firstOrNull() ?: return true
                val r = try { forward(e) } catch (x: Exception) {
                    if (!permanent(x)) { log("backend unreachable, will retry: ${x.message}"); return false }
                    HwOutcome("error", x.message ?: "")                                 // finished: never block the queue
                }
                lastOutcome = r
                queue.removeFirst(); persist()
                answered.getOrPut(e.sid) { mutableSetOf() }.add(e.seq)
                while (answered.size > 4) answered.remove(answered.keys.first())
                log("backend ${r.status} for ${e.sid}:${e.seq}")
                // Only the cart's current session has a buffer to clear; other sids were dropped by its STOP/START.
                if (CartProtocol.validSid(e.sid) && (e.sid == confirmed || e.sid == cart?.sid)) send(CartProtocol.ack(e.sid, e.seq))
            }
        } finally { pumping = false }
    }

    fun sync() = send(CartProtocol.SYNC)

    /**
     * Call every few seconds while connected. The contract promises no spontaneous STATUS, so if our START has gone
     * unanswered for 5 s (lost write, ERR) give up on it and ask for STATUS; reconcile then STARTs again.
     */
    fun watchdog() {
        if (!connected || desired == null || confirmed != null) return
        if (pendingStart != null && now() - pendingSince < 5000) return
        pendingStart = null
        send(CartProtocol.SYNC)
    }
}

/** One GATT operation. Android allows only one outstanding operation per connection, so they run one at a time. */
sealed class GattOp {
    object Mtu : GattOp() { override fun toString() = "MTU" }
    object Discover : GattOp() { override fun toString() = "DISCOVER" }
    data class Notify(val char: String) : GattOp()
    data class Read(val char: String) : GattOp()
    data class Write(val text: String) : GattOp()
}

/**
 * Serializes GATT operations: [run] starts one and returns false if Android refused it (then it is skipped).
 * The driver calls [done] from the matching callback (or a timeout). Pure; the fake GATT in tests drives it.
 */
class GattQueue(private val run: (GattOp) -> Boolean, private val log: (String) -> Unit = {}) {
    private val q = ArrayDeque<GattOp>()
    var current: GattOp? = null; private set
    val size get() = q.size

    fun add(op: GattOp) { q.addLast(op); next() }
    fun done() { current = null; next() }
    fun clear() { q.clear(); current = null }

    private fun next() {
        while (current == null) {
            val op = q.removeFirstOrNull() ?: return
            current = op
            if (!run(op)) { log("GATT refused $op"); current = null }
        }
    }
}

/**
 * A stand-in for the Jetson that follows contract v1.1 exactly (handoff step 2 "mock receiver"): answers CONTROL like the
 * cart, numbers events per session, keeps the last 64 unacknowledged events and resends them after OK|START on resume.
 * Used by the hidden Hardware test screen and by tests; the phone code path is the same as for a real cart.
 * [post] decides how replies are delivered (asynchronously on the main looper in the app, directly in tests).
 */
class MockCart(
    val device: String,
    private val onStatus: (String) -> Unit,
    private val onEvent: (String) -> Unit,
    private val post: (() -> Unit) -> Unit = { it() },
) {
    var sid: String? = null; private set
    var active = false; private set
    var seq = 0L; private set
    private val unacked = LinkedHashMap<Long, String>()
    val unackedCount get() = unacked.size

    fun status() = "S|${sid ?: "-"}|${if (active) "ACTIVE" else "IDLE"}|$seq|${unacked.size}|$device"

    fun control(text: String) {
        val f = text.split('|')
        post {
            when (f[0]) {
                "START" -> if (f.size != 3 || !CartProtocol.validSid(f[1]) || !CartProtocol.validStore(f[2])) onStatus("ERR|BAD_CMD|bad START")
                else if (active && sid == f[1]) { onStatus("OK|START|${f[1]}"); unacked.values.toList().forEach(onEvent) }
                else { sid = f[1]; active = true; seq = 0; unacked.clear(); onStatus("OK|START|${f[1]}") }
                "STOP" -> if (f.size != 2) onStatus("ERR|BAD_CMD|bad STOP") else {
                    if (sid == f[1]) { sid = null; active = false; seq = 0; unacked.clear() }
                    onStatus("OK|STOP|${f[1]}")
                }
                "ACK" -> if (f.size == 3 && f[1] == sid) f[2].toLongOrNull()?.let { unacked.remove(it) }
                "SYNC" -> onStatus(status())
                else -> onStatus("ERR|BAD_CMD|unknown command")
            }
        }
    }

    /** The scanner confirmed a crossing: one event if a session is ACTIVE (never batched, never guessed). */
    fun crossing(action: String, label: String, confidence: Int = 90): Boolean {
        if (!active) return false
        seq++
        val m = "$seq|$action|$label|$confidence"
        unacked[seq] = m
        while (unacked.size > 64) unacked.remove(unacked.keys.first())
        post { onEvent(m) }
        return true
    }
}
