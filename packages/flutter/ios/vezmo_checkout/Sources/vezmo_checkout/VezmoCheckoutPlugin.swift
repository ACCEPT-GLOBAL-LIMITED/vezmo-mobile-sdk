import Flutter
import UIKit
import VezmoCheckout

/// Dart's `VezmoCheckout` on iPhone: the native iOS SDK's sheet, presented over the Flutter view, with the result sent
/// back over the `vezmo_checkout` channel. Links back into the app (3D Secure, bank apps, pay-later providers) are
/// handed to the SDK from the app's and the scene's open-URL events.
public final class VezmoCheckoutPlugin: NSObject, FlutterPlugin, FlutterSceneLifeCycleDelegate {
  private weak var registrar: FlutterPluginRegistrar?
  /// The checkout last prepared: `present` opens the session it loaded.
  private var checkout: VezmoCheckout?

  init(registrar: FlutterPluginRegistrar) {
    self.registrar = registrar
  }

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "vezmo_checkout", binaryMessenger: registrar.messenger())
    let instance = VezmoCheckoutPlugin(registrar: registrar)
    registrar.addMethodCallDelegate(instance, channel: channel)
    registrar.addApplicationDelegate(instance)
    registrar.addSceneDelegate(instance)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "prepare":
      prepare(call.arguments as? [String: Any] ?? [:], result: result)
    case "present":
      present(result: result)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func prepare(_ args: [String: Any], result: @escaping FlutterResult) {
    let vezmo = VezmoCheckout(configuration: Self.configuration(args["configuration"] as? [String: Any] ?? [:]))
    checkout = vezmo
    let merchantDisplayName = args["merchantDisplayName"] as? String
    Task { @MainActor in
      // Dart sends exactly one of the two tokens.
      let error: VezmoError?
      if let subscriptionToken = args["subscriptionToken"] as? String {
        error = await vezmo.prepare(subscriptionToken: subscriptionToken, merchantDisplayName: merchantDisplayName)
      } else {
        error = await vezmo.prepare(clientToken: args["clientToken"] as? String ?? "", merchantDisplayName: merchantDisplayName)
      }
      result(error.map { Self.error($0.code, $0.message) })
    }
  }

  private func present(result: @escaping FlutterResult) {
    guard let vezmo = checkout else {
      return result(Self.failed(.config, "Call prepare() before present()."))
    }
    guard let controller = registrar?.viewController else {
      return result(Self.failed(.config, "No screen to show the checkout on."))
    }
    Task { @MainActor in
      switch await vezmo.present(from: controller) {
      case .succeeded(let paymentId, let transactionId, let subscription):
        result([
          "status": "succeeded",
          "paymentId": paymentId as Any? ?? NSNull(),
          "transactionId": transactionId as Any? ?? NSNull(),
          "subscription": Self.map(subscription),
        ])
      case .processing(let paymentId, let subscription):
        result(["status": "processing", "paymentId": paymentId as Any? ?? NSNull(), "subscription": Self.map(subscription)])
      case .canceled:
        result(["status": "canceled"])
      case .failed(let error):
        result(Self.failed(error.code, error.message))
      }
    }
  }

  // MARK: - Links back into the app

  public func application(
    _ application: UIApplication,
    open url: URL,
    options: [UIApplication.OpenURLOptionsKey: Any] = [:]
  ) -> Bool {
    VezmoCheckout.handleURL(url)
  }

  public func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) -> Bool {
    URLContexts.reduce(false) { handled, context in VezmoCheckout.handleURL(context.url) || handled }
  }

  // MARK: - From Dart

  /// The configuration Dart sends (`VezmoConfiguration._toMap`).
  static func configuration(_ map: [String: Any]) -> VezmoCheckout.Configuration {
    var configuration = VezmoCheckout.Configuration(
      returnURL: (map["returnUrl"] as? String).flatMap(URL.init(string:)),
      applePay: (map["applePay"] as? [String: Any]).flatMap { pay in
        (pay["merchantId"] as? String).map {
          VezmoCheckout.ApplePay(merchantId: $0, merchantCountryCode: pay["merchantCountryCode"] as? String ?? "US")
        }
      },
      style: map["style"] as? String == "light" ? .light : map["style"] as? String == "dark" ? .dark : .automatic
    )
    if let base = (map["apiBase"] as? String).flatMap(URL.init(string:)) {
      configuration.apiBase = base
    }
    configuration.sdk = map["sdk"] as? String
    if let appearance = map["appearance"] as? [String: Any] {
      var colors: [VezmoCheckout.Appearance.ColorKey: UIColor] = [:]
      for (key, value) in appearance["colors"] as? [String: [String: NSNumber]] ?? [:] {
        guard let colorKey = VezmoCheckout.Appearance.ColorKey(rawValue: key),
          let light = value["light"].map(color), let dark = value["dark"].map(color)
        else { continue }
        colors[colorKey] = UIColor { $0.userInterfaceStyle == .dark ? dark : light }
      }
      configuration.appearance = VezmoCheckout.Appearance(
        colors: colors,
        cornerRadius: (appearance["cornerRadius"] as? NSNumber).map { CGFloat($0.doubleValue) },
        fontFamily: appearance["fontFamily"] as? String
      )
    }
    return configuration
  }

  /// A Flutter ARGB color (`Color.toARGB32()`).
  private static func color(_ argb: NSNumber) -> UIColor {
    let value = argb.uint32Value
    return UIColor(
      red: CGFloat((value >> 16) & 0xFF) / 255,
      green: CGFloat((value >> 8) & 0xFF) / 255,
      blue: CGFloat(value & 0xFF) / 255,
      alpha: CGFloat((value >> 24) & 0xFF) / 255
    )
  }

  /// The same shape as Dart's `SubscriptionResult.fromJson`.
  private static func map(_ subscription: SubscriptionResult?) -> Any {
    subscription.map { ["ref": $0.ref, "status": $0.status.rawValue] } ?? NSNull()
  }

  private static func error(_ code: VezmoError.Code, _ message: String) -> [String: Any] {
    ["code": code.rawValue, "message": message]
  }

  private static func failed(_ code: VezmoError.Code, _ message: String) -> [String: Any] {
    ["status": "failed", "error": error(code, message)]
  }
}
