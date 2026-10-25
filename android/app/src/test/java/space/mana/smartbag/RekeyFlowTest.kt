package space.mana.smartbag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live (module v6, contract v1.2 §3 "Lost session"): events 1..2 on sid, the cart restarts (fresh MockCart, IDLE), the
 * phone's CartSync detects the lost session, drains, shop_session_rekey gives a new sid, START, and seq 1 on the new sid
 * is accepted (not duplicate); the old sid answers no_session. Fresh account, deleted afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RekeyFlowTest {
    private val lat = 37.7897; private val lng = -122.3972

    @Test fun rekeyAfterCartRestart(): Unit = runBlocking {
        val t = System.currentTimeMillis()
        val email = "rekey+$t@bagitwith.us"; val pw = "rekey test $t"
        if (Api.accountState().first.signedIn) Api.signOut()
        Api.locate(lat + 2000.0 / 111_320.0, lng, 10f)
        assertTrue(Api.signUp("Rekey Test", email, pw).first.signedIn)
        try {
            Api.cardAdd("Visa", "4242", 12, 2029, "Demo", true)
            assertEquals("bayside-grocer", Api.locate(lat, lng, 8f).storeId)
            val ss = Api.sessionState(); assertTrue(ss.active)
            val sid = ss.sessionId
            val statuses = ArrayDeque<String>(); val events = ArrayDeque<String>(); val writes = mutableListOf<String>()
            var mock = MockCart("SmartCart-T${t % 10000}", { statuses += it }, { events += it })
            val results = mutableListOf<String>()
            var lostSid: String? = null
            val sync = CartSync(write = { writes += it; mock.control(it) },
                forward = { e -> val r = Api.hwEvent(e.sid, e.seq, e.action, e.label, e.confidence); results += "${e.sid}:${e.seq} ${r.status}"; HwOutcome(r.status, r.message) },
                store = SmartCartTest.MemStore())
            sync.onLost = { lostSid = it; true }
            suspend fun drain() { while (statuses.isNotEmpty() || events.isNotEmpty()) { statuses.removeFirstOrNull()?.let(sync::onStatus); events.removeFirstOrNull()?.let(sync::onEvent) }; assertTrue(sync.pump()) }
            sync.onConnected(); sync.setSession(sid, ss.storeId, ss.lastSeq); sync.onStatus(mock.status()); drain()
            mock.crossing("A", "apple"); mock.crossing("A", "banana"); drain()
            assertEquals(listOf("$sid:1 accepted", "$sid:2 accepted"), results)
            assertEquals(2L, Api.sessionState().lastSeq)

            // The cart restarts: the link drops, a fresh cart reports IDLE.
            sync.onDisconnected()
            mock = MockCart(mock.device, { statuses += it }, { events += it })
            sync.onConnected(); sync.onStatus(mock.status()); drain()
            assertEquals(sid, lostSid); assertFalse("the lost sid is not restarted", mock.active)
            // What ShopModel's handler does: drain, rekey, persist, START the new sid.
            assertTrue(sync.drain(sid))
            val n = Api.sessionRekey()
            assertTrue(n.active); assertNotEquals(sid, n.sessionId); assertEquals(0L, n.lastSeq); assertEquals(ss.storeId, n.storeId)
            assertEquals("same visit, same cart", 2, n.cart.itemCount)
            sync.setSession(n.sessionId, n.storeId, n.lastSeq); drain()
            assertEquals("START|${n.sessionId}|${n.storeId}", writes.last { it.startsWith("START") }); assertEquals(n.sessionId, sync.confirmed)
            mock.crossing("A", "apple"); drain()
            assertEquals("${n.sessionId}:1 accepted", results.last())       // seq 1 again, but a new sid: not a duplicate
            assertEquals(3, Api.current().itemCount)
            assertEquals("no_session", Api.hwEvent(sid, 3, "A", "apple", 90).status)
            println("REKEY-FLOW-OK $sid -> ${n.sessionId} results=$results")
            Api.locate(lat + 270.0 / 111_320.0, lng, 8f); Api.locate(lat + 300.0 / 111_320.0, lng, 8f)
        } finally {
            if (!Api.accountState().first.signedIn) Api.signIn(email, pw)
            assertFalse(Api.deleteAccount(pw).first.signedIn)
        }
    }
}
