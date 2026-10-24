package space.mana.smartbag

import android.app.Application

class SmartBagApp : Application() {
    val shop: ShopModel by lazy { ShopModel(this) }
    @Volatile var foreground = false        // MainActivity is resumed (kept here so the Welcome screen doesn't start the model)
    override fun onCreate() {
        super.onCreate()
        Notify.channels(this)
        // Server push only exists in builds made with google-services.json (the fcm source set).
        if (BuildConfig.FCM) try { Class.forName("space.mana.smartbag.fcm.FcmBridge").getMethod("install").invoke(null) } catch (_: Exception) {}
    }
}
