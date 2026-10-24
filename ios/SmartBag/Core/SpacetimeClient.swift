// SpacetimeClient.swift: SmartBag's link to its SpacetimeDB module (database smartcart-5dt35 on Maincloud).
//
// Pure Foundation, no UIKit, so it compiles and runs on Linux too (see tools/verify-linux.sh).
// The phone keeps ONE WebSocket open, speaking SpacetimeDB's v2 binary protocol (subprotocol
// "v2.bsatn.spacetimedb", compression=None), and calls the module's procedures over it,
// request/response. Every call runs in a database transaction and returns the shopper's fresh,
// priced bag (CartState). No HTTP per action and no subscriptions: this mirrors the Android
// client (Api.kt) byte for byte.

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum SmartBagConfig {
    public static let host = "https://maincloud.spacetimedb.com"
    public static let database = "smartcart-5dt35"
    public static let subprotocol = "v2.bsatn.spacetimedb"
    public static let demoPayment = "Demo Visa •••• 4242"
}

// MARK: - Errors

public enum SmartBagError: Error, LocalizedError, Equatable {
    case unavailable(String)      // identity / token endpoints failed
    case connectionLost(String)   // socket closed or failed while a call was in flight
    case timeout                  // no ProcedureResult within the call timeout
    case server(String)           // the procedure returned InternalError
    case decode(String)           // malformed BSATN

    public var errorDescription: String? {
        switch self {
        case .unavailable(let m), .connectionLost(let m): return m
        case .timeout: return "SmartBag didn't answer in time."
        case .server(let m): return String(m.prefix(160))
        case .decode(let m): return "Unexpected reply from SmartBag (\(m))."
        }
    }
}

// MARK: - BSATN (little-endian; strings and byte arrays carry a u32 length prefix)

public final class BsatnWriter {
    public private(set) var data = Data()
    public init() {}
    @discardableResult public func u8(_ v: UInt8) -> BsatnWriter { data.append(v); return self }
    @discardableResult public func bool(_ v: Bool) -> BsatnWriter { u8(v ? 1 : 0) }
    @discardableResult public func u32(_ v: UInt32) -> BsatnWriter { le(v); return self }
    @discardableResult public func u64(_ v: UInt64) -> BsatnWriter { le(v); return self }
    @discardableResult public func i64(_ v: Int64) -> BsatnWriter { le(v); return self }
    @discardableResult public func f64(_ v: Double) -> BsatnWriter { le(v.bitPattern); return self }
    @discardableResult public func string(_ s: String) -> BsatnWriter { bytes(Data(s.utf8)) }
    @discardableResult public func bytes(_ b: Data) -> BsatnWriter { u32(UInt32(b.count)); data.append(b); return self }
    private func le<T: FixedWidthInteger>(_ v: T) {
        withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) }
    }
}

public struct BsatnReader {
    private let b: [UInt8]
    public private(set) var offset: Int
    public init(_ data: Data, offset: Int = 0) { b = [UInt8](data); self.offset = offset }
    public var remaining: Int { b.count - offset }

    private mutating func take(_ n: Int) throws -> ArraySlice<UInt8> {
        guard n >= 0, offset + n <= b.count else {
            throw SmartBagError.decode("needed \(n) bytes at \(offset), have \(b.count - offset)")
        }
        defer { offset += n }
        return b[offset ..< offset + n]
    }
    private mutating func le<T: FixedWidthInteger>(_: T.Type) throws -> T {
        var v: T = 0
        for (i, byte) in try take(MemoryLayout<T>.size).enumerated() { v |= T(truncatingIfNeeded: byte) << (8 * i) }
        return v
    }
    public mutating func u8() throws -> UInt8 { try take(1).first! }
    public mutating func bool() throws -> Bool { try u8() != 0 }
    public mutating func u32() throws -> UInt32 { try le(UInt32.self) }
    public mutating func u64() throws -> UInt64 { try le(UInt64.self) }
    public mutating func i64() throws -> Int64 { try le(Int64.self) }
    public mutating func f64() throws -> Double { Double(bitPattern: try u64()) }
    public mutating func bytes() throws -> Data { Data(try take(Int(try u32()))) }
    public mutating func string() throws -> String { String(decoding: try take(Int(try u32())), as: UTF8.self) }
    public mutating func array<T>(_ element: (inout BsatnReader) throws -> T) throws -> [T] {
        let n = Int(try u32())
        var out: [T] = []
        out.reserveCapacity(min(n, 1024))
        for _ in 0 ..< n { out.append(try element(&self)) }
        return out
    }
}

// MARK: - Module types (field order is the module's CartState / Receipt definition)

public struct CartLine: Equatable, Hashable, Sendable {
    public let sku: String, name: String
    public let qty: Int, priceCents: Int, lineCents: Int
    public let sold: Bool
    public let addedMicros: Int64
}

public struct ReceiptLine: Equatable, Hashable, Sendable {
    public let sku: String, name: String
    public let priceCents: Int, qty: Int
}

public struct Receipt: Equatable, Sendable, Identifiable {
    public let orderId: UInt64
    public let code: String, storeName: String
    public let totalCents: Int, itemCount: Int, unpricedCount: Int
    public let status: String, payment: String
    public let paidMicros: Int64
    public let lines: [ReceiptLine]
    public var id: UInt64 { orderId }
    public var isPaid: Bool { status == "paid" }
    public var paidAt: Date { Date(timeIntervalSince1970: Double(paidMicros) / 1_000_000) }
    /// Payload of the exit-pass QR code, identical to Android.
    public var exitPass: String { "SMARTBAG-EXIT:\(code):\(orderId):\(totalCents)" }
}

public struct CartState: Equatable, Sendable {
    public let storeId: String, storeName: String, storeAddress: String
    public let distanceM: Int
    public let nearestName: String
    public let nearestM: Int
    public let bag: String, payment: String
    public let leaving: Bool
    public let totalCents: Int, itemCount: Int
    public let lines: [CartLine]
    /// The shopper's latest walk-out order; nil when the module reports orderId 0 (none yet).
    public let lastReceipt: Receipt?

    public static func decode(_ data: Data) throws -> CartState {
        var r = BsatnReader(data)
        let s = CartState(
            storeId: try r.string(), storeName: try r.string(), storeAddress: try r.string(),
            distanceM: Int(try r.u32()), nearestName: try r.string(), nearestM: Int(try r.u32()),
            bag: try r.string(), payment: try r.string(), leaving: try r.bool(),
            totalCents: Int(try r.u32()), itemCount: Int(try r.u32()),
            lines: try r.array { r in
                CartLine(sku: try r.string(), name: try r.string(), qty: Int(try r.u32()), priceCents: Int(try r.u32()),
                         lineCents: Int(try r.u32()), sold: try r.bool(), addedMicros: try r.i64())
            },
            lastReceipt: try Self.receipt(&r))
        if r.remaining != 0 { throw SmartBagError.decode("\(r.remaining) trailing bytes") }
        return s
    }

    static func receipt(_ r: inout BsatnReader) throws -> Receipt? {
        let id = try r.u64(), code = try r.string(), store = try r.string()
        let total = Int(try r.u32()), count = Int(try r.u32()), unpriced = Int(try r.u32())
        let status = try r.string(), payment = try r.string(), paid = try r.i64()
        let items = try r.array { r in
            ReceiptLine(sku: try r.string(), name: try r.string(), priceCents: Int(try r.u32()), qty: Int(try r.u32()))
        }
        if id == 0 { return nil }
        return Receipt(orderId: id, code: code, storeName: store, totalCents: total, itemCount: count, unpricedCount: unpriced,
                       status: status, payment: payment, paidMicros: paid, lines: items)
    }
}

// MARK: - Wire frames

public enum StdbFrames {
    /// ClientMessage tag 4 = CallProcedure { requestId u32, flags u8, procedure string, args bytes }.
    public static func callProcedure(requestId: UInt32, name: String, args: Data) -> Data {
        BsatnWriter().u8(4).u32(requestId).u8(0).string(name).bytes(args).data
    }

    public enum Outcome: Equatable { case returned(Data), internalError(String) }
    public struct ProcedureResult: Equatable { public let requestId: UInt32; public let outcome: Outcome }

    /// Server frames are [compression=0][ServerMessage]. Only tag 7 (ProcedureResult
    /// { status, timestamp i64, duration i64, requestId u32 }) matters to the phone; everything else
    /// (the initial identity message, etc.) returns nil.
    public static func parseServer(_ frame: Data) throws -> ProcedureResult? {
        guard frame.count >= 2 else { return nil }
        var r = BsatnReader(frame)
        guard try r.u8() == 0, try r.u8() == 7 else { return nil }
        let status = try r.u8()
        let outcome: Outcome
        switch status {
        case 0: outcome = .returned(try r.bytes())
        case 1: outcome = .internalError(try r.string())
        default: throw SmartBagError.decode("procedure status \(status)")
        }
        _ = try r.i64(); _ = try r.i64()
        return ProcedureResult(requestId: try r.u32(), outcome: outcome)
    }
}

// MARK: - Pluggable transport

public protocol WebSocketConnection: AnyObject, Sendable {
    func send(_ data: Data) async throws
    func close()
}

public protocol WebSocketTransport: Sendable {
    /// Opens a socket and returns once the handshake has completed. `onMessage` receives binary frames;
    /// `onClose` is called exactly once when the socket ends for any reason after opening.
    func connect(url: URL, protocols: [String],
                 onMessage: @escaping @Sendable (Data) -> Void,
                 onClose: @escaping @Sendable (String) -> Void) async throws -> WebSocketConnection
}

/// URLSessionWebSocketTask transport (iOS, macOS; also swift-corelibs-foundation on Linux).
public struct URLSessionWebSocketTransport: WebSocketTransport {
    public var openTimeout: TimeInterval = 15
    public var pingInterval: TimeInterval = 20
    public init() {}
    public func connect(url: URL, protocols: [String],
                        onMessage: @escaping @Sendable (Data) -> Void,
                        onClose: @escaping @Sendable (String) -> Void) async throws -> WebSocketConnection {
        let s = URLSessionSocket(onMessage: onMessage, onClose: onClose, pingInterval: pingInterval)
        try await s.open(url: url, protocols: protocols, timeout: openTimeout)
        return s
    }
}

final class URLSessionSocket: NSObject, URLSessionWebSocketDelegate, WebSocketConnection, @unchecked Sendable {
    private let lock = NSLock()
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var opening: CheckedContinuation<Void, Error>?
    private var opened = false
    private var finished = false
    private let onMessage: @Sendable (Data) -> Void
    private let onClose: @Sendable (String) -> Void
    private let pingInterval: TimeInterval

    init(onMessage: @escaping @Sendable (Data) -> Void, onClose: @escaping @Sendable (String) -> Void, pingInterval: TimeInterval) {
        self.onMessage = onMessage
        self.onClose = onClose
        self.pingInterval = pingInterval
    }

    private func locked<T>(_ f: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return f() }

    func open(url: URL, protocols: [String], timeout: TimeInterval) async throws {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeout
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)   // retains self until invalidated
        let task = session.webSocketTask(with: url, protocols: protocols)
        task.maximumMessageSize = 16 << 20
        locked { self.session = session; self.task = task }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            locked { opening = c }
            task.resume()
            receive()
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { [weak self] in
                self?.finish("Can't reach SmartBag.")
            }
        }
        schedulePing()
    }

    private func receive() {
        guard let task = locked({ task }) else { return }
        Task { [weak self] in
            while true {
                do {
                    let message = try await task.receive()
                    guard let self else { return }
                    if case .data(let d) = message { self.onMessage(d) }   // text frames are not used by the v2 binary protocol
                } catch {
                    if ProcessInfo.processInfo.environment["SMARTBAG_DEBUG"] != nil { print("ws receive error: \(error)") }
                    self?.finish("Connection lost.")
                    return
                }
            }
        }
    }

    private func schedulePing() {
        DispatchQueue.global().asyncAfter(deadline: .now() + pingInterval) { [weak self] in
            guard let self, let task = self.locked({ self.finished ? nil : self.task }) else { return }
            task.sendPing { [weak self] error in
                if error != nil { self?.finish("Connection lost.") } else { self?.schedulePing() }
            }
        }
    }

    func send(_ data: Data) async throws {
        guard let task = locked({ finished ? nil : task }) else { throw SmartBagError.connectionLost("Connection lost.") }
        do { try await task.send(.data(data)) } catch {
            throw SmartBagError.connectionLost(error.localizedDescription)
        }
    }

    func close() { finish("Closed.", notify: false) }

    /// Ends the socket once: fails a pending open, or reports the close to the client.
    private func finish(_ why: String, notify: Bool = true) {
        let (c, wasOpen, t, s) = locked { () -> (CheckedContinuation<Void, Error>?, Bool, URLSessionWebSocketTask?, URLSession?) in
            if finished { return (nil, false, nil, nil) }
            finished = true
            defer { opening = nil; task = nil; session = nil }
            return (opening, opened, task, session)
        }
        guard t != nil || c != nil else { return }
        t?.cancel(with: .goingAway, reason: nil)
        s?.invalidateAndCancel()
        c?.resume(throwing: SmartBagError.connectionLost(why))
        if wasOpen && notify { onClose(why) }
    }

    // URLSessionWebSocketDelegate
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        let c = locked { () -> CheckedContinuation<Void, Error>? in
            guard !finished else { return nil }
            opened = true
            defer { opening = nil }
            return opening
        }
        c?.resume()
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        finish("Connection closed.")
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if ProcessInfo.processInfo.environment["SMARTBAG_DEBUG"] != nil { print("ws didComplete error: \(String(describing: error))") }
        finish(error == nil ? "Connection closed." : "Connection lost.")
    }
}

// MARK: - Identity token storage

public protocol TokenStore: AnyObject, Sendable {
    var token: String? { get set }
}

public final class MemoryTokenStore: TokenStore, @unchecked Sendable {
    public var token: String?
    public init(_ token: String? = nil) { self.token = token }
}

public final class UserDefaultsTokenStore: TokenStore, @unchecked Sendable {
    private let key: String
    public init(key: String = "stdb_token") { self.key = key }
    public var token: String? {
        get { UserDefaults.standard.string(forKey: key) }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }
}

// MARK: - The client

/// One persistent WebSocket, request/response procedure calls. Safe to call from anywhere;
/// the app additionally serialises its own calls so bag lines reach the database strictly in order.
public actor SpacetimeClient {
    public let host: String
    public let database: String
    public var callTimeout: TimeInterval = 20
    private let tokens: TokenStore
    private let transport: WebSocketTransport

    private var conn: WebSocketConnection?
    private var connGen = 0
    private var connecting: Task<WebSocketConnection, Error>?
    private var nextId: UInt32 = 1
    private var pending: [UInt32: CheckedContinuation<Data, Error>] = [:]

    public init(host: String = SmartBagConfig.host, database: String = SmartBagConfig.database,
                tokens: TokenStore = UserDefaultsTokenStore(), transport: WebSocketTransport = URLSessionWebSocketTransport()) {
        self.host = host.hasSuffix("/") ? String(host.dropLast()) : host
        self.database = database
        self.tokens = tokens
        self.transport = transport
    }

    // Procedures. Each returns the shopper's full, priced cart.
    public func state() async throws -> CartState { try await cart("shop_state", Data()) }
    /// A location fix. The database places the phone in a store or, if it has just left one, charges the bag.
    public func locate(lat: Double, lng: Double, accuracy: Double) async throws -> CartState {
        try await cart("shop_locate", BsatnWriter().f64(lat).f64(lng).f64(accuracy).data)
    }
    public func bagLine(_ line: String) async throws -> CartState { try await cart("shop_bag_line", Self.str(line)) }
    public func remove(sku: String) async throws -> CartState { try await cart("shop_remove", Self.str(sku)) }
    public func setBag(_ name: String) async throws -> CartState { try await cart("shop_set_bag", Self.str(name)) }
    public func setPayment(_ label: String) async throws -> CartState { try await cart("shop_set_payment", Self.str(label)) }

    private static func str(_ s: String) -> Data { BsatnWriter().string(s).data }
    private func cart(_ name: String, _ args: Data) async throws -> CartState { try CartState.decode(try await call(name, args)) }

    /// Closes the socket; the next call reconnects.
    public func disconnect() {
        if let c = conn { c.close() }
        lost(gen: connGen, why: "Disconnected.")
    }

    // MARK: call plumbing

    private struct SendFailed: Error { let why: String }

    public func call(_ name: String, _ args: Data) async throws -> Data {
        do {
            return try await attempt(name, args)
        } catch let e as SendFailed {
            // The frame never left the phone (typically a socket the OS closed while the app was suspended):
            // safe to retry once on a fresh connection.
            _ = e
            return try await attempt(name, args)
        }
    }

    private func attempt(_ name: String, _ args: Data) async throws -> Data {
        let c = try await connected()
        let gen = connGen
        let id = nextId
        nextId = nextId &+ 1 == 0 ? 1 : nextId &+ 1
        let frame = StdbFrames.callProcedure(requestId: id, name: name, args: args)
        let timeout = callTimeout
        return try await withCheckedThrowingContinuation { (k: CheckedContinuation<Data, Error>) in
            pending[id] = k       // registered before the frame is sent, so the reply can't race past it
            Task {
                do { try await c.send(frame) } catch {
                    self.lost(gen: gen, why: "Connection lost.", keep: id)
                    self.resolve(id, .failure(SendFailed(why: "\(error)")))
                }
            }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                self.resolve(id, .failure(SmartBagError.timeout))
            }
        }
    }

    private func resolve(_ id: UInt32, _ r: Result<Data, Error>) {
        guard let k = pending.removeValue(forKey: id) else { return }
        k.resume(with: r)
    }

    private func connected() async throws -> WebSocketConnection {
        if let c = conn { return c }
        if let t = connecting { return try await t.value }
        let t = Task { try await self.openSocket() }
        connecting = t
        defer { connecting = nil }
        return try await t.value
    }

    private func openSocket() async throws -> WebSocketConnection {
        let wsToken = try await socketToken()
        let wsHost = host.replacingOccurrences(of: "https://", with: "wss://").replacingOccurrences(of: "http://", with: "ws://")
        var comps = URLComponents(string: "\(wsHost)/v1/database/\(database)/subscribe")!
        comps.queryItems = [URLQueryItem(name: "compression", value: "None"), URLQueryItem(name: "token", value: wsToken)]
        connGen += 1
        let gen = connGen
        let c = try await transport.connect(url: comps.url!, protocols: [SmartBagConfig.subprotocol],
            onMessage: { [weak self] d in Task { await self?.onFrame(d) } },
            onClose: { [weak self] why in Task { await self?.lost(gen: gen, why: why) } })
        if gen == connGen { conn = c }
        return c
    }

    private func onFrame(_ f: Data) {
        guard let r = try? StdbFrames.parseServer(f) else { return }
        switch r.outcome {
        case .returned(let d): resolve(r.requestId, .success(d))
        case .internalError(let m): resolve(r.requestId, .failure(SmartBagError.server(m)))
        }
    }

    /// The socket of generation `gen` ended: forget it and fail everything still waiting on it.
    private func lost(gen: Int, why: String, keep: UInt32? = nil) {
        guard gen == connGen else { return }
        conn?.close()
        conn = nil
        connGen += 1
        for id in Array(pending.keys) where id != keep { resolve(id, .failure(SmartBagError.connectionLost(why))) }
    }

    // MARK: identity

    private func identityToken() async throws -> String {
        if let t = tokens.token, !t.isEmpty { return t }
        let (code, body) = try await post("/v1/identity", bearer: nil)
        guard (200 ..< 300).contains(code), let t = Self.jsonToken(body) else {
            throw SmartBagError.unavailable("SmartBag is unavailable right now.")
        }
        tokens.token = t
        return t
    }

    /// Short-lived token for the WebSocket handshake.
    private func socketToken() async throws -> String {
        var (code, body) = try await post("/v1/identity/websocket-token", bearer: try await identityToken())
        if code == 401 {
            tokens.token = nil
            (code, body) = try await post("/v1/identity/websocket-token", bearer: try await identityToken())
        }
        guard (200 ..< 300).contains(code), let t = Self.jsonToken(body) else {
            throw SmartBagError.unavailable("SmartBag is unavailable right now (\(code)).")
        }
        return t
    }

    static func jsonToken(_ d: Data) -> String? {
        (try? JSONSerialization.jsonObject(with: d)) .flatMap { ($0 as? [String: Any])?["token"] as? String }
    }

    private func post(_ path: String, bearer: String?) async throws -> (Int, Data) {
        guard let url = URL(string: host + path) else { throw SmartBagError.unavailable("Bad SmartBag address.") }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.httpMethod = "POST"
        req.httpBody = Data()
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        return try await withCheckedThrowingContinuation { k in
            URLSession.shared.dataTask(with: req) { data, resp, error in
                if error != nil { k.resume(throwing: SmartBagError.unavailable("Can't reach SmartBag.")); return }
                k.resume(returning: ((resp as? HTTPURLResponse)?.statusCode ?? 0, data ?? Data()))
            }.resume()
        }
    }
}
