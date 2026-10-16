# Vezmo Mobile SDK

Take payments in your iPhone and Android apps with Vezmo: cards, Apple Pay, Google Pay, pay-later methods, Cash App,
UPI and Pix, in the buyer's own currency, with 3D Secure, in a native payment sheet.

| Package | Status |
|---|---|
| [`@vezmopay/react-native`](packages/react-native) (React Native and Expo) | 0.1.0, release candidate |
| iOS (Swift Package) | Planned |
| Android (Maven) | Planned |
| Flutter | Planned |

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
  `react-native-e2e.yml` pays end to end with Maestro on an Android emulator (Pixel 7) and an iPhone simulator,
  including 3D Secure and dark-mode screenshots.
- **TestFlight**: `testflight-example.yml` (run by hand) signs the example app with a temporary distribution
  certificate made through the App Store Connect API key and uploads it; `testflight-status.yml` shows where builds
  are and adds a ready build to the internal testers.

## Releases

Bump `version` in `packages/react-native/package.json`, then push a tag `react-native-v<version>`.
`release-react-native.yml` publishes to npm with provenance (needs the `NPM_TOKEN` secret of the `vezmopay` npm org).

## License

MIT
