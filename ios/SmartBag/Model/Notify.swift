// Notify.swift: the local "Paid $11.18 at Bayside Grocer" notification after a walk-out.

import Foundation
import UserNotifications

final class Notify: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notify()
    private let center = UNUserNotificationCenter.current()

    func install() { center.delegate = self }

    /// Optional: receipts still show in the app without it. Asked once, from the onboarding screen.
    func requestPermission() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func paid(_ r: Receipt) {
        let content = UNMutableNotificationContent()
        content.title = r.isPaid ? "Paid \(money(r.totalCents)) as you left \(r.storeName)" : "Payment due: \(money(r.totalCents)) at \(r.storeName)"
        content.body = "\(r.itemCount) item\(r.itemCount == 1 ? "" : "s")"
            + (r.isPaid && !r.payment.isEmpty ? " · \(r.payment)" : "") + " · exit pass \(r.code)"
        content.sound = .default
        content.threadIdentifier = "receipts"
        center.add(UNNotificationRequest(identifier: "paid-\(r.orderId)", content: content, trigger: nil))
    }

    // App open: the receipt is already on screen, so no banner or sound over it; keep it in Notification Center.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.list])
    }
}
