package space.mana.smartbag

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Local notifications: channels, copy, switches, receipt de-duplication (local vs server push), tap routing, receivers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotifyTest {
    private lateinit var ctx: Context
    private lateinit var nm: NotificationManager
    private val paid = Receipt(1, "SRF6WQ", "Bayside Grocer", 1118, 2, 0, "paid", "Visa •••• 4242", 0L, emptyList())
    private val due = paid.copy(id = 2, code = "Q8ZR2C", status = "payment_due", payment = "")

    @Before fun setUp() {
        ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("smartbag", 0).edit().clear().commit()
        nm = ctx.getSystemService(NotificationManager::class.java)
        Notify.channels(ctx)
    }

    private fun all() = shadowOf(nm).allNotifications
    private fun title(n: android.app.Notification) = n.extras.getString(android.app.Notification.EXTRA_TITLE)
    private fun body(n: android.app.Notification) = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()

    @Test fun channels() {
        val want = mapOf("session" to NotificationManager.IMPORTANCE_LOW, "receipts" to NotificationManager.IMPORTANCE_HIGH,
            "store" to NotificationManager.IMPORTANCE_DEFAULT, "bag" to NotificationManager.IMPORTANCE_HIGH, "account" to NotificationManager.IMPORTANCE_HIGH)
        want.forEach { (id, imp) -> assertEquals(id, imp, nm.getNotificationChannel(id).importance) }
        assertNull("3.2's channel removed", nm.getNotificationChannel("paid"))
        assertEquals(Notify.CH_RECEIPTS, NotifKind.Trips.channel); assertEquals(Notify.CH_ACCOUNT, NotifKind.Security.channel)
    }

    @Test fun copy() {
        assertEquals(NotifyCopy.Text("Paid $11.18 at Bayside Grocer", "2 items with Visa •••• 4242. Exit code SRF6WQ."), NotifyCopy.receipt(paid))
        val d = NotifyCopy.receipt(due)
        assertEquals("Payment due at Bayside Grocer", d.title)
        assertEquals("$11.18 for 2 items. No card was on file, so nothing was charged. Show exit code Q8ZR2C to store staff.", d.body)
        assertFalse("never claims payment due can be settled in the app", d.body.contains("settle") || d.body.contains("in Bag It"))
        assertEquals("1 item with Visa •••• 4242. Exit code SRF6WQ.", NotifyCopy.receipt(paid.copy(count = 1)).body)
        assertEquals("Drop things in your bag. Leaving pays with Visa •••• 4242.", NotifyCopy.arrival("Bayside Grocer", "Visa •••• 4242").body)
        assertEquals("You're at Bayside Grocer", NotifyCopy.arrival("Bayside Grocer", "").title)
        assertEquals("Add a card before you leave, or you'll pay at the store.", NotifyCopy.arrival("Bayside Grocer", "").body)
        assertEquals("Your bag disconnected", NotifyCopy.bagLost.title)
    }

    @Test fun receiptOnReceiptsChannelAndTapOpensIt() {
        Notify.receipt(ctx, paid)
        val n = all().single()
        assertEquals("receipts", n.channelId)
        assertEquals("Paid $11.18 at Bayside Grocer", title(n))
        val sent = shadowOf(n.contentIntent).savedIntent
        assertEquals("SRF6WQ", sent.getStringExtra(Notify.EXTRA_RECEIPT))
        assertEquals(MainActivity::class.java.name, sent.component!!.className)
        Notify.cancelReceipt(ctx, "SRF6WQ")
        assertTrue(all().isEmpty())
    }

    private fun signIn() = ctx.getSharedPreferences("smartbag", 0).edit().putString("account_v5", "x").commit()

    @Test fun signedOutPushAndCleanup() {
        Notify.fromPush(ctx, mapOf("kind" to "trips", "type" to "trip", "code" to "AB12CD"), "Paid", "x")
        assertTrue("signed-out phones drop pushes", all().isEmpty())
        signIn()
        Notify.receipt(ctx, paid); Notify.bagLost(ctx); Notify.status(ctx, "Bag It is watching")
        assertEquals(3, all().size)
        Notify.clearAccount(ctx)
        assertEquals("only the session notification stays", listOf(Notify.ONGOING), shadowOf(nm).activeNotifications.map { it.id })
    }

    @Test fun dedupeLocalAndServer() {
        signIn()
        Notify.receipt(ctx, paid)
        Notify.receipt(ctx, paid)                                                      // same walk-out twice: once
        Notify.fromPush(ctx, mapOf("kind" to "trips", "type" to "trip", "code" to "SRF6WQ"), "Paid $11.18 at Bayside Grocer", "x")
        assertEquals(1, all().size)
        // A trip from another phone (new code) shows; the same push again doesn't.
        val other = mapOf("kind" to "trips", "type" to "payment_due", "code" to "ZZ9ZZZ")
        Notify.fromPush(ctx, other, "Payment due at Mission Fresh", "y")
        Notify.fromPush(ctx, other, "Payment due at Mission Fresh", "y")
        assertEquals(2, all().size)
        // Server first, then this phone's own walk-out with the same code: still one.
        Notify.fromPush(ctx, mapOf("kind" to "trips", "type" to "trip", "code" to "Q8ZR2C"), "Payment due at Bayside Grocer", "z")
        Notify.receipt(ctx, due)
        assertEquals(3, all().size)
        // Security pushes go to the account channel; in the foreground claim() without posting.
        Notify.fromPush(ctx, mapOf("kind" to "security", "type" to "sign_in"), "New sign-in to Bag It", "w")
        assertEquals("account", all().last { title(it) == "New sign-in to Bag It" }.channelId)
        assertFalse(Notify.claim(ctx, "SRF6WQ"))
    }

    @Test fun switchesAndChannels() {
        val prefs = NotifPrefs(ctx)
        assertEquals("trips,security", prefs.serverPrefs())
        prefs.set(NotifKind.Trips, false); prefs.set(NotifKind.Store, false)
        assertEquals("security", prefs.serverPrefs())
        signIn()
        Notify.receipt(ctx, paid)
        Notify.arrival(ctx, "Bayside Grocer", "Visa •••• 4242")
        Notify.fromPush(ctx, mapOf("kind" to "trips", "type" to "trip", "code" to "AAAAAA"), "Paid", "x")
        assertTrue("switched-off categories post nothing", all().isEmpty())
        Notify.bagLost(ctx)
        assertEquals("bag", all().single().channelId)
        prefs.set(NotifKind.Store, true)
        Notify.arrival(ctx, "Bayside Grocer", "")
        assertEquals("store", all().first { it.channelId == "store" }.channelId)
        assertEquals("Add a card before you leave, or you'll pay at the store.", body(all().first { it.channelId == "store" }))
        prefs.set(NotifKind.Security, false)
        assertEquals("", prefs.serverPrefs())
    }

    @Test fun rules() {
        val bay = StoreState.InStore("bayside-grocer", "Bayside Grocer", "", false)
        assertEquals("bayside-grocer" to true, NotifyRules.arrival(null, bay, foreground = false))
        assertEquals("bayside-grocer" to false, NotifyRules.arrival("bayside-grocer", bay, foreground = false))    // once per visit
        assertEquals("bayside-grocer" to false, NotifyRules.arrival(null, bay, foreground = true))               // in-app instead
        assertEquals(null to false, NotifyRules.arrival("bayside-grocer", StoreState.Away(null, null), false))    // visit ends
        assertEquals("bayside-grocer" to false, NotifyRules.arrival("bayside-grocer", StoreState.Locating, false))
        assertTrue(NotifyRules.warnBag(connected = false, inStore = true, count = 2, foreground = false))
        assertFalse(NotifyRules.warnBag(false, true, 0, false)); assertFalse(NotifyRules.warnBag(false, false, 2, false))
        assertFalse(NotifyRules.warnBag(true, true, 2, false)); assertFalse(NotifyRules.warnBag(false, true, 2, true))
        assertEquals("enter", NotifyRules.geofenceAction(Geofence.GEOFENCE_TRANSITION_ENTER))
        assertEquals("exit", NotifyRules.geofenceAction(Geofence.GEOFENCE_TRANSITION_EXIT))
        assertNull(NotifyRules.geofenceAction(Geofence.GEOFENCE_TRANSITION_DWELL))
        assertEquals(190f, NotifyRules.radius(NearbyStore("b", "B", "", 0.0, 0.0, 150, 0)))
    }

    @Test fun receiversIgnoreJunkAndNeedSignIn() {
        val app = shadowOf(ctx as Application)
        GeofenceReceiver().onReceive(ctx, Intent(ctx, GeofenceReceiver::class.java))        // no geofence data
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))                 // signed out: nothing to restore
        BootReceiver().onReceive(ctx, Intent("android.intent.action.SOMETHING_ELSE"))
        assertNull("no service started", app.nextStartedService)
        // Geofences: cache round trip; registering without background location is refused, not crashed.
        val stores = listOf(NearbyStore("bayside-grocer", "Bayside Grocer", "", 37.7897, -122.3972, 150, 12))
        Geofences.save(ctx, stores)
        assertEquals("bayside-grocer", Geofences.cached(ctx).single().id)
        assertFalse(Geofences.hasBackground(ctx))
        assertFalse(Geofences.register(ctx))
        Geofences.clear(ctx)
        assertTrue(Geofences.cached(ctx).isEmpty())
    }

    @Test fun pushCopyIsHonest() {
        assertTrue(pushLine(false, null).contains("can't reach this phone yet"))
        assertTrue(pushLine(true, PushState(false, true, "trips")).contains("aren't turned on yet"))
        assertTrue(pushLine(true, PushState(true, true, "trips")).startsWith("On."))
        assertFalse(pushLine(true, PushState(true, true, "trips")).contains("guarantee"))
    }
}
