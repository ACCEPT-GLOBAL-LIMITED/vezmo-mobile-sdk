// swift-tools-version: 5.9
// The plugin's iOS side, on Vezmo's native iOS SDK (the `VezmoCheckout` Swift package at the root of
// github.com/ACCEPT-GLOBAL-LIMITED/vezmo-mobile-sdk). Set VEZMO_IOS_SDK_PATH to a checkout of that repository to build
// against it instead (the repository's own CI and development).

import PackageDescription

let localSDK = Context.environment["VEZMO_IOS_SDK_PATH"]
let sdk: Package.Dependency = localSDK.map { .package(name: "VezmoCheckout", path: $0) }
    ?? .package(url: "https://github.com/ACCEPT-GLOBAL-LIMITED/vezmo-mobile-sdk", from: "0.1.0")

let package = Package(
    name: "vezmo_checkout",
    platforms: [
        .iOS("15.1")
    ],
    products: [
        .library(name: "vezmo-checkout", targets: ["vezmo_checkout"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        sdk,
    ],
    targets: [
        .target(
            name: "vezmo_checkout",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "VezmoCheckout", package: localSDK == nil ? "vezmo-mobile-sdk" : "VezmoCheckout"),
            ],
            resources: [
                .process("PrivacyInfo.xcprivacy")
            ]
        )
    ]
)
