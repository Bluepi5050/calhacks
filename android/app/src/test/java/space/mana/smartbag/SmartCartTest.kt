package space.mana.smartbag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SmartCart contract v1.1 on the phone: codec, GATT serialization, and the reconcile/ack/queue state machine with a fake GATT. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SmartCartTest {
    class MemStore(var json: String? = null) : QueueStore { override fun load() = json; override fun save(json: String) { this.json = json } }

    /** Fake GATT + fake backend around one CartSync. */
    class Rig(store: MemStore = MemStore()) {
        val writes = mutableListOf<String>()
        val backend = mutableListOf<String>()          // "sid:seq action label" in call order
        val order = mutableListOf<String>()            // backend calls and ACK writes interleaved, to check "ACK after answer"
        var offline = false
        var answer: (QueuedEvent) -> HwOutcome = { HwOutcome("accepted", "") }
        val log = mutableListOf<String>()
        val sync = CartSync(
            write = { writes += it; order += "W $it" },
            forward = { e -> if (offline) throw java.io.IOException("offline"); backend += "${e.sid}:${e.seq} ${e.action} ${e.label}"; order += "B ${e.sid}:${e.seq}"; answer(e) },
            store = store, log = { log += it })
        fun pump() = runBlocking { sync.pump() }
    }

    @Test fun codec() {
        assertEquals(CartProtocol.Event(17, "A", "apple", 93), CartProtocol.event("17|A|apple|93"))
        assertEquals(CartProtocol.Event(18, "R", "cell phone", 0), CartProtocol.event("18|R|cell phone|0"))
        listOf("", "1|A|apple", "0|A|apple|9", "1000000|A|apple|9", "1|X|apple|9", "1|A|Apple|9", "1|A|apple|101", "1|A||9", "x|A|apple|9",
            "1|A|apple|9|extra", "1|A|abcdefghijklmnopqrstu|9").forEach { assertNull(it, CartProtocol.event(it)) }
        assertEquals(CartProtocol.Status.State("s1k9", true, 17, 0, "SmartCart-3F2A"), CartProtocol.status("S|s1k9|ACTIVE|17|0|SmartCart-3F2A"))
        assertEquals(CartProtocol.Status.State(null, false, 0, 0, "SmartCart-3F2A"), CartProtocol.status("S|-|IDLE|0|0|SmartCart-3F2A"))
        assertEquals(CartProtocol.Status.OkStart("s1"), CartProtocol.status("OK|START|s1"))
        assertEquals(CartProtocol.Status.OkStop("s1"), CartProtocol.status("OK|STOP|s1"))
        assertEquals(CartProtocol.Status.Err("BUSY", "scanner restarting"), CartProtocol.status("ERR|BUSY|scanner restarting"))
        listOf("S|s1|RUNNING|1|0|x", "S|S1|ACTIVE|1|0|x", "OK|PAUSE|s1", "OK|START|S!", "HELLO", "S|s1|ACTIVE|x|0|n").forEach { assertNull(it, CartProtocol.status(it)) }
        assertEquals("START|s1k9|bayside-grocer", CartProtocol.start("s1k9", "bayside-grocer"))
        assertEquals("STOP|s1k9", CartProtocol.stop("s1k9")); assertEquals("ACK|s1k9|17", CartProtocol.ack("s1k9", 17)); assertEquals("SYNC", CartProtocol.SYNC)
        listOf({ CartProtocol.start("S1", "x") }, { CartProtocol.start("s1", "Bay Side") }, { CartProtocol.ack("s1", 0) }, { CartProtocol.stop("toolongsession1") })
            .forEach { f -> try { f(); fail("accepted a bad message") } catch (_: IllegalArgumentException) {} }
        // Every message we can produce fits the 60-byte limit.
        assertTrue(CartProtocol.start("a".repeat(12), "b".repeat(32)).toByteArray().size <= CartProtocol.MAX_MSG)
        assertEquals("Cart 3F2A", bagDisplayName("SmartCart-3F2A")); assertEquals("Bag 3F2A", bagDisplayName("SmartBag-3F2A"))
    }

    @Test fun gattOpsRunOneAtATime() {
        val ran = mutableListOf<GattOp>()
        val q = GattQueue({ ran += it; it !is GattOp.Read })        // Android refuses the read: it's skipped, not stuck
        q.add(GattOp.Mtu); q.add(GattOp.Discover); q.add(GattOp.Notify(CartProtocol.STATUS)); q.add(GattOp.Notify(CartProtocol.EVENTS))
        assertEquals(listOf<GattOp>(GattOp.Mtu), ran)                 // nothing else starts until the MTU callback
        q.done(); assertEquals(GattOp.Discover, ran.last())
        q.done(); assertEquals(GattOp.Notify(CartProtocol.STATUS), ran.last())
        q.add(GattOp.Write("SYNC"))
        assertEquals(3, ran.size)                                     // CCCD writes are serialized: EVENTS waits for STATUS
        q.done(); assertEquals(GattOp.Notify(CartProtocol.EVENTS), ran.last())
        q.add(GattOp.Read(CartProtocol.STATUS))
        q.done(); q.done()
        assertEquals(listOf("MTU", "DISCOVER"), ran.take(2).map { it.toString() })
        assertEquals(GattOp.Write("SYNC"), ran[4]); assertEquals(GattOp.Read(CartProtocol.STATUS), ran[5])
        assertNull("refused op skipped, queue idle", q.current); assertEquals(0, q.size)
    }

    @Test fun lifecycleReconcileAckAndStop() {
        val r = Rig(); val s = r.sync
        // Connected outside a store, cart idle: nothing to write.
        s.onConnected(); s.onStatus("S|-|IDLE|0|0|SmartCart-3F2A")
        assertTrue(r.writes.isEmpty())
        // Entering the store: backend session s1 → START.
        s.setSession("s1", "bayside-grocer")
        assertEquals(listOf("START|s1|bayside-grocer"), r.writes)
        // An event before OK|START is ignored and not acked.
        assertEquals("ignored: no confirmed session", s.onEvent("1|A|apple|93"))
        s.onStatus("OK|START|s1")
        assertEquals("s1", s.confirmed)
        assertEquals("queued", s.onEvent("1|A|apple|93"))
        assertEquals("duplicate, already queued", s.onEvent("1|A|apple|93"))
        assertTrue(r.pump())
        assertEquals(listOf("s1:1 A apple"), r.backend)
        assertEquals(listOf("B s1:1", "W ACK|s1|1"), r.order.drop(1))  // ACK only after the backend answered
        // The cart resends 1 (its ACK was lost): acked again, no second backend call.
        assertEquals("resent, acked again", s.onEvent("1|A|apple|93"))
        assertEquals(1, r.backend.size); assertEquals("ACK|s1|1", r.writes.last())
        // Every terminal status is acked (unknown product, invalid remove, stale, rejected).
        r.answer = { HwOutcome(listOf("unknown_product", "invalid_remove", "rejected")[(it.seq - 2).toInt()], "") }
        listOf("2|A|cell phone|71", "3|R|orange|88", "4|A|apple|90").forEach { s.onEvent(it) }
        r.pump()
        assertEquals(listOf("ACK|s1|2", "ACK|s1|3", "ACK|s1|4"), r.writes.takeLast(3))
        // Walk-out: STOP, and later events are ignored (no confirmed session).
        s.setSession(null, null)
        assertEquals("STOP|s1", r.writes.last())
        assertEquals("ignored: no confirmed session", s.onEvent("5|A|apple|90"))
        assertEquals(4, r.backend.size)
    }

    @Test fun offlineQueueKeepsOrderAndSurvivesRestart() {
        val mem = MemStore()
        val r = Rig(mem)
        r.sync.onConnected(); r.sync.setSession("s7", "mission-fresh"); r.sync.onStatus("OK|START|s7")
        r.offline = true
        listOf("1|A|apple|90", "2|A|banana|90", "3|R|apple|90").forEach { r.sync.onEvent(it) }
        assertFalse(r.pump())
        assertTrue("no ACK for unprocessed events", r.writes.none { it.startsWith("ACK") })
        assertEquals(3, r.sync.pending.size)
        // App restarts: the persisted queue comes back in order and is forwarded once the backend is reachable.
        val r2 = Rig(mem)
        assertEquals(listOf(1L, 2L, 3L), r2.sync.pending.map { it.seq })
        r2.sync.onConnected(); r2.sync.setSession("s7", "mission-fresh"); r2.sync.onStatus("OK|START|s7")
        assertTrue(r2.pump())
        assertEquals(listOf("s7:1 A apple", "s7:2 A banana", "s7:3 R apple"), r2.backend)
        assertEquals(listOf("ACK|s7|1", "ACK|s7|2", "ACK|s7|3"), r2.writes.filter { it.startsWith("ACK") })
        assertEquals("[]", mem.json)
    }

    @Test fun reconnectReconciles() {
        val r = Rig(); val s = r.sync
        s.setSession("s2", "bayside-grocer")
        assertTrue("not connected: nothing written", r.writes.isEmpty())
        // Reconnect while the cart still runs s2: START resumes it.
        s.onConnected(); s.onStatus("S|s2|ACTIVE|5|1|SmartCart-3F2A")
        assertEquals(listOf("START|s2|bayside-grocer"), r.writes)
        s.onStatus("OK|START|s2"); assertEquals("s2", s.confirmed)
        // Disconnect + reconnect: confirmation is gone until the cart answers again.
        s.onDisconnected(); assertNull(s.confirmed)
        assertEquals("ignored: no confirmed session", s.onEvent("6|A|apple|90"))
        s.onConnected(); s.onStatus("S|s2|ACTIVE|6|1|SmartCart-3F2A"); s.onStatus("OK|START|s2")
        assertEquals("queued", s.onEvent("6|A|apple|90"))
        // An ERR clears the pending START; the next STATUS tries again.
        s.setSession("s3", "bayside-grocer"); assertEquals("START|s3|bayside-grocer", r.writes.last())
        s.onStatus("ERR|BUSY|scanner restarting")
        s.onStatus("S|s2|ACTIVE|6|0|SmartCart-3F2A"); assertEquals("START|s3|bayside-grocer", r.writes.last())
        assertEquals(2, r.writes.count { it == "START|s3|bayside-grocer" })
        s.onStatus("OK|START|s3")
        // The cart later reports another session (it was restarted by someone else): start ours again.
        s.onStatus("S|-|IDLE|0|0|SmartCart-3F2A"); assertNull(s.confirmed); assertEquals("START|s3|bayside-grocer", r.writes.last())
        // No session on the phone but the cart is ACTIVE (e.g. after a walk-out while disconnected): STOP it.
        val r2 = Rig(); r2.sync.onConnected(); r2.sync.onStatus("S|s9|ACTIVE|3|2|SmartCart-3F2A")
        assertEquals(listOf("STOP|s9"), r2.writes)
        // A session id the cart can't take is never sent.
        val r3 = Rig(); r3.sync.onConnected(); r3.sync.setSession("S-UPPER", "bayside-grocer"); assertTrue(r3.writes.isEmpty())
    }

    @Test fun lostSessionHook() {
        val r = Rig(); val s = r.sync
        var asked: String? = null
        s.onLost = { asked = it; true }
        s.onConnected(); s.setSession("s4", "bayside-grocer")           // no STATUS yet: START as the contract says
        assertEquals("START|s4|bayside-grocer", r.writes.last()); s.onStatus("OK|START|s4")
        s.onDisconnected(); s.onConnected()
        s.onStatus("S|-|IDLE|0|0|SmartCart-3F2A")                       // cart rebooted and lost s4
        assertEquals("s4", asked); assertEquals(1, r.writes.size)        // the hook took over (rekey), no START|s4
        assertFalse("off until the contract adopts it", CartRules.REKEY_LOST_SESSION)
    }

    /** Contract §6 acceptance flow against MockCart (the Jetson stand-in) and a fake backend that keeps a cart. */
    @Test fun acceptanceWithMockCart() {
        val cartQty = mutableMapOf<String, Int>()
        lateinit var sync: CartSync
        val events = ArrayDeque<String>(); val statuses = ArrayDeque<String>()
        val mock = MockCart("SmartCart-3F2A", { statuses += it }, { events += it })
        var activeSid: String? = null
        val acked = mutableListOf<String>()
        sync = CartSync(write = { m -> if (m.startsWith("ACK")) acked += m; mock.control(m) },
            forward = { e ->
                when {
                    e.sid != activeSid -> HwOutcome("stale_session", "")
                    e.action == "A" -> { cartQty.merge(e.label, 1, Int::plus); HwOutcome("accepted", "") }
                    (cartQty[e.label] ?: 0) == 0 -> HwOutcome("invalid_remove", "")
                    else -> { cartQty.merge(e.label, -1, Int::plus); HwOutcome("accepted", "") }
                }
            }, store = MemStore())
        fun drain() { while (statuses.isNotEmpty() || events.isNotEmpty()) { statuses.removeFirstOrNull()?.let(sync::onStatus); events.removeFirstOrNull()?.let(sync::onEvent) }; runBlocking { sync.pump() } }
        // Connected outside the store: no session, apple moving does nothing.
        sync.onConnected(); sync.onStatus(mock.status()); drain()
        assertFalse(mock.crossing("A", "apple"))
        // Enter the store: session + START.
        activeSid = "s1"; sync.setSession("s1", "bayside-grocer"); drain()
        assertTrue(mock.active); assertEquals("s1", sync.confirmed)
        mock.crossing("A", "apple"); drain()
        assertEquals(1, cartQty["apple"]); assertEquals(0, mock.unackedCount)
        mock.crossing("R", "apple"); drain()
        assertEquals(0, cartQty["apple"])
        mock.crossing("R", "apple"); drain()                             // nothing to remove: invalid_remove, still acked
        assertEquals(0, mock.unackedCount)
        // Disconnect mid-trip; the cart keeps events; reconnect resumes and the resent events arrive after OK.
        sync.onDisconnected()
        mock.crossing("A", "banana"); events.clear()                     // lost on the air
        sync.onConnected(); sync.onStatus(mock.status()); drain()
        assertEquals(1, cartQty["banana"]); assertEquals(0, mock.unackedCount)
        // Walk out: STOP; further detections change nothing.
        activeSid = null; sync.setSession(null, null); drain()
        assertFalse(mock.active)
        assertFalse(mock.crossing("A", "apple")); drain()
        assertEquals(mapOf("apple" to 0, "banana" to 1), cartQty)
        assertEquals(listOf("ACK|s1|1", "ACK|s1|2", "ACK|s1|3", "ACK|s1|4"), acked)
    }

    @Test fun budgetAndAmounts() {
        assertEquals(2550, parseCents("25.50")); assertEquals(2500, parseCents("$25")); assertEquals(0, parseCents(""))
        assertNull(parseCents("25.555")); assertNull(parseCents("-3")); assertNull(parseCents("abc"))
        assertEquals("$8.82 left of your $20.00 budget" to false, budgetText(2000, 1118))
        assertEquals("$2.30 over your $20.00 budget" to true, budgetText(2000, 2230))
        assertNull(budgetText(0, 500))
    }

    @Test fun reviewFixes() {
        var clock = 0L
        val writes = mutableListOf<String>(); val fwd = mutableListOf<String>()
        var fail: Exception? = null
        val s = CartSync(write = { writes += it }, forward = { e -> fail?.let { f -> fail = null; throw f }; fwd += "${e.sid}:${e.seq}"; HwOutcome("accepted", "") },
            store = MemStore(), now = { clock })
        s.onConnected(); s.onStatus("S|-|IDLE|0|0|SmartCart-3F2A")
        s.setSession("s1", "bayside-grocer")
        // An ERR (answering something else) between START and OK doesn't lose the confirmation.
        s.onStatus("ERR|BAD_SID|ack for old session"); assertEquals("SYNC", writes.last())
        s.onStatus("OK|START|s1"); assertEquals("s1", s.confirmed)
        // A permanent server error finishes the event (acked) instead of blocking the queue forever.
        s.onEvent("1|A|apple|90"); s.onEvent("2|A|banana|90")
        fail = ProcedureError("Bag It needs an update to read this reply.")
        assertTrue(runBlocking { s.pump() })
        assertEquals(listOf("s1:2"), fwd); assertEquals(listOf("ACK|s1|1", "ACK|s1|2"), writes.filter { it.startsWith("ACK") })
        assertEquals("error", s.lastOutcome?.status.let { "error" })
        // A network failure is not permanent: the event stays queued.
        s.onEvent("3|A|apple|90"); fail = java.io.IOException("offline")
        assertFalse(runBlocking { s.pump() }); assertEquals(1, s.pending.size)
        // OK|STOP for our session (e.g. the test screen's STOP): unconfirmed, START again.
        s.onStatus("OK|STOP|s1"); assertNull(s.confirmed); assertEquals("START|s1|bayside-grocer", writes.last())
        s.onStatus("OK|START|s1")
        // Session changes while an old-sid event is queued: it is still forwarded, but not acked to a cart that dropped it.
        s.setSession("s2", "bayside-grocer"); s.onStatus("S|s2|IDLE|0|0|SmartCart-3F2A")
        val acksBefore = writes.count { it.startsWith("ACK") }
        assertTrue(runBlocking { s.pump() })
        assertEquals("s1:3", fwd.last()); assertEquals(acksBefore, writes.count { it.startsWith("ACK") })
        // Watchdog: a START that is never answered is retried via SYNC → STATUS → START.
        val n = writes.size
        s.watchdog(); assertEquals(n, writes.size)                    // still within 5 s
        clock += 6000; s.watchdog(); assertEquals("SYNC", writes.last())
        s.onStatus("S|-|IDLE|0|0|SmartCart-3F2A"); assertEquals("START|s2|bayside-grocer", writes.last())
        s.onStatus("OK|START|s2"); assertEquals("s2", s.confirmed)
        clock += 60_000; val m = writes.size; s.watchdog(); assertEquals("confirmed: watchdog quiet", m, writes.size)
    }
}
