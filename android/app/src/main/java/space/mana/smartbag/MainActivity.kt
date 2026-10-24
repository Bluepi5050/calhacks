package space.mana.smartbag

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val model: ShopModel get() = (application as SmartBagApp).shop
    private val prefs by lazy { getSharedPreferences("smartbag", 0) }
    private var perms by mutableStateOf(Perms(false, false, false, false))

    private val signedIn get() = model.account?.signedIn == true

    private val permissionAsk = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean("asked_permissions", true).putInt("ask_count", prefs.getInt("ask_count", 0) + 1).apply()
        refreshPerms()
        if (perms.ready && signedIn) { startLocation(); connectBag() }
    }

    // Notifications are optional (receipts still show in the app), so they are asked for but not required.
    private fun askList(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) permissions() + Manifest.permission.POST_NOTIFICATIONS else permissions()
    // Declined or not: connect() either connects or reports "Turn on Bluetooth…", so the bag never sits in Off.
    private val bluetoothAsk = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (model.pairing != null) model.startPairing() else model.link.connect()
    }

    private fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshPerms() {
        val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions().filter { !has(it) && it != Manifest.permission.ACCESS_COARSE_LOCATION }
        perms = Perms(
            nearby = Build.VERSION.SDK_INT < 31 || (has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)),
            location = fine,
            notifications = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
            asked = prefs.getBoolean("asked_permissions", false),
            approximate = !fine && has(Manifest.permission.ACCESS_COARSE_LOCATION),
            // After two denials Android stops showing the prompt; then only Settings can help.
            // (a dismissed dialog also clears the rationale flag, so allow at least two asks before sending people to Settings)
            canAskAgain = missing.isEmpty() || prefs.getInt("ask_count", 0) < 2 || missing.any { shouldShowRequestPermissionRationale(it) },
        )
    }

    private fun startLocation() {
        if (!model.location.enabled) model.locationUnavailable()
        model.location.start()
        WatchService.start(this)   // keeps reporting with the screen off: walking out is what pays
    }

    private fun connectBag() {
        if (model.pairing != null) return            // the pairing screen owns Bluetooth right now
        if (!model.link.hasBluetooth) { model.link.connect(); return }
        if (!model.link.isEnabled) {
            try { bluetoothAsk.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { model.link.connect() }
        } else model.link.connect()
    }

    private fun appSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) } catch (_: Exception) {}
    }

    private val actions = object : AppActions {
        override fun connectBag() = this@MainActivity.connectBag()
        override fun locationSettings() { try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {} }
        override fun retry() = model.retry()
        override fun remove(sku: String) = model.dismissLine(sku)
        override fun finishReceipt() = model.finishReceipt()
        override fun demoDrop() = model.demoDrop()
        override fun demoWalkOut() = model.demoWalkOut()

        // Start watching right away, but leave Bluetooth alone when the pairing step is next (it owns the radio then).
        private fun afterSignIn(signUp: Boolean, done: (String?, AccountState?) -> Unit): (String?) -> Unit = { err ->
            val a = model.account
            if (err == null && perms.ready) {
                startLocation()
                if (!signUp && a != null && a.bags.isNotEmpty()) connectBag()
            }
            done(err, a)
        }
        override fun signUp(name: String, email: String, password: String, done: (String?, AccountState?) -> Unit) =
            model.accountCall({ Api.signUp(name, email, password) }, afterSignIn(true, done), model.refreshBag())
        override fun signIn(email: String, password: String, done: (String?, AccountState?) -> Unit) =
            model.accountCall({ Api.signIn(email, password) }, afterSignIn(false, done), model.refreshBag())
        override fun signOut(done: (String?) -> Unit) = model.accountCall({ Api.signOut() }, done, model.refreshBag())
        override fun deleteAccount(password: String, done: (String?) -> Unit) = model.accountCall({ Api.deleteAccount(password) }, done, model.refreshBag())
        override fun addCard(c: NewCard, done: (String?) -> Unit) =
            model.accountCall({ Api.cardAdd(c.brand, c.last4, c.expMonth, c.expYear, c.nickname, c.makeDefault) }, done, model.refreshBag())
        override fun setDefaultCard(id: Long, done: (String?) -> Unit) = model.accountCall({ Api.cardSetDefault(id) }, done, model.refreshBag())
        override fun removeCard(id: Long, done: (String?) -> Unit) = model.accountCall({ Api.cardRemove(id) }, done, model.refreshBag())
        override fun renameBag(id: Long, name: String, done: (String?) -> Unit) = model.accountCall({ Api.bagRename(id, name) }, done)
        override fun unpairBag(id: Long, done: (String?) -> Unit) = model.accountCall({ Api.bagUnpair(id) }, done)
        override fun loadTrips() = model.loadTrips()
        override fun askPermissions() = permissionAsk.launch(askList())
        override fun appSettings() = this@MainActivity.appSettings()
        override fun bluetoothOn() { try { bluetoothAsk.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {} }
        override fun startPairing() = model.startPairing()
        override fun probe(b: FoundBag) = model.probe(b)
        override fun confirmProbe() = model.confirmProbe()
        override fun rejectProbe() = model.rejectProbe()
        override fun pair(bleName: String, name: String, done: (String?) -> Unit) = model.pair(bleName, name, done)
        override fun pairDemo(done: (String?) -> Unit) = model.pairDemo(done)
        override fun endPairing() = model.endPairing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPerms()
        setContent {
            BagItTheme { AppRoot(model.appUi(perms), actions) }
        }
        if (perms.ready && signedIn && savedInstanceState == null) { startLocation(); connectBag() }
    }

    override fun onPause() {
        super.onPause()
        (application as SmartBagApp).foreground = false
    }

    override fun onResume() {
        super.onResume()
        (application as SmartBagApp).foreground = true
        val wasReady = perms.ready
        refreshPerms()                         // the user may be back from Settings
        if (perms.ready && signedIn) {
            if (!wasReady) connectBag()
            if (model.location.enabled && model.store is StoreState.NoLocation) model.refreshStore()
            startLocation()
        }
    }
}

/** Snapshot of the model for the UI (reads Compose state, so the screen recomposes when it changes). */
fun ShopModel.ui(): ShopUi {
    val a = account?.takeIf { it.signedIn }
    return ShopUi(bag, store, payment, total, count, lines.toList(), paid, flash, syncError, lastTrip,
        noBag = a != null && a.bags.isEmpty(), noCard = a != null && a.cards.isEmpty())
}

fun ShopModel.appUi(perms: Perms) = AppUi(
    shop = ui(), account = account ?: SIGNED_OUT, perms = perms,
    trips = trips, tripsLoading = tripsLoading, tripsError = tripsError, pairing = pairing,
    connectedBag = (bag as? BagState.Connected)?.name?.takeIf { !demoStandIn }, version = BuildConfig.VERSION_NAME,
    demoActive = demoStandIn,
)
