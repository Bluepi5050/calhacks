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
        nm.createNotificationChannel(NotificationChannel(CH_SESSION, "Shopping session", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_PAID, "Payments", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("DEPRECATION")
    private fun builder(ctx: Context, ch: String) =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else Notification.Builder(ctx)

    fun ongoing(ctx: Context, text: String): Notification = builder(ctx, CH_SESSION)
        .setSmallIcon(R.drawable.ic_stat_bag).setContentTitle("SmartBag").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(ctx)).build()

    fun status(ctx: Context, text: String) {
        try { ctx.getSystemService(NotificationManager::class.java).notify(ONGOING, ongoing(ctx, text)) } catch (_: Exception) {}
    }

    fun paid(ctx: Context, r: Receipt) {
        val title = if (r.status == "paid") "Paid ${money(r.total)} at ${r.storeName}" else "${money(r.total)} due at ${r.storeName}"
        val text = "${r.count} item" + (if (r.count == 1) "" else "s") + (if (r.payment.isNotEmpty()) " · ${r.payment}" else "") + " · exit code ${r.code}"
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(PAID, builder(ctx, CH_PAID)
                .setSmallIcon(R.drawable.ic_stat_bag).setContentTitle(title).setContentText(text)
                .setAutoCancel(true).setContentIntent(open(ctx)).build())
        } catch (_: Exception) {}
    }
}
