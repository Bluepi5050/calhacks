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
        const val HEARTBEAT_MS = 15_000L
        /** A cart that hasn't answered anything this long after the link came up never will (see [firstAnswer]). */
        const val FIRST_ANSWER_MS = 4_000L
        /** Quick reconnects in a row for that case before the normal backoff takes over. */
        const val QUICK_RETRIES = 6
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
    private val opTimeout = Runnable {
        when {
            ops.current == GattOp.Mtu -> mtuNoAnswer("timed out")
            (ops.current as? GattOp.Read)?.heartbeat == true -> fail("cart stopped answering")
            else -> fail("GATT ${ops.current} timed out")
        }
    }
    // MTU (see MtuPolicy): largest MTU Android reported on this link, whether our request was answered, retried, trusted.
    private var observedMtu = 0
    private var mtuRetried = false
    private var discovered = false
    var mtuTrusted = false; private set
    private var truncations = 0
    // Give a fresh link ~600 ms to settle (and Android 14+'s own MTU exchange to finish) before asking for MTU.
    private val startMtu = Runnable {
        if (gatt == null || ready || ops.current != null) return@Runnable
        // Android already reported a big enough MTU on this link: don't ask (an unanswered request costs 5 s).
        if (observedMtu >= CartProtocol.MIN_MTU) { mtuTrusted = true; log("MTU $observedMtu (from Android)"); ops.add(GattOp.Discover) }
        else ops.add(GattOp.Mtu)
    }

    /** Our MTU request got [answer] (null = none). Decide per MtuPolicy what comes next. */
    private fun afterMtu(answer: Int?) {
        main.removeCallbacks(opTimeout)
        when (MtuPolicy.next(answer, observedMtu, mtuRetried, Build.VERSION.SDK_INT)) {
            MtuPolicy.Next.TOO_SMALL -> { emit(BagState.Problem("This cart can't talk to this phone (MTU $answer).")); userStopped = true; close(); return }
            MtuPolicy.Next.DISCOVER -> { mtuTrusted = true; ops.done(); ops.add(GattOp.Discover) }
            MtuPolicy.Next.RETRY_AFTER_DISCOVERY -> { mtuRetried = true; log("MTU: no answer, asking again after discovery"); ops.done(); ops.add(GattOp.Discover) }
            MtuPolicy.Next.UNCONFIRMED -> { fail("MTU unconfirmed"); return }
            MtuPolicy.Next.PROCEED -> {
                mtuTrusted = MtuPolicy.trusted(maxOf(observedMtu, answer ?: 0))
                log(if (mtuTrusted) "MTU ok (${maxOf(observedMtu, answer ?: 0)})"
                    else "MTU assumed (Android 14+ negotiates it itself); watching for cut-short messages")
                ops.done()
                if (!discovered) ops.add(GattOp.Discover)
            }
        }
    }
    private fun mtuNoAnswer(why: String) { log("MTU request $why"); afterMtu(null) }
    private val ignored = mutableSetOf<String>()                // addresses that turned out to be someone else's cart

    /**
     * While connected and idle, read STATUS every [HEARTBEAT_MS]. A cart that vanished (reset, out of range) never
     * answers, so the 5 s op timeout drops the link in seconds instead of waiting for Android to notice.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (gatt == null || !ready) return
            if (ops.current == null && ops.size == 0) ops.add(GattOp.Read(CartProtocol.STATUS, heartbeat = true))
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /**
     * Any GATT operation that times out, is refused, or reports failure breaks the link: close and reconnect. The
     * contract's reconnect reconcile (START resume, the cart resends, we re-ack) repairs whatever was lost.
     */
    private fun fail(why: String) {
        log("$why: reconnecting")
        close()
        if (!userStopped) scheduleRetry()
    }

    /**
     * The Jetson's Bluetooth chip sometimes loses its "connected" event: the link is up, but the cart's side
     * drops everything we send, and Android would wait ~20 s before giving up. If nothing at all has come back
     * [FIRST_ANSWER_MS] after the link came up, hang up and try again at once (each try has a fresh chance).
     */
    private var heardFromCart = false
    private var quickRetries = 0
    private var retryQuickly = false
    private val firstAnswer = Runnable {
        if (gatt != null && !ready && !heardFromCart) { retryQuickly = true; fail("cart didn't answer") }
    }

    private fun emit(s: BagState) { main.post { onState(s) } }

    /** Look for one of [allowed] (scan by service UUID) and connect. */
    fun connect() {
        val a = adapter ?: run { emit(BagState.Problem("This phone has no Bluetooth.")); return }
        if (!a.isEnabled) { emit(BagState.Problem("Turn on Bluetooth to connect your cart.")); return }
        if (allowed.isEmpty()) return
        userStopped = false
        truncations = 0
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
        userStopped = false; attempts = 0; quickRetries = 0; truncations = 0
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

    /** Backoff 1, 2, 4, 8, 16, then every 30 s, until the shopper or the app stops it; 0.3 s after [firstAnswer]. */
    private fun scheduleRetry() {
        if (userStopped) return
        val quick = retryQuickly && quickRetries < QUICK_RETRIES
        retryQuickly = false
        val delay = if (quick) { quickRetries++; 300L } else { attempts++; minOf(30_000L, 1000L shl minOf(attempts - 1, 5)) }
        log(if (quick) "reconnecting now (quick try $quickRetries of $QUICK_RETRIES)" else "reconnecting in ${delay / 1000} s")
        emit(BagState.Connecting(name, retry = true))
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }

    private fun guessName(addr: String) = "SmartCart-" + addr.replace(":", "").takeLast(4).uppercase()

    /**
     * The scan filter is the cart service, so this is a cart: always give it a SmartCart- name. Prefer the name it
     * advertises now; Android's device.name can be an old cached one (e.g. the Jetson's hostname), which made the
     * app treat the cart as a bag when pairing and as someone else's device when auto-connecting.
     */
    private fun cartName(result: ScanResult): String =
        listOf(result.scanRecord?.deviceName, result.device.name).firstOrNull { it != null && CartProtocol.isCartName(it) }
            ?: guessName(result.device.address)

    private fun close() {
        main.removeCallbacks(opTimeout)
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(startMtu)
        main.removeCallbacks(firstAnswer)
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
                is GattOp.Notify -> {                     // no early returns: the timeout must be cleared below on failure
                    val c = char(g, op.char)
                    val d = c?.getDescriptor(CCCD)
                    if (c == null || d == null) false
                    else {
                        g.setCharacteristicNotification(c, true)
                        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                        else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
                    }
                }
                is GattOp.Read -> char(g, op.char)?.let { g.readCharacteristic(it) } ?: false
                is GattOp.Write -> {
                    val c = char(g, CartProtocol.CONTROL)
                    val bytes = op.text.toByteArray(Charsets.UTF_8)
                    log("→ ${op.text}")
                    if (c == null) false
                    else if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = bytes; g.writeCharacteristic(c) }
                }
            }
        } catch (e: Exception) { log("GATT $op failed: ${e.message}"); false }
        if (!ok && op == GattOp.Mtu) { main.removeCallbacks(opTimeout); main.post { if (g === gatt && ops.current == GattOp.Mtu) mtuNoAnswer("refused") }; return true }
        if (!ok) { main.removeCallbacks(opTimeout); main.post { if (g === gatt) fail("GATT $op refused") } }
        return ok
    }

    private fun opDone() { main.removeCallbacks(opTimeout); ops.done() }

    /** A STATUS read finished: pass it on, unless it was only a [heartbeat]. */
    private fun readDone(value: ByteArray) {
        val beat = (ops.current as? GattOp.Read)?.heartbeat == true
        opDone()
        if (!beat) onStatus(String(value, Charsets.UTF_8))
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                if (result.device.address in ignored) return@post
                // The name may not fit the advertisement: derive it from the address (XXXX = last two MAC bytes) and let
                // STATUS (device_name) confirm it after connecting.
                val n = cartName(result)
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
                val n = cartName(result)
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
                    observedMtu = 0; mtuRetried = false; discovered = false; mtuTrusted = false; heardFromCart = false
                    ops.clear(); main.removeCallbacks(startMtu); main.postDelayed(startMtu, 600)
                    main.removeCallbacks(firstAnswer); main.postDelayed(firstAnswer, FIRST_ANSWER_MS)
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
                log("MTU $mtu" + if (status != BluetoothGatt.GATT_SUCCESS) " (status $status)" else "")
                if (status == BluetoothGatt.GATT_SUCCESS) { observedMtu = maxOf(observedMtu, mtu); heardFromCart = true }
                if (ops.current != GattOp.Mtu) return@post        // Android's own exchange, or a late answer: noted above
                afterMtu(if (status == BluetoothGatt.GATT_SUCCESS) mtu else null)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                opDone()
                if (status == BluetoothGatt.GATT_SUCCESS) heardFromCart = true
                if (g.getService(SERVICE) == null) { emit(BagState.Problem("That device isn't a SmartCart.")); userStopped = true; close(); return@post }
                discovered = true
                if (mtuRetried && !mtuTrusted) {
                    if (observedMtu >= CartProtocol.MIN_MTU) { mtuTrusted = true; log("MTU $observedMtu (from Android)") }
                    else ops.add(GattOp.Mtu)                                // second (last) MTU request, after discovery
                }
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
                    ready = true; attempts = 0; quickRetries = 0
                    emit(BagState.Connected(name))
                    onReady()
                    readStatus()
                    main.removeCallbacks(heartbeat); main.postDelayed(heartbeat, HEARTBEAT_MS)
                }
            }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { if (g === gatt) { if (status != BluetoothGatt.GATT_SUCCESS) fail("STATUS read failed ($status)") else readDone(value) } }
        }
        @Deprecated("Below Android 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value?.copyOf() ?: ByteArray(0)
            main.post { if (g === gatt) { if (status != BluetoothGatt.GATT_SUCCESS) fail("STATUS read failed ($status)") else readDone(v) } }
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
            val parsed = when (uuid) { STATUS -> CartProtocol.status(text) != null; EVENTS -> CartProtocol.event(text) != null; else -> true }
            if (MtuPolicy.truncated(value.size, parsed, mtuTrusted)) {
                // Cut short by a small MTU: this link can't carry the protocol. Reconnect once (MTU is asked again);
                // twice in a row means this phone and cart can't agree, so say so instead of looping.
                if (++truncations >= 2) { emit(BagState.Problem("This cart can't talk to this phone. Turn Bluetooth off and on, then try again.")); userStopped = true; close(); return@post }
                fail("message cut short (MTU too small)"); return@post
            }
            if (parsed) truncations = 0
            when (uuid) { STATUS -> onStatus(text); EVENTS -> onEvent(text) }
        }
    }
}
