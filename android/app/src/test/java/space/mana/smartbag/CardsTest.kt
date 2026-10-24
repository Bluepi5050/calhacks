package space.mana.smartbag

import org.junit.Assert.*
import org.junit.Test

/** Offline checks: card validation (what the form allows before anything is sent), labels, error cleaning, control lines. */
class CardsTest {
    @Test fun brands() {
        assertEquals("Visa", Cards.brand("4242424242424242"))
        assertEquals("Mastercard", Cards.brand("5555555555554444"))
        assertEquals("Mastercard", Cards.brand("2223003122003222"))
        assertEquals("Amex", Cards.brand("378282246310005"))
        assertEquals("Discover", Cards.brand("6011111111111117"))
        assertEquals("Card", Cards.brand("9999"))
    }
    @Test fun numbers() {
        assertNull(Cards.numberError("4242424242424242"))
        assertNull(Cards.numberError("378282246310005"))
        assertEquals("Check the card number.", Cards.numberError("4242424242424241"))   // Luhn
        assertEquals("Check the card number.", Cards.numberError("37828224631000"))     // Amex needs 15
        assertEquals("Enter the card number.", Cards.numberError(""))
        assertEquals("4242 4242 4242 4242", Cards.grouped("4242424242424242"))
        assertEquals("3782 822463 10005", Cards.grouped("378282246310005"))
        assertEquals(15, Cards.maxLength("Amex")); assertEquals(4, Cards.cvcLength("Amex")); assertEquals(3, Cards.cvcLength("Visa"))
    }
    @Test fun expiry() {
        assertEquals(12 to 2029, Cards.expiry("1229"))
        assertNull(Cards.expiry("1329"))
        assertNull(Cards.expiryError("1229", 2026, 10))
        assertNull(Cards.expiryError("1026", 2026, 10))
        assertEquals("This card has expired.", Cards.expiryError("0926", 2026, 10))
        assertEquals("Check the expiry date.", Cards.expiryError("12", 2026, 10))
        assertEquals("Check the security code.", Cards.cvcError("12", "Visa"))
        assertNull(Cards.cvcError("1234", "Amex"))
    }
    @Test fun labelsAndErrors() {
        val c = CardInfo(1, "Visa", "4242", 8, 2029, "Demo", true)
        assertEquals("Demo Visa •••• 4242", c.label); assertEquals("08/29", c.expiry)
        assertEquals("Visa •••• 4242", c.copy(nickname = "").label)
        assertEquals("Email or password is wrong.", cleanError("The module instance encountered a fatal error: Email or password is wrong."))
        assertEquals("Log in first.", cleanError("The module instance encountered a fatal error: SenderError: Log in first."))
        assertEquals("Bag 3F2A", bagDisplayName("SmartBag-3F2A")); assertEquals("Bag", bagDisplayName("SmartBag-01"))
        assertTrue(isControlLine("BAG SmartBag-3F2A fw2")); assertFalse(isControlLine("+1005@K1")); assertFalse(isControlLine("1005"))
        assertEquals("Right here", signalWords(-50)); assertEquals("Nearby", signalWords(-70)); assertEquals("Farther away", signalWords(-90))
    }
    @Test fun setupSkipsWhatTheAccountHas() {
        val ready = Perms(true, true, true, true)
        val bag = BagInfo(1, "SmartBag-1", "Mine", 0)
        val card = CardInfo(1, "Visa", "4242", 8, 2029, "", true)
        val full = AccountState(true, "A", "a@b.co", listOf(card), listOf(bag), card.label)
        assertEquals(emptyList<Step>(), setupSteps(full, ready, signUp = false))
        assertEquals(listOf(Step.Pair, Step.Card), setupSteps(full.copy(cards = emptyList(), bags = emptyList()), ready, signUp = false))
        assertEquals(listOf(Step.Perms, Step.Pair, Step.Card), setupSteps(SIGNED_OUT.copy(signedIn = true), Perms(false, false, false, false), signUp = true))
    }
    @Test fun accountCodec() {
        val a = AccountState(true, "Scott", "s@x.co", listOf(CardInfo(7, "Visa", "4242", 12, 2029, "Demo", true)),
            listOf(BagInfo(3, "SmartBag-3F2A", "Scott's bag", 1792878840000000L)), "Demo Visa •••• 4242")
        val b = Bw().bool(true).str("Scott").str("s@x.co").u32(1).i64(7).str("Visa").str("4242").u32(12).u32(2029).str("Demo").bool(true)
            .u32(1).i64(3).str("SmartBag-3F2A").str("Scott's bag").i64(1792878840000000L).str("Demo Visa •••• 4242").done()
        assertEquals(a, Api.decodeAccount(b))
        try { Api.decodeAccount(b + byteArrayOf(0)); fail("trailing bytes must be rejected") } catch (e: ApiException) {}
    }
}
