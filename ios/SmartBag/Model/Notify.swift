// Notify.swift: local notifications (receipts, store arrivals, bag alerts, tests) and what a tap opens.
// Copy and preferences live in Core/Notifications.swift; ShopModel decides when. Shown by the phone itself: no keys,
// works with the app in the background. If the shopper force-quits Bag It, iOS may not wake it, so nothing here is
// promised after a force-quit.

import Foundation
import UserNotifications

final class Notify: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notify()
    private let center = UNUserNotificationCenter.current()

    func install() { center.delegate = self }

    func post(_ t: NotificationText) {
        let content = UNMutableNotificationContent()
        content.title = t.title
        content.body = t.body
        content.sound = .default
        content.threadIdentifier = t.kind.thread
        content.userInfo = t.info
        if t.kind == .trips || t.kind == .bag { content.interruptionLevel = .timeSensitive }   // shown only if the entitlement exists; else .active
        // Same identifier for the same event: a second post replaces the first, it never stacks a duplicate.
        center.add(UNNotificationRequest(identifier: t.id, content: content, trigger: nil))
    }

    /// The system setting, in words: On / Off / Not asked.
    func status() async -> UNAuthorizationStatus { await center.notificationSettings().authorizationStatus }

    func requestPermission() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    // App open: the receipt, store and bag are already on screen, so no banner or sound; keep it in Notification Center.
    // Server pushes (another phone's trip, account security) do show a banner: the screen doesn't know about them.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let remote = notification.request.trigger is UNPushNotificationTrigger
        completionHandler(remote ? [.banner, .list, .sound] : [.list])
    }

    /// Tap: a trip or payment-due notification opens that receipt in Trips.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let type = info["type"] as? String ?? ""
        let code = info["code"] as? String
        Task { @MainActor in
            AppRouter.shared.open(type: type, code: code)
            completionHandler()
        }
    }
}
