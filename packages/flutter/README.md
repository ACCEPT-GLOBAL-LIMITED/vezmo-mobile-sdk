# vezmo_checkout

The Vezmo payment sheet for Flutter apps. Cards with 3D Secure, Google Pay, Apple Pay, Klarna, Affirm, Afterpay,
Amazon Pay, Cash App, UPI and Pix, in the buyer's own currency, in a native sheet. Payments and subscriptions.

Your server keeps your Vezmo API key. The app only ever receives a session's client token.

The sheet is drawn by Vezmo's native SDKs, so it looks and behaves exactly like the sheet in a native app.

## Requirements

- Flutter 3.41 or later (Dart 3.11).
- **Android**: Android 7.0 (API 24) or later, `compileSdk` 36, Kotlin 2.1 or later, Java 17. Your `MainActivity`
  extends `FlutterFragmentActivity` (below).
- **iPhone**: iOS 15.1 or later, Xcode 16 or later, with Swift Package Manager (Flutter's default). Subscriptions
  on iPhone are coming soon: until then `prepare(subscriptionToken: …)` answers with a `config` error there.
- Your app added in the Vezmo dashboard under **Developers → Mobile apps**, with its return link (for example
  `acme://vezmo`).

## Install

```sh
flutter pub add vezmo_checkout
```

### Android

The sheet reports its result through your activity, so `MainActivity` must be a `FlutterFragmentActivity` (as with
other payment plugins). In `android/app/src/main/kotlin/…/MainActivity.kt`:

```kotlin
import io.flutter.embedding.android.FlutterFragmentActivity

class MainActivity : FlutterFragmentActivity()
```

In `android/app/src/main/AndroidManifest.xml`, remove `android:taskAffinity=""` from `MainActivity` if it is there
(new Flutter apps have it). With it, the buyer can't come back from a bank app, Cash App or a pay-later provider to
the open sheet.

And in `android/app/build.gradle.kts`, at least Android 7.0:

```kotlin
android {
    defaultConfig {
        minSdk = 24
    }
}
```

Nothing else to set up: after a bank app, a pay-later provider or 3D Secure in the browser, the buyer comes back to
the open sheet by themselves.

### iPhone

The plugin uses Vezmo's iOS SDK through Swift Package Manager, which Flutter turns on by default (if your app turned
it off: `flutter config --enable-swift-package-manager`). Set the iOS version to at least 15.1 (in Xcode, Runner →
General → Minimum Deployments).

In `ios/Runner/Info.plist`, add your return link's scheme (`acme` for `acme://vezmo`) and the camera text used to
scan a card:

```xml
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleURLSchemes</key>
    <array><string>acme</string></array>
  </dict>
</array>
<key>NSCameraUsageDescription</key>
<string>Scan your card to fill in its number.</string>
```

The plugin hands the links that come back to the app (3D Secure, bank apps, pay-later providers) to the sheet by
itself.

## Take a payment

Your server creates the session with your API key (the same API checkout session as on the web) and sends its
`clientToken` to the app:

```http
POST /v1/merchant/secure-payments
{
  "title": "Order #1042",
  "amount": 49.00,
  "currency": "USD",
  "client": { "name": "Ana Silva", "email": "ana@example.com", "country": "US", "postalCode": "10001" },
  "app": { "returnUrl": "acme://vezmo" }
}
```

In the app, create one `VezmoCheckout` for the screens that take payments:

```dart
import 'package:vezmo_checkout/vezmo_checkout.dart';

final vezmo = VezmoCheckout(
  configuration: const VezmoConfiguration(
    returnUrl: 'acme://vezmo', // registered under Developers → Mobile apps
    applePay: ApplePayOptions(merchantId: 'merchant.com.acme'), // Apple Pay shows only with your Merchant ID
  ),
);

Future<void> pay(String orderId) async {
  final clientToken = await myApi.createVezmoSession(orderId);

  // Loads the session; call it as soon as you have the token.
  final error = await vezmo.prepare(clientToken: clientToken);
  if (error != null) return showError(error.message);

  // Opens the sheet and completes when the buyer is done. Never throws.
  switch (await vezmo.present()) {
    case CheckoutSucceeded(:final paymentId):
      showPaid(paymentId);
    case CheckoutProcessing():
      showPaymentReceived(); // a bank debit, UPI or Pix still settling
    case CheckoutCanceled():
      break; // the buyer closed the sheet
    case CheckoutFailed(:final error):
      showError(error.message);
  }
}
```

Neither `prepare()` nor `present()` throws. Fulfil the order from the `payment.success` webhook, not from the result
alone. The result tells your app what to show.

| `VezmoConfiguration` | What it does |
| --- | --- |
| `returnUrl` | Your registered return link. On iPhone it brings the buyer back after 3D Secure, a bank app or a pay-later provider; Android needs no link. |
| `applePay` | `ApplePayOptions(merchantId: …, merchantCountryCode: 'US')`. Without it the sheet shows no Apple Pay. |
| `googlePay` | `GooglePayOptions(merchantCountryCode: 'US')`. Google Pay shows when your business offers it and the phone has it. |
| `style` | `VezmoStyle.automatic` (follow the phone, default), `light` or `dark`. |
| `appearance` | Colors, corner radius and font, below. |

### Results and errors

`present()` completes with one of four results (a sealed class, so a `switch` covers them all):

| Result | Meaning |
| --- | --- |
| `CheckoutSucceeded` | Paid. `paymentId` and `transactionId` match the dashboard and the webhook. A subscription also has `subscription` (`ref`, `status`); `paymentId` is null when a free trial started. |
| `CheckoutProcessing` | Paid, money on its way (a bank debit, UPI or Pix still settling). Wait for the webhook. |
| `CheckoutCanceled` | The buyer closed the sheet (or pressed Back). Not an error. |
| `CheckoutFailed` | `error.code` is one of the codes below; `error.message` is written for the buyer. |

| `VezmoErrorCode` | What to do |
| --- | --- |
| `declined` | The bank said no. The buyer can try another card or method. |
| `expired` | The session ran out. Create a new one. |
| `alreadyPaid` | The session was already paid. Treat the order as paid. |
| `notAvailable` | This way to pay can't be used for this session. |
| `network` | No connection. Let the buyer try again. |
| `config` | A setup mistake in the app (for example `present()` before `prepare()`). |
| `unknown` | Anything else. Let the buyer try again. |

`prepare()` returns the same `VezmoError` when the session can't be paid (expired, already paid, a bad token).
`result.toJson()` gives the result in the same JSON shape as Vezmo's React Native package, for your logs.

## Subscriptions

Start a subscription in the app: the first payment, or a free trial with the card saved for the renewals. Your server
creates the subscription with your API key and sends its `checkoutToken` to the app:

```http
POST /v1/merchant/subscription
{ "planId": "plan_…", "customerEmail": "ana@example.com", "trialDays": 7 }
→ { "subscriptionRef": "SUB-7Q2K9WX", "status": "TRIALING", "checkoutToken": "…" }
```

In the app, pass it as `subscriptionToken`; everything else is the same:

```dart
final error = await vezmo.prepare(subscriptionToken: checkoutToken);
if (error != null) return showError(error.message);
final result = await vezmo.present();
if (result case CheckoutSucceeded(:final paymentId, :final subscription?)) {
  // subscription.ref: 'SUB-7Q2K9WX'; subscription.status: SubscriptionStatus.trialing or .active
  // paymentId is the first payment, or null for a free trial (nothing charged today)
}
```

The sheet shows what is due today, the plan after it ("7-day free trial, then $9.99 / month from Oct 23, 2026"), the
authorization the buyer gives, and **Start free trial** or **Subscribe**. Cards and Google Pay. Renewals are charged
by Vezmo; your server hears about each one from the subscription webhooks.

## What the buyer sees

- Your business, what they're paying for and the total, with a choice of their own currency where Vezmo offers one.
- Google Pay (or Apple Pay) as one tap at the top, when the phone has it.
- The other ways to pay as one list: **Card** (with the card's own logo once typed), pay-later methods, Cash App, and
  UPI or Pix for buyers in India or Brazil.
- One Pay button, 3D Secure when the bank asks for it, and a receipt: who was paid, with what, and a reference.

## Appearance

The sheet follows Vezmo's checkout design in light and dark. You can bring it closer to your app:

```dart
const VezmoConfiguration(
  returnUrl: 'acme://vezmo',
  appearance: VezmoAppearance(
    colors: {
      VezmoColorKey.accent: VezmoColor(Color(0xFF0A84FF)),
      VezmoColorKey.surface: VezmoColor(Color(0xFFFFFFFF), dark: Color(0xFF1C1C1E)),
    },
    cornerRadius: 12,
    fontFamily: 'inter',
  ),
)
```

The main color keys: `accent`, `onAccent`, `surface`, `surface2`, `field`, `fieldLine`, `line`, `ink`, `ink2`, `ink3`,
`good`, `bad`. Each takes one color, or one for light and one for dark. Setting your own `accent` replaces the Vezmo
gradient on the Pay button.

The sheet is native, so `fontFamily` names a font on the native side, not a Flutter asset: on Android a font resource
(`android/app/src/main/res/font/inter.ttf` → `'inter'`; keep it in release builds with
`tools:keep="@font/inter"` in `res/raw/keep.xml`), on iPhone the font's PostScript or family name.

## Google Pay

Google Pay needs no code: the plugin turns on the Google Pay API in your app's manifest, and the button shows when
your business offers Google Pay and the phone has it. In the sandbox it uses Google's test environment and the cards
in the phone's Google Wallet, without charging them. Before your app takes live Google Pay payments, ask Google for
production access in the **Google Pay & Wallet Console**: add your app, choose the **gateway** integration when asked
how you take payments, and send the screenshots of your checkout that Google asks for.

## Apple Pay

1. In your Apple Developer account, create a **Merchant ID** and turn on **Apple Pay Payment Processing** for your
   app's App ID with it. In Xcode, add the **Apple Pay** capability to the Runner target with that Merchant ID.
2. Write to Vezmo support with your Merchant ID. We send you a certificate signing request; create the **Apple Pay
   Payment Processing Certificate** with it and send us the certificate.
3. Pass the Merchant ID as `applePay: ApplePayOptions(merchantId: 'merchant.com.acme')`.

## Testing

Create the session with your sandbox API key. Pay with the test card `4242 4242 4242 4242`, any future expiry and any
CVC; 3D Secure shows a test page where you can approve or fail the payment. `4000 0000 0000 0002` is declined.

The [example app](https://github.com/ACCEPT-GLOBAL-LIMITED/vezmo-mobile-sdk/tree/main/packages/flutter/example) pays
against Vezmo's sandbox with no setup: `cd example && flutter run`. In the sandbox, Apple Pay and Google Pay use the
cards in the phone's wallet without charging them.

In your own widget tests, there is no native side: `prepare()` returns a `config` error. Put `VezmoCheckout` behind
your own interface, or answer the `vezmo_checkout` method channel with
`TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler`: `prepare` answers
`null` (ready) and `present` answers a result as `CheckoutResult.toJson()` writes it.

## Store rules

Apple and Google require their own in-app purchase for digital goods and services used inside the app (subscriptions
to app features or content, in-game items). Use Vezmo for real-world goods and services: deliveries, memberships,
classes, rentals, services.

## Troubleshooting

- **The `config` error "Vezmo checkout needs a FlutterFragmentActivity"**: your `MainActivity` extends
  `FlutterActivity`. Make it extend `FlutterFragmentActivity`.
- **The `config` error "Call prepare() before present()"**: `present()` needs a successful `prepare()` first, each
  time, on the same `VezmoCheckout`.
- **After Cash App, a bank app or a pay-later provider the buyer stays in the browser**: remove
  `android:taskAffinity=""` from `MainActivity` in `AndroidManifest.xml`.
- **No Google Pay**: the business doesn't offer it yet, or the phone has no Google Wallet card.
- **No Apple Pay**: no `applePay` in the configuration, the Apple Pay capability is missing on the Runner target, no
  card in the phone's Wallet, or the Apple Pay certificate isn't set up yet.
- **iOS build: "plugin vezmo_checkout does not support CocoaPods"**: turn Swift Package Manager back on with
  `flutter config --enable-swift-package-manager`.

## Development

```sh
cd packages/flutter
flutter analyze && flutter test
cd example && flutter run
```

Until the Android SDK is on Maven Central, build it into Maven local first: `gradle -p packages/android
:checkout:publishToMavenLocal` (Gradle 8.14, JDK 17).

To build the iPhone side against this repository's iOS SDK rather than a release, set `VEZMO_IOS_SDK_PATH` to the
repository's root: `VEZMO_IOS_SDK_PATH=$(git rev-parse --show-toplevel) flutter build ios --simulator`.

CI (`.github/workflows/flutter.yml`) runs the analysis and tests, then pays end to end from the example app with
Maestro (`e2e/maestro`). On an Android emulator: a card with 3D Secure, a decline, cancel, a bad token, Cash App on
the merchant-of-record track, and a subscription's free trial and its first payment. On an iPhone simulator: a card
with 3D Secure on both business models, a decline, cancel, a bad token and Cash App.

To release, set `version` in `pubspec.yaml` and the `CHANGELOG.md` entry, then push a tag `flutter-v<version>`.
`release-flutter.yml` publishes to pub.dev from GitHub Actions, with no stored secret.

## License

MIT
