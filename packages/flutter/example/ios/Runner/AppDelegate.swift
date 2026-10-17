import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    // The end-to-end runs launch the app with arguments (`-offerCurrency AED`, `-track mor`): Dart asks for them.
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "VezmoExampleLaunch") {
      FlutterMethodChannel(name: "vezmo_example/launch", binaryMessenger: registrar.messenger())
        .setMethodCallHandler { call, result in
          guard call.method == "argument", let name = call.arguments as? String else {
            result(FlutterMethodNotImplemented)
            return
          }
          result(launchArgument(name))
        }
    }
  }
}

/// A launch argument (`-name value`), or nil.
private func launchArgument(_ name: String) -> String? {
  if let value = UserDefaults.standard.string(forKey: name), !value.isEmpty { return value }
  let args = ProcessInfo.processInfo.arguments
  guard let i = args.firstIndex(where: { $0.trimmingCharacters(in: CharacterSet(charactersIn: "-")) == name }),
        i + 1 < args.count else { return nil }
  return args[i + 1]
}
