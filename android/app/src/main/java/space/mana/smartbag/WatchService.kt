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
        val n = Notify.ongoing(this, shop.statusLine())
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                startForeground(Notify.ONGOING, n, type)
            } else startForeground(Notify.ONGOING, n)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        shop.location.start()
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) {}
        }
    }
}
