# Changelog

## 0.1.0 (unreleased)

The first release of the Vezmo payment sheet for Flutter apps (`vezmo_checkout`), on Vezmo's native SDKs.

- **API**: `VezmoCheckout(configuration: …)`, `prepare(clientToken: …)` or `prepare(subscriptionToken: …)`, then
  `present()`. Neither throws. Results are a sealed class: `CheckoutSucceeded`, `CheckoutProcessing`,
  `CheckoutCanceled`, `CheckoutFailed` with a `VezmoError` (`VezmoErrorCode` and a message written for the buyer).
  One sheet at a time: `present()` while it is open is a `config` error.
- **Android**: the native Vezmo Android SDK's sheet (`com.vezmo:vezmo-checkout` 0.1.0): cards with 3D Secure, Google
  Pay, Klarna, Affirm, Afterpay, Amazon Pay, Cash App, UPI and Pix, the buyer's own currency, light and dark. The
  buyer comes back from bank apps and pay-later providers by themselves. Needs a `FlutterFragmentActivity`. A
  session prepared before the activity is recreated (a configuration change) is prepared again on the new one, so
  `present()` still opens it.
- **Subscriptions**: `prepare(subscriptionToken: …)` starts a subscription (the first payment, or a free trial with
  the card saved); the result carries `subscription` (`ref`, `status`), and `paymentId` is null for a free trial.
- **Appearance**: your colors (one, or light and dark), corner radius and a native font through `VezmoAppearance`.
- **iPhone**: the native Vezmo iOS SDK's sheet (Swift package `VezmoCheckout` 0.1.0, through Swift Package Manager):
  cards with 3D Secure, Apple Pay, pay-later methods, Cash App, UPI and Pix. The plugin hands links back into the
  app to the sheet by itself. Subscriptions too.
- Android 7.0+ (API 24), iOS 15.1+; Flutter 3.41+.
