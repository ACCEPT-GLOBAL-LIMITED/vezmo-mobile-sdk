# Changelog

## 0.1.0 (unreleased)

The first release of the Vezmo payment sheet for native iOS apps (Swift Package Manager, `VezmoCheckout`).

- **Payment sheet**: your business, what is being paid for and the total; the buyer's own currency where Vezmo offers
  one; light and dark; your colors, corner radius and font through `appearance`. The same screens and design as the
  React Native SDK.
- **Ways to pay**: cards with 3D Secure (the Card row shows the card's own logo once typed), Apple Pay as an express
  button, Klarna, Affirm, Afterpay, Amazon Pay and Cash App in the list, UPI and Pix for buyers paying in rupees or
  reais.
- **Server-side confirmation**: the app sends a confirmation token; Vezmo prices and confirms the payment. No API key
  or secret in the app.
- **Results**: `.succeeded`, `.processing`, `.canceled` or `.failed` with an error code and a message written for the
  buyer. A receipt in the sheet: who was paid, with what, and a reference.
- **UIKit and SwiftUI**: `prepare(clientToken:)` and `present(from:)` with async/await, the `.vezmoCheckout` view
  modifier, and `VezmoCheckout.handleURL` for links back into the app.
- iOS 15.1+.
