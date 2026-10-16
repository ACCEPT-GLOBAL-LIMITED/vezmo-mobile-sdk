// swift-tools-version: 5.9
// The plugin's iOS side for Swift Package Manager (Flutter's default). When Vezmo's iOS SDK ships, the target depends
// on its `VezmoCheckout` product:
//   .package(url: "https://github.com/ACCEPT-GLOBAL-LIMITED/vezmo-mobile-sdk", exact: "<version>")
//   .product(name: "VezmoCheckout", package: "vezmo-mobile-sdk")

import PackageDescription

let package = Package(
    name: "vezmo_checkout",
    platforms: [
        .iOS("15.1")
    ],
    products: [
        .library(name: "vezmo-checkout", targets: ["vezmo_checkout"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework")
    ],
    targets: [
        .target(
            name: "vezmo_checkout",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ],
            resources: [
                .process("PrivacyInfo.xcprivacy")
            ]
        )
    ]
)
