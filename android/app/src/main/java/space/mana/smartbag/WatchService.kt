package space.mana.smartbag

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the shopping session alive with the screen off, so the phone keeps reporting its location
 * and the bag stays connected. Walking out of the store is what pays, so this has to keep running.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shop = (application as SmartBagApp).shop
        shop.watching = true
        val n = Notify.ongoing(this, shop.statusLine())
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                startForeground(Notify.ONGOING, n, type)
            } else startForeground(Notify.ONGOING, n)
            shop.serviceRunning = true
        } catch (e: Exception) {
            shop.watching = false
            shop.serviceRunning = false
            // Android refused a background restart: say so, because nothing will charge on walk-out until the app is opened.
            Notify.status(this, "Bag It paused. Open the app to keep watching for your walk-out.")
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.getStringExtra("reason") != null && !(application as SmartBagApp).foreground) shop.geoWoken = true
        shop.location.start()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as SmartBagApp).shop.serviceRunning = false
        super.onDestroy()
    }

    companion object {
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, WatchService::class.java)) } catch (_: Exception) {} }
        fun start(ctx: Context, reason: String? = null) {
            val i = Intent(ctx, WatchService::class.java).apply { if (reason != null) putExtra("reason", reason) }
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) {}
        }
    }
}
