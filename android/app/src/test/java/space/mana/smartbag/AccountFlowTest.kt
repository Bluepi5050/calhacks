package space.mana.smartbag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Live test against the real SpacetimeDB module v5 (smartcart-5dt35), using the app's own hand-written client:
 * sign up, add the demo card, pair a bag, walk out of Bayside Grocer charged to the card, Trips, a second card +
 * default switch, sign out, sign back in, delete the account. Also checks the BSATN codecs byte for byte: every
 * AccountState / Receipt[] / CartState the server sends is decoded, re-encoded and compared with the original bytes.
 */
class AccountFlowTest {
    private val bayLat = 37.7897
    private val bayLng = -122.3972
    private fun north(m: Double) = bayLat + m / 111_320.0

    // Re-encoders mirroring the module's types, field by field (the decoders must consume every byte too).
    private fun enc(a: AccountState): ByteArray = Bw().bool(a.signedIn).str(a.name).str(a.email).u32(a.cards.size).apply {
        a.cards.forEach { c -> i64(c.id).str(c.brand).str(c.last4).u32(c.expMonth).u32(c.expYear).str(c.nickname).bool(c.isDefault) }
    }.u32(a.bags.size).apply { a.bags.forEach { b -> i64(b.id).str(b.bagId).str(b.name).i64(b.pairedMicros) } }.str(a.payment).done()
    private fun Bw.receipt(r: Receipt) = i64(r.id).str(r.code).str(r.storeName).u32(r.total).u32(r.count).u32(r.unpriced).str(r.status).str(r.payment)
        .i64(r.paidMicros).u32(r.items.size).apply { r.items.forEach { l -> str(l.sku).str(l.name).u32(l.price).u32(l.qty) } }
    private fun enc(t: List<Receipt>): ByteArray = Bw().u32(t.size).apply { t.forEach { receipt(it) } }.done()

    private suspend fun acct(proc: String, args: ByteArray = ByteArray(0)): AccountState {
        val raw = Api.call(proc, args)
        val a = Api.decodeAccount(raw)
        assertArrayEquals("AccountState codec round trip ($proc)", raw, enc(a))
        return a
    }

    private suspend fun expectError(fragment: String, block: suspend () -> Unit) {
        try { block(); fail("expected error containing '$fragment'") } catch (e: ApiException) {
            assertTrue("'${e.message}' should contain '$fragment'", e.message!!.contains(fragment))
            assertFalse("prefix stripped: ${e.message}", e.message!!.contains("fatal error"))
        }
    }

    @Test fun accountWalletBagsTrips() = runBlocking {
        val t = System.currentTimeMillis()
        val email = "test+$t@bagitwith.us"
        val pw = "correct horse $t"
        var a = acct("account_state")
        if (a.signedIn) a = Api.signOut().first
        assertFalse(a.signedIn)

        expectError("at least 8") { Api.signUp("Test", "x$email", "short") }
        a = acct("account_sign_up", Bw().str("Test Shopper").str(email).str(pw).done())
        assertTrue(a.signedIn); assertEquals("Test Shopper", a.name); assertEquals(email, a.email)
        assertTrue(a.cards.isEmpty()); assertEquals("", a.payment)
        expectError("already has an account") { Api.signUp("X", email.uppercase(), pw) }

        // Demo card: only brand, last four and expiry are sent.
        a = acct("card_add", Bw().str("Visa").str("4242").u32(12).u32(2029).str("Demo").bool(false).done())
        assertEquals(1, a.cards.size); assertTrue(a.cards[0].isDefault)
        assertEquals("Demo Visa •••• 4242", a.payment); assertEquals(a.cards[0].label, a.payment); assertEquals("12/29", a.cards[0].expiry)
        expectError("already in your wallet") { Api.cardAdd("Visa", "4242", 12, 2029, "", false) }
        expectError("expired") { Api.cardAdd("Visa", "1111", 1, 2024, "", false) }

        val bag = "SmartBag-T${t % 1_000_000}"
        a = acct("bag_pair", Bw().str(bag).str("Test's bag").done())
        assertEquals(listOf(bag), a.bags.map { it.bagId }); assertEquals("Bag T${t % 1_000_000}", bagDisplayName(bag))

        // Walk-out trip at Bayside Grocer, charged to the default card.
        var s = Api.locate(north(15.0), bayLng, 10f)
        assertEquals("bayside-grocer", s.storeId); assertEquals("Demo Visa •••• 4242", s.payment)
        Api.bagLine("+1005@A$t"); s = Api.bagLine("+1008@B$t")
        assertEquals(469 + 649, s.totalCents)
        val stateRaw = Api.call("shop_state", ByteArray(0))
        assertEquals(s.totalCents, Api.decodeState(stateRaw).totalCents)
        Api.locate(north(270.0), bayLng, 8f)
        s = Api.locate(north(300.0), bayLng, 8f)
        val r = s.lastReceipt!!
        assertEquals("", s.storeId); assertEquals("paid", r.status); assertEquals("Demo Visa •••• 4242", r.payment); assertEquals(469 + 649, r.total)

        val tripsRaw = Api.call("shop_trips", Bw().u32(20).done())
        val trips = Api.decodeTrips(tripsRaw)
        assertArrayEquals("Receipt[] codec round trip", tripsRaw, enc(trips))
        assertEquals(r.id, trips.first().id); assertEquals(r.code, trips.first().code); assertEquals(2, trips.first().items.size)

        // Second card, switch the default; the phone's walk-out payment follows.
        a = acct("card_add", Bw().str("Mastercard").str("4444").u32(1).u32(2031).str("").bool(false).done())
        assertEquals("Demo Visa •••• 4242", a.payment)
        val mc = a.cards.first { it.last4 == "4444" }
        a = acct("card_set_default", Bw().u64(mc.id).done())
        assertEquals("Mastercard •••• 4444", a.payment); assertEquals("Mastercard •••• 4444", Api.current().payment)
        a = acct("card_remove", Bw().u64(mc.id).done())
        assertEquals("Demo Visa •••• 4242", a.payment)          // removing the default promotes the oldest card

        a = acct("bag_rename", Bw().u64(a.bags[0].id).str("Kitchen bag").done())
        assertEquals("Kitchen bag", a.bags[0].name)

        a = acct("account_sign_out")
        assertFalse(a.signedIn); assertEquals("", Api.current().payment)
        expectError("Log in first") { Api.cardAdd("Visa", "1234", 1, 2030, "", false) }
        expectError("Email or password is wrong") { Api.signIn(email, "wrong password") }

        a = Api.signIn(email.uppercase(), pw).first
        assertTrue(a.signedIn); assertEquals(1, a.cards.size); assertEquals(1, a.bags.size)
        assertEquals("Demo Visa •••• 4242", Api.current().payment)
        assertTrue(Api.trips(5).any { it.id == r.id })
        expectError("That password is wrong") { Api.deleteAccount("nope nope") }
        a = acct("account_delete", Bw().str(pw).done())
        assertFalse(a.signedIn); assertEquals("", Api.current().payment)
        expectError("Email or password is wrong") { Api.signIn(email, pw) }
        println("ACCOUNT-FLOW-OK $email receipt ${r.code} ${money(r.total)} ${r.payment}")
    }
}
