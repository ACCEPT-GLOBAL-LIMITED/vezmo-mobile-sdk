# Vezmo Mobile SDK

Take payments in your iPhone and Android apps with Vezmo: cards, Apple Pay, Google Pay, pay-later methods, Cash App,
UPI and Pix, in the buyer's own currency, with 3D Secure, in a native payment sheet.

| Package | Status |
|---|---|
| `@vezmo/react-native` (React Native and Expo) | In development |
| iOS (Swift Package) | Planned |
| Android (Maven) | Planned |
| Flutter | Planned |

Until the SDK ships, apps can use [Checkout for apps](https://vezmo.com/docs/v1/mobile/checkout-for-apps): open
Vezmo's hosted checkout in the phone's secure browser sheet and get the buyer back with the result.

## How a payment works

1. Your server creates an API checkout session with your Vezmo API key and passes its `clientToken` to the app.
2. The app opens the Vezmo payment sheet with that token.
3. The buyer pays. Vezmo's servers price and confirm the payment; your app gets the result.
4. Your server fulfils the order from the `payment.success` webhook.

No API key or secret ever goes into the app.
