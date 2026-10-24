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

    private val permissionAsk = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean("asked_permissions", true).putInt("ask_count", prefs.getInt("ask_count", 0) + 1).apply()
        refreshPerms()
        if (perms.ready) { startLocation(); connectBag() }
    }

    // Notifications are optional (receipts still show in the app), so they are asked for but not required.
    private fun askList(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) permissions() + Manifest.permission.POST_NOTIFICATIONS else permissions()
    // Declined or not: connect() either connects or reports "Turn on Bluetooth…", so the bag never sits in Off.
    private val bluetoothAsk = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.link.connect() }

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
        if (!model.link.hasBluetooth) { model.link.connect(); return }
        if (!model.link.isEnabled) {
            try { bluetoothAsk.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { model.link.connect() }
        } else model.link.connect()
    }

    private fun appSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) } catch (_: Exception) {}
    }

    private val actions = object : ShopActions {
        override fun connectBag() = this@MainActivity.connectBag()
        override fun locationSettings() { try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {} }
        override fun retry() = model.retry()
        override fun remove(sku: String) = model.dismissLine(sku)
        override fun finishReceipt() = model.finishReceipt()
        override fun demoDrop() = model.demoDrop()
        override fun demoWalkOut() = model.demoWalkOut()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPerms()
        setContent {
            BagItTheme {
                if (!perms.ready) WelcomeScreen(perms, onContinue = { permissionAsk.launch(askList()) }, onSettings = ::appSettings)
                else ShopScreen(model.ui(), actions)
            }
        }
        if (perms.ready && savedInstanceState == null) { startLocation(); connectBag() }
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
        if (perms.ready) {
            if (!wasReady) connectBag()
            if (model.location.enabled && model.store is StoreState.NoLocation) model.refreshStore()
            startLocation()
        }
    }
}

/** Snapshot of the model for the UI (reads Compose state, so the screen recomposes when it changes). */
fun ShopModel.ui() = ShopUi(bag, store, payment, total, count, lines.toList(), paid, flash, syncError, lastTrip)
