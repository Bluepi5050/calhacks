package space.mana.smartbag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Notify {
    const val ONGOING = 1
    private const val PAID = 2
    private const val CH_SESSION = "session"
    private const val CH_PAID = "paid"

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SESSION, "Shopping session", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Your shopping status while Bag It watches for you to leave" })
        nm.createNotificationChannel(NotificationChannel(CH_PAID, "Receipts", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Your receipt when you walk out" })
    }

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("DEPRECATION")
    private fun builder(ctx: Context, ch: String) =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else Notification.Builder(ctx)

    fun ongoing(ctx: Context, text: String): Notification = builder(ctx, CH_SESSION)
        .setSmallIcon(R.drawable.ic_stat_bag).setContentTitle("Bag It").setContentText(text).setColor(0xFF18181B.toInt())
        .setShowWhen(false).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(ctx)).build()

    fun status(ctx: Context, text: String) {
        try { ctx.getSystemService(NotificationManager::class.java).notify(ONGOING, ongoing(ctx, text)) } catch (_: Exception) {}
    }

    /** The in-app receipt is on screen: the notification has done its job. */
    fun cancelPaid(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(PAID) } catch (_: Exception) {}
    }

    fun paid(ctx: Context, r: Receipt) {
        val title = if (r.status == "paid") "Paid ${money(r.total)} as you left ${r.storeName}" else "Payment due: ${money(r.total)} at ${r.storeName}"
        val text = "${r.count} item" + (if (r.count == 1) "" else "s") + (if (r.payment.isNotEmpty()) " · ${card(r.payment)}" else "") + " · Exit code ${r.code}"
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(PAID, builder(ctx, CH_PAID)
                .setSmallIcon(R.drawable.ic_stat_bag).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_STATUS)
                .apply { if (r.status == "paid") setColor(0xFFE8654A.toInt()) }   // coral = money moved; due keeps the system neutral
                .setShowWhen(true).setAutoCancel(true).setContentIntent(open(ctx)).build())
        } catch (_: Exception) {}
    }
}
