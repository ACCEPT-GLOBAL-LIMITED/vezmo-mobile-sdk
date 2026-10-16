import Foundation
import React
@_spi(ConfirmationTokensPublicPreview) import StripePaymentSheet
import UIKit

/// The Vezmo payment element on iOS: the processor's native embedded element, configured from JSON that the JS
/// layer builds, confirmed through Vezmo's server.
///
/// Payment flow: the buyer picks a method and taps Pay → `confirm()` → the element creates a confirmation token and
/// calls our handler → we hand the token's id to JS (`confirmationToken`) → JS sends it to Vezmo's API, which prices
/// and confirms the payment → JS answers with the intent's client secret (`resolveConfirmation`) → the element
/// finishes any step left (3D Secure, a wallet, a provider) and reports `confirmResult`.
@objc(VezmoPaymentElementHost)
public final class VezmoPaymentElementHost: UIView, EmbeddedPaymentElementDelegate {
  /// (event name, body) to the component view, which turns it into a React event.
  @objc public var onEvent: ((String, [String: Any]) -> Void)?

  private var element: EmbeddedPaymentElement?
  private var elementView: UIView?
  private var configJson = ""
  private var intentJson = ""
  private var building = false
  private var pendingSecret: CheckedContinuation<String, Error>?

  public override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
  }

  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  // MARK: Props

  @objc public func setConfigJson(_ config: String, intentJson intent: String) {
    let rebuild = config != configJson || element == nil
    configJson = config
    intentJson = intent
    Task { @MainActor in
      if rebuild { await self.build() } else { await self.update() }
    }
  }

  // MARK: Commands

  @objc public func confirm() {
    guard let element else {
      emit("confirmResult", ["status": "failed", "message": "The payment form isn’t ready yet."])
      return
    }
    element.presentingViewController = RCTPresentedViewController()
    element.confirm { [weak self] result in
      switch result {
      case .completed:
        self?.emit("confirmResult", ["status": "completed", "message": ""])
      case .canceled:
        self?.emit("confirmResult", ["status": "canceled", "message": ""])
      case .failed(let error):
        self?.emit("confirmResult", ["status": "failed", "message": error.localizedDescription])
      }
    }
  }

  @objc public func resolveConfirmation(clientSecret: String, errorMessage: String) {
    guard let continuation = pendingSecret else { return }
    pendingSecret = nil
    if !clientSecret.isEmpty {
      continuation.resume(returning: clientSecret)
    } else {
      continuation.resume(throwing: VezmoElementError(message: errorMessage))
    }
  }

  // MARK: Element

  @MainActor
  private func build() async {
    guard !building, let config = Self.parse(configJson), let intent = Self.parse(intentJson) else { return }
    guard let publishableKey = config["publishableKey"] as? String, !publishableKey.isEmpty else {
      emit("loadFailed", ["message": "Missing checkout keys."])
      return
    }
    building = true
    defer { building = false }

    STPAPIClient.shared.publishableKey = publishableKey
    STPAPIClient.shared.stripeAccount = config["stripeAccount"] as? String

    do {
      let created = try await EmbeddedPaymentElement.create(
        intentConfiguration: intentConfiguration(from: intent),
        configuration: configuration(from: config)
      )
      attach(created)
    } catch {
      emit("loadFailed", ["message": error.localizedDescription])
    }
  }

  @MainActor
  private func update() async {
    guard let element, let intent = Self.parse(intentJson) else { return }
    let result = await element.update(intentConfiguration: intentConfiguration(from: intent))
    if case .failed(let error) = result {
      emit("loadFailed", ["message": error.localizedDescription])
    }
  }

  @MainActor
  private func attach(_ created: EmbeddedPaymentElement) {
    elementView?.removeFromSuperview()
    element = created
    created.delegate = self
    created.presentingViewController = RCTPresentedViewController()

    let view = created.view
    view.translatesAutoresizingMaskIntoConstraints = false
    addSubview(view)
    NSLayoutConstraint.activate([
      view.topAnchor.constraint(equalTo: topAnchor),
      view.leadingAnchor.constraint(equalTo: leadingAnchor),
      view.trailingAnchor.constraint(equalTo: trailingAnchor),
    ])
    elementView = view

    emit("loaded", [:])
    embeddedPaymentElementDidUpdateHeight(embeddedPaymentElement: created)
    embeddedPaymentElementDidUpdatePaymentOption(embeddedPaymentElement: created)
  }

  private func intentConfiguration(from intent: [String: Any]) -> PaymentSheet.IntentConfiguration {
    let amount = (intent["amount"] as? NSNumber)?.intValue ?? 0
    let currency = (intent["currency"] as? String ?? "usd").lowercased()
    let types = intent["paymentMethodTypes"] as? [String]
    return PaymentSheet.IntentConfiguration(
      // Our server makes every intent with automatic_async capture; the element must say the same.
      mode: .payment(
        amount: amount,
        currency: currency,
        setupFutureUsage: nil,
        captureMethod: .automaticAsync,
        paymentMethodOptions: nil
      ),
      paymentMethodTypes: types,
      onBehalfOf: nil,
      confirmationTokenConfirmHandler: { [weak self] confirmationToken in
        guard let self else { throw VezmoElementError(message: "") }
        return try await self.clientSecret(forToken: confirmationToken.stripeId)
      }
    )
  }

  private func configuration(from config: [String: Any]) -> EmbeddedPaymentElement.Configuration {
    var configuration = EmbeddedPaymentElement.Configuration()
    configuration.merchantDisplayName = config["merchantDisplayName"] as? String ?? ""
    if let returnURL = config["returnURL"] as? String, !returnURL.isEmpty {
      configuration.returnURL = returnURL
    }
    // Our own Pay button confirms; the card form only collects details.
    configuration.formSheetAction = .continue
    // Vezmo is white label: no processor wallet of its own in the sheet.
    configuration.link = PaymentSheet.LinkConfiguration(display: .never)
    configuration.embeddedViewDisplaysMandateText = true
    switch config["style"] as? String {
    case "dark": configuration.style = .alwaysDark
    case "light": configuration.style = .alwaysLight
    default: configuration.style = .automatic
    }
    if let applePay = config["applePay"] as? [String: Any],
       let merchantId = applePay["merchantId"] as? String, !merchantId.isEmpty {
      configuration.applePay = PaymentSheet.ApplePayConfiguration(
        merchantId: merchantId,
        merchantCountryCode: applePay["merchantCountryCode"] as? String ?? "US"
      )
    }
    if let billing = config["billing"] as? [String: Any] {
      configuration.defaultBillingDetails.name = billing["name"] as? String
      configuration.defaultBillingDetails.email = billing["email"] as? String
    }
    if let appearance = config["appearance"] as? [String: Any] {
      configuration.appearance = Self.appearance(from: appearance)
    }
    return configuration
  }

  private static func appearance(from params: [String: Any]) -> PaymentSheet.Appearance {
    var appearance = PaymentSheet.Appearance()
    let colors = params["colors"] as? [String: String] ?? [:]
    func color(_ key: String) -> UIColor? { colors[key].flatMap(UIColor.vezmoHex) }
    if let c = color("primary") { appearance.colors.primary = c }
    if let c = color("background") { appearance.colors.background = c }
    if let c = color("componentBackground") { appearance.colors.componentBackground = c }
    if let c = color("componentBorder") { appearance.colors.componentBorder = c }
    if let c = color("componentDivider") { appearance.colors.componentDivider = c }
    if let c = color("text") { appearance.colors.text = c }
    if let c = color("textSecondary") { appearance.colors.textSecondary = c }
    if let c = color("componentText") { appearance.colors.componentText = c }
    if let c = color("placeholderText") { appearance.colors.componentPlaceholderText = c }
    if let c = color("icon") { appearance.colors.icon = c }
    if let c = color("danger") { appearance.colors.danger = c }
    if let radius = params["cornerRadius"] as? NSNumber { appearance.cornerRadius = CGFloat(radius.doubleValue) }
    if let width = params["borderWidth"] as? NSNumber { appearance.borderWidth = CGFloat(width.doubleValue) }
    if let width = params["selectedBorderWidth"] as? NSNumber { appearance.selectedBorderWidth = CGFloat(width.doubleValue) }
    // Methods as separate tiles, the chosen one outlined in the accent (as the sheet's own choices are).
    if let row = params["row"] as? [String: Any] {
      if row["style"] as? String == "floating" { appearance.embeddedPaymentElement.row.style = .floatingButton }
      if let spacing = row["spacing"] as? NSNumber { appearance.embeddedPaymentElement.row.floating.spacing = CGFloat(spacing.doubleValue) }
      if let insets = row["insets"] as? NSNumber { appearance.embeddedPaymentElement.row.additionalInsets = CGFloat(insets.doubleValue) }
    }
    if let family = params["fontFamily"] as? String, let font = UIFont(name: family, size: UIFont.systemFontSize) {
      appearance.font.base = font
    }
    return appearance
  }

  // MARK: Confirmation token → Vezmo → client secret

  private func clientSecret(forToken tokenId: String) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
      DispatchQueue.main.async {
        // A confirm still waiting is superseded by this one.
        self.pendingSecret?.resume(throwing: VezmoElementError(message: ""))
        self.pendingSecret = continuation
        self.emit("confirmationToken", ["id": tokenId])
      }
    }
  }

  // MARK: EmbeddedPaymentElementDelegate

  public func embeddedPaymentElementDidUpdateHeight(embeddedPaymentElement: EmbeddedPaymentElement) {
    let view = embeddedPaymentElement.view
    let height = view.systemLayoutSizeFitting(
      CGSize(width: view.bounds.width > 0 ? view.bounds.width : bounds.width,
             height: UIView.layoutFittingCompressedSize.height)
    ).height
    emit("height", ["height": height])
  }

  public func embeddedPaymentElementDidUpdatePaymentOption(embeddedPaymentElement: EmbeddedPaymentElement) {
    let option = embeddedPaymentElement.paymentOption
    emit("paymentOption", [
      "paymentMethodType": option?.paymentMethodType ?? "",
      "label": option?.label ?? "",
    ])
  }

  // MARK: Helpers

  private func emit(_ name: String, _ body: [String: Any]) {
    if Thread.isMainThread {
      onEvent?(name, body)
    } else {
      DispatchQueue.main.async { self.onEvent?(name, body) }
    }
  }

  private static func parse(_ json: String) -> [String: Any]? {
    guard !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
    return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
  }
}

/// An error the element shows the buyer as is (already in Vezmo's words).
struct VezmoElementError: LocalizedError {
  let message: String
  var errorDescription: String? { message.isEmpty ? "Your payment couldn’t be completed. Please try again." : message }
}

extension UIColor {
  /// `#RRGGBB` or `#RRGGBBAA`.
  static func vezmoHex(_ hex: String) -> UIColor? {
    var s = hex.trimmingCharacters(in: .whitespaces)
    if s.hasPrefix("#") { s.removeFirst() }
    guard s.count == 6 || s.count == 8, let value = UInt64(s, radix: 16) else { return nil }
    let hasAlpha = s.count == 8
    let r = CGFloat((value >> (hasAlpha ? 24 : 16)) & 0xFF) / 255
    let g = CGFloat((value >> (hasAlpha ? 16 : 8)) & 0xFF) / 255
    let b = CGFloat((value >> (hasAlpha ? 8 : 0)) & 0xFF) / 255
    let a = hasAlpha ? CGFloat(value & 0xFF) / 255 : 1
    return UIColor(red: r, green: g, blue: b, alpha: a)
  }
}
