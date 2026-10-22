// swift-tools-version:5.9
// The Vezmo payment sheet for native iOS apps (Swift Package Manager). The package lives in packages/ios; this file is
// at the repository root because Swift Package Manager only reads a package from there.
import PackageDescription

let package = Package(
  name: "VezmoCheckout",
  platforms: [.iOS("15.1")],
  products: [
    .library(name: "VezmoCheckout", targets: ["VezmoCheckout"]),
  ],
  dependencies: [
    // The native payment engine, pinned exactly (the same version as the React Native package); bumped on purpose.
    .package(url: "https://github.com/stripe/stripe-ios-spm", exact: "26.14.0"),
  ],
  targets: [
    .target(
      name: "VezmoCheckout",
      dependencies: [
        .product(name: "StripePaymentSheet", package: "stripe-ios-spm"),
        // Instant ACH's bank step, drawn natively (without it the engine falls back to a web page).
        .product(name: "StripeFinancialConnections", package: "stripe-ios-spm"),
      ],
      path: "packages/ios/Sources/VezmoCheckout",
      // Apple's privacy manifest: no tracking; what the sheet sends Vezmo to take the payment (see the README).
      resources: [.copy("PrivacyInfo.xcprivacy")]
    ),
    .testTarget(
      name: "VezmoCheckoutTests",
      dependencies: ["VezmoCheckout"],
      path: "packages/ios/Tests/VezmoCheckoutTests"
    ),
  ]
)
