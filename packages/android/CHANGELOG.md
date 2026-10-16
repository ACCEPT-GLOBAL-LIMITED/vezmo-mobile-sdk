# Changelog

## 0.1.0 (unreleased)

The first release of the Vezmo payment sheet for native Android apps (`com.vezmo:vezmo-checkout`).

- **Payment sheet**: your business, what is being paid for and the total; the buyer's own currency where Vezmo offers
  one; light and dark; your colors, corner radius and font through `VezmoAppearance`. The same sheet, copy and
  results as the React Native package.
- **Ways to pay**: cards with 3D Secure (the Card row shows the card's own logo once typed), Google Pay as an express
  button, Klarna, Affirm, Afterpay, Amazon Pay and Cash App in the list, UPI and Pix for buyers paying in rupees or
  reais.
- **Server-side confirmation**: the app sends a confirmation token; Vezmo prices and confirms the payment. No API key
  or secret in the app.
- **Results**: `Succeeded`, `Processing`, `Canceled` or `Failed` with a `VezmoError` code and a message written for
  the buyer. A receipt in the sheet: who was paid, with what, and a reference.
- **API**: `VezmoCheckout(activity, configuration) { result -> }` for activities, `rememberVezmoCheckout` for
  Compose; `prepare(clientToken)` and `present()`.
- Android 7.0+ (API 24), compiled against SDK 36.
