import Foundation

/// What the buyer is charged: the session's price, or the offer in their own currency when they chose it.
struct Charge: Equatable {
  var amountMinor: Int
  var currency: String
  /// The sealed local-currency offer when the buyer pays in their own currency.
  var localOffer: String?
}

func chargeOf(_ config: CheckoutConfig, payLocal: Bool) -> Charge {
  if payLocal, let offer = config.localOffer {
    return Charge(amountMinor: offer.amountMinor, currency: offer.currency.uppercased(), localOffer: offer.token)
  }
  return Charge(amountMinor: config.amountMinor, currency: config.currency.uppercased(), localOffer: nil)
}

/// The ways to pay the sheet offers.
struct SheetMethods: Equatable {
  /// For the engine (cards, pay-later, Cash App), in this order.
  var elementTypes: [String]
  var applePay: Bool
  var googlePay: Bool
  /// Our own panels: UPI (INR) and Pix (BRL), only when paying in that currency.
  var upi: Bool
  var pix: Bool
}

/// The engine's payment-method types, by our method key, in the sheet's order.
private let engineTypes: [(key: String, type: String)] = [
  ("card", "card"),
  ("klarna", "klarna"),
  ("affirm", "affirm"),
  ("afterpayClearpay", "afterpay_clearpay"),
  ("amazonPay", "amazon_pay"),
  ("cashapp", "cashapp"),
]

/// Methods the business priced only in US dollars: hidden when the buyer pays in their own currency (as on web).
private let usdOnly: Set<String> = ["klarna", "affirm", "afterpayClearpay", "amazonPay", "cashapp", "ach"]

/// The business's enabled methods, by the same rules as the web checkout. Bank debit (ACH) is not offered in the
/// mobile sheet yet.
func sheetMethods(_ config: CheckoutConfig, _ charge: Charge) -> SheetMethods {
  let local = charge.localOffer != nil
  let offerMethods = config.localOffer?.methods ?? []
  return SheetMethods(
    elementTypes: engineTypes
      .filter { $0.key == "card" || config.enabled($0.key) }
      .filter { !(local && usdOnly.contains($0.key)) }
      .map { $0.type },
    applePay: config.enabled("applePay"),
    googlePay: config.enabled("googlePay"),
    upi: local && charge.currency == "INR" && config.enabled("upi") && offerMethods.contains("upi"),
    pix: local && charge.currency == "BRL" && config.enabled("pix") && offerMethods.contains("pix")
  )
}

/// The merchant's result for a /pay answer that needs nothing more from the buyer.
func resultFromPay(_ res: PayResponse) -> CheckoutResult? {
  switch res.state {
  case "succeeded": return .succeeded(paymentId: res.paymentId, transactionId: res.transactionId)
  case "processing": return .processing(paymentId: res.paymentId)
  default: return nil // requires_action: the engine finishes it first
  }
}

/// The merchant's result from the session status (after 3D Secure, a redirect, UPI or Pix).
func resultFromStatus(_ s: SessionStatus) -> CheckoutResult? {
  switch s.state {
  case "succeeded": return .succeeded(paymentId: s.paymentId, transactionId: s.transactionId)
  case "processing": return .processing(paymentId: s.paymentId)
  case "failed": return .failed(declinedError)
  case "canceled": return .failed(expiredError)
  default: return nil // still open or waiting on the buyer
  }
}

let declinedError = VezmoError(code: .declined, message: "Your payment didn’t go through. Try another card or way to pay.")
let expiredError = VezmoError(code: .expired, message: "This checkout has expired. Please start again.")
let networkError = VezmoError(code: .network, message: "Check your connection and try again.")

/// `pi_123_secret_abc` → `pi_123`: the intent the device finished, for /confirm. Nil for anything else.
func intentIdOf(_ clientSecret: String?) -> String? {
  guard let clientSecret,
        let match = intentSecret.firstMatch(in: clientSecret, range: NSRange(clientSecret.startIndex..., in: clientSecret)),
        let range = Range(match.range(at: 1), in: clientSecret) else { return nil }
  return String(clientSecret[range])
}

private let intentSecret = try! NSRegularExpression(pattern: "^(pi_[A-Za-z0-9]+)_secret_[A-Za-z0-9_-]+$")

/// Amount for display, e.g. 4900 USD → "$49.00", 420000 INR → "₹4,200.00".
func formatAmount(_ amountMinor: Int, _ currency: String, locale: Locale = .current) -> String {
  let f = NumberFormatter()
  f.numberStyle = .currency
  f.locale = locale
  f.currencyCode = currency
  return f.string(from: NSNumber(value: Double(amountMinor) / 100)) ?? String(format: "%.2f %@", Double(amountMinor) / 100, currency)
}
