// Cards.swift: local card checks for the Add card form. Pure Foundation (runs on Linux, see tools/verify-linux.sh).
//
// Payments are simulated. The full number and the CVC are checked here and never leave the phone: the only
// thing sent to Bag It is a CardDraft (brand, last four, expiry), plus the shopper's nickname.

import Foundation

public enum CardBrand: String, Sendable, CaseIterable {
    case visa = "Visa", mastercard = "Mastercard", amex = "Amex", discover = "Discover", other = "Card"

    /// From the first digits: 4 Visa; 51-55 and 2221-2720 Mastercard; 34/37 Amex; 6011/65 Discover; else Card.
    public static func detect(_ digits: String) -> CardBrand {
        func prefix(_ n: Int) -> Int? { digits.count >= n ? Int(digits.prefix(n)) : nil }
        if digits.hasPrefix("4") { return .visa }
        if let p = prefix(2), (51 ... 55).contains(p) { return .mastercard }
        if let p = prefix(4), (2221 ... 2720).contains(p) { return .mastercard }
        if let p = prefix(2), p == 34 || p == 37 { return .amex }
        if digits.hasPrefix("6011") || digits.hasPrefix("65") { return .discover }
        return .other
    }

    public var lengths: [Int] {
        switch self {
        case .visa: return [13, 16, 19]
        case .mastercard: return [16]
        case .amex: return [15]
        case .discover: return [16, 17, 18, 19]
        case .other: return Array(12 ... 19)
        }
    }
    public var maxLength: Int { lengths.max() ?? 19 }
    public var cvcLength: Int { self == .amex ? 4 : 3 }
    /// Digit groups: 4-6-5 for Amex, 4-4-4-4(-3) otherwise.
    public var groups: [Int] { self == .amex ? [4, 6, 5] : [4, 4, 4, 4, 3] }
}

/// The only card data that leaves the phone.
public struct CardDraft: Equatable, Sendable {
    public let brand: String
    public let last4: String
    public let expMonth: Int
    public let expYear: Int        // 4-digit
}

public struct CardErrors: Equatable, Sendable, Error {
    public var number: String?
    public var expiry: String?
    public var cvc: String?
    public var isEmpty: Bool { number == nil && expiry == nil && cvc == nil }
}

public enum CardCheck {
    public static func digits(_ s: String) -> String { String(s.filter { $0.isASCII && $0.isNumber }) }

    public static func luhn(_ digits: String) -> Bool {
        guard !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return false }
        var sum = 0
        for (i, ch) in digits.reversed().enumerated() {
            var d = Int(String(ch))!
            if i % 2 == 1 { d *= 2; if d > 9 { d -= 9 } }
            sum += d
        }
        return sum % 10 == 0
    }

    /// Typing aid: keeps digits only, caps the length for the brand, groups them ("4242 4242 4242 4242").
    public static func formatNumber(_ input: String) -> String {
        var d = digits(input)
        let brand = CardBrand.detect(d)
        if d.count > brand.maxLength { d = String(d.prefix(brand.maxLength)) }
        var out = "", i = d.startIndex
        for g in brand.groups where i < d.endIndex {
            let j = d.index(i, offsetBy: g, limitedBy: d.endIndex) ?? d.endIndex
            out += (out.isEmpty ? "" : " ") + d[i ..< j]
            i = j
        }
        return out
    }

    /// Typing aid for MM/YY: "1229" -> "12/29", "4" -> "04/" is not forced; a slash appears after two digits.
    public static func formatExpiry(_ input: String) -> String {
        var d = digits(input)
        if d.count == 1, let m = Int(d), m > 1 { d = "0" + d }            // "4" means April
        d = String(d.prefix(4))
        return d.count > 2 ? d.prefix(2) + "/" + d.dropFirst(2) : d
    }

    public static func formatCVC(_ input: String, brand: CardBrand) -> String { String(digits(input).prefix(brand.cvcLength)) }

    /// (month, 4-digit year) from "MM/YY".
    public static func parseExpiry(_ s: String) -> (month: Int, year: Int)? {
        let d = digits(s)
        guard d.count == 4, let m = Int(d.prefix(2)), let y = Int(d.suffix(2)), (1 ... 12).contains(m) else { return nil }
        return (m, 2000 + y)
    }

    /// Validates everything locally. `now` is (year, month).
    public static func validate(number: String, expiry: String, cvc: String, now: (year: Int, month: Int)) -> Result<CardDraft, CardErrors> {
        var e = CardErrors()
        let d = digits(number)
        let brand = CardBrand.detect(d)
        if d.isEmpty { e.number = "Enter the card number." }
        else if !brand.lengths.contains(d.count) || !luhn(d) { e.number = "Check the card number." }
        let exp = parseExpiry(expiry)
        if digits(expiry).isEmpty { e.expiry = "Enter the expiry date." }
        else if exp == nil { e.expiry = "Enter the expiry as MM/YY." }
        else if let x = exp, x.year < now.year || (x.year == now.year && x.month < now.month) { e.expiry = "This card has expired." }
        else if let x = exp, x.year > now.year + 25 { e.expiry = "Check the expiry date." }
        let c = digits(cvc)
        if c.count != brand.cvcLength { e.cvc = "Enter the \(brand.cvcLength)-digit security code." }
        guard e.isEmpty, let x = exp else { return .failure(e) }
        return .success(CardDraft(brand: brand.rawValue, last4: String(d.suffix(4)), expMonth: x.month, expYear: x.year))
    }

    /// The demo card behind "Use demo card": 4242 4242 4242 4242, 12/29, 123, "Demo".
    public static let demo = (number: "4242 4242 4242 4242", expiry: "12/29", cvc: "123", nickname: "Demo")
}
