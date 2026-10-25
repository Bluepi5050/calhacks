package space.mana.smartbag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live against module v6 (contract v1.1 §5) with a fresh account at Bayside Grocer. Cart events go through the phone's
 * real CartSync + MockCart (the Jetson stand-in), so START/ACK/STOP are the app's own; nothing here touches Bluetooth.
 * session → A / duplicate / unknown / invalid remove / malformed → budget → set_qty / relabel → store products →
 * recommend → walk out (receipt, STOP) → stale event → summary; then the account is deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HwFlowTest {
    private val lat = 37.7897; private val lng = -122.3972
    private fun north(m: Double) = lat + m / 111_320.0

    @Test fun cartSessionToSummary() = runBlocking {
        val t = System.currentTimeMillis()
        val email = "cart+$t@bagitwith.us"; val pw = "cart test $t"
        if (Api.accountState().first.signedIn) Api.signOut()
        Api.locate(north(2000.0), lng, 10f)                                   // start outside any store
        assertTrue(Api.signUp("Cart Test", email, pw).first.signedIn)
        try {
            Api.cardAdd("Visa", "4242", 12, 2029, "Demo", true)
            var ss = Api.sessionState()
            assertFalse("no session outside a store", ss.active); assertEquals("", ss.sessionId)

            // Phone side: the app's own state machine and the Jetson stand-in.
            val statuses = ArrayDeque<String>(); val events = ArrayDeque<String>(); val writes = mutableListOf<String>()
            val mock = MockCart("SmartCart-T${t % 10000}", { statuses += it }, { events += it })
            var last: HwEventResult? = null
            val sync = CartSync(write = { writes += it; mock.control(it) },
                forward = { e -> Api.hwEvent(e.sid, e.seq, e.action, e.label, e.confidence).also { last = it }.let { HwOutcome(it.status, it.message) } },
                store = SmartCartTest.MemStore())
            suspend fun drain() { while (statuses.isNotEmpty() || events.isNotEmpty()) { statuses.removeFirstOrNull()?.let(sync::onStatus); events.removeFirstOrNull()?.let(sync::onEvent) }; assertTrue(sync.pump()) }
            sync.onConnected(); sync.onStatus(mock.status()); drain()
            assertFalse("connected outside the store: no session on the cart", mock.active)

            // Enter Bayside: shop_locate opens the session; the phone STARTs it.
            val inside = Api.locate(lat, lng, 8f)
            assertEquals("bayside-grocer", inside.storeId)
            ss = Api.sessionState()
            assertTrue(ss.active); assertTrue(CartProtocol.validSid(ss.sessionId)); assertEquals("bayside-grocer", ss.storeId); assertEquals(0L, ss.lastSeq)
            val sid = ss.sessionId
            sync.setSession(sid, ss.storeId); drain()
            assertEquals("START|$sid|bayside-grocer", writes.first { it.startsWith("START") })
            assertEquals(sid, sync.confirmed)

            mock.crossing("A", "apple", 93); drain()
            assertEquals("accepted", last!!.status)
            val apple = last!!.state.cart.lines.single { it.sold }
            assertEquals(1, apple.qty); assertTrue(apple.priceCents > 0)
            assertEquals("ACK|$sid|1", writes.last()); assertEquals(0, mock.unackedCount)
            // Same event again from another path: the backend de-duplicates by sid:seq.
            assertEquals("duplicate", Api.hwEvent(sid, 1, "A", "apple", 93).status)
            mock.crossing("A", "cell phone", 71); drain()
            assertEquals("unknown_product", last!!.status)
            assertTrue(last!!.state.cart.lines.any { !it.sold })
            mock.crossing("R", "orange", 88); drain()
            assertEquals("invalid_remove", last!!.status)
            assertEquals("malformed events are terminal, not errors", "rejected", Api.hwEvent(sid, 99, "X", "apple", 50).status)
            assertEquals(listOf(1L, 2L, 3L), writes.filter { it.startsWith("ACK") }.map { it.substringAfterLast('|').toLong() })

            // Budget, corrections, catalog, suggestions.
            ss = Api.setBudget(500)
            assertEquals(500, ss.budgetCents); assertEquals(apple.priceCents, ss.spentCents); assertEquals(500L - apple.priceCents, ss.remainingCents)
            ss = Api.setQty(apple.sku, 3)
            assertEquals(3, ss.cart.lines.single { it.sku == apple.sku }.qty)
            val products = Api.storeProducts()
            assertTrue(products.any { it.reason == "apple" && it.sku == apple.sku })
            val banana = products.first { it.reason == "banana" }
            ss = Api.relabel(apple.sku, banana.sku)
            assertEquals(2, ss.cart.lines.single { it.sku == apple.sku }.qty); assertEquals(1, ss.cart.lines.single { it.sku == banana.sku }.qty)
            val recs = Api.recommend()
            assertTrue(recs.items.size <= 3); assertTrue(recs.source == "rules" || recs.source == "ai")
            if (ss.remainingCents >= 0) assertTrue("suggestions fit the remaining budget", recs.items.all { it.priceCents <= ss.remainingCents })
            mock.crossing("R", "apple", 89); drain()
            assertEquals("accepted", last!!.status)
            assertEquals(1, last!!.state.cart.lines.single { it.sku == apple.sku }.qty)
            val total = last!!.state.cart.totalCents

            // Walk out: two fixes outside → receipt; the phone STOPs the cart; later events change nothing.
            Api.locate(north(270.0), lng, 8f)
            val out = Api.locate(north(300.0), lng, 8f)
            val r = out.lastReceipt!!
            assertEquals(total, r.total); assertEquals("paid", r.status); assertEquals("Visa •••• 4242", r.payment)
            assertFalse(Api.sessionState().active)
            sync.setSession(null, null); drain()
            assertEquals("STOP|$sid", writes.last { it.startsWith("STOP") }); assertFalse(mock.active)
            assertFalse("no events without a session", mock.crossing("A", "apple"))
            assertEquals("stale_session", Api.hwEvent(sid, 10, "A", "apple", 90).status)

            val sum = Api.summary(0)
            assertEquals(r.id, sum.receipt.id); assertEquals(sid, sum.sessionId); assertEquals(500, sum.budgetCents)
            assertEquals(500L - total, sum.underBudgetCents); assertTrue("events counted: ${sum.eventCount}", sum.eventCount >= 4); assertTrue(sum.text.isNotBlank())
            assertTrue(sum.endedMicros >= sum.startedMicros)
            assertEquals(sum, Api.summary(r.id))
            println("HW-FLOW-OK sid=$sid receipt=${r.code} total=$total budget=500 recs=${recs.source}:${recs.items.map { it.name }} events=${sum.eventCount} summary='${sum.text}'")
        } finally {
            Api.setBudget(0)
            if (!Api.accountState().first.signedIn) Api.signIn(email, pw)
            assertFalse(Api.deleteAccount(pw).first.signedIn)
        }
    }
}
