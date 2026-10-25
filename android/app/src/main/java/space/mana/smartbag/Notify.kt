package space.mana.smartbag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** What a notification says. Pure (unit tested); the same words the module uses where it can be honest. */
object NotifyCopy {
    data class Text(val title: String, val body: String)
    private fun items(n: Int) = "$n item" + if (n == 1) "" else "s"

    /** A walk-out charged on this phone. Payment due cannot be settled in the app: say what really happens. */
    fun receipt(r: Receipt): Text = if (r.status == "paid")
        Text("Paid ${money(r.total)} at ${r.storeName}", "${items(r.count)}" + (if (r.payment.isNotEmpty()) " with ${r.payment}" else "") + ". Exit code ${r.code}.")
    else Text("Payment due at ${r.storeName}", "${money(r.total)} for ${items(r.count)}. No card was on file, so nothing was charged. Show exit code ${r.code} to store staff.")

    fun arrival(store: String, card: String): Text = Text("You're at $store",
        if (card.isNotEmpty()) "Drop things in your bag. Leaving pays with $card." else "Add a card before you leave, or you'll pay at the store.")

    val bagLost = Text("Your bag disconnected", "Keep it near your phone so new items are counted.")
    val test = Text("Notifications are on", "This is how Bag It reaches you while you shop.")
}

/** Notification categories the shopper can switch off (Account › Notifications). All on by default. */
enum class NotifKind(val key: String, val channel: String) {
    Trips("trips", Notify.CH_RECEIPTS), Store("store", Notify.CH_STORE), Bag("bag", Notify.CH_BAG), Security("security", Notify.CH_ACCOUNT)
}

class NotifPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("smartbag", 0)
    fun on(k: NotifKind) = p.getBoolean("notif_${k.key}", true)
    fun set(k: NotifKind, on: Boolean) { p.edit().putBoolean("notif_${k.key}", on).apply() }
    /** The categories the server sends (trips from other phones, account security), as push_register expects. */
    fun serverPrefs() = listOf(NotifKind.Trips, NotifKind.Security).filter(::on).joinToString(",") { it.key }
    fun all() = NotifKind.values().associateWith(::on)
}

object Notify {
    const val ONGOING = 1
    private const val BAG = 3
    const val CH_SESSION = "session"
    const val CH_RECEIPTS = "receipts"
    const val CH_STORE = "store"
    const val CH_BAG = "bag"
    const val CH_ACCOUNT = "account"
    const val EXTRA_RECEIPT = "open_receipt"

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, imp: Int, desc: String) = nm.createNotificationChannel(NotificationChannel(id, name, imp).apply { description = desc })
        ch(CH_SESSION, "Shopping session", NotificationManager.IMPORTANCE_LOW, "Your shopping status while Bag It watches for you to leave")
        ch(CH_RECEIPTS, "Trips and receipts", NotificationManager.IMPORTANCE_HIGH, "Your receipt when a trip is charged, or when payment is due")
        ch(CH_STORE, "Store arrivals", NotificationManager.IMPORTANCE_DEFAULT, "When you walk into a Bag It store")
        ch(CH_BAG, "Bag alerts", NotificationManager.IMPORTANCE_HIGH, "If your bag disconnects while you shop")
        ch(CH_ACCOUNT, "Account security", NotificationManager.IMPORTANCE_HIGH, "New sign-ins and cards added on another phone")
        try { nm.deleteNotificationChannel("paid") } catch (_: Exception) {}       // 3.2's receipts channel
    }

    /** Tapping opens Bag It; with a receipt code, straight to that receipt. */
    private fun open(ctx: Context, code: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (code != null) i.putExtra(EXTRA_RECEIPT, code)
        return PendingIntent.getActivity(ctx, code?.hashCode() ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("DEPRECATION")
    private fun builder(ctx: Context, ch: String) =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else Notification.Builder(ctx)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    fun enabled(ctx: Context): Boolean = try { nm(ctx).areNotificationsEnabled() } catch (_: Exception) { false }

    private fun post(ctx: Context, ch: String, tag: String?, id: Int, t: NotifyCopy.Text, code: String? = null, coral: Boolean = false) {
        try {
            nm(ctx).notify(tag, id, builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_stat_bag).setContentTitle(t.title).setContentText(t.body)
                .setStyle(Notification.BigTextStyle().bigText(t.body))
                .setColor(if (coral) 0xFFE8654A.toInt() else 0xFF18181B.toInt())       // coral = money moved
                .setShowWhen(true).setAutoCancel(true).setContentIntent(open(ctx, code)).build())
        } catch (_: Exception) {}      // notifications off / not allowed: the app shows the same thing in-app
    }

    fun ongoing(ctx: Context, text: String): Notification = builder(ctx, CH_SESSION)
        .setSmallIcon(R.drawable.ic_stat_bag).setContentTitle("Bag It").setContentText(text).setColor(0xFF18181B.toInt())
        .setShowWhen(false).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(ctx)).build()

    fun status(ctx: Context, text: String) {
        try { nm(ctx).notify(ONGOING, ongoing(ctx, text)) } catch (_: Exception) {}
    }

    // ---- receipts: one notification per receipt code, whether it came from this phone or from the server

    private const val RECEIPT_ID = 2
    private fun shownCodes(ctx: Context) = ctx.getSharedPreferences("smartbag", 0)
    /** Marks [code] as notified; false if it already was (the other path got there first). */
    @Synchronized fun claim(ctx: Context, code: String): Boolean {
        val p = shownCodes(ctx)
        val seen = p.getString("notified_codes", "")!!.split(',').filter { it.isNotEmpty() }
        if (code in seen) return false
        p.edit().putString("notified_codes", (seen + code).takeLast(30).joinToString(",")).apply()
        return true
    }

    fun receipt(ctx: Context, r: Receipt) {
        if (!NotifPrefs(ctx).on(NotifKind.Trips) || !claim(ctx, r.code)) return
        post(ctx, CH_RECEIPTS, r.code, RECEIPT_ID, NotifyCopy.receipt(r), r.code, coral = r.status == "paid")
    }

    /** The in-app receipt is on screen: the notification has done its job. */
    fun cancelReceipt(ctx: Context, code: String) { try { nm(ctx).cancel(code, RECEIPT_ID) } catch (_: Exception) {} }

    fun arrival(ctx: Context, store: String, card: String) {
        if (!NotifPrefs(ctx).on(NotifKind.Store)) return
        post(ctx, CH_STORE, "arrival", 4, NotifyCopy.arrival(store, card))
    }
    fun cancelArrival(ctx: Context) { try { nm(ctx).cancel("arrival", 4) } catch (_: Exception) {} }

    fun bagLost(ctx: Context) {
        if (!NotifPrefs(ctx).on(NotifKind.Bag)) return
        post(ctx, CH_BAG, null, BAG, NotifyCopy.bagLost)
    }
    fun cancelBag(ctx: Context) { try { nm(ctx).cancel(BAG) } catch (_: Exception) {} }

    fun test(ctx: Context) = post(ctx, CH_RECEIPTS, "test", 5, NotifyCopy.test)

    /** Sign-out: remove everything except the shopping-session notification. */
    fun clearAccount(ctx: Context) {
        try { nm(ctx).activeNotifications.filter { it.id != ONGOING }.forEach { nm(ctx).cancel(it.tag, it.id) } } catch (_: Exception) {}
    }

    /**
     * A server push (FCM data while the app is in the foreground). Trips are deduplicated against receipts this phone
     * already showed (the server also skips the phone that caused the event); categories the shopper turned off are dropped.
     */
    fun fromPush(ctx: Context, data: Map<String, String>, title: String?, body: String?) {
        if (!Geofences.signedIn(ctx)) return                                    // signed out since the push was sent
        val kind = if (data["kind"] == "security") NotifKind.Security else NotifKind.Trips
        if (!NotifPrefs(ctx).on(kind) || title.isNullOrBlank()) return
        val code = data["code"]?.takeIf { it.isNotBlank() && data["type"] in setOf("trip", "payment_due") }
        if (code != null && !claim(ctx, code)) return
        post(ctx, kind.channel, code ?: "push-${data["type"]}", if (code != null) RECEIPT_ID else 6, NotifyCopy.Text(title, body ?: ""), code)
    }
}
