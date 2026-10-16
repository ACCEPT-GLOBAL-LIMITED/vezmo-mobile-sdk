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

1. Your server creates an API checkout session with your Vezmo API key and passes its `clientToken` to the app.
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

- **Example app** (`packages/react-native/example`): a small shop that pays against Vezmo's sandbox. Run
  `yarn example expo run:ios` or `yarn example expo run:android` with `EXPO_PUBLIC_VEZMO_DEMO_URL` and
  `EXPO_PUBLIC_VEZMO_API_BASE` set to the dev API.
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

- **React Native**: bump `version` in `packages/react-native/package.json`, then push a tag `react-native-v<version>`.
  `release-react-native.yml` publishes to npm with provenance (the `NPM_TOKEN` secret of the `vezmopay` npm org).
- **iOS**: Swift Package Manager reads plain semver tags at the repository root: push `<version>` (for example `0.1.0`)
  after bumping `VezmoCheckout.version` in `packages/ios/Sources/VezmoCheckout`.
- **Flutter**: bump `version` in `packages/flutter/pubspec.yaml`, then push a tag `flutter-v<version>`;
  `release-flutter.yml` publishes to pub.dev with GitHub's login (the one-time pub.dev setup is in that file's header).
- **Android**: bump the version in `packages/android/gradle.properties`, then push a tag `android-v<version>`.
  `release-android.yml` publishes to Maven Central; it needs `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
  `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` (a Central Portal account with the `com.vezmo` namespace verified).

## License

MIT
