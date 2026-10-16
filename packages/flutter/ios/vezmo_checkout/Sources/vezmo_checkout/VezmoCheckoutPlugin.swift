import Flutter
import UIKit

/// Dart's `VezmoCheckout` on iPhone. The sheet comes from Vezmo's native iOS SDK (the `VezmoCheckout` Swift package),
/// which is on its way: until it ships, every call answers with a `config` error.
public class VezmoCheckoutPlugin: NSObject, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "vezmo_checkout", binaryMessenger: registrar.messenger())
    registrar.addMethodCallDelegate(VezmoCheckoutPlugin(), channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let error: [String: Any] = ["code": "config", "message": "iOS support is coming soon."]
    switch call.method {
    case "prepare":
      result(error)
    case "present":
      result(["status": "failed", "error": error])
    default:
      result(FlutterMethodNotImplemented)
    }
  }
}
