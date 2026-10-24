package space.mana.smartbag

import android.app.Application

class SmartBagApp : Application() {
    val shop: ShopModel by lazy { ShopModel(this) }
    override fun onCreate() {
        super.onCreate()
        Notify.channels(this)
    }
}
