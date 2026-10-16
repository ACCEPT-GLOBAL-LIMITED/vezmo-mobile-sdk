# Vezmo Mobile SDK

Take payments in your iPhone and Android apps with Vezmo: cards, Apple Pay, Google Pay, pay-later methods, Cash App,
UPI and Pix, in the buyer's own currency, with 3D Secure, in a native payment sheet. Start subscriptions too: a free
trial or a first payment, with the card saved for the renewals.

| Package | Status |
|---|---|
| [`@vezmopay/react-native`](packages/react-native) (React Native and Expo) | 0.1.0, release candidate (payments and subscriptions) |
| [Android, `com.vezmo:vezmo-checkout`](packages/android) (Kotlin, Compose or Views) | 0.1.0, release candidate (payments and subscriptions) |
| [iOS, Swift Package `VezmoCheckout`](packages/ios) (SwiftUI or UIKit) | 0.1.0, release candidate (payments and subscriptions) |
| [Flutter, `vezmo_checkout`](packages/flutter) (on the native SDKs) | 0.1.0, release candidate (payments and subscriptions) |

Apps without the SDK can use [Checkout for apps](https://user.vezmo.com/docs/v1/mobile/checkout-for-apps): open
Vezmo's hosted checkout in the phone's secure browser sheet and get the buyer back with the result.

## How a payment works

1. Your server creates an API checkout session with your Vezmo API key and passes its client token
   (`data.securePayment.clientToken`) to the app.
2. The app opens the Vezmo payment sheet with that token.
3. The buyer pays. Vezmo's servers price and confirm the payment; your app gets the result.
4. Your server fulfils the order from the `payment.success` webhook.

No API key or secret ever goes into the app.

## Development

```sh
cd packages/react-native
node .yarn/releases/yarn-4.11.0.cjs install
node .yarn/releases/yarn-4.11.0.cjs tsc     # types
node .yarn/releases/yarn-4.11.0.cjs jest    # unit tests
node .yarn/releases/yarn-4.11.0.cjs prepare # builds lib/
```

- **Example app** (`packages/react-native/example`): a small shop that pays against Vezmo's dev sandbox. Run
  `yarn example expo run:ios` or `yarn example expo run:android`; `EXPO_PUBLIC_VEZMO_DEMO_URL` and
  `EXPO_PUBLIC_VEZMO_API_BASE` point it at another API.
- **CI** (`.github/workflows`): `react-native.yml` checks types, tests and both native builds on every push;
  `react-native-e2e.yml` pays end to end with Maestro on an Android emulator (Pixel 7) and an iPhone simulator: cards
  with 3D Secure, a decline, cancel, a bad token, both business models, a Cash App redirect back into the app,
  subscriptions with and without a free trial, and dark mode. `android.yml` and `ios.yml` run the native SDKs' unit
  and Maestro tests (Android also screenshot tests); `flutter.yml` runs the Flutter plugin's tests and its example
  app on an Android emulator and an iPhone simulator.
- **TestFlight**: `testflight-example.yml` (run by hand) signs the example app with a temporary distribution
  certificate made through the App Store Connect API key and uploads it; `testflight-status.yml` shows where builds
  are and adds a ready build to the internal testers.

## Releases

Release in this order (each tag runs its own workflow):

1. **Android**: push `android-v<version>`. `release-android.yml` signs and uploads to Maven Central (needs
   `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_KEY_PASSWORD`: a Central Portal
   account with the `com.vezmo` namespace verified); then release the validated upload in the Central Portal.
2. **iOS**: push the plain semver tag `<version>` (for example `0.1.0`) at the repository root; Swift Package Manager
   reads it directly. `release-ios.yml` checks that it equals `VezmoCheckout.version`.
3. **React Native**: push `react-native-v<version>`. `release-react-native.yml` publishes to npm with provenance (the
   `NPM_TOKEN` secret of the `vezmopay` npm org).
4. **Flutter**, last: push `flutter-v<version>`. The plugin runs on the native SDKs, so it needs 1 and 2 out first;
   `release-flutter.yml` checks both, then publishes to pub.dev with GitHub's login (the one-time pub.dev setup is in
   that file's header).

Bump every place a version lives (and each package's `CHANGELOG.md`) before tagging:

| Package | Version |
| --- | --- |
| React Native | `packages/react-native/package.json` and `packages/react-native/src/version.ts` |
| iOS | `VezmoCheckout.version` in `packages/ios/Sources/VezmoCheckout/VezmoCheckout.swift` |
| Android | `VERSION_NAME` in `packages/android/gradle.properties` |
| Flutter | `packages/flutter/pubspec.yaml`, `VezmoCheckout.version` in `packages/flutter/lib/vezmo_checkout.dart`, `version` and the `com.vezmo:vezmo-checkout` pin in `packages/flutter/android/build.gradle.kts`, and the iOS SDK pin in `packages/flutter/ios/vezmo_checkout/Package.swift` |

The READMEs link to the guides on user.vezmo.com: make sure those docs are live before publishing.

## License

MIT
