package space.mana.smartbag

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE central for the SmartCart (Jetson) service, beside the bag's NUS [BagLink]. Finds carts by service UUID, connects,
 * requests MTU 247 (needs >= 64), discovers, subscribes STATUS then EVENTS (descriptor writes one at a time), reads
 * STATUS, and writes CONTROL with response. Every GATT operation goes through one [GattQueue]. Reconnects with backoff
 * until [disconnect]. Callbacks arrive on the main thread.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class CartLink(
    private val ctx: Context,
    private val onState: (BagState) -> Unit,
    private val onReady: () -> Unit,              // subscribed: the protocol may write now (STATUS read follows)
    private val onStatus: (String) -> Unit,
    private val onEvent: (String) -> Unit,
    private val onDown: () -> Unit,
    private val log: (String) -> Unit,
) {
    companion object {
        val SERVICE: UUID = UUID.fromString(CartProtocol.SERVICE)
        val CONTROL: UUID = UUID.fromString(CartProtocol.CONTROL)
        val EVENTS: UUID = UUID.fromString(CartProtocol.EVENTS)
        val STATUS: UUID = UUID.fromString(CartProtocol.STATUS)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    var name: String = "Your cart"; private set
    private var userStopped = true
    private var attempts = 0
    private var scanning = false
    private var pairScanning = false
    private var onFound: ((List<FoundBag>) -> Unit)? = null
    private val found = LinkedHashMap<String, FoundBag>()
    var ready = false; private set
    /** BLE names of the account's paired carts; auto-connect only to these. */
    @Volatile var allowed: Set<String> = emptySet()

    private val ops = GattQueue(::exec, log)
    private val opTimeout = Runnable { fail("GATT ${ops.current} timed out") }
    private val ignored = mutableSetOf<String>()                // addresses that turned out to be someone else's cart

    /**
     * Any GATT operation that times out, is refused, or reports failure breaks the link: close and reconnect. The
     * contract's reconnect reconcile (START resume, the cart resends, we re-ack) repairs whatever was lost.
     */
    private fun fail(why: String) {
        log("$why: reconnecting")
        close()
        if (!userStopped) scheduleRetry()
    }

    private fun emit(s: BagState) { main.post { onState(s) } }

    /** Look for one of [allowed] (scan by service UUID) and connect. */
    fun connect() {
        val a = adapter ?: run { emit(BagState.Problem("This phone has no Bluetooth.")); return }
        if (!a.isEnabled) { emit(BagState.Problem("Turn on Bluetooth to connect your cart.")); return }
        if (allowed.isEmpty()) return
        userStopped = false
        if (gatt != null) return                               // already connected / connecting
        main.removeCallbacks(retry)
        startScan()
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        scanning = true
        emit(BagState.Searching)
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
            main.postDelayed(scanTimeout, 15_000)
        } catch (e: Exception) { scanning = false; emit(BagState.Problem("Couldn't search for your cart.")) }
    }

    private val scanTimeout = Runnable {
        if (scanning) { stopScan(); emit(BagState.Problem("Your cart isn't nearby. Make sure it's switched on.")); scheduleRetry() }
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (scanning) { scanning = false; try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {} }
    }

    /** After a final write (STOP): stop reconnecting now, let the queued write go out, then disconnect (max 1.5 s). */
    fun disconnectSoon() {
        userStopped = true
        main.removeCallbacks(retry)
        main.postDelayed({ if (userStopped) disconnect() }, 1500)
    }

    /** STATUS showed this isn't one of the account's carts (nameless advertisement): skip it and keep looking. */
    fun reject() {
        address?.let { ignored += it }
        log("not one of your carts: skipping it")
        close(); address = null
        if (!userStopped) scheduleRetry()
    }

    fun disconnect() {
        userStopped = true
        main.removeCallbacks(retry)
        stopScan(); stopPairScan()
        close()
        emit(BagState.Off)
    }

    /** Pairing list: carts in range (names come from the advertisement). */
    fun scanForPairing(onUpdate: (List<FoundBag>) -> Unit): String? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        stopPairScan()
        found.clear(); onFound = onUpdate; pairScanning = true
        return try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), pairCb); null
        } catch (e: SecurityException) { pairScanning = false; "denied" } catch (e: Exception) { pairScanning = false; null }
    }

    fun stopPairScan() {
        if (!pairScanning) return
        pairScanning = false; onFound = null
        try { adapter?.bluetoothLeScanner?.stopScan(pairCb) } catch (_: Exception) {}
    }

    /** Connect to one cart (pairing, or a known address). */
    fun connectTo(addr: String, bleName: String) {
        stopPairScan(); stopScan(); close()
        userStopped = false; attempts = 0
        address = addr; name = bleName
        open()
    }

    private fun open() {
        if (gatt != null) return                               // never two clients to the cart
        val a = adapter ?: return
        val addr = address ?: return startScan()
        emit(if (attempts == 0) BagState.Connecting(name) else BagState.Connecting(name, retry = true))
        try { gatt = a.getRemoteDevice(addr).connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE) }
        catch (e: Exception) { log("connect failed: ${e.message}"); scheduleRetry() }
    }

    private val retry = Runnable { if (!userStopped) { if (address != null) open() else startScan() } }

    /** Backoff 1, 2, 4, 8, 16, then every 30 s, until the shopper or the app stops it. */
    private fun scheduleRetry() {
        if (userStopped) return
        attempts++
        val delay = minOf(30_000L, 1000L shl minOf(attempts - 1, 5))
        log("reconnecting in ${delay / 1000} s")
        emit(BagState.Connecting(name, retry = true))
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }

    private fun guessName(addr: String) = "SmartCart-" + addr.replace(":", "").takeLast(4).uppercase()

    private fun close() {
        main.removeCallbacks(opTimeout)
        ops.clear()
        val g = gatt
        gatt = null
        val was = ready
        ready = false
        if (g != null) try { g.disconnect(); g.close() } catch (_: Exception) {}
        if (was) onDown()
    }

    /** CONTROL write (with response), queued behind any GATT operation in flight. */
    fun write(text: String) {
        if (text.toByteArray().size > CartProtocol.MAX_MSG) { log("message too long, not sent: $text"); return }
        if (gatt == null || !ready) { log("not connected, not sent: $text"); return }
        ops.add(GattOp.Write(text))
    }

    fun readStatus() { if (gatt != null && ready) ops.add(GattOp.Read(CartProtocol.STATUS)) }

    private fun char(g: BluetoothGatt, uuid: String) = g.getService(SERVICE)?.getCharacteristic(UUID.fromString(uuid))

    private fun exec(op: GattOp): Boolean {
        val g = gatt ?: return false
        main.removeCallbacks(opTimeout)
        main.postDelayed(opTimeout, 5_000)
        val ok = try {
            when (op) {
                GattOp.Mtu -> g.requestMtu(CartProtocol.MTU)
                GattOp.Discover -> g.discoverServices()
                is GattOp.Notify -> {
                    val c = char(g, op.char) ?: return false
                    g.setCharacteristicNotification(c, true)
                    val d = c.getDescriptor(CCCD) ?: return false
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
                }
                is GattOp.Read -> char(g, op.char)?.let { g.readCharacteristic(it) } ?: false
                is GattOp.Write -> {
                    val c = char(g, CartProtocol.CONTROL) ?: return false
                    val bytes = op.text.toByteArray(Charsets.UTF_8)
                    log("→ ${op.text}")
                    if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = bytes; g.writeCharacteristic(c) }
                }
            }
        } catch (e: Exception) { log("GATT $op failed: ${e.message}"); false }
        if (!ok) { main.removeCallbacks(opTimeout); main.post { if (g === gatt) fail("GATT $op refused") } }
        return ok
    }

    private fun opDone() { main.removeCallbacks(opTimeout); ops.done() }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                if (result.device.address in ignored) return@post
                // The name may not fit the advertisement: derive it from the address (XXXX = last two MAC bytes) and let
                // STATUS (device_name) confirm it after connecting.
                val n = (result.device.name ?: result.scanRecord?.deviceName).orEmpty().ifEmpty { guessName(result.device.address) }
                if (n !in allowed) return@post                       // someone else's cart
                stopScan()
                address = result.device.address; name = n; attempts = 0
                open()
            }
        }
        override fun onScanFailed(errorCode: Int) { main.post { scanning = false; emit(BagState.Problem("Couldn't search for your cart.")); scheduleRetry() } }
    }

    private val pairCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!pairScanning) return@post
                val n = (result.device.name ?: result.scanRecord?.deviceName).orEmpty().ifEmpty { guessName(result.device.address) }
                found[result.device.address] = FoundBag(result.device.address, n, result.rssi, System.currentTimeMillis())
                onFound?.invoke(found.values.toList())
            }
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("GATT connected")
                    ops.clear(); ops.add(GattOp.Mtu)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("GATT disconnected ($status)")
                    close()
                    if (userStopped) emit(BagState.Off) else scheduleRetry()
                }
            }
        }
        // v1.2: a "service changed" indication means the cart's GATT table changed (e.g. it restarted): treat as a disconnect.
        override fun onServiceChanged(g: BluetoothGatt) { main.post { if (g === gatt) fail("service changed") } }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g !== gatt) return@post
                log("MTU $mtu")
                if (mtu < CartProtocol.MIN_MTU) {
                    emit(BagState.Problem("This cart can't talk to this phone (MTU $mtu)."))
                    userStopped = true; close(); return@post
                }
                opDone(); ops.add(GattOp.Discover)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                opDone()
                if (g.getService(SERVICE) == null) { emit(BagState.Problem("That device isn't a SmartCart.")); userStopped = true; close(); return@post }
                ops.add(GattOp.Notify(CartProtocol.STATUS)); ops.add(GattOp.Notify(CartProtocol.EVENTS))
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("subscribe failed ($status)"); return@post }
                val wasEvents = d.characteristic.uuid == EVENTS
                opDone()
                if (wasEvents) {
                    ready = true; attempts = 0
                    emit(BagState.Connected(name))
                    onReady()
                    readStatus()
                }
            }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { if (g === gatt) { if (status != BluetoothGatt.GATT_SUCCESS) fail("STATUS read failed ($status)") else { opDone(); onStatus(String(value, Charsets.UTF_8)) } } }
        }
        @Deprecated("Below Android 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value?.copyOf() ?: ByteArray(0)
            main.post { if (g === gatt) { if (status != BluetoothGatt.GATT_SUCCESS) fail("STATUS read failed ($status)") else { opDone(); onStatus(String(v, Charsets.UTF_8)) } } }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { if (g === gatt) { if (status != BluetoothGatt.GATT_SUCCESS) fail("write failed ($status)") else opDone() } }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { deliver(g, c.uuid, value) }
        @Deprecated("Below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { deliver(g, c.uuid, c.value?.copyOf() ?: return) }
    }

    private fun deliver(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        val text = String(value, Charsets.UTF_8)
        main.post {
            if (g !== gatt) return@post
            when (uuid) { STATUS -> onStatus(text); EVENTS -> onEvent(text) }
        }
    }
}
