package space.mana.smartbag

import android.app.Application

class SmartBagApp : Application() {
    val shop: ShopModel by lazy { ShopModel(this) }
    @Volatile var foreground = false        // MainActivity is resumed (kept here so the Welcome screen doesn't start the model)
    override fun onCreate() {
        super.onCreate()
        Notify.channels(this)
    }
}
