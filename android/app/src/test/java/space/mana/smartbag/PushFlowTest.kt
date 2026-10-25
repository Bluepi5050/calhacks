package space.mana.smartbag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Live: push registration lifecycle and nearby stores against module v5.1, with a fresh account, then delete it.
 * The token is fake (no FCM project in this build): this checks the procedures and our codecs, NOT delivery.
 */
class PushFlowTest {
    private fun push(proc: String, args: ByteArray = ByteArray(0)): PushState = runBlocking {
        val raw = Api.call(proc, args)
        val s = Api.decodePush(raw)
        assertArrayEquals("PushState round trip ($proc)", raw, Bw().bool(s.serverPush).bool(s.registered).str(s.prefs).done())
        s
    }

    @Test fun registerStatusTestUnregister() = runBlocking {
        val t = System.currentTimeMillis()
        val email = "push+$t@bagitwith.us"; val pw = "push test $t"
        if (Api.accountState().first.signedIn) Api.signOut()
        try { Api.pushRegister("x".repeat(40), "trips"); fail("needs sign-in") } catch (e: ApiException) { assertTrue(e.message!!.contains("Log in first")) }
        assertTrue(Api.signUp("Push Test", email, pw).first.signedIn)
        try {
            var s = push("push_status")
            assertFalse(s.registered)
            val configured = s.serverPush
            try { Api.pushRegister("short", "trips"); fail("token too short") } catch (e: ApiException) { assertTrue(e.message!!.contains("token")) }
            s = push("push_register", Bw().str("android").str("fake-fcm-token-$t-" + "a".repeat(30)).str("trips,security,bogus").done())
            assertTrue(s.registered); assertEquals("trips,security", s.prefs)           // unknown categories dropped
            s = push("push_set_prefs", Bw().str("trips").done())
            assertEquals("trips", s.prefs)
            val test = Api.pushTest()
            if (!configured) assertEquals("push not configured", test)
            else assertFalse("a fake token can't be delivered: $test", test.startsWith("sent 1/1"))
            s = push("push_unregister")
            assertFalse(s.registered)
            assertEquals("not registered", Api.pushTest())
            val near = Api.nearbyStores(37.7897, -122.3972, 20)
            assertEquals("bayside-grocer", near.first().id); assertTrue(near.first().radiusM > 0)
            assertTrue(near.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
            val raw = Api.call("shop_nearby_stores", Bw().f64(37.7897).f64(-122.3972).u32(5).done())
            val ns = Api.decodeNearby(raw)
            assertArrayEquals(raw, Bw().u32(ns.size).apply { ns.forEach { n -> str(n.id).str(n.name).str(n.address).f64(n.lat).f64(n.lng).u32(n.radiusM).u32(n.distanceM) } }.done())
            // Sign-out removes the device on the server.
            push("push_register", Bw().str("android").str("fake-fcm-token-$t-" + "b".repeat(30)).str("trips").done())
            Api.signOut()
            assertFalse(push("push_status").registered)
            Api.signIn(email, pw)
            println("PUSH-FLOW-OK serverPush=$configured test='$test' nearby=${near.size}")
        } finally {
            if (!Api.accountState().first.signedIn) Api.signIn(email, pw)
            assertFalse(Api.deleteAccount(pw).first.signedIn)
        }
    }
}
