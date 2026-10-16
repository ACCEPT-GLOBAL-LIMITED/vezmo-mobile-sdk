import Foundation
import os
@_spi(STP) @_spi(ConfirmationTokensPublicPreview) import StripePaymentSheet
import SwiftUI
import UIKit

/// How the engine is set up: keys and account from the session, the look, the app's return link, Apple Pay (for the
/// card flow's express button) only when the business offers it. A change rebuilds the engine.
struct EngineSettings: Equatable {
  var publishableKey: String
  var account: String?
  var merchantDisplayName: String
  var returnURL: String?
  var dark: Bool
  var palette: Palette
  var radius: CGFloat
  var fontFamily: String?
  var applePay: VezmoCheckout.ApplePay?
  var billingName: String?
  var billingEmail: String?
  /// Bumped to rebuild the engine (Try again after a failed load).
  var attempt: Int
}

/// What is charged: a change updates the engine in place (the buyer switched currency). Cards go to the native card
/// form (`card`), the sheet's own Card row; every other method to the element's rows.
struct EngineIntent: Equatable {
  var amount: Int
  var currency: String
  var paymentMethodTypes: [String]
  var card: Bool

  init(charge: Charge, methods: SheetMethods) {
    amount = charge.amountMinor
    currency = charge.currency.lowercased()
    paymentMethodTypes = methods.elementTypes.filter { $0 != "card" }
    card = methods.elementTypes.contains("card")
  }

  /// What is charged, for the element or the card flow: a confirmation token goes to `secret`, which answers with the
  /// intent's client secret from Vezmo's server.
  func configuration(types: [String], secret: @escaping (String) async throws -> String) -> PaymentSheet.IntentConfiguration {
    PaymentSheet.IntentConfiguration(
      // Our server makes every intent with automatic_async capture; the engine must say the same.
      mode: .payment(
        amount: amount,
        currency: currency,
        setupFutureUsage: nil,
        captureMethod: .automaticAsync,
        paymentMethodOptions: nil
      ),
      paymentMethodTypes: types,
      onBehalfOf: nil,
      confirmationTokenConfirmHandler: { confirmationToken in
        try await secret(confirmationToken.stripeId)
      }
    )
  }
}

/// An error the engine shows the buyer as is (already in Vezmo's words).
struct EngineError: LocalizedError {
  let message: String
  var errorDescription: String? { message.isEmpty ? "Your payment couldn’t be completed. Please try again." : message }
}

private let notReady = "The payment form isn’t ready yet."

/// Why the engine couldn't load or update, for the app's console (the buyer sees our own words).
let engineLog = Logger(subsystem: "com.vezmo.checkout", category: "engine")

private func confirmStatus(_ result: PaymentSheetResult) -> (ConfirmStatus, String) {
  switch result {
  case .completed: return (.completed, "")
  case .canceled: return (.canceled, "")
  case .failed(let error): return (.failed, error.localizedDescription)
  }
}

// MARK: - Card flow

/// Cards and Apple Pay: the engine's flow controller (the native card form, Apple Pay), confirmed through Vezmo's
/// server like the element. The sheet draws the Card row itself (with the card network's logo) and opens the card
/// form with `presentCard()`; the express Apple Pay button is the wallet flow's.
///
/// Two flow controllers: one for cards only (its sheet is just the card form, which the Card row opens) and one with
/// Apple Pay that only draws the express button. Both confirm through the same Vezmo step.
@MainActor
final class CardFlowHost: ObservableObject, CardFlowPort {
  weak var session: CheckoutSession?
  weak var presenter: UIViewController?
  /// The flow behind the express Apple Pay button (nil: none on this session).
  @Published private(set) var walletFlow: PaymentSheet.FlowController?
  /// Built for the current settings.
  @Published private(set) var loaded = false

  private var flow: PaymentSheet.FlowController?
  private var settings: EngineSettings?
  private var intent: EngineIntent?
  private var building = false
  private var pendingSecret: CheckedContinuation<String, Error>?

  func configure(_ settings: EngineSettings, _ intent: EngineIntent) {
    let newSettings = settings != self.settings
    guard newSettings || intent != self.intent else { return }
    self.settings = settings
    self.intent = intent
    if newSettings || intent.card != (flow != nil) {
      loaded = false
      Task { await build() }
    } else if intent.card {
      update()
    }
  }

  /// The sheet is gone: let the engine go.
  func teardown() {
    pendingSecret?.resume(throwing: EngineError(message: ""))
    pendingSecret = nil
    flow = nil
    walletFlow = nil
  }

  // MARK: Commands

  func presentCard() {
    guard let flow, let presenter else { return }
    flow.presentPaymentOptions(from: presenter) { [weak self] _ in
      self?.reportCard()
    }
  }

  func confirmCard() {
    guard let flow, let presenter else {
      Task { await session?.onConfirmResult(.failed, message: notReady) }
      return
    }
    flow.confirm(from: presenter) { [weak self] result in
      self?.report(result)
    }
  }

  func resolveConfirmation(clientSecret: String?, errorMessage: String?) {
    guard let continuation = pendingSecret else { return }
    pendingSecret = nil
    if let clientSecret, !clientSecret.isEmpty {
      continuation.resume(returning: clientSecret)
    } else {
      continuation.resume(throwing: EngineError(message: errorMessage ?? ""))
    }
  }

  // MARK: Flow

  private func build() async {
    guard !building else { return }
    building = true
    defer { building = false }
    // What changes while a build runs is built next.
    while let settings, let intent {
      let built = await build(settings, intent)
      // Shown as loaded only when what was built is still what the sheet wants (a tap meanwhile would reach an
      // engine about to be replaced).
      if settings == self.settings && intent == self.intent {
        loaded = built
        return
      }
    }
  }

  /// Builds the engine for these settings; false when it couldn't load (the session shows why).
  private func build(_ settings: EngineSettings, _ intent: EngineIntent) async -> Bool {
    guard intent.card else {
      flow = nil
      walletFlow = nil
      reportCard()
      return true
    }
    STPAPIClient.shared.publishableKey = settings.publishableKey
    STPAPIClient.shared.stripeAccount = settings.account
    do {
      flow = try await makeFlow(intent, configuration(settings, wallets: false))
      // The express button needs iOS 16; without Apple Pay set up there is none.
      if #available(iOS 16.0, *), settings.applePay != nil {
        walletFlow = try await makeFlow(intent, configuration(settings, wallets: true))
      } else {
        walletFlow = nil
      }
      reportCard()
      return true
    } catch {
      engineLog.error("Card flow failed to load: \(String(describing: error), privacy: .public)")
      session?.setElementError(error.localizedDescription.isEmpty ? "load failed" : error.localizedDescription)
      return false
    }
  }

  private func makeFlow(_ intent: EngineIntent, _ configuration: PaymentSheet.Configuration) async throws -> PaymentSheet.FlowController {
    try await withCheckedThrowingContinuation { continuation in
      PaymentSheet.FlowController.create(
        intentConfiguration: intentConfiguration(intent),
        configuration: configuration
      ) { continuation.resume(with: $0) }
    }
  }

  private func update() {
    guard let flow, let intent else { return }
    flow.update(intentConfiguration: intentConfiguration(intent)) { [weak self] error in
      if let error {
        engineLog.error("Card flow failed to update: \(String(describing: error), privacy: .public)")
        self?.session?.setElementError(error.localizedDescription)
      }
      self?.reportCard()
    }
    walletFlow?.update(intentConfiguration: intentConfiguration(intent)) { _ in }
  }

  private func intentConfiguration(_ intent: EngineIntent) -> PaymentSheet.IntentConfiguration {
    intent.configuration(types: ["card"]) { [weak self] tokenId in
      guard let self else { throw EngineError(message: "") }
      return try await self.clientSecret(forToken: tokenId)
    }
  }

  /// The same business, look and billing details as the element; Apple Pay only for the wallet flow.
  private func configuration(_ settings: EngineSettings, wallets: Bool) -> PaymentSheet.Configuration {
    var configuration = PaymentSheet.Configuration()
    configuration.merchantDisplayName = settings.merchantDisplayName
    if let returnURL = settings.returnURL, !returnURL.isEmpty {
      configuration.returnURL = returnURL
    }
    // Vezmo is white label: no processor wallet of its own.
    configuration.link = PaymentSheet.LinkConfiguration(display: .never)
    configuration.paymentMethodLayout = .vertical
    configuration.style = settings.dark ? .alwaysDark : .alwaysLight
    if wallets, let applePay = settings.applePay, !applePay.merchantId.isEmpty {
      configuration.applePay = PaymentSheet.ApplePayConfiguration(
        merchantId: applePay.merchantId,
        merchantCountryCode: applePay.merchantCountryCode
      )
    }
    configuration.defaultBillingDetails.name = settings.billingName
    configuration.defaultBillingDetails.email = settings.billingEmail
    configuration.appearance = settings.palette.engineAppearance(radius: settings.radius, fontFamily: settings.fontFamily)
    return configuration
  }

  // MARK: Results

  /// The card from the card form, for the sheet's own Card row: its label and the network's logo.
  private func reportCard() {
    if let option = flow?.paymentOption, option.paymentMethodType == "card" {
      session?.setCard(CardChoice(label: option.label, image: option.image))
    } else {
      session?.setCard(nil)
    }
  }

  fileprivate func report(_ result: PaymentSheetResult) {
    let (status, message) = confirmStatus(result)
    Task { await session?.onConfirmResult(status, message: message) }
  }

  fileprivate func walletTapped(_ wallet: String) {
    session?.walletStarted(wallet)
  }

  // MARK: Confirmation token → Vezmo → client secret

  private func clientSecret(forToken tokenId: String) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
      // A confirm still waiting is superseded by this one.
      pendingSecret?.resume(throwing: EngineError(message: ""))
      pendingSecret = continuation
      Task { await session?.onConfirmationToken(tokenId) }
    }
  }
}

/// The express Apple Pay button (the engine's own, as the platform requires).
@available(iOS 16.0, *)
struct WalletButtons: View {
  let host: CardFlowHost
  let flow: PaymentSheet.FlowController

  var body: some View {
    let host = self.host
    return WalletButtonsView(
      flowController: flow,
      confirmHandler: { result in host.report(result) },
      clickHandler: { wallet in
        host.walletTapped(wallet)
        return true
      }
    )
    .id(ObjectIdentifier(flow))
  }
}

// MARK: - Element

/// The engine's embedded element for the methods with rows of their own (pay-later, Cash App…). Cards and wallets are
/// the card flow's; with no other method the element is left out.
///
/// Payment flow: the buyer picks a method and taps Pay → `confirm()` → the element creates a confirmation token and
/// calls our handler → the session sends it to Vezmo's API, which prices and confirms the payment → the session
/// answers with the intent's client secret (`resolveConfirmation`) → the element finishes any step left (3D Secure, a
/// provider) and reports the result.
@MainActor
final class PaymentElementHost: ObservableObject, ElementPort, EmbeddedPaymentElementDelegate {
  weak var session: CheckoutSession?
  weak var presenter: UIViewController?
  @Published private(set) var element: EmbeddedPaymentElement?
  @Published private(set) var height: CGFloat = 0
  /// Built for the current settings.
  @Published private(set) var loaded = false

  private var settings: EngineSettings?
  private var intent: EngineIntent?
  private var building = false
  private var pendingSecret: CheckedContinuation<String, Error>?
  private var width: CGFloat = 0

  func configure(_ settings: EngineSettings, _ intent: EngineIntent) {
    let newSettings = settings != self.settings
    guard newSettings || intent != self.intent else { return }
    self.settings = settings
    self.intent = intent
    // Never an empty list (the engine would show every method on the account): with none, the element goes away.
    let needsElement = !intent.paymentMethodTypes.isEmpty
    if newSettings || needsElement != (element != nil) {
      loaded = false
      Task { await build() }
    } else if needsElement {
      Task { await update() }
    }
  }

  /// The sheet is gone: let the engine go.
  func teardown() {
    pendingSecret?.resume(throwing: EngineError(message: ""))
    pendingSecret = nil
    element?.presentingViewController = nil
    element = nil
  }

  // MARK: Commands

  func confirm() {
    guard let element else {
      Task { await session?.onConfirmResult(.failed, message: notReady) }
      return
    }
    element.presentingViewController = presenter
    element.confirm { [weak self] result in
      let (status, message) = confirmStatus(result)
      Task { await self?.session?.onConfirmResult(status, message: message) }
    }
  }

  func clearSelection() {
    element?.clearPaymentOption()
  }

  func resolveConfirmation(clientSecret: String?, errorMessage: String?) {
    guard let continuation = pendingSecret else { return }
    pendingSecret = nil
    if let clientSecret, !clientSecret.isEmpty {
      continuation.resume(returning: clientSecret)
    } else {
      continuation.resume(throwing: EngineError(message: errorMessage ?? ""))
    }
  }

  // MARK: Element

  private func build() async {
    guard !building else { return }
    building = true
    defer { building = false }
    while let settings, let intent {
      let built = await build(settings, intent)
      // Shown as loaded only when what was built is still what the sheet wants (a tap meanwhile would reach an
      // engine about to be replaced).
      if settings == self.settings && intent == self.intent {
        loaded = built
        return
      }
    }
  }

  /// Builds the engine for these settings; false when it couldn't load (the session shows why).
  private func build(_ settings: EngineSettings, _ intent: EngineIntent) async -> Bool {
    STPAPIClient.shared.publishableKey = settings.publishableKey
    STPAPIClient.shared.stripeAccount = settings.account
    do {
      // The element draws the other ways to pay; with none (cards only), it is left out.
      if !intent.paymentMethodTypes.isEmpty {
        let created = try await EmbeddedPaymentElement.create(
          intentConfiguration: intentConfiguration(intent),
          configuration: configuration(settings)
        )
        created.delegate = self
        created.presentingViewController = presenter
        element = created
        measure()
        embeddedPaymentElementDidUpdatePaymentOption(embeddedPaymentElement: created)
      } else {
        element = nil
        height = 0
      }
      return true
    } catch {
      engineLog.error("Element failed to load: \(String(describing: error), privacy: .public)")
      session?.setElementError(error.localizedDescription.isEmpty ? "load failed" : error.localizedDescription)
      return false
    }
  }

  private func update() async {
    guard let element, let intent else { return }
    let result = await element.update(intentConfiguration: intentConfiguration(intent))
    if case .failed(let error) = result {
      engineLog.error("Element failed to update: \(String(describing: error), privacy: .public)")
      session?.setElementError(error.localizedDescription)
    }
  }

  private func intentConfiguration(_ intent: EngineIntent) -> PaymentSheet.IntentConfiguration {
    intent.configuration(types: intent.paymentMethodTypes) { [weak self] tokenId in
      guard let self else { throw EngineError(message: "") }
      return try await self.clientSecret(forToken: tokenId)
    }
  }

  private func configuration(_ settings: EngineSettings) -> EmbeddedPaymentElement.Configuration {
    var configuration = EmbeddedPaymentElement.Configuration()
    configuration.merchantDisplayName = settings.merchantDisplayName
    if let returnURL = settings.returnURL, !returnURL.isEmpty {
      configuration.returnURL = returnURL
    }
    // Our own Pay button confirms; the card form only collects details.
    configuration.formSheetAction = .continue
    // Vezmo is white label: no processor wallet of its own in the sheet.
    configuration.link = PaymentSheet.LinkConfiguration(display: .never)
    configuration.embeddedViewDisplaysMandateText = true
    configuration.style = settings.dark ? .alwaysDark : .alwaysLight
    configuration.defaultBillingDetails.name = settings.billingName
    configuration.defaultBillingDetails.email = settings.billingEmail
    configuration.appearance = settings.palette.engineAppearance(radius: settings.radius, fontFamily: settings.fontFamily)
    return configuration
  }

  /// The element's height at the width the sheet gives it.
  fileprivate func measure(width: CGFloat? = nil) {
    if let width, width > 0 { self.width = width }
    guard let view = element?.view else { return }
    let fitted = view.systemLayoutSizeFitting(
      CGSize(width: self.width > 0 ? self.width : view.bounds.width, height: UIView.layoutFittingCompressedSize.height),
      withHorizontalFittingPriority: self.width > 0 ? .required : .fittingSizeLevel,
      verticalFittingPriority: .fittingSizeLevel
    ).height
    guard abs(fitted - height) > 0.5 else { return }
    // Not during the sheet's own layout pass.
    DispatchQueue.main.async { self.height = fitted }
  }

  // MARK: Confirmation token → Vezmo → client secret

  private func clientSecret(forToken tokenId: String) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
      pendingSecret?.resume(throwing: EngineError(message: ""))
      pendingSecret = continuation
      Task { await session?.onConfirmationToken(tokenId) }
    }
  }

  // MARK: EmbeddedPaymentElementDelegate

  func embeddedPaymentElementDidUpdateHeight(embeddedPaymentElement: EmbeddedPaymentElement) {
    measure()
  }

  func embeddedPaymentElementDidUpdatePaymentOption(embeddedPaymentElement: EmbeddedPaymentElement) {
    let option = embeddedPaymentElement.paymentOption
    session?.setOption(option.map { $0.label })
  }
}

/// The element's rows, in the sheet's group.
struct ElementView: UIViewRepresentable {
  @ObservedObject var host: PaymentElementHost

  func makeUIView(context: Context) -> ElementContainer {
    let container = ElementContainer()
    container.host = host
    container.show(host.element?.view)
    return container
  }

  func updateUIView(_ container: ElementContainer, context: Context) {
    container.show(host.element?.view)
  }
}

final class ElementContainer: UIView {
  weak var host: PaymentElementHost?
  private weak var shown: UIView?

  func show(_ view: UIView?) {
    guard view !== shown || view?.superview !== self else { return }
    shown?.removeFromSuperview()
    shown = view
    guard let view else { return }
    view.removeFromSuperview()
    view.translatesAutoresizingMaskIntoConstraints = false
    addSubview(view)
    NSLayoutConstraint.activate([
      view.topAnchor.constraint(equalTo: topAnchor),
      view.leadingAnchor.constraint(equalTo: leadingAnchor),
      view.trailingAnchor.constraint(equalTo: trailingAnchor),
    ])
    setNeedsLayout()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    host?.measure(width: bounds.width)
  }
}
