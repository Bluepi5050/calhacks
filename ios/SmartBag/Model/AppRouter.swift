// AppRouter.swift: where a notification tap lands. Trips and payment-due notifications open that receipt in Trips
// (found by its exit code once the list has loaded); store and bag notifications open Shop.

import Foundation
import Combine

@MainActor
final class AppRouter: ObservableObject {
    static let shared = AppRouter()
    @Published var tab = 0                      // 0 Shop, 1 Trips, 2 Wallet, 3 Account
    @Published var pendingCode: String?         // receipt to open in Trips

    func open(type: String, code: String?) {
        switch type {
        case "trip", "payment_due":
            // The live receipt is already covering Shop for this trip: stay there instead of opening it twice.
            if let code, ShopModel.shared.paid?.code == code { tab = 0; return }
            tab = 1
            pendingCode = (code?.isEmpty == false) ? code : nil
        case "store", "bag":
            tab = 0
        default:
            break
        }
    }
}
