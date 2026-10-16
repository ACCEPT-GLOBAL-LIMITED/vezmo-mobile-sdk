# Changelog

## 0.1.0 (unreleased)

The first release of the Vezmo payment sheet for React Native and Expo.

- **Payment sheet**: your business, what is being paid for and the total; the buyer's own currency where Vezmo offers
  one; light and dark; your colors, corner radius and font through `appearance`.
- **Ways to pay**: cards with 3D Secure (the Card row shows the card's own logo once typed), Apple Pay and Google Pay
  as express buttons, Klarna, Affirm, Afterpay, Amazon Pay and Cash App in the list, UPI and Pix for buyers paying in
  rupees or reais.
- **Server-side confirmation**: the app sends a confirmation token; Vezmo prices and confirms the payment. No API key
  or secret in the app.
- **Results**: `succeeded`, `processing`, `canceled` or `failed` with an error code and a message written for the
  buyer. A receipt in the sheet: who was paid, with what, and a reference.
- **Back from bank apps and pay-later providers on iPhone**: the sheet hands the app's return link to the payment
  (Cash App, Klarna, Amazon Pay, 3D Secure in the browser).
- **Expo config plugin** for Apple Pay and the card-scanning camera text.
- **Subscriptions**: `prepareCheckout({ subscriptionToken })` starts a subscription in the app (the first payment,
  or a free trial with the card saved); the result carries `subscription: { ref, status }`. Apple Pay shows it as a
  recurring payment on iPhone.
- **Jest mock** (`@vezmopay/react-native/jest/mock`) so your own tests run without native views or network.
- iOS 15.1+ and Android 7.0+, React Native 0.79+ with the New Architecture.
