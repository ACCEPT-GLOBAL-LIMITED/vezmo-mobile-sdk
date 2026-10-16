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
- **Back from bank apps and pay-later providers**: the buyer returns to the open sheet by themselves; nothing to add to
  your manifest.
- **Subscriptions**: `prepare(subscriptionToken = …)` starts a subscription in the app (the first payment, or a free
  trial with the card saved); the result carries `subscription` (`ref`, `status`), and `paymentId` is null for a free
  trial. The sheet shows what is due today, the plan and the authorization the buyer gives.
- Both business models: sessions of a merchant-of-record business (no connected account) load and pay the same way.
- **Declines in Vezmo's words**: a card declined after 3D Secure shows Vezmo's reason, the same as on iPhone.
- **Large font sizes**: the sheet's buttons, fields and labels grow with the phone's font size instead of clipping.
- Minified (R8) release builds need no extra rules.
- Android 7.0+ (API 24), compiled against SDK 36.
