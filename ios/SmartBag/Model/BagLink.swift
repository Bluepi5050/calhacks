// BagLink.swift: BLE link to the bag over the Nordic UART Service (port of Android BagLink.kt).
// TX (…0003) notifies newline-terminated text lines, reassembled across packets; RX (…0002) takes
// the phone's acknowledgements ("OK +1005 4.69", "OK -1005", "ERR 9999 unknown", "PAID <code>").
//
// Runs on the main queue. With the bluetooth-central background mode and state restoration, iOS keeps
// delivering bag lines with the phone locked, and a pending connect() waits indefinitely for the bag to
// come back into range, so reconnection needs no retry loop.

import Foundation
import CoreBluetooth

final class BagLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let nus = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    static let rxUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")   // phone -> bag
    static let txUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")   // bag -> phone
    private static let restoreId = "space.mana.smartbag.central"

    private let onLine: (String) -> Void
    private let onState: (BagState) -> Void

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var rx: CBCharacteristic?
    private var wantConnected = false
    private var scanning = false
    private var failures = 0
    private var name = "Your bag"
    private var scanTimeout: DispatchWorkItem?
    private var flushTimer: DispatchWorkItem?
    private var inbox: [UInt8] = []
    private var outbox: [UInt8] = []
    private var awaitingWrite = false

    private(set) var state: BagState = .off {
        didSet { if state != oldValue { onState(state) } }
    }

    init(onLine: @escaping (String) -> Void, onState: @escaping (BagState) -> Void) {
        self.onLine = onLine
        self.onState = onState
        super.init()
    }

    /// Creating the manager is what shows the Bluetooth permission prompt, so it happens on the first connect().
    private func ensureCentral() -> CBCentralManager {
        if let c = central { return c }
        let c = CBCentralManager(delegate: self, queue: .main, options: [
            CBCentralManagerOptionRestoreIdentifierKey: Self.restoreId,
            CBCentralManagerOptionShowPowerAlertKey: true,
        ])
        central = c
        return c
    }

    func connect() {
        wantConnected = true
        let c = ensureCentral()
        guard c.state == .poweredOn else { reflect(c.state); return }      // centralManagerDidUpdateState continues
        if let p = peripheral, p.state == .connected || p.state == .connecting { return }
        if let p = c.retrieveConnectedPeripherals(withServices: [Self.nus]).first { attach(p, name: p.name); return }
        startScan(c)
    }

    func disconnect() {
        wantConnected = false
        stopScan()
        if let p = peripheral { central?.cancelPeripheralConnection(p) } else { state = .off }
    }

    func send(_ message: String) {
        outbox.append(contentsOf: Array((message + "\n").utf8))
        if outbox.count > 1200 { outbox.removeFirst(outbox.count - 1200) }
        pump()
    }

    // MARK: scanning

    private func startScan(_ c: CBCentralManager) {
        stopScan()
        failures = 0
        scanning = true
        state = .searching
        c.scanForPeripherals(withServices: [Self.nus], options: nil)
        let t = DispatchWorkItem { [weak self] in
            guard let self, self.scanning else { return }
            self.stopScan()
            self.state = .problem("No bag found nearby. Make sure it's switched on.")
        }
        scanTimeout = t
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: t)
    }

    private func stopScan() {
        scanTimeout?.cancel()
        scanTimeout = nil
        if scanning { scanning = false; central?.stopScan() }
    }

    private func attach(_ p: CBPeripheral, name advertised: String?) {
        peripheral = p
        p.delegate = self
        name = advertised ?? p.name ?? "Your bag"
        state = .connecting(name)
        if p.state == .connected { p.discoverServices([Self.nus]) } else { central?.connect(p, options: nil) }
    }

    private func reflect(_ s: CBManagerState) {
        switch s {
        case .poweredOff: state = .problem("Turn on Bluetooth to connect your bag.")
        case .unauthorized: state = .problem("Allow Bluetooth for Bag It in Settings.")
        case .unsupported: state = .problem("This phone has no Bluetooth.")
        default: break                                                       // unknown / resetting: wait
        }
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        if c.state == .poweredOn {
            guard wantConnected else { return }
            if let p = peripheral { attach(p, name: name) } else { connect() }
        } else {
            rx = nil
            awaitingWrite = false
            scanning = false
            reflect(c.state)
        }
    }

    /// iOS relaunched us in the background for a Bluetooth event: pick the bag back up.
    func centralManager(_ c: CBCentralManager, willRestoreState dict: [String: Any]) {
        guard let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first else { return }
        wantConnected = true
        peripheral = p
        p.delegate = self
        name = p.name ?? name
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard scanning else { return }
        stopScan()
        attach(p, name: advertisementData[CBAdvertisementDataLocalNameKey] as? String)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        guard p == peripheral else { return }
        failures = 0
        p.discoverServices([Self.nus])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        guard p == peripheral else { return }
        failures += 1
        if wantConnected && failures < 5 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8 * Double(failures)) { [weak self] in
                guard let self, self.wantConnected, self.peripheral == p else { return }
                c.connect(p, options: nil)
            }
        } else {
            peripheral = nil
            state = .problem("Couldn't connect to your bag. Tap to retry.")
        }
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        guard p == peripheral else { return }
        rx = nil
        awaitingWrite = false
        if wantConnected {
            state = .connecting(name)
            c.connect(p, options: nil)          // no timeout on iOS: reconnects when the bag is back in range
        } else {
            peripheral = nil
            state = .off
        }
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = p.services?.first(where: { $0.uuid == Self.nus }) else {
            wantConnected = false
            state = .problem("That isn't a Bag It bag.")
            central?.cancelPeripheralConnection(p)
            return
        }
        p.discoverCharacteristics([Self.rxUUID, Self.txUUID], for: svc)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor svc: CBService, error: Error?) {
        let chars = svc.characteristics ?? []
        rx = chars.first { $0.uuid == Self.rxUUID }
        guard let tx = chars.first(where: { $0.uuid == Self.txUUID }) else {
            wantConnected = false
            state = .problem("That isn't a Bag It bag.")
            central?.cancelPeripheralConnection(p)
            return
        }
        p.setNotifyValue(true, for: tx)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == Self.txUUID else { return }
        if error == nil && c.isNotifying {
            state = .connected(name)
            pump()
        } else {
            state = .problem("Couldn't read from your bag. Tap to retry.")
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == Self.txUUID, error == nil, let v = c.value else { return }
        receive(v)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        awaitingWrite = false
        pump()
    }

    func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) { pump() }

    // MARK: text lines

    private func receive(_ bytes: Data) {
        inbox.append(contentsOf: bytes)
        while let i = inbox.firstIndex(of: 0x0A) {
            let line = String(decoding: inbox[..<i], as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            inbox.removeSubrange(...i)
            if !line.isEmpty { onLine(line) }
        }
        flushTimer?.cancel()
        guard !inbox.isEmpty else { return }
        // A final line without "\n" still counts after a short pause.
        let t = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let line = String(decoding: self.inbox, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            self.inbox.removeAll()
            if !line.isEmpty { self.onLine(line) }
        }
        flushTimer = t
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: t)
    }

    private func pump() {
        guard let p = peripheral, p.state == .connected, let c = rx, !outbox.isEmpty else { return }
        if c.properties.contains(.writeWithoutResponse) {
            while !outbox.isEmpty && p.canSendWriteWithoutResponse {
                let n = min(outbox.count, max(20, p.maximumWriteValueLength(for: .withoutResponse)))
                p.writeValue(Data(outbox.prefix(n)), for: c, type: .withoutResponse)
                outbox.removeFirst(n)
            }
        } else if !awaitingWrite {
            let n = min(outbox.count, max(20, p.maximumWriteValueLength(for: .withResponse)))
            awaitingWrite = true
            p.writeValue(Data(outbox.prefix(n)), for: c, type: .withResponse)
            outbox.removeFirst(n)
        }
    }
}
