// Notify.swift: the local "Paid $11.18 at Bayside Grocer" notification after a walk-out.

import Foundation
import UserNotifications

final class Notify: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notify()
    private let center = UNUserNotificationCenter.current()

    func install() { center.delegate = self }

    /// Optional: receipts still show in the app without it.
    func requestPermission() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func paid(_ r: Receipt) {
        let content = UNMutableNotificationContent()
        content.title = r.isPaid ? "Paid \(money(r.totalCents)) at \(r.storeName)" : "\(money(r.totalCents)) due at \(r.storeName)"
        content.body = "\(r.itemCount) item\(r.itemCount == 1 ? "" : "s")"
            + (r.payment.isEmpty ? "" : " · \(r.payment)") + " · exit code \(r.code)"
        content.sound = .default
        content.threadIdentifier = "receipts"
        center.add(UNNotificationRequest(identifier: "paid-\(r.orderId)", content: content, trigger: nil))
    }

    // Show the banner even when the app is open.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }
}
