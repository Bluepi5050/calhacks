// KeychainTokenStore.swift: the shopper's SpacetimeDB identity token, kept in the Keychain.
// AfterFirstUnlock so the app can reconnect while the phone is locked in a pocket.

import Foundation
import Security

final class KeychainTokenStore: TokenStore, @unchecked Sendable {
    private let service = "space.mana.smartbag"
    private let account = "stdb_token"
    private let lock = NSLock()

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    var token: String? {
        get {
            lock.lock(); defer { lock.unlock() }
            var q = query
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: CFTypeRef?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
            return String(data: d, encoding: .utf8)
        }
        set {
            lock.lock(); defer { lock.unlock() }
            SecItemDelete(query as CFDictionary)
            guard let v = newValue, let d = v.data(using: .utf8) else { return }
            var q = query
            q[kSecValueData as String] = d
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(q as CFDictionary, nil)
        }
    }
}
