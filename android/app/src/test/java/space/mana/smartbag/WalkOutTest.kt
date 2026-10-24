package space.mana.smartbag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live test against the real SpacetimeDB module: one shopper trip, paid by walking out. */
class WalkOutTest {
    private val bayLat = 37.7897
    private val bayLng = -122.3972
    private fun north(m: Double) = bayLat + m / 111_320.0

    @Test fun walkOutPays() = runBlocking {
        var s = Api.setPayment("Test Visa •••• 0000")
        assertEquals("Test Visa •••• 0000", s.payment)
        s = Api.locate(north(15.0), bayLng, 10f)
        assertEquals("bayside-grocer", s.storeId)
        Api.bagLine("+1005@K1"); Api.bagLine("+1005@K1"); s = Api.bagLine("+1008@K2")
        assertEquals(2, s.itemCount); assertEquals(469 + 649, s.totalCents)
        val before = s.lastReceipt?.id ?: 0L
        s = Api.locate(north(260.0), bayLng, 8f)
        assertTrue("first outside fix only marks leaving", s.leaving && s.storeId == "bayside-grocer")
        s = Api.locate(north(290.0), bayLng, 8f)
        assertEquals("", s.storeId)
        assertEquals(0, s.lines.size)
        val r = s.lastReceipt!!
        assertTrue(r.id > before)
        assertEquals(469 + 649, r.total); assertEquals("paid", r.status); assertEquals("Bayside Grocer", r.storeName)
        println("KOTLIN-CLIENT-OK receipt ${r.code} ${r.total} ${r.payment} ${r.items}")
    }
}
