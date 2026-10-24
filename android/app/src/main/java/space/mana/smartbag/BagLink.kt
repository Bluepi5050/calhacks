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

sealed class BagState {
    object Off : BagState()
    object Searching : BagState()
    data class Connecting(val name: String) : BagState()
    data class Connected(val name: String) : BagState()
    data class Problem(val message: String) : BagState()
}

/** BLE link to the bag over the Nordic UART Service: TX notifies text lines, RX takes acknowledgements. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BagLink(
    private val ctx: Context,
    private val onLine: (String) -> Unit,
    private val onState: (BagState) -> Unit,
) {
    companion object {
        val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var userStopped = true
    private var retries = 0
    private var lastName = "Your bag"
    private val buffer = StringBuilder()
    private val writes = ArrayDeque<ByteArray>()
    private var writing = false

    val isEnabled: Boolean get() = adapter?.isEnabled == true
    val hasBluetooth: Boolean get() = adapter != null

    private fun emit(s: BagState) { main.post { onState(s) } }

    fun connect() {
        val a = adapter
        if (a == null) { emit(BagState.Problem("This phone has no Bluetooth.")); return }
        if (!a.isEnabled) { emit(BagState.Problem("Turn on Bluetooth to connect your bag.")); return }
        close()
        userStopped = false
        retries = 0
        val scanner = a.bluetoothLeScanner
        if (scanner == null) { emit(BagState.Problem("Bluetooth is still starting. Try again.")); return }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        emit(BagState.Searching)
        try { scanner.startScan(filters, settings, scanCb) } catch (e: Exception) {
            scanning = false; emit(BagState.Problem("Couldn't search for your bag.")); return
        }
        main.postDelayed(scanTimeout, 15_000)
    }

    fun disconnect() {
        userStopped = true
        stopScan()
        val g = gatt
        if (g == null) emit(BagState.Off) else g.disconnect()
    }

    fun send(message: String) {
        val bytes = (message + "\n").toByteArray()
        synchronized(writes) {
            var i = 0
            while (i < bytes.size) { writes.addLast(bytes.copyOfRange(i, minOf(i + 20, bytes.size))); i += 20 }
            while (writes.size > 60) writes.removeFirst()
        }
        pump()
    }

    private val scanTimeout = Runnable {
        if (scanning) { stopScan(); emit(BagState.Problem("No bag found nearby. Make sure it's switched on.")) }
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (scanning) {
            scanning = false
            try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        }
    }

    private fun close() {
        stopScan()
        val g = gatt
        gatt = null
        rx = null
        if (g != null) try { g.disconnect(); g.close() } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                stopScan()
                lastName = result.device.name ?: result.scanRecord?.deviceName ?: "Your bag"
                emit(BagState.Connecting(lastName))
                gatt = result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emit(BagState.Problem("Bluetooth search failed ($errorCode)."))
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    retries = 0
                    if (!g.requestMtu(185)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    rx = null
                    synchronized(writes) { writing = false }
                    if (userStopped) {
                        g.close(); gatt = null; emit(BagState.Off)
                    } else if (retries < 4) {
                        retries++
                        emit(BagState.Connecting(lastName))
                        main.postDelayed({ if (!userStopped && gatt === g) g.connect() }, 800L * retries)
                    } else {
                        g.close(); gatt = null
                        emit(BagState.Problem("Lost your bag. Tap to reconnect."))
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (g === gatt) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val svc = g.getService(NUS)
                val tx = svc?.getCharacteristic(TX)
                if (svc == null || tx == null) {
                    emit(BagState.Problem("That device isn't a SmartBag."))
                    userStopped = true
                    g.disconnect()
                    return@post
                }
                rx = svc.getCharacteristic(RX)
                g.setCharacteristicNotification(tx, true)
                val d = tx.getDescriptor(CCCD)
                if (d == null) { emit(BagState.Connected(lastName)); return@post }
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            emit(BagState.Connected(lastName))
            main.post { pump() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            receive(value)
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            receive(v.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(writes) { writing = false }
            main.post { pump() }
        }
    }

    private val flush = Runnable {
        val l = buffer.toString().trim()
        buffer.setLength(0)
        if (l.isNotEmpty()) onLine(l)
    }

    private fun receive(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        main.post {
            buffer.append(text)
            var i = buffer.indexOf("\n")
            while (i >= 0) {
                val line = buffer.substring(0, i).trim()
                buffer.delete(0, i + 1)
                if (line.isNotEmpty()) onLine(line)
                i = buffer.indexOf("\n")
            }
            main.removeCallbacks(flush)
            if (buffer.isNotEmpty()) main.postDelayed(flush, 300)
        }
    }

    private fun pump() {
        val g = gatt ?: return
        val c = rx ?: return
        val chunk = synchronized(writes) {
            if (writing) null else writes.removeFirstOrNull()?.also { writing = true }
        } ?: return
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = chunk
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) { false }
        if (!ok) synchronized(writes) { writing = false }
    }
}
