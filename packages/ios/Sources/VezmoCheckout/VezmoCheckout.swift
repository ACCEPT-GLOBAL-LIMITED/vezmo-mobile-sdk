import StripePaymentSheet
import SwiftUI
import UIKit

/// The Vezmo payment sheet. Create one with your settings, `prepare` it with a session's client token from your
/// server, then `present` it. Neither throws.
///
///     let vezmo = VezmoCheckout(configuration: .init(returnURL: URL(string: "acme://vezmo")))
///     if let error = await vezmo.prepare(clientToken: token) { show(error.message); return }
///     switch await vezmo.present(from: viewController) { … }
public final class VezmoCheckout {
  /// This SDK's version (sent to Vezmo with each call, for support).
  public static let version = "0.1.0"

  public let configuration: Configuration
  private var prepared: (session: CheckoutSession, merchantDisplayName: String?)?
  private var presenting = false

  public init(configuration: Configuration = Configuration()) {
    self.configuration = configuration
  }

  /// Loads the session: call it as soon as you have the client token. Returns an error (for example an expired
  /// session) or nil when the sheet is ready to present.
  /// - Parameters:
  ///   - clientToken: From your server: the API checkout session's `clientToken`.
  ///   - merchantDisplayName: Shown at the top of the sheet. Defaults to the business name on the session.
  @MainActor
  public func prepare(clientToken: String, merchantDisplayName: String? = nil) async -> VezmoError? {
    let token = clientToken.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !token.isEmpty else { return VezmoError(code: .config, message: "clientToken is required.") }
    let api = VezmoAPI(base: configuration.apiBase)
    let session = CheckoutSession(api: api, token: token, returnURL: configuration.returnURL?.absoluteString)
    await session.load()
    if case .closed(.failed(let error)) = session.snapshot.phase {
      prepared = nil
      return error
    }
    prepared = (session, merchantDisplayName)
    return nil
  }

  /// Opens the sheet over `viewController` (or whatever it presents) and returns when the buyer is done.
  @MainActor
  public func present(from viewController: UIViewController) async -> CheckoutResult {
    guard let next = prepared else {
      return .failed(VezmoError(code: .config, message: "Call prepare(clientToken:) before present(from:)."))
    }
    guard !presenting else {
      return .failed(VezmoError(code: .config, message: "The checkout is already open."))
    }
    var top = viewController
    while let presented = top.presentedViewController, !presented.isBeingDismissed { top = presented }
    presenting = true
    let result = await withCheckedContinuation { (done: CheckedContinuation<CheckoutResult, Never>) in
      let sheet = SheetController(
        session: next.session,
        merchantDisplayName: next.merchantDisplayName,
        configuration: configuration,
        dark: top.traitCollection.userInterfaceStyle == .dark
      ) { result in done.resume(returning: result) }
      top.present(sheet, animated: false)
    }
    presenting = false
    prepared = nil
    return result
  }

  /// Hands the app a link back from 3D Secure, a bank app or a pay-later provider. Call it from `onOpenURL` or your
  /// scene delegate's `scene(_:openURLContexts:)`; returns true when the link was Vezmo's.
  @discardableResult
  public static func handleURL(_ url: URL) -> Bool {
    StripeAPI.handleURLCallback(with: url)
  }
}

extension VezmoCheckout {
  public struct Configuration {
    /// The link that opens your app after a bank app, a pay-later provider or 3D Secure, registered under
    /// Developers → Mobile apps (for example `acme://vezmo`). Required for redirect-based methods.
    public var returnURL: URL?
    /// Apple Pay with your Apple Merchant ID. Without it the sheet shows no Apple Pay.
    public var applePay: ApplePay?
    /// Light, dark, or follow the phone (default).
    public var style: Style
    public var appearance: Appearance?
    /// Vezmo's API. Leave as is; for Vezmo's own testing only.
    public var apiBase: URL

    public init(
      returnURL: URL? = nil,
      applePay: ApplePay? = nil,
      style: Style = .automatic,
      appearance: Appearance? = nil,
      apiBase: URL = URL(string: "https://api.vezmo.com/api/v1")!
    ) {
      self.returnURL = returnURL
      self.applePay = applePay
      self.style = style
      self.appearance = appearance
      self.apiBase = apiBase
    }
  }

  public struct ApplePay: Equatable {
    /// Your Apple Merchant ID (Apple Developer → Identifiers → Merchant IDs).
    public var merchantId: String
    public var merchantCountryCode: String

    public init(merchantId: String, merchantCountryCode: String = "US") {
      self.merchantId = merchantId
      self.merchantCountryCode = merchantCountryCode
    }
  }

  public enum Style {
    case automatic, light, dark
  }

  /// What you may change in the sheet's look.
  public struct Appearance {
    /// The sheet's colour tokens.
    public enum ColorKey: String, CaseIterable {
      case page, surface, surface2, raised, fill, disabled, onDisabled, field, fieldLine, line
      case ink, ink2, ink3, accent, onAccent, accentSoft, good, bad, badSoft, warn, warnInk, warnSoft, logoTile, scrim
    }

    /// Colours by token. A dynamic `UIColor` (or one from an asset catalog) can differ between light and dark.
    /// Setting your own `.accent` replaces the Vezmo gradient on the Pay button.
    public var colors: [ColorKey: UIColor]
    /// Corner radius of fields and payment method rows, in points. Default 14.
    public var cornerRadius: CGFloat?
    /// A font already in your app: its PostScript or family name.
    public var fontFamily: String?

    public init(colors: [ColorKey: UIColor] = [:], cornerRadius: CGFloat? = nil, fontFamily: String? = nil) {
      self.colors = colors
      self.cornerRadius = cornerRadius
      self.fontFamily = fontFamily
    }
  }
}

// MARK: - SwiftUI

extension View {
  /// Presents the prepared Vezmo sheet while `isPresented` is true; `onCompletion` gets the result and
  /// `isPresented` goes back to false.
  public func vezmoCheckout(
    _ checkout: VezmoCheckout,
    isPresented: Binding<Bool>,
    onCompletion: @escaping (CheckoutResult) -> Void
  ) -> some View {
    modifier(VezmoCheckoutPresenter(checkout: checkout, isPresented: isPresented, onCompletion: onCompletion))
  }
}

private struct VezmoCheckoutPresenter: ViewModifier {
  let checkout: VezmoCheckout
  @Binding var isPresented: Bool
  let onCompletion: (CheckoutResult) -> Void

  func body(content: Content) -> some View {
    content
      .onAppear { if isPresented { open() } }
      .onChange(of: isPresented) { shown in if shown { open() } }
  }

  private func open() {
    Task { @MainActor in
      guard let root = UIApplication.shared.connectedScenes
        .compactMap({ ($0 as? UIWindowScene)?.windows.first(where: \.isKeyWindow) })
        .first?.rootViewController else {
        isPresented = false
        onCompletion(.failed(VezmoError(code: .config, message: "No window to present the checkout from.")))
        return
      }
      let result = await checkout.present(from: root)
      isPresented = false
      onCompletion(result)
    }
  }
}
