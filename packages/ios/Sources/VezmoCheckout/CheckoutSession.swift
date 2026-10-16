import Combine
import UIKit

/// What the engine's element (pay-later rows, Cash App…) can be asked to do.
@MainActor
protocol ElementPort: AnyObject {
  func confirm()
  func resolveConfirmation(clientSecret: String?, errorMessage: String?)
  func clearSelection()
}

/// What the card flow can be asked to do: the card form, paying with its card, answering its confirmation.
@MainActor
protocol CardFlowPort: AnyObject {
  func presentCard()
  func confirmCard()
  func resolveConfirmation(clientSecret: String?, errorMessage: String?)
}

/// The card from the native card form, for the sheet's own Card row.
struct CardChoice: Equatable {
  /// "•••• 4242".
  var label: String
  /// The network's logo when the engine gives one.
  var image: UIImage?
}

enum ConfirmStatus {
  case completed, canceled, failed
}

enum SessionPhase: Equatable {
  case loading
  case ready(error: VezmoError?)
  case paying
  /// UPI or Pix started: the buyer finishes in their bank app; we watch the session.
  case waiting(method: QrMethod, qr: QrPayment)
  case done(CheckoutResult)
  /// The session can't be paid (expired, already paid, the business can't take payments).
  case closed(CheckoutResult)

  var isReady: Bool { if case .ready = self { return true } else { return false } }
  var isWaiting: Bool { if case .waiting = self { return true } else { return false } }
  var isClosed: Bool { if case .closed = self { return true } else { return false } }
}

/// The selected way to pay: the sheet's Card row, one of the element's rows (pay-later), or our UPI or Pix row.
enum PayMethod: Equatable {
  case card, element, upi, pix

  var qr: QrMethod? { self == .upi ? .upi : self == .pix ? .pix : nil }
}

struct SessionSnapshot: Equatable {
  var phase: SessionPhase = .loading
  var config: CheckoutConfig?
  /// The buyer chose to pay in their own currency.
  var payLocal = false
  var charge: Charge?
  var methods: SheetMethods?
  /// The element has a method selected and can be paid.
  var canPay = false
  /// The selected method as the element names it ("Klarna").
  var option: String?
  /// The method the buyer paid with, kept for the receipt (the element clears its selection after paying).
  var paidWith: String?
  /// The same as Vezmo's server read it from the payment (the card's network for its logo).
  var paidMethod: PaidMethod?
  /// The engine couldn't load (shown in the sheet with Try again).
  var elementError: String?
  /// Bumped by Try again: rebuilds the engine.
  var elementAttempt = 0
  var method: PayMethod = .element
  /// The card typed in the card form, kept when the buyer looks at another method and comes back.
  var card: CardChoice?
}

private let priceChanged = "The price in your currency just changed. Check the new amount and pay again."

/// What the token is: an API checkout session's (a one-time payment) or a subscription checkout's (cards and Apple Pay;
/// a first payment or a free trial).
enum CheckoutKind {
  case payment, subscription
}

/// One checkout, from the session's client token (or a subscription checkout's token) to a result. The money
/// decisions are the server's: this only moves the buyer's confirmation token to Vezmo and the answer back to the
/// engine.
@MainActor
final class CheckoutSession: ObservableObject {
  @Published private(set) var snapshot = SessionSnapshot()

  private let api: CheckoutAPI
  private let token: String
  private let returnURL: String?
  private let kind: CheckoutKind
  private weak var element: ElementPort?
  private weak var cardFlow: CardFlowPort?
  /// Which engine view started the payment in progress (its confirmation is answered there).
  private var confirmViaCard = false
  private var lastPay: PayResponse?
  private var lastError: VezmoError?
  private var watching: Task<Void, Never>?
  private var qrIntentId: String?
  /// The card form was opened by the buyer: the card it returns becomes the selection.
  private var awaitingCard = false

  init(api: CheckoutAPI, token: String, returnURL: String?, kind: CheckoutKind = .payment) {
    self.api = api
    self.token = token
    self.returnURL = returnURL
    self.kind = kind
  }

  /// The buyer's currency offer no longer holds: fetch the new one; the buyer sees the new amount before paying again.
  private func refreshOffer() async {
    if let config = try? await fetchConfig() { apply(config, payLocal: snapshot.payLocal) }
  }

  private func fetchConfig() async throws -> CheckoutConfig {
    if kind == .subscription {
      let view = try await api.subscription(token: token)
      return configFromSubscription(view)
    }
    return try await api.checkout(token: token)
  }

  private func set(_ change: (inout SessionSnapshot) -> Void) {
    var next = snapshot
    change(&next)
    if next != snapshot { snapshot = next }
  }

  func attachElement(_ element: ElementPort?) { self.element = element }
  func attachCardFlow(_ cardFlow: CardFlowPort?) { self.cardFlow = cardFlow }

  // MARK: Loading and currency

  func load() async {
    do {
      let config = try await fetchConfig()
      // The buyer's own currency is offered first when there is an offer (as on the web checkout).
      apply(config, payLocal: config.localOffer != nil)
      set { $0.phase = .ready(error: nil) }
    } catch {
      close(error)
    }
  }

  /// The buyer switched between their own currency and the price's.
  func choosePayLocal(_ payLocal: Bool) {
    guard let config = snapshot.config, snapshot.phase != .paying else { return }
    apply(config, payLocal: payLocal)
  }

  /// The element's selected method changed (nil: nothing selected). Choosing one leaves the card, UPI or Pix.
  func setOption(_ label: String?) {
    let leave = label != nil && snapshot.method != .element && snapshot.phase.isReady
    set {
      $0.canPay = label != nil
      $0.option = label.flatMap { $0.isEmpty ? nil : $0 }
      if leave { $0.method = .element }
    }
  }

  /// The buyer tapped the Card row: the card already typed, or the card form to type one.
  func chooseCard() {
    guard snapshot.phase.isReady else { return }
    if snapshot.card != nil {
      element?.clearSelection()
      set {
        $0.method = .card
        $0.phase = .ready(error: nil)
      }
    } else {
      changeCard()
    }
  }

  /// Change (or first type) the card.
  func changeCard() {
    guard snapshot.phase.isReady else { return }
    awaitingCard = true
    cardFlow?.presentCard()
  }

  /// The card form reported its card (or none). One the buyer just typed becomes the selection.
  func setCard(_ card: CardChoice?) {
    let chosen = card != nil && awaitingCard && snapshot.phase.isReady
    if card != nil { awaitingCard = false }
    if chosen { element?.clearSelection() }
    set {
      $0.card = card
      if chosen {
        $0.method = .card
        $0.phase = .ready(error: nil)
      }
    }
  }

  /// The Pay button can be pressed: a typed card on the Card row, or a method chosen in the element.
  static func canPay(_ s: SessionSnapshot) -> Bool {
    switch s.method {
    case .card: return s.card != nil
    case .element: return s.canPay
    case .upi, .pix: return false
    }
  }

  func setElementError(_ message: String?) {
    set {
      $0.elementError = message
      if message != nil { $0.canPay = false }
    }
  }

  func retryElement() {
    set {
      $0.elementError = nil
      $0.elementAttempt += 1
    }
  }

  private func apply(_ config: CheckoutConfig, payLocal: Bool) {
    let charge = chargeOf(config, payLocal: payLocal && config.localOffer != nil)
    let methods = sheetMethods(config, charge)
    set {
      // A panel that this currency doesn't offer falls back to the element.
      if ($0.method == .upi && !methods.upi) || ($0.method == .pix && !methods.pix) { $0.method = .element }
      $0.config = config
      $0.payLocal = charge.localOffer != nil
      $0.charge = charge
      $0.methods = methods
    }
  }

  /// UPI or Pix (the sheet's own rows) or back to the element; one method is selected at a time.
  func chooseMethod(_ method: PayMethod) {
    guard snapshot.phase.isReady else { return }
    if method != .element { element?.clearSelection() }
    set {
      $0.method = method
      $0.phase = .ready(error: nil)
    }
  }

  // MARK: UPI and Pix

  /// Start UPI or Pix: the UPI app link or the Pix code comes back; then we watch the session until it is paid.
  func payWithQr(_ method: QrMethod, billing: QrBilling) async {
    guard snapshot.phase.isReady else { return }
    set {
      $0.phase = .paying
      $0.paidWith = method == .upi ? "UPI" : "Pix"
    }
    do {
      let intent = try await api.intent(token: token, localOffer: snapshot.charge?.localOffer)
      qrIntentId = intent.id
      let qr = try await api.startQr(method, intent: intent, returnURL: api.checkoutPageURL(token: token), billing: billing)
      set { $0.phase = .waiting(method: method, qr: qr) }
      watch()
    } catch {
      let apiError = error as? VezmoAPIError
      if apiError?.code == .expired || apiError?.code == .alreadyPaid { return close(error) }
      // UPI and Pix are always in the buyer's currency: an offer that ran out is fetched again, not resent.
      if apiError?.reason == "local_price_changed" {
        set { $0.phase = .ready(error: VezmoError(code: .unknown, message: priceChanged)) }
        return await refreshOffer()
      }
      set { $0.phase = .ready(error: apiError?.vezmoError ?? VezmoError(code: .unknown, message: declinedError.message)) }
    }
  }

  /// The buyer gave up waiting: back to the sheet (a payment made meanwhile still settles).
  func stopWaiting() {
    unwatch()
    if snapshot.phase.isWaiting { set { $0.phase = .ready(error: nil) } }
  }

  /// Check now (the buyer came back from their bank app).
  func checkNow() async {
    guard snapshot.phase.isWaiting else { return }
    guard let status = try? await api.status(token: token),
          let result = resultFromStatus(status),
          snapshot.phase.isWaiting else { return }
    // The watcher stops, but only once this check is over: it may be the watcher's own, and cancelling it now would
    // cancel the confirm call below with it.
    let watcher = watching
    watching = nil
    defer { watcher?.cancel() }
    if case .failed = result {
      set { $0.phase = .ready(error: VezmoError(code: .declined, message: "The payment didn’t go through. Try again.")) }
      return
    }
    // Recorded now rather than when the webhook arrives (as the web checkout does).
    if let qrIntentId { try? await api.confirm(token: token, paymentIntentId: qrIntentId) }
    set { $0.phase = .done(result) }
  }

  private func watch() {
    unwatch()
    let started = Date()
    watching = Task { [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        guard let self, !Task.isCancelled else { return }
        // A code lasts 10 minutes; stop watching a little after.
        if Date().timeIntervalSince(started) > 11 * 60 { return self.stopWaiting() }
        await self.checkNow()
      }
    }
  }

  private func unwatch() {
    watching?.cancel()
    watching = nil
  }

  /// The sheet is going away.
  func dispose() {
    unwatch()
  }

  // MARK: Paying

  func pay() {
    guard snapshot.phase.isReady, Self.canPay(snapshot) else { return }
    let card = snapshot.method == .card
    guard card ? cardFlow != nil : element != nil else { return }
    lastPay = nil
    lastError = nil
    confirmViaCard = card
    set {
      $0.phase = .paying
      $0.paidWith = card ? $0.card?.label : $0.option
    }
    if card { cardFlow?.confirmCard() } else { element?.confirm() }
  }

  /// An express wallet button was tapped (Apple Pay): its payment has started in the card flow.
  func walletStarted(_ wallet: String) {
    guard snapshot.phase.isReady else { return }
    lastPay = nil
    lastError = nil
    confirmViaCard = true
    set {
      $0.phase = .paying
      $0.paidWith = wallet == "apple_pay" ? "Apple Pay" : wallet == "google_pay" ? "Google Pay" : wallet
    }
  }

  /// The engine view that started the payment in progress answers its own confirmation.
  private func resolve(_ clientSecret: String?, _ errorMessage: String?) {
    if confirmViaCard {
      cardFlow?.resolveConfirmation(clientSecret: clientSecret, errorMessage: errorMessage)
    } else {
      element?.resolveConfirmation(clientSecret: clientSecret, errorMessage: errorMessage)
    }
  }

  /// The engine made a confirmation token: Vezmo prices and confirms the payment with it.
  func onConfirmationToken(_ id: String) async {
    do {
      let localOffer = snapshot.charge?.localOffer
      let res: PayResponse
      if kind == .subscription {
        let answer = try await api.subscriptionPay(token: token, confirmationToken: id, localOffer: localOffer, returnURL: returnURL)
        res = payFromSubscription(answer)
      } else {
        res = try await api.pay(token: token, confirmationToken: id, localOffer: localOffer, returnURL: returnURL)
      }
      lastPay = res
      if let method = res.method { set { $0.paidMethod = method } }
      resolve(res.clientSecret, res.clientSecret == nil ? declinedError.message : nil)
    } catch {
      let apiError = error as? VezmoAPIError
      if apiError?.reason == "local_price_changed" {
        let message = VezmoError(code: .unknown, message: priceChanged)
        lastError = message
        resolve(nil, message.message)
        await refreshOffer()
        return
      }
      let message = apiError?.vezmoError ?? VezmoError(code: .unknown, message: declinedError.message)
      lastError = message
      resolve(nil, message.message)
      if apiError?.code == .expired || apiError?.code == .alreadyPaid { close(error) }
    }
  }

  /// The engine finished: completed (maybe after 3D Secure), canceled, or failed.
  func onConfirmResult(_ status: ConfirmStatus, message: String) async {
    if snapshot.phase.isClosed { return }
    switch status {
    case .canceled:
      set { $0.phase = .ready(error: nil) }
      return
    case .failed:
      var error = lastError
      if error == nil {
        switch await deviceFailure() {
        case .failed(let verdict)?: error = verdict
        case let paid?:
          set { $0.phase = .done(paid) }
          return
        case nil: break
        }
      }
      let shown = error ?? VezmoError(code: .declined, message: message.isEmpty ? declinedError.message : message)
      set { $0.phase = .ready(error: shown) }
      return
    case .completed:
      break
    }
    // A payment our server already recorded is done; one finished on the device (3D Secure, a redirect) is recorded
    // now, then read back.
    if let lastPay, let result = resultFromPay(lastPay) {
      set { $0.phase = .done(result) }
      return
    }
    let intentId = intentIdOf(lastPay?.clientSecret)
    if kind == .subscription {
      let result = await startedSubscription(intentId)
      set { $0.phase = .done(result) }
      return
    }
    if let intentId {
      // The webhook records it either way; the status below is the answer.
      try? await api.confirm(token: token, paymentIntentId: intentId)
    }
    let result = await settledResult()
    set { $0.phase = .done(result) }
  }

  /// A payment Vezmo handed to the device (3D Secure, a redirect) that failed or was lost there: Vezmo's verdict.
  /// Paid after all; or why not, in Vezmo's words (the same on every phone, where the engine words a decline
  /// differently), its confirm step recording the decline at once. Nil when Vezmo has no answer.
  private func deviceFailure() async -> CheckoutResult? {
    guard lastPay?.state == "requires_action", let intentId = intentIdOf(lastPay?.clientSecret) else { return nil }
    do {
      if kind == .subscription {
        return resultFromSubscriptionConfirm(try await api.subscriptionConfirm(token: token, intentId: intentId))
      }
      // The session's state first: a step only lost (the payment still waiting on it) has no verdict to confirm.
      guard let status = try? await api.status(token: token) else { return nil }
      if let result = resultFromStatus(status) {
        if case .failed = result {} else { return result }
      }
      guard status.state == "failed" else { return nil }
      try await api.confirm(token: token, paymentIntentId: intentId)
      return nil
    } catch let error as VezmoAPIError where error.code == .declined {
      return .failed(error.vezmoError)
    } catch {
      return nil
    }
  }

  /// A subscription started on the device (after 3D Secure): Vezmo records it and says how it stands.
  private func startedSubscription(_ intentId: String?) async -> CheckoutResult {
    if let intentId, let confirmed = try? await api.subscriptionConfirm(token: token, intentId: intentId) {
      return resultFromSubscriptionConfirm(confirmed)
    }
    // Recorded from the processor's notice instead; the merchant's webhook tells them.
    let ref = snapshot.config?.subscription?.ref ?? ""
    return .processing(paymentId: nil, subscription: SubscriptionResult(ref: ref, status: .incomplete))
  }

  /// The session's state, polled briefly while the server catches up with a payment just made.
  private func settledResult() async -> CheckoutResult {
    for _ in 0..<5 {
      if let status = try? await api.status(token: token), let result = resultFromStatus(status) { return result }
      try? await Task.sleep(nanoseconds: 1_500_000_000)
    }
    // Paid on the device and not yet visible: the webhook will tell the merchant's server.
    return .processing(paymentId: snapshot.config?.paymentId ?? "", subscription: nil)
  }

  // MARK: Closing

  private func close(_ error: Error) {
    let err = (error as? VezmoAPIError)?.vezmoError ?? networkError
    set { $0.phase = .closed(.failed(err.code == .expired ? expiredError : err)) }
  }
}
