import Foundation

/// The outcome `present(from:)` returns. It never throws.
public enum CheckoutResult: Equatable, Sendable {
  /// Paid. `paymentId` and `transactionId` match the dashboard and the webhook. `paymentId` is nil only when a
  /// subscription's free trial started: nothing was charged today. `subscription` is set for a subscription checkout.
  case succeeded(paymentId: String?, transactionId: String?, subscription: SubscriptionResult?)
  /// Paid, money on its way (a bank debit, UPI or Pix still settling), or a UPI or Pix payment the buyer started and
  /// can still finish in their bank app after closing the sheet: wait for the webhook, which says how it ends.
  case processing(paymentId: String?, subscription: SubscriptionResult?)
  /// The buyer closed the sheet. Not an error.
  case canceled
  case failed(VezmoError)
}

/// The subscription a subscription checkout started.
public struct SubscriptionResult: Equatable, Sendable {
  public enum Status: String, Sendable {
    /// The first payment went through.
    case active
    /// A free trial started; the card is saved for the first charge.
    case trialing
    /// Still settling: the webhook tells your server when it is active.
    case incomplete
  }

  /// The subscription's reference, as in the dashboard and the webhooks.
  public let ref: String
  public let status: Status

  public init(ref: String, status: Status) {
    self.ref = ref
    self.status = status
  }
}

public struct VezmoError: Error, Equatable, Sendable {
  public enum Code: String, Sendable {
    /// The bank said no; the buyer can try another card or way to pay.
    case declined
    /// The session ran out: create a new one.
    case expired
    case alreadyPaid = "already_paid"
    /// This way to pay can't be used here.
    case notAvailable = "not_available"
    case network
    /// A setup mistake in the app.
    case config
    case unknown
  }

  public let code: Code
  /// Safe to show the buyer, in Vezmo's words.
  public let message: String

  public init(code: Code, message: String) {
    self.code = code
    self.message = message
  }
}

extension VezmoError: LocalizedError {
  public var errorDescription: String? { message }
}

// MARK: - What the API sends (internal)

/// What POST …/checkout returns that the sheet uses (the API checkout session's config).
struct CheckoutConfig: Decodable, Equatable {
  var publishableKey: String
  /// Null for a merchant-of-record business: the platform account.
  var connectedAccountId: String?
  var livemode: Bool
  var amountMinor: Int
  var currency: String
  var title: String?
  var merchantName: String?
  var enabledMethods: [String: Bool?]?
  var customerName: String?
  var customerEmail: String?
  var theme: String?
  var paymentId: String
  var localOffer: LocalOffer?
  /// A subscription checkout: the plan, and what starting it means. Nil for a one-time payment.
  var subscription: SubscriptionPlan?
  /// The country of the account that processes the payment (the business's own on Connect, Vezmo's on merchant of
  /// record): Apple Pay's merchant country. Nil from a server that doesn't say.
  var processingCountry: String? = nil

  private enum CodingKeys: String, CodingKey {
    case publishableKey, connectedAccountId, livemode, amountMinor, currency, title, merchantName, enabledMethods
    case customerName, customerEmail, theme, paymentId, localOffer, processingCountry
  }

  func enabled(_ method: String) -> Bool { (enabledMethods?[method] ?? nil) == true }
}

/// What the buyer subscribes to (from the subscription checkout).
struct SubscriptionPlan: Equatable {
  var ref: String
  /// `payment`: the first period is charged now. `setup`: a free trial; the card is saved, nothing is charged today.
  var mode: String
  var planName: String
  /// The price of each period, in the plan's currency (major units).
  var recurringAmount: Double
  var currency: String
  /// `HOUR`, `DAY`, `WEEK`, `MONTH` or `YEAR`.
  var interval: String
  var intervalCount: Int
  var trialDays: Int?
  /// When the plan's price is next charged (ISO 8601): the trial's end, or the end of the first period.
  var nextChargeAt: String?
  /// The buyer's page to view or cancel the subscription.
  var manageUrl: String?
  /// The recurring authorization the buyer agrees to by subscribing.
  var consentText: String

  var trial: Bool { mode == "setup" }
}

/// GET /subscription-checkout/:token: the subscription checkout as the API returns it.
struct SubscriptionView: Decodable, Equatable {
  struct Merchant: Decodable, Equatable { var name: String? }
  struct Customer: Decodable, Equatable {
    var email: String?
    var name: String?
  }

  var subscriptionRef: String
  var mode: String
  var publishableKey: String?
  var connectedAccountId: String?
  var amount: Double
  var recurringAmount: Double
  var currency: String
  var planName: String
  var interval: String
  var intervalCount: Int
  var trialDays: Int?
  var merchant: Merchant?
  var customer: Customer?
  var consentText: String
  var localOffer: LocalOffer?
  var livemode: Bool
  var nextChargeAt: String?
  var manageUrl: String?
  /// See `CheckoutConfig.processingCountry`.
  var processingCountry: String? = nil
  /// The business offers Instant ACH for this plan (US dollars).
  var instantAchEnabled: Bool? = nil
}

/// POST /subscription-checkout/:token/pay: the subscription twin of `PayResponse`.
struct SubscriptionPayResponse: Decodable, Equatable {
  /// `succeeded`, `processing` or `requires_action`.
  var state: String
  var subscriptionRef: String
  var subscriptionStatus: String?
  var paymentId: String?
  var transactionId: String?
  var clientSecret: String?
  var method: PaidMethod?
}

/// POST /subscription-checkout/:token/confirm: how the subscription stands once the device finished it.
struct SubscriptionConfirm: Decodable, Equatable {
  var status: String
  var subscriptionRef: String
  var paymentId: String?
  var transactionId: String?
}

/// The buyer's own currency, offered beside the price.
struct LocalOffer: Decodable, Equatable {
  var currency: String
  var amountMinor: Int
  var rate: Double
  var country: String?
  /// Methods offered when paying in this currency, e.g. ["upi"] for INR, ["pix"] for BRL.
  var methods: [String]?
  /// Sealed; sent back to pay in this currency.
  var token: String
}

/// What the buyer paid with (for the receipt): the card's network and last four, or the method.
struct PaidMethod: Decodable, Equatable {
  var type: String?
  var brand: String?
  var last4: String?
  var wallet: String?
}

/// POST …/pay: what the server did with the buyer's confirmation token.
struct PayResponse: Decodable, Equatable {
  /// `succeeded`, `processing` or `requires_action`.
  var state: String
  var paymentId: String?
  var transactionId: String?
  var clientSecret: String?
  var method: PaidMethod?
  /// A subscription checkout's subscription (set from `SubscriptionPayResponse`).
  var subscription: SubscriptionResult?

  private enum CodingKeys: String, CodingKey {
    case state, paymentId, transactionId, clientSecret, method
  }
}

struct SessionStatus: Decodable, Equatable {
  /// `open`, `requires_action`, `processing`, `succeeded`, `failed` or `canceled`.
  var state: String
  var paymentId: String
  var transactionId: String?
}

/// The session's intent, made or reused (UPI and Pix start from it).
struct SessionIntent: Decodable, Equatable {
  var id: String
  var clientSecret: String
  var connectedAccountId: String?
  var livemode: Bool
}

enum QrMethod: String, Equatable {
  case upi, pix
}

/// Who pays with UPI or Pix (Pix also needs the CPF or CNPJ).
struct QrBilling: Equatable {
  var name: String
  var line1: String
  var city: String?
  var postalCode: String?
  var country: String?
  var email: String?
  var taxId: String?

  var json: [String: Any] {
    var out: [String: Any] = ["name": name, "line1": line1]
    out["city"] = city
    out["postalCode"] = postalCode
    out["country"] = country
    out["email"] = email
    out["taxId"] = taxId
    return out
  }
}

/// A started UPI or Pix payment.
struct QrPayment: Decodable, Equatable {
  struct Code: Decodable, Equatable {
    var png: String?
    /// Unix seconds.
    var expiresAt: Double?
  }

  var status: String?
  var qr: Code?
  /// UPI: opens the buyer's UPI app on this phone.
  var link: String?
  /// Pix: the code to paste in a bank app.
  var code: String?
}
