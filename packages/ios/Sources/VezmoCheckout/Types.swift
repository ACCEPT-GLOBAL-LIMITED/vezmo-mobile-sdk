import Foundation

/// The outcome `present(from:)` returns. It never throws.
public enum CheckoutResult: Equatable {
  /// Paid. `paymentId` and `transactionId` match the dashboard and the webhook.
  case succeeded(paymentId: String, transactionId: String?)
  /// Paid, money on its way (a bank debit, UPI or Pix still settling): wait for the webhook.
  case processing(paymentId: String)
  /// The buyer closed the sheet. Not an error.
  case canceled
  case failed(VezmoError)
}

public struct VezmoError: Error, Equatable {
  public enum Code: String {
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

  func enabled(_ method: String) -> Bool { (enabledMethods?[method] ?? nil) == true }
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
  var paymentId: String
  var transactionId: String?
  var clientSecret: String?
  var method: PaidMethod?
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
