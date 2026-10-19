import Foundation

/// What the buyer is charged: the session's price, or the offer in their own currency when they chose it. A free
/// trial charges nothing today, whichever currency the plan will renew in.
struct Charge: Equatable {
  var amountMinor: Int
  var currency: String
  /// The sealed local-currency offer when the buyer pays in their own currency.
  var localOffer: String?
}

func chargeOf(_ config: CheckoutConfig, payLocal: Bool) -> Charge {
  let trial = config.subscription?.trial == true
  if payLocal, let offer = config.localOffer {
    return Charge(amountMinor: trial ? 0 : offer.amountMinor, currency: offer.currency.uppercased(), localOffer: offer.token)
  }
  return Charge(amountMinor: trial ? 0 : config.amountMinor, currency: config.currency.uppercased(), localOffer: nil)
}

// MARK: - Subscriptions

/// The subscription checkout as the sheet's config: cards and Apple Pay only, the plan beside the price.
func configFromSubscription(_ v: SubscriptionView) -> CheckoutConfig {
  CheckoutConfig(
    publishableKey: v.publishableKey ?? "",
    connectedAccountId: v.connectedAccountId,
    livemode: v.livemode,
    amountMinor: Int((v.amount * 100).rounded()),
    currency: v.currency,
    title: v.planName,
    merchantName: v.merchant?.name,
    enabledMethods: ["card": true, "applePay": true, "googlePay": true],
    customerName: v.customer?.name,
    customerEmail: v.customer?.email,
    theme: nil,
    paymentId: "",
    localOffer: v.localOffer,
    subscription: SubscriptionPlan(
      ref: v.subscriptionRef,
      mode: v.mode,
      planName: v.planName,
      recurringAmount: v.recurringAmount,
      currency: v.currency,
      interval: v.interval,
      intervalCount: v.intervalCount,
      trialDays: v.trialDays,
      nextChargeAt: v.nextChargeAt,
      manageUrl: v.manageUrl,
      consentText: v.consentText
    ),
    processingCountry: v.processingCountry
  )
}

/// Apple Pay's merchant country: the app's own, else the country of the account that processes the payment (from the
/// session: the business's own on Connect, Vezmo's on merchant of record), else US (a server that doesn't say).
func merchantCountry(_ app: String?, session: String?) -> String {
  for candidate in [app, session] {
    let code = (candidate ?? "").trimmingCharacters(in: .whitespaces).uppercased()
    if code.count == 2, code.unicodeScalars.allSatisfy({ ("A"..."Z").contains($0) }) { return code }
  }
  return "US"
}

func subscriptionOf(_ ref: String, _ status: String) -> SubscriptionResult {
  let s = status.lowercased()
  return SubscriptionResult(ref: ref, status: s == "active" ? .active : s == "trialing" ? .trialing : .incomplete)
}

/// A subscription /pay answer in the shape the session handles for every payment.
func payFromSubscription(_ r: SubscriptionPayResponse) -> PayResponse {
  switch r.state {
  case "succeeded":
    return PayResponse(state: r.state, paymentId: r.paymentId, transactionId: r.transactionId, clientSecret: r.clientSecret,
                       method: r.method, subscription: subscriptionOf(r.subscriptionRef, r.subscriptionStatus ?? ""))
  case "processing":
    return PayResponse(state: r.state, paymentId: r.paymentId, clientSecret: r.clientSecret, method: r.method,
                       subscription: subscriptionOf(r.subscriptionRef, "incomplete"))
  default:
    return PayResponse(state: "requires_action", paymentId: nil, clientSecret: r.clientSecret, method: r.method)
  }
}

/// The result after a subscription start finished on the device (3D Secure), from /confirm.
func resultFromSubscriptionConfirm(_ r: SubscriptionConfirm) -> CheckoutResult {
  let subscription = subscriptionOf(r.subscriptionRef, r.status)
  if r.status == "processing" { return .processing(paymentId: r.paymentId, subscription: subscription) }
  return .succeeded(paymentId: r.paymentId, transactionId: r.transactionId, subscription: subscription)
}

/// The plan's price in the currency the buyer pays in, in minor units: the plan's own, or about what it is in their
/// currency at today's offer (each renewal is converted at the rate of its billing day).
func recurringMinorIn(_ config: CheckoutConfig, _ charge: Charge) -> Int {
  guard let plan = config.subscription else { return 0 }
  let own = Int((plan.recurringAmount * 100).rounded())
  guard charge.localOffer != nil, let offer = config.localOffer else { return own }
  // The offer was priced on what is due today (the plan's price on a free trial).
  let basis = plan.trial ? own : config.amountMinor
  return basis > 0 ? Int((Double(own) * Double(offer.amountMinor) / Double(basis)).rounded()) : own
}

private let units: [String: (one: String, many: String)] = [
  "HOUR": ("hour", "hours"), "DAY": ("day", "days"), "WEEK": ("week", "weeks"),
  "MONTH": ("month", "months"), "YEAR": ("year", "years"),
]

/// "month", "3 months": one period of the plan.
func periodText(_ interval: String, _ count: Int) -> String {
  let unit = units[interval.uppercased()] ?? (interval.lowercased(), interval.lowercased() + "s")
  return count > 1 ? "\(count) \(unit.many)" : unit.one
}

/// "$9.99 / month", "$30.00 every 3 months": the plan's price, as the buyer reads it.
func planPriceText(_ plan: SubscriptionPlan, locale: Locale = .current) -> String {
  let price = formatAmount(Int((plan.recurringAmount * 100).rounded()), plan.currency.uppercased(), locale: locale)
  return plan.intervalCount > 1
    ? "\(price) every \(periodText(plan.interval, plan.intervalCount))"
    : "\(price) / \(periodText(plan.interval, 1))"
}

/// An ISO 8601 date from the API (with or without fractional seconds).
func isoDate(_ iso: String?) -> Date? {
  guard let iso, !iso.isEmpty else { return nil }
  let f = ISO8601DateFormatter()
  f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
  if let d = f.date(from: iso) { return d }
  f.formatOptions = [.withInternetDateTime]
  return f.date(from: iso)
}

/// "Oct 23, 2026" (the buyer's own date format).
func dateText(_ iso: String?, locale: Locale = .current) -> String {
  guard let date = isoDate(iso) else { return "" }
  let f = DateFormatter()
  f.locale = locale
  f.setLocalizedDateFormatFromTemplate("dMMMy")
  return f.string(from: date)
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
  case "succeeded": return .succeeded(paymentId: res.paymentId, transactionId: res.transactionId, subscription: res.subscription)
  case "processing": return .processing(paymentId: res.paymentId, subscription: res.subscription)
  default: return nil // requires_action: the engine finishes it first
  }
}

extension CheckoutResult {
  /// Paid, or money on its way.
  var isPaid: Bool {
    switch self {
    case .succeeded, .processing: return true
    case .canceled, .failed: return false
    }
  }
}

/// The merchant's result from the session status (after 3D Secure, a redirect, UPI or Pix).
func resultFromStatus(_ s: SessionStatus) -> CheckoutResult? {
  switch s.state {
  case "succeeded": return .succeeded(paymentId: s.paymentId, transactionId: s.transactionId, subscription: nil)
  case "processing": return .processing(paymentId: s.paymentId, subscription: nil)
  case "failed": return .failed(declinedError)
  case "canceled": return .failed(expiredError)
  default: return nil // still open or waiting on the buyer
  }
}

let declinedError = VezmoError(code: .declined, message: "Your payment didn’t go through. Try another card or way to pay.")
let expiredError = VezmoError(code: .expired, message: "This checkout has expired. Please start again.")
let networkError = VezmoError(code: .network, message: "Check your connection and try again.")

/// `pi_123_secret_abc` → `pi_123` (or a free trial's `seti_…`): the intent the device finished, for /confirm.
func intentIdOf(_ clientSecret: String?) -> String? {
  guard let clientSecret,
        let match = intentSecret.firstMatch(in: clientSecret, range: NSRange(clientSecret.startIndex..., in: clientSecret)),
        let range = Range(match.range(at: 1), in: clientSecret) else { return nil }
  return String(clientSecret[range])
}

private let intentSecret = try! NSRegularExpression(pattern: "^((?:pi|seti)_[A-Za-z0-9]+)_secret_[A-Za-z0-9_-]+$")

/// Amount for display, e.g. 4900 USD → "$49.00", 420000 INR → "₹4,200.00".
func formatAmount(_ amountMinor: Int, _ currency: String, locale: Locale = .current) -> String {
  let f = NumberFormatter()
  f.numberStyle = .currency
  f.locale = locale
  f.currencyCode = currency
  return f.string(from: NSNumber(value: Double(amountMinor) / 100)) ?? String(format: "%.2f %@", Double(amountMinor) / 100, currency)
}
